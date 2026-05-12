package PDFServer;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URLDecoder;
import java.util.*;
import java.text.SimpleDateFormat;
import PDFApp.*;
import org.omg.CosNaming.*;
import org.omg.CORBA.*;
import java.net.InetSocketAddress;

public class PDFWebGateway {
    private static PDFService pdfRef;
    private static final Map<String, String> SESSIONS = new HashMap<>(); 
    private static final Map<String, String> USERS = new HashMap<>();    
    private static final Map<String, String> FULL_NAMES = new HashMap<>(); 
    private static final Map<String, String> ROLES = new HashMap<>();
    
    // Liste pour stocker l'historique (Username | Action | Date)
    private static final List<String[]> ACTIVITY_LOG = new ArrayList<>();

    static {
        USERS.put("admin", "admin123");
        FULL_NAMES.put("admin", "Administrateur Système");
        ROLES.put("admin", "admin");
    }

    public static void main(String[] args) throws Exception {
        try {
            Properties props = new Properties();
            props.put("org.omg.CORBA.ORBInitialPort", "1050");
            props.put("org.omg.CORBA.ORBInitialHost", "127.0.0.1");
            ORB orb = ORB.init(args, props);

            int tentatives = 0;
            while (pdfRef == null && tentatives < 25) {
                try {
                    org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
                    NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
                    pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));
                } catch (Exception e) { tentatives++; Thread.sleep(5000); }
            }

            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

            server.createContext("/", new UIHandler());
            server.createContext("/login", new LoginHandler());
            server.createContext("/register", new RegisterHandler());
            server.createContext("/action", new ActionHandler()); // Nouvelle route pour simuler l'action
            server.createContext("/logout", t -> {
                SESSIONS.remove(getSession(t));
                t.getResponseHeaders().set("Set-Cookie", "session=; Path=/; Max-Age=0");
                redirect(t, "/login");
            });

            server.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String user = SESSIONS.get(getSession(t));
            if (user == null) { redirect(t, "/login"); return; }
            
            String role = ROLES.getOrDefault(user, "user");
            boolean isAdmin = "admin".equals(role);

            StringBuilder html = new StringBuilder();
            html.append("<html><head><meta charset='UTF-8'><style>" + CSS_APP + "</style></head><body>");
            
            // NAVIGATION
            html.append("<div class='nav'><b>STUDIO PDF CORBA</b>");
            html.append("<div class='nav-right'><span>" + FULL_NAMES.get(user) + "</span>");
            html.append("<a href='/logout' class='btn-logout'>Déconnexion</a></div></div>");

            html.append("<div class='container'>");

            if (isAdmin) {
                html.append("<div class='admin-stats'><h3><span class='pulse'></span> Surveillance</h3>");
                html.append("<p>Utilisateurs en ligne : <b>" + SESSIONS.size() + "</b></p></div>");
            }

            html.append("<h2>Services PDF</h2><div class='grid'>");
            String[][] tools = {
                {"Création","purple"},{"Extraction","blue"},{"Conversion","orange"},{"Protection","green"},
                {"Fusion","pink"},{"Découpage","d-orange"},{"Suppression","red"},{"Extrait Pages","lavender"},
                {"Compression","cyan"},{"Métadonnées","d-blue"},{"QR Code","b-blue"},{"Signature","l-green"}
            };
            for(String[] s : tools) {
                html.append("<div class='card "+s[1]+"'><h3>"+s[0]+"</h3><a href='/action?type="+s[0]+"' class='btn-start'>Démarrer</a></div>");
            }
            html.append("</div>");

            // --- SECTION RÉCAPITULATIF (HISTORIQUE) ---
            html.append("<div class='recap-section'><h2>Récapitulatif de vos activités</h2>");
            html.append("<table><thead><tr><th>Action</th><th>Utilisateur</th><th>Date</th><th>Statut</th></tr></thead><tbody>");
            
            boolean hasActivity = false;
            for (int i = ACTIVITY_LOG.size() - 1; i >= 0; i--) {
                String[] entry = ACTIVITY_LOG.get(i);
                // Si admin, il voit tout. Si user, il voit seulement ses actions.
                if (isAdmin || entry[0].equals(user)) {
                    html.append("<tr><td>" + entry[1] + "</td><td>" + entry[0] + "</td><td>" + entry[2] + "</td><td><span class='status-ok'>Succès</span></td></tr>");
                    hasActivity = true;
                }
            }
            if (!hasActivity) html.append("<tr><td colspan='4'>Aucune activité récente.</td></tr>");
            
