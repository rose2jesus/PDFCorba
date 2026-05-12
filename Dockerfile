# Utilisation d'une image moderne et maintenue (Eclipse Temurin)
FROM eclipse-temurin:17-jdk-focal

# Installation de netcat pour les tests de connectivité (optionnel mais utile)
RUN apt-get update && apt-get install -y netcat && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copie des sources
COPY . .

# Compilation propre : on s'assure que le classpath inclut le répertoire courant
RUN javac PDFApp/*.java PDFServer/*.java

# Exposition du port dynamique pour Render
EXPOSE 8080

# Script de démarrage amélioré
# On utilise '127.0.0.1' au lieu de 'localhost' pour éviter des ambiguïtés de résolution
RUN echo '#!/bin/bash \n\
echo "Démarrage du service de nommage CORBA..." \n\
tnameserv -ORBInitialPort 1050 & \n\
\n\
sleep 3 \n\
\n\
echo "Démarrage du Serveur PDF..." \n\
java PDFServer.PDFServer -ORBInitialPort 1050 -ORBInitialHost 127.0.0.1 & \n\
\n\
sleep 3 \n\
\n\
echo "Démarrage de la Gateway Web sur le port $PORT..." \n\
# On passe le PORT de Render à la Gateway \n\
java PDFServer.PDFWebGateway -ORBInitialPort 1050 -ORBInitialHost 127.0.0.1' > start.sh

RUN chmod +x start.sh

# Utilisation de exec pour que les signaux d'arrêt soient bien reçus
CMD ["/bin/bash", "./start.sh"]
