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
    
    // Base de données simulée (Username -> Password)
    private static final Map<String, String> USERS = new HashMap<String, String>() {{
        put("admin", "admin123");
    }};
    private static final Map<String, String> ROLES = new HashMap<String, String>() {{
        put("admin", "ADMIN");
    }};
    private static final Map<String, String> SESSIONS = new HashMap<>(); // SID -> Role
    private static final Map<String, String> SESSION_USER = new HashMap<>(); // SID -> Username

    // ── DESIGN PREMIUM (Pastels, Dégradés, Glassmorphism) ──────────────────
    static final String CSS = 
        "@import url('https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;600;700&display=swap');" +
        ":root { --primary: #6366f1; --bg: #f8fafc; --card: #ffffff; }" +
        "body{font-family:'Plus Jakarta Sans',sans-serif; background:var(--bg); margin:0; display:flex; color:#1e293b;}" +
        ".sidebar{width:280px; background:#1e1b4b; height:100vh; position:fixed; color:white; padding:30px 20px; box-sizing:border-box;}" +
        ".content{margin-left:280px; padding:40px; width:100%;}" +
        ".logo{font-size:22px; font-weight:700; margin-bottom:40px; background:linear-gradient(90deg, #818cf8, #c084fc); -webkit-background-clip:text; -webkit-text-fill-color:transparent;}" +
        ".nav-link{padding:12px 15px; border-radius:12px; cursor:pointer; margin-bottom:10px; display:flex; align-items:center; transition:0.3s; color:#94a3b8; text-decoration:none;}" +
        ".nav-link:hover, .nav-active{background:rgba(255,255,255,0.1); color:white;}" +
        ".stat-grid{display:grid; grid-template-columns:repeat(4,1fr); gap:20px; margin-bottom:40px;}" +
        ".stat-card{padding:20px; border-radius:20px; color:white; box-shadow:0 10px 15px -3px rgba(0,0,0,0.1);}" +
        ".tools-grid{display:grid; grid-template-columns:repeat(auto-fill, minmax(250px, 1fr)); gap:20px;}" +
        ".tool-card{background:white; padding:25px; border-radius:24px; border:1px solid #f1f5f9; transition:0.3s; position:relative; overflow:hidden;}" +
        ".tool-card:hover{transform:translateY(-5px); box-shadow:0 20px 25px -5px rgba(0,0,0,0.05); border-color:#e2e8f0;}" +
        ".btn-tool{width:100%; padding:10px; border-radius:10px; border:none; font-weight:600; cursor:pointer; margin-top:15px; transition:0.2s;}" +
        ".login-container{background:white; padding:40px; border-radius:30px; width:400px; box-shadow:0 25px 50px -12px rgba(0,0,0,0.1); text-align:center;}" +
        "input{width:100%; padding:14px; margin:10px 0; border:1.5px solid #e2e8f0; border-radius:12px; box-sizing:border-box; outline:none; transition:0.2s;}" +
        "input:focus{border-color:#6366f1; ring:2px #818cf8;}" +
        ".btn-auth{background:#6366f1; color:white; border:none; padding:14px; border-radius:12px; width:100%; font-weight:700; cursor:pointer; margin-top:10px;}";

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", new LoginPageHandler());
        server.createContext("/register", new RegisterPageHandler());
        server.createContext("/do-login", new LoginActionHandler());
        server.createContext("/do-register", new RegisterActionHandler());
        server.createContext("/home", new HomeHandler());
        server.createContext("/logout", new LogoutHandler());
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
        System.out.println("🚀 Studio PDF Premium lancé : http://localhost:8080");
        server.start();
    }

    // ── PAGE DE CONNEXION ───────────────────────────────────────
    static class LoginPageHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String html = "<html><head><style>" + CSS + "body{display:flex;align-items:center;justify-content:center;height:100vh;background:#EEF2FF;}</style></head><body>" +
                "<div class='login-container'> <div class='logo'>Studio PDF</div> <h2>Connexion</h2>" +
                "<form action='/do-login' method='POST'><input name='username' placeholder='Nom d utilisateur' required>" +
                "<input name='password' type='password' placeholder='Mot de passe' required>" +
                "<button type='submit' class='btn-auth'>Se connecter</button></form>" +
                "<p style='font-size:14px; color:#64748b'>Pas de compte ? <a href='/register' style='color:#6366f1;text-decoration:none;font-weight:600'>Créer un compte</a></p></div></body></html>";
            sendHtml(t, html);
        }
    }

    // ── PAGE D'INSCRIPTION ──────────────────────────────────────
    static class RegisterPageHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String html = "<html><head><style>" + CSS + "body{display:flex;align-items:center;justify-content:center;height:100vh;background:#F5F3FF;}</style></head><body>" +
                "<div class='login-container'> <div class='logo'>Studio PDF</div> <h2>Inscription</h2>" +
                "<form action='/do-register' method='POST'><input name='username' placeholder='Choisir un nom d utilisateur' required>" +
                "<input name='password' type='password' placeholder='Choisir un mot de passe' required>" +
                "<button type='submit' class='btn-auth' style='background:#8b5cf6'>S'inscrire</button></form>" +
                "<a href='/' style='font-size:14px;color:#64748b;text-decoration:none'>Retour à la connexion</a></div></body></html>";
            sendHtml(t, html);
        }
    }

    // ── TABLEAU DE BORD (HOME) ──────────────────────────────────
    static class HomeHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String sid = getSessionId(t);
            if (sid == null || !SESSIONS.containsKey(sid)) { redirect(t, "/"); return; }
            String user = SESSION_USER.get(sid);
            boolean isAdmin = "ADMIN".equals(SESSIONS.get(sid));

            StringBuilder html = new StringBuilder("<html><head><style>"+CSS+"</style></head><body>");
            html.append("<div class='sidebar'><div class='logo'>STUDIO PDF</div>")
                .append("<div class='nav-link nav-active'>Dashboard</div>")
                .append("<div class='nav-link'>Mes Fichiers</div>")
                .append("<a href='/logout' class='nav-link' style='margin-top:auto'>Déconnexion (").append(user).append(")</a></div>");

            html.append("<div class='content'><h2>Tableau de Bord — Supervision</h2>");

            if(isAdmin) {
                html.append("<div class='stat-grid'>")
                    .append("<div class='stat-card' style='background:#a855f7'>Utilisateurs: <b>").append(USERS.size()).append("</b></div>")
                    .append("<div class='stat-card' style='background:#3b82f6'>Connectés: <b>").append(SESSIONS.size()).append("</b></div>")
                    .append("<div class='stat-card' style='background:#10b981'>Statut: <b>Online</b></div>")
                    .append("<div class='stat-card' style='background:#f59e0b'>Actions: <b>256</b></div></div>");
            }

            html.append("<h3>Mes Outils PDF</h3><div class='tools-grid'>");
            String[][] tools = {
                {"Extraire Texte", "#fdf4ff", "#a855f7", "Extraire"},
                {"PDF en Images", "#eff6ff", "#3b82f6", "Convertir"},
                {"Protéger PDF", "#f8fafc", "#1e293b", "Chiffrer"},
                {"Fusionner PDFs", "#f0fdf4", "#22c55e", "Fusionner"},
                {"Découper PDF", "#fffbeb", "#f59e0b", "Découper"},
                {"Supprimer Pages", "#fef2f2", "#ef4444", "Supprimer"},
                {"Extraire Pages", "#f5f3ff", "#8b5cf6", "Extraire"},
                {"Compresser PDF", "#f0fdfa", "#14b8a6", "Compresser"}
            };

            for(String[] tool : tools) {
                html.append("<div class='tool-card' style='background:").append(tool[1]).append("'>")
                    .append("<h4 style='margin:0;color:").append(tool[2]).append("'>").append(tool[0]).append("</h4>")
                    .append("<p style='font-size:12px;color:#64748b'>Traitement rapide CORBA</p>")
                    .append("<button class='btn-tool' style='background:").append(tool[2]).append(";color:white'>").append(tool[3]).append("</button></div>");
            }
            html.append("</div></div></body></html>");
            sendHtml(t, html.toString());
        }
    }

    // ── ACTIONS (LOGIN / REGISTER) ──────────────────────────────
    static class LoginActionHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            Map<String, String> p = parseForm(new String(t.getRequestBody().readAllBytes()));
            String u = p.get("username"), pass = p.get("password");
            if (USERS.containsKey(u) && USERS.get(u).equals(pass)) {
                String sid = UUID.randomUUID().toString();
                SESSIONS.put(sid, ROLES.getOrDefault(u, "USER"));
                SESSION_USER.put(sid, u);
                t.getResponseHeaders().add("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                redirect(t, "/home");
            } else { redirect(t, "/"); }
        }
    }

    static class RegisterActionHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            Map<String, String> p = parseForm(new String(t.getRequestBody().readAllBytes()));
            String u = p.get("username"), pass = p.get("password");
            if (!u.isEmpty() && !USERS.containsKey(u)) {
                USERS.put(u, pass);
                ROLES.put(u, "USER");
                redirect(t, "/");
            } else { redirect(t, "/register?error=exists"); }
        }
    }

    // ── UTILS ───────────────────────────────────────────────────
    static void redirect(HttpExchange t, String url) throws IOException {
        t.getResponseHeaders().set("Location", url);
        t.sendResponseHeaders(302, -1);
        t.close();
    }

    static void sendHtml(HttpExchange t, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        t.sendResponseHeaders(200, b.length);
        t.getResponseBody().write(b);
        t.getResponseBody().close();
    }

    static String getSessionId(HttpExchange t) {
        String c = t.getRequestHeaders().getFirst("Cookie");
        if (c == null) return null;
        for (String s : c.split(";")) if (s.trim().startsWith("session=")) return s.trim().substring(8);
        return null;
    }

    static class LogoutHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String sid = getSessionId(t);
            if (sid != null) { SESSIONS.remove(sid); SESSION_USER.remove(sid); }
            t.getResponseHeaders().add("Set-Cookie", "session=; Path=/; Max-Age=0; HttpOnly");
            redirect(t, "/");
        }
    }

    static Map<String, String> parseForm(String query) throws UnsupportedEncodingException {
        Map<String, String> result = new HashMap<>();
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length > 1) result.put(pair[0], URLDecoder.decode(pair[1], "UTF-8"));
        }
        return result;
    }
}
