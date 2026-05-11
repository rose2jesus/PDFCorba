package PDFServer;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.*;
import PDFApp.*;
import org.omg.CosNaming.*;
import org.omg.CORBA.*;

public class PDFWebGateway {
    private static PDFService pdfRef;
    
    // Statistiques pour l'admin
    private static int nbCrees = 0, nbExtractions = 0, nbFusions = 0, nbProtections = 0;

    // Gestion des sessions et utilisateurs
    private static final Map<String, String> SESSIONS = new HashMap<>();
    private static final Map<String, String> USERS = new HashMap<>();
    private static final Map<String, String> ROLES = new HashMap<>();

    static {
        USERS.put("admin@pdf.com", "admin123");
        USERS.put("user@pdf.com", "user123");
        ROLES.put("admin@pdf.com", "admin");
        ROLES.put("user@pdf.com", "user");
    }

    // ── MAIN AVEC RECONNEXION AUTOMATIQUE ─────────────────
    public static void main(String[] args) throws Exception {
        try {
            System.out.println("Lancement de la Gateway...");
            
            // Propriétés pour forcer la connexion locale sur Render
            Properties props = new Properties();
            props.put("org.omg.CORBA.ORBInitialPort", "1050");
            props.put("org.omg.CORBA.ORBInitialHost", "127.0.0.1");
            
            ORB orb = ORB.init(args, props);

            // BOUCLE DE SURVIE : On tente de se connecter pendant 2 minutes max
            int attempts = 0;
            while (pdfRef == null && attempts < 24) {
                try {
                    org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
                    NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
                    pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));
                    System.out.println("SUCCESS : Connecté au PDFService !");
                } catch (Exception e) {
                    attempts++;
                    System.out.println("Attente du serveur CORBA... (Tentative " + attempts + "/24)");
                    Thread.sleep(5000); // 5 secondes entre chaque essai
                }
            }

            if (pdfRef == null) {
                System.err.println("ERREUR FATALE : Le serveur CORBA n'a pas répondu.");
                System.exit(1);
            }

            // Port dynamique pour Render (8080 par défaut)
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            
            // Routes Authentification
            server.createContext("/login",    new LoginHandler());
            server.createContext("/logout",   new LogoutHandler());
            
            // Route Principale (Affichage des 12 outils)
            server.createContext("/",         new UIHandler());
            
