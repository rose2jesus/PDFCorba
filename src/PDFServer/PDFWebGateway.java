package PDFServer;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import PDFApp.*;
import org.omg.CosNaming.*;
import org.omg.CORBA.*;
import org.mindrot.jbcrypt.BCrypt;

public class PDFWebGateway {

    private static PDFService pdfRef;

    // ── Connexion BDD avec reconnexion automatique ───────────
    private static volatile Connection DB;
    private static volatile String JDBC_URL;

    private static final String DB_URL = System.getenv("DATABASE_URL") != null
        ? System.getenv("DATABASE_URL")
        : "postgresql://pdfcorba_db_user:SqdCGjHJNDteWpuvPwh3R9F3EtrmoDlz@dpg-d81qlou7r5hc73bsoku0-a/pdfcorba_db";

    static void initDB() throws Exception {
        JDBC_URL = buildJdbcUrl(DB_URL);
        Class.forName("org.postgresql.Driver");
        DB = DriverManager.getConnection(JDBC_URL);
        DB.setAutoCommit(true);
        createSchema();
        System.out.println("[DB] Connexion établie");
    }

    private static String buildJdbcUrl(String url) {
        if (url.startsWith("postgresql://") || url.startsWith("postgres://")) {
            // postgresql://user:pass@host/db  →  jdbc:postgresql://host/db?user=...&password=...
            url = url.replaceFirst("^postgres(ql)?://", "");
            String[] atParts = url.split("@", 2);
            if (atParts.length == 2) {
                String[] creds = atParts[0].split(":", 2);
                String hostDb  = atParts[1];
                return "jdbc:postgresql://" + hostDb + "?user=" + creds[0]
                    + "&password=" + (creds.length > 1 ? creds[1] : "")
                    + "&sslmode=require&socketTimeout=30&connectTimeout=10";
            }
        }
        if (!url.startsWith("jdbc:")) return "jdbc:postgresql://" + url;
        return url;
    }

    /** Reconnexion transparente si la connexion est perdue */
    static synchronized Connection getDB() throws SQLException {
        try {
            if (DB != null && !DB.isClosed() && DB.isValid(2)) return DB;
        } catch (Exception ignored) {}
        System.out.println("[DB] Reconnexion...");
        try { DB = DriverManager.getConnection(JDBC_URL); DB.setAutoCommit(true); }
        catch (Exception e) { throw new SQLException("Impossible de se reconnecter à la BD : " + e.getMessage()); }
        return DB;
    }

    static void createSchema() throws Exception {
        Connection c = getDB();
        c.createStatement().execute(
            "CREATE TABLE IF NOT EXISTS users (" +
            "  id SERIAL PRIMARY KEY," +
            "  username VARCHAR(100) UNIQUE NOT NULL," +
            "  password_hash VARCHAR(255) NOT NULL," +
            "  role VARCHAR(20) NOT NULL DEFAULT 'user'," +
            "  created_at TIMESTAMP DEFAULT NOW()," +
            "  last_login TIMESTAMP" +
            ")"
        );
        c.createStatement().execute(
            "CREATE TABLE IF NOT EXISTS actions (" +
            "  id SERIAL PRIMARY KEY," +
            "  username VARCHAR(100)," +
            "  action VARCHAR(100)," +
            "  detail VARCHAR(500)," +
            "  created_at TIMESTAMP DEFAULT NOW()" +
            ")"
        );
        // Compte admin par défaut
        ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM users WHERE username='admin'");
        rs.next();
        if (rs.getInt(1) == 0) {
            PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users(username,password_hash,role) VALUES(?,?,'admin')");
            ps.setString(1, "admin");
            ps.setString(2, BCrypt.hashpw("admin123", BCrypt.gensalt()));
            ps.executeUpdate();
        }
    }

    // ── Gestion de sessions ──────────────────────────────────
    static class Session {
        String username, role;
        long createdAt;
        Session(String u, String r) { username = u; role = r; createdAt = System.currentTimeMillis(); }
        boolean expired() { return System.currentTimeMillis() - createdAt > 86400_000L; }
    }
    private static final Map<String, Session> SESSIONS       = new ConcurrentHashMap<>();
    /** Clé = IP, valeur = [compteur, timestamp_premiere_tentative] (long pour éviter l'overflow) */
    private static final Map<String, long[]>  LOGIN_ATTEMPTS = new ConcurrentHashMap<>();

    static String getSessionId(HttpExchange t) {
        String h = t.getRequestHeaders().getFirst("Cookie");
        if (h == null) return null;
        for (String c : h.split(";")) {
            c = c.trim();
            if (c.startsWith("sid=")) return c.substring(4);
        }
        return null;
    }
    static Session getSession(HttpExchange t) {
        String sid = getSessionId(t);
        if (sid == null) return null;
        Session s = SESSIONS.get(sid);
        if (s == null || s.expired()) { SESSIONS.remove(sid); return null; }
        return s;
    }
    static boolean isLoggedIn(HttpExchange t)  { return getSession(t) != null; }
    static String  getRole(HttpExchange t)      { Session s = getSession(t); return s == null ? null : s.role; }
    static String  getUsername(HttpExchange t)  { Session s = getSession(t); return s == null ? null : s.username; }

    static void redirect(HttpExchange t, String path) throws IOException {
        t.getResponseHeaders().set("Location", path);
        t.sendResponseHeaders(302, -1);
        t.getResponseBody().close();
    }
    static void logAction(String u, String action, String detail) {
        try {
            PreparedStatement ps = getDB().prepareStatement(
                "INSERT INTO actions(username,action,detail) VALUES(?,?,?)");
            ps.setString(1, u != null ? u : "?");
            ps.setString(2, action);
            ps.setString(3, detail != null ? detail : "");
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[LOG] Impossible d'enregistrer l'action : " + e.getMessage());
        }
    }

