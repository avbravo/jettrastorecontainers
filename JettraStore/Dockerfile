# ==============================================================================
# JettraStore Distributed Multi-Model Database (Java 25+ Panama FFM & Raft)
# Compatible con CRaC / CRIU para arranque instantáneo en caliente (< 20 ms)
# ==============================================================================
FROM bellsoft/liberica-runtime-container:jre-25-stream-musl
LABEL maintainer="Jettra Ecosystem <dev@jettra.io>"
LABEL description="JettraStore - Motor de Base de Datos Multimodelo Distribuido en Java 25+ con CRaC y Off-Heap FFM"

# Instalar curl para healthchecks y utilidades del sistema (compatible con Alpine/musl y Debian/glibc)
RUN if command -v apk > /dev/null; then \
        apk add --no-cache curl ca-certificates tzdata && rm -rf /var/cache/apk/*; \
    elif command -v apt-get > /dev/null; then \
        apt-get update && apt-get install -y --no-install-recommends curl ca-certificates tzdata && rm -rf /var/lib/apt/lists/*; \
    fi

WORKDIR /app

# Crear directorio de datos persistentes, punto de checkpoint CRaC y permisos
RUN mkdir -p /jettra/data /jettra/config /jettra/checkpoint && \
    chown -R 1000:1000 /jettra /app

# Copiar JAR autónomo optimizado y archivo de configuración base (sin capas redundantes)
COPY target/JettraStore-*.jar /app/
COPY src/main/resources/database.properties /app/config/database.properties

# Garantizar el archivo /app/JettraStore.jar sin duplicar capas pesadas en la imagen
RUN if [ -f /app/JettraStore-*-uber.jar ]; then \
        mv /app/JettraStore-*-uber.jar /app/JettraStore.jar && rm -f /app/JettraStore-*.jar; \
    elif [ -f /app/JettraStore-*.jar ]; then \
        mv /app/JettraStore-*.jar /app/JettraStore.jar; \
    fi

# Variables de entorno predeterminadas para nodo autónomo o clúster
# Configuración de alto rendimiento para base de datos con ZGC (Zero Garbage Collection Pauses < 1ms):
# -XX:+UseContainerSupport respeta los cgroups y límites de memoria del contenedor Docker.
# -XX:+UseZGC recolector de ultra-baja latencia concurrente para bases de datos de alto rendimiento.
# -XX:+UseCompactObjectHeaders (Java 25 Lilliput) reduce la sobrecarga de encabezados de objetos a 64 bits.
# -Xms512m -Xmx2g balance equilibrado entre alta tasa de transacciones concurrentes y consumo controlado de RAM.
ENV JETTRA_NODE_ID=node-01 \
    JETTRA_NODE_ROLE=PRIMARY \
    JETTRA_STORAGE_PATH=/jettra/data \
    JETTRA_STORAGE_MODE=OFF_HEAP \
    JETTRA_CRAC_CHECKPOINT_PATH=/jettra/checkpoint \
    JETTRA_CRAC_RESTORE=false \
    JETTRA_GRPC_PORT=9091 \
    JETTRA_REST_PORT=8080 \
    JETTRA_ADMIN_USERNAME=admin \
    JETTRA_ADMIN_PASSWORD=admin-jettra \
    JAVA_OPTS="--enable-preview --enable-native-access=ALL-UNNAMED -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:+UseContainerSupport -XX:+UseZGC -Xms512m -Xmx2g"

# Exponer puertos: REST HTTP (8080) y Puerto de Clúster / gRPC (9091)
EXPOSE 8080 9091

# Volúmenes para almacenamiento persistente y snapshots de memoria CRaC
VOLUME ["/jettra/data", "/jettra/checkpoint"]

# Verificación de salud periódica mediante endpoint REST
HEALTHCHECK --interval=10s --timeout=5s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:${JETTRA_REST_PORT}/api/v1/health || exit 1

# Usuario no root seguro
USER 1000:1000

# Inicio del servidor autónomo JettraStore
# Si JETTRA_CRAC_RESTORE=true y existen checkpoints en /jettra/checkpoint, restaura instantáneamente con CRaC
ENTRYPOINT ["sh", "-c", "if [ \"$JETTRA_CRAC_RESTORE\" = \"true\" ] && [ -d \"$JETTRA_CRAC_CHECKPOINT_PATH\" ] && [ \"$(ls -A $JETTRA_CRAC_CHECKPOINT_PATH 2>/dev/null)\" ]; then \
        echo '[CRaC] Restaurando JettraStore instantáneamente desde checkpoint CRIU en '$JETTRA_CRAC_CHECKPOINT_PATH'...'; \
        exec java -XX:CRaCRestoreFrom=$JETTRA_CRAC_CHECKPOINT_PATH; \
    else \
        exec java $JAVA_OPTS -Djettra.storage.mode=${JETTRA_STORAGE_MODE:-OFF_HEAP} -jar /app/JettraStore.jar; \
    fi"]
