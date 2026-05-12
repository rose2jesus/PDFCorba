FROM eclipse-temurin:8-jdk-focal

WORKDIR /app
COPY . .

# On se place dans src et on utilise find pour compiler TOUT d'un coup.
# Cela règle les 91 erreurs car javac verra tous les fichiers en même temps.
RUN cd src && find . -name "*.java" > sources.txt && javac @sources.txt

# Script de lancement
RUN echo '#!/bin/bash \n\
cd src \n\
echo "Demarrage de tnameserv..." \n\
tnameserv -ORBInitialPort 1050 & \n\
sleep 15 \n\
echo "Demarrage du Serveur PDF..." \n\
java -cp . PDFServer.PDFServer -ORBInitialPort 1050 -ORBInitialHost 127.0.0.1 & \n\
sleep 15 \n\
echo "Demarrage de la Gateway..." \n\
exec java -cp . PDFServer.PDFWebGateway' > start.sh

RUN chmod +x start.sh
CMD ["/bin/bash", "./start.sh"]
