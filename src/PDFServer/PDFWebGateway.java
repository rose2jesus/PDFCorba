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

// Imports requis pour la base de données PostgreSQL et BCrypt
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.mindrot.jbcrypt.BCrypt;

public class PDFWebGateway {
    private static PDFService pdfRef;
    private static int nbCrees = 0, nbExtractions = 0, nbFusions = 0, nbProtections = 0;

    // Table de session en mémoire vive (pour suivre les utilisateurs actifs connectés)
    private static final Map<String, String> SESSIONS = Collections.synchronizedMap(new HashMap<>());

    // ── Connexion dynamique et sécurisée à la Base de Données ──────────────
    private static Connection getDbConnection() throws SQLException {
        String dbUrl = System.getenv("DATABASE_URL");
        
        // Mode local (Fallback si exécuté en dehors de Render)
        if (dbUrl == null) {
            dbUrl = "jdbc:postgresql://localhost:5432/pdfcorba_db";
            return DriverManager.getConnection(dbUrl, "postgres", "password");
        }

        // CORRECTION RENDER : Traduction du protocole "postgres://" en "jdbc:postgresql://"
        if (dbUrl.startsWith("postgres://")) {
            dbUrl = dbUrl.replace("postgres://", "jdbc:postgresql://");
        }

        // Sécurité : Forcer le mode SSL exigé par l'infrastructure de Render
        if (!dbUrl.contains("sslmode")) {
            dbUrl += dbUrl.contains("?") ? "&sslmode=require" : "?sslmode=require";
        }
        
        // JDBC extrait nativement l'utilisateur et le mot de passe présents dans l'URL standardisée
        return DriverManager.getConnection(dbUrl);
    }

