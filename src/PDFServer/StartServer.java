package PDFServer;

import PDFApp.*;
import org.omg.CosNaming.*;
import org.omg.CORBA.*;
import org.omg.PortableServer.*;

public class StartServer {
    public static void main(String[] args) {
        try {
            ORB orb = ORB.init(args, null);
            POA rootpoa = POAHelper.narrow(orb.resolve_initial_references("RootPOA"));
            rootpoa.the_POAManager().activate();

            PDFServiceImpl pdfImpl = new PDFServiceImpl();
            pdfImpl.setORB(orb); // Cette ligne fonctionnera maintenant !

            org.omg.CORBA.Object ref = rootpoa.servant_to_reference(pdfImpl);
            PDFService href = PDFServiceHelper.narrow(ref);

            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);

            NameComponent path[] = ncRef.to_name("PDFService");
            ncRef.rebind(path, href);

            System.out.println("---------------------------------------");
            System.out.println("      SERVEUR PDF CORBA PRÊT          ");
            System.out.println("---------------------------------------");

            orb.run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
