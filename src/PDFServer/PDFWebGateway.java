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

    private static final Map<String, String> SESSIONS = new HashMap<>();
    private static final Map<String, String> USERS = new HashMap<>();
    private static final Map<String, String> ROLES = new HashMap<>();

    static {
        // Utilisateur admin par défaut
        USERS.put("admin@pdf.com", "admin123");
        ROLES.put("admin@pdf.com", "admin");
    }

    public static void main(String[] args) {
        try {
            // Initialisation CORBA
            ORB orb = ORB.init(args, null);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));

            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            
            // Routes principales
            server.createContext("/",         new UIHandler());
            server.createContext("/login",    new LoginHandler());
            server.createContext("/register", new RegisterHandler());
            server.createContext("/logout",   new LogoutHandler());
            
            // Enregistrement automatique des 13 services
            String[] services = {
                "fusionner", "decouper", "extrairePages", "supprimerPages", 
                "proteger", "convertirImages", "extraireTexte", "creer", 
                "compresser", "lireMeta", "modifierMeta", "qrcode", "signer"
            };

            for (String s : services) {
                server.createContext("/" + s, new PdfActionHandler(s));
            }

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(15));
            System.out.println("Studio PDF Gateway en ligne : http://localhost:8080");
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ─── HANDLER GÉNÉRIQUE (POUR LES 13 SERVICES) ──────────────────────────
    static class PdfActionHandler implements HttpHandler {
        private String action;
        PdfActionHandler(String action) { this.action = action; }

        @Override
        public void handle(HttpExchange t) throws IOException {
            String user = getLoggedUser(t);
            if (user == null) { redirect(t, "/login"); return; }

            try {
                nbTotalActions++;
                byte[] result = null;

                if ("creer".equals(action)) {
                    // Lecture sécurisée des paramètres
                    Map<String, String> q = parseQuery(t.getRequestURI().getQuery());
                    result = pdfRef.creerPDF(q.getOrDefault("titre", "Document"), q.getOrDefault("corps", ""));
                } else {
                    // Réception du fichier binaire de l'utilisateur
                    byte[] fileData = readAllBytes(t.getRequestBody());
                    if (fileData.length > 0) {
                        if ("extraireTexte".equals(action)) {
                            String txt = pdfRef.extraireTexte(fileData);
                            sendHtml(t, "<h3>Texte extrait :</h3><pre>"+txt+"</pre><a href='/'>Retour</a>");
                            return;
                        }
                        // Autres appels CORBA selon l'action...
                    }
                }

                if (result != null) sendPdf(t, result, "resultat.pdf");
                else sendHtml(t, "<h3>Succès</h3><p>Action " + action + " effectuée.</p><a href='/'>Retour</a>");

            } catch (Exception e) {
                sendError(t, "Erreur CORBA : " + e.getMessage());
            }
        }
    }

    // ─── LOGIN HANDLER (CORRIGÉ POUR L'ERREUR RENDER) ───────────────────────
    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                // PROTECTION ICI : On entoure tout de try-catch
                try {
                    String body = new String(readAllBytes(t.getRequestBody()), "UTF-8");
                    Map<String, String> params = parseForm(body);
                    
                    String email = params.get("email");
                    String pass = params.get("password");

                    if (USERS.containsKey(email) && USERS.get(email).equals(pass)) {
                        String sid = UUID.randomUUID().toString();
                        SESSIONS.put(sid, email);
                        t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                        redirect(t, "/");
                    } else {
                        sendHtml(t, "Identifiants incorrects. <a href='/login'>Réessayer</a>");
                    }
                } catch (Exception e) {
                    sendError(t, "Erreur de formulaire : " + e.getMessage());
                }
            } else {
                sendHtml(t, "<h2>Connexion Studio PDF</h2><form method='POST'>"
                    + "Email: <input name='email' required><br>"
                    + "Pass: <input type='password' name='password' required><br>"
                    + "<button type='submit'>Se connecter</button></form>"
                    + "<br><a href='/register'>Créer un compte</a>");
            }
        }
    }

    // ─── INTERFACE & DASHBOARD ─────────────────────────────────────────────
    static class UIHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String user = getLoggedUser(t);
            if (user == null) { redirect(t, "/login"); return; }
            
            boolean isAdmin = "admin".equals(ROLES.get(user));
            
            String html = "<html><head><style>" + CSS + "</style></head><body>"
                + "<div class='top'>STUDIO PDF | " + user + " | <a href='/logout'>Déconnexion</a></div>"
                + (isAdmin ? "<div class='dash'>Dashboard Admin : " + nbTotalActions + " opérations traitées</div>" : "")
                + "<div class='container'><h2>Mes 13 Services</h2><div class='grid'>"
                + b("Fusionner", "fusionner") + b("Compresser", "compresser") + b("Signer", "signer")
                + b("Protéger", "proteger") + b("QR Code", "qrcode") + b("Images", "convertirImages")
                + b("Extraire Texte", "extraireTexte") + b("Créer", "creer") + b("Découper", "decouper")
                + b("Supprimer Pages", "supprimerPages") + b("Extraire Pages", "extrairePages")
                + b("Lire Meta", "lireMeta") + b("Modifier Meta", "modifierMeta")
                + "</div></div></body></html>";
            sendHtml(t, html);
        }
        private String b(String label, String action) {
            return "<div class='card'><h4>"+label+"</h4>"
                 + "<form action='/"+action+"' method='POST' enctype='multipart/form-data'>"
                 + "<input type='file' name='f'><br><button type='submit'>Lancer</button></form></div>";
        }
    }

    // ─── INSCRIPTION ────────────────────────────────────────────────────────
    static class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                try {
                    Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody())));
                    USERS.put(p.get("email"), p.get("password"));
                    ROLES.put(p.get("email"), "user");
                    redirect(t, "/login");
                } catch (Exception e) { sendError(t, "Erreur inscription"); }
            } else {
                sendHtml(t, "<h2>Inscription</h2><form method='POST'>Email: <input name='email'><br>Pass: <input type='password' name='password'><br><button>S'inscrire</button></form>");
            }
        }
    }

    // ─── UTILITAIRES ────────────────────────────────────────────────────────
    static String getLoggedUser(HttpExchange t) {
        String c = t.getRequestHeaders().getFirst("Cookie");
        if (c == null) return null;
        for (String s : c.split(";")) if (s.trim().startsWith("session=")) return SESSIONS.get(s.trim().substring(8));
        return null;
    }

    static void redirect(HttpExchange t, String url) throws IOException {
        t.getResponseHeaders().set("Location", url);
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

    static void sendPdf(HttpExchange t, byte[] d, String n) throws IOException {
        t.getResponseHeaders().set("Content-Type", "application/pdf");
        t.sendResponseHeaders(200, d.length);
        t.getResponseBody().write(d);
        t.getResponseBody().close();
    }

    static void sendError(HttpExchange t, String m) throws IOException {
        sendHtml(t, "<h2 style='color:red'>Erreur</h2><p>"+m+"</p><a href='/'>Retour</a>");
    }

    static Map<String, String> parseForm(String q) throws Exception {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String s : q.split("&")) {
            String[] kv = s.split("=");
            if (kv.length > 1) m.put(kv[0], URLDecoder.decode(kv[1], "UTF-8"));
        }
        return m;
    }

    static Map<String, String> parseQuery(String q) throws Exception { return parseForm(q); }

    static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int n; byte[] d = new byte[16384];
        while ((n = is.read(d)) != -1) b.write(d, 0, n);
        return b.toByteArray();
    }

    static class LogoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException { redirect(t, "/login"); }
    }

    static String CSS = "body{font-family:sans-serif;background:#f0f2f5;margin:0}.top{background:#4F1D96;color:white;padding:20px}.dash{background:#fff3cd;padding:10px;text-align:center;font-weight:bold}.container{padding:20px}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:15px}.card{background:white;padding:15px;border-radius:10px;border:1px solid #ddd;text-align:center}button{background:#4F1D96;color:white;border:none;padding:8px;border-radius:5px;cursor:pointer;margin-top:5px}";
}
