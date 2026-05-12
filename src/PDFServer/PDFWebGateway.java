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

/**
 * STUDIO PDF CORBA - VERSION MASTER FINALE
 * Intègre : 12 Services, Historique utilisateur et Monitoring Admin.
 */
public class PDFWebGateway {
    private static PDFService pdfRef;
    
    // Gestion des données en mémoire
    private static final Map<String, String> SESSIONS = new HashMap<>(); 
    private static final Map<String, String> USERS = new HashMap<>();    
    private static final Map<String, String> FULL_NAMES = new HashMap<>(); 
    private static final Map<String, String> ROLES = new HashMap<>();
    private static final List<String[]> ACTIVITY_LOG = new ArrayList<>(); // [User, Action, Date]

    static {
        // Compte Administrateur par défaut
        USERS.put("admin", "admin123");
        FULL_NAMES.put("admin", "Administrateur Système");
        ROLES.put("admin", "admin");
    }

    public static void main(String[] args) throws Exception {
        try {
            System.out.println("Démarrage du Studio PDF CORBA...");
            Properties props = new Properties();
            props.put("org.omg.CORBA.ORBInitialPort", "1050");
            props.put("org.omg.CORBA.ORBInitialHost", "127.0.0.1");
            ORB orb = ORB.init(args, props);

            // Tentative de liaison avec le serveur CORBA
            int tentatives = 0;
            while (pdfRef == null && tentatives < 20) {
                try {
                    org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
                    NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
                    pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));
                    System.out.println("Connexion CORBA : OK");
                } catch (Exception e) {
                    tentatives++;
                    System.out.println("Attente du serveur... (" + tentatives + "/20)");
                    Thread.sleep(3000);
                }
            }

            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

            // Routage
            server.createContext("/", new UIHandler());
            server.createContext("/login", new LoginHandler());
            server.createContext("/register", new RegisterHandler());
            server.createContext("/action", new ActionHandler());
            server.createContext("/logout", t -> {
                SESSIONS.remove(getSession(t));
                t.getResponseHeaders().set("Set-Cookie", "session=; Path=/; Max-Age=0");
                redirect(t, "/login");
            });

