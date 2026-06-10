FROM eclipse-temurin:8-jdk

WORKDIR /app

# ── Outils système nécessaires ───────────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
      wget \
      netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*

# ── Copier les sources ───────────────────────────────────────
COPY src/ ./src/
COPY lib/ ./lib/
RUN mkdir -p bin

# ── Télécharger toutes les dépendances ──────────────────────
RUN wget -q "https://repo1.maven.org/maven2/org/apache/pdfbox/pdfbox/2.0.32/pdfbox-2.0.32.jar"             -O lib/pdfbox-2.0.32.jar && \
    wget -q "https://repo1.maven.org/maven2/org/apache/pdfbox/fontbox/2.0.32/fontbox-2.0.32.jar"           -O lib/fontbox-2.0.32.jar && \
    wget -q "https://repo1.maven.org/maven2/commons-logging/commons-logging/1.2/commons-logging-1.2.jar"   -O lib/commons-logging-1.2.jar && \
    wget -q "https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk15on/1.70/bcprov-jdk15on-1.70.jar"  -O lib/bcprov-jdk15on-1.70.jar && \
    wget -q "https://repo1.maven.org/maven2/org/bouncycastle/bcpkix-jdk15on/1.70/bcpkix-jdk15on-1.70.jar"  -O lib/bcpkix-jdk15on-1.70.jar && \
    wget -q "https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar"                    -O lib/zxing-core-3.5.3.jar && \
    wget -q "https://repo1.maven.org/maven2/com/google/zxing/javase/3.5.3/javase-3.5.3.jar"                -O lib/zxing-javase-3.5.3.jar && \
    wget -q "https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.3/postgresql-42.7.3.jar"         -O lib/postgresql-42.7.3.jar && \
    wget -q "https://repo1.maven.org/maven2/org/mindrot/jbcrypt/0.4/jbcrypt-0.4.jar"                       -O lib/jbcrypt-0.4.jar && \
    echo "✓ Toutes les dépendances téléchargées"

# ── Compilation ──────────────────────────────────────────────
RUN javac -encoding UTF-8 -d bin -cp "lib/*" \
      src/PDFApp/*.java \
      src/PDFServer/PDFServiceImpl.java \
      src/PDFServer/PDFWebGateway.java \
      src/PDFServer/StartServer.java \
    && echo "✓ Compilation réussie"

# ── Script de démarrage robuste ──────────────────────────────
RUN printf '#!/bin/sh\nset -e\n\
\n\
wait_port() {\n\
  HOST=$1; PORT=$2; LABEL=$3; MAX=${4:-60}\n\
  echo "[WAIT] Attente de $LABEL ($HOST:$PORT)..."\n\
  COUNT=0\n\
  while ! nc -z "$HOST" "$PORT" 2>/dev/null; do\n\
    COUNT=$((COUNT+1))\n\
    if [ $COUNT -ge $MAX ]; then\n\
      echo "[ERREUR] $LABEL non disponible apres ${MAX}s"\n\
      exit 1\n\
    fi\n\
    sleep 1\n\
  done\n\
  echo "[OK] $LABEL pret (${COUNT}s)"\n\
}\n\
\n\
if [ -n "$DATABASE_URL" ]; then\n\
  DB_HOST=$(echo "$DATABASE_URL" | sed '"'"'s|.*@||'"'"' | sed '"'"'s|[:/].*||'"'"')\n\
  DB_PORT=$(echo "$DATABASE_URL" | sed '"'"'s|.*@[^:]*:||'"'"' | sed '"'"'s|/.*||'"'"')\n\
  echo "$DB_PORT" | grep -qE '"'"'^[0-9]+$'"'"' || DB_PORT=5432\n\
  wait_port "$DB_HOST" "$DB_PORT" "PostgreSQL" 120\n\
else\n\
  echo "[WARN] DATABASE_URL non definie"\n\
fi\n\
\n\
echo "[1/3] Lancement orbd (port 1050)..."\n\
orbd -ORBInitialPort 1050 &\n\
wait_port localhost 1050 "orbd" 60\n\
\n\
echo "[2/3] Lancement PDFServiceImpl..."\n\
java -cp bin:lib/* -Djava.awt.headless=true PDFServer.StartServer \\\n\
  -ORBInitialPort 1050 -ORBInitialHost localhost &\n\
SERVER_PID=$!\n\
sleep 15\n\
kill -0 $SERVER_PID 2>/dev/null || { echo "[ERREUR] PDFServiceImpl arrete"; exit 1; }\n\
echo "[OK] PDFServiceImpl actif"\n\
\n\
echo "[3/3] Lancement PDFWebGateway (port 8080)..."\n\
exec java -cp bin:lib/* -Djava.awt.headless=true PDFServer.PDFWebGateway \\\n\
  -ORBInitialPort 1050 -ORBInitialHost localhost\n\
' > /app/entrypoint.sh && chmod +x /app/entrypoint.sh

EXPOSE 8080

HEALTHCHECK \
  --interval=30s \
  --timeout=10s \
  --start-period=90s \
  --retries=5 \
  CMD wget -q --spider http://localhost:8080/login || exit 1

CMD ["/app/entrypoint.sh"]
