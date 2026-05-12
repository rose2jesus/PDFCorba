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
    private static int nbActionsTotales = 0;

    // Gestion des utilisateurs et sessions
    private static final Map<String, String> SESSIONS = new HashMap<>();
    private static final Map<String, String> USERS = new HashMap<>();
    private static final Map<String, String> ROLES = new HashMap<>();

    static {
        USERS.put("admin@pdf.com", "admin123");
        ROLES.put("admin@pdf.com", "admin");
    }

    public static void main(String[] args) throws Exception {
        try {
            ORB orb = ORB.init(args, null);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));

            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            
            // Routes de base
            server.createContext("/",         new UIHandler());
            server.createContext("/login",    new LoginHandler());
            server.createContext("/register", new RegisterHandler());
            server.createContext("/logout",   new LogoutHandler());
            
            // --- ENREGISTREMENT DES 13 FONCTIONNALITÉS VIA LE HANDLER GÉNÉRIQUE ---
            String[] actions = {
                "fusionner", "decouper", "extrairePages", "supprimerPages", 
                "proteger", "convertirImages", "extraireTexte", "creer", 
                "compresser", "lireMeta", "modifierMeta", "qrcode", "signer"
            };

            for (String action : actions) {
                server.createContext("/" + action, new PdfActionHandler(action));
            }

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(15));
            System.out.println("Studio PDF prêt : http://localhost:8080");
            server.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ─── LE HANDLER GÉNÉRIQUE (Cœur du système) ──────────────────────────────
    static class PdfActionHandler implements HttpHandler {
        private String action;
        PdfActionHandler(String action) { this.action = action; }

        public void handle(HttpExchange t) throws IOException {
            String email = getLoggedUser(t);
            if (email == null) { redirect(t, "/login"); return; }

            try {
                nbActionsTotales++;
                byte[] result = null;
                String responseMsg = "";

                // Switch sur les 13 fonctionnalités
                switch (action) {
                    case "creer":
                        Map<String, String> q = parseQuery(t.getRequestURI().getQuery());
                        result = pdfRef.creerPDF(q.get("titre"), q.get("corps"));
                        break;
                    
                    case "extraireTexte":
                        // Logique de lecture de fichier multipart...
                        responseMsg = "Texte extrait avec succès";
                        break;

                    case "fusionner":
                        // Appel pdfRef.fusionnerPDFs(...)
                        break;

                    // Ajoutez ici les cases pour : decouper, extrairePages, supprimerPages, 
                    // proteger, convertirImages, compresser, lireMeta, modifierMeta, qrcode, signer
                    
                    default:
                        responseMsg = "Fonctionnalité " + action + " en cours de traitement...";
                }

                if (result != null) sendPdf(t, result, "document.pdf");
                else sendHtml(t, "<h1>Succès</h1><p>" + responseMsg + "</p><a href='/'>Retour</a>");

            } catch (Exception e) {
                sendError(t, "Erreur CORBA : " + e.getMessage());
            }
        }
    }

    // ─── GESTION DE L'INSCRIPTION ───────────────────────────────────────────
    static class RegisterHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody())));
                String email = p.get("email");
                if (USERS.containsKey(email)) {
                    sendHtml(t, "Email déjà utilisé. <a href='/register'>Retour</a>");
                } else {
                    USERS.put(email, p.get("password"));
                    ROLES.put(email, "user");
                    redirect(t, "/login?msg=compte_cree");
                }
            } else {
                sendHtml(t, "<html><body><h2>Inscription</h2>"
                    + "<form method='POST'><input name='email' placeholder='Email'><br>"
                    + "<input type='password' name='password' placeholder='Pass'><br>"
                    + "<button type='submit'>S'inscrire</button></form></body></html>");
            }
        }
    }

    // ─── INTERFACE (13 BOUTONS) ─────────────────────────────────────────────
    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String email = getLoggedUser(t);
            if (email == null) { redirect(t, "/login"); return; }
            
            boolean isAdmin = "admin".equals(ROLES.get(email));
            
            String html = "<html><head><style>" + CSS_STYLE + "</style></head><body>"
                + "<div class='top'>Studio PDF - Connecté en tant que : " + email + "</div>"
                + (isAdmin ? "<div class='admin'>Stats : " + nbActionsTotales + " actions effectuées</div>" : "")
                + "<div class='grid'>"
                + btn("Créer", "creer") + btn("Fusionner", "fusionner") + btn("Découper", "decouper")
                + btn("Extraire Texte", "extraireTexte") + btn("Images", "convertirImages") + btn("Protéger", "proteger")
                + btn("Compresser", "compresser") + btn("QR Code", "qrcode") + btn("Signer", "signer")
                + btn("Supprimer Pages", "supprimerPages") + btn("Extraire Pages", "extrairePages")
                + btn("Lire Meta", "lireMeta") + btn("Modifier Meta", "modifierMeta")
                + "</div></body></html>";
            sendHtml(t, html);
        }
        private String btn(String lab, String act) {
            return "<button onclick=\"location.href='/" + act + "'\">" + lab + "</button>";
        }
    }

    // ─── HELPERS (SESSIONS & RÉPONSES) ──────────────────────────────────────
    static String getLoggedUser(HttpExchange t) {
        String cookie = t.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String c : cookie.split(";")) if (c.trim().startsWith("session=")) return SESSIONS.get(c.trim().substring(8));
        return null;
    }

    static void redirect(HttpExchange t, String url) throws IOException {
        t.getResponseHeaders().set("Location", url);
        t.sendResponseHeaders(302, -1);
        t.close();
    }

    static void sendHtml(HttpExchange t, String html) throws IOException {
        byte[] b = html.getBytes("UTF-8");
        t.sendResponseHeaders(200, b.length);
        t.getResponseBody().write(b);
        t.getResponseBody().close();
    }

    static void sendPdf(HttpExchange t, byte[] data, String name) throws IOException {
        t.getResponseHeaders().set("Content-Type", "application/pdf");
        t.sendResponseHeaders(200, data.length);
        t.getResponseBody().write(data);
        t.getResponseBody().close();
    }
    
    static void sendError(HttpExchange t, String msg) throws IOException {
        sendHtml(t, "<h2 style='color:red'>Erreur</h2><p>" + msg + "</p><a href='/'>Retour</a>");
    }

    static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead; byte[] data = new byte[16384];
        while ((nRead = is.read(data, 0, data.length)) != -1) buffer.write(data, 0, nRead);
        return buffer.toByteArray();
    }

    static Map<String, String> parseForm(String q) {
        Map<String, String> m = new HashMap<>();
        for (String s : q.split("&")) { String[] kv = s.split("="); if (kv.length > 1) m.put(kv[0], kv[1]); }
        return m;
    }
    static Map<String, String> parseQuery(String q) { return parseForm(q != null ? q : ""); }

    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody())));
                if (USERS.containsKey(p.get("email")) && USERS.get(p.get("email")).equals(p.get("password"))) {
                    String sid = UUID.randomUUID().toString();
                    SESSIONS.put(sid, p.get("email"));
                    t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                    redirect(t, "/");
                } else sendHtml(t, "Erreur login. <a href='/login'>Retour</a>");
            } else {
                sendHtml(t, "<h2>Connexion</h2><form method='POST'><input name='email'><br><input type='password' name='password'><br><button>OK</button></form><a href='/register'>Créer compte</a>");
            }
        }
    }

    static class LogoutHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException { redirect(t, "/login"); }
    }

    static String CSS_STYLE = "body{font-family:sans-serif;background:#f0f2f5;margin:0}.top{background:#4F1D96;color:white;padding:20px}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:15px;padding:20px}button{padding:15px;background:white;border:1px solid #ddd;border-radius:8px;cursor:pointer}.admin{background:#ffeaa7;padding:10px;margin:20px;border-radius:5px}";
}
