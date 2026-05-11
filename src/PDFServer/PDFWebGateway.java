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
    
    // Système de gestion de session simple
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "pass123";
    private static Map<String, Boolean> sessions = new HashMap<>();

    public static void main(String[] args) throws Exception {
        try {
            ORB orb = ORB.init(args, null);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));

            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            
            // Route d'authentification
            server.createContext("/login",    new LoginHandler());
            
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
            System.out.println("Studio PDF CORBA -> http://localhost:8080");
            server.start();
        } catch (Exception e) {
            System.err.println("ERREUR : " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ══════════════════════════════════════════════════════════
    //  MIDDLEWARE D'AUTHENTIFICATION
    // ══════════════════════════════════════════════════════════
    static boolean checkAuth(HttpExchange t) throws IOException {
        String cookieHeader = t.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader != null) {
            for (String cookie : cookieHeader.split(";")) {
                if (cookie.trim().startsWith("session=")) {
                    String sid = cookie.split("=")[1];
                    if (sessions.getOrDefault(sid, false)) return true;
                }
            }
        }
        t.getResponseHeaders().set("Location", "/login");
        t.sendResponseHeaders(303, -1);
        return false;
    }

    // ══════════════════════════════════════════════════════════
    //  CSS GLOBAL
    // ══════════════════════════════════════════════════════════
    static final String CSS =
        "<style>"
        + "*{box-sizing:border-box;margin:0;padding:0;font-family:'Inter',sans-serif}"
        + "body{background:#F5F3FF;min-height:100vh}"
        + ".topbar{background:linear-gradient(135deg,#4F1D96 0%,#6D28D9 40%,#7C3AED 70%,#8B5CF6 100%);padding:28px 28px 80px;position:relative;overflow:hidden}"
        + ".topbar-row{display:flex;justify-content:space-between;align-items:flex-start;position:relative;z-index:1}"
        + ".topbar h1{font-size:20px;font-weight:600;color:#fff;margin-bottom:4px}"
        + ".badge{display:inline-flex;align-items:center;gap:6px;background:rgba(255,255,255,0.15);border:0.5px solid rgba(255,255,255,0.3);color:#fff;padding:6px 14px;border-radius:20px;font-size:11px}"
        + ".dot{width:7px;height:7px;border-radius:50%;background:#4ADE80;animation:blink 1.5s infinite}"
        + "@keyframes blink{0%,100%{opacity:1}50%{opacity:0.3}}"
        + ".main{padding:0 24px 32px;margin-top:-48px;position:relative;z-index:2}"
        + ".stats{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:28px}"
        + ".stat{border-radius:16px;padding:20px;background:#fff}"
        + ".stat-n{font-size:26px;font-weight:600;color:#4C1D95}"
        + ".tools{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:24px}"
        + ".tc{background:#fff;border-radius:14px;padding:18px 14px;cursor:pointer;border:1.5px solid transparent;transition:all 0.2s}"
        + ".tc:hover{border-color:#A78BFA;transform:translateY(-3px);box-shadow:0 8px 24px rgba(124,58,237,0.12)}"
        + ".inp{background:#F8F7FF;border:1.5px solid #EDE9FE;border-radius:10px;padding:10px 14px;font-size:13px;width:100%;margin-bottom:10px;outline:none}"
        + ".btn-gen{width:100%;background:linear-gradient(135deg,#6D28D9,#7C3AED);color:#fff;border:none;padding:12px;border-radius:10px;font-weight:600;cursor:pointer}"
        + ".overlay{display:none;position:fixed;inset:0;background:rgba(79,29,150,0.2);backdrop-filter:blur(8px);z-index:100;align-items:center;justify-content:center}"
        + ".overlay.active{display:flex}"
        + ".modal{background:#fff;border-radius:20px;padding:32px;width:90%;max-width:400px;box-shadow:0 40px 80px rgba(0,0,0,0.1)}"
        + "</style>";

    // ══════════════════════════════════════════════════════════
    //  HANDLERS AUTH
    // ══════════════════════════════════════════════════════════
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                Map<String, String> params = parseQuery(new String(readAllBytes(t.getRequestBody())));
                if (ADMIN_USER.equals(params.get("user")) && ADMIN_PASS.equals(params.get("pass"))) {
                    String sid = UUID.randomUUID().toString();
                    sessions.put(sid, true);
                    t.getResponseHeaders().add("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                    t.getResponseHeaders().set("Location", "/");
                    t.sendResponseHeaders(303, -1);
                } else {
                    renderLogin(t, "Identifiants invalides");
                }
            } else {
                renderLogin(t, null);
            }
        }
        private void renderLogin(HttpExchange t, String err) throws IOException {
            String html = "<html><head><title>Login</title>" + CSS + "</head>"
                + "<body style='display:flex;align-items:center;justify-content:center'>"
                + "<div class='modal' style='display:block'><h2>Connexion</h2>"
                + (err != null ? "<p style='color:red;font-size:12px;margin:10px 0'>"+err+"</p>" : "")
                + "<form method='POST'><label>User</label><input name='user' class='inp'>"
                + "<label>Pass</label><input name='pass' type='password' class='inp'>"
                + "<button class='btn-gen'>Entrer</button></form></div></body></html>";
            sendHtml(t, html);
        }
    }

    // ══════════════════════════════════════════════════════════
    //  UI & LOGIQUE PDF
    // ══════════════════════════════════════════════════════════
    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if(!checkAuth(t)) return;
            String html = "<!DOCTYPE html><html lang='fr'><head>"
                + "<meta charset='UTF-8'><title>Studio PDF CORBA</title>"
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@400;600&display=swap' rel='stylesheet'>"
                + CSS + "</head><body>"
                + "<div class='topbar'><div class='topbar-row'>"
                + "<div><h1>Studio PDF CORBA</h1><p>Java 8 &times; PDFBox &times; CORBA</p></div>"
                + "<span class='badge'><span class='dot'></span>&nbsp;Connecté</span>"
                + "</div></div>"
                + "<div class='main'>"
                + "<div class='stats'>"
                + statItem("PDF Créés", nbCrees) + statItem("Extractions", nbExtractions)
                + statItem("Fusions", nbFusions) + statItem("Protégés", nbProtections)
                + "</div>"
                + "<div class='tools'>"
                + tc("#7C3AED", "Extraire texte", "m-extract") + tc("#0EA5E9", "Images", "m-image")
                + tc("#10B981", "Protéger", "m-protect") + tc("#F59E0B", "Fusionner", "m-merge")
                + "</div>"
                + "</div>"
                + modal("m-extract", "Extraire texte", "/extract", uploadZone("f1", "doc", false))
                + modal("m-image", "Convertir Image", "/image", uploadZone("f2", "doc", false) + "<select name='dpi' class='inp'><option value='150'>150 DPI</option></select>")
                + modal("m-protect", "Mot de passe", "/protect", uploadZone("f3", "doc", false) + "<input type='password' name='mdp' class='inp' placeholder='Pass...'>")
                + modal("m-merge", "Fusionner", "/merge", uploadZone("f4", "docs", true))
                + "<script>function openM(id){document.getElementById(id).classList.add('active')} function closeM(id){document.getElementById(id).classList.remove('active')}</script>"
                + "</body></html>";
            sendHtml(t, html);
        }
        private String statItem(String l, int v) { return "<div class='stat'><b>"+l+"</b><div class='stat-n'>"+v+"</div></div>"; }
        private String tc(String c, String t, String m) { return "<div class='tc' onclick='openM(\""+m+"\")' style='border-top:4px solid "+c+"'><h3>"+t+"</h3></div>"; }
    }

    // HANDLERS PDF (Exemples principaux)
    static class GenerateHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if(!checkAuth(t)) return;
            try {
                Map<String,String> p = parseQuery(t.getRequestURI().getQuery());
                byte[] pdf = pdfRef.creerPDF(p.getOrDefault("titre","Sans titre"), p.getOrDefault("corps",""));
                nbCrees++;
                sendPdf(t, pdf, "document.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class ExtractHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if(!checkAuth(t)) return;
            try {
                MultipartData mp = parseMultipart(t);
                String texte = pdfRef.extraireTexte(mp.files.get("doc"));
                nbExtractions++;
                sendHtml(t, "<h2>Texte Extrait :</h2><pre>"+escapeHtml(texte)+"</pre>");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  AUTRES HANDLERS (STUBS)
    // ══════════════════════════════════════════════════════════
    static class ToImageHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { /* Impl similaire à Extract */ } }
    static class ProtectHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { /* Impl */ } }
    static class MergeHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { /* Impl */ } }
    static class SplitHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { /* Impl */ } }
    static class DeleteHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { /* Impl */ } }
    static class ExtractPagesHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { /* Impl */ } }
    static class CompressHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { /* Impl */ } }
    static class MetaReadHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { /* Impl */ } }
    static class MetaModHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { /* Impl */ } }
    static class QRCodeHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { /* Impl */ } }
    static class SignHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { /* Impl */ } }

    // ══════════════════════════════════════════════════════════
    //  UTILITAIRES (DÉJÀ PRÉSENTS DANS VOTRE CODE)
    // ══════════════════════════════════════════════════════════
    static String uploadZone(String id, String name, boolean m) {
        return "<input type='file' id='"+id+"' name='"+name+"' "+(m?"multiple":"")+" class='inp'>";
    }

    static String modal(String id, String title, String action, String content) {
        return "<div class='overlay' id='"+id+"'><div class='modal'><h2>"+title+"</h2>"
            + "<form method='POST' enctype='multipart/form-data' action='"+action+"'>"+content
            + "<button class='btn-gen'>Valider</button><br><br>"
            + "<button type='button' class='btn-gen' style='background:#ccc' onclick='closeM(\""+id+"\")'>Annuler</button></form></div></div>";
    }

    static void sendPdf(HttpExchange t, byte[] d, String n) throws IOException {
        t.getResponseHeaders().set("Content-Type","application/pdf");
        t.getResponseHeaders().set("Content-Disposition","attachment; filename="+n);
        t.sendResponseHeaders(200, d.length);
        t.getResponseBody().write(d); t.getResponseBody().close();
    }

    static void sendHtml(HttpExchange t, String h) throws IOException {
        byte[] b = h.getBytes("UTF-8");
        t.getResponseHeaders().set("Content-Type","text/html; charset=UTF-8");
        t.sendResponseHeaders(200, b.length);
        t.getResponseBody().write(b); t.getResponseBody().close();
    }

    static void sendError(HttpExchange t, String m) throws IOException {
        sendHtml(t, "<h3>Erreur</h3><p>"+m+"</p><a href='/'>Retour</a>");
    }

    static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n=is.read(buf))!=-1) bos.write(buf,0,n);
        return bos.toByteArray();
    }

    static Map<String,String> parseQuery(String q) {
        Map<String,String> m = new HashMap<>();
        if (q==null) return m;
        for (String s : q.split("&")) {
            String[] kv = s.split("=",2);
            try { if (kv.length>1) m.put(kv[0], URLDecoder.decode(kv[1],"UTF-8")); } catch(Exception e){}
        }
        return m;
    }

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
    
    static String escapeHtml(String s) { return s==null?"":s.replace("<","&lt;").replace(">","&gt;"); }
}