            server.start();
            System.out.println("Serveur Web accessible sur le port " + port);
        } catch (Exception e) { e.printStackTrace(); }
    }

    // --- INTERFACE PRINCIPALE ---
    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String user = SESSIONS.get(getSession(t));
            if (user == null) { redirect(t, "/login"); return; }
            
            String role = ROLES.getOrDefault(user, "user");
            boolean isAdmin = "admin".equals(role);

            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
            html.append("<title>Studio PDFCorba</title><style>" + CSS_APP + "</style></head><body>");
            
            // Barre de navigation
            html.append("<div class='nav'><b>STUDIO PDF CORBA</b>");
            html.append("<div class='nav-right'><span><i class='user-icon'>👤</i> " + FULL_NAMES.get(user) + "</span>");
            html.append("<a href='/logout' class='btn-logout'>Déconnexion</a></div></div>");

            html.append("<div class='container'>");

            // Bloc Admin : Surveillance des connexions
            if (isAdmin) {
                html.append("<div class='admin-card'><h3><span class='pulse'></span> Surveillance Système</h3>");
                html.append("<p>Utilisateurs connectés : <b>" + SESSIONS.size() + "</b></p></div>");
            }

            // Grille des 12 fonctionnalités
            html.append("<h2>Nos Services PDF</h2><div class='grid'>");
            String[][] tools = {
                {"Création","purple"},{"Extraction","blue"},{"Conversion","orange"},{"Protection","green"},
                {"Fusion","pink"},{"Découpage","d-orange"},{"Suppression","red"},{"Extrait Pages","lavender"},
                {"Compression","cyan"},{"Métadonnées","d-blue"},{"QR Code","b-blue"},{"Signature","l-green"}
            };
            for(String[] s : tools) {
                html.append("<div class='card "+s[1]+"'><h3>"+s[0]+"</h3>");
                html.append("<a href='/action?type="+s[0]+"' class='btn-action'>Démarrer</a></div>");
            }
            html.append("</div>");

            // Récapitulatif d'activité
            html.append("<div class='recap'><h2>Récapitulatif des activités</h2>");
            html.append("<table><thead><tr><th>Service</th><th>Utilisateur</th><th>Date</th><th>Statut</th></tr></thead><tbody>");
            boolean activityFound = false;
            for (int i = ACTIVITY_LOG.size() - 1; i >= 0; i--) {
                String[] log = ACTIVITY_LOG.get(i);
                if (isAdmin || log[0].equals(user)) {
                    html.append("<tr><td>"+log[1]+"</td><td>"+log[0]+"</td><td>"+log[2]+"</td><td><span class='badge'>Succès</span></td></tr>");
                    activityFound = true;
                }
            }
            if(!activityFound) html.append("<tr><td colspan='4' style='text-align:center'>Aucune activité enregistrée.</td></tr>");
            html.append("</tbody></table></div></div></body></html>");
            
            sendHtml(t, html.toString());
        }
    }

    // --- GESTION DES ACTIONS ---
    static class ActionHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String user = SESSIONS.get(getSession(t));
            if (user != null) {
                String type = t.getRequestURI().getQuery().split("=")[1];
                String now = new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date());
                ACTIVITY_LOG.add(new String[]{user, type, now});
            }
            redirect(t, "/");
        }
    }

    // --- AUTHENTIFICATION ---
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody()), "UTF-8"));
                String u = p.get("username"), pass = p.get("password");
                if (USERS.containsKey(u) && USERS.get(u).equals(pass)) {
                    String sid = UUID.randomUUID().toString();
                    SESSIONS.put(sid, u);
                    t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                    redirect(t, "/");
                } else { sendHtml(t, authPage("Erreur : Identifiants incorrects", true)); }
            } else { sendHtml(t, authPage(null, true)); }
        }
    }

    static class RegisterHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody()), "UTF-8"));
                String u = p.get("username");
                if (u != null && !USERS.containsKey(u)) {
                    USERS.put(u, p.get("password"));
                    FULL_NAMES.put(u, p.get("prenom") + " " + p.get("nom"));
                    ROLES.put(u, "user");
                    redirect(t, "/login");
                } else { sendHtml(t, authPage("Erreur : Identifiant déjà pris", false)); }
            } else { sendHtml(t, authPage(null, false)); }
        }
    }

    static String authPage(String err, boolean isLogin) {
        String title = isLogin ? "Connexion Studio PDF" : "Créer un compte";
        StringBuilder f = new StringBuilder();
        f.append("<html><head><style>"+CSS_AUTH+"</style></head><body><div class='box'><h2>"+title+"</h2>");
        if(err != null) f.append("<p style='color:#ef4444; font-size:13px'>"+err+"</p>");
        f.append("<form method='POST'>");
        if(!isLogin) {
            f.append("<input name='prenom' placeholder='Prénom' required>");
            f.append("<input name='nom' placeholder='Nom' required>");
        }
        f.append("<input name='username' placeholder='Identifiant' required>");
        f.append("<input name='password' type='password' placeholder='Mot de passe' required>");
        f.append("<button type='submit'>"+(isLogin?"Se connecter":"S'inscrire")+"</button></form>");
        f.append("<a href='"+(isLogin?"/register":"/login")+"' style='display:block;margin-top:15px;color:#4338ca;text-decoration:none;font-size:14px;'>"+(isLogin?"Nouveau ? Créer un compte":"Déjà inscrit ? Login")+"</a></div></body></html>");
        return f.toString();
    }

    // --- DESIGN CSS ---
    static String CSS_AUTH = "body{background:#1e1b4b; display:flex; justify-content:center; align-items:center; height:100vh; font-family:sans-serif; margin:0;} .box{background:white; padding:40px; border-radius:15px; width:320px; text-align:center; box-shadow:0 10px 25px rgba(0,0,0,0.3);} h2{color:#1e1b4b;} input{width:100%; padding:12px; margin:8px 0; border:1px solid #ddd; border-radius:8px; box-sizing:border-box;} button{width:100%; padding:12px; background:#4338ca; color:white; border:none; border-radius:8px; cursor:pointer; font-weight:bold; margin-top:10px;}";
    
    static String CSS_APP = "body{font-family:sans-serif; background:#f1f5f9; margin:0; color:#1e293b;} " +
        ".nav{background:#1e1b4b; color:white; padding:15px 40px; display:flex; justify-content:space-between; align-items:center;} " +
        ".btn-logout{background:#ef4444; color:white; padding:8px 15px; border-radius:5px; text-decoration:none; font-size:12px; font-weight:bold;} " +
        ".container{padding:30px; max-width:1100px; margin:auto;} " +
        ".admin-card{background:white; padding:15px; border-radius:10px; border-left:5px solid #6366f1; margin-bottom:25px; box-shadow:0 2px 5px rgba(0,0,0,0.05);} " +
        ".pulse{height:10px; width:10px; background:#10b981; border-radius:50%; display:inline-block; margin-right:8px; animation:blink 1s infinite;} @keyframes blink{0%,100%{opacity:1} 50%{opacity:0.3}} " +
        ".grid{display:grid; grid-template-columns:repeat(4,1fr); gap:15px; margin-bottom:40px;} " +
        ".card{background:white; padding:20px; border-radius:10px; text-align:center; border-top:5px solid #cbd5e1; transition:0.2s;} .card:hover{transform:translateY(-3px);} " +
        ".btn-action{display:block; background:#4338ca; color:white; padding:8px; text-decoration:none; border-radius:5px; margin-top:10px; font-size:13px;} " +
        ".recap{background:white; padding:25px; border-radius:10px; box-shadow:0 2px 10px rgba(0,0,0,0.05);} table{width:100%; border-collapse:collapse; margin-top:15px;} " +
        "th{text-align:left; padding:10px; border-bottom:2px solid #f1f5f9;} td{padding:10px; border-bottom:1px solid #f1f5f9; font-size:13px;} " +
        ".badge{background:#dcfce7; color:#166534; padding:3px 8px; border-radius:4px; font-weight:bold; font-size:10px;} " +
        ".purple{border-top-color:#a855f7}.blue{border-top-color:#3b82f6}.orange{border-top-color:#f59e0b}.green{border-top-color:#10b981}.pink{border-top-color:#ec4899}.d-orange{border-top-color:#f97316}.red{border-top-color:#ef4444}.lavender{border-top-color:#818cf8}.cyan{border-top-color:#06b6d4}.d-blue{border-top-color:#1e3a8a}.b-blue{border-top-color:#2563eb}.l-green{border-top-color:#34d399}";

    // --- UTILITAIRES ---
    static void redirect(HttpExchange t, String u) throws IOException { t.getResponseHeaders().set("Location", u); t.sendResponseHeaders(302, -1); }
    static void sendHtml(HttpExchange t, String h) throws IOException { byte[] b=h.getBytes("UTF-8"); t.getResponseHeaders().set("Content-Type","text/html; charset=UTF-8"); t.sendResponseHeaders(200, b.length); t.getResponseBody().write(b); t.getResponseBody().close(); }
    static String getSession(HttpExchange t) { String c=t.getRequestHeaders().getFirst("Cookie"); if(c==null)return null; for(String s:c.split(";")) if(s.trim().startsWith("session=")) return s.trim().substring(8); return null; }
    static Map<String,String> parseForm(String b) throws UnsupportedEncodingException { Map<String,String> m=new HashMap<>(); for(String s:b.split("&")){ String[] kv=s.split("="); if(kv.length>1) m.put(URLDecoder.decode(kv[0],"UTF-8"), URLDecoder.decode(kv[1],"UTF-8")); } return m; }
    static byte[] readAllBytes(InputStream i) throws IOException { ByteArrayOutputStream o=new ByteArrayOutputStream(); byte[] f=new byte[8192]; int n; while((n=i.read(f))!=-1) o.write(f,0,n); return o.toByteArray(); }
}
