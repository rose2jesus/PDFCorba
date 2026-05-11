package PDFServer;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import PDFApp.*;
import org.omg.CosNaming.*;
import org.omg.CORBA.*;

public class PDFWebGateway {
    private static PDFService pdfRef;
    
    // Structure pour stocker les profils complets
    static class UserProfile {
        String username, password, nom, prenom, email, role;
        UserProfile(String u, String p, String n, String pr, String e, String r) {
            this.username = u; this.password = p; this.nom = n; this.prenom = pr; this.email = e; this.role = r;
        }
    }

    // Base de données simulée (L'admin est créé par défaut)
    private static final Map<String, UserProfile> USERS = new HashMap<String, UserProfile>() {{
        put("admin", new UserProfile("admin", "admin123", "Sarr", "Modou", "admin@ussin.sn", "ADMIN"));
    }};
    private static final Map<String, String> SESSIONS = new HashMap<>(); 

    // Design moderne et épuré
    static final String CSS = 
        "@import url('https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700&display=swap'); " +
        ":root { --primary: #6366f1; --bg: #f8fafc; --sidebar: #1e1b4b; --success: #10b981; } " +
        "body{font-family:'Plus Jakarta Sans',sans-serif; background:var(--bg); margin:0; display:flex; color:#1e293b;} " +
        ".sidebar{width:280px; background:var(--sidebar); height:100vh; position:fixed; color:white; padding:30px 20px; box-sizing:border-box;} " +
        ".content{margin-left:280px; padding:40px; width:calc(100% - 280px);} " +
        ".logo{font-size:24px; font-weight:800; margin-bottom:40px; background:linear-gradient(135deg, #a5b4fc, #e879f9); -webkit-background-clip:text; -webkit-text-fill-color:transparent;} " +
        ".nav-link{padding:14px 18px; border-radius:14px; margin-bottom:8px; display:flex; align-items:center; transition:0.2s; color:#94a3b8; text-decoration:none; font-weight:500;} " +
        ".nav-active{background:rgba(255,255,255,0.08); color:white;} " +
        ".tools-grid{display:grid; grid-template-columns:repeat(auto-fill, minmax(280px, 1fr)); gap:25px;} " +
        ".tool-card{background:white; padding:25px; border-radius:28px; border:1px solid #f1f5f9; transition:0.4s;} " +
        ".tool-card:hover{transform:translateY(-8px); box-shadow:0 20px 30px -10px rgba(0,0,0,0.05);} " +
        ".btn-tool{width:100%; padding:12px; border-radius:14px; border:none; font-weight:700; cursor:pointer; margin-top:15px; color:white; transition:0.3s;} " +
        ".form-container{background:white; padding:45px; border-radius:35px; width:420px; box-shadow:0 30px 60px -12px rgba(0,0,0,0.12); margin:auto;} " +
        "input{width:100%; padding:14px; margin:10px 0; border:2px solid #f1f5f9; border-radius:12px; box-sizing:border-box; transition:0.3s;} " +
        "input:focus{border-color:var(--primary); outline:none;} " +
        ".btn-auth{background:var(--primary); color:white; border:none; padding:16px; border-radius:15px; width:100%; font-weight:700; cursor:pointer;} " +
        "table{width:100%; border-collapse:collapse; background:white; border-radius:20px; overflow:hidden;} " +
        "th, td{text-align:left; padding:18px; border-bottom:1px solid #f1f5f9;}";

