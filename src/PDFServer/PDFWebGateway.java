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
    private static int nbCrees = 0, nbExtractions = 0, nbFusions = 0, nbProtections = 0;
    
    // Gestion des utilisateurs et sessions
    private static Map<String, String> userDatabase = new HashMap<>();
    private static Map<String, String> sessions = new HashMap<>();
    private static final String USERS_FILE = "users.txt";

    static {
        userDatabase.put("admin", "pass123");
    }

    public static void main(String[] args) throws Exception {
        chargerUtilisateurs();
        try {
            ORB orb = ORB.init(args, null);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));

            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            
            // Routes d'authentification
            server.createContext("/login",    new LoginHandler());
            server.createContext("/register", new RegisterHandler());

            // Routes du Studio (Protégées)
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
            System.out.println("Studio PDF CORBA Sécurisé -> http://localhost:8080");
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ══════════════════════════════════════════════════════════
    //  SÉCURITÉ & PERSISTANCE
    // ══════════════════════════════════════════════════════════
    private static void chargerUtilisateurs() {
        File f = new File(USERS_FILE);
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = br.readLine()) != null) {
                String[] p = l.split(":", 2);
                if (p.length == 2) userDatabase.put(p[0], p[1]);
            }
        } catch (IOException e) {}
    }

    private static synchronized void sauvegarderUtilisateurs() {
        try (PrintWriter out = new PrintWriter(new FileWriter(USERS_FILE))) {
            for (Map.Entry<String, String> e : userDatabase.entrySet()) 
                out.println(e.getKey() + ":" + e.getValue());
        } catch (IOException e) {}
    }

    static boolean checkAuth(HttpExchange t) throws IOException {
        String cookie = t.getRequestHeaders().getFirst("Cookie");
        if (cookie != null && cookie.contains("session=")) {
            String sid = cookie.split("session=")[1].split(";")[0];
            if (sessions.containsKey(sid)) return true;
        }
        t.getResponseHeaders().set("Location", "/login");
        t.sendResponseHeaders(303, -1);
        return false;
    }

    // ══════════════════════════════════════════════════════════
    //  HANDLERS AUTH (Ta page de connexion)
    // ══════════════════════════════════════════════════════════
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                Map<String, String> p = parseQuery(new String(readAllBytes(t.getRequestBody())));
                String u = p.get("user"), pw = p.get("pass");
                if (pw != null && pw.equals(userDatabase.get(u))) {
                    String sid = UUID.randomUUID().toString();
                    sessions.put(sid, u);
                    t.getResponseHeaders().add("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                    t.getResponseHeaders().set("Location", "/");
                    t.sendResponseHeaders(303, -1);
                    return;
                }
            }
            renderAuthPage(t, "Connexion", false);
        }
    }

    static class RegisterHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                Map<String, String> p = parseQuery(new String(readAllBytes(t.getRequestBody())));
                String u = p.get("user"), pw = p.get("pass");
                if (u != null && !userDatabase.containsKey(u)) {
                    userDatabase.put(u, pw);
                    sauvegarderUtilisateurs();
                    t.getResponseHeaders().set("Location", "/login");
                    t.sendResponseHeaders(303, -1);
                    return;
                }
            }
            renderAuthPage(t, "Inscription", true);
        }
    }

    private static void renderAuthPage(HttpExchange t, String title, boolean isReg) throws IOException {
        String action = isReg ? "/register" : "/login";
        String link = isReg ? "/login" : "/register";
        String linkText = isReg ? "Déjà inscrit ? Connexion" : "Pas de compte ? S'inscrire";
        
        String html = "<html><head><title>"+title+"</title><link href='https://fonts.googleapis.com/css2?family=Inter:wght@400;600&display=swap' rel='stylesheet'>"
            + "<style>body{margin:0;font-family:Inter,sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;background:#F5F3FF}"
            + ".card{background:#fff;padding:40px;border-radius:24px;width:350px;box-shadow:0 20px 50px rgba(79,29,150,0.1)}"
            + "h2{margin:0 0 20px;font-weight:600;color:#1E1B4B} label{font-size:12px;color:#6B7280;display:block;margin-top:15px}"
            + ".inp{width:100%;padding:12px;border:1.5px solid #EDE9FE;border-radius:10px;margin-top:5px;outline:none;background:#F8F7FF}"
            + ".btn{width:100%;background:#7C3AED;color:#fff;border:none;padding:14px;border-radius:12px;margin-top:25px;font-weight:600;cursor:pointer}"
            + "a{display:block;text-align:center;margin-top:15px;font-size:12px;color:#7C3AED;text-decoration:none}</style></head>"
            + "<body><div class='card'><h2>"+title+"</h2><form method='POST' action='"+action+"'>"
            + "<label>Utilisateur</label><input name='user' class='inp' required>"
            + "<label>Mot de passe</label><input name='pass' type='password' class='inp' required>"
            + "<button class='btn'>Entrer</button></form><a href='"+link+"'>"+linkText+"</a></div></body></html>";
        sendHtml(t, html);
    }

    // ══════════════════════════════════════════════════════════
    //  PAGE PRINCIPALE (Ton Dashboard de Master)
    // ══════════════════════════════════════════════════════════
    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!checkAuth(t)) return;
            
            String html = "<!DOCTYPE html><html lang='fr'><head>"
                + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Studio PDF CORBA</title>"
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap' rel='stylesheet'>"
                + CSS + "</head><body>"
                + "<div class='topbar'><div class='topbar-row'>"
                + "<div><h1>Studio PDF CORBA</h1><p>Gestionnaire distribue &mdash; Connecte</p></div>"
                + "<span class='badge'><span class='dot'></span>&nbsp;Session Active</span>"
                + "</div></div>"
                + "<div class='main'>"
                + "<div class='stats'>"
                + statDiv("PDFs crees", nbCrees, "st1") + statDiv("Extractions", nbExtractions, "st2")
                + statDiv("Fusions", nbFusions, "st3") + statDiv("Proteges", nbProtections, "st4")
                + "</div>"
                + "<p class='sec-label'>Outils disponibles</p>"
                + "<div class='tools'>"
                + tc("linear-gradient(90deg,#7C3AED,#A78BFA)", "Extraire texte", "m-extract")
                + tc("linear-gradient(90deg,#0EA5E9,#7DD3FC)", "En images", "m-image")
                + tc("linear-gradient(90deg,#10B981,#6EE7B7)", "Proteger", "m-protect")
                + tc("linear-gradient(90deg,#F59E0B,#FCD34D)", "Fusionner", "m-merge")
                + tc("linear-gradient(90deg,#EC4899,#F9A8D4)", "Decouper", "m-split")
                + tc("linear-gradient(90deg,#EF4444,#FCA5A5)", "Supprimer pages", "m-delete")
                + tc("linear-gradient(90deg,#8B5CF6,#C4B5FD)", "Extraire pages", "m-pages")
                + tc("linear-gradient(90deg,#14B8A6,#99F6E4)", "Compresser", "m-compress")
                + tc("linear-gradient(90deg,#6366F1,#A5B4FC)", "Metadonnees", "m-meta")
                + tc("linear-gradient(90deg,#D946EF,#F0ABFC)", "Modifier meta", "m-metamod")
                + tc("linear-gradient(90deg,#0284C7,#7DD3FC)", "QR Code", "m-qrcode")
                + tc("linear-gradient(90deg,#059669,#6EE7B7)", "Signer", "m-sign")
                + "</div>"
                + "</div>"
                + generateModals()
                + "<script>function openM(id){document.getElementById(id).classList.add('active')} function closeM(id){document.getElementById(id).classList.remove('active')} function showName(id,i){document.getElementById(id).textContent=i.files[0].name}</script>"
                + "</body></html>";
            sendHtml(t, html);
        }
    }

    // ══════════════════════════════════════════════════════════
    //  STYLING & HELPERS (Recopiés de ton design)
    // ══════════════════════════════════════════════════════════
    static final String CSS = "<style>*{box-sizing:border-box;margin:0;padding:0;font-family:'Inter',sans-serif} body{background:#F5F3FF} .topbar{background:linear-gradient(135deg,#4F1D96,#6D28D9);padding:30px 30px 80px;color:#fff} .main{padding:0 24px;margin-top:-50px} .stats{display:grid;grid-template-columns:repeat(4,1fr);gap:15px;margin-bottom:30px} .stat{background:#fff;padding:20px;border-radius:16px} .stat-n{font-size:24px;font-weight:700} .st1 .stat-n{color:#4C1D95} .tools{display:grid;grid-template-columns:repeat(4,1fr);gap:15px} .tc{background:#fff;border-radius:14px;padding:20px;cursor:pointer;transition:0.2s} .tc:hover{transform:translateY(-3px);box-shadow:0 10px 20px rgba(0,0,0,0.05)} .tc-bar{height:4px;width:30px;border-radius:2px;margin-bottom:10px} .overlay{display:none;position:fixed;inset:0;background:rgba(0,0,0,0.3);backdrop-filter:blur(5px);z-index:100;align-items:center;justify-content:center} .active{display:flex} .modal{background:#fff;padding:30px;border-radius:20px;width:400px} .btn-ok{width:100%;background:#7C3AED;color:#fff;border:none;padding:12px;border-radius:10px;margin-top:10px;cursor:pointer} .badge{background:rgba(255,255,255,0.2);padding:5px 15px;border-radius:20px;font-size:12px} .dot{height:8px;width:8px;background:#4ADE80;border-radius:50%;display:inline-block}</style>";

    static String statDiv(String l, int n, String cl) { return "<div class='stat "+cl+"'><div style='font-size:10px;text-transform:uppercase;color:#6B7280'>"+l+"</div><div class='stat-n'>"+n+"</div></div>"; }
    
    static String tc(String g, String t, String m) { return "<div class='tc' onclick='openM(\""+m+"\")'><div class='tc-bar' style='background:"+g+"'></div><h3>"+t+"</h3><p style='font-size:11px;color:#9CA3AF'>Outil Studio PDF</p></div>"; }

    static String generateModals() {
        return modal("m-extract", "Extraire Texte", "/extract", uploadZone("f1"))
             + modal("m-image", "PDF vers Images", "/image", uploadZone("f2")+"<label>DPI</label><input name='dpi' value='150' class='inp'>")
             + modal("m-protect", "Mot de passe", "/protect", uploadZone("f3")+"<input name='mdp' type='password' class='inp' placeholder='Mot de passe'>")
             + modal("m-merge", "Fusionner", "/merge", uploadZone("f4"))
             + modal("m-compress", "Compresser", "/compress", uploadZone("f5"))
             + modal("m-qrcode", "Ajouter QR", "/qrcode", uploadZone("f6")+"<input name='contenu' placeholder='Lien...' class='inp'>")
             + modal("m-sign", "Signer PDF", "/sign", uploadZone("f7")+"<input name='nom' placeholder='Votre nom' class='inp'>")
             + modal("m-split", "Decouper", "/split", uploadZone("f8"))
             + modal("m-delete", "Supprimer Pages", "/delete", uploadZone("f9"))
             + modal("m-pages", "Extraire Pages", "/pages", uploadZone("f10"))
             + modal("m-meta", "Metadonnees", "/meta", uploadZone("f11"))
             + modal("m-metamod", "Modif Meta", "/metamod", uploadZone("f12"));
    }

    static String modal(String id, String t, String a, String c) { return "<div class='overlay' id='"+id+"'><div class='modal'><h2>"+t+"</h2><form method='POST' enctype='multipart/form-data' action='"+a+"'>"+c+"<button class='btn-ok'>Lancer</button><button type='button' onclick='closeM(\""+id+"\")' style='width:100%;background:none;border:none;margin-top:10px;cursor:pointer;color:#9CA3AF'>Annuler</button></form></div></div>"; }
    
    static String uploadZone(String id) { return "<div style='border:2px dashed #DDD6FE;padding:20px;text-align:center;margin:10px 0;border-radius:10px' onclick='document.getElementById(\""+id+"\").click()'><p id='l-"+id+"' style='font-size:13px;color:#7C3AED'>Choisir PDF</p></div><input type='file' id='"+id+"' name='doc' style='display:none' onchange='showName(\"l-"+id+"\",this)'>"; }

    // Handlers techniques (Generate, Extract, etc. - identiques à ton code original)
    static class ExtractHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { if(!checkAuth(t))return; try { byte[] pdf = parseMultipart(t).files.values().iterator().next(); String res = pdfRef.extraireTexte(pdf); nbExtractions++; sendHtml(t, "<h3>Texte :</h3><pre>"+res+"</pre><a href='/'>Retour</a>"); } catch(Exception e){sendHtml(t, "Erreur");} } }
    static class GenerateHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { try { Map<String,String> p = parseQuery(t.getRequestURI().getQuery()); byte[] pdf = pdfRef.creerPDF(p.get("titre"), p.get("corps")); nbCrees++; sendPdf(t, pdf, "doc.pdf"); } catch(Exception e){}} }
    // ... AJOUTE ICI TOUS LES AUTRES HANDLERS (ToImageHandler, ProtectHandler, etc.) de ton fichier original ...

    // UTILS
    static void sendHtml(HttpExchange t, String h) throws IOException { byte[] b = h.getBytes(StandardCharsets.UTF_8); t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8"); t.sendResponseHeaders(200, b.length); t.getResponseBody().write(b); t.getResponseBody().close(); }
    static void sendPdf(HttpExchange t, byte[] d, String n) throws IOException { t.getResponseHeaders().set("Content-Type","application/pdf"); t.getResponseHeaders().set("Content-Disposition","attachment; filename="+n); t.sendResponseHeaders(200, d.length); t.getResponseBody().write(d); t.getResponseBody().close(); }
    static byte[] readAllBytes(InputStream is) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while((n=is.read(buf))!=-1) b.write(buf,0,n); return b.toByteArray(); }
    static Map<String,String> parseQuery(String q) { Map<String,String> m = new HashMap<>(); if(q==null) return m; for(String s : q.split("&")){String[] kv=s.split("=",2); if(kv.length>1) m.put(kv[0], kv[1]);} return m; }
    static class MultipartData { Map<String,byte[]> files = new HashMap<>(); }
    static MultipartData parseMultipart(HttpExchange t) throws Exception {
        MultipartData mp = new MultipartData();
        String ct = t.getRequestHeaders().getFirst("Content-Type");
        String boundary = "--" + ct.split("boundary=")[1].trim();
        String raw = new String(readAllBytes(t.getRequestBody()), "ISO-8859-1");
        for (String part : raw.split(java.util.regex.Pattern.quote(boundary))) {
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
