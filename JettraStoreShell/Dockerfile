# ==============================================================================
# JettraStoreShell Interactive CLI (Java 25+ Panama FFM)
# ==============================================================================
FROM bellsoft/liberica-runtime-container:jre-25-stream-musl
LABEL maintainer="Jettra Ecosystem <dev@jettra.io>"
LABEL description="JettraStoreShell - Consola CLI interactiva para administración de JettraStore"

# Instalar utilidades básicas (compatible con Alpine/musl y Debian/glibc)
RUN if command -v apk > /dev/null; then \
        apk add --no-cache curl ca-certificates tzdata; \
    elif command -v apt-get > /dev/null; then \
        apt-get update && apt-get install -y --no-install-recommends curl ca-certificates tzdata && rm -rf /var/lib/apt/lists/*; \
    fi

WORKDIR /app

# Crear directorio de trabajo y home de usuario para historial y conexiones (.jettra)
RUN mkdir -p /home/jettra/.jettra /app/config && \
    chown -R 1000:1000 /home/jettra /app

# Copiar el Uber JAR autónomo (Fat JAR con todas las librerías integradas)
COPY target/JettraStoreShell-*-uber.jar /app/JettraStoreShell.jar
COPY config/connections.properties /home/jettra/.jettra/connections.properties

RUN chown -R 1000:1000 /home/jettra

# Variables de entorno predeterminadas
ENV HOME=/home/jettra \
    JETTRA_HOST=jettra-node-01 \
    JETTRA_PORT=9091 \
    JAVA_OPTS="-XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders --enable-preview --enable-native-access=ALL-UNNAMED"

USER 1000:1000

# Entrada interactiva a la consola JettraStoreShell
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/JettraStoreShell.jar \"$@\"", "--"]
