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
    private static int nbTotalActions = 0; // Pour le dashboard admin
    private static final Map<String, String> SESSIONS = new HashMap<>();
    private static final Map<String, String> USERS = new HashMap<>();
    private static final Map<String, String> ROLES = new HashMap<>();

    static {
        // ADMIN PAR DÉFAUT
        USERS.put("admin@pdf.com", "admin123");
        ROLES.put("admin@pdf.com", "admin");
    }

    public static void main(String[] args) {
        try {
            try {
                ORB orb = ORB.init(args, null);
                org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
                NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
                pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));
            } catch (Exception e) { System.out.println("Attente CORBA..."); }

            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            
            server.createContext("/", t -> handleUI(t));
            server.createContext("/login", t -> handleLogin(t));
            server.createContext("/register", t -> handleRegister(t));
            server.createContext("/logout", t -> handleLogout(t));
            server.createContext("/service", t -> handleServices(t));

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(15));
            server.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    // --- INTERFACE DIFFÉRENCIÉE ---
    private static void handleUI(HttpExchange t) throws IOException {
        String user = getLoggedUser(t);
        if (user == null) { redirect(t, "/login"); return; }
        
        boolean isAdmin = "admin".equals(ROLES.get(user));
        StringBuilder sb = new StringBuilder("<html><body style='font-family:sans-serif; padding:20px;'>");
        
        if (isAdmin) {
            sb.append("<div style='background:#fee2e2; padding:15px; border-radius:8px; border:2px solid #ef4444;'>");
            sb.append("<h2 style='color:#b91c1c;'>ESPACE ADMINISTRATEUR</h2>");
            sb.append("<p><b>Statistiques globales :</b> ").append(nbTotalActions).append(" opérations traitées au total.</p>");
            sb.append("</div>");
        } else {
            sb.append("<h2 style='color:#1e40af;'>ESPACE UTILISATEUR</h2>");
        }

        sb.append("<p>Connecté en tant que : <b>").append(user).append("</b> | <a href='/logout'>Déconnexion</a></p><hr>");
        
        // Les 13 services (Menu identique, mais accès Admin tracé)
        String[] actions = {"fusion", "proteger", "signer", "qrcode", "compresser", "extraireTexte", "creer"};
        sb.append("<h3>Vos Services PDF</h3>");
        for(String a : actions) {
            sb.append("<div style='margin-bottom:15px; padding:10px; border:1px solid #ddd;'>");
            sb.append("<b>").append(a.toUpperCase()).append("</b>");
            sb.append("<form action='/service?action=").append(a).append("' method='POST' enctype='multipart/form-data'>");
            sb.append("<input type='file' name='f'> <button type='submit'>Lancer</button></form></div>");
        }
        sb.append("</body></html>");
        sendHtml(t, sb.toString());
    }

    private static void handleServices(HttpExchange t) throws IOException {
        String user = getLoggedUser(t);
        if (user == null) { redirect(t, "/login"); return; }
        try {
            nbTotalActions++; // Incrémentation pour l'admin
            String action = parseQuery(t.getRequestURI().getQuery()).getOrDefault("action", "");
            byte[] fileData = readAllBytes(t.getRequestBody());
            byte[] res = null;

            if (pdfRef != null) {
                switch (action) {
                    case "creer": res = pdfRef.creerPDF("Note", "Contenu"); break;
                    case "fusion": res = pdfRef.fusionnerPDFs(new byte[][]{fileData}); break;
                    case "extraireTexte": 
                        sendHtml(t, "<h3>Texte :</h3><pre>" + pdfRef.extraireTexte(fileData) + "</pre><a href='/'>Retour</a>");
                        return;
                    case "proteger": res = pdfRef.ajouterMotDePasse(fileData, "1234"); break;
                    default: res = fileData;
                }
            }
            if (res != null) sendPdf(t, res);
            else sendHtml(t, "Erreur ou fichier vide.");
        } catch (Exception e) { sendHtml(t, "Erreur : " + e.getMessage()); }
    }

    // --- LOGIN / REGISTER / LOGOUT ---
    private static void handleLogin(HttpExchange t) throws IOException {
        if ("POST".equals(t.getRequestMethod())) {
            try {
                Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody()), "UTF-8"));
                String mail = p.get("email");
                if (USERS.containsKey(mail) && USERS.get(mail).equals(p.get("password"))) {
                    String sid = UUID.randomUUID().toString();
                    SESSIONS.put(sid, mail);
                    t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                    redirect(t, "/");
                } else { sendHtml(t, "Identifiants invalides. <a href='/login'>Retour</a>"); }
            } catch (Exception e) { sendHtml(t, "Erreur serveur."); }
        } else {
            sendHtml(t, "<h2>Connexion Studio PDF</h2><form method='POST'>Email: <input name='email' required><br>MDP: <input type='password' name='password' required><br><button>Connexion</button></form><p><a href='/register'>Créer un compte</a></p>");
        }
    }

    private static void handleRegister(HttpExchange t) throws IOException {
        if ("POST".equals(t.getRequestMethod())) {
            try {
                Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody()), "UTF-8"));
                USERS.put(p.get("email"), p.get("password"));
                ROLES.put(p.get("email"), "user"); // Par défaut, tout le monde est 'user'
                sendHtml(t, "Compte créé ! <a href='/login'>Se connecter</a>");
            } catch (Exception e) { sendHtml(t, "Erreur."); }
        } else {
            sendHtml(t, "<h2>Inscription</h2><form method='POST'>Email: <input name='email'><br>MDP: <input type='password' name='password'><br><button>S'inscrire</button></form>");
        }
    }

    private static void handleLogout(HttpExchange t) throws IOException {
        String sid = getSessionId(t);
        if (sid != null) SESSIONS.remove(sid);
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
    static void sendPdf(HttpExchange t, byte[] d) throws IOException {
        t.getResponseHeaders().set("Content-Type", "application/pdf");
        t.sendResponseHeaders(200, d.length);
        t.getResponseBody().write(d);
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
    static Map<String, String> parseQuery(String q) throws Exception { return parseForm(q); }
    static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] d = new byte[8192]; int n;
        while ((n = is.read(d)) != -1) b.write(d, 0, n);
        return b.toByteArray();
    }
}
