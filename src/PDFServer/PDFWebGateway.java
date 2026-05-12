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
    private static int nbTotalActions = 0;
    
    // Cache temporaire pour stocker le dernier PDF traité par session
    private static final Map<String, byte[]> PDF_CACHE = new HashMap<>();
    private static final Map<String, String> SESSIONS = new HashMap<>();
    private static final Map<String, String> USERS = new HashMap<>();
    private static final Map<String, String> ROLES = new HashMap<>();

    static {
        USERS.put("admin@pdf.com", "admin123");
        ROLES.put("admin@pdf.com", "admin");
    }

    public static void main(String[] args) {
        try {
            new Thread(() -> {
                try {
                    ORB orb = ORB.init(args, null);
                    org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
                    NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
                    pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));
                    System.out.println("CORBA CONNECTE");
                } catch (Exception e) { System.out.println("Attente CORBA..."); }
            }).start();

            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            
            server.createContext("/", t -> handleUI(t));
            server.createContext("/login", t -> handleAuth(t, false));
            server.createContext("/register", t -> handleAuth(t, true));
            server.createContext("/logout", t -> handleLogout(t));
            server.createContext("/service", t -> handleServices(t));
            server.createContext("/download", t -> handleDownload(t));

            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            System.out.println("Gateway en ligne sur le port " + port);
        } catch (Exception e) { e.printStackTrace(); }
    }

    // --- INTERFACE PRINCIPALE ---
    private static void handleUI(HttpExchange t) throws IOException {
        String user = getLoggedUser(t);
        if (user == null) { redirect(t, "/login"); return; }
        
        boolean isAdmin = "admin".equals(ROLES.get(user));
        StringBuilder sb = new StringBuilder("<html><body style='font-family:sans-serif;padding:30px;background:#f4f4f9;'>");
        
        if(isAdmin) {
            sb.append("<div style='background:#fee2e2;padding:15px;border-radius:10px;border:2px solid red;margin-bottom:20px;'>");
            sb.append("<h2 style='margin:0;color:red;'>DASHBOARD ADMIN</h2><p>Actions systeme : <b>").append(nbTotalActions).append("</b></p></div>");
        }
        
        sb.append("<div style='background:white;padding:20px;border-radius:10px;box-shadow:0 2px 5px rgba(0,0,0,0.1);'>");
        sb.append("<h1>Studio PDF Master</h1><p>Connecte : <b>").append(user).append("</b> | <a href='/logout' style='color:red;'>Deconnexion</a></p><hr>");
        
        String[] acts = {"fusion", "proteger", "signer", "qrcode", "compresser", "extraireTexte", "creer", "image", "decouper", "supprimer", "extrairePages", "lireMeta", "modMeta"};
        sb.append("<h3>Services disponibles (13)</h3><div style='display:grid;grid-template-columns: 1fr 1fr;gap:10px;'>");
        for(String a : acts) {
            sb.append("<div style='padding:10px;border:1px solid #ddd;'>");
            sb.append("<form action='/service?action=").append(a).append("' method='POST' enctype='multipart/form-data'>");
            sb.append("<b style='display:block;margin-bottom:5px;'>").append(a.toUpperCase()).append("</b>");
            sb.append("<input type='file' name='f' style='width:100%;'><br><button type='submit' style='margin-top:5px;cursor:pointer;'>Lancer le traitement</button></form></div>");
        }
        sb.append("</div></div></body></html>");
        sendHtml(t, sb.toString());
    }

    // --- LOGIQUE DE TRAITEMENT CORBA ET CHOIX ---
    private static void handleServices(HttpExchange t) throws IOException {
        String sid = getSessionId(t);
        if (sid == null) { redirect(t, "/login"); return; }

        try {
            nbTotalActions++;
            Map<String, String> q = parseForm(t.getRequestURI().getQuery());
            String action = q.getOrDefault("action", "action");
            byte[] fileData = readAllBytes(t.getRequestBody());
            
            byte[] result = fileData; // Par défaut (si CORBA est lent)
            if (pdfRef != null && fileData.length > 0) {
                // Ici tu appelles tes vraies méthodes CORBA
                if(action.equals("fusion")) result = pdfRef.fusionnerPDFs(new byte[][]{fileData});
                else if(action.equals("proteger")) result = pdfRef.ajouterMotDePasse(fileData, "1234");
                // ... ajoute les autres appels CORBA ici
            }

            // ON MET LE RESULTAT EN CACHE POUR CETTE SESSION
            PDF_CACHE.put(sid, result);

            // PAGE DE CHOIX
            StringBuilder sb = new StringBuilder("<html><body style='font-family:sans-serif;text-align:center;padding-top:100px;background:#f4f4f9;'>");
            sb.append("<div style='background:white;display:inline-block;padding:40px;border-radius:15px;box-shadow:0 4px 10px rgba(0,0,0,0.1);'>");
            sb.append("<h2 style='color:green;'>Traitement " + action.toUpperCase() + " effectue !</h2>");
            sb.append("<p>Voulez-vous visualiser le fichier avant de le telecharger ?</p><br>");
            sb.append("<a href='/download?mode=view' target='_blank' style='padding:12px 25px;background:#3b82f6;color:white;text-decoration:none;border-radius:5px;margin-right:10px;'>Visualiser (Apercu)</a>");
            sb.append("<a href='/download?mode=save' style='padding:12px 25px;background:#10b981;color:white;text-decoration:none;border-radius:5px;'>Telecharger</a>");
            sb.append("<br><br><br><a href='/' style='color:#666;'>Retour a l'accueil</a>");
            sb.append("</div></body></html>");
            sendHtml(t, sb.toString());
        } catch (Exception e) { sendHtml(t, "Erreur : " + e.getMessage()); }
    }

    // --- TELECHARGEMENT OU APERCU ---
    private static void handleDownload(HttpExchange t) throws IOException {
        String sid = getSessionId(t);
        byte[] data = PDF_CACHE.get(sid);

        if (data == null) { sendHtml(t, "Aucun fichier en cache. <a href='/'>Retour</a>"); return; }

        Map<String, String> q = parseForm(t.getRequestURI().getQuery());
        String mode = q.getOrDefault("mode", "view");

        t.getResponseHeaders().set("Content-Type", "application/pdf");
        if ("save".equals(mode)) {
            t.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"resultat_studio_pdf.pdf\"");
        } else {
            t.getResponseHeaders().set("Content-Disposition", "inline");
        }

        t.sendResponseHeaders(200, data.length);
        try (OutputStream os = t.getResponseBody()) { os.write(data); }
    }

    // --- AUTHENTIFICATION ---
    private static void handleAuth(HttpExchange t, boolean isReg) throws IOException {
        if ("POST".equals(t.getRequestMethod())) {
            try {
                Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody()), "UTF-8"));
                String mail = p.get("email");
                if (isReg) {
                    USERS.put(mail, p.get("password"));
                    ROLES.put(mail, "user");
                    redirect(t, "/login");
                } else if (USERS.containsKey(mail) && USERS.get(mail).equals(p.get("password"))) {
                    String sid = UUID.randomUUID().toString();
                    SESSIONS.put(sid, mail);
                    t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                    redirect(t, "/");
                } else { sendHtml(t, "Echec. <a href='/login'>Retour</a>"); }
            } catch (Exception e) { sendHtml(t, "Erreur."); }
        } else {
            String title = isReg ? "Creer un compte" : "Connexion";
            sendHtml(t, "<html><body style='font-family:sans-serif;text-align:center;padding-top:100px;'><h2>"+title+"</h2><form method='POST'>Email: <input name='email' required><br><br>MDP: <input type='password' name='password' required><br><br><button type='submit'>Valider</button></form>"+(isReg?"":"<br><a href='/register'>S'inscrire</a>")+"</body></html>");
        }
    }

    private static void handleLogout(HttpExchange t) throws IOException {
        String sid = getSessionId(t);
        if (sid != null) { SESSIONS.remove(sid); PDF_CACHE.remove(sid); }
        t.getResponseHeaders().set("Set-Cookie", "session=; Max-Age=0; Path=/");
        redirect(t, "/login");
    }

    // --- UTILS ---
    static String getLoggedUser(HttpExchange t) {
        String sid = getSessionId(t);
        return (sid != null) ? SESSIONS.get(sid) : null;
    }
    static String getSessionId(HttpExchange t) {
        String c = t.getRequestHeaders().getFirst("Cookie");
        if (c != null) for (String s : c.split(";")) if (s.trim().startsWith("session=")) return s.trim().substring(8);
        return null;
    }
    static void redirect(HttpExchange t, String u) throws IOException {
        t.getResponseHeaders().set("Location", u);
        t.sendResponseHeaders(302, -1);
        t.close();
    }
    static void sendHtml(HttpExchange t, String h) throws IOException {
        byte[] b = h.getBytes("UTF-8");
        t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        t.sendResponseHeaders(200, b.length);
        t.getResponseBody().write(b);
        t.getResponseBody().close();
    }
    static Map<String, String> parseForm(String q) throws Exception {
        Map<String, String> m = new HashMap<>();
        if (q == null) return m;
        for (String s : q.split("&")) {
            String[] kv = s.split("=");
            if (kv.length > 1) m.put(kv[0], URLDecoder.decode(kv[1], "UTF-8"));
        }
        return m;
    }
    static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] d = new byte[8192]; int n;
        while ((n = is.read(d)) != -1) b.write(d, 0, n);
        return b.toByteArray();
    }
}
