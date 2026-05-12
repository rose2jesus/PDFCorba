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
    private static int nbCrees = 0, nbExtractions = 0, nbFusions = 0, nbProtections = 0;

    // ── Sessions et utilisateurs ─────────────────────────
    private static final Map<String, String> SESSIONS = new HashMap<>();
    private static final Map<String, String> USERS = new HashMap<>();
    private static final Map<String, String> ROLES = new HashMap<>();

    static {
        USERS.put("admin@pdf.com", "admin123");
        USERS.put("user@pdf.com",  "user123");
        ROLES.put("admin@pdf.com", "admin");
        ROLES.put("user@pdf.com",  "user");
    }

    // ── Helpers session ──────────────────────────────────
    static String getSession(HttpExchange t) {
        String cookie = t.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String c : cookie.split(";")) {
            c = c.trim();
            if (c.startsWith("session=")) return c.substring(8);
        }
        return null;
    }

    static String getRole(HttpExchange t) {
        String sid = getSession(t);
        if (sid == null) return null;
        return SESSIONS.get(sid);
    }

    static boolean isLoggedIn(HttpExchange t) { return getRole(t) != null; }
    static boolean isAdmin(HttpExchange t)    { return "admin".equals(getRole(t)); }

    static void redirect(HttpExchange t, String url) throws IOException {
        t.getResponseHeaders().set("Location", url);
        t.sendResponseHeaders(302, -1);
        t.getResponseBody().close();
    }

    // ── Main ─────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        try {
            ORB orb = ORB.init(args, null);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));

            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.createContext("/",         new UIHandler());
            server.createContext("/login",    new LoginHandler());
            server.createContext("/logout",   new LogoutHandler());
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

    // ════════════════════════════════════════════════════
    //  PAGE DE CONNEXION
    // ════════════════════════════════════════════════════
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                // Traiter le formulaire
                byte[] body = readAllBytes(t.getRequestBody());
                Map<String,String> params = parseForm(new String(body, "UTF-8"));
                String email = params.getOrDefault("email", "");
                String pass  = params.getOrDefault("password", "");

                if (USERS.containsKey(email) && USERS.get(email).equals(pass)) {
                    String sid = UUID.randomUUID().toString();
                    SESSIONS.put(sid, ROLES.get(email));
                    t.getResponseHeaders().set("Set-Cookie", "session=" + sid + "; Path=/; HttpOnly");
                    redirect(t, "/");
                } else {
                    sendHtml(t, loginPage("Email ou mot de passe incorrect."));
                }
            } else {
                if (isLoggedIn(t)) { redirect(t, "/"); return; }
                sendHtml(t, loginPage(null));
            }
        }
    }

    static String loginPage(String error) {
        return "<!DOCTYPE html><html lang='fr'><head><meta charset='UTF-8'>"
            + "<title>Connexion — Studio PDF CORBA</title>"
            + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&display=swap' rel='stylesheet'>"
            + "<style>"
            + "*{box-sizing:border-box;margin:0;padding:0;font-family:'Inter',sans-serif}"
            + "body{background:linear-gradient(135deg,#4F1D96 0%,#6D28D9 50%,#2D3B8E 100%);min-height:100vh;display:flex;align-items:center;justify-content:center}"
            + ".card{background:white;border-radius:24px;padding:48px 40px;width:100%;max-width:420px;box-shadow:0 40px 80px rgba(79,29,150,0.3)}"
            + ".logo{text-align:center;margin-bottom:32px}"
            + ".logo h1{font-size:28px;font-weight:800;color:#4F1D96;margin-bottom:4px}"
            + ".logo p{font-size:13px;color:#9CA3AF}"
            + ".badge{display:inline-block;background:#EDE9FE;color:#5B21B6;padding:4px 12px;border-radius:20px;font-size:11px;font-weight:600;margin-top:8px}"
            + "label{display:block;font-size:12px;font-weight:600;color:#6B7280;margin-bottom:6px;margin-top:18px;text-transform:uppercase;letter-spacing:1px}"
            + "input{width:100%;padding:12px 16px;border:2px solid #EDE9FE;border-radius:12px;font-size:14px;color:#1E1B4B;outline:none;transition:0.2s;font-family:'Inter',sans-serif}"
            + "input:focus{border-color:#7C3AED;box-shadow:0 0 0 3px rgba(124,58,237,0.1)}"
            + ".btn{width:100%;background:linear-gradient(135deg,#6D28D9,#7C3AED);color:white;border:none;padding:14px;border-radius:12px;font-size:15px;font-weight:700;cursor:pointer;margin-top:24px;transition:0.2s}"
            + ".btn:hover{opacity:0.92;transform:translateY(-1px)}"
            + ".error{background:#FEE2E2;border:1px solid #FCA5A5;color:#DC2626;padding:12px 16px;border-radius:10px;font-size:13px;margin-top:16px;text-align:center}"
            + ".hint{background:#F5F3FF;border-radius:12px;padding:14px 16px;margin-top:20px}"
            + ".hint p{font-size:11px;color:#7C3AED;font-weight:600;margin-bottom:6px;text-transform:uppercase;letter-spacing:1px}"
            + ".hint table{width:100%;font-size:12px;color:#1E1B4B}"
            + ".hint td{padding:3px 6px}"
            + ".hint .role{font-weight:700;color:#4F1D96}"
            + "</style></head><body>"
            + "<div class='card'>"
            + "<div class='logo'>"
            + "<h1>Studio PDF CORBA</h1>"
            + "<p>Gestionnaire de documents distribué</p>"
            + "<span class='badge'>Java 8 × CORBA × PDFBox</span>"
            + "</div>"
            + (error != null ? "<div class='error'>" + error + "</div>" : "")
            + "<form method='post' action='/login'>"
            + "<label>Adresse email</label>"
            + "<input type='email' name='email' placeholder='votre@email.com' required>"
            + "<label>Mot de passe</label>"
            + "<input type='password' name='password' placeholder='••••••••' required>"
            + "<button class='btn' type='submit'>Se connecter</button>"
            + "</form>"
            + "<div class='hint'>"
            + "<p>Comptes de démonstration</p>"
            + "<table>"
            + "<tr><td class='role'>Administrateur</td><td>admin@pdf.com</td><td>admin123</td></tr>"
            + "<tr><td class='role'>Utilisateur</td><td>user@pdf.com</td><td>user123</td></tr>"
            + "</table>"
            + "</div>"
            + "</div></body></html>";
    }

    // ════════════════════════════════════════════════════
    //  LOGOUT
    // ════════════════════════════════════════════════════
    static class LogoutHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String sid = getSession(t);
            if (sid != null) SESSIONS.remove(sid);
            t.getResponseHeaders().set("Set-Cookie", "session=; Path=/; Max-Age=0");
            redirect(t, "/login");
        }
    }

    // ════════════════════════════════════════════════════
    //  CSS GLOBAL
    // ════════════════════════════════════════════════════
    static final String CSS =
        "<style>"
        + "*{box-sizing:border-box;margin:0;padding:0;font-family:'Inter',sans-serif}"
        + "body{background:#F5F3FF;min-height:100vh}"
        + ".topbar{background:linear-gradient(135deg,#4F1D96 0%,#6D28D9 40%,#7C3AED 70%,#8B5CF6 100%);padding:16px 28px 70px;position:relative;overflow:hidden}"
        + ".topbar::before{content:'';position:absolute;top:-60px;right:-60px;width:220px;height:220px;background:rgba(255,255,255,0.06);border-radius:50%}"
        + ".topbar-row{display:flex;justify-content:space-between;align-items:center;position:relative;z-index:1}"
        + ".topbar h1{font-size:18px;font-weight:700;color:#fff}"
        + ".topbar p{font-size:12px;color:rgba(255,255,255,0.7)}"
        + ".topbar-right{display:flex;align-items:center;gap:12px}"
        + ".role-badge{background:rgba(255,255,255,0.2);border:1px solid rgba(255,255,255,0.3);color:#fff;padding:4px 12px;border-radius:20px;font-size:11px;font-weight:600}"
        + ".role-badge.admin{background:rgba(250,204,21,0.2);border-color:rgba(250,204,21,0.5);color:#FDE68A}"
        + ".logout-btn{background:rgba(255,255,255,0.15);border:1px solid rgba(255,255,255,0.3);color:#fff;padding:6px 14px;border-radius:8px;font-size:12px;font-weight:600;cursor:pointer;text-decoration:none}"
        + ".logout-btn:hover{background:rgba(255,255,255,0.25)}"
        + ".main{padding:0 24px 32px;margin-top:-40px;position:relative;z-index:2}"
        + ".stats{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:24px}"
        + ".stat{border-radius:16px;padding:18px}"
        + ".stat-n{font-size:24px;font-weight:700;margin-bottom:4px}"
        + ".stat-l{font-size:10px;font-weight:600;letter-spacing:1px;text-transform:uppercase;opacity:0.75;margin-bottom:2px}"
        + ".stat-s{font-size:10px;opacity:0.6}"
        + ".st1{background:linear-gradient(135deg,#EDE9FE,#DDD6FE)}.st1 .stat-n,.st1 .stat-l,.st1 .stat-s{color:#4C1D95}"
        + ".st2{background:linear-gradient(135deg,#E0F2FE,#BAE6FD)}.st2 .stat-n,.st2 .stat-l,.st2 .stat-s{color:#0C4A6E}"
        + ".st3{background:linear-gradient(135deg,#DCFCE7,#BBF7D0)}.st3 .stat-n,.st3 .stat-l,.st3 .stat-s{color:#14532D}"
        + ".st4{background:linear-gradient(135deg,#FEF3C7,#FDE68A)}.st4 .stat-n,.st4 .stat-l,.st4 .stat-s{color:#78350F}"
        + ".sec-label{font-size:11px;font-weight:600;letter-spacing:2px;text-transform:uppercase;color:#9CA3AF;margin-bottom:14px}"
        + ".tools{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:24px}"
        + ".tc{background:#fff;border-radius:14px;padding:18px 14px;cursor:pointer;border:1.5px solid transparent;transition:all 0.2s}"
        + ".tc:hover{border-color:#A78BFA;transform:translateY(-3px);box-shadow:0 8px 24px rgba(124,58,237,0.12)}"
        + ".tc-bar{height:3px;border-radius:2px;margin-bottom:14px;width:32px}"
        + ".tc h3{font-size:13px;font-weight:600;color:#1E1B4B;margin-bottom:5px}"
        + ".tc p{font-size:11px;color:#9CA3AF;line-height:1.5}"
        + ".tc-tag{display:inline-block;font-size:10px;font-weight:600;padding:3px 9px;border-radius:10px;margin-top:10px}"
        + ".bottom{display:grid;grid-template-columns:1.1fr 0.9fr;gap:14px}"
        + ".create{background:#fff;border-radius:16px;padding:24px}"
        + ".create h2{font-size:15px;font-weight:700;color:#1E1B4B;margin-bottom:3px}"
        + ".create .sub{font-size:12px;color:#9CA3AF;margin-bottom:18px}"
        + ".inp{background:#F8F7FF;border:1.5px solid #EDE9FE;border-radius:10px;padding:10px 14px;font-size:13px;color:#1E1B4B;width:100%;font-family:inherit;margin-bottom:10px;outline:none}"
        + ".inp:focus{border-color:#7C3AED}"
        + ".inp-row{display:grid;grid-template-columns:1fr 1fr;gap:10px}"
        + "textarea.inp{resize:none;height:68px}"
        + ".btn-gen{width:100%;background:linear-gradient(135deg,#6D28D9,#7C3AED);color:#fff;border:none;padding:12px;border-radius:10px;font-size:13px;font-weight:700;cursor:pointer;margin-top:4px}"
        + ".btn-gen:hover{opacity:0.92}"
        + ".guide{background:#fff;border-radius:16px;padding:24px}"
        + ".guide h2{font-size:15px;font-weight:700;color:#1E1B4B;margin-bottom:16px}"
        + ".ai{display:flex;align-items:center;gap:12px;padding:10px 0;border-bottom:1px solid #F5F3FF}"
        + ".ai:last-child{border-bottom:none;padding-bottom:0}"
        + ".ai-ico{width:34px;height:34px;border-radius:9px;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:700;flex-shrink:0}"
        + ".ai-info{flex:1}"
        + ".ai-info b{font-size:12px;font-weight:600;color:#1E1B4B;display:block}"
        + ".ai-info small{font-size:11px;color:#9CA3AF}"
        + ".overlay{display:none;position:fixed;inset:0;background:rgba(79,29,150,0.2);backdrop-filter:blur(8px);z-index:100;align-items:center;justify-content:center}"
        + ".overlay.active{display:flex}"
        + ".modal{background:#fff;border-radius:20px;padding:32px;width:90%;max-width:440px;box-shadow:0 40px 80px rgba(79,29,150,0.15)}"
        + ".modal h2{font-size:15px;font-weight:700;color:#1E1B4B;margin-bottom:4px}"
        + ".modal .msub{font-size:12px;color:#9CA3AF;margin-bottom:20px}"
        + ".modal label{font-size:11px;font-weight:600;color:#6B7280;display:block;margin-bottom:5px;margin-top:12px;text-transform:uppercase;letter-spacing:1px}"
        + ".modal input,.modal select{width:100%;padding:10px 14px;border:1.5px solid #EDE9FE;border-radius:10px;font-size:13px;color:#1E1B4B;outline:none;font-family:inherit;background:#F8F7FF}"
        + ".modal input:focus,.modal select:focus{border-color:#7C3AED}"
        + ".upload-zone{border:2px dashed #DDD6FE;border-radius:12px;padding:24px;text-align:center;cursor:pointer;background:#F8F7FF;margin:8px 0;transition:0.2s}"
        + ".upload-zone:hover{border-color:#7C3AED;background:#F3E8FF}"
        + ".upload-zone p{font-size:13px;color:#7C3AED;font-weight:600;margin-bottom:4px}"
        + ".upload-zone small{font-size:11px;color:#9CA3AF}"
        + ".btn-row{display:flex;gap:10px;margin-top:20px}"
        + ".btn-ok{flex:1;background:linear-gradient(135deg,#6D28D9,#7C3AED);color:#fff;border:none;padding:12px;border-radius:10px;font-weight:700;font-size:13px;cursor:pointer}"
        + ".btn-cancel{flex:1;background:#F5F3FF;color:#6B7280;border:none;padding:12px;border-radius:10px;font-weight:600;font-size:13px;cursor:pointer}"
        + ".admin-section{background:#fff;border-radius:16px;padding:24px;margin-bottom:24px;border-left:4px solid #FDE68A}"
        + ".admin-section h2{font-size:15px;font-weight:700;color:#1E1B4B;margin-bottom:16px;display:flex;align-items:center;gap:8px}"
        + ".admin-badge{background:#FEF3C7;color:#92400E;padding:2px 10px;border-radius:20px;font-size:10px;font-weight:700}"
        + ".user-table{width:100%;border-collapse:collapse;font-size:13px}"
        + ".user-table th{background:#F5F3FF;padding:10px 14px;text-align:left;font-size:11px;font-weight:700;color:#6B7280;text-transform:uppercase;letter-spacing:1px}"
        + ".user-table td{padding:10px 14px;border-bottom:1px solid #F5F3FF;color:#1E1B4B}"
        + ".user-table tr:last-child td{border-bottom:none}"
        + ".tag-admin{background:#FEF3C7;color:#92400E;padding:2px 8px;border-radius:10px;font-size:10px;font-weight:700}"
        + ".tag-user{background:#EDE9FE;color:#5B21B6;padding:2px 8px;border-radius:10px;font-size:10px;font-weight:700}"
        + ".footer{text-align:center;padding:20px;color:#C4B5FD;font-size:11px}"
        + "</style>";

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

    // ════════════════════════════════════════════════════
    //  PAGE PRINCIPALE
    // ════════════════════════════════════════════════════
    static class UIHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isLoggedIn(t)) { redirect(t, "/login"); return; }
            boolean admin = isAdmin(t);
            String role = admin ? "admin" : "user";

            String html = "<!DOCTYPE html><html lang='fr'><head>"
                + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Studio PDF CORBA</title>"
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&display=swap' rel='stylesheet'>"
                + CSS + "</head><body>"

                // TOPBAR
                + "<div class='topbar'><div class='topbar-row'>"
                + "<div><h1>Studio PDF CORBA</h1><p>Gestionnaire de documents distribue &mdash; Java 8 &times; PDFBox</p></div>"
                + "<div class='topbar-right'>"
                + "<span class='role-badge " + role + "'>" + (admin ? "Administrateur" : "Utilisateur") + "</span>"
                + "<a href='/logout' class='logout-btn'>Deconnexion</a>"
                + "</div></div></div>"

                + "<div class='main'>";

            // STATS (admin seulement)
            if (admin) {
                html += "<div class='stats'>"
                    + "<div class='stat st1'><div class='stat-l'>PDFs crees</div><div class='stat-n'>" + nbCrees + "</div><div class='stat-s'>Session actuelle</div></div>"
                    + "<div class='stat st2'><div class='stat-l'>Extractions</div><div class='stat-n'>" + nbExtractions + "</div><div class='stat-s'>Session actuelle</div></div>"
                    + "<div class='stat st3'><div class='stat-l'>Fusions</div><div class='stat-n'>" + nbFusions + "</div><div class='stat-s'>Session actuelle</div></div>"
                    + "<div class='stat st4'><div class='stat-l'>Proteges</div><div class='stat-n'>" + nbProtections + "</div><div class='stat-s'>Session actuelle</div></div>"
                    + "</div>";
            }

            // SECTION ADMIN — Gestion utilisateurs
            if (admin) {
                html += "<div class='admin-section'>"
                    + "<h2>Gestion des utilisateurs <span class='admin-badge'>ADMIN</span></h2>"
                    + "<table class='user-table'>"
                    + "<thead><tr><th>Email</th><th>Role</th><th>Statut</th></tr></thead>"
                    + "<tbody>"
                    + "<tr><td>admin@pdf.com</td><td><span class='tag-admin'>Administrateur</span></td><td>Actif</td></tr>"
                    + "<tr><td>user@pdf.com</td><td><span class='tag-user'>Utilisateur</span></td><td>Actif</td></tr>"
                    + "</tbody></table></div>";
            }

            // OUTILS — Admin : 12 outils, User : 4 outils
            html += "<p class='sec-label'>Outils disponibles</p>"
                + "<div class='tools'>"
                + tc("linear-gradient(90deg,#7C3AED,#A78BFA)", "Extraire texte",  "Lisez le contenu texte",       "Analyse",    "background:#EDE9FE;color:#5B21B6", "m-extract")
                + tc("linear-gradient(90deg,#0EA5E9,#7DD3FC)", "En images",       "PDF vers PNG haute qualite",   "Conversion", "background:#E0F2FE;color:#0369A1", "m-image")
                + tc("linear-gradient(90deg,#14B8A6,#99F6E4)", "Creation PDF",    "Nouveau document via CORBA",   "Creation",   "background:#CCFBF1;color:#0F766E", "m-create");

            if (admin) {
                html += tc("linear-gradient(90deg,#10B981,#6EE7B7)", "Proteger",       "Chiffrement mot de passe",      "Securite",    "background:#D1FAE5;color:#065F46", "m-protect")
                    + tc("linear-gradient(90deg,#F59E0B,#FCD34D)", "Fusionner",       "Combiner plusieurs PDFs",       "Assemblage",  "background:#FEF3C7;color:#92400E", "m-merge")
                    + tc("linear-gradient(90deg,#EC4899,#F9A8D4)", "Decouper",        "Diviser en plusieurs parties",  "Edition",     "background:#FCE7F3;color:#9D174D", "m-split")
                    + tc("linear-gradient(90deg,#EF4444,#FCA5A5)", "Supprimer pages", "Retirer des pages precises",    "Edition",     "background:#FEE2E2;color:#991B1B", "m-delete")
                    + tc("linear-gradient(90deg,#8B5CF6,#C4B5FD)", "Extraire pages",  "Selectionner et exporter",      "Extraction",  "background:#EDE9FE;color:#5B21B6", "m-pages")
                    + tc("linear-gradient(90deg,#6366F1,#A5B4FC)", "Compresser",      "Reduire la taille",             "Optimisation","background:#EEF2FF;color:#3730A3", "m-compress")
                    + tc("linear-gradient(90deg,#D946EF,#F0ABFC)", "Metadonnees",     "Lire infos du document",        "Info",        "background:#FDF4FF;color:#86198F", "m-meta")
                    + tc("linear-gradient(90deg,#0284C7,#7DD3FC)", "QR Code",         "Inserer un QR code",            "Enrichissement","background:#E0F2FE;color:#0C4A6E","m-qrcode")
                    + tc("linear-gradient(90deg,#059669,#6EE7B7)", "Signer",          "Signature numerique RSA",       "Securite",    "background:#D1FAE5;color:#065F46", "m-sign");
            }

            html += "</div>";

            // BAS DE PAGE
            html += "<div class='bottom'>"
                + "<div class='create'><h2>Creer un PDF</h2>"
                + "<p class='sub'>Generez instantanement via le serveur CORBA</p>"
                + "<form action='/create' method='get'>"
                + "<div class='inp-row'>"
                + "<input class='inp' name='titre' placeholder='Titre...'>"
                + "<input class='inp' name='auteur' placeholder='Auteur...'>"
                + "</div>"
                + "<textarea class='inp' name='corps' placeholder='Contenu du document...'></textarea>"
                + "<button class='btn-gen' type='submit'>Generer le PDF</button>"
                + "</form></div>"

                + "<div class='guide'><h2>Guide rapide</h2>"
                + guideItem("#EDE9FE","#5B21B6","TXT","Extraire texte","Uploadez un PDF et obtenez le texte")
                + guideItem("#E0F2FE","#0369A1","IMG","Convertir","Choisissez 72/150/300 DPI selon besoin");

            if (admin) {
                html += guideItem("#D1FAE5","#065F46","FUS","Fusionner","Selectionnez plusieurs PDFs a la fois")
                    + guideItem("#FEF3C7","#92400E","QR","QR Code","Entrez x,y pour positionner le QR")
                    + guideItem("#CCFBF1","#0F766E","SIG","Signer","Entrez nom, raison et lieu de signature");
            }

            html += "</div></div></div>"
                + "<div class='footer'>Studio PDF CORBA &mdash; Java 8 &times; PDFBox 2.0 &times; ZXing &times; BouncyCastle</div>";

            // MODALS
            html += modal("m-extract","Extraire le texte","Obtenez le contenu textuel","/extract", uploadZone("fi-extract","doc",false))
                + modal("m-image","Convertir en images","PDF vers PNG haute qualite","/image",
                    uploadZone("fi-image","doc",false)
                    + "<label>Resolution</label>"
                    + "<select name='dpi'><option value='72'>72 DPI</option><option value='150' selected>150 DPI</option><option value='300'>300 DPI</option></select>")
                + modal("m-create","Creer un PDF","Generez un PDF personnalise","/create",
                    "<label>Titre</label><input type='text' name='titre' placeholder='Titre...'>"
                    + "<label>Contenu</label><input type='text' name='corps' placeholder='Contenu...'>");

            if (admin) {
                html += modal("m-protect","Proteger le PDF","Securisez avec un mot de passe","/protect",
                        uploadZone("fi-protect","doc",false)
                        + "<label>Mot de passe</label><input type='password' name='mdp' placeholder='Mot de passe...'>")
                    + modal("m-merge","Fusionner des PDFs","Combinez plusieurs fichiers","/merge", uploadZone("fi-merge","docs",true))
                    + modal("m-split","Decouper le PDF","Divisez en plusieurs parties","/split",
                        uploadZone("fi-split","doc",false)
                        + "<label>Pages par partie</label><input type='number' name='nb' value='1' min='1'>")
                    + modal("m-delete","Supprimer des pages","Retirez les pages indesirables","/delete",
                        uploadZone("fi-delete","doc",false)
                        + "<label>Pages a supprimer (ex: 1,3,5)</label><input type='text' name='pages' placeholder='1,2,3...'>")
                    + modal("m-pages","Extraire des pages","Selectionnez les pages a conserver","/pages",
                        uploadZone("fi-pages","doc",false)
                        + "<label>Pages a extraire (ex: 1,3,5)</label><input type='text' name='pages' placeholder='1,2,3...'>")
                    + modal("m-compress","Compresser le PDF","Reduire la taille du fichier","/compress", uploadZone("fi-compress","doc",false))
                    + modal("m-meta","Lire les metadonnees","Afficher les informations","/meta", uploadZone("fi-meta","doc",false))
                    + modal("m-qrcode","Ajouter un QR Code","Inserez un QR code dans le PDF","/qrcode",
                        uploadZone("fi-qrcode","doc",false)
                        + "<label>Contenu du QR Code</label><input type='text' name='contenu' placeholder='https://...'>"
                        + "<label>Page (commence a 0)</label><input type='number' name='page' value='0' min='0'>"
                        + "<label>Position X</label><input type='number' name='x' value='400' min='0'>"
                        + "<label>Position Y</label><input type='number' name='y' value='50' min='0'>")
                    + modal("m-sign","Signature numerique","Signez votre PDF avec RSA","/sign",
                        uploadZone("fi-sign","doc",false)
                        + "<label>Nom du signataire</label><input type='text' name='nom' placeholder='Votre nom...'>"
                        + "<label>Raison</label><input type='text' name='raison' placeholder='Ex: Approbation...'>"
                        + "<label>Lieu</label><input type='text' name='lieu' placeholder='Ex: Dakar...'>");
            }

            html += "<script>"
                + "function openM(id){document.getElementById(id).classList.add('active')}"
                + "function closeM(id){document.getElementById(id).classList.remove('active')}"
                + "function showName(id,input){document.getElementById(id).textContent=input.files.length>1?input.files.length+' fichiers':input.files[0]?.name||'Aucun'}"
                + "document.querySelectorAll('.overlay').forEach(function(o){o.addEventListener('click',function(e){if(e.target===this)this.classList.remove('active')})})"
                + "</script></body></html>";

            sendHtml(t, html);
        }

        String guideItem(String bg, String color, String label, String title, String desc) {
            return "<div class='ai'>"
                + "<div class='ai-ico' style='background:" + bg + ";color:" + color + "'>" + label + "</div>"
                + "<div class='ai-info'><b>" + title + "</b><small>" + desc + "</small></div>"
                + "</div>";
        }
    }

    // ════════════════════════════════════════════════════
    //  HANDLERS PDF
    // ════════════════════════════════════════════════════

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
                sendHtml(t, resultPage("Texte extrait","#5B21B6","#EDE9FE",
                    "<pre style='white-space:pre-wrap;color:#1E1B4B;font-size:13px;line-height:1.8;font-family:Inter,sans-serif'>" + escapeHtml(texte) + "</pre>"));
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
                    sb.append("<div style='margin-bottom:16px;border-radius:12px;overflow:hidden;border:1px solid #EDE9FE'>")
                      .append("<p style='background:#F5F3FF;padding:8px 14px;font-size:11px;color:#7C3AED;font-weight:600'>Page ").append(i+1).append("</p>")
                      .append("<img src='data:image/png;base64,").append(b64).append("' style='width:100%;display:block'>")
                      .append("</div>");
                }
                sendHtml(t, resultPage(images.length + " page(s) convertie(s)","#0369A1","#E0F2FE", sb.toString()));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class ProtectHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isAdmin(t)) { sendError(t, "Acces reserve a l'administrateur"); return; }
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
            if (!isAdmin(t)) { sendError(t, "Acces reserve a l'administrateur"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                List<byte[]> docs = mp.fileList.get("docs");
                if (docs==null||docs.size()<2) throw new Exception("Selectionnez au moins 2 PDFs");
                byte[] result = pdfRef.fusionnerPDFs(docs.toArray(new byte[0][]));
                nbFusions++;
                sendPdf(t, result, "fusion.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class SplitHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isAdmin(t)) { sendError(t, "Acces reserve a l'administrateur"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                int nb = Integer.parseInt(mp.fields.getOrDefault("nb","1"));
                byte[][] parts = pdfRef.decouperPDF(mp.files.get("doc"), nb);
                StringBuilder sb = new StringBuilder();
                for (int i=0; i<parts.length; i++) {
                    String b64 = Base64.getEncoder().encodeToString(parts[i]);
                    sb.append("<div style='display:flex;align-items:center;gap:14px;padding:14px 18px;background:#fff;border:1.5px solid #EDE9FE;border-radius:12px;margin-bottom:10px'>")
                      .append("<span style='flex:1;font-size:13px;font-weight:600;color:#1E1B4B'>Partie ").append(i+1).append(" &mdash; ").append(parts[i].length/1024).append(" Ko</span>")
                      .append("<a href='data:application/pdf;base64,").append(b64).append("' download='partie_").append(i+1).append(".pdf' style='background:linear-gradient(135deg,#6D28D9,#7C3AED);color:#fff;padding:7px 16px;border-radius:8px;text-decoration:none;font-size:12px;font-weight:700'>Telecharger</a>")
                      .append("</div>");
                }
                sendHtml(t, resultPage("PDF decoupe","#9D174D","#FCE7F3", sb.toString()));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class DeleteHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isAdmin(t)) { sendError(t, "Acces reserve a l'administrateur"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                int[] pages = parsePages(mp.fields.getOrDefault("pages","1"));
                sendPdf(t, pdfRef.supprimerPages(mp.files.get("doc"), pages), "sans_pages.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class ExtractPagesHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isAdmin(t)) { sendError(t, "Acces reserve a l'administrateur"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                int[] pages = parsePages(mp.fields.getOrDefault("pages","1"));
                sendPdf(t, pdfRef.extrairePages(mp.files.get("doc"), pages), "pages_extraites.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class CompressHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isAdmin(t)) { sendError(t, "Acces reserve a l'administrateur"); return; }
            try {
                byte[] pdf = parseMultipart(t).files.values().iterator().next();
                byte[] result = pdfRef.compresserPDF(pdf);
                sendHtml(t, resultPage("PDF compresse","#0F766E","#CCFBF1",
                    "<div style='background:#fff;border:1.5px solid #CCFBF1;border-radius:14px;padding:24px'>"
                    + "<p style='font-size:11px;font-weight:700;letter-spacing:2px;color:#0F766E;text-transform:uppercase;margin-bottom:16px'>Resultat</p>"
                    + "<p style='font-size:13px;color:#1E1B4B;margin-bottom:16px'>Taille originale : <b>" + (pdf.length/1024) + " Ko</b> &rarr; Taille compresse : <b>" + (result.length/1024) + " Ko</b></p>"
                    + "<a href='data:application/pdf;base64," + Base64.getEncoder().encodeToString(result)
                    + "' download='compresse.pdf' style='background:linear-gradient(135deg,#059669,#10B981);color:#fff;padding:10px 24px;border-radius:10px;text-decoration:none;font-size:13px;font-weight:700'>Telecharger</a>"
                    + "</div>"));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class MetaReadHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isAdmin(t)) { sendError(t, "Acces reserve a l'administrateur"); return; }
            try {
                byte[] pdf = parseMultipart(t).files.values().iterator().next();
                String meta = pdfRef.lireMetadonnees(pdf);
                sendHtml(t, resultPage("Metadonnees","#3730A3","#EEF2FF",
                    "<pre style='white-space:pre-wrap;color:#1E1B4B;font-size:13px;line-height:2;font-family:Inter,sans-serif'>" + escapeHtml(meta) + "</pre>"));
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class MetaModHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isAdmin(t)) { sendError(t, "Acces reserve a l'administrateur"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                byte[] result = pdfRef.modifierMetadonnees(mp.files.get("doc"),
                    mp.fields.getOrDefault("titre",""), mp.fields.getOrDefault("auteur",""), mp.fields.getOrDefault("sujet",""));
                sendPdf(t, result, "modifie.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class QRCodeHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isAdmin(t)) { sendError(t, "Acces reserve a l'administrateur"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                byte[] result = pdfRef.ajouterQRCode(mp.files.get("doc"),
                    mp.fields.getOrDefault("contenu","https://corba.pdf"),
                    Integer.parseInt(mp.fields.getOrDefault("page","0")),
                    Integer.parseInt(mp.fields.getOrDefault("x","400")),
                    Integer.parseInt(mp.fields.getOrDefault("y","50")));
                sendPdf(t, result, "avec_qrcode.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    static class SignHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (!isAdmin(t)) { sendError(t, "Acces reserve a l'administrateur"); return; }
            try {
                MultipartData mp = parseMultipart(t);
                byte[] result = pdfRef.signerPDF(mp.files.get("doc"),
                    mp.fields.getOrDefault("nom","Signataire"),
                    mp.fields.getOrDefault("raison","Approbation"),
                    mp.fields.getOrDefault("lieu","Dakar"));
                sendPdf(t, result, "signe.pdf");
            } catch (Exception e) { sendError(t, e.getMessage()); }
        }
    }

    // ════════════════════════════════════════════════════
    //  UTILITAIRES
    // ════════════════════════════════════════════════════

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

    static Map<String,String> parseForm(String body) throws Exception {
        Map<String,String> m = new HashMap<>();
        if (body == null) return m;
        for (String s : body.split("&")) {
            String[] kv = s.split("=", 2);
            if (kv.length > 1) m.put(URLDecoder.decode(kv[0],"UTF-8"), URLDecoder.decode(kv[1],"UTF-8"));
        }
        return m;
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
        if (q == null) return m;
        for (String s : q.split("&")) {
            String[] kv = s.split("=", 2);
            if (kv.length > 1) m.put(kv[0], URLDecoder.decode(kv[1], "UTF-8"));
        }
        return m;
    }

    static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
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
            + "<style>*{box-sizing:border-box;margin:0;padding:0;font-family:'Inter',sans-serif}body{background:#F5F3FF;min-height:100vh}"
            + ".topbar{background:linear-gradient(135deg,#4F1D96,#7C3AED);padding:18px 28px;display:flex;align-items:center;gap:16px}"
            + ".topbar a{color:rgba(255,255,255,0.85);text-decoration:none;font-size:13px;font-weight:600}"
            + ".topbar h2{color:white;font-size:14px;font-weight:700}"
            + ".content{max-width:860px;margin:0 auto;padding:28px 24px}"
            + ".result-header{background:" + bg + ";border-radius:12px;padding:14px 18px;margin-bottom:20px}"
            + ".result-header span{font-size:11px;font-weight:700;color:" + color + ";text-transform:uppercase;letter-spacing:1.5px}"
            + "</style></head><body>"
            + "<div class='topbar'><a href='/'>&#8592; Retour</a>&nbsp;&nbsp;<h2>" + titre + "</h2></div>"
            + "<div class='content'>"
            + "<div class='result-header'><span>Resultat &mdash; " + titre + "</span></div>"
            + content + "</div></body></html>";
    }

    static void sendError(HttpExchange t, String msg) throws IOException {
        sendHtml(t, resultPage("Erreur","#991B1B","#FEE2E2",
            "<div style='background:#fff;border:1.5px solid #FCA5A5;border-radius:12px;padding:24px;text-align:center'>"
            + "<h3 style='color:#DC2626;margin-bottom:8px;font-size:15px'>Erreur</h3>"
            + "<p style='color:#9CA3AF;font-size:13px'>" + escapeHtml(msg) + "</p>"
            + "</div>"));
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }
}
