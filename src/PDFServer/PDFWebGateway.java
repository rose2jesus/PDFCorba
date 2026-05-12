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
            // Initialisation CORBA sécurisée
            try {
                ORB orb = ORB.init(args, null);
                org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
                NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
                pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));
            } catch (Exception e) {
                System.out.println("En attente du serveur CORBA...");
            }

            // Port dynamique pour Render
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            
            server.createContext("/", t -> handleUI(t));
            server.createContext("/login", t -> handleLogin(t));
            server.createContext("/logout", t -> {
                t.getResponseHeaders().set("Set-Cookie", "session=; Max-Age=0");
                redirect(t, "/login");
            });

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
            server.start();
            System.out.println("Gateway prête sur le port " + port);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static void handleUI(HttpExchange t) throws IOException {
        String user = getLoggedUser(t);
        if (user == null) { redirect(t, "/login"); return; }
        
        boolean isAdmin = "admin".equals(ROLES.get(user));
        String html = "<html><body><h1>Studio PDF</h1>"
            + (isAdmin ? "<p style='color:red'><b>DASHBOARD ADMIN : " + nbTotalActions + " actions</b></p>" : "")
            + "<ul><li>Service Fusion</li><li>Service Signature</li></ul>"
            + "<a href='/logout'>Deconnexion</a></body></html>";
        sendHtml(t, html);
    }

    private static void handleLogin(HttpExchange t) throws IOException {
        if ("POST".equals(t.getRequestMethod())) {
            try {
                // CORRECTION CRITIQUE : Try-catch interne pour parseForm
                String body = new String(readAllBytes(t.getRequestBody()), "UTF-8");
                Map<String, String> params = parseForm(body);
                String email = params.get("email");
                String pass = params.get("password");

                if (USERS.containsKey(email) && USERS.get(email).equals(pass)) {
                    String sid = UUID.randomUUID().toString();
                    SESSIONS.put(sid, email);
                    t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                    redirect(t, "/");
                } else { sendHtml(t, "Erreur login. <a href='/login'>Retour</a>"); }
            } catch (Exception e) { sendHtml(t, "Erreur formulaire."); }
        } else {
            sendHtml(t, "<form method='POST'>Email: <input name='email'><br>Pass: <input type='password' name='password'><br><button>OK</button></form>");
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
    static Map<String, String> parseForm(String q) throws Exception {
        Map<String, String> m = new HashMap<>();
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
