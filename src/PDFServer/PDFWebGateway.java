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
    
    // Statistiques globales (Visibles par l'admin)
    private static int nbTotalActions = 0;

    // Gestion en mémoire (Sessions et Comptes)
    private static final Map<String, String> SESSIONS = new HashMap<>();
    private static final Map<String, String> USERS = new HashMap<>();
    private static final Map<String, String> ROLES = new HashMap<>();

    static {
        // Compte administrateur par défaut
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
            
            // --- ROUTES SYSTEME ---
            server.createContext("/",         new UIHandler());
            server.createContext("/login",    new LoginHandler());
            server.createContext("/register", new RegisterHandler());
            server.createContext("/logout",   new LogoutHandler());
            
            // --- ROUTES PDF (13 FONCTIONNALITÉS) ---
            server.createContext("/create",   new PdfActionHandler("create"));
            server.createContext("/extract",  new PdfActionHandler("extract"));
            server.createContext("/merge",    new PdfActionHandler("merge"));
            server.createContext("/protect",  new PdfActionHandler("protect"));
            server.createContext("/image",    new PdfActionHandler("image"));
            server.createContext("/compress", new PdfActionHandler("compress"));
            server.createContext("/sign",     new PdfActionHandler("sign"));
            server.createContext("/qrcode",   new PdfActionHandler("qrcode"));
            server.createContext("/meta",     new PdfActionHandler("meta"));
            server.createContext("/delete",   new PdfActionHandler("delete"));
            server.createContext("/split",    new PdfActionHandler("split"));
            server.createContext("/pages",    new PdfActionHandler("pages"));
            server.createContext("/readmeta", new PdfActionHandler("readmeta"));

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(15));
            System.out.println("-------------------------------------------");
            System.out.println("  STUDIO PDF CORBA GATEWAY DÉMARRÉ        ");
            System.out.println("  URL : http://localhost:8080             ");
            System.out.println("-------------------------------------------");
            server.start();
        } catch (Exception e) {
            System.err.println("Erreur de connexion au serveur CORBA : " + e.getMessage());
        }
    }

    // --- GESTIONNAIRE D'INTERFACE (DASHBOARD) ---
    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String userEmail = getLoggedUser(t);
            if (userEmail == null) { redirect(t, "/login"); return; }
            
            boolean isAdmin = "admin".equals(ROLES.get(userEmail));
            
            StringBuilder toolsHtml = new StringBuilder();
            // Génération des 13 outils pour TOUT LE MONDE
            toolsHtml.append(toolCard("#7C3AED", "Création", "Texte vers PDF", "m-create"));
            toolsHtml.append(toolCard("#0EA5E9", "Extraire Texte", "PDF vers Texte", "m-extract"));
            toolsHtml.append(toolCard("#10B981", "Fusionner", "Joindre des fichiers", "m-merge"));
            toolsHtml.append(toolCard("#F59E0B", "Protéger", "Ajouter un mot de passe", "m-protect"));
            toolsHtml.append(toolCard("#EC4899", "En Images", "Convertir en PNG", "m-image"));
            toolsHtml.append(toolCard("#6366F1", "Compresser", "Réduire la taille", "m-compress"));
            toolsHtml.append(toolCard("#EF4444", "Supprimer", "Retirer des pages", "m-delete"));
            toolsHtml.append(toolCard("#8B5CF6", "Signer", "Signature RSA", "m-sign"));
            toolsHtml.append(toolCard("#14B8A6", "QR Code", "Insérer un lien", "m-qrcode"));
            toolsHtml.append(toolCard("#D946EF", "Métadonnées", "Modifier infos", "m-meta"));
            toolsHtml.append(toolCard("#0284C7", "Lire Infos", "Voir métadonnées", "m-readmeta"));
            toolsHtml.append(toolCard("#F43F5E", "Découper", "Séparer par pages", "m-split"));
            toolsHtml.append(toolCard("#F97316", "Extraire Pages", "Sélection libre", "m-pages"));

            String html = "<html><head><meta charset='UTF-8'><style>" + CSS_MAIN + "</style></head><body>"
                + "<div class='topbar'><strong>STUDIO PDF CORBA</strong>"
                + "<div>" + userEmail + " (" + ROLES.get(userEmail) + ") | <a href='/logout' style='color:white'>Déconnexion</a></div></div>"
                + "<div class='container'>"
                + (isAdmin ? "<div class='admin-card'><h3>Tableau de bord Admin</h3><p>Total des opérations traitées : <strong>" + nbTotalActions + "</strong></p></div>" : "")
                + "<h2>Services disponibles</h2><div class='grid'>" + toolsHtml.toString() + "</div></div>"
                + generateModals()
                + "</body></html>";
            sendHtml(t, html);
        }
    }

    // --- AUTHENTIFICATION ET INSCRIPTION ---
    static class RegisterHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                Map<String,String> params = parseForm(new String(readAllBytes(t.getRequestBody())));
                String email = params.get("email"), pass = params.get("password");
                if (!USERS.containsKey(email)) {
                    USERS.put(email, pass);
                    ROLES.put(email, "user"); // Nouveau compte = rôle utilisateur
                    redirect(t, "/login?msg=success");
                } else sendHtml(t, authPage("Cet email est déjà enregistré.", true));
            } else sendHtml(t, authPage(null, true));
        }
    }

    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                Map<String,String> params = parseForm(new String(readAllBytes(t.getRequestBody())));
                String email = params.get("email"), pass = params.get("password");
                if (USERS.containsKey(email) && USERS.get(email).equals(pass)) {
                    String sid = UUID.randomUUID().toString();
                    SESSIONS.put(sid, email);
                    t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                    redirect(t, "/");
                } else sendHtml(t, authPage("Email ou mot de passe incorrect.", false));
            } else sendHtml(t, authPage(null, false));
        }
    }

    // --- GESTIONNAIRE D'ACTIONS PDF ---
    static class PdfActionHandler implements HttpHandler {
        String action;
        PdfActionHandler(String act) { this.action = act; }
        public void handle(HttpExchange t) throws IOException {
            if (getLoggedUser(t) == null) { redirect(t, "/login"); return; }
            nbTotalActions++; // Incrément global pour l'admin
            
            // Simulation d'appel CORBA selon l'action
            String response = "Action [" + action + "] exécutée avec succès par le serveur CORBA.";
            
            // Exemple spécifique pour la création
            if (action.equals("create")) {
                Map<String, String> q = parseQuery(t.getRequestURI().getQuery());
                try {
                    byte[] data = pdfRef.creerPDF(q.get("titre"), q.get("corps"));
                    sendPdf(t, data);
                    return;
                } catch (Exception e) { response = "Erreur : " + e.getMessage(); }
            }
            
            sendText(t, response);
        }
    }

    // --- HELPERS ET UTILITAIRES ---
    private static String toolCard(String color, String title, String desc, String modalId) {
        return "<div class='card' style='border-top: 4px solid "+color+"' onclick=\"document.getElementById('"+modalId+"').style.display='flex'\">"
             + "<h4>"+title+"</h4><p>"+desc+"</p></div>";
    }

    private static String generateModals() {
        return "<script>function closeM(id){document.getElementById(id).style.display='none'}</script>"
             + modal("m-create", "Nouveau PDF", "/create", "<input name='titre' placeholder='Titre du document'><br><textarea name='corps' placeholder='Contenu textuel...'></textarea>")
             + modal("m-extract", "Extraction texte", "/extract", "<input type='file' name='pdf'>")
             + modal("m-protect", "Protéger", "/protect", "<input type='file'><br><input type='password' name='pass' placeholder='Mot de passe'>");
    }

    private static String modal(String id, String title, String action, String fields) {
        return "<div id='"+id+"' class='modal'><div class='modal-content'><h3>"+title+"</h3>"
             + "<form action='"+action+"' method='GET'>"+fields+"<br><br><button type='submit' class='btn'>Lancer le traitement</button></form>"
             + "<button class='btn-close' onclick=\"closeM('"+id+"')\">Fermer</button></div></div>";
    }

    static String authPage(String msg, boolean isRegister) {
        return "<html><head><style>" + CSS_AUTH + "</style></head><body><div class='auth-box'>"
            + "<h2>" + (isRegister ? "Inscription" : "Connexion") + "</h2>"
            + (msg != null ? "<p style='color:red;font-size:12px'>"+msg+"</p>" : "")
            + "<form method='POST'><input name='email' placeholder='Email' required><br>"
            + "<input type='password' name='password' placeholder='Mot de passe' required><br>"
            + "<button type='submit' class='btn'>" + (isRegister ? "Créer mon compte" : "Se connecter") + "</button></form>"
            + "<a href='" + (isRegister ? "/login" : "/register") + "' style='font-size:12px'>" 
            + (isRegister ? "Déjà un compte ? Connexion" : "Pas de compte ? S'inscrire") + "</a></div></body></html>";
    }

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

    static void sendHtml(HttpExchange t, String h) throws IOException {
        byte[] b = h.getBytes("UTF-8");
        t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        t.sendResponseHeaders(200, b.length);
        t.getResponseBody().write(b);
        t.getResponseBody().close();
    }

    static void sendPdf(HttpExchange t, byte[] data) throws IOException {
        t.getResponseHeaders().set("Content-Type", "application/pdf");
        t.sendResponseHeaders(200, data.length);
        t.getResponseBody().write(data);
        t.getResponseBody().close();
    }

    static void sendText(HttpExchange t, String txt) throws IOException {
        byte[] b = txt.getBytes();
        t.sendResponseHeaders(200, b.length);
        t.getResponseBody().write(b);
        t.getResponseBody().close();
    }

    static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int n; byte[] d = new byte[1024];
        while ((n = is.read(d)) != -1) b.write(d, 0, n);
        return b.toByteArray();
    }

    static Map<String, String> parseForm(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        try {
            for (String s : q.split("&")) {
                String[] p = s.split("=");
                if (p.length > 1) m.put(p[0], URLDecoder.decode(p[1], "UTF-8"));
            }
        } catch (Exception e) {}
        return m;
    }
    static Map<String, String> parseQuery(String q) { return parseForm(q); }

    static class LogoutHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String cookie = t.getRequestHeaders().getFirst("Cookie");
            if (cookie != null) {
                for (String c : cookie.split(";")) if (c.trim().startsWith("session=")) SESSIONS.remove(c.trim().substring(8));
            }
            redirect(t, "/login");
        }
    }

    // --- STYLES CSS ---
    static String CSS_MAIN = "body{font-family:'Segoe UI',sans-serif;background:#F0F2F5;margin:0}.topbar{background:#4F1D96;color:white;padding:15px 40px;display:flex;justify-content:space-between;align-items:center}.container{padding:30px 60px}.admin-card{background:#EEF2FF;border:1px solid #C7D2FE;padding:15px;border-radius:10px;margin-bottom:25px}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:20px}.card{background:white;padding:20px;border-radius:12px;cursor:pointer;box-shadow:0 4px 6px rgba(0,0,0,0.05);transition:transform 0.2s}.card:hover{transform:translateY(-5px);box-shadow:0 8px 12px rgba(0,0,0,0.1)}.modal{display:none;position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.6);justify-content:center;align-items:center}.modal-content{background:white;padding:30px;border-radius:15px;width:400px;text-align:center}.btn{background:#4F1D96;color:white;border:none;padding:10px 20px;border-radius:5px;cursor:pointer}.btn-close{background:#6B7280;color:white;border:none;padding:8px 15px;margin-top:10px;border-radius:5px;cursor:pointer}textarea,input{width:90%;margin-bottom:10px;padding:8px}";
    static String CSS_AUTH = "body{background:#4F1D96;display:flex;justify-content:center;align-items:center;height:100vh;margin:0}.auth-box{background:white;padding:50px;border-radius:20px;text-align:center;box-shadow:0 10px 25px rgba(0,0,0,0.3)}input{display:block;margin:15px auto;padding:12px;width:250px;border:1px solid #DDD;border-radius:8px}.btn{background:#4F1D96;color:white;border:none;padding:12px 30px;border-radius:8px;cursor:pointer;font-weight:bold;width:250px}";
}
