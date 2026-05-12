# Utilisation d'une image stable avec les outils CORBA
FROM eclipse-temurin:8-jdk-focal

WORKDIR /app
COPY . .

# Compilation
RUN javac PDFApp/*.java PDFServer/*.java

# Script de lancement robuste
RUN echo '#!/bin/bash \n\
tnameserv -ORBInitialPort 1050 & \n\
sleep 5 \n\
java PDFServer.PDFServer -ORBInitialPort 1050 -ORBInitialHost localhost & \n\
sleep 5 \n\
java PDFServer.PDFWebGateway -ORBInitialPort 1050 -ORBInitialHost localhost' > start.sh

RUN chmod +x start.sh
CMD ["/bin/bash", "./start.sh"]
