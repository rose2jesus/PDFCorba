FROM eclipse-temurin:8-jdk
WORKDIR /app

COPY src/ ./src/
COPY lib/ ./lib/
RUN mkdir -p bin

# Télécharger les dépendances
RUN wget -q "https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.3/postgresql-42.7.3.jar" -O lib/postgresql-42.7.3.jar && \
    wget -q "https://repo1.maven.org/maven2/org/mindrot/jbcrypt/0.4/jbcrypt-0.4.jar" -O lib/jbcrypt-0.4.jar && \
    echo "Deps OK"

# Compilation
RUN javac -encoding UTF-8 -d bin -cp "lib/*" \
    src/PDFApp/*.java \
    src/PDFServer/PDFServiceImpl.java \
    src/PDFServer/PDFWebGateway.java \
    src/PDFServer/StartServer.java

EXPOSE 8080

CMD ["sh", "-c", \
     "orbd -ORBInitialPort 1050 & \
      sleep 20 && \
      java -cp bin:lib/* PDFServer.StartServer -ORBInitialPort 1050 -ORBInitialHost localhost & \
      sleep 30 && \
      java -cp bin:lib/* PDFServer.PDFWebGateway -ORBInitialPort 1050 -ORBInitialHost localhost"]
