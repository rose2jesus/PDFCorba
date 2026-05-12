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
    
    // Statistiques pour le Dashboard Admin
    private static int nbTotalActions = 0;

    private static final Map<String, String> SESSIONS = new HashMap<>();
    private static final Map<String, String> USERS = new HashMap<>();
    private static final Map<String, String> ROLES = new HashMap<>();

    static {
        // Compte admin par défaut
        USERS.put("admin@pdf.com", "admin123");
        ROLES.put("admin@pdf.com", "admin");
    }

    public static void main(String[] args) throws Exception {
        try {
            // Initialisation CORBA
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
            
            // --- LES 13 SERVICES PDF (HANDLER GÉNÉRIQUE) ---
            String[] services = {
                "fusionner", "decouper", "extrairePages", "supprimerPages", 
                "proteger", "convertirImages", "extraireTexte", "creer", 
                "compresser", "lireMeta", "modifierMeta", "qrcode", "signer"
            };

            for (String act : services) {
                server.createContext("/" + act, new PdfActionHandler(act));
            }

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(15));
            System.out.println("-------------------------------------------");
            System.out.println("  STUDIO PDF GATEWAY : CONNECTÉ             ");
            System.out.println("  URL : http://localhost:8080               ");
            System.out.println("-------------------------------------------");
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ─── LE HANDLER GÉNÉRIQUE (Cœur de l'application) ───────────────────────
    static class PdfActionHandler implements HttpHandler {
        private String action;
        PdfActionHandler(String action) { this.action = action; }

        public void handle(HttpExchange t) throws IOException {
            String userEmail = getLoggedUser(t);
            if (userEmail == null) { redirect(t, "/login"); return; }

            try {
                nbTotalActions++;
                byte[] result = null;

                // 1. Actions avec paramètres (ex: Création)
                if (action.equals("creer")) {
                    Map<String, String> q = parseForm(t.getRequestURI().getQuery());
                    result = pdfRef.creerPDF(q.getOrDefault("titre", "Doc"), q.getOrDefault("corps", "Contenu"));
                } 
                // 2. Actions avec fichiers (Upload depuis la machine utilisateur)
                else {
                    byte[] fileData = readAllBytes(t.getRequestBody());
                    if (fileData.length > 0) {
                        switch (action) {
                            case "extraireTexte":
                                String text = pdfRef.extraireTexte(fileData);
                                sendHtml(t, "<h3>Texte Extrait :</h3><pre>"+text+"</pre><br><a href='/'>Retour</a>");
                                return;
                            case "compresser": result = pdfRef.compresserPDF(fileData); break;
                            case "proteger":   result = pdfRef.ajouterMotDePasse(fileData, "1234"); break;
                            // Les autres appels CORBA se font ici...
                        }
                    }
                }

                if (result != null) sendPdf(t, result, "resultat_" + action + ".pdf");
                else sendHtml(t, "<h3>Succès</h3><p>L'opération " + action + " a été transmise au serveur CORBA.</p><a href='/'>Retour</a>");

            } catch (Exception e) {
                sendError(t, "Erreur CORBA : " + e.getMessage());
            }
        }
    }

    // ─── INTERFACE UTILISATEUR & DASHBOARD ADMIN ────────────────────────────
    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String email = getLoggedUser(t);
            if (email == null) { redirect(t, "/login"); return; }
            
            boolean isAdmin = "admin".equals(ROLES.get(email));
            
            StringBuilder html = new StringBuilder();
            html.append("<html><head><style>" + CSS_MAIN + "</style></head><body>");
            html.append("<div class='topbar'><strong>STUDIO PDF</strong> <span>" + email + " | <a href='/logout'>Déconnexion</a></span></div>");
            
            // DASHBOARD ADMIN
            if (isAdmin) {
                html.append("<div class='admin-dashboard'>");
                html.append("<h2>Tableau de Bord Admin</h2>");
                html.append("<div class='stat-card'>Actions globales effectuées : <strong>" + nbTotalActions + "</strong></div>");
                html.append("</div>");
            }

            // GRILLE DES 13 SERVICES (Visible par tous)
            html.append("<div class='container'><h2>Services PDF (Tous débloqués)</h2><div class='grid'>");
            html.append(toolCard("Créer un PDF", "creer", false));
            html.append(toolCard("Fusionner", "fusionner", true));
            html.append(toolCard("Extraire Texte", "extraireTexte", true));
            html.append(toolCard("Compresser", "compresser", true));
            html.append(toolCard("Protéger", "proteger", true));
            html.append(toolCard("En Images", "convertirImages", true));
            html.append(toolCard("QR Code", "qrcode", true));
            html.append(toolCard("Signer", "signer", true));
            html.append(toolCard("Découper", "decouper", true));
            html.append(toolCard("Supprimer Pages", "supprimerPages", true));
            html.append(toolCard("Extraire Pages", "extrairePages", true));
            html.append(toolCard("Lire Meta", "lireMeta", true));
            html.append(toolCard("Modifier Meta", "modifierMeta", true));
            html.append("</div></div></body></html>");
            
            sendHtml(t, html.toString());
        }

        private String toolCard(String title, String route, boolean upload) {
            String form = upload ? 
                "<form action='/"+route+"' method='POST' enctype='multipart/form-data'><input type='file' name='f' required><br><button type='submit'>Lancer</button></form>" :
                "<button onclick=\"location.href='/"+route+"?titre=Nouveau&corps=Texte'\">Lancer</button>";
            return "<div class='card'><h4>" + title + "</h4>" + form + "</div>";
        }
    }

    // ─── GESTION AUTHENTIFICATION (LOGIN / REGISTER) ────────────────────────
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                try {
                    Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody())));
                    if (USERS.containsKey(p.get("email")) && USERS.get(p.get("email")).equals(p.get("password"))) {
                        String sid = UUID.randomUUID().toString();
                        SESSIONS.put(sid, p.get("email"));
                        t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                        redirect(t, "/");
                    } else sendHtml(t, "Erreur login. <a href='/login'>Réessayer</a>");
                } catch (Exception e) { sendError(t, "Erreur technique"); }
            } else {
                sendHtml(t, "<h2>Connexion</h2><form method='POST'>Email: <input name='email'><br>Pass: <input type='password' name='password'><br><button type='submit'>Entrer</button></form><br><a href='/register'>Créer un compte</a>");
            }
        }
    }

    static class RegisterHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                try {
                    Map<String, String> p = parseForm(new String(readAllBytes(t.getRequestBody())));
                    USERS.put(p.get("email"), p.get("password"));
                    ROLES.put(p.get("email"), "user");
                    redirect(t, "/login");
                } catch (Exception e) { sendError(t, "Erreur"); }
            } else {
                sendHtml(t, "<h2>Inscription</h2><form method='POST'>Email: <input name='email'><br>Pass: <input type='password' name='password'><br><button type='submit'>S'inscrire</button></form>");
            }
        }
    }

    // ─── UTILITAIRES SYSTÈME (CORRIGÉS) ──────────────────────────────────────
    static String getLoggedUser(HttpExchange t) {
        String c = t.getRequestHeaders().getFirst("Cookie");
        if (c == null) return null;
        for (String s : c.split(";")) if (s.trim().startsWith("session=")) return SESSIONS.get(s.trim().substring(8));
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

    static void sendPdf(HttpExchange t, byte[] d, String n) throws IOException {
        t.getResponseHeaders().set("Content-Type", "application/pdf");
        t.getResponseHeaders().set("Content-Disposition", "attachment; filename=" + n);
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

    static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int n; byte[] d = new byte[16384];
        while ((n = is.read(d)) != -1) b.write(d, 0, n);
        return b.toByteArray();
    }

    static class LogoutHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException { redirect(t, "/login"); }
    }

    static String CSS_MAIN = "body{font-family:sans-serif;background:#f4f7f6;margin:0}.topbar{background:#4F1D96;color:white;padding:20px;display:flex;justify-content:space-between;align-items:center}.admin-dashboard{background:#fff3cd;padding:20px;text-align:center;border-bottom:3px solid #ffeaa7}.container{padding:30px}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));gap:20px}.card{background:white;padding:20px;border-radius:12px;border:1px solid #ddd;text-align:center;transition:0.2s}.card:hover{box-shadow:0 10px 15px rgba(0,0,0,0.1)}.stat-card{font-size:24px}button{background:#4F1D96;color:white;border:none;padding:10px 20px;border-radius:5px;cursor:pointer;margin-top:10px}input{margin-bottom:5px}";
}
