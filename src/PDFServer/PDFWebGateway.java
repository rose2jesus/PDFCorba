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
    
    // Sessions et utilisateurs
    private static final Map<String, String> SESSIONS = new HashMap<>();
    private static final Map<String, String> USERS = new HashMap<>();
    private static final Map<String, String> ROLES = new HashMap<>();

    static {
        USERS.put("admin@pdf.com", "admin123");
        USERS.put("user@pdf.com", "user123");
        ROLES.put("admin@pdf.com", "admin");
        ROLES.put("user@pdf.com", "user");
    }

    public static void main(String[] args) {
        try {
            System.out.println("Démarrage de la Gateway...");

            // Configuration forcée pour l'environnement Render (127.0.0.1)
            Properties props = new Properties();
            props.put("org.omg.CORBA.ORBInitialPort", "1050");
            props.put("org.omg.CORBA.ORBInitialHost", "127.0.0.1");
            ORB orb = ORB.init(args, props);

            // BOUCLE DE RECONNEXION : On tente de trouver le service pendant 2 minutes
            int tentatives = 0;
            while (pdfRef == null && tentatives < 25) {
                try {
                    org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
                    NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
                    pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));
                    System.out.println(">>> SUCCESS : Connecté au PDFService !");
                } catch (Exception e) {
                    tentatives++;
                    System.out.println("Le serveur n'est pas encore prêt (Tentative " + tentatives + "/25). Attente de 5s...");
                    Thread.sleep(5000);
                }
            }

            if (pdfRef == null) {
                System.err.println("ERREUR : Impossible de joindre le service PDF après 2 minutes.");
                System.exit(1);
            }

            // Récupération du port dynamique de Render
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

            // Définition des routes
            server.createContext("/", new UIHandler());
            server.createContext("/login", new LoginHandler());
            server.createContext("/logout", t -> {
                String sid = getSession(t);
                if (sid != null) SESSIONS.remove(sid);
                t.getResponseHeaders().set("Set-Cookie", "session=; Path=/; Max-Age=0");
                redirect(t, "/login");
            });

            server.setExecutor(null);
            server.start();
            System.out.println("Serveur Web prêt sur le port " + port);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ── INTERFACE UTILISATEUR (12 OUTILS) ─────────────────
    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            boolean admin = "admin".equals(SESSIONS.get(getSession(t)));

            String html = "<html><head><meta charset='UTF-8'><style>" + CSS + "</style></head><body>"
                + "<div class='nav'><b>Studio PDF CORBA</b> <span>" + (admin?"ADMIN":"USER") + "</span> <a href='/logout'>Déconnexion</a></div>"
                + "<div class='container'><h2>Vos 12 Outils PDF</h2><div class='grid'>"
                + tool("Extraire Texte") + tool("Vers Image") + tool("Créer PDF") + tool("Protéger")
                + tool("Fusionner") + tool("Découper") + tool("Supprimer Page") + tool("Extraire Pages")
                + tool("Compresser") + tool("Lire Meta") + tool("Modifier Meta") + tool("Générer QR")
                + "</div></div></body></html>";
            sendHtml(t, html);
        }
        String tool(String n) { return "<div class='card'><h3>"+n+"</h3><button onclick=\"alert('Action CORBA lancée')\">Lancer</button></div>"; }
    }

    // ── AUTHENTIFICATION ──────────────────────────────────
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
                } else { sendHtml(t, "Échec : <a href='/login'>Réessayer</a>"); }
            } else {
                sendHtml(t, "<html><body style='background:#4F1D96;display:flex;justify-content:center;padding-top:100px;font-family:sans-serif'>"
                    + "<form method='POST' style='background:white;padding:40px;border-radius:12px;width:300px'>"
                    + "<h2 style='margin-bottom:20px'>Connexion</h2>"
                    + "Email: <input name='email' style='width:100%;padding:10px;margin:10px 0'><br>"
                    + "Pass: <input type='password' name='password' style='width:100%;padding:10px;margin:10px 0'><br><br>"
                    + "<button type='submit' style='width:100%;padding:10px;background:#6D28D9;color:white;border:none;border-radius:6px;cursor:pointer'>Entrer</button></form></body></html>");
            }
        }
    }

    // ── MÉTHODES UTILES ────────────────────────────────────
    static String CSS = "body{font-family:sans-serif;background:#F3F4F6;margin:0}.nav{background:#4F1D96;color:white;padding:15px 40px;display:flex;justify-content:space-between;align-items:center}.container{padding:40px;max-width:1100px;margin:auto}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:20px}.card{background:white;padding:25px;border-radius:12px;box-shadow:0 2px 10px rgba(0,0,0,0.05);text-align:center}.card h3{font-size:16px;margin-bottom:15px}button{background:#6D28D9;color:white;border:none;padding:8px 15px;border-radius:5px;cursor:pointer}";
    static void redirect(HttpExchange t, String u) throws IOException { t.getResponseHeaders().set("Location",u); t.sendResponseHeaders(302,-1); }
    static void sendHtml(HttpExchange t, String h) throws IOException { byte[] b=h.getBytes("UTF-8"); t.sendResponseHeaders(200,b.length); t.getResponseBody().write(b); t.getResponseBody().close(); }
    static boolean isLoggedIn(HttpExchange t) { return getSession(t) != null && SESSIONS.containsKey(getSession(t)); }
    static String getSession(HttpExchange t) { String c=t.getRequestHeaders().getFirst("Cookie"); if(c==null)return null; for(String s:c.split(";")) if(s.trim().startsWith("session=")) return s.trim().substring(8); return null; }
    static Map<String,String> parseForm(String b) { Map<String,String> m=new HashMap<>(); for(String s:b.split("&")){ String[] kv=s.split("="); if(kv.length>1) m.put(URLDecoder.decode(kv[0]), URLDecoder.decode(kv[1])); } return m; }
    static byte[] readAllBytes(InputStream i) throws IOException { ByteArrayOutputStream o=new ByteArrayOutputStream(); byte[] f=new byte[8192]; int n; while((n=i.read(f))!=-1) o.write(f,0,n); return o.toByteArray(); }
}
