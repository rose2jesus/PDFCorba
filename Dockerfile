FROM eclipse-temurin:8-jdk
WORKDIR /app

COPY src/ ./src/
COPY lib/ ./lib/
RUN mkdir -p bin

# Compilation : On compile tout le dossier src pour éviter les erreurs de dépendances
RUN javac -d bin -cp "lib/*" src/PDFApp/*.java src/PDFServer/*.java

# On ne force pas EXPOSE 8080, Render s'en occupe.

# Commande de lancement optimisée
# On termine par le Gateway SANS le '&' pour que le conteneur reste actif.
CMD ["sh", "-c", \
     "orbd -ORBInitialPort 1050 & \
      sleep 10 && \
      java -cp bin:lib/* PDFServer.StartServer -ORBInitialPort 1050 -ORBInitialHost localhost & \
      sleep 15 && \
      java -cp bin:lib/* PDFServer.PDFWebGateway -ORBInitialPort 1050 -ORBInitialHost localhost"]
