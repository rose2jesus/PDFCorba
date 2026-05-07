# Studio PDF CORBA

Application distribuée de gestion de documents PDF basée sur CORBA.

## Architecture
Client Web → Gateway Java (HTTP) → Serveur CORBA → PDFBox

## Fonctionnalités
- Création, fusion, découpage de PDF
- Extraction de texte et pages
- Protection par mot de passe
- Conversion en images
- Compression, métadonnées
- QR Code et signature numérique

## Technologies
- Java 8 + CORBA + PDFBox 2.0.32
- ZXing (QR Code) + BouncyCastle (Signature)

## Lancement
```bash
orbd -ORBInitialPort 1050
java -cp bin:lib/* PDFServer.StartServer -ORBInitialPort 1050 -ORBInitialHost localhost
java -cp bin:lib/* PDFServer.PDFWebGateway -ORBInitialPort 1050 -ORBInitialHost localhost
```
Ouvrez http://localhost:8080
