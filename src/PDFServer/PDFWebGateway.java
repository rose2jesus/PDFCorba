package PDFServer;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import PDFApp.*;
import org.omg.CosNaming.*;
import org.omg.CORBA.*;

public class PDFWebGateway {
    private static PDFService pdfRef;
    private static int nbCrees = 128, nbExtractions = 64, nbFusions = 32, nbProtections = 16;

    // Gestion des utilisateurs et sessions
    private static Map<String, String> userDatabase = new HashMap<>();
    private static Set<String> admins = new HashSet<>();
    private static Map<String, String> sessions = new HashMap<>();
    private static final String USERS_FILE = "users.txt";

    static {
        // Compte administrateur par défaut
        userDatabase.put("admin", "pass123");
        admins.add("admin");
    }

    public static void main(String[] args) throws Exception {
        chargerUtilisateurs();
        try {
            ORB orb = ORB.init(args, null);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));

            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

            // Routes Authentification
            server.createContext("/login",    new LoginHandler());
            server.createContext("/register", new RegisterHandler());
            server.createContext("/logout",   new LogoutHandler());

            // Routes Application (Protégées par checkAuth)
            server.createContext("/",         new UIHandler());
            server.createContext("/extract",  new ExtractHandler());
            // Ajoute ici tes autres contexts (merge, protect, etc.)

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
            System.out.println("Studio PDF CORBA Connecté -> http://localhost:8080");
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ══════════════════════════════════════════════════════════
    //  SÉCURITÉ & PERSISTANCE
    // ══════════════════════════════════════════════════════════
    private static void chargerUtilisateurs() {
        File f = new File(USERS_FILE);
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = br.readLine()) != null) {
                String[] p = l.split(":", 2);
                if (p.length == 2) userDatabase.put(p[0], p[1]);
            }
        } catch (IOException e) {}
    }

    private static synchronized void sauvegarderUtilisateurs() {
        try (PrintWriter out = new PrintWriter(new FileWriter(USERS_FILE))) {
            for (Map.Entry<String, String> e : userDatabase.entrySet()) 
                out.println(e.getKey() + ":" + e.getValue());
        } catch (IOException e) {}
    }

    static String getUsername(HttpExchange t) {
        String cookie = t.getRequestHeaders().getFirst("Cookie");
        if (cookie != null && cookie.contains("session=")) {
            String sid = cookie.split("session=")[1].split(";")[0];
            return sessions.get(sid);
        }
        return null;
    }

    static boolean checkAuth(HttpExchange t) throws IOException {
        String user = getUsername(t);
        if (user != null) return true;
        t.getResponseHeaders().set("Location", "/login");
        t.sendResponseHeaders(303, -1);
        return false;
    }

    // ══════════════════════════════════════════════════════════
    //  HANDLERS AUTHENTIFICATION
    // ══════════════════════════════════════════════════════════
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                Map<String, String> p = parseQuery(new String(readAllBytes(t.getRequestBody())));
                String u = p.get("user"), pw = p.get("pass");
                if (pw != null && pw.equals(userDatabase.get(u))) {
                    String sid = UUID.randomUUID().toString();
                    sessions.put(sid, u);
                    t.getResponseHeaders().add("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                    t.getResponseHeaders().set("Location", "/");
                    t.sendResponseHeaders(303, -1);
                    return;
                }
            }
            renderAuthPage(t, "Connexion", false);
        }
    }

    static class RegisterHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                Map<String, String> p = parseQuery(new String(readAllBytes(t.getRequestBody())));
                String u = p.get("user"), pw = p.get("pass");
                if (u != null && !userDatabase.containsKey(u)) {
                    userDatabase.put(u, pw);
                    sauvegarderUtilisateurs();
                    t.getResponseHeaders().set("Location", "/login");
                    t.sendResponseHeaders(303, -1);
                    return;
                }
            }
            renderAuthPage(t, "Inscription", true);
        }
    }

    static class LogoutHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            t.getResponseHeaders().add("Set-Cookie", "session=; Path=/; Max-Age=0; HttpOnly");
            t.getResponseHeaders().set("Location", "/login");
            t.sendResponseHeaders(303, -1);
        }
    }

    private static void renderAuthPage(HttpExchange t, String title, boolean isReg) throws IOException {
        String action = isReg ? "/register" : "/login";
        String link = isReg ? "/login" : "/register";
        String linkText = isReg ? "Déjà un compte ? Connexion" : "Pas de compte ? S'inscrire";
        
        String html = "<html><head><title>"+title+"</title><style>"
            + "body{margin:0;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;background:#F5F3FF}"
            + ".card{background:#fff;padding:40px;border-radius:20px;width:320px;box-shadow:0 10px 25px rgba(0,0,0,0.05)}"
            + "input{width:100%;padding:12px;margin:10px 0;border:1px solid #EDE9FE;border-radius:8px;outline:none}"
            + ".btn{width:100%;background:#7C3AED;color:#fff;border:none;padding:12px;border-radius:8px;font-weight:600;cursor:pointer;margin-top:10px}"
            + "a{display:block;text-align:center;margin-top:15px;font-size:12px;color:#7C3AED;text-decoration:none}</style></head>"
            + "<body><div class='card'><h2>"+title+"</h2><form method='POST' action='"+action+"'>"
            + "User<input name='user' required>Pass<input name='pass' type='password' required>"
            + "<button class='btn'>Entrer</button></form><a href='"+link+"'>"+linkText+"</a></div></body></html>";
        sendHtml(t, html);
    }

    // ══════════════════════════════════════════════════════════
    //  INTERFACE STUDIO (UI)
    // ══════════════════════════════════════════════════════════
    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!checkAuth(t)) return;
            String user = getUsername(t);
            boolean isAdmin = admins.contains(user);

            String html = "<html><head><meta charset='UTF-8'><style>"
                + "*{box-sizing:border-box;margin:0;padding:0;font-family:sans-serif}"
                + "body{background:#F8F9FD} .topbar{background:#4F1D96;padding:40px 20px 80px;color:#fff;text-align:center}"
                + ".nav-top{display:flex;justify-content:space-between;max-width:1100px;margin: -30px auto 20px;padding:0 20px}"
                + ".container{max-width:1100px;margin:0 auto;padding:0 20px}"
                + ".stats{display:grid;grid-template-columns:repeat(4,1fr);gap:15px;margin-top:-60px;margin-bottom:30px}"
                + ".stat{background:#fff;padding:20px;border-radius:8px;text-align:center;box-shadow:0 2px 10px rgba(0,0,0,0.05);border-top:4px solid #7C3AED}"
                + ".tools{display:grid;grid-template-columns:repeat(3,1fr);gap:20px}"
                + ".tc{background:#fff;padding:30px;border-radius:8px;text-align:center;cursor:pointer;transition:0.2s;border-top:4px solid #DDD6FE}"
                + ".tc:hover{transform:translateY(-3px);box-shadow:0 10px 20px rgba(0,0,0,0.05)}"
                + ".admin-sec{background:#FEF2F2;padding:20px;border-radius:8px;margin-bottom:20px;border:1px solid #FEE2E2;color:#991B1B}"
                + ".logout{color:#fff;text-decoration:none;font-size:12px;border:1px solid rgba(255,255,255,0.4);padding:5px 12px;border-radius:4px}"
                + ".overlay{display:none;position:fixed;inset:0;background:rgba(0,0,0,0.4);z-index:100;align-items:center;justify-content:center}"
                + ".active{display:flex} .modal{background:#fff;padding:30px;border-radius:12px;width:350px}"
                + "</style></head><body>"
                + "<div class='topbar'><h1>Studio PDF CORBA</h1><p>Connecté en tant que: <b>"+user+"</b></p></div>"
                + "<div class='nav-top'><div style='width:1px'></div><a href='/logout' class='logout'>Déconnexion</a></div>"
                + "<div class='container'>"
                + "<div class='stats'>"
                + "<div class='stat'><h3>"+nbCrees+"</h3><p>Créés</p></div><div class='stat'><h3>"+nbExtractions+"</h3><p>Extractions</p></div>"
                + "<div class='stat'><h3>"+nbFusions+"</h3><p>Fusions</p></div><div class='stat'><h3>"+nbProtections+"</h3><p>Protégés</p></div>"
                + "</div>"
                + (isAdmin ? "<div class='admin-sec'><b>Outils Administrateur</b><br>Gestion des logs et serveurs CORBA active.</div>" : "")
                + "<div class='tools'>"
                + "<div class='tc' onclick='openM(\"m1\")' style='border-top-color:#A78BFA'><h3>Extraire Texte</h3></div>"
                + "<div class='tc' onclick='openM(\"m1\")' style='border-top-color:#7DD3FC'><h3>Images</h3></div>"
                + "<div class='tc' onclick='openM(\"m1\")' style='border-top-color:#34D399'><h3>Protéger</h3></div>"
                + "<div class='tc' onclick='openM(\"m1\")' style='border-top-color:#FBBF24'><h3>Fusionner</h3></div>"
                + "<div class='tc' onclick='openM(\"m1\")' style='border-top-color:#F472B6'><h3>QR Code</h3></div>"
                + "<div class='tc' onclick='openM(\"m1\")' style='border-top-color:#2DD4BF'><h3>Signer</h3></div>"
                + "</div></div>"
                + "<div id='m1' class='overlay'><div class='modal'><h2>Action PDF</h2><form method='POST' enctype='multipart/form-data' action='/extract'><input type='file' name='doc' required><br><br><button style='width:100%;padding:10px;background:#7C3AED;color:white;border:none;border-radius:6px'>Lancer</button><button type='button' onclick='closeM(\"m1\")' style='width:100%;background:none;border:none;margin-top:10px;color:#999'>Annuler</button></form></div></div>"
                + "<script>function openM(id){document.getElementById(id).classList.add('active')} function closeM(id){document.getElementById(id).classList.remove('active')}</script>"
                + "</body></html>";
            sendHtml(t, html);
        }
    }

    // HANDLER TECHNIQUE EXEMPLE
    static class ExtractHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!checkAuth(t)) return;
            try {
                byte[] pdf = parseMultipart(t).files.values().iterator().next();
                String res = pdfRef.extraireTexte(pdf);
                nbExtractions++;
                sendHtml(t, "<h3>Résultat :</h3><pre>"+res+"</pre><a href='/'>Retour</a>");
            } catch (Exception e) { sendHtml(t, "Erreur"); }
        }
    }

    // UTILS
    static void sendHtml(HttpExchange t, String h) throws IOException {
        byte[] b = h.getBytes(StandardCharsets.UTF_8);
        t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        t.sendResponseHeaders(200, b.length);
        t.getResponseBody().write(b); t.getResponseBody().close();
    }
    static byte[] readAllBytes(InputStream is) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while((n=is.read(buf))!=-1) b.write(buf,0,n); return b.toByteArray(); }
    static Map<String,String> parseQuery(String q) { Map<String,String> m = new HashMap<>(); if(q==null) return m; for(String s : q.split("&")){String[] kv=s.split("=",2); if(kv.length>1) m.put(kv[0], kv[1]);} return m; }
    static class MultipartData { Map<String,byte[]> files = new HashMap<>(); }
    static MultipartData parseMultipart(HttpExchange t) throws Exception {
        MultipartData mp = new MultipartData();
        String ct = t.getRequestHeaders().getFirst("Content-Type");
        String boundary = "--" + ct.split("boundary=")[1].trim();
        String raw = new String(readAllBytes(t.getRequestBody()), "ISO-8859-1");
        for (String part : raw.split(java.util.regex.Pattern.quote(boundary))) {
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