            // Routes des outils (Exemples de liens vers CORBA)
            server.createContext("/create",   new ActionHandler("Création"));
            server.createContext("/extract",  new ActionHandler("Extraction"));
            server.createContext("/merge",    new ActionHandler("Fusion"));

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
            System.out.println("Serveur Web prêt sur le port " + port);
            server.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ── GESTIONNAIRES (HANDLERS) ──────────────────────────

    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                Map<String,String> p = parseForm(new String(readAllBytes(t.getRequestBody())));
                String email = p.get("email"), pass = p.get("password");
                if (USERS.containsKey(email) && USERS.get(email).equals(pass)) {
                    String sid = UUID.randomUUID().toString();
                    SESSIONS.put(sid, ROLES.get(email));
                    t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                    redirect(t, "/");
                } else { sendHtml(t, authPage("Accès refusé : identifiants incorrects.")); }
            } else { sendHtml(t, authPage(null)); }
        }
    }

    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            boolean admin = isAdmin(t);
            
            String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
                + "<title>Studio PDF CORBA</title>"
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&display=swap' rel='stylesheet'>"
                + CSS_APP + "</head><body>"
                + "<div class='topbar'><h1>Studio PDF CORBA</h1>"
                + "<div><span class='badge'>" + (admin?"ADMIN":"USER") + "</span>"
                + "<a href='/logout' class='logout'>Déconnexion</a></div></div>"
                + "<div class='container'>";

            if (admin) {
                html += "<div class='admin-card'><h3>Dashboard Admin</h3><p>Utilisateurs : " + USERS.size() + " | Actions : " + (nbCrees+nbExtractions) + "</p></div>";
            }

            html += "<h2>Mes 12 Fonctionnalités</h2><div class='grid'>"
                + tool("Extraction Texte", "txt", "Récupérer le texte d'un PDF")
                + tool("Vers Image", "img", "Convertir les pages en PNG")
                + tool("Créer PDF", "new", "Générer un PDF vierge")
                + tool("Protéger", "lock", "Ajouter un mot de passe")
                + tool("Fusionner", "join", "Assembler 2 fichiers")
                + tool("Découper", "cut", "Extraire une page")
                + tool("Supprimer Page", "del", "Enlever une page")
                + tool("Extraire Pages", "rng", "Choisir un intervalle")
                + tool("Compresser", "zip", "Alléger le fichier")
                + tool("Lire Meta", "meta", "Infos du document")
                + tool("Modifier Meta", "edit", "Changer l'auteur")
                + tool("Générer QR", "qr", "Ajouter un QR Code")
                + "</div></div>"
                + "<script>function run(id){ alert('Démarrage de : ' + id); }</script></body></html>";

            sendHtml(t, html);
        }
        String tool(String t, String id, String d) {
            return "<div class='card' onclick=\"run('"+id+"')\"><h3>"+t+"</h3><p>"+d+"</p></div>";
        }
    }

    // ── STYLES ET UTILS ───────────────────────────────────

    static final String CSS_APP = "<style>"
        + "*{box-sizing:border-box;margin:0;padding:0;font-family:'Inter',sans-serif}"
        + "body{background:#F3F4F6}.topbar{background:#4F1D96;padding:15px 40px;color:white;display:flex;justify-content:space-between;align-items:center}"
        + ".container{max-width:1100px;margin:30px auto;padding:0 20px}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:20px}"
        + ".card{background:white;padding:25px;border-radius:15px;cursor:pointer;transition:0.3s;border:1px solid #E5E7EB}"
        + ".card:hover{transform:translateY(-5px);border-color:#6D28D9;box-shadow:0 10px 20px rgba(0,0,0,0.05)}"
        + ".badge{background:#FDE68A;color:#92400E;padding:5px 12px;border-radius:20px;font-size:11px;font-weight:700}"
        + ".logout{color:white;text-decoration:none;margin-left:15px;font-size:13px}"
        + ".admin-card{background:#EEF2FF;padding:20px;border-radius:15px;margin-bottom:25px;border:1px dashed #4F1D96}"
        + "</style>";

    static String authPage(String err) {
        return "<html><head><meta charset='UTF-8'><style>body{background:#4F1D96;display:flex;align-items:center;justify-content:center;height:100vh;font-family:sans-serif}"
            + ".box{background:white;padding:40px;border-radius:20px;width:350px;text-align:center}"
            + "input{width:100%;padding:12px;margin:10px 0;border:1px solid #ddd;border-radius:8px}"
            + "button{width:100%;padding:12px;background:#6D28D9;color:white;border:none;border-radius:8px;cursor:pointer;font-weight:bold}"
            + "</style></head><body><div class='box'><h1>Connexion</h1>"
            + (err!=null?"<p style='color:red;font-size:12px'>"+err+"</p>":"")
            + "<form method='POST'><input name='email' placeholder='Email'><input type='password' name='password' placeholder='Pass'><button>Entrer</button></form></div></body></html>";
    }

    // -- Méthodes techniques obligatoires --
    static void redirect(HttpExchange t, String u) throws IOException { t.getResponseHeaders().set("Location", u); t.sendResponseHeaders(302, -1); }
    static void sendHtml(HttpExchange t, String h) throws IOException { byte[] b = h.getBytes("UTF-8"); t.getResponseHeaders().set("Content-Type","text/html; charset=UTF-8"); t.sendResponseHeaders(200, b.length); t.getResponseBody().write(b); t.getResponseBody().close(); }
    static boolean isLoggedIn(HttpExchange t) { String sid = getSession(t); return sid != null && SESSIONS.containsKey(sid); }
    static boolean isAdmin(HttpExchange t) { return "admin".equals(SESSIONS.get(getSession(t))); }
    static String getSession(HttpExchange t) { String c = t.getRequestHeaders().getFirst("Cookie"); if (c==null) return null; for (String s : c.split(";")) if (s.trim().startsWith("session=")) return s.trim().substring(8); return null; }
    static Map<String,String> parseForm(String b) { Map<String,String> m = new HashMap<>(); for(String s : b.split("&")) { String[] kv=s.split("="); if(kv.length>1) m.put(URLDecoder.decode(kv[0]), URLDecoder.decode(kv[1])); } return m; }
    static byte[] readAllBytes(InputStream is) throws IOException { ByteArrayOutputStream bos = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while((n=is.read(buf))!=-1) bos.write(buf,0,n); return bos.toByteArray(); }
    static class ActionHandler implements HttpHandler { String n; ActionHandler(String n){this.n=n;} public void handle(HttpExchange t) throws IOException { sendHtml(t, "Outil " + n + " en cours..."); } }
}
