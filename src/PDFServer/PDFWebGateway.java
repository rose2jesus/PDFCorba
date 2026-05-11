package PDFServer;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import PDFApp.*;
import org.omg.CosNaming.*;
import org.omg.CORBA.*;

public class PDFWebGateway {
    private static PDFService pdfRef;
    private static int nbCrees = 0, nbExtractions = 0, nbFusions = 0, nbProtections = 0;
    
    // Base de données utilisateurs (Username -> Password)
    private static Map<String, String> userDatabase = new HashMap<>();
    // Sessions actives (SessionID -> Username)
    private static Map<String, String> sessions = new HashMap<>();
    private static final String USERS_FILE = "users.txt";

    static {
        // Compte par défaut
        userDatabase.put("admin", "pass123");
    }

    public static void main(String[] args) throws Exception {
        // Charger les utilisateurs sauvegardés au démarrage
        chargerUtilisateurs();

        try {
            ORB orb = ORB.init(args, null);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));

            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            
            // Routes publiques
            server.createContext("/login",    new LoginHandler());
            server.createContext("/register", new RegisterHandler());
            
            // Routes protégées
            server.createContext("/",         new UIHandler());
            server.createContext("/extract",  new ExtractHandler());
            // Ajoute ici tes autres contexts (merge, protect, etc.) comme dans ton code original

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
            System.out.println("Studio PDFCorba opérationnel sur http://localhost:8080");
            server.start();
        } catch (Exception e) {
            System.err.println("Erreur d'initialisation CORBA : " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════
    //  PERSISTANCE DES UTILISATEURS
    // ══════════════════════════════════════════════════════════
    private static synchronized void sauvegarderUtilisateurs() {
        try (PrintWriter out = new PrintWriter(new FileWriter(USERS_FILE))) {
            for (Map.Entry<String, String> entry : userDatabase.entrySet()) {
                out.println(entry.getKey() + ":" + entry.getValue());
            }
        } catch (IOException e) {
            System.err.println("Erreur sauvegarde : " + e.getMessage());
        }
    }

    private static void chargerUtilisateurs() {
        File file = new File(USERS_FILE);
        if (!file.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) userDatabase.put(parts[0], parts[1]);
            }
        } catch (IOException e) {
            System.err.println("Erreur chargement utilisateurs : " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════
    //  AUTHENTIFICATION & SÉCURITÉ
    // ══════════════════════════════════════════════════════════
    static boolean checkAuth(HttpExchange t) throws IOException {
        String cookieHeader = t.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader != null) {
            for (String cookie : cookieHeader.split(";")) {
                if (cookie.trim().startsWith("session=")) {
                    String sid = cookie.split("=")[1];
                    if (sessions.containsKey(sid)) return true;
                }
            }
        }
        t.getResponseHeaders().set("Location", "/login");
        t.sendResponseHeaders(303, -1);
        return false;
    }

    static class RegisterHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                Map<String, String> params = parseQuery(new String(readAllBytes(t.getRequestBody())));
                String user = params.get("user");
                String pass = params.get("pass");

                if (user != null && !user.isEmpty() && !userDatabase.containsKey(user)) {
                    userDatabase.put(user, pass);
                    sauvegarderUtilisateurs(); // Sauvegarde immédiate sur disque
                    t.getResponseHeaders().set("Location", "/login");
                    t.sendResponseHeaders(303, -1);
                } else {
                    renderAuthPage(t, "Inscription", "Nom d'utilisateur déjà pris ou invalide", true);
                }
            } else {
                renderAuthPage(t, "Inscription", null, true);
            }
        }
    }

    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                Map<String, String> params = parseQuery(new String(readAllBytes(t.getRequestBody())));
                String user = params.get("user");
                String pass = params.get("pass");

                String storedPass = userDatabase.get(user);
                if (storedPass != null && storedPass.equals(pass)) {
                    String sid = UUID.randomUUID().toString();
                    sessions.put(sid, user);
                    t.getResponseHeaders().add("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                    t.getResponseHeaders().set("Location", "/");
                    t.sendResponseHeaders(303, -1);
                } else {
                    renderAuthPage(t, "Connexion", "Identifiants incorrects", false);
                }
            } else {
                renderAuthPage(t, "Connexion", null, false);
            }
        }
    }

    private static void renderAuthPage(HttpExchange t, String title, String err, boolean isReg) throws IOException {
        String action = isReg ? "/register" : "/login";
        String toggleLink = isReg ? "/login" : "/register";
        String toggleText = isReg ? "Déjà inscrit ? Connectez-vous" : "Pas de compte ? Créer un compte";

        String html = "<html><head><title>" + title + "</title>" + CSS + "</head>"
            + "<body style='display:flex;align-items:center;justify-content:center;height:100vh;background:#F3F4F6'>"
            + "<div class='modal' style='display:block;width:320px'>"
            + "<h2 style='text-align:center'>" + title + "</h2>"
            + (err != null ? "<p style='color:#DC2626;font-size:12px;text-align:center'>" + err + "</p>" : "")
            + "<form method='POST' action='" + action + "'>"
            + "<label>Utilisateur</label><input name='user' class='inp' required>"
            + "<label>Mot de passe</label><input name='pass' type='password' class='inp' required>"
            + "<button class='btn-gen'>" + title + "</button></form>"
            + "<div style='text-align:center;margin-top:15px'><a href='" + toggleLink + "' style='font-size:12px;color:#6D28D9;text-decoration:none'>" + toggleText + "</a></div>"
            + "</div></body></html>";
        sendHtml(t, html);
    }

    // ══════════════════════════════════════════════════════════
    //  INTERFACE PRINCIPALE (UI)
    // ══════════════════════════════════════════════════════════
    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!checkAuth(t)) return;
            String html = "<html><head><meta charset='UTF-8'>" + CSS + "</head><body>"
                + "<div class='topbar'><h1>Studio PDFCorba</h1></div>"
                + "<div class='main'>"
                + "<div class='stats'>"
                + "<div class='stat'><b>Actions</b><div class='stat-n'>" + (nbExtractions + nbFusions) + "</div></div>"
                + "</div>"
                + "<div class='tools'>"
                + "<div class='tc' onclick='openM(\"m-extract\")'><h3>Extraire Texte</h3></div>"
                // Ajoute tes autres outils ici
                + "</div>"
                + "</div>"
                + modal("m-extract", "Extraire le texte", "/extract", "<input type='file' name='doc' class='inp' required>")
                + "<script>function openM(id){document.getElementById(id).classList.add('active')} function closeM(id){document.getElementById(id).classList.remove('active')}</script>"
                + "</body></html>";
            sendHtml(t, html);
        }
    }

    static class ExtractHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!checkAuth(t)) return;
            try {
                MultipartData mp = parseMultipart(t);
                String texte = pdfRef.extraireTexte(mp.files.get("doc"));
                nbExtractions++;
                sendHtml(t, "<h3>Résultat :</h3><pre style='background:#eee;padding:15px'>" + texte + "</pre><br><a href='/'>Retour</a>");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  UTILS & CSS
    // ══════════════════════════════════════════════════════════
    static final String CSS = "<style>body{margin:0;background:#f8fafc;font-family:sans-serif}.topbar{background:#6D28D9;color:white;padding:15px 25px}.main{padding:25px}.stats{display:flex;margin-bottom:25px}.stat{background:white;padding:20px;border-radius:10px;box-shadow:0 1px 3px rgba(0,0,0,0.1);width:150px}.stat-n{font-size:24px;font-weight:bold;color:#6D28D9}.tools{display:grid;grid-template-columns:1fr 1fr;gap:20px}.tc{background:white;padding:25px;border-radius:12px;text-align:center;cursor:pointer;border:2px solid transparent;transition:0.2s}.tc:hover{border-color:#6D28D9;transform:translateY(-2px)}.modal{display:none;position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);background:white;padding:30px;border-radius:15px;box-shadow:0 10px 25px rgba(0,0,0,0.2);z-index:100}.active{display:block}.inp{width:100%;padding:10px;margin:8px 0;border:1px solid #ddd;border-radius:6px;box-sizing:border-box}.btn-gen{width:100%;padding:12px;background:#6D28D9;color:white;border:none;border-radius:6px;cursor:pointer;font-weight:bold;margin-top:10px}</style>";

    static String modal(String id, String title, String action, String content) {
        return "<div id='"+id+"' class='modal'><h3>"+title+"</h3><form method='POST' enctype='multipart/form-data' action='"+action+"'>"+content+"<button class='btn-gen'>Lancer</button><button type='button' onclick='closeM(\""+id+"\")' style='background:none;border:none;color:gray;width:100%;margin-top:10px;cursor:pointer'>Annuler</button></form></div>";
    }

    static void sendHtml(HttpExchange t, String h) throws IOException {
        byte[] b = h.getBytes(StandardCharsets.UTF_8);
        t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        t.sendResponseHeaders(200, b.length);
        t.getResponseBody().write(b); t.getResponseBody().close();
    }

    static void sendError(HttpExchange t, String m) throws IOException { sendHtml(t, "<h3>Erreur</h3><p>"+m+"</p><a href='/'>Retour</a>"); }
    static byte[] readAllBytes(InputStream is) throws IOException { ByteArrayOutputStream bos = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while((n=is.read(buf))!=-1) bos.write(buf,0,n); return bos.toByteArray(); }
    static Map<String,String> parseQuery(String q) { Map<String,String> m = new HashMap<>(); if(q==null) return m; for(String s:q.split("&")){String[] kv=s.split("=",2); if(kv.length>1) m.put(kv[0],kv[1]);} return m; }

    static class MultipartData { Map<String,byte[]> files = new HashMap<>(); }
    static MultipartData parseMultipart(HttpExchange t) throws Exception {
        MultipartData mp = new MultipartData();
        String ct = t.getRequestHeaders().getFirst("Content-Type");
        String boundary = "--" + ct.split("boundary=")[1].trim();
        byte[] body = readAllBytes(t.getRequestBody());
        String raw = new String(body, "ISO-8859-1");
        String[] parts = raw.split(java.util.regex.Pattern.quote(boundary));
        for (String part : parts) {
            if (part.contains("filename=")) {
                String name = part.split("name=\"")[1].split("\"")[0];
                int start = part.indexOf("\r\n\r\n") + 4;
                int end = part.lastIndexOf("\r\n");
                mp.files.put(name, part.substring(start, end).getBytes("ISO-8859-1"));
            }
        }
        return mp;
    }
}
