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

    // Gestion des utilisateurs et sessions
    private static final Map<String, String> SESSIONS = new HashMap<>();
    private static final Map<String, String> USERS = new HashMap<>();
    private static final Map<String, String> ROLES = new HashMap<>();

    static {
        // Utilisateurs par défaut
        USERS.put("admin@pdf.com", "admin123");
        ROLES.put("admin@pdf.com", "admin");
        USERS.put("user@pdf.com", "user123");
        ROLES.put("user@pdf.com", "user");
    }

    // ── MAIN ET INITIALISATION CORBA ───────────────────────
    public static void main(String[] args) throws Exception {
        try {
            // Configuration spécifique pour le déploiement Cloud (Render/Linux)
            Properties props = new Properties();
            props.put("org.omg.CORBA.ORBInitialPort", "1050");
            props.put("org.omg.CORBA.ORBInitialHost", "127.0.0.1");
            
            ORB orb = ORB.init(args, props);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));

            // Démarrage du serveur Web sur le port 8080
            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            
            // Routes Authentification
            server.createContext("/login",    new LoginHandler());
            server.createContext("/register", new RegisterHandler());
            server.createContext("/logout",   new LogoutHandler());
            
            // Route Principale et Outils
            server.createContext("/",         new UIHandler());
            
            // Routes des fonctionnalités
            server.createContext("/create",   new ActionHandler("Création"));
            server.createContext("/extract",  new ActionHandler("Extraction"));
            server.createContext("/merge",    new ActionHandler("Fusion"));
            // ... autres routes pointant vers vos Handlers spécifiques

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
            System.out.println("Studio PDF CORBA opérationnel sur http://localhost:8080");
            server.start();
        } catch (Exception e) {
            System.err.println("Erreur de lancement : " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ── GESTIONNAIRES D'ACCÈS ──────────────────────────────

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
                } else { sendHtml(t, authPage("Email ou mot de passe incorrect.", true)); }
            } else { sendHtml(t, authPage(null, true)); }
        }
    }

    static class RegisterHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                Map<String,String> p = parseForm(new String(readAllBytes(t.getRequestBody())));
                String email = p.get("email"), pass = p.get("password");
                if (email != null && !USERS.containsKey(email)) {
                    USERS.put(email, pass);
                    ROLES.put(email, "user");
                    redirect(t, "/login");
                } else { sendHtml(t, authPage("Cet email est déjà utilisé.", false)); }
            } else { sendHtml(t, authPage(null, false)); }
        }
    }

    static class LogoutHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String sid = getSession(t);
            if (sid != null) SESSIONS.remove(sid);
            t.getResponseHeaders().set("Set-Cookie", "session=; Path=/; Max-Age=0");
            redirect(t, "/login");
        }
    }

    // ── INTERFACE UTILISATEUR ───────────────────────────────

    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            boolean admin = isAdmin(t);

            String html = "<!DOCTYPE html><html lang='fr'><head><meta charset='UTF-8'>"
                + "<title>Studio PDF CORBA</title>"
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700;800&display=swap' rel='stylesheet'>"
                + CSS_APP + "</head><body>"
                + "<div class='topbar'><h1>Studio PDF CORBA</h1>"
                + "<div><span class='badge'>" + (admin?"ADMINISTRATEUR":"UTILISATEUR") + "</span>"
                + "<a href='/logout' class='btn-logout'>Déconnexion</a></div></div>"
                + "<div class='main'>";

            if (admin) {
                html += "<div class='admin-card'><h3>Statistiques Globales</h3>"
                    + "<div style='display:flex;gap:20px;margin-top:10px'>"
                    + "<span>Utilisateurs : " + USERS.size() + "</span>"
                    + "<span>Documents créés : " + nbCrees + "</span>"
                    + "</div></div>";
            }

            html += "<h2>Mes 12 Outils</h2>"
                + "<div class='grid'>"
                + tool("Extraire Texte", "txt", "Récupérer le texte d'un PDF")
                + tool("Vers Image", "img", "Convertir les pages en images")
                + tool("Créer PDF", "new", "Générer un nouveau document")
                + tool("Protéger", "lock", "Ajouter un mot de passe")
                + tool("Fusionner", "join", "Combiner deux fichiers")
                + tool("Découper", "cut", "Séparer un PDF")
                + tool("Supprimer Page", "del", "Retirer une page inutile")
                + tool("Extraire Pages", "pages", "Choisir un intervalle")
                + tool("Compresser", "zip", "Réduire le poids du fichier")
                + tool("Lire Meta", "meta", "Propriétés du document")
                + tool("Modifier Meta", "edit", "Changer titre/auteur")
                + tool("Générer QR", "qr", "Ajouter un QR Code")
                + "</div></div>"
                + "<script>function run(id){ alert('Démarrage de l\\'outil : ' + id); }</script></body></html>";

            sendHtml(t, html);
        }
        String tool(String name, String id, String desc) {
            return "<div class='card' onclick=\"run('"+id+"')\"><h3>"+name+"</h3><p>"+desc+"</p></div>";
        }
    }

    // ── STYLES CSS ─────────────────────────────────────────

    static final String CSS_APP = "<style>"
        + "*{box-sizing:border-box;margin:0;padding:0;font-family:'Inter',sans-serif}"
        + "body{background:#F9FAFB;color:#111827}"
        + ".topbar{background:#4F1D96;padding:15px 40px;display:flex;justify-content:space-between;align-items:center;color:white}"
        + ".main{max-width:1100px;margin:40px auto;padding:0 20px}"
        + ".grid{display:grid;grid-template-columns:repeat(4,1fr);gap:20px}"
        + ".card{background:white;padding:25px;border-radius:16px;cursor:pointer;transition:0.3s;border:1px solid #E5E7EB}"
        + ".card:hover{transform:translateY(-5px);border-color:#6D28D9;box-shadow:0 10px 20px rgba(109,40,217,0.1)}"
        + ".badge{background:#FDE68A;color:#92400E;padding:5px 12px;border-radius:20px;font-size:11px;font-weight:700}"
        + ".btn-logout{margin-left:20px;color:white;text-decoration:none;font-size:13px;font-weight:600}"
        + ".admin-card{background:#EEF2FF;padding:20px;border-radius:16px;margin-bottom:30px;border:1px dashed #4F1D96}"
        + "</style>";

    static final String CSS_AUTH = "body{background:linear-gradient(135deg,#4F1D96,#2D3B8E);height:100vh;display:flex;align-items:center;justify-content:center;font-family:sans-serif}"
        + ".box{background:white;padding:50px;border-radius:24px;width:380px;text-align:center}"
        + "input{width:100%;padding:14px;margin:10px 0;border:2px solid #F3F4F6;border-radius:12px;outline:none}"
        + "button{width:100%;padding:14px;background:#6D28D9;color:white;border:none;border-radius:12px;font-weight:700;cursor:pointer;margin-top:10px}"
        + "a{display:block;margin-top:20px;color:#6D28D9;text-decoration:none;font-size:13px}";

    static String authPage(String error, boolean login) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><style>"+CSS_AUTH+"</style></head><body>"
            + "<div class='box'><h1>"+(login?"Connexion":"Inscription")+"</h1>"
            + (error!=null?"<p style='color:red;font-size:12px'>"+error+"</p>":"")
            + "<form method='POST'><input type='email' name='email' placeholder='Email' required>"
            + "<input type='password' name='password' placeholder='Mot de passe' required>"
            + "<button type='submit'>"+(login?"Se connecter":"S'inscrire")+"</button></form>"
            + "<a href='"+(login?"/register":"/login")+"'>"+(login?"Créer un compte":"Déjà inscrit ? Connexion")+"</a>"
            + "</div></body></html>";
    }

    // ── UTILITAIRES ────────────────────────────────────────

    static String getSession(HttpExchange t) {
        String c = t.getRequestHeaders().getFirst("Cookie");
        if (c==null) return null;
        for(String s : c.split(";")) if(s.trim().startsWith("session=")) return s.trim().substring(8);
        return null;
    }
    static boolean isLoggedIn(HttpExchange t) { return getRole(t) != null; }
    static String getRole(HttpExchange t) { String sid=getSession(t); return (sid!=null)?SESSIONS.get(sid):null; }
    static boolean isAdmin(HttpExchange t) { return "admin".equals(getRole(t)); }
    static void redirect(HttpExchange t, String u) throws IOException { t.getResponseHeaders().set("Location",u); t.sendResponseHeaders(302,-1); }
    static void sendHtml(HttpExchange t, String h) throws IOException {
        byte[] b = h.getBytes("UTF-8");
        t.getResponseHeaders().set("Content-Type","text/html; charset=UTF-8");
        t.sendResponseHeaders(200, b.length);
        t.getResponseBody().write(b);
        t.getResponseBody().close();
    }
    static Map<String,String> parseForm(String b) {
        Map<String,String> m = new HashMap<>();
        try { for(String s : b.split("&")) { String[] kv=s.split("="); m.put(URLDecoder.decode(kv[0],"UTF-8"), URLDecoder.decode(kv[1],"UTF-8")); } } catch(Exception e){}
        return m;
    }
    static byte[] readAllBytes(InputStream i) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while((n=i.read(buf))!=-1) o.write(buf,0,n);
        return o.toByteArray();
    }

    static class ActionHandler implements HttpHandler {
        String name; ActionHandler(String n) { this.name = n; }
        public void handle(HttpExchange t) throws IOException { sendHtml(t, "L'outil " + name + " est en cours d'exécution via CORBA..."); }
    }
}
