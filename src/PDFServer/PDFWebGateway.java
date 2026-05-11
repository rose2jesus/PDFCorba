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
    private static int nbCrees = 0, nbExtractions = 0, nbFusions = 0, nbProtections = 0;
    
    // Base de données utilisateurs en mémoire (Username -> Password)
    private static Map<String, String> userDatabase = new HashMap<>();
    // Gestion des sessions actives (SessionID -> Username)
    private static Map<String, String> sessions = new HashMap<>();

    static {
        // Compte administrateur par défaut
        userDatabase.put("admin", "pass123");
    }

    public static void main(String[] args) throws Exception {
        try {
            ORB orb = ORB.init(args, null);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));

            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            
            // Routes publiques (Authentification)
            server.createContext("/login",    new LoginHandler());
            server.createContext("/register", new RegisterHandler());
            
            // Routes protégées
            server.createContext("/",         new UIHandler());
            server.createContext("/create",   new GenerateHandler());
            server.createContext("/extract",  new ExtractHandler());
            server.createContext("/image",    new ToImageHandler());
            server.createContext("/protect",  new ProtectHandler());
            server.createContext("/merge",    new MergeHandler());
            server.createContext("/split",    new SplitHandler());
            server.createContext("/delete",   new DeleteHandler());
            server.createContext("/pages",    new ExtractPagesHandler());
            server.createContext("/compress", new CompressHandler());
            server.createContext("/meta",     new MetaReadHandler());
            server.createContext("/metamod",  new MetaModHandler());
            server.createContext("/qrcode",   new QRCodeHandler());
            server.createContext("/sign",     new SignHandler());
            
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
            System.out.println("Studio PDF avec Inscription lancé sur http://localhost:8080");
            server.start();
        } catch (Exception e) {
            System.err.println("ERREUR INITIALISATION : " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════
    //  MIDDLEWARE DE SÉCURITÉ
    // ══════════════════════════════════════════════════════════
    static boolean checkAuth(HttpExchange t) throws IOException {
        String cookieHeader = t.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader != null) {
            for (String cookie : cookieHeader.split(";")) {
                if (cookie.trim().startsWith("session=")) {
                    String sid = cookie.split("=")[1];
                    if (sessions.containsKey(sid)) return true;
                }
            }
        }
        t.getResponseHeaders().set("Location", "/login");
        t.sendResponseHeaders(303, -1);
        return false;
    }

    // ══════════════════════════════════════════════════════════
    //  HANDLERS D'AUTHENTIFICATION (LOGIN & REGISTER)
    // ══════════════════════════════════════════════════════════
    
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                Map<String, String> params = parseQuery(new String(readAllBytes(t.getRequestBody())));
                String user = params.get("user");
                String pass = params.get("pass");

                if (userDatabase.containsKey(user) && userDatabase.get(user).equals(pass)) {
                    String sid = UUID.randomUUID().toString();
                    sessions.put(sid, user);
                    t.getResponseHeaders().add("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                    t.getResponseHeaders().set("Location", "/");
                    t.sendResponseHeaders(303, -1);
                } else {
                    renderAuthPage(t, "Login", "Identifiants incorrects", false);
                }
            } else {
                renderAuthPage(t, "Login", null, false);
            }
        }
    }

    static class RegisterHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                Map<String, String> params = parseQuery(new String(readAllBytes(t.getRequestBody())));
                String user = params.get("user");
                String pass = params.get("pass");

                if (user != null && !user.isEmpty() && !userDatabase.containsKey(user)) {
                    userDatabase.put(user, pass);
                    t.getResponseHeaders().set("Location", "/login");
                    t.sendResponseHeaders(303, -1);
                } else {
                    renderAuthPage(t, "Inscription", "Utilisateur déjà existant ou invalide", true);
                }
            } else {
                renderAuthPage(t, "Inscription", null, true);
            }
        }
    }

    private static void renderAuthPage(HttpExchange t, String title, String err, boolean isRegister) throws IOException {
        String action = isRegister ? "/register" : "/login";
        String linkText = isRegister ? "Déjà un compte ? Se connecter" : "Pas de compte ? S'inscrire";
        String linkUrl = isRegister ? "/login" : "/register";

        String html = "<html><head><title>"+title+"</title>" + CSS + "</head>"
            + "<body style='display:flex;align-items:center;justify-content:center;background:#F3F4F6'>"
            + "<div class='modal' style='display:block; max-width:350px'>"
            + "<h2 style='text-align:center;margin-bottom:20px'>"+title+"</h2>"
            + (err != null ? "<div style='background:#FEE2E2;color:#991B1B;padding:10px;border-radius:8px;font-size:12px;margin-bottom:15px'>"+err+"</div>" : "")
            + "<form method='POST' action='"+action+"'>"
            + "<label style='font-size:12px;font-weight:600'>Utilisateur</label><input name='user' class='inp' required>"
            + "<label style='font-size:12px;font-weight:600'>Mot de passe</label><input name='pass' type='password' class='inp' required>"
            + "<button class='btn-gen'>"+title+"</button></form>"
            + "<div style='text-align:center;margin-top:20px'><a href='"+linkUrl+"' style='font-size:13px;color:#6D28D9;text-decoration:none'>"+linkText+"</a></div>"
            + "</div></body></html>";
        sendHtml(t, html);
    }

    // ══════════════════════════════════════════════════════════
    //  UI & LOGIQUE PDF (VOTRE CODE ORIGINAL PROTÉGÉ)
    // ══════════════════════════════════════════════════════════
    
    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if(!checkAuth(t)) return;
            // Utilisation de votre design original
            String html = "<!DOCTYPE html><html lang='fr'><head><meta charset='UTF-8'>" + CSS + "</head><body>"
                + "<div class='topbar'><div style='display:flex;justify-content:space-between;align-items:center'>"
                + "<div><h1>Studio PDF CORBA</h1></div>"
                + "<div style='color:white;font-size:12px'>Session active</div>"
                + "</div></div>"
                + "<div class='main'><div class='stats'>"
                + "<div class='stat'><b>PDF Créés</b><div class='stat-n'>"+nbCrees+"</div></div>"
                + "<div class='stat'><b>Extractions</b><div class='stat-n'>"+nbExtractions+"</div></div>"
                + "</div>"
                + "<div class='tools'>"
                + "<div class='tc' onclick='openM(\"m-extract\")'><h3>Extraire Texte</h3></div>"
                + "<div class='tc' onclick='openM(\"m-merge\")'><h3>Fusionner PDF</h3></div>"
                + "</div></div>"
                // Modals...
                + modal("m-extract", "Extraction", "/extract", uploadZone("f1","doc",false))
                + "<script>function openM(id){document.getElementById(id).classList.add('active')} function closeM(id){document.getElementById(id).classList.remove('active')}</script>"
                + "</body></html>";
            sendHtml(t, html);
        }
    }

    // Handlers PDF (Stubs pour l'exemple, à garder tels que dans votre code actuel)
    static class GenerateHandler implements HttpHandler { 
        public void handle(HttpExchange t) throws IOException {
            if(!checkAuth(t)) return;
            // ... votre logique originale ...
        }
    }

    static class ExtractHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if(!checkAuth(t)) return;
            try {
                MultipartData mp = parseMultipart(t);
                String texte = pdfRef.extraireTexte(mp.files.get("doc"));
                nbExtractions++;
                sendHtml(t, "<h2>Texte Extrait</h2><pre>"+texte+"</pre><br><a href='/'>Retour</a>");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  STUBS POUR LES AUTRES HANDLERS (A REMPLACER PAR VOTRE CODE)
    // ══════════════════════════════════════════════════════════
    static class ToImageHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { if(!checkAuth(t)) return; } }
    static class ProtectHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { if(!checkAuth(t)) return; } }
    static class MergeHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { if(!checkAuth(t)) return; } }
    static class SplitHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { if(!checkAuth(t)) return; } }
    static class DeleteHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { if(!checkAuth(t)) return; } }
    static class ExtractPagesHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { if(!checkAuth(t)) return; } }
    static class CompressHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { if(!checkAuth(t)) return; } }
    static class MetaReadHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { if(!checkAuth(t)) return; } }
    static class MetaModHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { if(!checkAuth(t)) return; } }
    static class QRCodeHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { if(!checkAuth(t)) return; } }
    static class SignHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { if(!checkAuth(t)) return; } }

    // ══════════════════════════════════════════════════════════
    //  UTILS (IDENTIQUES A VOTRE CODE)
    // ══════════════════════════════════════════════════════════
    static final String CSS = "<style>body{font-family:sans-serif;margin:0;background:#f4f7f6}.topbar{background:#6D28D9;padding:20px;color:white}.main{padding:20px}.stats{display:flex;gap:20px;margin-bottom:20px}.stat{background:white;padding:20px;border-radius:10px;flex:1;box-shadow:0 2px 5px rgba(0,0,0,0.1)}.stat-n{font-size:24px;font-weight:bold;color:#6D28D9}.tools{display:grid;grid-template-columns:1fr 1fr;gap:20px}.tc{background:white;padding:30px;text-align:center;border-radius:10px;cursor:pointer;border:2px solid transparent}.tc:hover{border-color:#6D28D9}.modal{display:none;position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);background:white;padding:30px;border-radius:15px;box-shadow:0 10px 30px rgba(0,0,0,0.2);z-index:10}.active{display:block}.inp{width:100%;padding:10px;margin:10px 0;border:1px solid #ddd;border-radius:5px}.btn-gen{width:100%;padding:12px;background:#6D28D9;color:white;border:none;border-radius:5px;cursor:pointer;font-weight:bold}</style>";

    static String uploadZone(String id, String name, boolean m) { return "<input type='file' name='"+name+"' "+(m?"multiple":"")+" class='inp' required>"; }
    static String modal(String id, String title, String action, String content) {
        return "<div id='"+id+"' class='modal'><h3>"+title+"</h3><form method='POST' enctype='multipart/form-data' action='"+action+"'>"+content+"<button class='btn-gen'>Exécuter</button><button type='button' onclick='closeM(\""+id+"\")' style='background:none;border:none;color:gray;cursor:pointer;width:100%;margin-top:10px'>Annuler</button></form></div>";
    }

    static void sendHtml(HttpExchange t, String h) throws IOException {
        byte[] b = h.getBytes("UTF-8");
        t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        t.sendResponseHeaders(200, b.length);
        t.getResponseBody().write(b); t.getResponseBody().close();
    }

    static void sendError(HttpExchange t, String m) throws IOException { sendHtml(t, "<h3>Erreur</h3><p>"+m+"</p><a href='/'>Retour</a>"); }
    static byte[] readAllBytes(InputStream is) throws IOException { ByteArrayOutputStream bos = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while((n=is.read(buf))!=-1) bos.write(buf,0,n); return bos.toByteArray(); }
    static Map<String,String> parseQuery(String q) { Map<String,String> m = new HashMap<>(); if(q==null) return m; for(String s:q.split("&")){String[] kv=s.split("=",2); if(kv.length>1) m.put(kv[0],kv[1]);} return m; }
    
    static class MultipartData { Map<String,byte[]> files = new HashMap<>(); }
    static MultipartData parseMultipart(HttpExchange t) throws Exception {
        MultipartData mp = new MultipartData();
        String ct = t.getRequestHeaders().getFirst("Content-Type");
        String boundary = "--" + ct.split("boundary=")[1].trim();
        String raw = new String(readAllBytes(t.getRequestBody()), "ISO-8859-1");
        String[] parts = raw.split(java.util.regex.Pattern.quote(boundary));
        for (String part : parts) {
            if (part.contains("filename=")) {
                String name = part.split("name=\"")[1].split("\"")[0];
                int start = part.indexOf("\r\n\r\n") + 4;
                int end = part.lastIndexOf("\r\n");
                mp.files.put(name, part.substring(start, end).getBytes("ISO-8859-1"));
            }
        }
        return mp;
    }
}
