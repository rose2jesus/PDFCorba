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
    private static final Map<String, String> USERS = new HashMap<String, String>() {{ put("admin", "admin123"); }};
    private static final Map<String, String> ROLES = new HashMap<String, String>() {{ put("admin", "ADMIN"); }};
    private static final Map<String, String> SESSIONS = new HashMap<>(); 
    private static final Map<String, String> SESSION_USER = new HashMap<>(); 

    static final String CSS = "@import url('https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;600;700&display=swap'); :root { --primary: #6366f1; --bg: #f8fafc; } body{font-family:'Plus Jakarta Sans',sans-serif; background:var(--bg); margin:0; display:flex; color:#1e293b;} .sidebar{width:280px; background:#1e1b4b; height:100vh; position:fixed; color:white; padding:30px 20px; box-sizing:border-box;} .content{margin-left:280px; padding:40px; width:100%;} .logo{font-size:22px; font-weight:700; margin-bottom:40px; background:linear-gradient(90deg, #818cf8, #c084fc); -webkit-background-clip:text; -webkit-text-fill-color:transparent;} .nav-link{padding:12px 15px; border-radius:12px; cursor:pointer; margin-bottom:10px; display:flex; align-items:center; transition:0.3s; color:#94a3b8; text-decoration:none;} .nav-link:hover, .nav-active{background:rgba(255,255,255,0.1); color:white;} .stat-grid{display:grid; grid-template-columns:repeat(4,1fr); gap:20px; margin-bottom:40px;} .stat-card{padding:20px; border-radius:20px; color:white; box-shadow:0 10px 15px -3px rgba(0,0,0,0.1);} .tools-grid{display:grid; grid-template-columns:repeat(auto-fill, minmax(250px, 1fr)); gap:20px;} .tool-card{background:white; padding:25px; border-radius:24px; border:1px solid #f1f5f9; transition:0.3s;} .btn-tool{width:100%; padding:10px; border-radius:10px; border:none; font-weight:600; cursor:pointer; margin-top:15px; color:white;} .login-container{background:white; padding:40px; border-radius:30px; width:400px; box-shadow:0 25px 50px -12px rgba(0,0,0,0.1); text-align:center; margin: auto;} input{width:100%; padding:14px; margin:10px 0; border:1.5px solid #e2e8f0; border-radius:12px; box-sizing:border-box;} .btn-auth{background:#6366f1; color:white; border:none; padding:14px; border-radius:12px; width:100%; font-weight:700; cursor:pointer;}";

    public static void main(String[] args) throws Exception {
        // --- INITIALISATION CORBA ---
        try {
            ORB orb = ORB.init(args, null);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));
        } catch (Exception e) { System.err.println("CORBA Offline: " + e.getMessage()); }

        // --- PORT DYNAMIQUE POUR RENDER (CRITIQUE) ---
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", new LoginPageHandler());
        server.createContext("/register", new RegisterPageHandler());
        server.createContext("/do-login", new LoginActionHandler());
        server.createContext("/do-register", new RegisterActionHandler());
        server.createContext("/home", new HomeHandler());
        server.createContext("/logout", new LogoutHandler());

        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
        System.out.println("🚀 Serveur démarré sur port " + port);
        server.start();
    }

    static class LoginPageHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String html = "<html><head><style>" + CSS + "body{display:flex;height:100vh;background:#EEF2FF;}</style></head><body><div class='login-container'><div class='logo'>Studio PDF</div><h2>Connexion</h2><form action='/do-login' method='POST'><input name='username' placeholder='Username' required><input name='password' type='password' placeholder='Password' required><button type='submit' class='btn-auth'>Se connecter</button></form><p style='font-size:14px;'>Pas de compte ? <a href='/register'>S'inscrire</a></p></div></body></html>";
            sendHtml(t, html);
        }
    }

    static class RegisterPageHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String html = "<html><head><style>" + CSS + "body{display:flex;height:100vh;background:#F5F3FF;}</style></head><body><div class='login-container'><div class='logo'>Studio PDF</div><h2>Inscription</h2><form action='/do-register' method='POST'><input name='username' placeholder='Choisir Username' required><input name='password' type='password' placeholder='Choisir Password' required><button type='submit' class='btn-auth' style='background:#8b5cf6'>S'inscrire</button></form><a href='/'>Retour</a></div></body></html>";
            sendHtml(t, html);
        }
    }

    static class HomeHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String sid = getSessionId(t);
            if (sid == null || !SESSIONS.containsKey(sid)) { redirect(t, "/"); return; }
            String user = SESSION_USER.get(sid);
            boolean isAdmin = "ADMIN".equals(SESSIONS.get(sid));
            StringBuilder html = new StringBuilder("<html><head><style>"+CSS+"</style></head><body><div class='sidebar'><div class='logo'>STUDIO PDF</div><div class='nav-link nav-active'>Dashboard</div><a href='/logout' class='nav-link' style='margin-top:auto'>Déconnexion ("+user+")</a></div><div class='content'><h2>Supervision</h2>");
            if(isAdmin) html.append("<div class='stat-grid'><div class='stat-card' style='background:#a855f7'>Users: "+USERS.size()+"</div><div class='stat-card' style='background:#3b82f6'>Online: "+SESSIONS.size()+"</div><div class='stat-card' style='background:#10b981'>Status: OK</div><div class='stat-card' style='background:#f59e0b'>Actions: 256</div></div>");
            html.append("<h3>Outils PDF</h3><div class='tools-grid'>");
            String[][] tools = {{"Fusion", "#f0fdf4", "#22c55e"}, {"Découpage", "#fffbeb", "#f59e0b"}, {"Compression", "#f0fdfa", "#14b8a6"}, {"Protection", "#f8fafc", "#1e293b"}};
            for(String[] tool : tools) html.append("<div class='tool-card' style='background:"+tool[1]+"'><h4 style='color:"+tool[2]+"'>"+tool[0]+"</h4><button class='btn-tool' style='background:"+tool[2]+"'>Lancer</button></div>");
            html.append("</div></div></body></html>");
            sendHtml(t, html.toString());
        }
    }

    static class LoginActionHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody())));
            String u = p.get("username"), pass = p.get("password");
            if (USERS.containsKey(u) && USERS.get(u).equals(pass)) {
                String sid = UUID.randomUUID().toString();
                SESSIONS.put(sid, ROLES.getOrDefault(u, "USER")); SESSION_USER.put(sid, u);
                t.getResponseHeaders().add("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                redirect(t, "/home");
            } else { redirect(t, "/"); }
        }
    }

    static class RegisterActionHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody())));
            String u = p.get("username"), pass = p.get("password");
            if (u != null && !USERS.containsKey(u)) { USERS.put(u, pass); ROLES.put(u, "USER"); redirect(t, "/"); } 
            else { redirect(t, "/register"); }
        }
    }

    static void redirect(HttpExchange t, String url) throws IOException { t.getResponseHeaders().set("Location", url); t.sendResponseHeaders(302, -1); t.close(); }
    static void sendHtml(HttpExchange t, String html) throws IOException { byte[] b = html.getBytes(StandardCharsets.UTF_8); t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8"); t.sendResponseHeaders(200, b.length); t.getResponseBody().write(b); t.getResponseBody().close(); }
    static String getSessionId(HttpExchange t) { String c = t.getRequestHeaders().getFirst("Cookie"); if (c == null) return null; for (String s : c.split(";")) if (s.trim().startsWith("session=")) return s.trim().substring(8); return null; }
    static class LogoutHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { String sid = getSessionId(t); if (sid != null) { SESSIONS.remove(sid); SESSION_USER.remove(sid); } t.getResponseHeaders().add("Set-Cookie", "session=; Path=/; Max-Age=0; HttpOnly"); redirect(t, "/"); } }
    static Map<String, String> parseForm(String query) throws UnsupportedEncodingException { Map<String, String> result = new HashMap<>(); for (String param : query.split("&")) { String[] pair = param.split("="); if (pair.length > 1) result.put(pair[0], URLDecoder.decode(pair[1], "UTF-8")); } return result; }
    static byte[] readAllBytes(InputStream is) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); int n; byte[] d = new byte[16384]; while ((n = is.read(d, 0, d.length)) != -1) b.write(d, 0, n); return b.toByteArray(); }
}
