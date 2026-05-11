FROM eclipse-temurin:8-jdk
WORKDIR /app

# Copie des fichiers
COPY src/ ./src/
COPY lib/ ./lib/
RUN mkdir -p bin

# Compilation : on utilise -cp pour inclure toutes les libs et le dossier bin
RUN javac -d bin -cp "lib/*:src" src/PDFApp/*.java src/PDFServer/*.java

# Exposition pour Render
EXPOSE 8080

# Commande de lancement avec des délais augmentés pour garantir l'ordre
# 1. orbd (Service de nommage)
# 2. StartServer (Le serveur qui s'enregistre)
# 3. PDFWebGateway (L'interface web)
CMD sh -c "orbd -ORBInitialPort 1050 -ORBInitialHost 127.0.0.1 & \
    sleep 20 && \
    java -cp 'bin:lib/*' PDFServer.StartServer -ORBInitialPort 1050 -ORBInitialHost 127.0.0.1 & \
    sleep 20 && \
    java -cp 'bin:lib/*' PDFServer.PDFWebGateway"