    // ── Démarrage ────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        try {
            initDB();
            ORB orb = ORB.init(args, null);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));
            System.out.println("[CORBA] Service PDF résolu");

            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 128);
            server.createContext("/login",              new LoginHandler());
            server.createContext("/logout",             new LogoutHandler());
            server.createContext("/register",           new RegisterHandler());
            server.createContext("/admin",              new AdminHandler());
            server.createContext("/admin/delete-user",  new AdminDeleteUserHandler());
            server.createContext("/admin/promote-user", new AdminPromoteUserHandler());
            server.createContext("/create",             new GenerateHandler());
            server.createContext("/extract",            new ExtractHandler());
            server.createContext("/image",              new ToImageHandler());
            server.createContext("/protect",            new ProtectHandler());
            server.createContext("/merge",              new MergeHandler());
            server.createContext("/split",              new SplitHandler());
            server.createContext("/delete",             new DeleteHandler());
            server.createContext("/pages",              new ExtractPagesHandler());
            server.createContext("/compress",           new CompressHandler());
            server.createContext("/meta",               new MetaReadHandler());
            server.createContext("/metamod",            new MetaModHandler());
            server.createContext("/qrcode",             new QRCodeHandler());
            server.createContext("/sign",               new SignHandler());
            // UIHandler en dernier (catch-all)
            server.createContext("/",                   new UIHandler());
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(20));
            System.out.println("[HTTP] Studio PDF CORBA → http://localhost:8080");
            server.start();
        } catch (Exception e) {
            System.err.println("[FATAL] " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ════════════════════════════════════════════════════════
    //  STYLES ET COMPOSANTS UI
    // ════════════════════════════════════════════════════════

    static final String CSS_BASE =
        "<style>"
        + "*{box-sizing:border-box;margin:0;padding:0;font-family:'Inter',sans-serif}"
        + "body{background:#F5F3FF;min-height:100vh}"
        + ".topbar{background:linear-gradient(135deg,#4F1D96 0%,#6D28D9 40%,#7C3AED 70%,#8B5CF6 100%);padding:28px 28px 80px;position:relative;overflow:hidden}"
        + ".topbar::before{content:'';position:absolute;top:-60px;right:-60px;width:220px;height:220px;background:rgba(255,255,255,0.06);border-radius:50%}"
        + ".topbar-row{display:flex;justify-content:space-between;align-items:flex-start;position:relative;z-index:1;flex-wrap:wrap;gap:12px}"
        + ".topbar h1{font-size:20px;font-weight:600;color:#fff;margin-bottom:4px}"
        + ".topbar p{font-size:13px;color:rgba(255,255,255,0.7)}"
        + ".badge{display:inline-flex;align-items:center;gap:6px;background:rgba(255,255,255,0.15);border:0.5px solid rgba(255,255,255,0.3);color:#fff;padding:6px 14px;border-radius:20px;font-size:11px;font-weight:500}"
        + ".dot{width:7px;height:7px;border-radius:50%;background:#4ADE80;animation:blink 1.5s infinite}"
        + "@keyframes blink{0%,100%{opacity:1}50%{opacity:0.3}}"
        + ".nav-links{display:flex;gap:8px;align-items:center;flex-wrap:wrap}"
        + ".nav-link{color:rgba(255,255,255,0.85);text-decoration:none;font-size:12px;font-weight:500;padding:6px 14px;border-radius:16px;background:rgba(255,255,255,0.1);border:0.5px solid rgba(255,255,255,0.2);transition:0.2s}"
        + ".nav-link:hover{background:rgba(255,255,255,0.22)}"
        + ".nav-link.logout{background:rgba(239,68,68,0.3);border-color:rgba(239,68,68,0.4)}"
        + ".main{padding:0 24px 32px;margin-top:-48px;position:relative;z-index:2}"
        + ".stats,.stat-row{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:28px}"
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
        + ".card-box,.section-card{background:#fff;border-radius:16px;padding:24px;margin-bottom:0}"
        + ".section-card{margin-bottom:20px}"
        + ".card-box h2,.section-card h2{font-size:15px;font-weight:600;color:#1E1B4B;margin-bottom:4px}"
        + ".card-box .sub,.section-card .sub{font-size:12px;color:#9CA3AF;margin-bottom:18px}"
        + ".inp{background:#F8F7FF;border:1.5px solid #EDE9FE;border-radius:10px;padding:10px 14px;font-size:13px;color:#1E1B4B;width:100%;font-family:inherit;margin-bottom:10px;outline:none}"
        + ".inp:focus{border-color:#7C3AED}"
        + ".inp-row{display:grid;grid-template-columns:1fr 1fr;gap:10px}"
        + "textarea.inp{resize:none;height:68px}"
        + ".btn-gen{width:100%;background:linear-gradient(135deg,#6D28D9,#7C3AED);color:#fff;border:none;padding:12px;border-radius:10px;font-size:13px;font-weight:600;cursor:pointer;margin-top:4px}"
        + ".btn-gen:hover{opacity:0.92}"
        + ".ai,.history-item{display:flex;align-items:flex-start;gap:12px;padding:10px 0;border-bottom:1px solid #F5F3FF}"
        + ".ai:last-child,.history-item:last-child{border-bottom:none;padding-bottom:0}"
        + ".ai-ico,.history-dot{width:34px;height:34px;border-radius:9px;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:700;flex-shrink:0}"
        + ".history-dot{width:8px;height:8px;border-radius:50%;background:#A78BFA;margin-top:5px;flex:none}"
        + ".ai-info,.history-info{flex:1}"
        + ".ai-info b,.history-info b{font-size:12px;font-weight:500;color:#1E1B4B;display:block}"
        + ".ai-info small,.history-info small{font-size:11px;color:#9CA3AF}"
        + ".overlay{display:none;position:fixed;inset:0;background:rgba(79,29,150,0.2);backdrop-filter:blur(8px);z-index:100;align-items:center;justify-content:center}"
        + ".overlay.active{display:flex}"
        + ".modal{background:#fff;border-radius:20px;padding:32px;width:90%;max-width:440px;box-shadow:0 40px 80px rgba(79,29,150,0.15);max-height:90vh;overflow-y:auto}"
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
        + ".alert{padding:12px 16px;border-radius:10px;font-size:13px;margin-bottom:16px}"
        + ".alert-error{background:#FEE2E2;border:1px solid #FCA5A5;color:#991B1B}"
        + ".alert-success{background:#D1FAE5;border:1px solid #6EE7B7;color:#065F46}"
        + ".alert-warn{background:#FEF3C7;border:1px solid #FDE68A;color:#92400E}"
        + ".admin-table{width:100%;border-collapse:collapse;font-size:13px}"
        + ".admin-table th{text-align:left;font-size:10px;font-weight:600;letter-spacing:1.5px;text-transform:uppercase;color:#9CA3AF;padding:8px 12px;border-bottom:2px solid #F5F3FF}"
        + ".admin-table td{padding:11px 12px;border-bottom:1px solid #F5F3FF;color:#1E1B4B;vertical-align:middle}"
        + ".admin-table tr:last-child td{border-bottom:none}"
        + ".role-badge{display:inline-block;padding:3px 10px;border-radius:10px;font-size:10px;font-weight:600}"
        + ".role-admin{background:#FEF3C7;color:#92400E}"
        + ".role-user{background:#DBEAFE;color:#1E40AF}"
        + ".btn-sm{padding:5px 12px;border-radius:8px;font-size:11px;font-weight:600;cursor:pointer;border:none;display:inline-block;text-decoration:none}"
        + ".btn-promote{background:#D1FAE5;color:#065F46}"
        + ".btn-del{background:#FEE2E2;color:#991B1B;margin-left:6px}"
        + ".admin-badge{display:inline-flex;align-items:center;gap:6px;background:rgba(251,191,36,0.2);border:1px solid rgba(251,191,36,0.4);color:#FDE68A;padding:5px 12px;border-radius:14px;font-size:11px;font-weight:600;margin-bottom:8px}"
        + ".progress-overlay{display:none;position:fixed;inset:0;background:rgba(79,29,150,0.4);backdrop-filter:blur(4px);z-index:200;align-items:center;justify-content:center;flex-direction:column;gap:16px}"
        + ".progress-overlay.active{display:flex}"
        + ".spinner{width:44px;height:44px;border:3px solid rgba(255,255,255,0.3);border-top-color:#fff;border-radius:50%;animation:spin 0.8s linear infinite}"
        + "@keyframes spin{to{transform:rotate(360deg)}}"
        + ".progress-label{color:#fff;font-size:14px;font-weight:500}"
        + "@media(max-width:768px){.stats,.stat-row{grid-template-columns:1fr 1fr}.tools{grid-template-columns:1fr 1fr}.bottom{grid-template-columns:1fr}.main{padding:0 14px 24px}.topbar{padding:20px 16px 70px}}"
        + "</style>";

    static String favicon() {
        return "<link rel='icon' href='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 100 100%22><rect width=%22100%22 height=%22100%22 rx=%2220%22 fill=%22%237C3AED%22/><text y=%22.9em%22 font-size=%2270%22 x=%2212%22>📄</text></svg>'>";
    }

    static String tc(String grad, String title, String desc, String tag, String tagStyle, String mid) {
        return "<div class='tc' onclick='openM(\"" + mid + "\")'>"
            + "<div class='tc-bar' style='background:" + grad + "'></div>"
            + "<h3>" + title + "</h3><p>" + desc + "</p>"
            + "<span class='tc-tag' style='" + tagStyle + "'>" + tag + "</span></div>";
    }
    static String uploadZone(String id, String name, boolean multi) {
        return "<div class='upload-zone' onclick='document.getElementById(\"" + id + "\").click()'>"
            + "<p>&#128196; Choisir un PDF</p><small id='lbl-" + id + "'>Cliquer pour parcourir</small></div>"
            + "<input type='file' id='" + id + "' name='" + name + "' accept='.pdf,application/pdf'" + (multi ? " multiple" : "")
            + " style='display:none' onchange='validateAndShow(\"lbl-" + id + "\",this)'>";
    }
    static String modal(String id, String title, String sub, String action, String content) {
        return "<div class='overlay' id='" + id + "'><div class='modal'>"
            + "<h2>" + title + "</h2><p class='msub'>" + sub + "</p>"
            + "<form method='post' enctype='multipart/form-data' action='" + action
            + "' onsubmit='return submitWithToken(this)'>"
            + content
            + "<div class='btn-row'>"
            + "<button class='btn-cancel' type='button' onclick='closeM(\"" + id + "\")'> Annuler</button>"
            + "<button class='btn-ok'>Confirmer</button>"
            + "</div></form></div></div>";
    }
    static String navbar(String username, String role, String active) {
        boolean isAdmin = "admin".equals(role);
        return "<div class='nav-links'>"
            + "<span class='badge'><span class='dot'></span>&nbsp;" + (isAdmin ? "👑 " : "👤 ") + escapeHtml(username) + "</span>"
            + (isAdmin ? "<a class='nav-link' href='/admin'>Admin</a>" : "")
            + "<a class='nav-link' href='/'>Studio</a>"
            + "<a class='nav-link logout' href='/logout'>Déconnexion</a>"
            + "</div>";
    }
    static String allTools() {
        return "<div class='tools'>"
            + tc("linear-gradient(90deg,#7C3AED,#A78BFA)", "Extraire texte",  "Lisez le contenu texte",              "Analyse",      "background:#EDE9FE;color:#5B21B6", "m-extract")
            + tc("linear-gradient(90deg,#0EA5E9,#7DD3FC)", "En images",       "PDF vers PNG haute qualité",           "Conversion",   "background:#E0F2FE;color:#0369A1", "m-image")
            + tc("linear-gradient(90deg,#10B981,#6EE7B7)", "Protéger",        "Chiffrement mot de passe",             "Sécurité",     "background:#D1FAE5;color:#065F46", "m-protect")
            + tc("linear-gradient(90deg,#F59E0B,#FCD34D)", "Fusionner",       "Combiner plusieurs PDFs",              "Assemblage",   "background:#FEF3C7;color:#92400E", "m-merge")
            + tc("linear-gradient(90deg,#EC4899,#F9A8D4)", "Découper",        "Diviser en plusieurs parties",         "Édition",      "background:#FCE7F3;color:#9D174D", "m-split")
            + tc("linear-gradient(90deg,#EF4444,#FCA5A5)", "Supprimer pages", "Retirer des pages précises",           "Édition",      "background:#FEE2E2;color:#991B1B", "m-delete")
            + tc("linear-gradient(90deg,#8B5CF6,#C4B5FD)", "Extraire pages",  "Sélectionner et exporter",            "Extraction",   "background:#EDE9FE;color:#5B21B6", "m-pages")
            + tc("linear-gradient(90deg,#14B8A6,#99F6E4)", "Compresser",      "Réduire la taille du fichier",        "Optimisation", "background:#CCFBF1;color:#0F766E", "m-compress")
            + tc("linear-gradient(90deg,#6366F1,#A5B4FC)", "Métadonnées",     "Lire infos du document",              "Info",         "background:#EEF2FF;color:#3730A3", "m-meta")
            + tc("linear-gradient(90deg,#D946EF,#F0ABFC)", "Modifier meta",   "Titre auteur sujet",                  "Édition",      "background:#FDF4FF;color:#86198F", "m-metamod")
            + tc("linear-gradient(90deg,#0284C7,#7DD3FC)", "QR Code",         "Insérer un QR code",                  "Enrichissement","background:#E0F2FE;color:#0C4A6E","m-qrcode")
            + tc("linear-gradient(90deg,#059669,#6EE7B7)", "Signer",          "Signature numérique RSA / PKCS#7",    "Sécurité",     "background:#D1FAE5;color:#065F46", "m-sign")
            + "</div>";
    }
    static String allModals() {
        return modal("m-extract", "Extraire le texte", "Obtenez le contenu textuel", "/extract",
                uploadZone("fi-extract", "doc", false))
            + modal("m-image", "Convertir en images", "PDF vers PNG haute qualité", "/image",
                uploadZone("fi-image", "doc", false)
                + "<label>Résolution</label><select name='dpi' class='inp' style='margin-bottom:0'>"
                + "<option value='72'>72 DPI</option><option value='150' selected>150 DPI</option><option value='300'>300 DPI</option></select>")
            + modal("m-protect", "Protéger le PDF", "Sécurisez avec un mot de passe", "/protect",
                uploadZone("fi-protect", "doc", false)
                + "<label>Mot de passe</label><input type='password' name='mdp' placeholder='Mot de passe...' required minlength='4'>")
            + modal("m-merge", "Fusionner des PDFs", "Combinez plusieurs fichiers", "/merge",
                uploadZone("fi-merge", "docs", true))
            + modal("m-split", "Découper le PDF", "Divisez en plusieurs parties", "/split",
                uploadZone("fi-split", "doc", false)
                + "<label>Pages par partie</label><input type='number' name='nb' value='1' min='1' required>")
            + modal("m-delete", "Supprimer des pages", "Retirez les pages indésirables", "/delete",
                "<div class='alert alert-warn'>&#9888; Action irréversible.</div>"
                + uploadZone("fi-delete", "doc", false)
                + "<label>Pages à supprimer (ex: 1,3,5)</label>"
                + "<input type='text' name='pages' placeholder='1,2,3...' required pattern='[0-9,\\s]+'>")
            + modal("m-pages", "Extraire des pages", "Sélectionnez les pages à conserver", "/pages",
                uploadZone("fi-pages", "doc", false)
                + "<label>Pages à extraire (ex: 1,3,5)</label>"
                + "<input type='text' name='pages' placeholder='1,2,3...' required pattern='[0-9,\\s]+'>")
            + modal("m-compress", "Compresser le PDF", "Réduire la taille du fichier", "/compress",
                uploadZone("fi-compress", "doc", false))
            + modal("m-meta", "Lire les métadonnées", "Afficher les informations du doc", "/meta",
                uploadZone("fi-meta", "doc", false))
            + modal("m-metamod", "Modifier métadonnées", "Titre, auteur et sujet", "/metamod",
                uploadZone("fi-metamod", "doc", false)
                + "<label>Titre</label><input type='text' name='titre' placeholder='Nouveau titre...'>"
                + "<label>Auteur</label><input type='text' name='auteur' placeholder='Auteur...'>"
                + "<label>Sujet</label><input type='text' name='sujet' placeholder='Sujet...'>")
            + modal("m-qrcode", "Ajouter un QR Code", "Insérez un QR code dans le PDF", "/qrcode",
                uploadZone("fi-qrcode", "doc", false)
                + "<label>Contenu du QR Code</label><input type='text' name='contenu' placeholder='https://...' required>"
                + "<label>Page (commence à 1)</label><input type='number' name='page' value='1' min='1'>"
                + "<label>Position X (points depuis la gauche)</label><input type='number' name='x' value='400' min='0'>"
                + "<label>Position Y (points depuis le bas)</label><input type='number' name='y' value='50' min='0'>")
            + modal("m-sign", "Signature numérique", "Signez votre PDF avec RSA/PKCS#7", "/sign",
                uploadZone("fi-sign", "doc", false)
                + "<label>Nom du signataire</label><input type='text' name='nom' placeholder='Votre nom...' required>"
                + "<label>Raison</label><input type='text' name='raison' placeholder='Ex: Approbation...'>"
                + "<label>Lieu</label><input type='text' name='lieu' placeholder='Ex: Dakar...'>");
    }

    static String jsCommon() {
        return "<div class='progress-overlay' id='progress-overlay'>"
            + "<div class='spinner'></div>"
            + "<span class='progress-label'>Traitement en cours...</span>"
            + "</div>"
            + "<script>"
            + "function getCookie(n){var m=document.cookie.match('(?:^|;)\\s*'+n+'=([^;]*)');return m?decodeURIComponent(m[1]):null;}"
            + "function deleteCookie(n){document.cookie=n+'=;Path=/;Max-Age=0';}"
            + "var _pollTimer=null;"
            + "function showProgress(token){"
            + "  document.getElementById('progress-overlay').classList.add('active');"
            + "  if(_pollTimer)clearInterval(_pollTimer);"
            + "  _pollTimer=setInterval(function(){"
            + "    if(getCookie('dl_done')===token){deleteCookie('dl_done');hideProgress();}"
            + "  },300);"
            + "  setTimeout(function(){hideProgress();},120000);"
            + "}"
            + "function hideProgress(){"
            + "  document.getElementById('progress-overlay').classList.remove('active');"
            + "  if(_pollTimer){clearInterval(_pollTimer);_pollTimer=null;}"
            + "}"
            + "function makeToken(){return Math.random().toString(36).slice(2)+Date.now();}"
            + "function openM(id){document.getElementById(id).classList.add('active')}"
            + "function closeM(id){document.getElementById(id).classList.remove('active')}"
            + "function submitWithToken(form){"
            + "  var tok=makeToken();"
            + "  var inp=form.querySelector('input[name=dl_token]');"
            + "  if(!inp){inp=document.createElement('input');inp.type='hidden';inp.name='dl_token';form.appendChild(inp);}"
            + "  inp.value=tok;"
            + "  showProgress(tok);"
            + "  return true;"
            + "}"
            + "function validateAndShow(id,input){"
            + "  for(var i=0;i<input.files.length;i++){"
            + "    var f=input.files[i];"
            + "    if(f.type!='application/pdf'&&!f.name.toLowerCase().endsWith('.pdf')){alert('Seuls les fichiers PDF sont acceptes.');input.value='';return;}"
            + "    if(f.size>50*1024*1024){alert('Taille max: 50 Mo.');input.value='';return;}"
            + "  }"
            + "  document.getElementById(id).textContent=input.files.length>1?input.files.length+' fichiers selectionnes':input.files[0]?input.files[0].name:'Aucun';"
            + "}"
            + "function confirm2(msg,url){if(confirm(msg))window.location.href=url;}"
            + "document.querySelectorAll('.overlay').forEach(function(o){"
            + "  o.addEventListener('click',function(e){if(e.target===this)this.classList.remove('active')})"
            + "});"
            + "window.addEventListener('pageshow',function(){hideProgress();});"
            + "</script>";
    }

    // ════════════════════════════════════════════════════════
    //  PAGES AUTH
    // ════════════════════════════════════════════════════════

    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("GET".equalsIgnoreCase(t.getRequestMethod())) {
                Session s = getSession(t);
                if (s != null) { redirect(t, "admin".equals(s.role) ? "/admin" : "/"); return; }
                String q = t.getRequestURI().getQuery();
                String msg = "";
                if (q != null && q.contains("error=locked"))
                    msg = "<div class='alert alert-error'>Trop de tentatives. Réessayez dans 5 minutes.</div>";
                else if (q != null && q.contains("error=1"))
                    msg = "<div class='alert alert-error'>Identifiants incorrects.</div>";
                else if (q != null && q.contains("registered=1"))
                    msg = "<div class='alert alert-success'>Compte créé ! Vous pouvez vous connecter.</div>";
                sendHtml(t, loginPage(msg));
            } else if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                try {
                    String ip = t.getRemoteAddress().getAddress().getHostAddress();
                    long[] attempts = LOGIN_ATTEMPTS.getOrDefault(ip, new long[]{0, 0});
                    if (attempts[0] >= 5 && (System.currentTimeMillis() - attempts[1]) < 300_000L) {
                        redirect(t, "/login?error=locked"); return;
                    }
                    byte[] body = readAllBytes(t.getRequestBody());
                    Map<String, String> params;
                    try { params = parseFormBody(new String(body, "UTF-8")); }
                    catch (Exception ex) { redirect(t, "/login?error=1"); return; }
                    String username = params.getOrDefault("username", "").trim();
                    String password = params.getOrDefault("password", "");
                    if (username.isEmpty() || password.isEmpty()) { redirect(t, "/login?error=1"); return; }
                    PreparedStatement ps = getDB().prepareStatement(
                        "SELECT password_hash,role FROM users WHERE username=?");
                    ps.setString(1, username);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next() && BCrypt.checkpw(password, rs.getString("password_hash"))) {
                        LOGIN_ATTEMPTS.remove(ip);
                        String role = rs.getString("role");
                        String sid  = UUID.randomUUID().toString();
                        SESSIONS.put(sid, new Session(username, role));
                        PreparedStatement upd = getDB().prepareStatement(
                            "UPDATE users SET last_login=NOW() WHERE username=?");
                        upd.setString(1, username); upd.executeUpdate();
                        logAction(username, "CONNEXION", "Depuis " + ip);
                        // SameSite=Lax pour se protéger contre les CSRF
                        t.getResponseHeaders().set("Set-Cookie",
                            "sid=" + sid + "; Path=/; HttpOnly; SameSite=Lax");
                        redirect(t, "admin".equals(role) ? "/admin" : "/");
                    } else {
                        attempts[0]++;
                        if (attempts[0] == 1) attempts[1] = System.currentTimeMillis();
                        LOGIN_ATTEMPTS.put(ip, attempts);
                        redirect(t, "/login?error=1");
                    }
                } catch (Exception e) { sendError(t, e.getMessage()); }
            } else { t.sendResponseHeaders(405, -1); t.getResponseBody().close(); }
        }

        static String loginPage(String msg) {
            return "<!DOCTYPE html><html lang='fr'><head>"
                + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Connexion – Studio PDF CORBA</title>" + favicon()
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap' rel='stylesheet'>"
                + "<style>*{box-sizing:border-box;margin:0;padding:0;font-family:'Inter',sans-serif}"
                + "body{background:linear-gradient(135deg,#4F1D96 0%,#6D28D9 50%,#8B5CF6 100%);min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}"
                + ".card{background:#fff;border-radius:24px;padding:40px;width:100%;max-width:400px;box-shadow:0 40px 80px rgba(79,29,150,0.3)}"
                + ".logo{width:52px;height:52px;background:linear-gradient(135deg,#6D28D9,#8B5CF6);border-radius:16px;display:flex;align-items:center;justify-content:center;margin:0 auto 20px;font-size:24px}"
                + "h1{text-align:center;font-size:22px;font-weight:700;color:#1E1B4B;margin-bottom:4px}"
                + ".sub{text-align:center;font-size:13px;color:#9CA3AF;margin-bottom:24px}"
                + "label{font-size:11px;font-weight:600;color:#6B7280;display:block;margin-bottom:6px;letter-spacing:1px;text-transform:uppercase}"
                + "input{width:100%;padding:12px 16px;border:1.5px solid #EDE9FE;border-radius:12px;font-size:14px;color:#1E1B4B;outline:none;font-family:inherit;background:#F8F7FF;margin-bottom:16px;transition:0.2s}"
                + "input:focus{border-color:#7C3AED;background:#fff}"
                + "button{width:100%;background:linear-gradient(135deg,#6D28D9,#7C3AED);color:#fff;border:none;padding:14px;border-radius:12px;font-size:14px;font-weight:600;cursor:pointer;transition:0.2s;margin-top:4px}"
                + "button:hover{opacity:0.9;transform:translateY(-1px)}"
                + ".alert{padding:12px 16px;border-radius:10px;font-size:13px;margin-bottom:16px}"
                + ".alert-error{background:#FEE2E2;border:1px solid #FCA5A5;color:#991B1B}"
                + ".alert-success{background:#D1FAE5;border:1px solid #6EE7B7;color:#065F46}"
                + "</style></head><body><div class='card'>"
                + "<div class='logo'>📄</div>"
                + "<h1>Studio PDF CORBA</h1>"
                + "<p class='sub'>Connectez-vous pour accéder à l'application</p>"
                + msg
                + "<form method='POST' action='/login'>"
                + "<label>Nom d'utilisateur</label>"
                + "<input type='text' name='username' placeholder='Votre identifiant' required autofocus autocomplete='username'>"
                + "<label>Mot de passe</label>"
                + "<input type='password' name='password' placeholder='••••••••' required autocomplete='current-password'>"
                + "<button type='submit'>Se connecter →</button>"
                + "</form>"
                + "<p style='text-align:center;margin-top:20px;font-size:13px;color:#9CA3AF'>"
                + "Pas encore de compte ? <a href='/register' style='color:#7C3AED;font-weight:600;text-decoration:none'>Créer un compte</a>"
                + "</p></div></body></html>";
        }
    }

    static class LogoutHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String sid = getSessionId(t);
            if (sid != null) {
                Session s = SESSIONS.remove(sid);
                if (s != null) logAction(s.username, "DÉCONNEXION", "");
            }
            t.getResponseHeaders().set("Set-Cookie", "sid=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
            redirect(t, "/login");
        }
    }

    static class RegisterHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("GET".equalsIgnoreCase(t.getRequestMethod())) {
                if (isLoggedIn(t)) { redirect(t, "/"); return; }
                String q = t.getRequestURI().getQuery();
                String msg = "";
                if (q != null && q.contains("exists=1"))      msg = "<div class='alert alert-error'>Ce nom d'utilisateur est déjà pris.</div>";
                else if (q != null && q.contains("error=short")) msg = "<div class='alert alert-error'>Le mot de passe doit faire au moins 6 caractères.</div>";
                else if (q != null && q.contains("error=1"))     msg = "<div class='alert alert-error'>Les mots de passe ne correspondent pas.</div>";
                else if (q != null && q.contains("error=user"))  msg = "<div class='alert alert-error'>Le nom d'utilisateur doit faire entre 3 et 30 caractères (lettres, chiffres, _ seulement).</div>";
                sendHtml(t, registerPage(msg));
            } else if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                try {
                    byte[] body = readAllBytes(t.getRequestBody());
                    Map<String, String> params;
                    try { params = parseFormBody(new String(body, "UTF-8")); }
                    catch (Exception ex) { redirect(t, "/register"); return; }
                    String username = params.getOrDefault("username", "").trim();
                    String password = params.getOrDefault("password", "");
                    String confirm  = params.getOrDefault("confirm", "");
                    // Validation
                    if (username.isEmpty() || username.length() < 3 || username.length() > 30
                            || !username.matches("[a-zA-Z0-9_]+")) {
                        redirect(t, "/register?error=user"); return;
                    }
                    if (!password.equals(confirm)) { redirect(t, "/register?error=1"); return; }
                    if (password.length() < 6)     { redirect(t, "/register?error=short"); return; }
                    PreparedStatement check = getDB().prepareStatement("SELECT 1 FROM users WHERE username=?");
                    check.setString(1, username);
                    if (check.executeQuery().next()) { redirect(t, "/register?exists=1"); return; }
                    PreparedStatement ins = getDB().prepareStatement(
                        "INSERT INTO users(username,password_hash,role) VALUES(?,?,'user')");
                    ins.setString(1, username);
                    ins.setString(2, BCrypt.hashpw(password, BCrypt.gensalt()));
                    ins.executeUpdate();
                    logAction(username, "INSCRIPTION", "Nouveau compte créé");
                    redirect(t, "/login?registered=1");
                } catch (Exception e) { sendError(t, e.getMessage()); }
            } else { t.sendResponseHeaders(405, -1); t.getResponseBody().close(); }
        }

        static String registerPage(String msg) {
            return "<!DOCTYPE html><html lang='fr'><head>"
                + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Inscription – Studio PDF CORBA</title>" + favicon()
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap' rel='stylesheet'>"
                + "<style>*{box-sizing:border-box;margin:0;padding:0;font-family:'Inter',sans-serif}"
                + "body{background:linear-gradient(135deg,#4F1D96 0%,#6D28D9 50%,#8B5CF6 100%);min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}"
                + ".card{background:#fff;border-radius:24px;padding:40px;width:100%;max-width:400px;box-shadow:0 40px 80px rgba(79,29,150,0.3)}"
                + ".logo{width:52px;height:52px;background:linear-gradient(135deg,#6D28D9,#8B5CF6);border-radius:16px;display:flex;align-items:center;justify-content:center;margin:0 auto 20px;font-size:24px}"
                + "h1{text-align:center;font-size:22px;font-weight:700;color:#1E1B4B;margin-bottom:4px}"
                + ".sub{text-align:center;font-size:13px;color:#9CA3AF;margin-bottom:24px}"
                + "label{font-size:11px;font-weight:600;color:#6B7280;display:block;margin-bottom:6px;letter-spacing:1px;text-transform:uppercase}"
                + "input{width:100%;padding:12px 16px;border:1.5px solid #EDE9FE;border-radius:12px;font-size:14px;color:#1E1B4B;outline:none;font-family:inherit;background:#F8F7FF;margin-bottom:16px;transition:0.2s}"
                + "input:focus{border-color:#7C3AED;background:#fff}"
                + "button{width:100%;background:linear-gradient(135deg,#6D28D9,#7C3AED);color:#fff;border:none;padding:14px;border-radius:12px;font-size:14px;font-weight:600;cursor:pointer;transition:0.2s;margin-top:4px}"
                + "button:hover{opacity:0.9;transform:translateY(-1px)}"
                + ".alert{padding:12px 16px;border-radius:10px;font-size:13px;margin-bottom:16px}"
                + ".alert-error{background:#FEE2E2;border:1px solid #FCA5A5;color:#991B1B}"
                + ".hint{font-size:11px;color:#9CA3AF;margin-top:-10px;margin-bottom:14px}"
                + "</style></head><body><div class='card'>"
                + "<div class='logo'>✨</div>"
                + "<h1>Créer un compte</h1>"
                + "<p class='sub'>Rejoignez Studio PDF CORBA</p>"
                + msg
                + "<form method='POST' action='/register'>"
                + "<label>Nom d'utilisateur</label>"
                + "<input type='text' name='username' placeholder='3–30 caractères (lettres, chiffres, _)' required autofocus minlength='3' maxlength='30' pattern='[a-zA-Z0-9_]+'>"
                + "<label>Mot de passe</label>"
                + "<input type='password' name='password' placeholder='Minimum 6 caractères' required minlength='6'>"
                + "<p class='hint'>Au moins 6 caractères</p>"
                + "<label>Confirmer le mot de passe</label>"
                + "<input type='password' name='confirm' placeholder='Confirmez votre mot de passe' required>"
                + "<button type='submit'>Créer mon compte →</button>"
                + "</form>"
                + "<p style='text-align:center;margin-top:20px;font-size:13px;color:#9CA3AF'>"
                + "Déjà un compte ? <a href='/login' style='color:#7C3AED;font-weight:600;text-decoration:none'>Se connecter</a>"
                + "</p></div></body></html>";
        }
    }

    // ════════════════════════════════════════════════════════
    //  PAGES PRINCIPALES
    // ════════════════════════════════════════════════════════

    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            if ("admin".equals(getRole(t))) { redirect(t, "/admin"); return; }
            String username = getUsername(t);
            String html = "<!DOCTYPE html><html lang='fr'><head>"
                + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Studio PDF – Mon Espace</title>" + favicon()
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap' rel='stylesheet'>"
                + CSS_BASE + "</head><body>"
                + "<div class='topbar'>"
                + "<div class='topbar-row'>"
                + "<div><h1>📄 Mon espace PDF</h1><p>Traitez vos documents en toute simplicité</p></div>"
                + navbar(username, "user", "home")
                + "</div></div>"
                + "<div class='main'>"
                + "<p class='sec-label'>Outils disponibles</p>"
                + allTools()
                + "<div class='bottom'>"
                + "<div class='card-box'><h2>Créer un PDF</h2>"
                + "<p class='sub'>Générez instantanément via le serveur CORBA</p>"
                // FIX: POST au lieu de GET pour éviter la limite d'URL
                + "<form action='/create' method='post' onsubmit='return submitWithToken(this)'>"
                + "<div class='inp-row'>"
                + "<input class='inp' name='titre' placeholder='Titre...' required>"
                + "<input class='inp' name='auteur' placeholder='Auteur...'>"
                + "</div>"
                + "<textarea class='inp' name='corps' placeholder='Contenu du document...'></textarea>"
                + "<button class='btn-gen' type='submit'>Générer le PDF</button>"
                + "</form></div>"
                + "<div class='card-box'><h2>Mes actions récentes</h2>"
                + buildHistory(username, 5)
                + "</div></div></div>"
                + "<div class='footer'>Studio PDF CORBA &mdash; Java 8 &times; PDFBox 2.0</div>"
                + allModals() + jsCommon() + "</body></html>";
            sendHtml(t, html);
        }
    }

    static class AdminHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            if (!"admin".equals(getRole(t))) { redirect(t, "/"); return; }
            String username = getUsername(t);
            int[] stats = getStats();
            String html = "<!DOCTYPE html><html lang='fr'><head>"
                + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Admin – Studio PDF CORBA</title>" + favicon()
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap' rel='stylesheet'>"
                + CSS_BASE + "</head><body>"
                + "<div class='topbar' style='background:linear-gradient(135deg,#1E1B4B 0%,#312E81 40%,#4338CA 70%,#6366F1 100%)'>"
                + "<div class='topbar-row'>"
                + "<div><div class='admin-badge'>👑 ADMINISTRATEUR</div>"
                + "<h1>Tableau de bord</h1><p>Studio PDF CORBA – Panneau d'administration</p></div>"
                + navbar(username, "admin", "admin")
                + "</div></div>"
                + "<div class='main'>"
                + "<div class='stat-row'>"
                + "<div class='stat st1'><div class='stat-l'>Utilisateurs</div><div class='stat-n'>" + stats[0] + "</div><div class='stat-s'>Comptes enregistrés</div></div>"
                + "<div class='stat st2'><div class='stat-l'>Connectés</div><div class='stat-n'>" + SESSIONS.size() + "</div><div class='stat-s'>Sessions actives</div></div>"
                + "<div class='stat st3'><div class='stat-l'>Actions</div><div class='stat-n'>" + stats[1] + "</div><div class='stat-s'>Total historique</div></div>"
                + "<div class='stat st4'><div class='stat-l'>Aujourd'hui</div><div class='stat-n'>" + stats[2] + "</div><div class='stat-s'>Actions du jour</div></div>"
                + "</div>"
                + "<div class='section-card'>"
                + "<h2>Gestion des utilisateurs</h2>"
                + "<p class='sub'>" + stats[0] + " compte(s) &mdash; " + SESSIONS.size() + " session(s) active(s)</p>"
                + "<table class='admin-table'><thead><tr>"
                + "<th>Utilisateur</th><th>Rôle</th><th>Statut</th><th>Dernière connexion</th><th>Actions</th>"
                + "</tr></thead><tbody>" + buildUsersTable() + "</tbody></table></div>"
                + "<div class='section-card'><h2>Tous les outils PDF</h2><p class='sub'>Accès complet à toutes les fonctionnalités</p>"
                + allTools() + "</div>"
                + "<div class='section-card'><h2>Créer un PDF</h2><p class='sub'>Générez instantanément via le serveur CORBA</p>"
                // FIX: POST aussi côté admin
                + "<form action='/create' method='post' onsubmit='return submitWithToken(this)'>"
                + "<div class='inp-row'><input class='inp' name='titre' placeholder='Titre...' required><input class='inp' name='auteur' placeholder='Auteur...'></div>"
                + "<textarea class='inp' name='corps' placeholder='Contenu du document...'></textarea>"
                + "<button class='btn-gen' type='submit'>Générer le PDF</button>"
                + "</form></div>"
                + "<div class='section-card'><h2>Historique global</h2><p class='sub'>10 dernières actions</p>"
                + buildHistory(null, 10) + "</div>"
                + "</div>"
                + "<div class='footer'>Studio PDF CORBA – Interface Administrateur</div>"
                + allModals() + jsCommon() + "</body></html>";
            sendHtml(t, html);
        }
    }

    static class AdminDeleteUserHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t) || !"admin".equals(getRole(t))) { redirect(t, "/login"); return; }
            try {
                Map<String, String> p = parseQuery(t.getRequestURI().getQuery());
                String target = p.getOrDefault("u", "");
                if (!target.isEmpty() && !target.equals("admin")) {
                    PreparedStatement ps = getDB().prepareStatement(
                        "DELETE FROM users WHERE username=? AND role!='admin'");
                    ps.setString(1, target); ps.executeUpdate();
                    SESSIONS.entrySet().removeIf(e -> target.equals(e.getValue().username));
                    logAction(getUsername(t), "SUPPRESSION_USER", "Supprimé: " + target);
                }
                redirect(t, "/admin");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class AdminPromoteUserHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t) || !"admin".equals(getRole(t))) { redirect(t, "/login"); return; }
            try {
                Map<String, String> p = parseQuery(t.getRequestURI().getQuery());
                String target  = p.getOrDefault("u", "");
                String newRole = p.getOrDefault("r", "user");
                if (!target.isEmpty() && (newRole.equals("admin") || newRole.equals("user"))) {
                    PreparedStatement ps = getDB().prepareStatement(
                        "UPDATE users SET role=? WHERE username=?");
                    ps.setString(1, newRole); ps.setString(2, target); ps.executeUpdate();
                    for (Session s : SESSIONS.values()) {
                        if (target.equals(s.username)) s.role = newRole;
                    }
                    logAction(getUsername(t), "PROMOTION_USER", target + " → " + newRole);
                }
                redirect(t, "/admin");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    // ════════════════════════════════════════════════════════
    //  HANDLERS PDF
    // ════════════════════════════════════════════════════════

    /**
     * FIX MAJEUR : était en GET, le corps du document était dans l'URL
     * (limite ~2 Ko selon les proxies, accents perdus, historique navigateur).
     * Maintenant en POST avec body form-urlencoded.
     */
    static class GenerateHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                Map<String, String> p;
                if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                    p = parseFormBody(new String(readAllBytes(t.getRequestBody()), "UTF-8"));
                } else {
                    // Compatibilité avec d'éventuels liens anciens en GET
                    p = parseQuery(t.getRequestURI().getQuery());
                }
                String titre  = p.getOrDefault("titre", "Sans titre").trim();
                String corps  = p.getOrDefault("corps", "");
                String auteur = p.getOrDefault("auteur", "");
                if (titre.isEmpty()) titre = "Sans titre";
                byte[] pdf = pdfRef.creerPDF(titre, corps);
                logAction(getUsername(t), "CRÉATION_PDF", titre);
                sendPdf(t, pdf, "document.pdf", p.getOrDefault("dl_token",""));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class ExtractHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mpC = parseMultipart(t); byte[] pdf = mpC.files.values().iterator().next();
                String texte = pdfRef.extraireTexte(pdf);
                logAction(getUsername(t), "EXTRACTION_TEXTE", "");
                sendHtml(t, resultPage("Texte extrait", "#5B21B6", "#EDE9FE",
                    "<div style='background:#fff;border:1.5px solid #EDE9FE;border-radius:14px;padding:24px'>"
                    + "<pre style='white-space:pre-wrap;color:#1E1B4B;font-size:13px;line-height:1.8;overflow-x:auto'>"
                    + escapeHtml(texte) + "</pre></div>"));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class ToImageHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                int dpi = Integer.parseInt(mp.fields.getOrDefault("dpi", "150"));
                byte[][] images = pdfRef.convertirEnImages(mp.files.get("doc"), dpi);
                logAction(getUsername(t), "CONVERSION_IMAGE", dpi + " DPI");
                StringBuilder sb = new StringBuilder();
                // Afficher + lien téléchargement pour chaque page
                for (int i = 0; i < images.length; i++) {
                    String b64 = Base64.getEncoder().encodeToString(images[i]);
                    sb.append("<div style='margin-bottom:16px;border-radius:12px;overflow:hidden;border:1px solid #EDE9FE'>")
                      .append("<div style='background:#F5F3FF;padding:8px 14px;display:flex;align-items:center;justify-content:space-between'>")
                      .append("<span style='font-size:11px;color:#7C3AED;font-weight:500'>Page ").append(i + 1).append("</span>")
                      .append("<a href='data:image/png;base64,").append(b64).append("' download='page_").append(i + 1).append(".png' style='font-size:11px;color:#7C3AED;font-weight:600;text-decoration:none'>⬇ Télécharger PNG</a>")
                      .append("</div>")
                      .append("<img src='data:image/png;base64,").append(b64).append("' style='width:100%;display:block'></div>");
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
                String mdp = mp.fields.getOrDefault("mdp", "").trim();
                if (mdp.isEmpty()) throw new Exception("Le mot de passe ne peut pas être vide");
                byte[] result = pdfRef.ajouterMotDePasse(mp.files.get("doc"), mdp);
                logAction(getUsername(t), "PROTECTION_PDF", "");
                sendPdf(t, result, "protected.pdf", mp.fields.getOrDefault("dl_token",""));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class MergeHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                List<byte[]> docs = mp.fileList.get("docs");
                if (docs == null || docs.size() < 2)
                    throw new Exception("Sélectionnez au moins 2 fichiers PDF");
                byte[] result = pdfRef.fusionnerPDFs(docs.toArray(new byte[0][]));
                logAction(getUsername(t), "FUSION_PDF", docs.size() + " fichiers");
                sendPdf(t, result, "fusion.pdf", mp.fields.getOrDefault("dl_token",""));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class SplitHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                int nb = Math.max(1, Integer.parseInt(mp.fields.getOrDefault("nb", "1")));
                byte[][] parts = pdfRef.decouperPDF(mp.files.get("doc"), nb);
                logAction(getUsername(t), "DÉCOUPAGE_PDF", nb + " pages/partie");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.length; i++) {
                    String b64 = Base64.getEncoder().encodeToString(parts[i]);
                    sb.append("<div style='display:flex;align-items:center;gap:14px;padding:14px 18px;background:#fff;border:1.5px solid #EDE9FE;border-radius:12px;margin-bottom:10px'>")
                      .append("<div style='width:36px;height:36px;border-radius:9px;background:#EDE9FE;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:700;color:#5B21B6'>P").append(i + 1).append("</div>")
                      .append("<span style='flex:1;font-size:13px;font-weight:500;color:#1E1B4B'>Partie ").append(i + 1).append(" &mdash; ").append(parts[i].length / 1024).append(" Ko</span>")
                      .append("<a href='data:application/pdf;base64,").append(b64).append("' download='partie_").append(i + 1).append(".pdf' style='background:linear-gradient(135deg,#6D28D9,#7C3AED);color:#fff;padding:7px 16px;border-radius:8px;text-decoration:none;font-size:12px;font-weight:600'>Télécharger</a></div>");
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
                int[] pages = parsePages(mp.fields.getOrDefault("pages", "1"));
                byte[] result = pdfRef.supprimerPages(mp.files.get("doc"), pages);
                logAction(getUsername(t), "SUPPRESSION_PAGES", mp.fields.getOrDefault("pages", ""));
                sendPdf(t, result, "sans_pages.pdf", mp.fields.getOrDefault("dl_token",""));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class ExtractPagesHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                int[] pages = parsePages(mp.fields.getOrDefault("pages", "1"));
                byte[] result = pdfRef.extrairePages(mp.files.get("doc"), pages);
                logAction(getUsername(t), "EXTRACTION_PAGES", mp.fields.getOrDefault("pages", ""));
                sendPdf(t, result, "pages_extraites.pdf", mp.fields.getOrDefault("dl_token",""));
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
                int pct  = pdf.length > 0 ? (int)(gain * 100 / pdf.length) : 0;
                logAction(getUsername(t), "COMPRESSION_PDF", "Gain: " + (gain / 1024) + " Ko (" + pct + "%)");
                sendHtml(t, resultPage("PDF compressé", "#0F766E", "#CCFBF1",
                    "<div style='background:#fff;border:1.5px solid #CCFBF1;border-radius:14px;padding:24px'>"
                    + "<div style='display:flex;gap:16px;margin-bottom:20px'>"
                    + statBox("Taille originale",  (pdf.length / 1024) + " Ko",    "#EDE9FE", "#5B21B6")
                    + statBox("Taille compressée", (result.length / 1024) + " Ko", "#D1FAE5", "#065F46")
                    + statBox("Gain",              (gain / 1024) + " Ko (" + pct + "%)", "#FEF3C7", "#92400E")
                    + "</div><a href='data:application/pdf;base64," + Base64.getEncoder().encodeToString(result)
                    + "' download='compresse.pdf' style='background:linear-gradient(135deg,#059669,#10B981);color:#fff;padding:10px 24px;border-radius:10px;text-decoration:none;font-size:13px;font-weight:600'>Télécharger le PDF compressé</a></div>"));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class MetaReadHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mpM = parseMultipart(t); byte[] pdf = mpM.files.values().iterator().next();
                String meta = pdfRef.lireMetadonnees(pdf);
                logAction(getUsername(t), "LECTURE_META", "");
                sendHtml(t, resultPage("Métadonnées", "#3730A3", "#EEF2FF",
                    "<div style='background:#fff;border:1.5px solid #C7D2FE;border-radius:14px;padding:24px'>"
                    + "<pre style='white-space:pre-wrap;color:#1E1B4B;font-size:13px;line-height:2;font-family:Inter,sans-serif'>"
                    + escapeHtml(meta) + "</pre></div>"));
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
                    mp.fields.getOrDefault("titre", ""),
                    mp.fields.getOrDefault("auteur", ""),
                    mp.fields.getOrDefault("sujet", ""));
                logAction(getUsername(t), "MODIF_META", mp.fields.getOrDefault("titre", ""));
                sendPdf(t, result, "modifie.pdf", mp.fields.getOrDefault("dl_token",""));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class QRCodeHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                String contenu = mp.fields.getOrDefault("contenu", "https://corba.pdf");
                // FIX: le formulaire affiche "page 1" = index 0 côté serveur
                int page = Math.max(0, Integer.parseInt(mp.fields.getOrDefault("page", "1")) - 1);
                int x    = Integer.parseInt(mp.fields.getOrDefault("x", "400"));
                int y    = Integer.parseInt(mp.fields.getOrDefault("y", "50"));
                byte[] result = pdfRef.ajouterQRCode(mp.files.get("doc"), contenu, page, x, y);
                logAction(getUsername(t), "AJOUT_QRCODE", contenu);
                sendPdf(t, result, "avec_qrcode.pdf", mp.fields.getOrDefault("dl_token",""));
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
                    mp.fields.getOrDefault("nom", "Signataire"),
                    mp.fields.getOrDefault("raison", "Approbation"),
                    mp.fields.getOrDefault("lieu", "Dakar"));
                logAction(getUsername(t), "SIGNATURE_PDF", mp.fields.getOrDefault("nom", ""));
                sendPdf(t, result, "signe.pdf", mp.fields.getOrDefault("dl_token",""));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    // ════════════════════════════════════════════════════════
    //  HELPERS ADMIN
    // ════════════════════════════════════════════════════════

    static String buildUsersTable() {
        StringBuilder sb = new StringBuilder();
        Set<String> connected = new HashSet<>();
        for (Session s : SESSIONS.values()) connected.add(s.username);
        try {
            ResultSet rs = getDB().createStatement().executeQuery(
                "SELECT username,role,last_login FROM users ORDER BY role DESC,username");
            while (rs.next()) {
                String uname = rs.getString("username");
                String role  = rs.getString("role");
                String last  = rs.getTimestamp("last_login") != null
                    ? rs.getTimestamp("last_login").toString().substring(0, 16) : "Jamais";
                boolean conn = connected.contains(uname);
                boolean adm  = "admin".equals(role);
                String roleCell   = adm
                    ? "<span class='role-badge role-admin'>Administrateur</span>"
                    : "<span class='role-badge role-user'>Utilisateur</span>";
                String statusCell = conn
                    ? "<span style='color:#059669;font-weight:600;font-size:12px'>&#9679; Connecté</span>"
                    : "<span style='color:#9CA3AF;font-size:12px'>&#9675; Hors ligne</span>";
                String actions = uname.equals("admin")
                    ? "<span style='font-size:11px;color:#9CA3AF'>Compte protégé</span>"
                    : "<a class='btn-sm btn-promote' href='#' onclick='confirm2(\""
                        + (adm ? "Rétrograder" : "Promouvoir admin") + " " + escapeHtml(uname) + "?\","
                        + "\"/admin/promote-user?u=" + escapeHtml(uname) + "&r=" + (adm ? "user" : "admin") + "\")'>"
                        + (adm ? "Rétrograder" : "Promouvoir") + "</a>"
                      + "<a class='btn-sm btn-del' href='#' onclick='confirm2(\"Supprimer "
                        + escapeHtml(uname) + "?\",\"/admin/delete-user?u=" + escapeHtml(uname) + "\")'>Supprimer</a>";
                sb.append("<tr><td><b>").append(escapeHtml(uname)).append("</b></td><td>").append(roleCell)
                  .append("</td><td>").append(statusCell)
                  .append("</td><td style='font-size:12px;color:#9CA3AF'>").append(last)
                  .append("</td><td>").append(actions).append("</td></tr>");
            }
        } catch (Exception e) {
            sb.append("<tr><td colspan='5' style='color:#9CA3AF'>Erreur : ").append(escapeHtml(e.getMessage())).append("</td></tr>");
        }
        return sb.toString();
    }

    static String buildHistory(String username, int limit) {
        StringBuilder sb = new StringBuilder();
        try {
            PreparedStatement ps;
            if (username != null) {
                ps = getDB().prepareStatement(
                    "SELECT username,action,detail,created_at FROM actions WHERE username=? ORDER BY created_at DESC LIMIT ?");
                ps.setString(1, username); ps.setInt(2, limit);
            } else {
                ps = getDB().prepareStatement(
                    "SELECT username,action,detail,created_at FROM actions ORDER BY created_at DESC LIMIT ?");
                ps.setInt(1, limit);
            }
            ResultSet rs = ps.executeQuery();
            boolean any = false;
            while (rs.next()) {
                any = true;
                String when = rs.getTimestamp("created_at").toString().substring(0, 16);
                sb.append("<div class='history-item'><div class='history-dot'></div><div class='history-info'>")
                  .append("<b>").append(escapeHtml(rs.getString("action")));
                String det = rs.getString("detail");
                if (det != null && !det.isEmpty()) sb.append(" &mdash; ").append(escapeHtml(det));
                sb.append("</b><small>").append(escapeHtml(rs.getString("username")))
                  .append(" &mdash; ").append(when).append("</small></div></div>");
            }
            if (!any) sb.append("<p style='font-size:13px;color:#9CA3AF'>Aucune action enregistrée.</p>");
        } catch (Exception e) {
            sb.append("<p style='font-size:13px;color:#9CA3AF'>Historique indisponible.</p>");
        }
        return sb.toString();
    }

    static int[] getStats() {
        int[] s = {0, 0, 0};
        try {
            ResultSet r1 = getDB().createStatement().executeQuery("SELECT COUNT(*) FROM users");
            if (r1.next()) s[0] = r1.getInt(1);
            ResultSet r2 = getDB().createStatement().executeQuery("SELECT COUNT(*) FROM actions");
            if (r2.next()) s[1] = r2.getInt(1);
            ResultSet r3 = getDB().createStatement().executeQuery(
                "SELECT COUNT(*) FROM actions WHERE created_at::date=CURRENT_DATE");
            if (r3.next()) s[2] = r3.getInt(1);
        } catch (Exception e) {
            System.err.println("[STATS] " + e.getMessage());
        }
        return s;
    }

    // ════════════════════════════════════════════════════════
    //  UTILITAIRES
    // ════════════════════════════════════════════════════════

    static String statBox(String label, String val, String bg, String color) {
        return "<div style='flex:1;background:" + bg + ";border-radius:10px;padding:14px;text-align:center'>"
            + "<p style='font-size:10px;font-weight:600;color:" + color + ";text-transform:uppercase;letter-spacing:1px;margin-bottom:6px'>" + label + "</p>"
            + "<p style='font-size:20px;font-weight:700;color:" + color + "'>" + val + "</p></div>";
    }

    static class MultipartData {
        Map<String, byte[]>        files    = new HashMap<>();
        Map<String, List<byte[]>>  fileList = new HashMap<>();
        Map<String, String>        fields   = new HashMap<>();
    }

    /**
     * FIX : boundary peut contenir des espaces ou être entouré de guillemets.
     * On trim() et on retire les guillemets éventuels.
     */
    static MultipartData parseMultipart(HttpExchange t) throws Exception {
        MultipartData mp = new MultipartData();
        String ct = t.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.contains("boundary=")) throw new Exception("Content-Type multipart invalide");
        String boundaryParam = ct.split("boundary=", 2)[1].trim();
        // Retirer les guillemets éventuels
        if (boundaryParam.startsWith("\"")) boundaryParam = boundaryParam.replace("\"", "");
        String boundary = "--" + boundaryParam;

        byte[] body = readAllBytes(t.getRequestBody());
        String raw  = new String(body, "ISO-8859-1");
        String[] parts = raw.split(java.util.regex.Pattern.quote(boundary));
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty() || trimmed.equals("--") || trimmed.startsWith("--")) continue;
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

    /** Pages au format "1,3,5" → index 0-based [0,2,4] */
    static int[] parsePages(String s) throws Exception {
        if (s == null || s.trim().isEmpty()) throw new Exception("La liste de pages est vide");
        String[] parts = s.split("[,;\\s]+");
        int[] pages = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                int num = Integer.parseInt(parts[i].trim());
                if (num < 1) throw new Exception("Numéro de page invalide : " + parts[i]);
                pages[i] = num - 1; // 1-based → 0-based
            } catch (NumberFormatException e) {
                throw new Exception("Numéro de page non valide : '" + parts[i] + "'");
            }
        }
        return pages;
    }

    static Map<String, String> parseQuery(String q) throws Exception {
        Map<String, String> m = new HashMap<>();
        if (q == null) return m;
        for (String s : q.split("&")) {
            String[] kv = s.split("=", 2);
            if (kv.length > 1) m.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
        }
        return m;
    }

    static Map<String, String> parseFormBody(String body) throws Exception {
        Map<String, String> m = new HashMap<>();
        if (body == null || body.isEmpty()) return m;
        for (String s : body.split("&")) {
            String[] kv = s.split("=", 2);
            if (kv.length == 2)
                m.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
        }
        return m;
    }

    static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    static void sendPdf(HttpExchange t, byte[] data, String name, String dlToken) throws IOException {
        // Poser le cookie dl_done avec le token pour que le JS puisse cacher le spinner
        if (dlToken != null && !dlToken.isEmpty()) {
            t.getResponseHeaders().set("Set-Cookie",
                "dl_done=" + dlToken + "; Path=/; SameSite=Lax; Max-Age=30");
        }
        t.getResponseHeaders().set("Content-Type", "application/pdf");
        t.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");
        t.sendResponseHeaders(200, data.length);
        OutputStream os = t.getResponseBody();
        os.write(data);
        os.close();
    }
    static void sendPdf(HttpExchange t, byte[] data, String name) throws IOException {
        sendPdf(t, data, name, null);
    }

    static void sendHtml(HttpExchange t, String html) throws IOException {
        byte[] b = html.getBytes("UTF-8");
        t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        t.sendResponseHeaders(200, b.length);
        OutputStream os = t.getResponseBody();
        os.write(b);
        os.close();
    }

    static String resultPage(String titre, String color, String bg, String content) {
        return "<!DOCTYPE html><html lang='fr'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>" + titre + " – Studio PDF</title>" + favicon()
            + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap' rel='stylesheet'>"
            + "<style>*{box-sizing:border-box;margin:0;padding:0;font-family:'Inter',sans-serif}"
            + "body{background:#F5F3FF;min-height:100vh}"
            + ".topbar{background:linear-gradient(135deg,#4F1D96,#7C3AED);padding:18px 28px;display:flex;align-items:center;gap:16px}"
            + ".topbar a{color:rgba(255,255,255,0.8);text-decoration:none;font-size:13px;font-weight:500}"
            + ".topbar h2{color:white;font-size:14px;font-weight:600}"
            + ".content{max-width:860px;margin:0 auto;padding:28px 24px}"
            + ".result-header{background:" + bg + ";border-radius:12px;padding:14px 18px;margin-bottom:20px}"
            + ".result-header span{font-size:11px;font-weight:600;color:" + color + ";text-transform:uppercase;letter-spacing:1.5px}"
            + "</style></head><body>"
            + "<div class='topbar'>"
            + "<a href='javascript:history.back()'>&#8592; Retour</a>&nbsp;&nbsp;"
            + "<h2>" + titre + "</h2>"
            + "</div>"
            + "<div class='content'>"
            + "<div class='result-header'><span>" + titre + "</span></div>"
            + content
            + "</div></body></html>";
    }

    static void sendError(HttpExchange t, String msg) throws IOException {
        sendHtml(t, resultPage("Erreur", "#991B1B", "#FEE2E2",
            "<div style='background:#fff;border:1.5px solid #FCA5A5;border-radius:12px;padding:24px;text-align:center'>"
            + "<h3 style='color:#DC2626;margin-bottom:8px;font-size:15px'>Une erreur est survenue</h3>"
            + "<p style='color:#9CA3AF;font-size:13px'>" + escapeHtml(msg) + "</p>"
            + "<a href='javascript:history.back()' style='display:inline-block;margin-top:16px;background:#EDE9FE;color:#5B21B6;padding:8px 20px;border-radius:8px;text-decoration:none;font-size:13px;font-weight:600'>← Réessayer</a>"
            + "</div>"));
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
