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
    
    // Statistiques de session (Simulées pour la démo)
    private static int totalActions = 0;

    // ── GESTION DES SESSIONS & UTILISATEURS ──────────────────────────
    private static final Map<String, String> SESSIONS = new HashMap<>(); // SID -> Role
    private static final Map<String, String> SESSION_USER = new HashMap<>(); // SID -> Email
    
    private static final Map<String, String> USERS = new HashMap<String, String>() {{
        put("admin@pdf.com", "admin123");
        put("etudiant@ussein.sn", "pass123");
    }};
    
    private static final Map<String, String> ROLES = new HashMap<String, String>() {{
        put("admin@pdf.com", "ADMIN");
        put("etudiant@ussein.sn", "USER");
    }};

    // ── HELPERS DE SESSION ───────────────────────────────────────
    static String getSessionId(HttpExchange t) {
        String cookie = t.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String c : cookie.split(";")) {
            c = c.trim();
            if (c.startsWith("session=")) return c.substring(8);
        }
        return null;
    }

    static boolean isLoggedIn(HttpExchange t) {
        String sid = getSessionId(t);
        return sid != null && SESSIONS.containsKey(sid);
    }

    static void redirect(HttpExchange t, String url) throws IOException {
        t.getResponseHeaders().set("Location", url);
        t.sendResponseHeaders(302, -1);
        t.getResponseBody().close();
    }

    // ── MAIN ─────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        try {
            ORB orb = ORB.init(args, null);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));

            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

            server.createContext("/",         new LoginPageHandler());
            server.createContext("/login",     new LoginHandler());
            server.createContext("/logout",    new LogoutHandler());
            server.createContext("/home",      new HomeHandler());
            
            // Les autres routes (create, extract, etc.) pointeraient vers leurs handlers respectifs
            
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
            System.out.println("Gateway Studio PDF lancé sur http://localhost:8080");
            server.start();
        } catch (Exception e) {
            System.err.println("Erreur CORBA/Serveur : " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════
    //  CSS & UI COMPONENTS
    // ══════════════════════════════════════════════════════
    static final String CSS = 
        "<style>" +
        "body{font-family:'Inter',sans-serif; background:#F5F3FF; margin:0;}" +
        ".topbar{background:#4F1D96; color:white; padding:15px 30px; display:flex; justify-content:space-between; align-items:center;}" +
        ".main{max-width:1100px; margin:30px auto; padding:0 20px;}" +
        ".admin-monitor{background:#FFFBEB; border:1.5px solid #FDE68A; padding:20px; border-radius:15px; margin-bottom:25px; color:#92400E;}" +
        ".grid{display:grid; grid-template-columns:repeat(4,1fr); gap:15px;}" +
        ".card{background:white; padding:20px; border-radius:12px; box-shadow:0 4px 6px rgba(0,0,0,0.05); text-align:center; transition:0.3s; cursor:pointer;}" +
        ".card:hover{transform:translateY(-5px); box-shadow:0 10px 15px rgba(79,29,150,0.1); border:1px solid #7C3AED;}" +
        ".btn-logout{color:white; text-decoration:none; border:1px solid rgba(255,255,255,0.4); padding:7px 15px; border-radius:8px; font-size:13px;}" +
        ".status-badge{background:#10B981; color:white; padding:2px 8px; border-radius:10px; font-size:11px;}" +
        "</style>";

    // ══════════════════════════════════════════════════════
    //  HANDLERS
    // ══════════════════════════════════════════════════════
    
    static class LoginPageHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (isLoggedIn(t)) { redirect(t, "/home"); return; }
            String html = "<html><head><style>" +
                "body{background:#6D28D9; display:flex; align-items:center; justify-content:center; height:100vh; font-family:sans-serif;}" +
                ".login-box{background:white; padding:40px; border-radius:20px; width:320px; text-align:center;}" +
                "input{width:100%; padding:12px; margin:10px 0; border:1px solid #DDD; border-radius:10px;}" +
                "button{width:100%; padding:12px; background:#4F1D96; color:white; border:none; border-radius:10px; cursor:pointer;}" +
                "</style></head><body><div class='login-box'><h2>Studio PDF</h2>" +
                "<form action='/login' method='POST'>" +
                "<input name='email' placeholder='Email USSEIN' required>" +
                "<input name='password' type='password' placeholder='Mot de passe' required>" +
                "<button type='submit'>Se connecter</button></form></div></body></html>";
            sendHtml(t, html);
        }
    }

    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String body = new String(readAllBytes(t.getRequestBody()), StandardCharsets.UTF_8);
            Map<String, String> params = parseForm(body);
            String email = params.get("email");
            String pass = params.get("password");

            if (USERS.containsKey(email) && USERS.get(email).equals(pass)) {
                String sid = UUID.randomUUID().toString();
                SESSIONS.put(sid, ROLES.get(email));
                SESSION_USER.put(sid, email);
                t.getResponseHeaders().add("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                redirect(t, "/home");
            } else {
                redirect(t, "/?error=1");
            }
        }
    }

    static class HomeHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/"); return; }
            
            String sid = getSessionId(t);
            String role = SESSIONS.get(sid);
            String userEmail = SESSION_USER.get(sid);
            boolean isAdmin = "ADMIN".equals(role);

            StringBuilder html = new StringBuilder();
            html.append("<html><head><meta charset='UTF-8'>").append(CSS).append("</head><body>");
            
            // BARRE SUPÉRIEURE
            html.append("<div class='topbar'>")
                .append("<span><b>STUDIO PDF</b> | ").append(userEmail).append("</span>")
                .append("<a href='/logout' class='btn-logout'>Déconnexion</a>")
                .append("</div>");

            html.append("<div class='main'>");

            // MONITORING ADMIN (Uniquement si ADMIN)
            if (isAdmin) {
                html.append("<div class='admin-monitor'>")
                    .append("<h3><span style='margin-right:10px;'>📊</span> Supervision Administrateur</h3>")
                    .append("<div style='display:flex; gap:30px;'>")
                    .append("<div>Utilisateurs en ligne : <b>").append(SESSIONS.size()).append("</b></div>")
                    .append("<div>Actions Serveur : <b>").append(totalActions).append("</b></div>")
                    .append("<div>Serveur CORBA : <span class='status-badge'>OPÉRATIONNEL</span></div>")
                    .append("</div>")
                    .append("<div style='margin-top:10px; font-size:12px;'>Dernier connecté : ").append(userEmail).append("</div>")
                    .append("</div>");
            }

            // GRILLE DES 12 OUTILS (Pour TOUT LE MONDE)
            html.append("<h2>Mes Outils PDF</h2>")
                .append("<div class='grid'>")
                .append(toolCard("Extraire Texte", "Analyse"))
                .append(toolCard("PDF vers Images", "Conversion"))
                .append(toolCard("Protéger (MDP)", "Sécurité"))
                .append(toolCard("Fusionner PDFs", "Assemblage"))
                .append(toolCard("Découper", "Édition"))
                .append(toolCard("Supprimer Pages", "Édition"))
                .append(toolCard("Extraire Pages", "Extraction"))
                .append(toolCard("Compresser", "Optimisation"))
                .append(toolCard("Métadonnées", "Information"))
                .append(toolCard("Modifier Meta", "Édition"))
                .append(toolCard("Ajouter QR Code", "Enrichissement"))
                .append(toolCard("Signer (RSA)", "Sécurité"))
                .append("</div>");

            html.append("</div></body></html>");
            sendHtml(t, html.toString());
        }

        private String toolCard(String name, String cat) {
            return "<div class='card'><div style='font-size:10px; color:#6D28D9; font-weight:700; margin-bottom:5px;'>" + cat.toUpperCase() + "</div>"
                 + "<h3>" + name + "</h3><p style='font-size:12px; color:#9CA3AF;'>Lancer l'outil</p></div>";
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

    // ── UTILS ────────────────────────────────────────────────────
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
