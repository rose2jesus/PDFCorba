FROM eclipse-temurin:8-jdk
WORKDIR /app

COPY src/ ./src/
COPY lib/ ./lib/
RUN mkdir -p bin

# Compilation robuste de tout le projet
RUN javac -d bin -cp "lib/*" src/PDFApp/*.java src/PDFServer/*.java

# Lancement propre pour Render (sans & final pour garder le container actif)
CMD ["sh", "-c", "orbd -ORBInitialPort 1050 & sleep 8 && java -cp bin:lib/* PDFServer.StartServer -ORBInitialPort 1050 & sleep 8 && java -cp bin:lib/* PDFServer.PDFWebGateway"]
