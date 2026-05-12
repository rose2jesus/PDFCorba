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

    // ── Sessions et utilisateurs ─────────────────────────
    private static final Map<String, String> SESSIONS = new HashMap<>();
    private static final Map<String, String> USERS = new HashMap<>();
    private static final Map<String, String> ROLES = new HashMap<>();

    static {
        USERS.put("admin@pdf.com", "admin123");
        USERS.put("user@pdf.com",  "user123");
        ROLES.put("admin@pdf.com", "admin");
        ROLES.put("user@pdf.com",  "user");
    }

    // ── Helpers session ──────────────────────────────────
    static String getSession(HttpExchange t) {
        String cookie = t.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String c : cookie.split(";")) {
            c = c.trim();
            if (c.startsWith("session=")) return c.substring(8);
        }
        return null;
    }

    static String getRole(HttpExchange t) {
        String sid = getSession(t);
        if (sid == null) return null;
        return SESSIONS.get(sid);
    }

    static boolean isLoggedIn(HttpExchange t) { return getRole(t) != null; }
    static boolean isAdmin(HttpExchange t)    { return "admin".equals(getRole(t)); }

    static void redirect(HttpExchange t, String url) throws IOException {
        t.getResponseHeaders().set("Location", url);
        t.sendResponseHeaders(302, -1);
        t.getResponseBody().close();
    }

    // ── Main ─────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        try {
            ORB orb = ORB.init(args, null);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));

            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.createContext("/",         new UIHandler());
            server.createContext("/login",    new LoginHandler());
            server.createContext("/logout",   new LogoutHandler());
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

    // ════════════════════════════════════════════════════
    //  PAGE DE CONNEXION (CORRIGÉ POUR RENDER)
    // ════════════════════════════════════════════════════
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                try {
                    byte[] body = readAllBytes(t.getRequestBody());
                    Map<String,String> params = parseForm(new String(body, "UTF-8"));
                    String email = params.getOrDefault("email", "");
                    String pass  = params.getOrDefault("password", "");

                    if (USERS.containsKey(email) && USERS.get(email).equals(pass)) {
                        String sid = UUID.randomUUID().toString();
                        SESSIONS.put(sid, ROLES.get(email));
                        t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                        redirect(t, "/");
                    } else {
                        sendHtml(t, loginPage("Email ou mot de passe incorrect."));
                    }
                } catch (Exception e) {
                    sendError(t, "Erreur de connexion : " + e.getMessage());
                }
            } else {
                if (isLoggedIn(t)) { redirect(t, "/"); return; }
                sendHtml(t, loginPage(null));
            }
        }
    }

    static String loginPage(String error) {
        return "<!DOCTYPE html><html lang='fr'><head><meta charset='UTF-8'>"
            + "<title>Connexion — Studio PDF CORBA</title>"
            + "<style>"
            + "*{box-sizing:border-box;margin:0;padding:0;font-family:sans-serif}"
            + "body{background:linear-gradient(135deg,#4F1D96 0%,#2D3B8E 100%);min-height:100vh;display:flex;align-items:center;justify-content:center}"
            + ".card{background:white;border-radius:24px;padding:40px;width:400px;box-shadow:0 20px 40px rgba(0,0,0,0.2)}"
            + "h1{color:#4F1D96;text-align:center;margin-bottom:20px}input{width:100%;padding:12px;margin:10px 0;border:1px solid #ddd;border-radius:8px}"
            + ".btn{width:100%;background:#4F1D96;color:white;border:none;padding:14px;border-radius:8px;cursor:pointer;font-weight:bold}"
            + ".error{color:red;text-align:center;margin-bottom:10px}</style></head><body>"
            + "<div class='card'><h1>Studio PDF</h1>"
            + (error != null ? "<div class='error'>" + error + "</div>" : "")
            + "<form method='post' action='/login'>"
            + "<input type='email' name='email' placeholder='Email' required>"
            + "<input type='password' name='password' placeholder='Mot de passe' required>"
            + "<button class='btn' type='submit'>Connexion</button></form></div></body></html>";
    }

    static class LogoutHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String sid = getSession(t);
            if (sid != null) SESSIONS.remove(sid);
            t.getResponseHeaders().set("Set-Cookie", "session=; Path=/; Max-Age=0");
            redirect(t, "/login");
        }
    }

    // ── Handlers PDF ─────────────────────────────────────
    static class GenerateHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
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
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                byte[] pdf = parseMultipart(t).files.values().iterator().next();
                String texte = pdfRef.extraireTexte(pdf);
                nbExtractions++;
                sendHtml(t, "<h3>Texte extrait :</h3><pre>" + escapeHtml(texte) + "</pre><br><a href='/'>Retour</a>");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class ToImageHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                int dpi = Integer.parseInt(mp.fields.getOrDefault("dpi","150"));
                byte[][] images = pdfRef.convertirEnImages(mp.files.get("doc"), dpi);
                StringBuilder sb = new StringBuilder("<h3>Images extraites :</h3>");
                for (byte[] img : images) {
                    String b64 = Base64.getEncoder().encodeToString(img);
                    sb.append("<img src='data:image/png;base64,").append(b64).append("' style='width:100%;margin-bottom:10px'>");
                }
                sendHtml(t, sb.append("<br><a href='/'>Retour</a>").toString());
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class ProtectHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isAdmin(t)) { sendError(t, "Accès restreint"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                byte[] res = pdfRef.ajouterMotDePasse(mp.files.get("doc"), mp.fields.getOrDefault("mdp","1234"));
                nbProtections++;
                sendPdf(t, res, "protected.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class MergeHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isAdmin(t)) { sendError(t, "Accès restreint"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                List<byte[]> docs = mp.fileList.get("docs");
                byte[] res = pdfRef.fusionnerPDFs(docs.toArray(new byte[0][]));
                nbFusions++;
                sendPdf(t, res, "fusion.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    // ── Autres Handlers Admin ────────────────────────────
    static class SplitHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isAdmin(t)) { sendError(t, "Accès restreint"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                byte[][] parts = pdfRef.decouperPDF(mp.files.get("doc"), Integer.parseInt(mp.fields.get("nb")));
                sendPdf(t, parts[0], "partie1.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class CompressHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            try {
                byte[] pdf = parseMultipart(t).files.values().iterator().next();
                sendPdf(t, pdfRef.compresserPDF(pdf), "compressed.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class QRCodeHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            try {
                MultipartData mp = parseMultipart(t);
                byte[] res = pdfRef.ajouterQRCode(mp.files.get("doc"), mp.fields.get("contenu"), 0, 400, 50);
                sendPdf(t, res, "qrcode.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class SignHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            try {
                MultipartData mp = parseMultipart(t);
                byte[] res = pdfRef.signerPDF(mp.files.get("doc"), mp.fields.get("nom"), "Approbation", "Dakar");
                sendPdf(t, res, "signe.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }
    
    // Handlers manquants pour compléter les 13 services
    static class DeleteHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { sendError(t, "Service en cours"); } }
    static class ExtractPagesHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { sendError(t, "Service en cours"); } }
    static class MetaReadHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { sendError(t, "Service en cours"); } }
    static class MetaModHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { sendError(t, "Service en cours"); } }

    // ── Interface Principale ─────────────────────────────
    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            boolean admin = isAdmin(t);
            StringBuilder html = new StringBuilder("<html><body style='font-family:sans-serif; padding:20px;'>");
            html.append("<h1>Studio PDF CORBA</h1>");
            html.append("<p>Utilisateur : ").append(getRole(t)).append(" | <a href='/logout'>Déconnexion</a></p>");

            if (admin) {
                html.append("<div style='background:#eee; padding:10px; margin-bottom:20px;'>");
                html.append("<b>Stats Admin :</b> Créés: ").append(nbCrees)
                    .append(" | Fusions: ").append(nbFusions).append("</div>");
            }

            html.append("<h3>Outils PDF :</h3>");
            html.append("<ul><li><a href='#' onclick='document.getElementById(\"m-create\").style.display=\"block\"'>Créer PDF</a></li>");
            html.append("<li><a href='#' onclick='document.getElementById(\"m-extract\").style.display=\"block\"'>Extraire Texte</a></li>");
            
            if (admin) {
                html.append("<li><a href='#' onclick='document.getElementById(\"m-merge\").style.display=\"block\"'>Fusionner PDFs (Admin)</a></li>");
                html.append("<li><a href='#' onclick='document.getElementById(\"m-protect\").style.display=\"block\"'>Protéger (Admin)</a></li>");
            }
            html.append("</ul>");

            // Modals simples pour l'exemple
            html.append("<div id='m-create' style='display:none; border:1px solid #ccc; padding:10px;'><h4>Créer</h4><form action='/create'><input name='titre' placeholder='Titre'><input name='corps' placeholder='Corps'><button>Générer</button></form></div>");
            html.append("<div id='m-extract' style='display:none; border:1px solid #ccc; padding:10px;'><h4>Extraire</h4><form action='/extract' method='post' enctype='multipart/form-data'><input type='file' name='doc'><button>Analyser</button></form></div>");

            html.append("</body></html>");
            sendHtml(t, html.toString());
        }
    }

    // ── Utilitaires Système ──────────────────────────────
    static class MultipartData {
        Map<String,byte[]> files = new HashMap<>();
        Map<String,List<byte[]>> fileList = new HashMap<>();
        Map<String,String> fields = new HashMap<>();
    }

    static MultipartData parseMultipart(HttpExchange t) throws Exception {
        MultipartData mp = new MultipartData();
        String ct = t.getRequestHeaders().getFirst("Content-Type");
        String boundary = "--" + ct.split("boundary=")[1].trim();
        byte[] body = readAllBytes(t.getRequestBody());
        String raw = new String(body, "ISO-8859-1");
        String[] parts = raw.split(java.util.regex.Pattern.quote(boundary));
        for (String part : parts) {
            if (part.trim().isEmpty() || part.contains("--\r\n")) continue;
            int hEnd = part.indexOf("\r\n\r\n");
            if (hEnd < 0) continue;
            String headers = part.substring(0, hEnd);
            String dataRaw = part.substring(hEnd + 4);
            if (dataRaw.endsWith("\r\n")) dataRaw = dataRaw.substring(0, dataRaw.length()-2);
            if (headers.contains("filename=")) {
                String name = "doc"; 
                byte[] data = dataRaw.getBytes("ISO-8859-1");
                mp.files.put(name, data);
                mp.fileList.computeIfAbsent("docs", k -> new ArrayList<>()).add(data);
            } else { mp.fields.put("mdp", dataRaw.trim()); }
        }
        return mp;
    }

    static Map<String,String> parseForm(String body) throws Exception {
        Map<String,String> m = new HashMap<>();
        for (String s : body.split("&")) {
            String[] kv = s.split("=", 2);
            if (kv.length > 1) m.put(URLDecoder.decode(kv[0],"UTF-8"), URLDecoder.decode(kv[1],"UTF-8"));
        }
        return m;
    }

    static Map<String,String> parseQuery(String q) throws Exception {
        return (q == null) ? new HashMap<>() : parseForm(q);
    }

    static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    static void sendPdf(HttpExchange t, byte[] data, String name) throws IOException {
        t.getResponseHeaders().set("Content-Type","application/pdf");
        t.getResponseHeaders().set("Content-Disposition","attachment; filename="+name);
        t.sendResponseHeaders(200, data.length);
        t.getResponseBody().write(data);
        t.getResponseBody().close();
    }

    static void sendHtml(HttpExchange t, String html) throws IOException {
        byte[] b = html.getBytes("UTF-8");
        t.getResponseHeaders().set("Content-Type","text/html; charset=UTF-8");
        t.sendResponseHeaders(200, b.length);
        t.getResponseBody().write(b);
        t.getResponseBody().close();
    }

    static void sendError(HttpExchange t, String msg) throws IOException {
        sendHtml(t, "<h2 style='color:red'>Erreur</h2><p>" + escapeHtml(msg) + "</p><a href='/'>Retour</a>");
    }

    static String escapeHtml(String s) {
        return (s == null) ? "" : s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }
}
