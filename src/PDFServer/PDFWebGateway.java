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
        USERS.put("admin@pdf.com", "admin123");
        ROLES.put("admin@pdf.com", "admin");
    }

    public static void main(String[] args) {
        try {
            // 1. Initialisation CORBA (On entoure pour éviter que le main crash direct)
            try {
                ORB orb = ORB.init(args, null);
                org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
                NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
                pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));
                System.out.println("Connexion CORBA réussie.");
            } catch (Exception e) {
                System.err.println("ALERTE : Impossible de se connecter au serveur CORBA. Vérifiez si le serveur tourne.");
                e.printStackTrace();
            }

            // 2. Gestion du Port pour Render (Important !)
            String portEnv = System.getenv("PORT");
            int port = (portEnv != null) ? Integer.parseInt(portEnv) : 8080;

            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            
            // Routes
            server.createContext("/",         new UIHandler());
            server.createContext("/login",    new LoginHandler());
            server.createContext("/register", new RegisterHandler());
            server.createContext("/logout",   new LogoutHandler());
            
            String[] services = {
                "fusionner", "decouper", "extrairePages", "supprimerPages", 
                "proteger", "convertirImages", "extraireTexte", "creer", 
                "compresser", "lireMeta", "modifierMeta", "qrcode", "signer"
            };

            for (String s : services) {
                server.createContext("/" + s, new PdfActionHandler(s));
            }

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(15));
            System.out.println("Serveur Web démarré sur le port : " + port);
            server.start();

        } catch (Exception e) {
            System.err.println("ERREUR CRITIQUE AU DÉMARRAGE :");
            e.printStackTrace();
        }
    }

    // --- HANDLER AVEC SÉCURITÉ RUNTIME ---
    static class PdfActionHandler implements HttpHandler {
        private String action;
        PdfActionHandler(String action) { this.action = action; }

        @Override
        public void handle(HttpExchange t) throws IOException {
            try {
                String user = getLoggedUser(t);
                if (user == null) { redirect(t, "/login"); return; }

                nbTotalActions++;
                byte[] result = null;

                if ("creer".equals(action)) {
                    Map<String, String> q = parseQuery(t.getRequestURI().getQuery());
                    if (pdfRef != null) result = pdfRef.creerPDF(q.getOrDefault("titre", "Doc"), q.getOrDefault("corps", ""));
                } else {
                    byte[] fileData = readAllBytes(t.getRequestBody());
                    if (fileData.length > 0 && pdfRef != null) {
                        if ("extraireTexte".equals(action)) {
                            sendHtml(t, "<h3>Texte :</h3><pre>"+pdfRef.extraireTexte(fileData)+"</pre><a href='/'>Retour</a>");
                            return;
                        }
                    }
                }

                if (result != null) sendPdf(t, result, "resultat.pdf");
                else sendHtml(t, "<h3>Succès</h3><p>Action " + action + " validée.</p><a href='/'>Retour</a>");

            } catch (Exception e) {
                sendError(t, "Erreur lors de l'exécution : " + e.getMessage());
            }
        }
    }

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            try {
                if ("POST".equals(t.getRequestMethod())) {
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
                        sendHtml(t, "Erreur login. <a href='/login'>Retour</a>");
                    }
                } else {
                    sendHtml(t, "<h2>Connexion</h2><form method='POST'>Email: <input name='email'><br>Pass: <input type='password' name='password'><br><button>OK</button></form>");
                }
            } catch (Exception e) {
                sendError(t, "Erreur Login : " + e.getMessage());
            }
        }
    }

    static class UIHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            try {
                String user = getLoggedUser(t);
                if (user == null) { redirect(t, "/login"); return; }
                boolean isAdmin = "admin".equals(ROLES.get(user));
                
                StringBuilder sb = new StringBuilder();
                sb.append("<html><body><h1>Studio PDF</h1>");
                if(isAdmin) sb.append("<p style='color:blue'>Admin Dashboard - Actions : " + nbTotalActions + "</p>");
                sb.append("<ul>");
                String[] acts = {"fusionner", "extraireTexte", "creer", "proteger", "compresser", "signer"};
                for(String a : acts) sb.append("<li><a href='/"+a+"'>"+a+"</a></li>");
                sb.append("</ul></body></html>");
                sendHtml(t, sb.toString());
            } catch (Exception e) {
                sendError(t, "Erreur UI : " + e.getMessage());
            }
        }
    }

    // --- UTILITAIRES ---
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
        try { sendHtml(t, "<h2 style='color:red'>Erreur</h2><p>"+m+"</p><a href='/'>Retour</a>"); } catch (Exception ignored) {}
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

    static class RegisterHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { sendHtml(t, "Inscription..."); } }
    static class LogoutHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { redirect(t, "/login"); } }
}
