# ==============================================================================
# JettraStore Distributed Multi-Model Database (Java 25+ Panama FFM & Raft)
# ==============================================================================
FROM bellsoft/liberica-runtime-container:jre-25-stream-musl
LABEL maintainer="Jettra Ecosystem <dev@jettra.io>"
LABEL description="JettraStore - Motor de Base de Datos Multimodelo Distribuido en Java 25+"

# Instalar curl para healthchecks y utilidades del sistema (compatible con Alpine/musl y Debian/glibc)
RUN if command -v apk > /dev/null; then \
        apk add --no-cache curl ca-certificates tzdata; \
    elif command -v apt-get > /dev/null; then \
        apt-get update && apt-get install -y --no-install-recommends curl ca-certificates tzdata && rm -rf /var/lib/apt/lists/*; \
    fi

WORKDIR /app

# Crear directorio de datos persistentes y asignación de permisos
RUN mkdir -p /jettra/data /jettra/config && \
    chown -R 1000:1000 /jettra /app

# Copiar el Uber JAR autónomo (Fat JAR con todas las librerías integradas)
COPY target/JettraStore-*-uber.jar /app/JettraStore.jar
COPY src/main/resources/database.properties /app/config/database.properties

# Variables de entorno predeterminadas para nodo autónomo o clúster
ENV JETTRA_NODE_ID=node-01 \
    JETTRA_NODE_ROLE=PRIMARY \
    JETTRA_STORAGE_PATH=/jettra/data \
    JETTRA_GRPC_PORT=9091 \
    JETTRA_REST_PORT=8080 \
    JETTRA_ADMIN_USERNAME=admin \
    JETTRA_ADMIN_PASSWORD=admin-jettra \
    JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseZGC -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders --enable-preview --enable-native-access=ALL-UNNAMED"

# Exponer puertos: REST HTTP (8080) y Puerto de Clúster / gRPC (9091)
EXPOSE 8080 9091

# Volumen para almacenamiento de datos físicos .jettra
VOLUME ["/jettra/data"]

# Verificación de salud periódica mediante endpoint REST
HEALTHCHECK --interval=10s --timeout=5s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:${JETTRA_REST_PORT}/api/v1/health || exit 1

# Usuario no root seguro
USER 1000:1000

# Inicio del servidor autónomo JettraStore ejecutando directamente el Uber JAR
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/JettraStore.jar"]
