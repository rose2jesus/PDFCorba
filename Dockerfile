FROM eclipse-temurin:8-jdk
WORKDIR /app

# Copie des fichiers
COPY src/ ./src/
COPY lib/ ./lib/
RUN mkdir -p bin

# Compilation avec le bon séparateur Linux ':'
RUN javac -d bin -cp "lib/*:." src/PDFApp/*.java src/PDFServer/*.java

# Render détecte ce port pour le trafic Web
EXPOSE 8080

# Utilisation de l'adresse 127.0.0.1 pour forcer la communication interne
CMD sh -c "orbd -ORBInitialPort 1050 -ORBInitialHost 127.0.0.1 & \
    sleep 10 && \
    java -cp bin:lib/* PDFServer.StartServer -ORBInitialPort 1050 -ORBInitialHost 127.0.0.1 & \
    sleep 10 && \
    java -cp bin:lib/* PDFServer.PDFWebGateway"