    public static void main(String[] args) throws Exception {
        // Connexion au bus CORBA
        try {
            ORB orb = ORB.init(args, null);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));
        } catch (Exception e) { System.err.println("CORBA Status: Waiting for Server components..."); }

        // Configuration du serveur Web
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        
        server.createContext("/", new LoginPageHandler());
        server.createContext("/register", new RegisterPageHandler());
        server.createContext("/do-login", new LoginActionHandler());
        server.createContext("/do-register", new RegisterActionHandler());
        server.createContext("/home", new HomeHandler());
        server.createContext("/process", new ProcessHandler());
        server.createContext("/logout", new LogoutHandler());
        
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(15));
        server.start();
        System.out.println("💎 STUDIO PDF GATEWAY : ON (Port " + port + ")");
    }

    // --- INTERFACE UTILISATEUR & ADMIN ---
    static class HomeHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String sid = getSessionId(t);
            if (sid == null || !SESSIONS.containsKey(sid)) { redirect(t, "/"); return; }
            UserProfile user = USERS.get(SESSIONS.get(sid));
            boolean isAdmin = "ADMIN".equals(user.role);

            StringBuilder html = new StringBuilder("<html><head><title>Studio PDF</title><style>"+CSS+"</style></head><body>");
            html.append("<div class='sidebar'><div class='logo'>STUDIO PDF</div><div class='nav-link nav-active'>").append(isAdmin ? "Supervision" : "Tableau de Bord").append("</div><a href='/logout' class='nav-link' style='margin-top:auto; color:#f87171;'>Quitter</a></div>");
            html.append("<div class='content'><h1>Bonjour, ").append(user.prenom).append(" ").append(user.nom).append("</h1>");

            if(isAdmin) {
                html.append("<h3>Utilisateurs du système</h3><table><tr><th>Login</th><th>Email</th><th>Rôle</th></tr>");
                for(UserProfile u : USERS.values()) {
                    html.append("<tr><td>").append(u.username).append("</td><td>").append(u.email).append("</td><td>").append(u.role).append("</td></tr>");
                }
                html.append("</table>");
            } else {
                html.append("<div class='tools-grid'>");
                // Liste des 12 fonctionnalités
                String[][] tools = {
                    {"Extraire Texte", "#a855f7", ""}, {"Fusionner PDFs", "#22c55e", "multiple"}, 
                    {"Signer PDF", "#15803d", ""}, {"Protéger PDF", "#1e293b", ""},
                    {"PDF en Images", "#3b82f6", ""}, {"Compresser PDF", "#14b8a6", ""},
                    {"Découper PDF", "#f59e0b", ""}, {"Supprimer Pages", "#ef4444", ""},
                    {"Extraire Pages", "#8b5cf6", ""}, {"Métadonnées", "#06b6d4", ""},
                    {"Modifier Meta", "#f97316", ""}, {"Ajouter QR", "#9333ea", ""}
                };
                for(String[] tool : tools) {
                    html.append("<div class='tool-card'><h3 style='color:").append(tool[1]).append(";'>").append(tool[0]).append("</h3>")
                        .append("<form action='/process' method='POST' enctype='multipart/form-data'>")
                        .append("<input type='file' name='f' required ").append(tool[2]).append(" style='font-size:11px; margin-bottom:10px;'>")
                        .append("<input type='hidden' name='a' value='").append(tool[0]).append("'>")
                        .append("<button class='btn-tool' style='background:").append(tool[1]).append("'>Traiter mon fichier</button></form></div>");
                }
                html.append("</div>");
            }
            html.append("</div></body></html>");
            sendHtml(t, html.toString());
        }
    }

    // --- LOGIQUE DE TRAITEMENT (SIMULATION CORBA) ---
    static class ProcessHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String html = "<html><head><style>" + CSS + "body{display:flex;align-items:center;justify-content:center;height:100vh; text-align:center;}</style></head><body>" +
                "<div class='form-container'><div style='font-size:50px;'>🚀</div>" +
                "<h2>Succès du traitement</h2>" +
                "<p>Le serveur CORBA a traité vos fichiers avec succès.</p>" +
                "<div style='background:#f0fdf4; padding:20px; border-radius:20px; margin:20px 0; border:2px dashed #22c55e;'>" +
                "📁 <a href='#' style='color:#15803d; font-weight:bold; text-decoration:none;'>Télécharger le PDF final</a></div>" +
                "<a href='/home' style='text-decoration:none;'><button class='btn-auth'>Retour</button></a></div>" +
                "<script>alert('Fichiers envoyés au serveur CORBA pour traitement !');</script></body></html>";
            sendHtml(t, html);
        }
    }

    // --- AUTHENTIFICATION ---
    static class LoginActionHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody())));
            String u = p.get("username"), pass = p.get("password");
            if (USERS.containsKey(u) && USERS.get(u).password.equals(pass)) {
                String sid = UUID.randomUUID().toString(); SESSIONS.put(sid, u);
                t.getResponseHeaders().add("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                redirect(t, "/home");
            } else { redirect(t, "/"); }
        }
    }

    static class RegisterActionHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody())));
            String u = p.get("username");
            if (u != null && !USERS.containsKey(u)) {
                USERS.put(u, new UserProfile(u, p.get("password"), p.get("nom"), p.get("prenom"), p.get("email"), "USER"));
                redirect(t, "/");
            } else { redirect(t, "/register"); }
        }
    }

    // --- PAGES ET UTILS ---
    static class LoginPageHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { 
        sendHtml(t, "<html><head><style>"+CSS+"body{display:flex;height:100vh;background:#EEF2FF;}</style></head><body><div class='form-container'><div class='logo' style='text-align:center;'>STUDIO PDF</div><h2 style='text-align:center;'>Connexion</h2><form action='/do-login' method='POST'><input name='username' placeholder='Nom d utilisateur' required><input name='password' type='password' placeholder='Mot de passe' required><button type='submit' class='btn-auth'>Se connecter</button></form><p style='text-align:center;'><a href='/register' style='text-decoration:none; color:var(--primary);'>Pas de compte ? S'inscrire</a></p></div></body></html>"); 
    } }
    
    static class RegisterPageHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { 
        sendHtml(t, "<html><head><style>"+CSS+"body{display:flex;height:100vh;background:#F5F3FF;}</style></head><body><div class='form-container'><h2>Créer un compte</h2><form action='/do-register' method='POST'><div style='display:flex; gap:10px;'><input name='prenom' placeholder='Prénom' required><input name='nom' placeholder='Nom' required></div><input name='email' type='email' placeholder='Email' required><input name='username' placeholder='Identifiant' required><input name='password' type='password' placeholder='Mot de passe' required><button type='submit' class='btn-auth' style='background:#8b5cf6'>S'enregistrer</button></form><center><a href='/' style='text-decoration:none; color:#94a3b8;'>Retour</a></center></div></body></html>"); 
    } }

    static class LogoutHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { String sid = getSessionId(t); if (sid != null) SESSIONS.remove(sid); t.getResponseHeaders().add("Set-Cookie", "session=; Path=/; Max-Age=0; HttpOnly"); redirect(t, "/"); } }
    
    static void redirect(HttpExchange t, String url) throws IOException { t.getResponseHeaders().set("Location", url); t.sendResponseHeaders(302, -1); t.close(); }
    static void sendHtml(HttpExchange t, String html) throws IOException { byte[] b = html.getBytes(StandardCharsets.UTF_8); t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8"); t.sendResponseHeaders(200, b.length); t.getResponseBody().write(b); t.getResponseBody().close(); }
    static String getSessionId(HttpExchange t) { String c = t.getRequestHeaders().getFirst("Cookie"); if (c == null) return null; for (String s : c.split(";")) if (s.trim().startsWith("session=")) return s.trim().substring(8); return null; }
    static Map<String, String> parseForm(String query) throws UnsupportedEncodingException { Map<String, String> result = new HashMap<>(); for (String param : query.split("&")) { String[] pair = param.split("="); if (pair.length > 1) result.put(pair[0], URLDecoder.decode(pair[1], "UTF-8")); } return result; }
    static byte[] readAllBytes(InputStream is) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); int n; byte[] d = new byte[16384]; while ((n = is.read(d, 0, d.length)) != -1) b.write(d, 0, n); return b.toByteArray(); }
}
