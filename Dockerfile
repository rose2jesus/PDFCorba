FROM eclipse-temurin:8-jdk
WORKDIR /app

# Copie des sources et des bibliothèques
COPY src/ ./src/
COPY lib/ ./lib/

# Création du dossier pour les fichiers compilés
RUN mkdir -p bin

# Compilation de tous les fichiers Java avec le classpath pointant vers lib
RUN javac -d bin \
    -cp "lib/*" \
    src/PDFApp/*.java \
    src/PDFServer/PDFServiceImpl.java \
    src/PDFServer/PDFWebGateway.java \
    src/PDFServer/StartServer.java

# Port exposé par Render
EXPOSE 8080

# Exécution des services
# IMPORTANT : Le dernier processus (PDFWebGateway) n'a pas de '&' 
# pour empêcher le conteneur de se fermer.
CMD ["sh", "-c", \
     "orbd -ORBInitialPort 1050 & \
      sleep 15 && \
      java -cp bin:lib/* PDFServer.StartServer -ORBInitialPort 1050 -ORBInitialHost localhost & \
      sleep 20 && \
      java -cp bin:lib/* PDFServer.PDFWebGateway -ORBInitialPort 1050 -ORBInitialHost localhost"]
