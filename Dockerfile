# ── Base ────────────────────────────────────────────────────
FROM eclipse-temurin:8-jdk

WORKDIR /app

# ── Copier les sources ───────────────────────────────────────
COPY src/ ./src/
COPY lib/ ./lib/
RUN mkdir -p bin

# ── Télécharger les dépendances manquantes ───────────────────
# PDFBox 2.0 + dépendances
RUN wget -q "https://repo1.maven.org/maven2/org/apache/pdfbox/pdfbox/2.0.32/pdfbox-2.0.32.jar"           -O lib/pdfbox-2.0.32.jar && \
    wget -q "https://repo1.maven.org/maven2/org/apache/pdfbox/fontbox/2.0.32/fontbox-2.0.32.jar"         -O lib/fontbox-2.0.32.jar && \
    wget -q "https://repo1.maven.org/maven2/commons-logging/commons-logging/1.2/commons-logging-1.2.jar" -O lib/commons-logging-1.2.jar && \
    wget -q "https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk15on/1.70/bcprov-jdk15on-1.70.jar" -O lib/bcprov-jdk15on-1.70.jar && \
    wget -q "https://repo1.maven.org/maven2/org/bouncycastle/bcpkix-jdk15on/1.70/bcpkix-jdk15on-1.70.jar" -O lib/bcpkix-jdk15on-1.70.jar && \
    wget -q "https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar"                  -O lib/zxing-core-3.5.3.jar && \
    wget -q "https://repo1.maven.org/maven2/com/google/zxing/javase/3.5.3/javase-3.5.3.jar"              -O lib/zxing-javase-3.5.3.jar && \
    wget -q "https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.3/postgresql-42.7.3.jar"       -O lib/postgresql-42.7.3.jar && \
    wget -q "https://repo1.maven.org/maven2/org/mindrot/jbcrypt/0.4/jbcrypt-0.4.jar"                     -O lib/jbcrypt-0.4.jar && \
    echo "✓ Dépendances téléchargées"

# ── Compilation ──────────────────────────────────────────────
RUN javac -encoding UTF-8 -d bin -cp "lib/*" \
    src/PDFApp/*.java \
    src/PDFServer/PDFServiceImpl.java \
    src/PDFServer/PDFWebGateway.java \
    src/PDFServer/StartServer.java && \
    echo "✓ Compilation réussie"

# ── Script de démarrage robuste ──────────────────────────────
RUN printf '#!/bin/sh\n\
set -e\n\
\n\
echo "[BOOT] Démarrage ORBD..."\n\
orbd -ORBInitialPort 1050 &\n\
ORBD_PID=$!\n\
\n\
# Attendre qu ORBD soit prêt (max 60s)\n\
MAX=60; COUNT=0\n\
until nc -z localhost 1050 2>/dev/null || [ $COUNT -ge $MAX ]; do\n\
    sleep 1; COUNT=$((COUNT+1))\n\
done\n\
if [ $COUNT -ge $MAX ]; then\n\
    echo "[ERREUR] ORBD n'"'"'a pas démarré après ${MAX}s"\n\
    exit 1\n\
fi\n\
echo "[BOOT] ORBD prêt (${COUNT}s)"\n\
\n\
echo "[BOOT] Démarrage PDFServer..."\n\
java -cp bin:lib/* PDFServer.StartServer \\\n\
    -ORBInitialPort 1050 -ORBInitialHost localhost &\n\
SERVER_PID=$!\n\
\n\
# Attendre que le serveur CORBA soit enregistré (max 60s)\n\
sleep 5\n\
MAX=55; COUNT=0\n\
until java -cp bin:lib/* PDFServer.PDFWebGateway \\\n\
    -ORBInitialPort 1050 -ORBInitialHost localhost \\\n\
    --check-only 2>/dev/null || [ $COUNT -ge $MAX ]; do\n\
    sleep 2; COUNT=$((COUNT+2))\n\
done\n\
\n\
echo "[BOOT] Démarrage PDFWebGateway..."\n\
exec java -cp bin:lib/* PDFServer.PDFWebGateway \\\n\
    -ORBInitialPort 1050 -ORBInitialHost localhost\n\
' > /app/start.sh && chmod +x /app/start.sh

# ── Script de démarrage simplifié (sans nc ni --check-only) ─
# Utiliser des sleep calibrés est plus portable dans un container Docker
RUN printf '#!/bin/sh\n\
echo "[1/3] Démarrage ORBD sur le port 1050..."\n\
orbd -ORBInitialPort 1050 &\n\
sleep 8\n\
\n\
echo "[2/3] Démarrage du serveur CORBA (PDFServiceImpl)..."\n\
java -cp bin:lib/* PDFServer.StartServer \\\n\
     -ORBInitialPort 1050 -ORBInitialHost localhost &\n\
sleep 15\n\
\n\
echo "[3/3] Démarrage de la passerelle HTTP (port 8080)..."\n\
exec java -cp bin:lib/* \\\n\
     -Djava.awt.headless=true \\\n\
     PDFServer.PDFWebGateway \\\n\
     -ORBInitialPort 1050 -ORBInitialHost localhost\n\
' > /app/entrypoint.sh && chmod +x /app/entrypoint.sh

# ── Exposition du port ───────────────────────────────────────
EXPOSE 8080

# ── Healthcheck ──────────────────────────────────────────────
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/login || exit 1

# ── Point d'entrée ───────────────────────────────────────────
CMD ["/app/entrypoint.sh"]
