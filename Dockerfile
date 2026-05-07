FROM eclipse-temurin:8-jdk
WORKDIR /app
COPY src/ ./src/
COPY lib/ ./lib/
RUN mkdir -p bin

RUN javac -d bin \
    -cp lib/pdfbox-app-2.0.32.jar:lib/core-3.5.2.jar:lib/javase-3.5.2.jar:lib/bcprov-jdk15on-1.70.jar:lib/bcpkix-jdk15on-1.70.jar \
    src/PDFApp/*.java \
    src/PDFServer/PDFServiceImpl.java \
    src/PDFServer/PDFWebGateway.java \
    src/PDFServer/StartServer.java

EXPOSE 8080
CMD ["sh", "-c", \
    "orbd -ORBInitialPort 1050 & sleep 3 && \
     java -cp bin:lib/* PDFServer.StartServer -ORBInitialPort 1050 -ORBInitialHost localhost & sleep 3 && \
     java -cp bin:lib/* PDFServer.PDFWebGateway -ORBInitialPort 1050 -ORBInitialHost localhost"]
