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
import org.bouncycastle.operator.*;
import org.bouncycastle.operator.jcajce.*;

import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.*;
import com.google.zxing.client.j2se.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

public class PDFServiceImpl extends PDFServicePOA {

    private ORB orb;

    public void setORB(ORB orb_val) {
        this.orb = orb_val;
    }

    // ── 1. Création de PDF ───────────────────────────────────
    @Override
    public byte[] creerPDF(String titre, String contenu) throws PDFException {
        try {
            PDDocument doc = new PDDocument();
            PDPage page = new PDPage();
            doc.addPage(page);

            PDPageContentStream content = new PDPageContentStream(doc, page);

            // Titre
            content.setFont(PDType1Font.HELVETICA_BOLD, 18);
            content.beginText();
            content.newLineAtOffset(50, 750);
            content.showText(titre);
            content.endText();

            // Ligne séparatrice
            content.moveTo(50, 740);
            content.lineTo(550, 740);
            content.stroke();

            // Contenu
            content.setFont(PDType1Font.HELVETICA, 12);
            content.beginText();
            content.setLeading(18f);
            content.newLineAtOffset(50, 720);
            for (String line : contenu.split("\n")) {
                if (line.length() > 80) line = line.substring(0, 80);
                content.showText(line);
                content.newLine();
            }
            content.endText();
            content.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            doc.close();
            System.out.println("[SERVEUR] PDF cree : " + titre);
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur creation : " + e.getMessage());
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
            throw new PDFException("Erreur fusion : " + e.getMessage());
        }
    }

