FROM eclipse-temurin:8-jdk

WORKDIR /app

COPY src/ ./src/
COPY lib/ ./lib/
RUN mkdir -p bin

RUN wget -q \
    "https://repo1.maven.org/maven2/org/bouncycastle/bcmail-jdk15on/1.70/bcmail-jdk15on-1.70.jar" \
    -O lib/bcmail-jdk15on-1.70.jar && \
    echo "✓ Dépendances OK"

RUN javac -encoding UTF-8 -d bin -cp "lib/*" \
    src/PDFApp/*.java \
    src/PDFServer/PDFServiceImpl.java \
    src/PDFServer/PDFWebGateway.java \
    src/PDFServer/StartServer.java && \
    echo "✓ Compilation réussie"

RUN printf '#!/bin/sh\n\
echo "[1/3] Démarrage ORBD..."\n\
orbd -ORBInitialPort 1050 &\n\
sleep 8\n\
echo "[2/3] Démarrage serveur CORBA..."\n\
java -cp bin:lib/* PDFServer.StartServer \\\n\
     -ORBInitialPort 1050 -ORBInitialHost localhost &\n\
sleep 15\n\
echo "[3/3] Démarrage passerelle HTTP (port 8080)..."\n\
exec java -cp bin:lib/* \\\n\
     -Djava.awt.headless=true \\\n\
     PDFServer.PDFWebGateway \\\n\
     -ORBInitialPort 1050 -ORBInitialHost localhost\n\
' > /app/entrypoint.sh && chmod +x /app/entrypoint.sh

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/login || exit 1

CMD ["/app/entrypoint.sh"]
