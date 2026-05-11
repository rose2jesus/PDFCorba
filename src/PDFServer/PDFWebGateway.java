package PDFServer;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URLDecoder;
import java.util.*;
import PDFApp.*;
import org.omg.CosNaming.*;
import org.omg.CORBA.*;
import java.net.InetSocketAddress;

public class PDFWebGateway {
    private static PDFService pdfRef;
    
    // Stockage en mémoire vive (Attention: s'efface au redémarrage de Render)
    private static final Map<String, String> SESSIONS = new HashMap<>(); // SID -> Username
    private static final Map<String, String> USERS = new HashMap<>();    // Username -> Password
    private static final Map<String, String> FULL_NAMES = new HashMap<>(); // Username -> Prénom Nom
    private static final Map<String, String> ROLES = new HashMap<>();    // Username -> Role

    static {
        // Compte Administrateur par défaut
        USERS.put("admin", "admin123");
        FULL_NAMES.put("admin", "Administrateur Système");
        ROLES.put("admin", "admin");
    }

    public static void main(String[] args) throws Exception {
        try {
            System.out.println("Démarrage de la Gateway...");

            // Initialisation CORBA optimisée pour Render
            Properties props = new Properties();
            props.put("org.omg.CORBA.ORBInitialPort", "1050");
            props.put("org.omg.CORBA.ORBInitialHost", "127.0.0.1");
            ORB orb = ORB.init(args, props);

            // Boucle de connexion pour attendre que le StartServer soit prêt
            int tentatives = 0;
            while (pdfRef == null && tentatives < 25) {
                try {
                    org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
                    NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
                    pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));
                    System.out.println(">>> SUCCESS : Connecté au PDFService !");
                } catch (Exception e) {
                    tentatives++;
                    System.out.println("Attente du serveur (Tentative " + tentatives + "/25)...");
                    Thread.sleep(5000);
                }
            }

            if (pdfRef == null) {
                System.err.println("ERREUR : Serveur CORBA introuvable.");
                System.exit(1);
            }

            // Port dynamique injecté par Render
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

            // Définition des accès (Routes)
            server.createContext("/", new UIHandler());
            server.createContext("/login", new LoginHandler());
            server.createContext("/register", new RegisterHandler());
            server.createContext("/logout", t -> {
                SESSIONS.remove(getSession(t));
                t.getResponseHeaders().set("Set-Cookie", "session=; Path=/; Max-Age=0");
                redirect(t, "/login");
            });

