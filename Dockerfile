FROM eclipse-temurin:8-jdk
WORKDIR /app

COPY src/ ./src/
COPY lib/ ./lib/
RUN mkdir -p bin

# Compilation
RUN javac -encoding UTF-8 -d bin -cp "lib/*" \
    src/PDFApp/*.java \
    src/PDFServer/PDFServiceImpl.java \
    src/PDFServer/PDFWebGateway.java \
    src/PDFServer/StartServer.java

EXPOSE 8080

# Commande de lancement avec délais de sécurité maximum pour Render
CMD ["sh", "-c", \
     "orbd -ORBInitialPort 1050 & \
      sleep 20 && \
      java -cp bin:lib/* PDFServer.StartServer -ORBInitialPort 1050 -ORBInitialHost localhost & \
      sleep 30 && \
      java -cp bin:lib/* PDFServer.PDFWebGateway -ORBInitialPort 1050 -ORBInitialHost localhost"]
