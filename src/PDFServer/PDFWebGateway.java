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

    private static final Map<String, String> SESSIONS = new HashMap<>();
    private static final Map<String, String> USERS = new HashMap<>();
    private static final Map<String, String> ROLES = new HashMap<>();

    static {
        USERS.put("admin@pdf.com", "admin123");
        USERS.put("user@pdf.com",  "user123");
        ROLES.put("admin@pdf.com", "admin");
        ROLES.put("user@pdf.com",  "user");
    }

    // ── MAIN AVEC BOUCLE DE RECONNEXION ──────────────────
    public static void main(String[] args) throws Exception {
        try {
            // Configuration pour Render (localhost interne)
            Properties props = new Properties();
            props.put("org.omg.CORBA.ORBInitialPort", "1050");
            props.put("org.omg.CORBA.ORBInitialHost", "127.0.0.1");
            
            ORB orb = ORB.init(args, props);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);

            // Tentatives de connexion au PDFService (évite le NotFound prématuré)
            int attempts = 0;
            while (pdfRef == null && attempts < 15) {
                try {
                    pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));
                    System.out.println(">>> Connecté au PDFService avec succès !");
                } catch (Exception e) {
                    attempts++;
                    System.out.println("Attente du serveur (tentative " + attempts + "/15)...");
                    Thread.sleep(4000);
                }
            }

            if (pdfRef == null) {
                System.err.println("ERREUR : PDFService introuvable. Arrêt.");
                System.exit(1);
            }

            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            
            // Routes
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
            server.createContext("/qrcode",   new QRCodeHandler());
            server.createContext("/sign",     new SignHandler());

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
            System.out.println("Studio PDF prêt sur http://localhost:8080");
            server.start();
        } catch (Exception e) {
            System.err.println("Erreur fatale : " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ── GESTIONNAIRES AUTHENTIFICATION ──────────────────
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                Map<String,String> params = parseForm(new String(readAllBytes(t.getRequestBody()), "UTF-8"));
                String email = params.getOrDefault("email", "");
                String pass  = params.getOrDefault("password", "");

                if (USERS.containsKey(email) && USERS.get(email).equals(pass)) {
                    String sid = UUID.randomUUID().toString();
                    SESSIONS.put(sid, ROLES.get(email));
                    t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                    redirect(t, "/");
                } else {
                    sendHtml(t, loginPage("Identifiants invalides."));
                }
            } else {
                if (isLoggedIn(t)) { redirect(t, "/"); return; }
                sendHtml(t, loginPage(null));
            }
        }
    }

    static class LogoutHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String sid = getSession(t);
            if (sid != null) SESSIONS.remove(sid);
            t.getResponseHeaders().set("Set-Cookie", "session=; Path=/; Max-Age=0");
            redirect(t, "/login");
        }
    }

    // ── INTERFACE GRAPHIQUE ─────────────────────────────
    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            boolean admin = isAdmin(t);
            String role = admin ? "admin" : "user";

            String html = "<!DOCTYPE html><html lang='fr'><head><meta charset='UTF-8'>"
                + "<title>Studio PDF CORBA</title>"
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&display=swap' rel='stylesheet'>"
                + CSS + "</head><body>"
                + "<div class='topbar'><div class='topbar-row'>"
                + "<h1>Studio PDF CORBA</h1>"
                + "<div class='topbar-right'><span class='role-badge " + role + "'>" + (admin ? "ADMIN" : "USER") + "</span>"
                + "<a href='/logout' class='logout-btn'>Déconnexion</a></div></div></div>"
                + "<div class='main'>";

            if (admin) {
                html += "<div class='stats'>"
                    + stat("Créés", nbCrees, "st1") + stat("Extraits", nbExtractions, "st2")
                    + stat("Fusions", nbFusions, "st3") + stat("Protégés", nbProtections, "st4")
                    + "</div>";
            }

            html += "<p class='sec-label'>Vos outils</p><div class='tools'>"
                + tc("#7C3AED", "Extraire texte", "m-extract") + tc("#0EA5E9", "En images", "m-image")
                + tc("#14B8A6", "Créer PDF", "m-create");

            if (admin) {
                html += tc("#10B981", "Protéger", "m-protect") + tc("#F59E0B", "Fusionner", "m-merge")
                    + tc("#EC4899", "Découper", "m-split") + tc("#EF4444", "Supprimer", "m-delete")
                    + tc("#8B5CF6", "Compresser", "m-compress") + tc("#D946EF", "QR Code", "m-qrcode");
            }

            html += "</div></div>"
                + modals(admin)
                + "<script>function openM(id){document.getElementById(id).style.display='flex'}"
                + "function closeM(id){document.getElementById(id).style.display='none'}</script></body></html>";
            sendHtml(t, html);
        }
        String stat(String l, int n, String c) { return "<div class='stat "+c+"'><div class='stat-l'>"+l+"</div><div class='stat-n'>"+n+"</div></div>"; }
        String tc(String c, String t, String m) { return "<div class='tc' onclick='openM(\""+m+"\")'><div style='height:3px;background:"+c+"'></div><h3>"+t+"</h3></div>"; }
    }

    // ── HANDLERS FONCTIONNELS (EXTRAIT) ─────────────────
    static class GenerateHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            try {
                Map<String,String> p = parseQuery(t.getRequestURI().getQuery());
                byte[] pdf = pdfRef.creerPDF(p.getOrDefault("titre","Sans titre"), p.getOrDefault("corps",""));
                nbCrees++;
                sendPdf(t, pdf, "document.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    // ── STYLES ET UTILS ────────────────────────────────
    static final String CSS = "<style>*{box-sizing:border-box;margin:0;padding:0;font-family:'Inter',sans-serif}body{background:#F5F3FF}.topbar{background:#4F1D96;padding:20px;color:white}.topbar-row{display:flex;justify-content:space-between;align-items:center}.main{padding:20px;max-width:1000px;margin:auto}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:20px}.stat{background:white;padding:15px;border-radius:10px;text-align:center}.tools{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:15px}.tc{background:white;padding:20px;border-radius:10px;cursor:pointer;transition:0.2s}.tc:hover{transform:translateY(-3px)}.role-badge{padding:4px 10px;border-radius:20px;font-size:10px;font-weight:bold;background:rgba(255,255,255,0.2)}.modal-overlay{display:none;position:fixed;inset:0;background:rgba(0,0,0,0.5);align-items:center;justify-content:center}.modal{background:white;padding:30px;border-radius:15px;width:400px}input{width:100%;padding:10px;margin:10px 0;border:1px solid #DDD;border-radius:5px}</style>";

    static String modals(boolean admin) {
        String m = modal("m-create", "Nouveau PDF", "/create", "<input name='titre' placeholder='Titre'><input name='corps' placeholder='Texte'>");
        if(admin) m += modal("m-protect", "Protéger", "/protect", "<input type='file' name='doc'><input name='mdp' placeholder='Pass'>");
        return m;
    }

    static String modal(String id, String t, String a, String c) {
        return "<div class='modal-overlay' id='"+id+"'><div class='modal'><h2>"+t+"</h2><form action='"+a+"'>"+c+"<button>Valider</button><button type='button' onclick='closeM(\""+id+"\")'>Annuler</button></form></div></div>";
    }

    // ── LOGIQUE COMMUNE ────────────────────────────────
    static boolean isLoggedIn(HttpExchange t) { return getRole(t) != null; }
    static boolean isAdmin(HttpExchange t)    { return "admin".equals(getRole(t)); }
    static String getRole(HttpExchange t)     { String sid = getSession(t); return sid != null ? SESSIONS.get(sid) : null; }
    static String getSession(HttpExchange t)  {
        String c = t.getRequestHeaders().getFirst("Cookie");
        if (c == null) return null;
        for (String s : c.split(";")) if (s.trim().startsWith("session=")) return s.trim().substring(8);
        return null;
    }
    static void redirect(HttpExchange t, String url) throws IOException { t.getResponseHeaders().set("Location", url); t.sendResponseHeaders(302, -1); }
    static void sendHtml(HttpExchange t, String html) throws IOException { byte[] b = html.getBytes("UTF-8"); t.getResponseHeaders().set("Content-Type","text/html"); t.sendResponseHeaders(200, b.length); t.getResponseBody().write(b); t.getResponseBody().close(); }
    static void sendPdf(HttpExchange t, byte[] d, String n) throws IOException { t.getResponseHeaders().set("Content-Type","application/pdf"); t.getResponseHeaders().set("Content-Disposition","attachment; filename="+n); t.sendResponseHeaders(200, d.length); t.getResponseBody().write(d); t.getResponseBody().close(); }
    static byte[] readAllBytes(InputStream is) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while((n=is.read(buf))!=-1) b.write(buf,0,n); return b.toByteArray(); }
    static Map<String,String> parseForm(String b) { Map<String,String> m = new HashMap<>(); for(String s : b.split("&")) { String[] kv=s.split("="); if(kv.length>1) m.put(kv[0], kv[1]); } return m; }
    static Map<String,String> parseQuery(String q) { Map<String,String> m = new HashMap<>(); if(q!=null) for(String s : q.split("&")) { String[] kv=s.split("="); if(kv.length>1) m.put(kv[0], kv[1]); } return m; }
    static void sendError(HttpExchange t, String m) throws IOException { sendHtml(t, "<h1>Erreur</h1><p>"+m+"</p><a href='/'>Retour</a>"); }
    static String loginPage(String err) { return "<html><head>"+CSS+"</head><body><div class='modal' style='margin:100px auto'><h1>Connexion</h1>"+(err!=null?"<p style='color:red'>"+err+"</p>":"")+"<form method='POST'><input name='email' placeholder='Email'><input type='password' name='password' placeholder='Pass'><button>Entrer</button></form></div></body></html>"; }
}