            html.append("</tbody></table></div></div></body></html>");
            sendHtml(t, html.toString());
        }
    }

    // Handler pour enregistrer une action dans le récapitulatif
    static class ActionHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String user = SESSIONS.get(getSession(t));
            if (user != null) {
                String type = t.getRequestURI().getQuery().split("=")[1];
                String date = new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date());
                ACTIVITY_LOG.add(new String[]{user, type, date});
            }
            redirect(t, "/");
        }
    }

    static String CSS_APP = "body{font-family:sans-serif;background:#F3F4F6;margin:0;}.nav{background:#1e1b4b;color:white;padding:15px 40px;display:flex;justify-content:space-between;align-items:center;}.btn-logout{background:#ef4444;color:white;padding:8px 15px;border-radius:5px;text-decoration:none;font-size:12px;}.container{padding:40px;max-width:1200px;margin:auto;}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:20px;margin-bottom:50px;}.card{background:white;padding:20px;border-radius:10px;text-align:center;border-top:5px solid #ddd;}.btn-start{display:block;background:#4338ca;color:white;padding:10px;text-decoration:none;border-radius:5px;margin-top:10px;font-size:14px;}" +
    ".recap-section{background:white;padding:30px;border-radius:10px;box-shadow:0 2px 10px rgba(0,0,0,0.05);} table{width:100%;border-collapse:collapse;margin-top:20px;} th{text-align:left;padding:12px;border-bottom:2px solid #eee;} td{padding:12px;border-bottom:1px solid #eee;font-size:14px;} .status-ok{background:#dcfce7;color:#166534;padding:4px 8px;border-radius:4px;font-size:11px;font-weight:bold;}" +
    ".purple{border-top-color:#a855f7}.blue{border-top-color:#3b82f6}.orange{border-top-color:#f59e0b}.green{border-top-color:#10b981}.pink{border-top-color:#ec4899}.d-orange{border-top-color:#f97316}.red{border-top-color:#ef4444}.lavender{border-top-color:#818cf8}.cyan{border-top-color:#06b6d4}.d-blue{border-top-color:#1e3a8a}.b-blue{border-top-color:#2563eb}.l-green{border-top-color:#34d399}";

    // (Utiliser les mêmes méthodes LoginHandler, RegisterHandler, redirect, sendHtml, etc. que précédemment)
    static class LoginHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { if ("POST".equals(t.getRequestMethod())) { Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody()), "UTF-8")); String u = p.get("username"); if (USERS.containsKey(u) && USERS.get(u).equals(p.get("password"))) { String sid = UUID.randomUUID().toString(); SESSIONS.put(sid, u); t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly"); redirect(t, "/"); } else { sendHtml(t, "Erreur"); } } else { sendHtml(t, "Login Page"); } } }
    static void redirect(HttpExchange t, String u) throws IOException { t.getResponseHeaders().set("Location", u); t.sendResponseHeaders(302, -1); }
    static void sendHtml(HttpExchange t, String h) throws IOException { byte[] b=h.getBytes("UTF-8"); t.getResponseHeaders().set("Content-Type","text/html; charset=UTF-8"); t.sendResponseHeaders(200, b.length); t.getResponseBody().write(b); t.getResponseBody().close(); }
    static String getSession(HttpExchange t) { String c=t.getRequestHeaders().getFirst("Cookie"); if(c==null)return null; for(String s:c.split(";")) if(s.trim().startsWith("session=")) return s.trim().substring(8); return null; }
    static Map<String,String> parseForm(String b) throws UnsupportedEncodingException { Map<String,String> m=new HashMap<>(); for(String s:b.split("&")){ String[] kv=s.split("="); if(kv.length>1) m.put(URLDecoder.decode(kv[0],"UTF-8"), URLDecoder.decode(kv[1],"UTF-8")); } return m; }
    static byte[] readAllBytes(InputStream i) throws IOException { ByteArrayOutputStream o=new ByteArrayOutputStream(); byte[] f=new byte[8192]; int n; while((n=i.read(f))!=-1) o.write(f,0,n); return o.toByteArray(); }
    static class RegisterHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody()), "UTF-8")); String u = p.get("username"); if (u != null) { USERS.put(u, p.get("password")); FULL_NAMES.put(u, p.get("prenom") + " " + p.get("nom")); ROLES.put(u, "user"); redirect(t, "/login"); } } }
}
