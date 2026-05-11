FROM eclipse-temurin:8-jdk
WORKDIR /app

COPY . .
RUN mkdir -p bin
RUN javac -d bin -cp "lib/*:src" src/PDFApp/*.java src/PDFServer/*.java

EXPOSE 8080

# Script de lancement integre pour eviter les problemes de droits
CMD sh -c "orbd -ORBInitialPort 1050 -ORBInitialHost 127.0.0.1 & \
    sleep 15 && \
    java -cp bin:lib/* PDFServer.StartServer -ORBInitialPort 1050 -ORBInitialHost 127.0.0.1 & \
    sleep 15 && \
    java -cp bin:lib/* PDFServer.PDFWebGateway"
