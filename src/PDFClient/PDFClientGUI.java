package PDFClient;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import PDFApp.*;
import org.omg.CosNaming.*;
import org.omg.CORBA.*;

public class PDFClientGUI extends JFrame {
    private PDFService pdfRef;

    public PDFClientGUI(String[] args) {
        // Connexion CORBA (comme avant)
        try {
            ORB orb = ORB.init(args, null);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            pdfRef = PDFServiceHelper.narrow(ncRef.resolve_str("PDFService"));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Erreur connexion Serveur : " + e.getMessage());
        }

        // Configuration de la fenêtre
        setTitle("Gestionnaire PDF CORBA");
        setSize(400, 200);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new FlowLayout());

        // Composants
        JLabel label = new JLabel("Cliquez pour générer un PDF :");
        JButton btnCreate = new JButton("Créer PDF de Test");

        // Action du bouton
        btnCreate.addActionListener(e -> {
            try {
                byte[] data = pdfRef.creerPDF("Titre GUI", "Contenu généré depuis l'interface Swing.");
                try (FileOutputStream fos = new FileOutputStream("pdf_gui.pdf")) {
                    fos.write(data);
                }
                JOptionPane.showMessageDialog(this, "Succès ! Fichier 'pdf_gui.pdf' créé.");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Erreur : " + ex.getMessage());
            }
        });

        add(label);
        add(btnCreate);
        setLocationRelativeTo(null);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new PDFClientGUI(args).setVisible(true);
        });
    }
}
