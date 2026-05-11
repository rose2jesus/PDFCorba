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
    private static int totalActions = 0;

    // SESSIONS & ROLES (Utilise uniquement le Username maintenant)
    private static final Map<String, String> SESSIONS = new HashMap<>(); 
    private static final Map<String, String> SESSION_USER = new HashMap<>(); 
    
    private static final Map<String, String> USERS = new HashMap<String, String>() {{
        put("admin", "admin123");
        put("etudiant", "pass123");
    }};
    
    private static final Map<String, String> ROLES = new HashMap<String, String>() {{
        put("admin", "ADMIN");
        put("etudiant", "USER");
    }};

    // ── CONFIGURATION DESIGN (Inspiré Linear/Figma) ──────────────────
    static final String CSS = 
        "@import url('https://fonts.googleapis.com/css2?family=Inter:wght@300;400;600&display=swap');" +
        "body{font-family:'Inter',sans-serif; background:#F8F9FF; color:#1A1A1A; margin:0;}" +
        ".sidebar{width:260px; background:#FFFFFF; height:100vh; position:fixed; border-right:1px solid #E5E7EB; padding:20px;}" +
        ".content{margin-left:300px; padding:40px;}" +
        ".logo{font-weight:600; font-size:18px; color:#4F46E5; margin-bottom:40px; display:flex; align-items:center;}" +
        ".nav-item{padding:10px; margin:5px 0; border-radius:8px; cursor:pointer; color:#6B7280; font-size:14px; transition:0.2s;}" +
        ".nav-item:hover, .nav-active{background:#F3F4F6; color:#4F46E5;}" +
        ".stats-grid{display:grid; grid-template-columns:repeat(3,1fr); gap:20px; margin-bottom:40px;}" +
        ".stat-card{background:white; padding:20px; border-radius:12px; border:1px solid #E5E7EB; box-shadow: 0 1px 3px rgba(0,0,0,0.02);}" +
        ".stat-val{font-size:24px; font-weight:600; color:#111827;}" +
        ".stat-label{font-size:12px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px;}" +
        ".tools-grid{display:grid; grid-template-columns:repeat(auto-fill, minmax(220px, 1fr)); gap:20px;}" +
        ".tool-card{background:white; padding:25px; border-radius:16px; border:1px solid #F3F4F6; transition:all 0.3s cubic-bezier(0.4, 0, 0.2, 1); cursor:pointer;}" +
        ".tool-card:hover{border-color:#C7D2FE; transform:translateY(-4px); box-shadow:0 12px 20px -10px rgba(79, 70, 229, 0.1);}" +
        ".tool-icon{width:40px; height:40px; border-radius:10px; background:#EEF2FF; margin-bottom:15px; display:flex; align-items:center; justify-content:center; color:#4F46E5;}" +
        ".btn-primary{background:#4F46E5; color:white; border:none; padding:12px; border-radius:8px; cursor:pointer; font-weight:600; width:100%; transition:0.2s;}" +
        ".btn-primary:hover{background:#4338CA;}";

    // ── MAIN & HANDLERS ──────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", new LoginPageHandler());
        server.createContext("/login", new LoginHandler());
        server.createContext("/home", new HomeHandler());
        server.createContext("/logout", new LogoutHandler());
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
        System.out.println("Studio PDF (Linear Style) -> http://localhost:8080");
        server.start();
    }

    static class LoginPageHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String html = "<html><head><style>" + CSS + 
                "body{display:flex; align-items:center; justify-content:center; height:100vh; background:#F3F4F6;}" +
                ".login-card{background:white; padding:40px; border-radius:24px; width:360px; box-shadow:0 20px 25px -5px rgba(0,0,0,0.1);}" +
                "input{width:100%; padding:12px; margin:10px 0; border:1px solid #E5E7EB; border-radius:8px; box-sizing:border-box;}" +
                "</style></head><body><div class='login-card'>" +
                "<div class='logo'>Studio PDF</div>" +
                "<form action='/login' method='POST'>" +
                "<input name='username' placeholder='Nom d utilisateur' required autofocus>" +
                "<input name='password' type='password' placeholder='Mot de passe' required>" +
                "<button type='submit' class='btn-primary'>Continuer</button>" +
                "</form></div></body></html>";
            sendHtml(t, html);
        }
    }

    static class HomeHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String sid = getSessionId(t);
            if (sid == null || !SESSIONS.containsKey(sid)) { redirect(t, "/"); return; }
            
            String user = SESSION_USER.get(sid);
            boolean isAdmin = "ADMIN".equals(SESSIONS.get(sid));

            StringBuilder html = new StringBuilder();
            html.append("<html><head><style>").append(CSS).append("</style></head><body>");
            
            // SIDEBAR
            html.append("<div class='sidebar'>")
                .append("<div class='logo'>✦ Studio PDF</div>")
                .append("<div class='nav-item nav-active'>Tableau de bord</div>")
                .append("<div class='nav-item'>Mes fichiers</div>")
                .append("<div class='nav-item'>Paramètres</div>")
                .append("<div style='position:absolute; bottom:20px; width:220px;'>")
                .append("<div class='nav-item' onclick=\"location.href='/logout'\">Déconnexion (").append(user).append(")</div>")
                .append("</div></div>");

            // MAIN CONTENT
            html.append("<div class='content'>");
            
            if (isAdmin) {
                html.append("<div class='stats-grid'>")
                    .append("<div class='stat-card'><div class='stat-label'>Actifs</div><div class='stat-val'>").append(SESSIONS.size()).append("</div></div>")
                    .append("<div class='stat-card'><div class='stat-label'>Serveur</div><div class='stat-val' style='color:#10B981'>On</div></div>")
                    .append("<div class='stat-card'><div class='stat-label'>Actions</div><div class='stat-val'>").append(totalActions).append("</div></div>")
                    .append("</div>");
            }

            html.append("<h2 style='font-weight:600; margin-bottom:30px;'>Outils PDF</h2>")
                .append("<div class='tools-grid'>");
            
            String[] tools = {"Fusion", "Découpage", "Signature", "Compression", "Vers Image", "Texte", "Protection", "Métadonnées", "QR Code", "Optimisation", "Extraction", "Suppression"};
            for(String tool : tools) {
                html.append("<div class='tool-card'>")
                    .append("<div class='tool-icon'>◈</div>")
                    .append("<div style='font-weight:600; font-size:15px;'>").append(tool).append("</div>")
                    .append("<div style='font-size:12px; color:#9CA3AF; margin-top:4px;'>Lancer le traitement</div>")
                    .append("</div>");
            }

            html.append("</div></div></body></html>");
            sendHtml(t, html.toString());
        }
    }

    // ── HELPERS (Session, Parsing, Redirect) ─────────────────────────
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String body = new String(readAllBytes(t.getRequestBody()), StandardCharsets.UTF_8);
            Map<String, String> p = parseForm(body);
            String u = p.get("username"), pass = p.get("password");
            if (USERS.containsKey(u) && USERS.get(u).equals(pass)) {
                String sid = UUID.randomUUID().toString();
                SESSIONS.put(sid, ROLES.get(u)); SESSION_USER.put(sid, u);
                t.getResponseHeaders().add("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                redirect(t, "/home");
            } else { redirect(t, "/"); }
        }
    }

    static class LogoutHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String sid = getSessionId(t);
            if (sid != null) { SESSIONS.remove(sid); SESSION_USER.remove(sid); }
            t.getResponseHeaders().add("Set-Cookie", "session=; Path=/; Max-Age=0; HttpOnly");
            redirect(t, "/");
        }
    }

    static String getSessionId(HttpExchange t) {
        String c = t.getRequestHeaders().getFirst("Cookie");
        if (c == null) return null;
        for (String s : c.split(";")) if (s.trim().startsWith("session=")) return s.trim().substring(8);
        return null;
    }

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

    static Map<String, String> parseForm(String query) throws UnsupportedEncodingException {
        Map<String, String> result = new HashMap<>();
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length > 1) result.put(pair[0], URLDecoder.decode(pair[1], "UTF-8"));
        }
        return result;
    }

    static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead; byte[] data = new byte[16384];
        while ((nRead = is.read(data, 0, data.length)) != -1) buffer.write(data, 0, nRead);
        return buffer.toByteArray();
    }
}
