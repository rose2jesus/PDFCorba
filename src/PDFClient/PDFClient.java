package PDFClient;

import PDFApp.*;
import org.omg.CosNaming.*;
import org.omg.CORBA.*;
import java.io.*;

public class PDFClient {
    public static void main(String[] args) {
        try {
            // 1. Initialiser l'ORB
            ORB orb = ORB.init(args, null);

            // 2. Récupérer le service de nommage
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);

            // 3. Chercher le service "PDFService" dans l'annuaire
            PDFService pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));

            System.out.println("\n[CLIENT] Connecté avec succès au serveur PDF !");

            // 4. TEST : Création d'un nouveau PDF
            System.out.println("[CLIENT] Envoi d'une requête de création de PDF...");
            String titre = "Mon Premier PDF CORBA";
            String contenu = "Bonjour !\nCe document a été généré via CORBA.\n\nTechnologie utilisée :\n- Java CORBA (ORBD)\n- Apache PDFBox 2.0.32";
            
            byte[] pdfGenere = pdfRef.creerPDF(titre, contenu);

            // 5. Sauvegarder le fichier reçu sur le PC du client
            File fichierSortie = new File("resultat_test.pdf");
            try (FileOutputStream fos = new FileOutputStream(fichierSortie)) {
                fos.write(pdfGenere);
            }

            System.out.println("[CLIENT] Succès ! Le fichier a été créé : " + fichierSortie.getAbsolutePath());

        } catch (Exception e) {
            System.err.println("[CLIENT] Erreur : " + e.getMessage());
            e.printStackTrace();
        }
    }
}
