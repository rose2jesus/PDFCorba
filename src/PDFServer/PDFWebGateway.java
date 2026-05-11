package PDFServer;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import PDFApp.*;
import org.omg.CosNaming.*;
import org.omg.CORBA.*;

public class PDFWebGateway {
    private static PDFService pdfRef;
    
    // Structure de données pour stocker les infos complètes des utilisateurs
    static class UserProfile {
        String username, password, nom, prenom, email, role;
        UserProfile(String u, String p, String n, String pr, String e, String r) {
            this.username = u; this.password = p; this.nom = n; this.prenom = pr; this.email = e; this.role = r;
        }
    }

    private static final Map<String, UserProfile> USERS = new HashMap<String, UserProfile>() {{
        put("admin", new UserProfile("admin", "admin123", "Admin", "System", "admin@studio.sn", "ADMIN"));
    }};
    private static final Map<String, String> SESSIONS = new HashMap<>(); // SID -> Username

    static final String CSS = 
        "@import url('https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;600;700&display=swap'); " +
        ":root { --primary: #6366f1; --admin: #ef4444; --bg: #f8fafc; } " +
        "body{font-family:'Plus Jakarta Sans',sans-serif; background:var(--bg); margin:0; display:flex; color:#1e293b;} " +
        ".sidebar{width:280px; background:#1e1b4b; height:100vh; position:fixed; color:white; padding:30px 20px; box-sizing:border-box;} " +
        ".content{margin-left:280px; padding:40px; width:100%;} " +
        ".logo{font-size:22px; font-weight:700; margin-bottom:40px; background:linear-gradient(90deg, #818cf8, #c084fc); -webkit-background-clip:text; -webkit-text-fill-color:transparent;} " +
        ".nav-link{padding:12px 15px; border-radius:12px; margin-bottom:10px; display:flex; align-items:center; transition:0.3s; color:#94a3b8; text-decoration:none;} " +
        ".nav-active{background:rgba(255,255,255,0.1); color:white;} " +
        ".stat-grid{display:grid; grid-template-columns:repeat(4,1fr); gap:20px; margin-bottom:40px;} " +
        ".stat-card{padding:20px; border-radius:20px; color:white; box-shadow:0 10px 15px -3px rgba(0,0,0,0.1);} " +
        ".tools-grid{display:grid; grid-template-columns:repeat(auto-fill, minmax(220px, 1fr)); gap:20px;} " +
        ".tool-card{background:white; padding:20px; border-radius:20px; border:1px solid #f1f5f9; transition:0.3s;} " +
        ".btn-tool{width:100%; padding:10px; border-radius:10px; border:none; font-weight:600; cursor:pointer; margin-top:12px; color:white;} " +
        ".form-container{background:white; padding:40px; border-radius:30px; width:450px; box-shadow:0 25px 50px -12px rgba(0,0,0,0.1); margin:auto;} " +
        "input{width:100%; padding:12px; margin:8px 0; border:1.5px solid #e2e8f0; border-radius:10px; box-sizing:border-box;} " +
        ".btn-auth{background:#6366f1; color:white; border:none; padding:14px; border-radius:12px; width:100%; font-weight:700; cursor:pointer; margin-top:10px;}";