    // ── Initialisation de la Base de Données au démarrage ─────────────────
    private static void initDatabase() {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS users ("
                + "username VARCHAR(50) PRIMARY KEY, "
                + "password_hash VARCHAR(255) NOT NULL, "
                + "role VARCHAR(20) NOT NULL DEFAULT 'user');";
        
        try (Connection conn = getDbConnection();
             PreparedStatement stmt = conn.prepareStatement(createTableSQL)) {
            stmt.executeUpdate();
            
            // Injection automatique des deux comptes par défaut s'ils n'existent pas encore
            insertDefaultUser(conn, "admin", "admin123", "admin");
            insertDefaultUser(conn, "user", "user123", "user");
            
        } catch (SQLException e) {
            System.err.println("Erreur critique d'initialisation de la DB : " + e.getMessage());
        }
    }

    private static void insertDefaultUser(Connection conn, String username, String plainPassword, String role) throws SQLException {
        String checkSQL = "SELECT username FROM users WHERE username = ?";
        try (PreparedStatement checkStmt = conn.prepareStatement(checkSQL)) {
            checkStmt.setString(1, username);
            ResultSet rs = checkStmt.executeQuery();
            if (!rs.next()) {
                String insertSQL = "INSERT INTO users (username, password_hash, role) VALUES (?, ?, ?)";
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSQL)) {
                    String hashed = BCrypt.hashpw(plainPassword, BCrypt.gensalt(12));
                    insertStmt.setString(1, username);
                    insertStmt.setString(2, hashed);
                    insertStmt.setString(3, role);
                    insertStmt.executeUpdate();
                }
            }
        }
    }

    // ── Extraction dynamique du rôle de l'utilisateur ─────────────────────
    private static String getUserRole(String username) {
        String query = "SELECT role FROM users WHERE username = ?";
        try (Connection conn = getDbConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("role");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // ── Gestionnaires de Cookies Sécurisés (Anti CSRF / XSS) ──────────────
    static String getSessionId(HttpExchange t) {
        String cookieHeader = t.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null) return null;
        for (String c : cookieHeader.split(";")) {
            String[] pair = c.trim().split("=");
            if (pair.length == 2 && "session".equals(pair[0])) {
                return pair[1];
            }
        }
        return null;
    }

    static void setSessionCookie(HttpExchange t, String sid) {
        // Ajout des drapeaux professionnels de restriction d'accès aux cookies
        t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly; SameSite=Strict");
    }

    static void clearSessionCookie(HttpExchange t) {
        t.getResponseHeaders().set("Set-Cookie", "session=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; SameSite=Strict");
    }

    // ── Point d'entrée principal (Main) ───────────────────────────────────
    public static void main(String[] args) {
        try {
            // 1. Démarrage et synchronisation de la base PostgreSQL
            initDatabase();

            // 2. Initialisation de la couche CORBA
            Properties props = new Properties();
            props.put("org.omg.CORBA.ORBInitialHost", "localhost");
            props.put("org.omg.CORBA.ORBInitialPort", "1050");
            ORB orb = ORB.init(new String[]{}, props);

            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));

            // 3. Configuration du port dynamique pour Render
            int port = 8080;
            String envPort = System.getenv("PORT");
            if (envPort != null) port = Integer.parseInt(envPort);

            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            
            // Mappage des routes existantes
            server.createContext("/", new RootHandler());
            server.createContext("/login", new LoginHandler());
            server.createContext("/register", new RegisterHandler());
            server.createContext("/logout", new LogoutHandler());
            server.createContext("/dashboard", new DashboardHandler());
            server.createContext("/create", new CreateHandler());
            server.createContext("/extract", new ExtractHandler());
            server.createContext("/merge", new MergeHandler());
            server.createContext("/protect", new ProtectHandler());
            server.createContext("/to-image", new ToImageHandler());

            // Mappage des nouvelles routes d'administration demandées
            server.createContext("/admin/delete-user", new AdminDeleteUserHandler());
            server.createContext("/admin/toggle-role", new AdminToggleRoleHandler());

            server.setExecutor(null);
            System.out.println("=== Passerelle Web active (Port " + port + ") ===");
            server.start();

        } catch (Exception e) {
            System.err.println("Erreur d'initialisation réseau/CORBA : " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ── COUCHE DES HANDLERS DE FLUX HTTP ──────────────────────────────────

    static class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String sid = getSessionId(t);
            if (sid != null && SESSIONS.containsKey(sid)) {
                t.getResponseHeaders().set("Location", "/dashboard");
                t.sendResponseHeaders(303, -1);
                return;
            }

            String html = "<html><head><meta charset='UTF-8'><title>Studio PDF CORBA</title>"
                + "<style>"
                + "body{font-family:'Segoe UI',system-ui,sans-serif;background:#f1f5f9;margin:0;display:flex;align-items:center;justify-content:center;min-height:100vh;color:#1e293b}"
                + ".card{background:#fff;padding:40px;border-radius:16px;box-shadow:0 10px 25px -5px rgba(0,0,0,0.05),0 8px 10px -6px rgba(0,0,0,0.05);width:100%;max-width:400px;text-align:center;box-sizing:border-box}"
                + "h1{font-size:28px;margin-bottom:8px;font-weight:700;color:#0f172a;letter-spacing:-0.5px}"
                + "p{color:#64748b;font-size:15px;margin-bottom:32px}"
                + "form{display:flex;flex-direction:column;gap:16px;text-align:left}"
                + "label{font-size:13px;font-weight:600;color:#475569;margin-bottom:-6px}"
                + "input{padding:12px 16px;border:1.5px solid #e2e8f0;border-radius:8px;font-size:15px;transition:all 0.2s;width:100%;box-sizing:border-box}"
                + "input:focus{outline:none;border-color:#2563eb;box-shadow:0 0 0 4px rgba(37,99,235,0.15)}"
                + "button{background:#2563eb;color:#fff;border:none;padding:12px;border-radius:8px;font-size:15px;font-weight:600;cursor:pointer;transition:background 0.2s;margin-top:8px}"
                + "button:hover{background:#1d4ed8}"
                + ".switch{margin-top:24px;font-size:14px;color:#64748b}"
                + ".switch a{color:#2563eb;text-decoration:none;font-weight:500}"
                + ".switch a:hover{text-decoration:underline}"
                + "@media (max-width: 450px) { .card { padding: 24px; border-radius: 0; min-height: 100vh; display: flex; flex-direction: column; justify-content: center; } }"
                + "</style></head><body>"
                + "<div class='card'>"
                + "<h1>Studio PDF</h1>"
                + "<p>Connectez-vous pour gérer vos documents</p>"
                + "<form action='/login' method='POST'>"
                + "<label>Nom d'utilisateur</label><input type='text' name='username' required>"
                + "<label>Mot de passe</label><input type='password' name='password' required>"
                + "<button type='submit'>Se connecter</button>"
                + "</form>"
                + "<div class='switch'>Pas encore de compte ? <a href='#' onclick='showRegister()'>Créer un compte</a></div>"
                + "</div>"
                + "<script>"
                + "function showRegister(){"
                + "  document.querySelector('p').innerText='Créez votre compte en quelques secondes';"
                + "  document.querySelector('form').action='/register';"
                + "  document.querySelector('button').innerText='S\\'inscrire';"
                + "  document.querySelector('.switch').innerHTML='Déjà inscrit ? <a href=\"#\" onclick=\"location.reload()\">Se connecter</a>';"
                + "}"
                + "</script>"
                + "</body></html>";

            sendHtml(t, html);
        }
    }

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                sendError(t, "Méthode non autorisée");
                return;
            }
            Map<String, String> params = parseFormData(t);
            String u = params.get("username");
            String p = params.get("password");

            boolean authenticated = false;
            String query = "SELECT password_hash FROM users WHERE username = ?";
            try (Connection conn = getDbConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, u);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    String hashed = rs.getString("password_hash");
                    // Validation sécurisée via comparaison par BCrypt
                    if (BCrypt.checkpw(p, hashed)) {
                        authenticated = true;
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            if (authenticated) {
                String sid = UUID.randomUUID().toString();
                SESSIONS.put(sid, u);
                setSessionCookie(t, sid);
                t.getResponseHeaders().set("Location", "/dashboard");
                t.sendResponseHeaders(303, -1);
            } else {
                sendError(t, "Identifiants invalides ou erronés.");
            }
        }
    }

    static class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                sendError(t, "Méthode non autorisée");
                return;
            }
            Map<String, String> params = parseFormData(t);
            String u = params.get("username");
            String p = params.get("password");

            if (u == null || p == null || u.trim().isEmpty() || p.trim().isEmpty()) {
                sendError(t, "Veuillez remplir l'intégralité des champs.");
                return;
            }

            boolean success = false;
            String insertSQL = "INSERT INTO users (username, password_hash, role) VALUES (?, ?, 'user')";
            try (Connection conn = getDbConnection();
                 PreparedStatement stmt = conn.prepareStatement(insertSQL)) {
                String hashed = BCrypt.hashpw(p, BCrypt.gensalt(12));
                stmt.setString(1, u.trim());
                stmt.setString(2, hashed);
                int rows = stmt.executeUpdate();
                if (rows > 0) success = true;
            } catch (SQLException e) {
                sendError(t, "Désolé, cet identifiant est déjà utilisé.");
                return;
            }

            if (success) {
                String sid = UUID.randomUUID().toString();
                SESSIONS.put(sid, u);
                setSessionCookie(t, sid);
                t.getResponseHeaders().set("Location", "/dashboard");
                t.sendResponseHeaders(303, -1);
            } else {
                sendError(t, "Une erreur s'est produite lors de la création du compte.");
            }
        }
    }

    static class LogoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String sid = getSessionId(t);
            if (sid != null) SESSIONS.remove(sid);
            clearSessionCookie(t);
            t.getResponseHeaders().set("Location", "/");
            t.sendResponseHeaders(303, -1);
        }
    }

    static class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String sid = getSessionId(t);
            if (sid == null || !SESSIONS.containsKey(sid)) {
                t.getResponseHeaders().set("Location", "/");
                t.sendResponseHeaders(303, -1);
                return;
            }

            String user = SESSIONS.get(sid);
            String role = getUserRole(user);

            StringBuilder html = new StringBuilder();
            html.append("<html><head><meta charset='UTF-8'><title>Studio PDF — Table de contrôle</title>")
                .append("<style>")
                .append("body{font-family:'Segoe UI',system-ui,sans-serif;background:#f8fafc;margin:0;color:#0f172a}")
                .append(".navbar{background:#0f172a;color:#fff;padding:16px 32px;display:flex;justify-content:between;align-items:center;box-shadow:0 4px 6px -1px rgba(0,0,0,0.1)}")
                .append(".navbar h2{margin:0;font-size:20px;font-weight:600;letter-spacing:-0.5px}")
                .append(".navbar-right{display:flex;align-items:center;gap:20px;margin-left:auto}")
                .append(".user-badge{background:#334155;padding:6px 12px;border-radius:20px;font-size:13px;font-weight:500;color:#cbd5e1}")
                .append(".logout-btn{color:#f1f5f9;text-decoration:none;font-size:14px;font-weight:500;transition:opacity 0.2s}")
                .append(".logout-btn:hover{opacity:0.8}")
                .append(".container{max-width:1140px;margin:40px auto;padding:0 24px;box-sizing:border-box}")
                .append(".welcome-header{margin-bottom:32px}")
                .append(".welcome-header h1{font-size:28px;margin:0 0 8px 0;font-weight:700;letter-spacing:-0.5px}")
                .append(".welcome-header p{margin:0;color:#64748b;font-size:16px}")
                
                // Amélioration de la structure Grid pour s'adapter proprement aux mobiles
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fit, minmax(260px, 1fr));gap:24px;margin-bottom:40px}")
                
                .append(".card{background:#fff;border-radius:12px;border:1px solid #e2e8f0;padding:24px;box-shadow:0 1px 3px rgba(0,0,0,0.05);transition:transform 0.2s,box-shadow 0.2s}")
                .append(".card:hover{transform:translateY(-2px);box-shadow:0 10px 15px -3px rgba(0,0,0,0.05)}")
                .append(".card h3{margin:0 0 12px 0;font-size:18px;font-weight:600;color:#1e293b}")
                .append(".card p{margin:0 0 20px 0;color:#64748b;font-size:14px;line-height:1.5}")
                .append(".form-group{display:flex;flex-direction:column;gap:8px;margin-bottom:14px}")
                .append(".form-group label{font-size:12px;font-weight:600;color:#475569}")
                .append("input[type='text'],input[type='file'],input[type='password']{padding:10px 12px;border:1.5px solid #e2e8f0;border-radius:6px;font-size:14px;width:100%;box-sizing:border-box}")
                .append("button.btn{background:#2563eb;color:#fff;border:none;padding:10px;border-radius:6px;font-size:14px;font-weight:600;cursor:pointer;width:100%;transition:background 0.2s}")
                .append("button.btn:hover{background:#1d4ed8}")
                
                // Section d'administration intégrée
                .append(".admin-section{background:#fff;border-radius:12px;border:1px solid #fee2e2;padding:32px;box-shadow:0 1px 3px rgba(0,0,0,0.05)}")
                .append(".admin-section h2{margin:0 0 8px 0;color:#991b1b;font-size:22px;font-weight:700}")
                .append(".stats-grid{display:grid;grid-template-columns:repeat(auto-fit, minmax(180px, 1fr));gap:16px;margin-top:24px;margin-bottom:24px}")
                .append(".stat-box{background:#fef2f2;border:1px solid #fee2e2;border-radius:8px;padding:16px;text-align:center}")
                .append(".stat-val{font-size:24px;font-weight:700;color:#991b1b;margin-bottom:4px}")
                .append(".stat-lbl{font-size:12px;color:#7f1d1d;font-weight:500;text-transform:uppercase;letter-spacing:0.5px}")
                
                .append(".admin-table{width:100%;border-collapse:collapse;margin-top:20px;font-size:14px;}")
                .append(".admin-table th, .admin-table td{padding:12px;text-align:left;border-bottom:1px solid #e2e8f0;}")
                .append(".admin-table th{background:#f1f5f9;color:#475569;font-weight:600;}")
                .append(".btn-danger{background:#ef4444;color:white;border:none;padding:6px 12px;border-radius:4px;cursor:pointer;font-size:12px;}")
                .append(".btn-danger:hover{background:#dc2626;}")
                .append(".btn-secondary{background:#64748b;color:white;border:none;padding:6px 12px;border-radius:4px;cursor:pointer;font-size:12px;}")
                .append(".btn-secondary:hover{background:#475569;}")
                
                .append("</style></head><body>")
                
                .append("<div class='navbar'>")
                .append("<h2>Studio PDF</h2>")
                .append("<div class='navbar-right'>")
                .append("<span class='user-badge'>").append(user).append(" (").append(role).append(")</span>")
                .append("<a href='/logout' class='logout-btn'>Déconnexion</a>")
                .append("</div></div>")
                
                .append("<div class='container'>")
                .append("<div class='welcome-header'>")
                .append("<h1>Tableau de bord</h1>")
                .append("<p>Exploitez la puissance des objets distribués CORBA pour manipuler vos fichiers</p>")
                .append("</div>")
                
                .append("<div class='grid'>")
                
                // Formulaires métiers d'origine
                .append("<div class='card'><h3>Créer un PDF</h3><p>Générez un document PDF de base contenant un texte brut personnalisé.</p>")
                .append("<form action='/create' method='POST'><div class='form-group'><label>Texte du document</label>")
                .append("<input type='text' name='content' placeholder='Saisissez votre texte...' required></div>")
                .append("<button type='submit' class='btn'>Générer</button></form></div>")
                
                .append("<div class='card'><h3>Extraire des pages</h3><p>Récupérez une page spécifique de votre document au format PDF.</p>")
                .append("<form action='/extract' method='POST' enctype='multipart/form-data'><div class='form-group'><label>Fichier PDF</label>")
                .append("<input type='file' name='file' accept='.pdf' required></div><div class='form-group'><label>Numéro de page</label>")
                .append("<input type='text' name='page' placeholder='Ex: 1' required></div>")
                .append("<button type='submit' class='btn'>Extraire</button></form></div>")
                
                .append("<div class='card'><h3>Fusionner deux PDF</h3><p>Assemblez deux documents distincts en un seul fichier final.</p>")
                .append("<form action='/merge' method='POST' enctype='multipart/form-data'><div class='form-group'><label>Premier PDF</label>")
                .append("<input type='file' name='file1' accept='.pdf' required></div><div class='form-group'><label>Second PDF</label>")
                .append("<input type='file' name='file2' accept='.pdf' required></div>")
                .append("<button type='submit' class='btn'>Fusionner</button></form></div>")
                
                .append("<div class='card'><h3>Chiffrer et Protéger</h3><p>Verrouillez l'accès en lecture de votre fichier par un mot de passe fort.</p>")
                .append("<form action='/protect' method='POST' enctype='multipart/form-data'><div class='form-group'><label>Fichier PDF</label>")
                .append("<input type='file' name='file' accept='.pdf' required></div><div class='form-group'><label>Mot de passe</label>")
                .append("<input type='password' name='password' placeholder='••••••••' required></div>")
                .append("<button type='submit' class='btn'>Sécuriser</button></form></div>")

                .append("<div class='card'><h3>PDF en Image</h3><p>Convertissez les pages d'un PDF en images affichables.</p>")
                .append("<form action='/to-image' method='POST' enctype='multipart/form-data'><div class='form-group'><label>Fichier PDF</label>")
                .append("<input type='file' name='file' accept='.pdf' required></div>")
                .append("<button type='submit' class='btn'>Convertir</button></form></div>")
                
                .append("</div>");

            // Rendu conditionnel du bloc d'administration si rôle == admin
            if ("admin".equals(role)) {
                html.append("<div class='admin-section'>")
                    .append("<h2>Console d'Administration</h2>")
                    .append("<p style='color:#7f1d1d;margin:0;font-size:14px'>Indicateurs d'activité globale du serveur et gestion des comptes persistants.</p>")
                    .append("<div class='stats-grid'>")
                    .append("<div class='stat-box'><div class='stat-val'>").append(nbCrees).append("</div><div class='stat-lbl'>Créations</div></div>")
                    .append("<div class='stat-box'><div class='stat-val'>").append(nbExtractions).append("</div><div class='stat-lbl'>Extractions</div></div>")
                    .append("<div class='stat-box'><div class='stat-val'>").append(nbFusions).append("</div><div class='stat-lbl'>Fusions</div></div>")
                    .append("<div class='stat-box'><div class='stat-val'>").append(nbProtections).append("</div><div class='stat-lbl'>Protections</div></div>")
                    .append("</div>");

                html.append("<h3>Liste des utilisateurs inscrits</h3>")
                    .append("<table class='admin-table'>")
                    .append("<tr><th>Nom d'utilisateur</th><th>Rôle actuel</th><th>Actions</th></tr>");

                String selectUsers = "SELECT username, role FROM users ORDER BY username ASC";
                try (Connection conn = getDbConnection();
                     PreparedStatement stmt = conn.prepareStatement(selectUsers);
                     ResultSet rs = stmt.executeQuery()) {
                    
                    while (rs.next()) {
                        String uName = rs.getString("username");
                        String uRole = rs.getString("role");
                        
                        html.append("<tr>")
                            .append("<td>").append(uName).append("</td>")
                            .append("<td><strong>").append(uRole).append("</strong></td>")
                            .append("<td>");
                        
                        if (!uName.equals(user)) {
                            html.append("<form action='/admin/toggle-role' method='POST' style='display:inline;margin-right:5px;'>")
                                .append("<input type='hidden' name='username' value='").append(uName).append("'>")
                                .append("<button type='submit' class='btn-secondary'>")
                                .append("admin".equals(uRole) ? "Retirer Admin" : "Rendre Admin")
                                .append("</button></form>");

                            html.append("<form action='/admin/delete-user' method='POST' style='display:inline;' onsubmit=\"return confirm('Supprimer définitivement l\\'utilisateur ").append(uName).append(" ?');\">")
                                .append("<input type='hidden' name='username' value='").append(uName).append("'>")
                                .append("<button type='submit' class='btn-danger'>Supprimer</button></form>");
                        } else {
                            html.append("<span style='color:#94a3b8;font-style:italic;'>Session active</span>");
                        }
                        html.append("</td></tr>");
                    }
                } catch (SQLException e) {
                    html.append("<tr><td colspan='3'>Erreur de communication avec la base de données.</td></tr>");
                }

                html.append("</table></div>");
            }

            html.append("</div></body></html>");
            sendHtml(t, html.toString());
        }
    }

    static class AdminDeleteUserHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                sendError(t, "Méthode non autorisée");
                return;
            }
            String sid = getSessionId(t);
            String currentUser = SESSIONS.get(sid);
            if (currentUser == null || !"admin".equals(getUserRole(currentUser))) {
                sendError(t, "Privilèges administrateur requis.");
                return;
            }

            Map<String, String> params = parseFormData(t);
            String userToDelete = params.get("username");

            if (userToDelete != null && !userToDelete.equalsIgnoreCase(currentUser)) {
                String query = "DELETE FROM users WHERE username = ?";
                try (Connection conn = getDbConnection();
                     PreparedStatement stmt = conn.prepareStatement(query)) {
                    stmt.setString(1, userToDelete);
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            t.getResponseHeaders().set("Location", "/dashboard");
            t.sendResponseHeaders(303, -1);
        }
    }

    static class AdminToggleRoleHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                sendError(t, "Méthode non autorisée");
                return;
            }
            String sid = getSessionId(t);
            String currentUser = SESSIONS.get(sid);
            if (currentUser == null || !"admin".equals(getUserRole(currentUser))) {
                sendError(t, "Privilèges administrateur requis.");
                return;
            }

            Map<String, String> params = parseFormData(t);
            String targetUser = params.get("username");

            if (targetUser != null && !targetUser.equalsIgnoreCase(currentUser)) {
                String currentRole = getUserRole(targetUser);
                String newRole = "admin".equals(currentRole) ? "user" : "admin";

                String query = "UPDATE users SET role = ? WHERE username = ?";
                try (Connection conn = getDbConnection();
                     PreparedStatement stmt = conn.prepareStatement(query)) {
                    stmt.setString(1, newRole);
                    stmt.setString(2, targetUser);
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            t.getResponseHeaders().set("Location", "/dashboard");
            t.sendResponseHeaders(303, -1);
        }
    }

    // ── GESTIONNAIRES MÉTIERS CORBA RE-VÉRIFIÉS ET CONSERVÉS ──────────────────

    static class CreateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if(!"POST".equalsIgnoreCase(t.getRequestMethod())){ sendError(t,"POST requis"); return; }
            Map<String,String> p = parseFormData(t);
            String content = p.get("content");
            if(content==null) content="";
            try {
                byte[] out = pdfRef.creerPDF(content);
                nbCrees++;
                sendPdfFile(t, "document_cree.pdf", out);
            } catch(Exception e){
                sendError(t, "Erreur CORBA Création : " + e.getMessage());
            }
        }
    }

    static class ExtractHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if(!"POST".equalsIgnoreCase(t.getRequestMethod())){ sendError(t,"POST requis"); return; }
            try {
                MultipartParser mp = new MultipartParser(t);
                byte[] f = mp.getFile("file");
                String pStr = mp.getString("page");
                int pageIdx = Integer.parseInt(pStr.trim());
                
                byte[] out = pdfRef.extrairePage(f, pageIdx);
                nbExtractions++;
                sendPdfFile(t, "page_extraite.pdf", out);
            } catch(Exception e){
                sendError(t, "Erreur Extraction : " + e.getMessage());
            }
        }
    }

    static class MergeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if(!"POST".equalsIgnoreCase(t.getRequestMethod())){ sendError(t,"POST requis"); return; }
            try {
                MultipartParser mp = new MultipartParser(t);
                byte[] f1 = mp.getFile("file1");
                byte[] f2 = mp.getFile("file2");

                byte[] out = pdfRef.fusionnerPDF(f1, f2);
                nbFusions++;
                sendPdfFile(t, "fusionne.pdf", out);
            } catch(Exception e){
                sendError(t, "Erreur Fusion : " + e.getMessage());
            }
        }
    }

    static class ProtectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if(!"POST".equalsIgnoreCase(t.getRequestMethod())){ sendError(t,"POST requis"); return; }
            try {
                MultipartParser mp = new MultipartParser(t);
                byte[] f = mp.getFile("file");
                String pass = mp.getString("password");

                byte[] out = pdfRef.protegerPDF(f, pass);
                nbProtections++;
                sendPdfFile(t, "protege.pdf", out);
            } catch(Exception e){
                sendError(t, "Erreur Protection : " + e.getMessage());
            }
        }
    }

    static class ToImageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if(!"POST".equalsIgnoreCase(t.getRequestMethod())){ sendError(t,"POST requis"); return; }
            try {
                MultipartParser mp = new MultipartParser(t);
                byte[] f = mp.getFile("file");

                String[] imgs = pdfRef.pdfToImages(f);
                StringBuilder sb = new StringBuilder();
                sb.append("<p style='color:#64748b;margin-bottom:24px'>Nombre de pages converties : <strong>").append(imgs.length).append("</strong></p>");
                sb.append("<div style='display:flex;flex-direction:column;gap:24px;align-items:center;'>");
                for(int i=0; i<imgs.length; i++) {
                    sb.append("<div style='background:#fff;border:1px solid #e2e8f0;border-radius:12px;padding:16px;box-shadow:0 4px 6px -1px rgba(0,0,0,0.05);max-width:100%;box-sizing:border-box'>")
                      .append("<span style='font-size:12px;font-weight:600;color:#64748b;display:block;margin-bottom:8px'>PAGE ").append(i+1).append("</span>")
                      .append("<img src='data:image/png;base64,").append(imgs[i]).append("' style='max-width:100%;height:auto;border-radius:6px;border:1px solid #f1f5f9;'/>")
                      .append("</div>");
                }
                sb.append("</div>");
                sendHtml(t, resultPage("Conversion PDF en Images", "#2563eb", "#dbeafe", sb.toString()));
            } catch(Exception e){
                sendError(t, "Erreur Conversion Images : " + e.getMessage());
            }
        }
    }

    // ── PARSEURS ET UTILITAIRES DE RENDU ──────────────────────────────────

    private static Map<String, String> parseFormData(HttpExchange t) throws IOException {
        Map<String, String> result = new HashMap<>();
        InputStream is = t.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        String body = bos.toString(StandardCharsets.UTF_8.name());
        if(body.isEmpty()) return result;
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            String[] idx = pair.split("=");
            if (idx.length == 2) {
                String key = URLDecoder.decode(idx[0], StandardCharsets.UTF_8.name());
                String value = URLDecoder.decode(idx[1], StandardCharsets.UTF_8.name());
                result.put(key, value);
            }
        }
        return result;
    }

    static void sendHtml(HttpExchange t, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        t.sendResponseHeaders(200, b.length);
        OutputStream os = t.getResponseBody();
        os.write(b);
        os.close();
    }

    static void sendPdfFile(HttpExchange t, String filename, byte[] data) throws IOException {
        t.getResponseHeaders().set("Content-Type", "application/pdf");
        t.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        t.sendResponseHeaders(200, data.length);
        OutputStream os = t.getResponseBody();
        os.write(data);
        os.close();
    }

    static String resultPage(String titre, String bg, String color, String content) {
        return "<html><head><meta charset='UTF-8'><title>" + titre + "</title>"
            + "<style>"
            + "body{font-family:'Segoe UI',system-ui,sans-serif;background:#f8fafc;margin:0;color:#0f172a}"
            + ".topbar{background:#0f172a;padding:16px 32px;display:flex;align-items:center;box-shadow:0 4px 6px -1px rgba(0,0,0,0.1)}"
            + ".topbar a{color:rgba(255,255,255,0.8);text-decoration:none;font-size:13px;font-weight:500}"
            + ".topbar a:hover{color:white}"
            + ".topbar h2{color:white;font-size:14px;font-weight:600;margin:0;margin-left:16px;}"
            + ".content{max-width:860px;margin:0 auto;padding:28px 24px;box-sizing:border-box}"
            + ".result-header{background:" + bg + ";border-radius:12px;padding:14px 18px;margin-bottom:20px}"
            + ".result-header span{font-size:11px;font-weight:600;color:" + color + ";text-transform:uppercase;letter-spacing:1.5px}"
            + "</style></head><body>"
            + "<div class='topbar'><a href='javascript:history.back()'>&#8592; Retour</a><h2>" + titre + "</h2></div>"
            + "<div class='content'>"
            + "<div class='result-header'><span>Résultat &mdash; " + titre + "</span></div>"
            + content + "</div></body></html>";
    }

    static void sendError(HttpExchange t, String msg) throws IOException {
        sendHtml(t, resultPage("Erreur","#991B1B","#FEE2E2",
            "<div style='background:#fff;border:1.5px solid #FCA5A5;border-radius:12px;padding:24px;text-align:left;box-shadow:0 1px 3px rgba(0,0,0,0.05)'>"
            + "<h3 style='margin:0 0 8px 0;color:#991B1B;font-size:18px;font-weight:600'>Une erreur est survenue</h3>"
            + "<p style='margin:0;color:#64748b;font-size:14px;line-height:1.5'>" + msg + "</p>"
            + "</div>"
        ));
    }

    // ── PARSEUR DE MULTIPART (D'ORIGINE) ──────────────────────────────────
    static class MultipartParser {
        private final Map<String, byte[]> files = new HashMap<>();
        private final Map<String, String> fields = new HashMap<>();

        public MultipartParser(HttpExchange t) throws IOException {
            String contentType = t.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.contains("multipart/form-data")) return;
            String boundary = "";
            for (String param : contentType.split(";")) {
                if (param.trim().startsWith("boundary=")) {
                    boundary = param.split("=")[1].trim();
                }
            }
            if (boundary.isEmpty()) return;
            byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
            InputStream is = t.getRequestBody();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) { bos.write(buffer, 0, len); }
            byte[] bodyBytes = bos.toByteArray();
            int index = 0;
            while (index < bodyBytes.length) {
                index = findBytes(bodyBytes, boundaryBytes, index);
                if (index == -1) break;
                index += boundaryBytes.length;
                if (index < bodyBytes.length && bodyBytes[index] == '-' && bodyBytes[index + 1] == '-') break;
                index += 2; 
                int headerEnd = findBytes(bodyBytes, new byte[]{13, 10, 13, 10}, index);
                if (headerEnd == -1) break;
                String headers = new String(bodyBytes, index, headerEnd - index, StandardCharsets.UTF_8);
                index = headerEnd + 4;
                int nextBoundary = findBytes(bodyBytes, boundaryBytes, index);
                if (nextBoundary == -1) break;
                int partLength = nextBoundary - index - 2; 
                byte[] partData = new byte[partLength];
                System.arraycopy(bodyBytes, index, partData, 0, partLength);
                parsePart(headers, partData);
                index = nextBoundary;
            }
        }

        private int findBytes(byte[] src, byte[] target, int start) {
            for (int i = start; i <= src.length - target.length; i++) {
                boolean found = true;
                for (int j = 0; j < target.length; j++) {
                    if (src[i + j] != target[j]) { found = false; break; }
                }
                if (found) return i;
            }
            return -1;
        }

        private void parsePart(String headers, byte[] data) throws IOException {
            String name = "";
            String filename = "";
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-disposition:")) {
                    for (String param : line.split(";")) {
                        if (param.trim().startsWith("name=")) {
                            name = param.split("=")[1].replace("\"", "").trim();
                        }
                        if (param.trim().startsWith("filename=")) {
                            filename = param.split("=")[1].replace("\"", "").trim();
                        }
                    }
                }
            }
            if (!filename.isEmpty()) { files.put(name, data); }
            else { fields.put(name, new String(data, StandardCharsets.UTF_8).trim()); }
        }

        public byte[] getFile(String name) { return files.get(name); }
        public String getString(String name) { return fields.get(name); }
    }
}
