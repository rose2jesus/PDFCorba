package PDFServer;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.*;
import java.sql.*;
import PDFApp.*;
import org.omg.CosNaming.*;
import org.omg.CORBA.*;
import org.mindrot.jbcrypt.BCrypt;

public class PDFWebGateway {
    private static PDFService pdfRef;
    private static int nbCrees = 0, nbExtractions = 0, nbFusions = 0, nbProtections = 0;

    // Sessions volatiles (conservées en mémoire pour la navigation)
    private static final Map<String,String> SESSIONS = Collections.synchronizedMap(new HashMap<>());

    // ── Connexion PostgreSQL robuste pour Render ───────────────────────────
    private static Connection getDbConnection() throws SQLException {
        String dbUrl = System.getenv("DATABASE_URL");
        
        if (dbUrl == null) {
            // Fallback local si la variable n'est pas définie
            dbUrl = "jdbc:postgresql://localhost:5432/pdfcorba_db";
            return DriverManager.getConnection(dbUrl, "postgres", "password");
        }

        // Si Render fournit une URL de type postgres://, on la convertit proprement pour JDBC
        if (dbUrl.startsWith("postgres://")) {
            try {
                String cleanUri = dbUrl.substring(11);
                String[] authAndHost = cleanUri.split("@");
                String[] userAndPass = authAndHost[0].split(":");
                String[] hostAndDb = authAndHost[1].split("/");
                
                String username = userAndPass[0];
                String password = userAndPass[1];
                String hostAndPort = hostAndDb[0];
                String dbName = hostAndDb[1];
                
                String jdbcUrl = "jdbc:postgresql://" + hostAndPort + "/" + dbName;
                if (!jdbcUrl.contains("sslmode")) {
                    jdbcUrl += "?sslmode=require";
                }
                return DriverManager.getConnection(jdbcUrl, username, password);
            } catch (Exception e) {
                String fallbackUrl = dbUrl.replace("postgres://", "jdbc:postgresql://");
                if (!fallbackUrl.contains("sslmode")) {
                    fallbackUrl += "?sslmode=require";
                }
                return DriverManager.getConnection(fallbackUrl);
            }
        }
        return DriverManager.getConnection(dbUrl);
    }