    // ── 3. Découpage de PDF ──────────────────────────────────
    @Override
    public byte[][] decouperPDF(byte[] pdf, int nbPages) throws PDFException {
        try {
            PDDocument doc = PDDocument.load(pdf);
            int total = doc.getNumberOfPages();
            List<byte[]> result = new ArrayList<>();
            for (int i = 0; i < total; i += nbPages) {
                PDDocument part = new PDDocument();
                for (int j = i; j < Math.min(i + nbPages, total); j++) {
                    part.addPage(doc.getPage(j));
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                part.save(out);
                part.close();
                result.add(out.toByteArray());
            }
            doc.close();
            System.out.println("[SERVEUR] PDF decoupe en " + result.size() + " parties");
            return result.toArray(new byte[0][]);
        } catch (Exception e) {
            throw new PDFException("Erreur decoupage : " + e.getMessage());
        }
    }

    // ── 4. Extraction de pages ───────────────────────────────
    @Override
    public byte[] extrairePages(byte[] pdf, int[] pages) throws PDFException {
        try {
            PDDocument doc = PDDocument.load(pdf);
            PDDocument result = new PDDocument();
            for (int page : pages) {
                if (page >= 0 && page < doc.getNumberOfPages()) {
                    result.addPage(doc.getPage(page));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            result.save(out);
            result.close();
            doc.close();
            System.out.println("[SERVEUR] " + pages.length + " pages extraites");
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur extraction pages : " + e.getMessage());
        }
    }

    // ── 5. Suppression de pages ──────────────────────────────
    @Override
    public byte[] supprimerPages(byte[] pdf, int[] pages) throws PDFException {
        try {
            PDDocument doc = PDDocument.load(pdf);
            Set<Integer> toDelete = new HashSet<>();
            for (int p : pages) toDelete.add(p);
            PDDocument result = new PDDocument();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                if (!toDelete.contains(i)) result.addPage(doc.getPage(i));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            result.save(out);
            result.close();
            doc.close();
            System.out.println("[SERVEUR] " + pages.length + " pages supprimees");
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur suppression : " + e.getMessage());
        }
    }

    // ── 6. Ajout mot de passe ────────────────────────────────
    @Override
    public byte[] ajouterMotDePasse(byte[] pdf, String motDePasse) throws PDFException {
        try {
            PDDocument doc = PDDocument.load(pdf);
            AccessPermission ap = new AccessPermission();
            StandardProtectionPolicy policy =
                new StandardProtectionPolicy(motDePasse, motDePasse, ap);
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            doc.close();
            System.out.println("[SERVEUR] Mot de passe ajoute");
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur protection : " + e.getMessage());
        }
    }

    // ── 7. Conversion en images ──────────────────────────────
    @Override
    public byte[][] convertirEnImages(byte[] pdf, int dpi) throws PDFException {
        try {
            PDDocument doc = PDDocument.load(pdf);
            PDFRenderer renderer = new PDFRenderer(doc);
            List<byte[]> images = new ArrayList<>();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, dpi);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(img, "PNG", out);
                images.add(out.toByteArray());
            }
            doc.close();
            System.out.println("[SERVEUR] PDF converti en " + images.size() + " images");
            return images.toArray(new byte[0][]);
        } catch (Exception e) {
            throw new PDFException("Erreur conversion : " + e.getMessage());
        }
    }

    // ── 8. Extraction de texte ───────────────────────────────
    @Override
    public String extraireTexte(byte[] pdf) throws PDFException {
        try {
            PDDocument doc = PDDocument.load(pdf);
            PDFTextStripper stripper = new PDFTextStripper();
            String texte = stripper.getText(doc);
            doc.close();
            System.out.println("[SERVEUR] Texte extrait : " + texte.length() + " caracteres");
            return texte;
        } catch (Exception e) {
            throw new PDFException("Erreur extraction texte : " + e.getMessage());
        }
    }

    // ── 9. Compression ──────────────────────────────────────
    @Override
    public byte[] compresserPDF(byte[] pdf) throws PDFException {
        try {
            PDDocument doc = PDDocument.load(pdf);
            // PDFBox recompresse automatiquement en sauvegardant
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            doc.close();
            System.out.println("[SERVEUR] PDF compresse : " + pdf.length + " -> " + out.size() + " octets");
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur compression : " + e.getMessage());
        }
    }

    // ── 10. Lire métadonnées ─────────────────────────────────
    @Override
    public String lireMetadonnees(byte[] pdf) throws PDFException {
        try {
            PDDocument doc = PDDocument.load(pdf);
            PDDocumentInformation info = doc.getDocumentInformation();
            StringBuilder sb = new StringBuilder();
            sb.append("Titre      : ").append(info.getTitle() != null ? info.getTitle() : "Non defini").append("\n");
            sb.append("Auteur     : ").append(info.getAuthor() != null ? info.getAuthor() : "Non defini").append("\n");
            sb.append("Sujet      : ").append(info.getSubject() != null ? info.getSubject() : "Non defini").append("\n");
            sb.append("Createur   : ").append(info.getCreator() != null ? info.getCreator() : "Non defini").append("\n");
            sb.append("Producteur : ").append(info.getProducer() != null ? info.getProducer() : "Non defini").append("\n");
            sb.append("Pages      : ").append(doc.getNumberOfPages()).append("\n");
            sb.append("Creation   : ").append(info.getCreationDate() != null ? info.getCreationDate().getTime() : "Non definie").append("\n");
            doc.close();
            System.out.println("[SERVEUR] Metadonnees lues");
            return sb.toString();
        } catch (Exception e) {
            throw new PDFException("Erreur lecture metadonnees : " + e.getMessage());
        }
    }

    // ── 11. Modifier métadonnées ─────────────────────────────
    @Override
    public byte[] modifierMetadonnees(byte[] pdf, String titre, String auteur, String sujet) throws PDFException {
        try {
            PDDocument doc = PDDocument.load(pdf);
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle(titre);
            info.setAuthor(auteur);
            info.setSubject(sujet);
            info.setCreator("Studio PDF CORBA");
            doc.setDocumentInformation(info);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            doc.close();
            System.out.println("[SERVEUR] Metadonnees modifiees");
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur modification metadonnees : " + e.getMessage());
        }
    }

    // ── 12. QR Code ─────────────────────────────────────────
    @Override
    public byte[] ajouterQRCode(byte[] pdf, String contenu, int page, int x, int y) throws PDFException {
        try {
            // Générer le QR Code avec ZXing
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(contenu, BarcodeFormat.QR_CODE, 150, 150);
            BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(matrix);

            // Intégrer dans le PDF
            PDDocument doc = PDDocument.load(pdf);
            PDPage pdPage = doc.getPage(Math.min(page, doc.getNumberOfPages() - 1));
            PDImageXObject pdImage = LosslessFactory.createFromImage(doc, qrImage);

            PDPageContentStream cs = new PDPageContentStream(
                doc, pdPage, PDPageContentStream.AppendMode.APPEND, true, true);
            cs.drawImage(pdImage, x, y, 150, 150);
            cs.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            doc.close();
            System.out.println("[SERVEUR] QR Code ajoute page " + page);
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur QR Code : " + e.getMessage());
        }
    }

    // ── 13. Signature numérique ──────────────────────────────
    @Override
    public byte[] signerPDF(byte[] pdf, String nomSignataire, String raison, String lieu) throws PDFException {
        try {
            // Générer paire de clés RSA
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            KeyPair keyPair = keyGen.generateKeyPair();
            final java.security.PrivateKey privateKey = keyPair.getPrivate();

            // Créer certificat auto-signé avec BouncyCastle
            X500Name subject = new X500Name("CN=" + nomSignataire + ", O=Studio PDF CORBA");
            BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
            Date notBefore = new Date();
            Date notAfter = new Date(notBefore.getTime() + 365L * 24 * 60 * 60 * 1000);

            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
                .build(privateKey);

            X509CertificateHolder certHolder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject,
                keyPair.getPublic()).build(contentSigner);

            final X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
                .getCertificate(certHolder);

            // Signer le PDF
            PDDocument doc = PDDocument.load(pdf);
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName(nomSignataire);
            signature.setLocation(lieu);
            signature.setReason(raison);
            signature.setSignDate(java.util.Calendar.getInstance());

            doc.addSignature(signature, new SignatureInterface() {
                public byte[] sign(InputStream content) throws IOException {
                    try {
                        java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
                        sig.initSign(privateKey);
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = content.read(buf)) != -1) sig.update(buf, 0, n);
                        return sig.sign();
                    } catch (Exception ex) {
                        throw new IOException("Erreur signature : " + ex.getMessage());
                    }
                }
            });

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.saveIncremental(out);
            doc.close();
            System.out.println("[SERVEUR] PDF signe par " + nomSignataire);
            return out.toByteArray();
        } catch (Exception e) {
            throw new PDFException("Erreur signature : " + e.getMessage());
        }
    }
}
