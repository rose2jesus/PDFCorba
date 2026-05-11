FROM eclipse-temurin:8-jdk
WORKDIR /app

# Copie des fichiers
COPY src/ ./src/
COPY lib/ ./lib/
RUN mkdir -p bin

# Compilation (utilisation du séparateur ':' pour Linux)
RUN javac -d bin -cp "lib/*:." src/PDFApp/*.java src/PDFServer/*.java

# Exposition du port par défaut de Render
EXPOSE 8080

# Commande de lancement robuste
# On utilise 'wait' pour s'assurer que le conteneur reste actif tant que la Gateway tourne
CMD sh -c "orbd -ORBInitialPort 1050 & sleep 5 && \
    java -cp bin:lib/* PDFServer.StartServer -ORBInitialPort 1050 & sleep 5 && \
    java -cp bin:lib/* PDFServer.PDFWebGateway"