    public static void main(String[] args) throws Exception {
        try {
            ORB orb = ORB.init(args, null);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));
        } catch (Exception e) { System.err.println("CORBA Error: " + e.getMessage()); }

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new LoginPageHandler());
        server.createContext("/register", new RegisterPageHandler());
        server.createContext("/do-login", new LoginActionHandler());
        server.createContext("/do-register", new RegisterActionHandler());
        server.createContext("/home", new HomeHandler());
        server.createContext("/logout", new LogoutHandler());
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
        server.start();
    }

    // --- PAGES ---
    static class LoginPageHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String html = "<html><head><style>" + CSS + "body{display:flex;height:100vh;background:#EEF2FF;}</style></head><body>" +
                "<div class='form-container'><div class='logo'>Studio PDF</div><h2>Connexion</h2>" +
                "<form action='/do-login' method='POST'><input name='username' placeholder='Identifiant' required>" +
                "<input name='password' type='password' placeholder='Mot de passe' required>" +
                "<button type='submit' class='btn-auth'>Accéder au Dashboard</button></form>" +
                "<p>Pas encore membre ? <a href='/register'>Créer un compte complet</a></p></div></body></html>";
            sendHtml(t, html);
        }
    }

    static class RegisterPageHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String html = "<html><head><style>" + CSS + "body{display:flex;height:100vh;background:#F5F3FF;}</style></head><body>" +
                "<div class='form-container'><div class='logo'>Studio PDF</div><h2>Inscription complète</h2>" +
                "<form action='/do-register' method='POST'>" +
                "<div style='display:flex;gap:10px;'><input name='prenom' placeholder='Prénom' required><input name='nom' placeholder='Nom' required></div>" +
                "<input name='email' type='email' placeholder='Adresse Email' required>" +
                "<input name='username' placeholder='Choisir un Identifiant' required>" +
                "<input name='password' type='password' placeholder='Choisir un Mot de passe' required>" +
                "<button type='submit' class='btn-auth' style='background:#8b5cf6'>Finaliser l'inscription</button></form>" +
                "<center><a href='/' style='text-decoration:none;color:#94a3b8;'>Déjà inscrit ? Se connecter</a></center></div></body></html>";
            sendHtml(t, html);
        }
    }

    static class HomeHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String sid = getSessionId(t);
            if (sid == null || !SESSIONS.containsKey(sid)) { redirect(t, "/"); return; }
            UserProfile user = USERS.get(SESSIONS.get(sid));
            boolean isAdmin = "ADMIN".equals(user.role);

            StringBuilder html = new StringBuilder("<html><head><style>"+CSS+"</style></head><body>");
            html.append("<div class='sidebar'><div class='logo'>STUDIO PDF</div>")
                .append("<div class='nav-link nav-active'>").append(isAdmin ? "Admin Panel" : "Dashboard").append("</div>")
                .append("<a href='/logout' class='nav-link' style='margin-top:auto'>Quitter (").append(user.prenom).append(")</a></div>");
            
            html.append("<div class='content'><h1>Bienvenue, ").append(user.prenom).append(" ").append(user.nom).append("</h1>");

            if(isAdmin) {
                // VUE ADMINISTRATEUR : Gestion système
                html.append("<p>Interface de gestion globale du système CORBA.</p><div class='stat-grid'>")
                    .append("<div class='stat-card' style='background:#6366f1'>Total Users: ").append(USERS.size()).append("</div>")
                    .append("<div class='stat-card' style='background:#10b981'>Services: Actifs</div>")
                    .append("<div class='stat-card' style='background:#f59e0b'>Serveur: ").append(System.getProperty("os.name")).append("</div>")
                    .append("<div class='stat-card' style='background:#ef4444'>Logs: 0 Erreurs</div></div>")
                    .append("<h3>Derniers Utilisateurs Inscrits</h3><table style='width:100%; background:white; border-radius:15px; padding:20px;'>")
                    .append("<tr><th>Login</th><th>Nom Complet</th><th>Email</th><th>Rôle</th></tr>");
                for(UserProfile u : USERS.values()) {
                    html.append("<tr><td>").append(u.username).append("</td><td>").append(u.prenom).append(" ").append(u.nom)
                        .append("</td><td>").append(u.email).append("</td><td>").append(u.role).append("</td></tr>");
                }
                html.append("</table>");
            } else {
                // VUE UTILISATEUR : Les 12 outils
                html.append("<p>Sélectionnez un outil pour traiter vos documents PDF via nos serveurs CORBA.</p>")
                    .append("<div class='tools-grid'>");
                String[][] tools = {
                    {"Extraire Texte", "#fdf4ff", "#a855f7"}, {"PDF en Images", "#eff6ff", "#3b82f6"}, 
                    {"Protéger PDF", "#f1f5f9", "#1e293b"}, {"Fusionner PDFs", "#f0fdf4", "#22c55e"}, 
                    {"Découper PDF", "#fffbeb", "#f59e0b"}, {"Supprimer Pages", "#fef2f2", "#ef4444"},
                    {"Extraire Pages", "#f5f3ff", "#8b5cf6"}, {"Compresser PDF", "#f0fdfa", "#14b8a6"}, 
                    {"Métadonnées", "#ecfeff", "#06b6d4"}, {"Modifier Meta", "#fff7ed", "#f97316"}, 
                    {"Ajouter QR", "#faf5ff", "#9333ea"}, {"Signer PDF", "#f0fdf4", "#15803d"}
                };
                for(String[] tool : tools) {
                    html.append("<div class='tool-card' style='background:").append(tool[1]).append("'>")
                        .append("<h4 style='color:").append(tool[2]).append("'>").append(tool[0]).append("</h4>")
                        .append("<button class='btn-tool' style='background:").append(tool[2]).append("'>Utiliser l'outil</button></div>");
                }
                html.append("</div>");
            }
            html.append("</div></body></html>");
            sendHtml(t, html.toString());
        }
    }

    // --- LOGIQUE AUTH ---
    static class LoginActionHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody())));
            String u = p.get("username"), pass = p.get("password");
            if (USERS.containsKey(u) && USERS.get(u).password.equals(pass)) {
                String sid = UUID.randomUUID().toString();
                SESSIONS.put(sid, u);
                t.getResponseHeaders().add("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                redirect(t, "/home");
            } else { redirect(t, "/"); }
        }
    }

    static class RegisterActionHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody())));
            String u = p.get("username"), pass = p.get("password"), n = p.get("nom"), pr = p.get("prenom"), e = p.get("email");
            if (u != null && !u.isEmpty() && !USERS.containsKey(u)) {
                USERS.put(u, new UserProfile(u, pass, n, pr, e, "USER"));
                redirect(t, "/");
            } else { redirect(t, "/register"); }
        }
    }

    // --- UTILS ---
    static void redirect(HttpExchange t, String url) throws IOException { t.getResponseHeaders().set("Location", url); t.sendResponseHeaders(302, -1); t.close(); }
    static void sendHtml(HttpExchange t, String html) throws IOException { byte[] b = html.getBytes(StandardCharsets.UTF_8); t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8"); t.sendResponseHeaders(200, b.length); t.getResponseBody().write(b); t.getResponseBody().close(); }
    static String getSessionId(HttpExchange t) { String c = t.getRequestHeaders().getFirst("Cookie"); if (c == null) return null; for (String s : c.split(";")) if (s.trim().startsWith("session=")) return s.trim().substring(8); return null; }
    static class LogoutHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { String sid = getSessionId(t); if (sid != null) SESSIONS.remove(sid); t.getResponseHeaders().add("Set-Cookie", "session=; Path=/; Max-Age=0; HttpOnly"); redirect(t, "/"); } }
    static Map<String, String> parseForm(String query) throws UnsupportedEncodingException { Map<String, String> result = new HashMap<>(); for (String param : query.split("&")) { String[] pair = param.split("="); if (pair.length > 1) result.put(pair[0], URLDecoder.decode(pair[1], "UTF-8")); } return result; }
    static byte[] readAllBytes(InputStream is) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); int n; byte[] d = new byte[16384]; while ((n = is.read(d, 0, d.length)) != -1) b.write(d, 0, n); return b.toByteArray(); }
}
