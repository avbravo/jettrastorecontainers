# ==============================================================================
# JettraStoreExplorer Web Console (Java 25+ JettraFlux & JettraServer)
# ==============================================================================
FROM bellsoft/liberica-runtime-container:jre-25-stream-musl
LABEL maintainer="Jettra Ecosystem <dev@jettra.io>"
LABEL description="JettraStoreExplorer - Consola Web de Administración Multimodelo para JettraStore"

# Instalar utilidades básicas para healthcheck (compatible con Alpine/musl y Debian/glibc)
RUN if command -v apk > /dev/null; then \
        apk add --no-cache curl ca-certificates tzdata && rm -rf /var/cache/apk/*; \
    elif command -v apt-get > /dev/null; then \
        apt-get update && apt-get install -y --no-install-recommends curl ca-certificates tzdata && rm -rf /var/lib/apt/lists/*; \
    fi

WORKDIR /app

# Crear directorios de configuración y persistencia de sesión/nodos
RUN mkdir -p /app/config /app/memory && \
    chown -R 1000:1000 /app

# Copiar JAR ejecutable, microprofile-config.properties y script de inicio
COPY target/JettraStoreExplorer-*.jar /app/
COPY src/main/resources/META-INF/microprofile-config.properties /app/config/microprofile-config.properties
COPY start.sh /app/start.sh

RUN rm -f /app/original-JettraStoreExplorer-*.jar && \
    if [ -f /app/JettraStoreExplorer-*-uber.jar ]; then \
        mv /app/JettraStoreExplorer-*-uber.jar /app/JettraStoreExplorer.jar && rm -f /app/JettraStoreExplorer-*.jar; \
    elif [ -f /app/JettraStoreExplorer-*.jar ]; then \
        mv /app/JettraStoreExplorer-*.jar /app/JettraStoreExplorer.jar; \
    fi && \
    chmod +x /app/start.sh && \
    chown -R 1000:1000 /app

# Variables de entorno predeterminadas
ENV SERVER_HOST=0.0.0.0 \
    SERVER_PORT=8085 \
    STORAGE_MODE=OFF_HEAP \
    MICROPROFILE_CONFIG_FILE=/app/config/microprofile-config.properties \
    JAVA_OPTS="-Xms256m -Xmx1g -XX:+UseContainerSupport -XX:+UseZGC -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders --enable-preview --enable-native-access=ALL-UNNAMED"

EXPOSE 8085

HEALTHCHECK --interval=10s --timeout=5s --start-period=8s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/health || exit 1

USER 1000:1000

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dmicroprofile.config.file=${MICROPROFILE_CONFIG_FILE} -Djettra.explorer.config.file=${MICROPROFILE_CONFIG_FILE} -jar /app/JettraStoreExplorer.jar \"$@\"", "--"]