    // ── Initialisation de la base de données ──────────────────────────────
    private static void initDatabase() {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS users ("
                + "username VARCHAR(50) PRIMARY KEY, "
                + "password VARCHAR(255) NOT NULL, "
                + "role VARCHAR(20) NOT NULL"
                + ");";
        try (Connection conn = getDbConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
            
            // Insertion des comptes par défaut si la table est vide
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    try (PreparedStatement pstmt = conn.prepareStatement("INSERT INTO users VALUES (?, ?, ?)")) {
                        // admin / admin123
                        pstmt.setString(1, "admin");
                        pstmt.setString(2, BCrypt.hashpw("admin123", BCrypt.gensalt()));
                        pstmt.setString(3, "admin");
                        pstmt.executeUpdate();
                        
                        // user / user123
                        pstmt.setString(1, "user");
                        pstmt.setString(2, BCrypt.hashpw("user123", BCrypt.gensalt()));
                        pstmt.setString(3, "user");
                        pstmt.executeUpdate();
                    }
                }
            }
            System.out.println("Base de données PostgreSQL initialisée avec succès.");
        } catch (Exception e) {
            System.err.println("Erreur d'initialisation de la base de données : " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ── Session helpers ──────────────────────────────────────
    static String getSessionId(HttpExchange t) {
        String cookieHeader = t.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null) return null;
        for (String c : cookieHeader.split(";")) {
            c = c.trim();
            if (c.startsWith("session=")) return c.substring(8);
        }
        return null;
    }

    static String getRole(HttpExchange t) {
        String sid = getSessionId(t);
        if (sid == null) return null;
        String val = SESSIONS.get(sid);
        if (val == null) return null;
        return val.contains(":") ? val.split(":")[0] : val;
    }

    static boolean isLoggedIn(HttpExchange t) { return getRole(t) != null; }

    static void redirect(HttpExchange t, String path) throws IOException {
        t.getResponseHeaders().set("Location", path);
        t.sendResponseHeaders(302, -1);
        t.getResponseBody().close();
    }

    // ══════════════════════════════════════════════════════════
    //  MAIN
    // ══════════════════════════════════════════════════════════
    public static void main(String[] args) throws Exception {
        try {
            // Charger le driver explicitement pour éviter les soucis de classpath sous Docker
            Class.forName("org.postgresql.Driver");
            
            // Initialiser les tables PostgreSQL
            initDatabase();

            ORB orb = ORB.init(args, null);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));

            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.createContext("/login",    new LoginHandler());
            server.createContext("/logout",   new LogoutHandler());
            server.createContext("/register", new RegisterHandler());
            server.createContext("/",         new UIHandler());
            server.createContext("/admin",    new AdminHandler());
            server.createContext("/create",   new GenerateHandler());
            server.createContext("/extract",  new ExtractHandler());
            server.createContext("/image",    new ToImageHandler());
            server.createContext("/protect",  new ProtectHandler());
            server.createContext("/merge",    new MergeHandler());
            server.createContext("/split",    new SplitHandler());
            server.createContext("/delete",   new DeleteHandler());
            server.createContext("/pages",    new ExtractPagesHandler());
            server.createContext("/compress", new CompressHandler());
            server.createContext("/meta",     new MetaReadHandler());
            server.createContext("/metamod",  new MetaModHandler());
            server.createContext("/qrcode",   new QRCodeHandler());
            server.createContext("/sign",     new SignHandler());
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
            System.out.println("Studio PDF CORBA -> http://localhost:8080");
            server.start();
        } catch (Exception e) {
            System.err.println("ERREUR : " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ══════════════════════════════════════════════════════════
    //  CSS COMMUN
    // ══════════════════════════════════════════════════════════
    static final String CSS_BASE =
        "<style>"
        + "*{box-sizing:border-box;margin:0;padding:0;font-family:'Inter',sans-serif}"
        + "body{background:#F5F3FF;min-height:100vh}"
        + ".topbar{background:linear-gradient(135deg,#4F1D96 0%,#6D28D9 40%,#7C3AED 70%,#8B5CF6 100%);padding:28px 28px 80px;position:relative;overflow:hidden}"
        + ".topbar::before{content:'';position:absolute;top:-60px;right:-60px;width:220px;height:220px;background:rgba(255,255,255,0.06);border-radius:50%}"
        + ".topbar::after{content:'';position:absolute;bottom:-80px;left:30%;width:300px;height:300px;background:rgba(255,255,255,0.04);border-radius:50%}"
        + ".topbar-row{display:flex;justify-content:space-between;align-items:flex-start;position:relative;z-index:1}"
        + ".topbar h1{font-size:20px;font-weight:600;color:#fff;margin-bottom:4px}"
        + ".topbar p{font-size:13px;color:rgba(255,255,255,0.7)}"
        + ".badge{display:inline-flex;align-items:center;gap:6px;background:rgba(255,255,255,0.15);border:0.5px solid rgba(255,255,255,0.3);color:#fff;padding:6px 14px;border-radius:20px;font-size:11px;font-weight:500}"
        + ".dot{width:7px;height:7px;border-radius:50%;background:#4ADE80;animation:blink 1.5s infinite}"
        + "@keyframes blink{0%,100%{opacity:1}50%{opacity:0.3}}"
        + ".nav-links{display:flex;gap:10px;align-items:center}"
        + ".nav-link{color:rgba(255,255,255,0.8);text-decoration:none;font-size:12px;font-weight:500;padding:6px 14px;border-radius:16px;background:rgba(255,255,255,0.1);border:0.5px solid rgba(255,255,255,0.2);transition:0.2s}"
        + ".nav-link:hover{background:rgba(255,255,255,0.2)}"
        + ".nav-link.active{background:rgba(255,255,255,0.25)}"
        + ".nav-link.logout{background:rgba(239,68,68,0.3);border-color:rgba(239,68,68,0.4)}"
        + ".main{padding:0 24px 32px;margin-top:-48px;position:relative;z-index:2}"
        + ".stats{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:28px}"
        + ".stat{border-radius:16px;padding:20px}"
        + ".stat-n{font-size:26px;font-weight:600;margin-bottom:4px}"
        + ".stat-l{font-size:11px;font-weight:500;letter-spacing:1px;text-transform:uppercase;opacity:0.75;margin-bottom:2px}"
        + ".stat-s{font-size:11px;opacity:0.6}"
        + ".st1{background:linear-gradient(135deg,#EDE9FE,#DDD6FE)}.st1 .stat-n,.st1 .stat-l,.st1 .stat-s{color:#4C1D95}"
        + ".st2{background:linear-gradient(135deg,#E0F2FE,#BAE6FD)}.st2 .stat-n,.st2 .stat-l,.st2 .stat-s{color:#0C4A6E}"
        + ".st3{background:linear-gradient(135deg,#DCFCE7,#BBF7D0)}.st3 .stat-n,.st3 .stat-l,.st3 .stat-s{color:#14532D}"
        + ".st4{background:linear-gradient(135deg,#FEF3C7,#FDE68A)}.st4 .stat-n,.st4 .stat-l,.st4 .stat-s{color:#78350F}"
        + ".sec-label{font-size:11px;font-weight:500;letter-spacing:2px;text-transform:uppercase;color:#9CA3AF;margin-bottom:14px}"
        + ".tools{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:24px}"
        + ".tc{background:#fff;border-radius:14px;padding:18px 14px;cursor:pointer;border:1.5px solid transparent;transition:all 0.2s}"
        + ".tc:hover{border-color:#A78BFA;transform:translateY(-3px);box-shadow:0 8px 24px rgba(124,58,237,0.12)}"
        + ".tc-bar{height:3px;border-radius:2px;margin-bottom:14px;width:32px}"
        + ".tc h3{font-size:13px;font-weight:500;color:#1E1B4B;margin-bottom:5px}"
        + ".tc p{font-size:11px;color:#9CA3AF;line-height:1.5}"
        + ".tc-tag{display:inline-block;font-size:10px;font-weight:500;padding:3px 9px;border-radius:10px;margin-top:10px}"
        + ".bottom{display:grid;grid-template-columns:1.1fr 0.9fr;gap:14px}"
        + ".create{background:#fff;border-radius:16px;padding:24px}"
        + ".create h2{font-size:15px;font-weight:600;color:#1E1B4B;margin-bottom:3px}"
        + ".create .sub{font-size:12px;color:#9CA3AF;margin-bottom:18px}"
        + ".inp{background:#F8F7FF;border:1.5px solid #EDE9FE;border-radius:10px;padding:10px 14px;font-size:13px;color:#1E1B4B;width:100%;font-family:inherit;margin-bottom:10px;outline:none}"
        + ".inp:focus{border-color:#7C3AED}"
        + ".inp-row{display:grid;grid-template-columns:1fr 1fr;gap:10px}"
        + "textarea.inp{resize:none;height:68px}"
        + ".btn-gen{width:100%;background:linear-gradient(135deg,#6D28D9,#7C3AED);color:#fff;border:none;padding:12px;border-radius:10px;font-size:13px;font-weight:600;cursor:pointer;margin-top:4px}"
        + ".btn-gen:hover{opacity:0.92}"
        + ".activity{background:#fff;border-radius:16px;padding:24px}"
        + ".activity h2{font-size:15px;font-weight:600;color:#1E1B4B;margin-bottom:16px}"
        + ".ai{display:flex;align-items:center;gap:12px;padding:10px 0;border-bottom:1px solid #F5F3FF}"
        + ".ai:last-child{border-bottom:none;padding-bottom:0}"
        + ".ai-ico{width:34px;height:34px;border-radius:9px;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:700;flex-shrink:0}"
        + ".ai-info{flex:1}"
        + ".ai-info b{font-size:12px;font-weight:500;color:#1E1B4B;display:block}"
        + ".ai-info small{font-size:11px;color:#9CA3AF}"
        + ".overlay{display:none;position:fixed;inset:0;background:rgba(79,29,150,0.2);backdrop-filter:blur(8px);z-index:100;align-items:center;justify-content:center}"
        + ".overlay.active{display:flex}"
        + ".modal{background:#fff;border-radius:20px;padding:32px;width:90%;max-width:440px;box-shadow:0 40px 80px rgba(79,29,150,0.15)}"
        + ".modal h2{font-size:15px;font-weight:600;color:#1E1B4B;margin-bottom:4px}"
        + ".modal .msub{font-size:12px;color:#9CA3AF;margin-bottom:20px}"
        + ".modal label{font-size:11px;font-weight:500;color:#6B7280;display:block;margin-bottom:5px;margin-top:12px;text-transform:uppercase;letter-spacing:1px}"
        + ".modal input,.modal select{width:100%;padding:10px 14px;border:1.5px solid #EDE9FE;border-radius:10px;font-size:13px;color:#1E1B4B;outline:none;font-family:inherit;background:#F8F7FF}"
        + ".modal input:focus,.modal select:focus{border-color:#7C3AED}"
        + ".upload-zone{border:2px dashed #DDD6FE;border-radius:12px;padding:24px;text-align:center;cursor:pointer;background:#F8F7FF;margin:8px 0;transition:0.2s}"
        + ".upload-zone:hover{border-color:#7C3AED;background:#F3E8FF}"
        + ".upload-zone p{font-size:13px;color:#7C3AED;font-weight:500;margin-bottom:4px}"
        + ".upload-zone small{font-size:11px;color:#9CA3AF}"
        + ".btn-row{display:flex;gap:10px;margin-top:20px}"
        + ".btn-ok{flex:1;background:linear-gradient(135deg,#6D28D9,#7C3AED);color:#fff;border:none;padding:12px;border-radius:10px;font-weight:600;font-size:13px;cursor:pointer}"
        + ".btn-cancel{flex:1;background:#F5F3FF;color:#6B7280;border:none;padding:12px;border-radius:10px;font-weight:500;font-size:13px;cursor:pointer}"
        + ".footer{text-align:center;padding:20px;color:#C4B5FD;font-size:11px}"
        + "</style>";

    // ══════════════════════════════════════════════════════════
    //  PAGE DE CONNEXION (Vérification PostgreSQL + BCrypt)
    // ══════════════════════════════════════════════════════════
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String method = t.getRequestMethod();

            if ("GET".equalsIgnoreCase(method)) {
                String role = getRole(t);
                if ("admin".equals(role)) { redirect(t, "/admin"); return; }
                if ("user".equals(role))  { redirect(t, "/");      return; }

                String msg = t.getRequestURI().getQuery() != null && t.getRequestURI().getQuery().contains("error")
                    ? "<div style='background:#FEE2E2;border:1px solid #FCA5A5;color:#991B1B;padding:12px 16px;border-radius:10px;font-size:13px;margin-bottom:16px'>Identifiants incorrects. Veuillez réessayer.</div>"
                    : "";

                sendHtml(t, loginPage(msg));
            } else if ("POST".equalsIgnoreCase(method)) {
                byte[] body = readAllBytes(t.getRequestBody());
                Map<String,String> params; 
                try { params = parseFormBody(new String(body, "UTF-8")); } catch (Exception ex) { redirect(t, "/login?error=1"); return; }
                String username = params.getOrDefault("username", "").trim();
                String password = params.getOrDefault("password", "");

                // Vérification en base PostgreSQL avec BCrypt
                String query = "SELECT password, role FROM users WHERE username = ?";
                try (Connection conn = getDbConnection(); PreparedStatement pstmt = conn.prepareStatement(query)) {
                    pstmt.setString(1, username);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next() && BCrypt.checkpw(password, rs.getString("password"))) {
                            String role = rs.getString("role");
                            String sid = UUID.randomUUID().toString();
                            SESSIONS.put(sid, role + ":" + username);
                            t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                            if ("admin".equals(role)) { redirect(t, "/admin"); }
                            else                      { redirect(t, "/");      }
                            return;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                redirect(t, "/login?error=1");
            } else {
                t.sendResponseHeaders(405, -1);
                t.getResponseBody().close();
            }
        }

        static String loginPage(String errorMsg) {
            return "<!DOCTYPE html><html lang='fr'><head>"
                + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Connexion - Studio PDF CORBA</title>"
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap' rel='stylesheet'>"
                + "<style>"
                + "*{box-sizing:border-box;margin:0;padding:0;font-family:'Inter',sans-serif}"
                + "body{background:linear-gradient(135deg,#4F1D96 0%,#6D28D9 50%,#8B5CF6 100%);min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}"
                + ".card{background:#fff;border-radius:24px;padding:40px;width:100%;max-width:400px;box-shadow:0 40px 80px rgba(79,29,150,0.3)}"
                + ".logo{width:52px;height:52px;background:linear-gradient(135deg,#6D28D9,#8B5CF6);border-radius:16px;display:flex;align-items:center;justify-content:center;margin:0 auto 20px;font-size:22px}"
                + "h1{text-align:center;font-size:22px;font-weight:700;color:#1E1B4B;margin-bottom:4px}"
                + ".sub{text-align:center;font-size:13px;color:#9CA3AF;margin-bottom:28px}"
                + "label{font-size:11px;font-weight:600;color:#6B7280;display:block;margin-bottom:6px;letter-spacing:1px;text-transform:uppercase}"
                + "input{width:100%;padding:12px 16px;border:1.5px solid #EDE9FE;border-radius:12px;font-size:14px;color:#1E1B4B;outline:none;font-family:inherit;background:#F8F7FF;margin-bottom:16px;transition:0.2s}"
                + "input:focus{border-color:#7C3AED;background:#fff}"
                + "button{width:100%;background:linear-gradient(135deg,#6D28D9,#7C3AED);color:#fff;border:none;padding:14px;border-radius:12px;font-size:14px;font-weight:600;cursor:pointer;transition:0.2s;margin-top:4px}"
                + "button:hover{opacity:0.9;transform:translateY(-1px)}"
                + "</style></head><body>"
                + "<div class='card'>"
                + "<div class='logo'>📄</div>"
                + "<h1>Studio PDF CORBA</h1>"
                + "<p class='sub'>Connectez-vous pour accéder à l'application</p>"
                + errorMsg
                + "<form method='POST' action='/login'>"
                + "<label>Nom d'utilisateur</label>"
                + "<input type='text' name='username' placeholder='Votre nom d\\'utilisateur' required autofocus>"
                + "<label>Mot de passe</label>"
                + "<input type='password' name='password' placeholder='••••••••' required>"
                + "<button type='submit'>Se connecter →</button>"
                + "</form>"
                + "<p style='text-align:center;margin-top:20px;font-size:13px;color:#9CA3AF'>"
                + "Pas encore de compte ? "
                + "<a href='/register' style='color:#7C3AED;font-weight:600;text-decoration:none'>Créer un compte</a>"
                + "</p>"
                + "</div></body></html>";
        }
    }

    // ══════════════════════════════════════════════════════════
    //  LOGOUT
    // ══════════════════════════════════════════════════════════
    static class LogoutHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String sid = getSessionId(t);
            if (sid != null) SESSIONS.remove(sid);
            t.getResponseHeaders().set("Set-Cookie", "session=; Path=/; Max-Age=0");
            redirect(t, "/login");
        }
    }

    // ══════════════════════════════════════════════════════════
    //  INSCRIPTION (Enregistrement persistant PostgreSQL)
    // ══════════════════════════════════════════════════════════
    static class RegisterHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String method = t.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                if (isLoggedIn(t)) { redirect(t, "/"); return; }
                String msg = t.getRequestURI().getQuery() != null && t.getRequestURI().getQuery().contains("exists")
                    ? "<div style='background:#FEE2E2;border:1px solid #FCA5A5;color:#991B1B;padding:12px 16px;border-radius:10px;font-size:13px;margin-bottom:16px'>Ce nom d'utilisateur existe déjà.</div>"
                    : "";
                sendHtml(t, registerPage(msg));
            } else if ("POST".equalsIgnoreCase(method)) {
                try {
                    byte[] body = readAllBytes(t.getRequestBody());
                    Map<String,String> params; 
                    try { params = parseFormBody(new String(body, "UTF-8")); } catch (Exception ex) { redirect(t, "/register"); return; }
                    String username = params.getOrDefault("username", "").trim();
                    String password = params.getOrDefault("password", "");
                    String confirm  = params.getOrDefault("confirm",  "");
                    
                    if (username.isEmpty() || password.isEmpty() || !password.equals(confirm)) {
                        redirect(t, "/register?error=1"); return;
                    }

                    try (Connection conn = getDbConnection()) {
                        // Vérifier si l'utilisateur existe déjà
                        String checkSQL = "SELECT username FROM users WHERE username = ?";
                        try (PreparedStatement checkStmt = conn.prepareStatement(checkSQL)) {
                            checkStmt.setString(1, username);
                            try (ResultSet rs = checkStmt.executeQuery()) {
                                if (rs.next()) {
                                    redirect(t, "/register?exists=1"); return;
                                }
                            }
                        }

                        // Insertion du nouvel utilisateur haché avec BCrypt
                        String insertSQL = "INSERT INTO users (username, password, role) VALUES (?, ?, ?)";
                        try (PreparedStatement insertStmt = conn.prepareStatement(insertSQL)) {
                            insertStmt.setString(1, username);
                            insertStmt.setString(2, BCrypt.hashpw(password, BCrypt.gensalt()));
                            insertStmt.setString(3, "user");
                            insertStmt.executeUpdate();
                        }
                    }

                    String sid = UUID.randomUUID().toString();
                    SESSIONS.put(sid, "user:" + username);
                    t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                    redirect(t, "/");
                } catch (Exception e) { sendError(t, e.getMessage()); }
            } else {
                t.sendResponseHeaders(405, -1);
                t.getResponseBody().close();
            }
        }

        static String registerPage(String errorMsg) {
            return "<!DOCTYPE html><html lang='fr'><head>"
                + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Inscription - Studio PDF CORBA</title>"
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap' rel='stylesheet'>"
                + "<style>"
                + "*{box-sizing:border-box;margin:0;padding:0;font-family:'Inter',sans-serif}"
                + "body{background:linear-gradient(135deg,#4F1D96 0%,#6D28D9 50%,#8B5CF6 100%);min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}"
                + ".card{background:#fff;border-radius:24px;padding:40px;width:100%;max-width:400px;box-shadow:0 40px 80px rgba(79,29,150,0.3)}"
                + ".logo{width:52px;height:52px;background:linear-gradient(135deg,#6D28D9,#8B5CF6);border-radius:16px;display:flex;align-items:center;justify-content:center;margin:0 auto 20px;font-size:22px}"
                + "h1{text-align:center;font-size:22px;font-weight:700;color:#1E1B4B;margin-bottom:4px}"
                + ".sub{text-align:center;font-size:13px;color:#9CA3AF;margin-bottom:28px}"
                + "label{font-size:11px;font-weight:600;color:#6B7280;display:block;margin-bottom:6px;letter-spacing:1px;text-transform:uppercase}"
                + "input{width:100%;padding:12px 16px;border:1.5px solid #EDE9FE;border-radius:12px;font-size:14px;color:#1E1B4B;outline:none;font-family:inherit;background:#F8F7FF;margin-bottom:16px;transition:0.2s}"
                + "input:focus{border-color:#7C3AED;background:#fff}"
                + "button{width:100%;background:linear-gradient(135deg,#6D28D9,#7C3AED);color:#fff;border:none;padding:14px;border-radius:12px;font-size:14px;font-weight:600;cursor:pointer;transition:0.2s;margin-top:4px}"
                + "button:hover{opacity:0.9;transform:translateY(-1px)}"
                + "</style></head><body>"
                + "<div class='card'>"
                + "<div class='logo'>✨</div>"
                + "<h1>Créer un compte</h1>"
                + "<p class='sub'>Rejoignez Studio PDF CORBA</p>"
                + errorMsg
                + "<form method='POST' action='/register'>"
                + "<label>Nom d'utilisateur</label>"
                + "<input type='text' name='username' placeholder='Choisissez un identifiant' required autofocus>"
                + "<label>Mot de passe</label>"
                + "<input type='password' name='password' placeholder='Mot de passe' required>"
                + "<label>Confirmer le mot de passe</label>"
                + "<input type='password' name='confirm' placeholder='Confirmer' required>"
                + "<button type='submit'>Créer mon compte →</button>"
                + "</form>"
                + "<p style='text-align:center;margin-top:20px;font-size:13px;color:#9CA3AF'>"
                + "Déjà un compte ? "
                + "<a href='/login' style='color:#7C3AED;font-weight:600;text-decoration:none'>Se connecter</a>"
                + "</p>"
                + "</div></body></html>";
        }
    }

    // ══════════════════════════════════════════════════════════
    //  CONSTRUCTION DU TABLEAU UTILISATEURS DEPUIS POSTGRESQL
    // ══════════════════════════════════════════════════════════
    static String buildUserRows(int[] countOut) {
        Set<String> connected = new java.util.HashSet<>();
        for (String val : SESSIONS.values()) {
            if (val.contains(":")) connected.add(val.split(":", 2)[1]);
        }
        StringBuilder sb = new StringBuilder();
        int totalUsers = 0;
        
        String selectSQL = "SELECT username, role FROM users ORDER BY role ASC, username ASC";
        try (Connection conn = getDbConnection(); 
             Statement stmt = conn.createStatement(); 
             ResultSet rs = stmt.executeQuery(selectSQL)) {
            
            while (rs.next()) {
                totalUsers++;
                String uname = rs.getString("username");
                String role  = rs.getString("role");
                boolean isAdmin = "admin".equals(role);
                boolean isConnected = connected.contains(uname);
                
                String roleCell = isAdmin
                    ? "<span class='role-badge role-admin'>Administrateur</span>"
                    : "<span class='role-badge role-user'>Utilisateur</span>";
                String statusCell = isConnected
                    ? "<span style='color:#059669;font-weight:600;font-size:12px'>&#9679; Connecté</span>"
                    : "<span style='color:#9CA3AF;font-weight:500;font-size:12px'>&#9675; Hors ligne</span>";
                
                sb.append("<tr>")
                  .append("<td><b>").append(escapeHtml(uname)).append("</b></td>")
                  .append("<td>").append(roleCell).append("</td>")
                  .append("<td>").append(statusCell).append("</td>")
                  .append("</tr>");
            }
        } catch (Exception e) {
            e.printStackTrace();
            sb.append("<tr><td colspan='3' style='color:red;'>Erreur lors de la récupération des données.</td></tr>");
        }
        countOut[0] = totalUsers;
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════
    //  PAGE ADMINISTRATEUR (/admin)
    // ══════════════════════════════════════════════════════════
    static class AdminHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            if (!"admin".equals(getRole(t))) { redirect(t, "/"); return; }

            int[] totalUsersArr = new int[]{0};
            String rows = buildUserRows(totalUsersArr);

            String html = "<!DOCTYPE html><html lang='fr'><head>"
                + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Studio PDF – Administration</title>"
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap' rel='stylesheet'>"
                + CSS_BASE
                + "<style>"
                + ".admin-badge{display:inline-flex;align-items:center;gap:6px;background:rgba(251,191,36,0.2);border:1px solid rgba(251,191,36,0.4);color:#FDE68A;padding:5px 12px;border-radius:14px;font-size:11px;font-weight:600;margin-bottom:8px}"
                + ".section-card{background:#fff;border-radius:16px;padding:24px;margin-bottom:20px}"
                + ".section-card h2{font-size:15px;font-weight:600;color:#1E1B4B;margin-bottom:4px}"
                + ".section-card .sub{font-size:12px;color:#9CA3AF;margin-bottom:18px}"
                + ".admin-table{width:100%;border-collapse:collapse;font-size:13px}"
                + ".admin-table th{text-align:left;font-size:10px;font-weight:600;letter-spacing:1.5px;text-transform:uppercase;color:#9CA3AF;padding:8px 12px;border-bottom:2px solid #F5F3FF}"
                + ".admin-table td{padding:12px;border-bottom:1px solid #F5F3FF;color:#1E1B4B;vertical-align:middle}"
                + ".admin-table tr:last-child td{border-bottom:none}"
                + ".role-badge{display:inline-block;padding:3px 10px;border-radius:10px;font-size:10px;font-weight:600}"
                + ".role-admin{background:#FEF3C7;color:#92400E}"
                + ".role-user{background:#DBEAFE;color:#1E40AF}"
                + ".stat-row{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:24px}"
                + "</style>"
                + "</head><body>"

                + "<div class='topbar' style='background:linear-gradient(135deg,#1E1B4B 0%,#312E81 40%,#4338CA 70%,#6366F1 100%)'>"
                + "<div class='topbar-row'>"
                + "<div>"
                + "<div class='admin-badge'>👑 ADMINISTRATEUR</div>"
                + "<h1>Tableau de bord Admin</h1>"
                + "<p>Studio PDF CORBA – Panneau d'administration</p>"
                + "</div>"
                + navbarAdmin("dash")
                + "</div></div>"

                + "<div class='main'>"

                + "<div class='stat-row'>"
                + "<div class='stat st1'><div class='stat-l'>PDFs créés</div><div class='stat-n'>" + nbCrees + "</div><div class='stat-s'>Session actuelle</div></div>"
                + "<div class='stat st2'><div class='stat-l'>Extractions</div><div class='stat-n'>" + nbExtractions + "</div><div class='stat-s'>Session actuelle</div></div>"
                + "<div class='stat st3'><div class='stat-l'>Fusions</div><div class='stat-n'>" + nbFusions + "</div><div class='stat-s'>Session actuelle</div></div>"
                + "<div class='stat st4'><div class='stat-l'>Protégés</div><div class='stat-n'>" + nbProtections + "</div><div class='stat-s'>Session actuelle</div></div>"
                + "</div>"

                + "<div class='section-card'>"
                + "<h2>Gestion des utilisateurs</h2>"
                + "<p class='sub'>" + totalUsersArr[0] + " compte(s) enregistré(s) &mdash; " + SESSIONS.size() + " session(s) active(s)</p>"
                + "<table class='admin-table'>"
                + "<thead><tr><th>Nom d'utilisateur</th><th>Rôle</th><th>Statut</th></tr></thead>"
                + "<tbody>"
                + rows
                + "</tbody></table>"
                + "</div>"

                + "<div class='section-card'>"
                + "<h2>Tous les outils PDF</h2>"
                + "<p class='sub'>L'administrateur dispose de l'ensemble des fonctionnalités</p>"
                + "<div class='tools'>"
                + tc("linear-gradient(90deg,#7C3AED,#A78BFA)", "Extraire texte",   "Lisez le contenu texte",        "Analyse",       "background:#EDE9FE;color:#5B21B6", "m-extract")
                + tc("linear-gradient(90deg,#0EA5E9,#7DD3FC)", "En images",        "PDF vers PNG haute qualité",    "Conversion",    "background:#E0F2FE;color:#0369A1", "m-image")
                + tc("linear-gradient(90deg,#10B981,#6EE7B7)", "Protéger",         "Chiffrement mot de passe",      "Sécurité",      "background:#D1FAE5;color:#065F46", "m-protect")
                + tc("linear-gradient(90deg,#F59E0B,#FCD34D)", "Fusionner",        "Combiner plusieurs PDFs",       "Assemblage",    "background:#FEF3C7;color:#92400E", "m-merge")
                + tc("linear-gradient(90deg,#EC4899,#F9A8D4)", "Découper",         "Diviser en plusieurs parties",  "Édition",       "background:#FCE7F3;color:#9D174D", "m-split")
                + tc("linear-gradient(90deg,#EF4444,#FCA5A5)", "Supprimer pages",  "Retirer des pages précises",    "Admin",         "background:#FEE2E2;color:#991B1B", "m-delete")
                + tc("linear-gradient(90deg,#8B5CF6,#C4B5FD)", "Extraire pages",   "Sélectionner et exporter",     "Extraction",    "background:#EDE9FE;color:#5B21B6", "m-pages")
                + tc("linear-gradient(90deg,#14B8A6,#99F6E4)", "Compresser",       "Réduire la taille du fichier", "Optimisation",  "background:#CCFBF1;color:#0F766E", "m-compress")
                + tc("linear-gradient(90deg,#6366F1,#A5B4FC)", "Métadonnées",      "Lire infos du document",       "Info",          "background:#EEF2FF;color:#3730A3", "m-meta")
                + tc("linear-gradient(90deg,#D946EF,#F0ABFC)", "Modifier meta",    "Titre auteur sujet",           "Admin",         "background:#FDF4FF;color:#86198F", "m-metamod")
                + tc("linear-gradient(90deg,#0284C7,#7DD3FC)", "QR Code",          "Insérer un QR code",           "Enrichissement","background:#E0F2FE;color:#0C4A6E", "m-qrcode")
                + tc("linear-gradient(90deg,#059669,#6EE7B7)", "Signer",           "Signature numérique RSA",      "Sécurité",      "background:#D1FAE5;color:#065F46", "m-sign")
                + "</div>"
                + "</div>"

                + "<div class='section-card'>"
                + "<h2>Créer un PDF</h2>"
                + "<p class='sub'>Générez instantanément via le serveur CORBA</p>"
                + "<form action='/create' method='get'>"
                + "<div class='inp-row'>"
                + "<input class='inp' name='titre' placeholder='Titre...'>"
                + "<input class='inp' name='auteur' placeholder='Auteur...'>"
                + "</div>"
                + "<textarea class='inp' name='corps' placeholder='Contenu du document...'></textarea>"
                + "<button class='btn-gen' type='submit'>Générer le PDF</button>"
                + "</form>"
                + "</div>"

                + "</div>"
                + "<div class='footer'>Studio PDF CORBA – Interface Administrateur</div>"

                + modal("m-extract",  "Extraire le texte",     "Obtenez le contenu textuel",          "/extract",  uploadZone("fi-extract","doc",false))
                + modal("m-image",    "Convertir en images",   "PDF vers PNG haute qualité",           "/image",
                    uploadZone("fi-image","doc",false)
                    + "<label>Résolution</label>"
                    + "<select name='dpi'><option value='72'>72 DPI</option><option value='150' selected>150 DPI</option><option value='300'>300 DPI</option></select>")
                + modal("m-protect",  "Protéger le PDF",       "Sécurisez avec un mot de passe",       "/protect",
                    uploadZone("fi-protect","doc",false)
                    + "<label>Mot de passe</label><input type='password' name='mdp' placeholder='Mot de passe...'>")
                + modal("m-merge",    "Fusionner des PDFs",    "Combinez plusieurs fichiers",          "/merge",    uploadZone("fi-merge","docs",true))
                + modal("m-split",    "Découper le PDF",       "Divisez en plusieurs parties",         "/split",
                    uploadZone("fi-split","doc",false)
                    + "<label>Pages par partie</label><input type='number' name='nb' value='1' min='1'>")
                + modal("m-delete",   "Supprimer des pages",   "Retirez les pages indésirables",       "/delete",
                    uploadZone("fi-delete","doc",false)
                    + "<label>Pages à supprimer (ex: 1,3,5)</label><input type='text' name='pages' placeholder='1,2,3...'>")
                + modal("m-pages",    "Extraire des pages",    "Sélectionnez les pages à conserver",   "/pages",
                    uploadZone("fi-pages","doc",false)
                    + "<label>Pages à extraire (ex: 1,3,5)</label><input type='text' name='pages' placeholder='1,2,3...'>")
                + modal("m-compress", "Compresser le PDF",     "Réduire la taille du fichier",         "/compress", uploadZone("fi-compress","doc",false))
                + modal("m-meta",     "Lire les métadonnées",  "Afficher les informations du document","/meta",     uploadZone("fi-meta","doc",false))
                + modal("m-metamod",  "Modifier métadonnées",  "Titre, auteur et sujet",               "/metamod",
                    uploadZone("fi-metamod","doc",false)
                    + "<label>Titre</label><input type='text' name='titre' placeholder='Nouveau titre...'>"
                    + "<label>Auteur</label><input type='text' name='auteur' placeholder='Auteur...'>"
                    + "<label>Sujet</label><input type='text' name='sujet' placeholder='Sujet...'>")
                + modal("m-qrcode",   "Ajouter un QR Code",   "Insérez un QR code dans le PDF",       "/qrcode",
                    uploadZone("fi-qrcode","doc",false)
                    + "<label>Contenu du QR Code</label><input type='text' name='contenu' placeholder='https://...'>"
                    + "<label>Page (commence à 0)</label><input type='number' name='page' value='0' min='0'>"
                    + "<label>Position X</label><input type='number' name='x' value='400' min='0'>"
                    + "<label>Position Y</label><input type='number' name='y' value='50' min='0'>")
                + modal("m-sign",     "Signature numérique",   "Signez votre PDF avec RSA",            "/sign",
                    uploadZone("fi-sign","doc",false)
                    + "<label>Nom du signataire</label><input type='text' name='nom' placeholder='Votre nom...'>"
                    + "<label>Raison</label><input type='text' name='raison' placeholder='Ex: Approbation...'>"
                    + "<label>Lieu</label><input type='text' name='lieu' placeholder='Ex: Dakar...'>")

                + jsCommon() + "</body></html>";

            sendHtml(t, html);
        }
    }

    // ── OUTILS ET UTILS RESTANTS (IDENTIQUES) ──────────────────────────────────────────────────
    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            if ("admin".equals(getRole(t))) { redirect(t, "/admin"); return; }

            String html = "<!DOCTYPE html><html lang='fr'><head>"
                + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Studio PDF – Espace Utilisateur</title>"
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap' rel='stylesheet'>"
                + CSS_BASE + "</head><body>"
                + "<div class='topbar' style='background:linear-gradient(135deg,#065F46 0%,#047857 40%,#059669 70%,#10B981 100%)'>"
                + "<div class='topbar-row'>"
                + "<div><h1>📄 Mon espace PDF</h1><p>Traitez vos documents en toute simplicité</p></div>"
                + navbarUser("home")
                + "</div></div>"
                + "<div class='main'>"
                + "<div style='background:#fff;border-radius:16px;padding:20px 24px;margin-bottom:24px;display:flex;align-items:center;gap:16px;border-left:4px solid #059669'>"
                + "<div style='width:44px;height:44px;background:linear-gradient(135deg,#D1FAE5,#6EE7B7);border-radius:12px;display:flex;align-items:center;justify-content:center;font-size:20px'>👤</div>"
                + "<div><p style='font-size:14px;font-weight:600;color:#1E1B4B'>Bienvenue dans votre espace utilisateur</p>"
                + "<p style='font-size:12px;color:#9CA3AF'>Vous pouvez utiliser tous les outils PDF disponibles ci-dessous.</p></div>"
                + "</div>"
                + "<p class='sec-label'>Outils disponibles</p>"
                + "<div class='tools'>"
                + tc("linear-gradient(90deg,#7C3AED,#A78BFA)", "Extraire texte",   "Lisez le contenu texte",        "Analyse",       "background:#EDE9FE;color:#5B21B6", "m-extract")
                + tc("linear-gradient(90deg,#0EA5E9,#7DD3FC)", "En images",        "PDF vers PNG haute qualité",    "Conversion",    "background:#E0F2FE;color:#0369A1", "m-image")
                + tc("linear-gradient(90deg,#10B981,#6EE7B7)", "Protéger",         "Chiffrement mot de passe",      "Sécurité",      "background:#D1FAE5;color:#065F46", "m-protect")
                + tc("linear-gradient(90deg,#F59E0B,#FCD34D)", "Fusionner",        "Combiner plusieurs PDFs",       "Assemblage",    "background:#FEF3C7;color:#92400E", "m-merge")
                + tc("linear-gradient(90deg,#EC4899,#F9A8D4)", "Découper",         "Diviser en plusieurs parties",  "Édition",       "background:#FCE7F3;color:#9D174D", "m-split")
                + tc("linear-gradient(90deg,#EF4444,#FCA5A5)", "Supprimer pages",  "Retirer des pages précises",    "Édition",       "background:#FEE2E2;color:#991B1B", "m-delete")
                + tc("linear-gradient(90deg,#8B5CF6,#C4B5FD)", "Extraire pages",   "Sélectionner et exporter",     "Extraction",    "background:#EDE9FE;color:#5B21B6", "m-pages")
                + tc("linear-gradient(90deg,#14B8A6,#99F6E4)", "Compresser",       "Réduire la taille du fichier", "Optimisation",  "background:#CCFBF1;color:#0F766E", "m-compress")
                + tc("linear-gradient(90deg,#6366F1,#A5B4FC)", "Métadonnées",      "Lire infos du document",       "Info",          "background:#EEF2FF;color:#3730A3", "m-meta")
                + tc("linear-gradient(90deg,#D946EF,#F0ABFC)", "Modifier meta",    "Titre auteur sujet",           "Édition",       "background:#FDF4FF;color:#86198F", "m-metamod")
                + tc("linear-gradient(90deg,#0284C7,#7DD3FC)", "QR Code",          "Insérer un QR code",           "Enrichissement","background:#E0F2FE;color:#0C4A6E", "m-qrcode")
                + tc("linear-gradient(90deg,#059669,#6EE7B7)", "Signer",           "Signature numérique RSA",      "Sécurité",      "background:#D1FAE5;color:#065F46", "m-sign")
                + "</div>"
                + "<div class='bottom'>"
                + "<div class='create'><h2>Créer un PDF</h2>"
                + "<p class='sub'>Générez instantanément via le serveur CORBA</p>"
                + "<form action='/create' method='get'>"
                + "<div class='inp-row'>"
                + "<input class='inp' name='titre' placeholder='Titre...'>"
                + "<input class='inp' name='auteur' placeholder='Auteur...'>"
                + "</div>"
                + "<textarea class='inp' name='corps' placeholder='Contenu du document...'></textarea>"
                + "<button class='btn-gen' type='submit'>Générer le PDF</button>"
                + "</form></div>"
                + "<div class='activity'><h2>Guide rapide</h2>"
                + actItem("#EDE9FE","#5B21B6","TXT","Extraire texte","Uploadez un PDF et obtenez le texte")
                + actItem("#E0F2FE","#0369A1","IMG","Convertir","Choisissez 72/150/300 DPI selon besoin")
                + actItem("#D1FAE5","#065F46","FUS","Fusionner","Sélectionnez plusieurs PDFs à la fois")
                + actItem("#CCFBF1","#0F766E","ZIP","Compresser","Réduisez le poids de votre fichier")
                + "</div></div></div>"
                + "<div class='footer'>Studio PDF CORBA – Java 8 × PDFBox 2.0</div>"
                + modal("m-extract",  "Extraire le texte",     "Obtenez le contenu textuel",          "/extract",  uploadZone("fi-extract","doc",false))
                + modal("m-image",    "Convertir en images",   "PDF vers PNG haute qualité",           "/image",
                    uploadZone("fi-image","doc",false)
                    + "<label>Résolution</label>"
                    + "<select name='dpi'><option value='72'>72 DPI</option><option value='150' selected>150 DPI</option><option value='300'>300 DPI</option></select>")
                + modal("m-protect",  "Protéger le PDF",       "Sécurisez avec un mot de passe",       "/protect",
                    uploadZone("fi-protect","doc",false)
                    + "<label>Mot de passe</label><input type='password' name='mdp' placeholder='Mot de passe...'>")
                + modal("m-merge",    "Fusionner des PDFs",    "Combinez plusieurs fichiers",          "/merge",    uploadZone("fi-merge","docs",true))
                + modal("m-split",    "Découper le PDF",       "Divisez en plusieurs parties",         "/split",
                    uploadZone("fi-split","doc",false)
                    + "<label>Pages par partie</label><input type='number' name='nb' value='1' min='1'>")
                + modal("m-delete",   "Supprimer des pages",   "Retirez les pages indésirables",       "/delete",
                    uploadZone("fi-delete","doc",false)
                    + "<label>Pages à supprimer (ex: 1,3,5)</label><input type='text' name='pages' placeholder='1,2,3...'>")
                + modal("m-pages",    "Extraire des pages",    "Sélectionnez les pages à conserver",   "/pages",
                    uploadZone("fi-pages","doc",false)
                    + "<label>Pages à extraire (ex: 1,3,5)</label><input type='text' name='pages' placeholder='1,2,3...'>")
                + modal("m-compress", "Compresser le PDF",     "Réduire la taille du fichier",         "/compress", uploadZone("fi-compress","doc",false))
                + modal("m-meta",     "Lire les métadonnées",  "Afficher les informations du doc",     "/meta",     uploadZone("fi-meta","doc",false))
                + modal("m-metamod",  "Modifier métadonnées",  "Titre, auteur et sujet",               "/metamod",
                    uploadZone("fi-metamod","doc",false)
                    + "<label>Titre</label><input type='text' name='titre' placeholder='Nouveau titre...'>"
                    + "<label>Auteur</label><input type='text' name='auteur' placeholder='Auteur...'>"
                    + "<label>Sujet</label><input type='text' name='sujet' placeholder='Sujet...'>")
                + modal("m-qrcode",   "Ajouter un QR Code",   "Insérez un QR code dans le PDF",       "/qrcode",
                    uploadZone("fi-qrcode","doc",false)
                    + "<label>Contenu du QR Code</label><input type='text' name='contenu' placeholder='https://...'>"
                    + "<label>Page (commence à 0)</label><input type='number' name='page' value='0' min='0'>"
                    + "<label>Position X</label><input type='number' name='x' value='400' min='0'>"
                    + "<label>Position Y</label><input type='number' name='y' value='50' min='0'>")
                + modal("m-sign",     "Signature numérique",   "Signez votre PDF avec RSA",            "/sign",
                    uploadZone("fi-sign","doc",false)
                    + "<label>Nom du signataire</label><input type='text' name='nom' placeholder='Votre nom...'>"
                    + "<label>Raison</label><input type='text' name='raison' placeholder='Ex: Approbation...'>"
                    + "<label>Lieu</label><input type='text' name='lieu' placeholder='Ex: Dakar...'>")
                + jsCommon() + "</body></html>";

            sendHtml(t, html);
        }

        static String actItem(String bg, String color, String label, String title, String desc) {
            return "<div class='ai'>"
                + "<div class='ai-ico' style='background:" + bg + ";color:" + color + "'>" + label + "</div>"
                + "<div class='ai-info'><b>" + title + "</b><small>" + desc + "</small></div>"
                + "</div>";
        }
    }

    static String tc(String grad, String title, String desc, String tag, String tagStyle, String modalId) {
        return "<div class='tc' onclick='openM(\"" + modalId + "\")'>"
            + "<div class='tc-bar' style='background:" + grad + "'></div>"
            + "<h3>" + title + "</h3><p>" + desc + "</p>"
            + "<span class='tc-tag' style='" + tagStyle + "'>" + tag + "</span>"
            + "</div>";
    }

    static String uploadZone(String id, String name, boolean multi) {
        String mult = multi ? " multiple" : "";
        return "<div class='upload-zone' onclick='document.getElementById(\"" + id + "\").click()'>"
            + "<p>Choisir un PDF</p><small id='lbl-" + id + "'>Cliquer pour parcourir</small></div>"
            + "<input type='file' id='" + id + "' name='" + name + "' accept='.pdf'" + mult
            + " style='display:none' onchange='showName(\"lbl-" + id + "\",this)'>";
    }

    static String modal(String id, String title, String sub, String action, String content) {
        return "<div class='overlay' id='" + id + "'><div class='modal'>"
            + "<h2>" + title + "</h2><p class='msub'>" + sub + "</p>"
            + "<form method='post' enctype='multipart/form-data' action='" + action + "'>"
            + content
            + "<div class='btn-row'>"
            + "<button class='btn-cancel' type='button' onclick='closeM(\"" + id + "\")'>Annuler</button>"
            + "<button class='btn-ok'>Confirmer</button>"
            + "</div></form></div></div>";
    }

    static String navbarUser(String active) {
        return "<div class='nav-links'>"
            + "<span class='badge'><span class='dot'></span>&nbsp;Connecté</span>"
            + (!"home".equals(active) ? "<a class='nav-link' href='/'>Accueil</a>" : "")
            + "<a class='nav-link logout' href='/logout'>Déconnexion</a>"
            + "</div>";
    }

    static String navbarAdmin(String active) {
        return "<div class='nav-links'>"
            + "<span class='badge'><span class='dot'></span>&nbsp;Admin</span>"
            + "<a class='nav-link" + ("dash".equals(active) ? " active" : "") + "' href='/admin'>Tableau de bord</a>"
            + "<a class='nav-link logout' href='/logout'>Déconnexion</a>"
            + "</div>";
    }

    static String jsCommon() {
        return "<script>"
            + "function openM(id){document.getElementById(id).classList.add('active')}"
            + "function closeM(id){document.getElementById(id).classList.remove('active')}"
            + "function showName(id,input){document.getElementById(id).textContent=input.files.length>1?input.files.length+' fichiers':input.files[0]?.name||'Aucun'}"
            + "document.querySelectorAll('.overlay').forEach(function(o){o.addEventListener('click',function(e){if(e.target===this)this.classList.remove('active')})})"
            + "</script>";
    }

    static class GenerateHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                Map<String,String> p = parseQuery(t.getRequestURI().getQuery());
                byte[] pdf = pdfRef.creerPDF(p.getOrDefault("titre","Sans titre"), p.getOrDefault("corps",""));
                nbCrees++;
                sendPdf(t, pdf, "document.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class ExtractHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                byte[] pdf = parseMultipart(t).files.values().iterator().next();
                String texte = pdfRef.extraireTexte(pdf);
                nbExtractions++;
                sendHtml(t, resultPage("Texte extrait", "#5B21B6", "#EDE9FE",
                    "<pre style='white-space:pre-wrap;color:#1E1B4B;font-size:13px;line-height:1.8'>" + escapeHtml(texte) + "</pre>"));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class ToImageHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                int dpi = Integer.parseInt(mp.fields.getOrDefault("dpi","150"));
                byte[][] images = pdfRef.convertirEnImages(mp.files.get("doc"), dpi);
                StringBuilder sb = new StringBuilder();
                for (int i=0; i<images.length; i++) {
                    String b64 = Base64.getEncoder().encodeToString(images[i]);
                    sb.append("<div style='margin-bottom:16px;border-radius:12px;overflow:hidden;border:1px solid #EDE9FE;box-shadow:0 4px 16px rgba(124,58,237,0.08)'>")
                      .append("<p style='background:#F5F3FF;padding:8px 14px;font-size:11px;color:#7C3AED;font-weight:500;letter-spacing:1px;text-transform:uppercase'>Page ").append(i+1).append("</p>")
                      .append("<img src='data:image/png;base64,").append(b64).append("' style='width:100%;display:block'>")
                      .append("</div>");
                }
                sendHtml(t, resultPage(images.length + " page(s) convertie(s)", "#0369A1", "#E0F2FE", sb.toString()));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class ProtectHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                byte[] result = pdfRef.ajouterMotDePasse(mp.files.get("doc"), mp.fields.getOrDefault("mdp","1234"));
                nbProtections++;
                sendPdf(t, result, "protected.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class MergeHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                List<byte[]> docs = mp.fileList.get("docs");
                if (docs==null || docs.size()<2) throw new Exception("Sélectionnez au moins 2 PDFs");
                byte[] result = pdfRef.fusionnerPDFs(docs.toArray(new byte[0][]));
                nbFusions++;
                sendPdf(t, result, "fusion.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class SplitHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                int nb = Integer.parseInt(mp.fields.getOrDefault("nb","1"));
                byte[][] parts = pdfRef.decouperPDF(mp.files.get("doc"), nb);
                StringBuilder sb = new StringBuilder();
                for (int i=0; i<parts.length; i++) {
                    String b64 = Base64.getEncoder().encodeToString(parts[i]);
                    sb.append("<div style='display:flex;align-items:center;gap:14px;padding:14px 18px;background:#fff;border:1.5px solid #EDE9FE;border-radius:12px;margin-bottom:10px'>")
                      .append("<div style='width:36px;height:36px;border-radius:9px;background:#EDE9FE;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:700;color:#5B21B6'>P").append(i+1).append("</div>")
                      .append("<span style='flex:1;font-size:13px;font-weight:500;color:#1E1B4B'>Partie ").append(i+1).append(" &mdash; ").append(parts[i].length/1024).append(" Ko</span>")
                      .append("<a href='data:application/pdf;base64,").append(b64).append("' download='partie_").append(i+1).append(".pdf' style='background:linear-gradient(135deg,#6D28D9,#7C3AED);color:#fff;padding:7px 16px;border-radius:8px;text-decoration:none;font-size:12px;font-weight:600'>Télécharger</a>")
                      .append("</div>");
                }
                sendHtml(t, resultPage("PDF découpé", "#9D174D", "#FCE7F3", sb.toString()));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class DeleteHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                int[] pages = parsePages(mp.fields.getOrDefault("pages","1"));
                sendPdf(t, pdfRef.supprimerPages(mp.files.get("doc"), pages), "sans_pages.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class ExtractPagesHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                int[] pages = parsePages(mp.fields.getOrDefault("pages","1"));
                sendPdf(t, pdfRef.extrairePages(mp.files.get("doc"), pages), "pages_extraites.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class CompressHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                byte[] pdf = parseMultipart(t).files.values().iterator().next();
                byte[] result = pdfRef.compresserPDF(pdf);
                long gain = pdf.length - result.length;
                sendHtml(t, resultPage("PDF compressé", "#0F766E", "#CCFBF1",
                    "<div style='background:#fff;border:1.5px solid #CCFBF1;border-radius:14px;padding:24px'>"
                    + "<p style='font-size:11px;font-weight:600;letter-spacing:2px;color:#0F766E;text-transform:uppercase;margin-bottom:16px'>Résultat compression</p>"
                    + "<div style='display:flex;gap:16px;margin-bottom:20px'>"
                    + statBox("Taille originale", (pdf.length/1024) + " Ko", "#EDE9FE", "#5B21B6")
                    + statBox("Taille compressé", (result.length/1024) + " Ko", "#D1FAE5", "#065F46")
                    + statBox("Gain", (gain/1024) + " Ko", "#FEF3C7", "#92400E")
                    + "</div>"
                    + "<a href='data:application/pdf;base64," + Base64.getEncoder().encodeToString(result)
                    + "' download='compresse.pdf' style='background:linear-gradient(135deg,#059669,#10B981);color:#fff;padding:10px 24px;border-radius:10px;text-decoration:none;font-size:13px;font-weight:600'>Télécharger le PDF compressé</a>"
                    + "</div>"));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class MetaReadHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                byte[] pdf = parseMultipart(t).files.values().iterator().next();
                String meta = pdfRef.lireMetadonnees(pdf);
                sendHtml(t, resultPage("Métadonnées", "#3730A3", "#EEF2FF",
                    "<div style='background:#fff;border:1.5px solid #C7D2FE;border-radius:14px;padding:24px'>"
                    + "<p style='font-size:11px;font-weight:600;letter-spacing:2px;color:#3730A3;text-transform:uppercase;margin-bottom:16px'>Informations du document</p>"
                    + "<pre style='white-space:pre-wrap;color:#1E1B4B;font-size:13px;line-height:2;font-family:Inter,sans-serif'>" + escapeHtml(meta) + "</pre>"
                    + "</div>"));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class MetaModHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                byte[] result = pdfRef.modifierMetadonnees(
                    mp.files.get("doc"),
                    mp.fields.getOrDefault("titre",""),
                    mp.fields.getOrDefault("auteur",""),
                    mp.fields.getOrDefault("sujet",""));
                sendPdf(t, result, "modifie.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class QRCodeHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                String contenu = mp.fields.getOrDefault("contenu","https://corba.pdf");
                int page = Integer.parseInt(mp.fields.getOrDefault("page","0"));
                int x    = Integer.parseInt(mp.fields.getOrDefault("x","400"));
                int y    = Integer.parseInt(mp.fields.getOrDefault("y","50"));
                byte[] result = pdfRef.ajouterQRCode(mp.files.get("doc"), contenu, page, x, y);
                sendPdf(t, result, "avec_qrcode.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class SignHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                byte[] result = pdfRef.signerPDF(
                    mp.files.get("doc"),
                    mp.fields.getOrDefault("nom","Signataire"),
                    mp.fields.getOrDefault("raison","Approbation"),
                    mp.fields.getOrDefault("lieu","Dakar"));
                sendPdf(t, result, "signe.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class MultipartData {
        Map<String,byte[]> files = new HashMap<>();
        Map<String,List<byte[]>> fileList = new HashMap<>();
        Map<String,String> fields = new HashMap<>();
    }

    static MultipartData parseMultipart(HttpExchange t) throws Exception {
        MultipartData mp = new MultipartData();
        String ct = t.getRequestHeaders().getFirst("Content-Type");
        String boundary = "--" + ct.split("boundary=")[1].trim();
        byte[] body = readAllBytes(t.getRequestBody());
        String raw = new String(body, "ISO-8859-1");
        String[] parts = raw.split(java.util.regex.Pattern.quote(boundary));
        for (String part : parts) {
            if (part.trim().isEmpty() || part.equals("--\r\n") || part.equals("--")) continue;
            int hEnd = part.indexOf("\r\n\r\n");
            if (hEnd < 0) continue;
            String headers = part.substring(0, hEnd);
            String dataRaw = part.substring(hEnd + 4);
            if (dataRaw.endsWith("\r\n")) dataRaw = dataRaw.substring(0, dataRaw.length() - 2);
            if (headers.contains("filename=")) {
                String name = extractHeaderValue(headers, "name");
                byte[] data = dataRaw.getBytes("ISO-8859-1");
                mp.files.put(name, data);
                mp.fileList.computeIfAbsent(name, k -> new ArrayList<>()).add(data);
            } else if (headers.contains("name=")) {
                mp.fields.put(extractHeaderValue(headers, "name"), dataRaw.trim());
            }
        }
        return mp;
    }

    static String extractHeaderValue(String headers, String key) {
        for (String line : headers.split("\r\n")) {
            if (line.contains(key + "=\"")) {
                int s = line.indexOf(key + "=\"") + key.length() + 2;
                int e = line.indexOf("\"", s);
                if (e > s) return line.substring(s, e);
            }
        }
        return "";
    }

    static int[] parsePages(String s) {
        String[] parts = s.split(",");
        int[] pages = new int[parts.length];
        for (int i=0; i<parts.length; i++) pages[i] = Integer.parseInt(parts[i].trim()) - 1;
        return pages;
    }

    static Map<String,String> parseQuery(String q) throws Exception {
        Map<String,String> m = new HashMap<>();
        if (q==null) return m;
        for (String s : q.split("&")) {
            String[] kv = s.split("=",2);
            if (kv.length>1) m.put(kv[0], URLDecoder.decode(kv[1],"UTF-8"));
        }
        return m;
    }

    static Map<String,String> parseFormBody(String body) throws Exception {
        Map<String,String> m = new HashMap<>();
        if (body==null || body.isEmpty()) return m;
        for (String s : body.split("&")) {
            String[] kv = s.split("=",2);
            if (kv.length==2) m.put(URLDecoder.decode(kv[0],"UTF-8"), URLDecoder.decode(kv[1],"UTF-8"));
        }
        return m;
    }

    static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n=is.read(buf))!=-1) bos.write(buf,0,n);
        return bos.toByteArray();
    }

    static void sendPdf(HttpExchange t, byte[] data, String name) throws IOException {
        t.getResponseHeaders().set("Content-Type","application/pdf");
        t.getResponseHeaders().set("Content-Disposition","attachment; filename="+name);
        t.sendResponseHeaders(200, data.length);
        t.getResponseBody().write(data);
        t.getResponseBody().close();
    }

    static void sendHtml(HttpExchange t, String html) throws IOException {
        byte[] b = html.getBytes("UTF-8");
        t.getResponseHeaders().set("Content-Type","text/html; charset=UTF-8");
        t.sendResponseHeaders(200, b.length);
        t.getResponseBody().write(b);
        t.getResponseBody().close();
    }

    static String resultPage(String titre, String color, String bg, String content) {
        return "<!DOCTYPE html><html lang='fr'><head><meta charset='UTF-8'>"
            + "<title>" + titre + " - Studio PDF</title>"
            + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap' rel='stylesheet'>"
            + "<style>*{box-sizing:border-box;margin:0;padding:0;font-family:'Inter',sans-serif}"
            + "body{background:#F5F3FF;min-height:100vh}"
            + ".topbar{background:linear-gradient(135deg,#4F1D96,#7C3AED);padding:18px 28px;display:flex;align-items:center;gap:16px}"
            + ".topbar a{color:rgba(255,255,255,0.8);text-decoration:none;font-size:13px;font-weight:500}"
            + ".topbar a:hover{color:white}"
            + ".topbar h2{color:white;font-size:14px;font-weight:600}"
            + ".content{max-width:860px;margin:0 auto;padding:28px 24px}"
            + ".result-header{background:" + bg + ";border-radius:12px;padding:14px 18px;margin-bottom:20px}"
            + ".result-header span{font-size:11px;font-weight:600;color:" + color + ";text-transform:uppercase;letter-spacing:1.5px}"
            + "</style></head><body>"
            + "<div class='topbar'><a href='javascript:history.back()'>&#8592; Retour</a>&nbsp;&nbsp;<h2>" + titre + "</h2></div>"
            + "<div class='content'>"
            + "<div class='result-header'><span>Résultat &mdash; " + titre + "</span></div>"
            + content + "</div></body></html>";
    }

    static void sendError(HttpExchange t, String msg) throws IOException {
        sendHtml(t, resultPage("Erreur","#991B1B","#FEE2E2",
            "<div style='background:#fff;border:1.5px solid #FCA5A5;border-radius:12px;padding:24px;text-align:center'>"
            + "<h3 style='color:#DC2626;margin-bottom:8px;font-size:15px'>Une erreur est survenue</h3>"
            + "<p style='color:#9CA3AF;font-size:13px'>" + escapeHtml(msg) + "</p>"
            + "</div>"));
    }

    static String escapeHtml(String s) {
        if (s==null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }
}
