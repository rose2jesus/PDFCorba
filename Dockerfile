FROM eclipse-temurin:8-jdk
WORKDIR /app

# Copie d'abord les bibliothèques et le code source
COPY lib/ ./lib/
COPY src/ ./src/

RUN mkdir -p bin

# AMÉLIORATION : Utilisation de "lib/*" avec des guillemets pour inclure tous les JARs automatiquement
RUN javac -d bin \
    -cp "lib/*" \
    src/PDFApp/*.java \
    src/PDFServer/PDFServiceImpl.java \
    src/PDFServer/PDFWebGateway.java \
    src/PDFServer/StartServer.java

EXPOSE 8080

CMD ["sh", "-c", "orbd -ORBInitialPort 1050 & sleep 3 && \
     java -cp bin:lib/* PDFServer.StartServer -ORBInitialPort 1050 -ORBInitialHost localhost & sleep 3 && \
     java -cp bin:lib/* -DDATABASE_URL=$DATABASE_URL PDFServer.PDFWebGateway -ORBInitialPort 1050 -ORBInitialHost localhost"]
