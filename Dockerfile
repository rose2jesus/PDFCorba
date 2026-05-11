FROM eclipse-temurin:8-jdk
WORKDIR /app

# Copie des fichiers
COPY src/ ./src/
COPY lib/ ./lib/
RUN mkdir -p bin

# 1. Compilation : Utilisation du wildcard pour inclure tous les JARs du dossier lib
RUN javac -d bin -cp "lib/*:." src/PDFApp/*.java src/PDFServer/*.java

# 2. Exposition du port 8080 (celui utilisé par votre PDFWebGateway)
EXPOSE 8080

# 3. Lancement des services
# On lance orbd et le serveur en arrière-plan (&)
# On finit par la Gateway SANS le '&' pour que Render ne ferme pas le conteneur
CMD sh -c "orbd -ORBInitialPort 1050 & sleep 5 && \
    java -cp bin:lib/* PDFServer.StartServer -ORBInitialPort 1050 & sleep 5 && \
    java -cp bin:lib/* PDFServer.PDFWebGateway"