            server.start();
            System.out.println("Serveur Web opérationnel sur le port " + port);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ── GESTIONNAIRE D'INSCRIPTION (Nom, Prénom, Username) ──
    static class RegisterHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody()), "UTF-8"));
                String username = p.get("username");
                String password = p.get("password");
                String fullname = p.get("prenom") + " " + p.get("nom");

                if (username != null && !username.isEmpty() && !USERS.containsKey(username)) {
                    USERS.put(username, password);
                    FULL_NAMES.put(username, fullname);
                    ROLES.put(username, "user");
                    redirect(t, "/login?status=success");
                } else {
                    sendHtml(t, authPage("Ce nom d'utilisateur est déjà pris.", false));
                }
            } else {
                sendHtml(t, authPage(null, false));
            }
        }
    }

    // ── GESTIONNAIRE DE CONNEXION (Username, Password) ──
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody()), "UTF-8"));
                String username = p.get("username");
                String password = p.get("password");

                if (USERS.containsKey(username) && USERS.get(username).equals(password)) {
                    String sid = UUID.randomUUID().toString();
                    SESSIONS.put(sid, username);
                    t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                    redirect(t, "/");
                } else {
                    sendHtml(t, authPage("Identifiants incorrects.", true));
                }
            } else {
                sendHtml(t, authPage(null, true));
            }
        }
    }

    // ── INTERFACE UTILISATEUR (UI) ──
    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String user = SESSIONS.get(getSession(t));
            if (user == null) { redirect(t, "/login"); return; }
            
            String name = FULL_NAMES.get(user);
            String role = ROLES.get(user);

            String html = "<html><head><meta charset='UTF-8'><style>" + CSS_APP + "</style></head><body>"
                + "<div class='nav'><b>Studio PDF CORBA</b> <span>Bienvenue, " + name + " (" + role + ")</span> <a href='/logout'>Déconnexion</a></div>"
                + "<div class='container'><h2>Vos Outils PDF</h2><div class='grid'>"
                + tool("Extraire Texte", "Récupérer le texte d'un document")
                + tool("Fusionner PDF", "Assembler plusieurs fichiers")
                + tool("Générer QR Code", "Ajouter un code QR au PDF")
                + tool("Protéger", "Ajouter un mot de passe")
                + "</div></div></body></html>";
            sendHtml(t, html);
        }
        String tool(String t, String d) { return "<div class='card'><h3>"+t+"</h3><p>"+d+"</p><button>Lancer</button></div>"; }
    }

    // ── PAGE AUTHENTIFICATION (Générique) ──
    static String authPage(String err, boolean isLogin) {
        String title = isLogin ? "Connexion" : "Inscription";
        StringBuilder f = new StringBuilder();
        f.append("<html><head><meta charset='UTF-8'><style>"+CSS_AUTH+"</style></head><body><div class='box'><h2>"+title+"</h2>");
        if(err != null) f.append("<p style='color:red;font-size:12px'>"+err+"</p>");
        f.append("<form method='POST'>");
        if(!isLogin) {
            f.append("<input name='prenom' placeholder='Prénom' required>");
            f.append("<input name='nom' placeholder='Nom' required>");
        }
        f.append("<input name='username' placeholder=\"Nom d'utilisateur\" required>");
        f.append("<input name='password' type='password' placeholder='Mot de passe' required>");
        f.append("<button type='submit'>"+(isLogin?"Se connecter":"S'inscrire")+"</button></form>");
        f.append("<a href='"+(isLogin?"/register":"/login")+"'>"+(isLogin?"Créer un compte":"Déjà inscrit ?")+"</a></div></body></html>");
        return f.toString();
    }

    // ── STYLES ET UTILITAIRES ──
    static String CSS_AUTH = "body{background:#4F1D96;display:flex;justify-content:center;align-items:center;height:100vh;font-family:sans-serif}.box{background:white;padding:40px;border-radius:15px;width:320px;text-align:center}input{width:100%;padding:12px;margin:8px 0;border:1px solid #ddd;border-radius:8px}button{width:100%;padding:12px;background:#6D28D9;color:white;border:none;border-radius:8px;cursor:pointer;font-weight:bold;margin-top:10px}a{display:block;margin-top:15px;color:#6D28D9;text-decoration:none;font-size:13px}";
    static String CSS_APP = "body{font-family:sans-serif;background:#F3F4F6;margin:0}.nav{background:#4F1D96;color:white;padding:15px 40px;display:flex;justify-content:space-between;align-items:center}.container{padding:40px;max-width:1000px;margin:auto}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:20px}.card{background:white;padding:25px;border-radius:12px;box-shadow:0 2px 5px rgba(0,0,0,0.1);text-align:center}";

    static void redirect(HttpExchange t, String u) throws IOException { t.getResponseHeaders().set("Location", u); t.sendResponseHeaders(302, -1); }
    static void sendHtml(HttpExchange t, String h) throws IOException { byte[] b=h.getBytes("UTF-8"); t.getResponseHeaders().set("Content-Type","text/html; charset=UTF-8"); t.sendResponseHeaders(200, b.length); t.getResponseBody().write(b); t.getResponseBody().close(); }
    static String getSession(HttpExchange t) { String c=t.getRequestHeaders().getFirst("Cookie"); if(c==null)return null; for(String s:c.split(";")) if(s.trim().startsWith("session=")) return s.trim().substring(8); return null; }
    static Map<String,String> parseForm(String b) throws UnsupportedEncodingException { Map<String,String> m=new HashMap<>(); for(String s:b.split("&")){ String[] kv=s.split("="); if(kv.length>1) m.put(URLDecoder.decode(kv[0],"UTF-8"), URLDecoder.decode(kv[1],"UTF-8")); } return m; }
    static byte[] readAllBytes(InputStream i) throws IOException { ByteArrayOutputStream o=new ByteArrayOutputStream(); byte[] f=new byte[8192]; int n; while((n=i.read(f))!=-1) o.write(f,0,n); return o.toByteArray(); }
}
