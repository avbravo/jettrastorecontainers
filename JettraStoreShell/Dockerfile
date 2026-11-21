# ==============================================================================
# JettraStoreShell Interactive CLI (Java 25+ Panama FFM)
# ==============================================================================
FROM bellsoft/liberica-runtime-container:jre-25-stream-musl
LABEL maintainer="Jettra Ecosystem <dev@jettra.io>"
LABEL description="JettraStoreShell - Consola CLI interactiva para administración de JettraStore"

# Instalar utilidades básicas (compatible con Alpine/musl y Debian/glibc)
RUN if command -v apk > /dev/null; then \
        apk add --no-cache curl ca-certificates tzdata && rm -rf /var/cache/apk/*; \
    elif command -v apt-get > /dev/null; then \
        apt-get update && apt-get install -y --no-install-recommends curl ca-certificates tzdata && rm -rf /var/lib/apt/lists/*; \
    fi

WORKDIR /app

# Crear directorio de trabajo y home de usuario para historial y conexiones (.jettra)
RUN mkdir -p /home/jettra/.jettra /app/config && \
    chown -R 1000:1000 /home/jettra /app

# Copiar JAR ejecutable, microprofile-config.properties y script de inicio
COPY target/JettraStoreShell-*.jar /app/
COPY src/main/resources/META-INF/microprofile-config.properties /app/config/microprofile-config.properties
COPY start.sh /app/start.sh

RUN rm -f /app/original-JettraStoreShell-*.jar && \
    if [ -f /app/JettraStoreShell-*-uber.jar ]; then \
        mv /app/JettraStoreShell-*-uber.jar /app/JettraStoreShell.jar && rm -f /app/JettraStoreShell-*.jar; \
    elif [ -f /app/JettraStoreShell-*.jar ]; then \
        mv /app/JettraStoreShell-*.jar /app/JettraStoreShell.jar; \
    fi && \
    chmod +x /app/start.sh && \
    chown -R 1000:1000 /home/jettra /app

# Variables de entorno predeterminadas
ENV HOME=/home/jettra \
    MICROPROFILE_CONFIG_FILE=/app/config/microprofile-config.properties \
    JETTRA_HOST=jettra-node-01 \
    JETTRA_PORT=50001 \
    JAVA_OPTS="-Xms64m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=128m -XX:+UseContainerSupport -XX:+UseG1GC -XX:+UseStringDeduplication -XX:CICompilerCount=2 -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders --enable-preview --enable-native-access=ALL-UNNAMED"

USER 1000:1000

# Entrada interactiva a la consola JettraStoreShell usando microprofile-config.properties
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dmicroprofile.config.file=${MICROPROFILE_CONFIG_FILE} -Djettra.shell.config.file=${MICROPROFILE_CONFIG_FILE} -jar /app/JettraStoreShell.jar \"$@\"", "--"]
