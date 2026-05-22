package PDFServer;

import PDFApp.*;
import org.omg.CORBA.ORB;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdmodel.graphics.image.*;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.*;
import org.apache.pdfbox.multipdf.*;
import org.apache.pdfbox.rendering.*;
import org.apache.pdfbox.text.*;

import org.bouncycastle.asn1.x500.*;
import org.bouncycastle.cert.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.cms.*;
import org.bouncycastle.cms.jcajce.*;
import org.bouncycastle.operator.*;
import org.bouncycastle.operator.jcajce.*;

import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.*;
import com.google.zxing.client.j2se.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.List;

public class PDFServiceImpl extends PDFServicePOA {

    private ORB orb;

    public void setORB(ORB orb_val) {
        this.orb = orb_val;
    }

    // ── Utilitaire : word-wrap ───────────────────────────────
    /**
     * Découpe une ligne en segments de max maxWidth points selon la police/taille.
     * Evite la troncature brutale à 80 chars.
     */
    private List<String> wrapLine(PDFont font, float fontSize, String text, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) { lines.add(""); return lines; }
        // Remplacer les caractères non supportés par PDType1Font
        text = sanitizeForType1(text);
        String[] words = text.split(" ", -1);
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            float w;
            try { w = font.getStringWidth(candidate) / 1000 * fontSize; }
            catch (Exception e) { w = candidate.length() * fontSize * 0.5f; }
            if (w > maxWidth && current.length() > 0) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    /**
     * PDType1Font ne supporte que WinAnsiEncoding (Latin-1 étendu).
     * On remplace les caractères hors plage par leur équivalent ASCII ou "?".
     */
    private String sanitizeForType1(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c < 256) {
                sb.append(c);
            } else {
                // Translittération basique pour les plus courants
                String norm = java.text.Normalizer.normalize(String.valueOf(c),
                    java.text.Normalizer.Form.NFD);
                char base = norm.charAt(0);
                sb.append(base < 256 ? base : '?');
            }
        }
        return sb.toString();
    }

    // ── 1. Création de PDF ───────────────────────────────────
    @Override
    public byte[] creerPDF(String titre, String contenu) throws PDFException {
        PDDocument doc = null;
        try {
            doc = new PDDocument();
            PDPage page = new PDPage();
            doc.addPage(page);

            PDPageContentStream content = new PDPageContentStream(doc, page);
            float pageWidth = page.getMediaBox().getWidth();
            float margin = 50f;
            float contentWidth = pageWidth - 2 * margin;
            float y = 750f;

            // ── Titre
            content.setFont(PDType1Font.HELVETICA_BOLD, 18);
            content.beginText();
            content.newLineAtOffset(margin, y);
            content.showText(sanitizeForType1(titre));
            content.endText();
            y -= 14;

            // ── Ligne séparatrice
            content.setLineWidth(0.5f);
            content.moveTo(margin, y);
            content.lineTo(pageWidth - margin, y);
            content.stroke();
            y -= 22;

            // ── Contenu avec word-wrap et pagination automatique
            PDType1Font bodyFont = PDType1Font.HELVETICA;
            float fontSize = 12f;
            float leading = 18f;
            content.setFont(bodyFont, fontSize);
            content.beginText();
            content.setLeading(leading);
            content.newLineAtOffset(margin, y);

            for (String rawLine : contenu.split("\n", -1)) {
                List<String> wrapped = wrapLine(bodyFont, fontSize, rawLine, contentWidth);
                for (String wl : wrapped) {
                    y -= leading;
                    if (y < 60) {
                        // Nouvelle page
                        content.endText();
                        content.close();
                        page = new PDPage();
                        doc.addPage(page);
                        content = new PDPageContentStream(doc, page);
                        content.setFont(bodyFont, fontSize);
                        content.beginText();
                        y = 750f;
                        content.newLineAtOffset(margin, y);
                    }
                    content.showText(wl);
                    content.newLine();
                }
            }
            content.endText();
            content.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            System.out.println("[SERVEUR] PDF créé : " + titre);
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur creation : " + sanitizeCdrString(e.getMessage()));
        } finally {
            closeQuietly(doc);
        }
    }

    // ── 2. Fusion de PDFs ────────────────────────────────────
    @Override
    public byte[] fusionnerPDFs(byte[][] pdfs) throws PDFException {
        try {
            PDFMergerUtility merger = new PDFMergerUtility();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            merger.setDestinationStream(out);
            for (byte[] pdfData : pdfs) {
                merger.addSource(new ByteArrayInputStream(pdfData));
            }
            merger.mergeDocuments(null);
            System.out.println("[SERVEUR] Fusion de " + pdfs.length + " PDFs");
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur fusion : " + sanitizeCdrString(e.getMessage()));
        }
    }

    // ── 3. Découpage de PDF ──────────────────────────────────
    @Override
    public byte[][] decouperPDF(byte[] pdf, int nbPages) throws PDFException {
        PDDocument doc = null;
        try {
            doc = PDDocument.load(pdf);
            int total = doc.getNumberOfPages();
            if (nbPages < 1) nbPages = 1;
            List<byte[]> result = new ArrayList<>();
            for (int i = 0; i < total; i += nbPages) {
                PDDocument part = new PDDocument();
                try {
                    for (int j = i; j < Math.min(i + nbPages, total); j++) {
                        part.addPage(doc.getPage(j));
                    }
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    part.save(out);
                    result.add(out.toByteArray());
                } finally {
                    closeQuietly(part);
                }
            }
            System.out.println("[SERVEUR] PDF découpé en " + result.size() + " parties");
            return result.toArray(new byte[0][]);
        } catch (Exception e) {
            throw new PDFException("Erreur decoupage : " + sanitizeCdrString(e.getMessage()));
        } finally {
            closeQuietly(doc);
        }
    }

    // ── 4. Extraction de pages ───────────────────────────────
    @Override
    public byte[] extrairePages(byte[] pdf, int[] pages) throws PDFException {
        PDDocument doc = null;
        PDDocument result = null;
        try {
            doc = PDDocument.load(pdf);
            result = new PDDocument();
            int total = doc.getNumberOfPages();
            for (int page : pages) {
                if (page >= 0 && page < total) {
                    result.addPage(doc.getPage(page));
                }
            }
            if (result.getNumberOfPages() == 0) {
                throw new Exception("Aucune page valide dans la sélection (total : " + total + " pages)");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            result.save(out);
            System.out.println("[SERVEUR] " + result.getNumberOfPages() + " page(s) extraite(s)");
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur extraction pages : " + sanitizeCdrString(e.getMessage()));
        } finally {
            closeQuietly(result);
            closeQuietly(doc);
        }
    }

    // ── 5. Suppression de pages ──────────────────────────────
    @Override
    public byte[] supprimerPages(byte[] pdf, int[] pages) throws PDFException {
        PDDocument doc = null;
        PDDocument result = null;
        try {
            doc = PDDocument.load(pdf);
            Set<Integer> toDelete = new HashSet<>();
            for (int p : pages) toDelete.add(p);
            result = new PDDocument();
            int total = doc.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                if (!toDelete.contains(i)) result.addPage(doc.getPage(i));
            }
            if (result.getNumberOfPages() == 0) {
                throw new Exception("Impossible de supprimer toutes les pages du document");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            result.save(out);
            System.out.println("[SERVEUR] " + pages.length + " page(s) supprimée(s)");
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur suppression : " + sanitizeCdrString(e.getMessage()));
        } finally {
            closeQuietly(result);
            closeQuietly(doc);
        }
    }

    // ── 6. Ajout mot de passe ────────────────────────────────
    @Override
    public byte[] ajouterMotDePasse(byte[] pdf, String motDePasse) throws PDFException {
        PDDocument doc = null;
        try {
            doc = PDDocument.load(pdf);
            if (doc.isEncrypted()) {
                throw new Exception("Ce document est déjà chiffré.");
            }
            AccessPermission ap = new AccessPermission();
            // Mot de passe propriétaire = mot de passe utilisateur pour simplifier
            StandardProtectionPolicy policy =
                new StandardProtectionPolicy(motDePasse, motDePasse, ap);
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            System.out.println("[SERVEUR] Mot de passe ajouté");
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur protection : " + sanitizeCdrString(e.getMessage()));
        } finally {
            closeQuietly(doc);
        }
    }

    // ── 7. Conversion en images ──────────────────────────────
    @Override
    public byte[][] convertirEnImages(byte[] pdf, int dpi) throws PDFException {
        PDDocument doc = null;
        try {
            if (dpi < 72) dpi = 72;
            if (dpi > 600) dpi = 600;
            doc = PDDocument.load(pdf);
            PDFRenderer renderer = new PDFRenderer(doc);
            List<byte[]> images = new ArrayList<>();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, dpi);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(img, "PNG", out);
                images.add(out.toByteArray());
            }
            System.out.println("[SERVEUR] PDF converti en " + images.size() + " image(s) à " + dpi + " DPI");
            return images.toArray(new byte[0][]);
        } catch (Exception e) {
            throw new PDFException("Erreur conversion : " + sanitizeCdrString(e.getMessage()));
        } finally {
            closeQuietly(doc);
        }
    }

    // ── 8. Extraction de texte ───────────────────────────────
    @Override
    public String extraireTexte(byte[] pdf) throws PDFException {
        PDDocument doc = null;
        try {
            doc = PDDocument.load(pdf);
            PDFTextStripper stripper = new PDFTextStripper();
            String texte = stripper.getText(doc);
            if (texte == null || texte.trim().isEmpty()) {
                return "(Aucun texte extractible - le document est peut-etre scanne ou compose uniquement d'images)";
            }
            System.out.println("[SERVEUR] Texte extrait : " + texte.length() + " caracteres");
            // CORBA CDR (Java 8) ne peut pas serialiser les String contenant des
            // caracteres hors Latin-1 (> U+00FF) : on translittere via NFD puis
            // on supprime tout ce qui reste hors plage.
            return sanitizeCdrString(texte);
        } catch (Exception e) {
            throw new PDFException("Erreur extraction texte : " + sanitizeCdrString(e.getMessage()));
        } finally {
            closeQuietly(doc);
        }
    }

    /**
     * Rend une String compatible avec la serialisation CDR de CORBA Java 8.
     * Etape 1 : decomposition NFD pour recuperer la lettre de base des accentes.
     * Etape 2 : tout caractere encore > U+00FF est remplace par '?'.
     * Les sauts de ligne et tabulations sont preserves.
     */
    private static String sanitizeCdrString(String s) {
        if (s == null) return "";
        // NFD decompose e.g. é → e + combining accent ; on garde seulement le char de base
        String nfd = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(nfd.length());
        for (int i = 0; i < nfd.length(); i++) {
            char c = nfd.charAt(i);
            if (c < 0x0100) {
                sb.append(c);           // Latin-1 : OK pour CDR
            } else if (Character.getType(c) == Character.NON_SPACING_MARK) {
                // accent combinant : on l'ignore (sa lettre de base a deja ete ajoutee)
            } else {
                sb.append('?');         // tout le reste : remplacement neutre
            }
        }
        return sb.toString();
    }

    // ── 9. Compression ──────────────────────────────────────
    /**
     * Compression réelle : réencoder les images intégrées en JPEG (qualité 0.6)
     * et supprimer les ressources orphelines (flush + save).
     */
    @Override
    public byte[] compresserPDF(byte[] pdf) throws PDFException {
        PDDocument doc = null;
        try {
            doc = PDDocument.load(pdf);
            // Re-compression des images intégrées
            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                if (res == null) continue;
                for (COSName name : res.getXObjectNames()) {
                    org.apache.pdfbox.pdmodel.graphics.PDXObject xObj;
                    try { xObj = res.getXObject(name); } catch (Exception ex) { continue; }
                    if (xObj instanceof PDImageXObject) {
                        PDImageXObject img = (PDImageXObject) xObj;
                        BufferedImage bImg;
                        try { bImg = img.getImage(); } catch (Exception ex) { continue; }
                        if (bImg == null) continue;
                        // Convertir en JPEG si l'image est grande (> 100x100)
                        if (bImg.getWidth() > 100 && bImg.getHeight() > 100) {
                            try {
                                // Convertir ARGB → RGB pour JPEG
                                BufferedImage rgb = new BufferedImage(
                                    bImg.getWidth(), bImg.getHeight(), BufferedImage.TYPE_INT_RGB);
                                Graphics2D g = rgb.createGraphics();
                                g.setColor(Color.WHITE);
                                g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                                g.drawImage(bImg, 0, 0, null);
                                g.dispose();
                                PDImageXObject compressed =
                                    JPEGFactory.createFromImage(doc, rgb, 0.6f);
                                res.put(name, compressed);
                            } catch (Exception ex) {
                                // Laisser l'image originale si la compression échoue
                            }
                        }
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            System.out.println("[SERVEUR] Compression : " + pdf.length + " → " + out.size() + " octets");
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur compression : " + sanitizeCdrString(e.getMessage()));
        } finally {
            closeQuietly(doc);
        }
    }

    // ── 10. Lire métadonnées ─────────────────────────────────
    @Override
    public String lireMetadonnees(byte[] pdf) throws PDFException {
        PDDocument doc = null;
        try {
            doc = PDDocument.load(pdf);
            PDDocumentInformation info = doc.getDocumentInformation();
            StringBuilder sb = new StringBuilder();
            sb.append("Titre      : ").append(nvl(info.getTitle())).append("\n");
            sb.append("Auteur     : ").append(nvl(info.getAuthor())).append("\n");
            sb.append("Sujet      : ").append(nvl(info.getSubject())).append("\n");
            sb.append("Créateur   : ").append(nvl(info.getCreator())).append("\n");
            sb.append("Producteur : ").append(nvl(info.getProducer())).append("\n");
            sb.append("Pages      : ").append(doc.getNumberOfPages()).append("\n");
            sb.append("Chiffré    : ").append(doc.isEncrypted() ? "Oui" : "Non").append("\n");
            sb.append("Création   : ").append(
                info.getCreationDate() != null ? info.getCreationDate().getTime() : "Non définie"
            ).append("\n");
            sb.append("Modifié    : ").append(
                info.getModificationDate() != null ? info.getModificationDate().getTime() : "Non définie"
            ).append("\n");
            System.out.println("[SERVEUR] Metadonnees lues");
            return sanitizeCdrString(sb.toString());
        } catch (Exception e) {
            throw new PDFException("Erreur lecture metadonnees : " + sanitizeCdrString(e.getMessage()));
        } finally {
            closeQuietly(doc);
        }
    }

    // ── 11. Modifier métadonnées ─────────────────────────────
    @Override
    public byte[] modifierMetadonnees(byte[] pdf, String titre, String auteur, String sujet) throws PDFException {
        PDDocument doc = null;
        try {
            doc = PDDocument.load(pdf);
            PDDocumentInformation info = doc.getDocumentInformation();
            if (titre != null && !titre.isEmpty())  info.setTitle(titre);
            if (auteur != null && !auteur.isEmpty()) info.setAuthor(auteur);
            if (sujet != null && !sujet.isEmpty())   info.setSubject(sujet);
            info.setCreator("Studio PDF CORBA");
            info.setModificationDate(java.util.Calendar.getInstance());
            doc.setDocumentInformation(info);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            System.out.println("[SERVEUR] Métadonnées modifiées");
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur modification metadonnees : " + sanitizeCdrString(e.getMessage()));
        } finally {
            closeQuietly(doc);
        }
    }

    // ── 12. QR Code ─────────────────────────────────────────
    @Override
    public byte[] ajouterQRCode(byte[] pdf, String contenu, int page, int x, int y) throws PDFException {
        PDDocument doc = null;
        try {
            if (contenu == null || contenu.trim().isEmpty()) {
                throw new Exception("Le contenu du QR code ne peut pas être vide");
            }
            // Générer le QR Code
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(contenu, BarcodeFormat.QR_CODE, 200, 200, hints);
            BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(matrix);

            doc = PDDocument.load(pdf);
            int pageIndex = Math.max(0, Math.min(page, doc.getNumberOfPages() - 1));
            PDPage pdPage = doc.getPage(pageIndex);
            PDImageXObject pdImage = LosslessFactory.createFromImage(doc, qrImage);

            PDPageContentStream cs = new PDPageContentStream(
                doc, pdPage, PDPageContentStream.AppendMode.APPEND, true, true);
            cs.drawImage(pdImage, x, y, 150, 150);
            cs.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            System.out.println("[SERVEUR] QR Code ajouté page " + pageIndex);
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur QR Code : " + sanitizeCdrString(e.getMessage()));
        } finally {
            closeQuietly(doc);
        }
    }

    // ── 13. Signature numérique (PKCS#7 CMS via BouncyCastle) ──
    @Override
    public byte[] signerPDF(byte[] pdf, String nomSignataire, String raison, String lieu) throws PDFException {
        PDDocument doc = null;
        try {
            // ── Générer paire de clés RSA 2048
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048, new SecureRandom());
            KeyPair keyPair = keyGen.generateKeyPair();
            final PrivateKey privateKey = keyPair.getPrivate();

            // ── Certificat auto-signé avec BouncyCastle
            org.bouncycastle.jce.provider.BouncyCastleProvider bcProvider =
                new org.bouncycastle.jce.provider.BouncyCastleProvider();

            String safeNom = sanitizeForType1(nomSignataire != null ? nomSignataire : "Signataire");
            X500Name subject = new X500Name("CN=" + safeNom + ", O=Studio PDF CORBA, C=SN");
            BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
            Date notBefore = new Date();
            Date notAfter  = new Date(notBefore.getTime() + 365L * 24 * 60 * 60 * 1000);

            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(bcProvider).build(privateKey);

            X509CertificateHolder certHolder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, keyPair.getPublic()
            ).build(contentSigner);

            final X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider(bcProvider).getCertificate(certHolder);

            // ── Signer le PDF avec PKCS#7 CMS Detached (format conforme PDF)
            doc = PDDocument.load(pdf);
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName(safeNom);
            signature.setLocation(sanitizeForType1(lieu != null ? lieu : ""));
            signature.setReason(sanitizeForType1(raison != null ? raison : "Approbation"));
            signature.setSignDate(java.util.Calendar.getInstance());

            doc.addSignature(signature, new SignatureInterface() {
                @Override
                public byte[] sign(InputStream content) throws IOException {
                    try {
                        // Lire tout le contenu à signer
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = content.read(buf)) != -1) bos.write(buf, 0, n);
                        byte[] contentBytes = bos.toByteArray();

                        // Construire PKCS#7 CMS SignedData (format requis par PDF)
                        org.bouncycastle.jce.provider.BouncyCastleProvider prov =
                            new org.bouncycastle.jce.provider.BouncyCastleProvider();

                        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
                        ContentSigner sha256Signer = new JcaContentSignerBuilder("SHA256withRSA")
                            .setProvider(prov).build(privateKey);

                        gen.addSignerInfoGenerator(
                            new JcaSignerInfoGeneratorBuilder(
                                new JcaDigestCalculatorProviderBuilder().setProvider(prov).build()
                            ).build(sha256Signer, cert)
                        );

                        List<X509Certificate> certList = new ArrayList<>();
                        certList.add(cert);
                        gen.addCertificates(new JcaCertStore(certList));

                        CMSProcessableByteArray msg = new CMSProcessableByteArray(contentBytes);
                        CMSSignedData signedData = gen.generate(msg, false);
                        return signedData.getEncoded();
                    } catch (Exception ex) {
                        throw new IOException("Erreur PKCS#7 : " + ex.getMessage(), ex);
                    }
                }
            });

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.saveIncremental(out);
            System.out.println("[SERVEUR] PDF signé par " + safeNom);
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur signature : " + sanitizeCdrString(e.getMessage()));
        } finally {
            closeQuietly(doc);
        }
    }

    // ── Utilitaires internes ─────────────────────────────────
    private static String nvl(String s) { return s != null ? s : "Non défini"; }

    private static void closeQuietly(Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }
}
