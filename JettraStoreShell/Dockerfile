# ==============================================================================
# JettraStoreShell Interactive CLI (Java 25+ Panama FFM & JettraStoreDriver)
# Multi-stage Alpaquita/musl build optimizado para bajo consumo de RAM y CPU
# ==============================================================================
FROM bellsoft/liberica-runtime-container:jre-25-stream-musl AS packager
WORKDIR /staging
COPY target/JettraStoreShell-*.jar /staging/
COPY src/main/resources/META-INF/microprofile-config.properties /staging/config/microprofile-config.properties
COPY start.sh /staging/start.sh
RUN rm -f /staging/original-JettraStoreShell-*.jar && \
    if [ -f /staging/JettraStoreShell-*-uber.jar ]; then \
        mv /staging/JettraStoreShell-*-uber.jar /staging/JettraStoreShell.jar && rm -f /staging/JettraStoreShell-*.jar; \
    elif [ -f /staging/JettraStoreShell-*.jar ]; then \
        mv /staging/JettraStoreShell-*.jar /staging/JettraStoreShell.jar; \
    fi && \
    chmod +x /staging/start.sh

FROM bellsoft/liberica-runtime-container:jre-25-stream-musl
LABEL maintainer="Jettra Ecosystem <dev@jettra.io>"
LABEL description="JettraStoreShell - Consola CLI interactiva de bajo consumo para JettraStore"

RUN if command -v apk > /dev/null; then \
        apk add --no-cache curl ca-certificates tzdata && rm -rf /var/cache/apk/* /tmp/*; \
    elif command -v apt-get > /dev/null; then \
        apt-get update && apt-get install -y --no-install-recommends curl ca-certificates tzdata && rm -rf /var/lib/apt/lists/* /tmp/*; \
    fi

WORKDIR /app

RUN mkdir -p /home/jettra/.jettra /app/config /app/cluster-state && \
    chown -R 1000:1000 /home/jettra /app

COPY --from=packager --chown=1000:1000 /staging/JettraStoreShell.jar /app/JettraStoreShell.jar
COPY --from=packager --chown=1000:1000 /staging/config/microprofile-config.properties /app/config/microprofile-config.properties
COPY --from=packager --chown=1000:1000 /staging/start.sh /app/start.sh

ENV HOME=/home/jettra \
    MICROPROFILE_CONFIG_FILE=/app/config/microprofile-config.properties \
    JETTRA_CONNECTION_CONFIG_DIR=/app/config \
    JETTRA_CLUSTER_STATE_CONFIG_DIR=/app/cluster-state \
    CLUSTER_IP_NODES=127.0.0.1:50001 \
    JETTRA_HOST=jettra-node-01 \
    JETTRA_PORT=50001 \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:+UseG1GC -XX:+UseStringDeduplication -XX:CICompilerCount=2 -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:MaxRAMPercentage=70.0 --enable-preview --enable-native-access=ALL-UNNAMED" \
    JAVA_OPTS="-Xms48m -Xmx192m -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=96m"

VOLUME ["/home/jettra/.jettra", "/app/config", "/app/cluster-state"]

USER 1000:1000

ENTRYPOINT ["sh", "-c", "mkdir -p \"${JETTRA_CLUSTER_STATE_CONFIG_DIR:-/app/cluster-state}\"; exec java $JAVA_OPTS -Dmicroprofile.config.file=${MICROPROFILE_CONFIG_FILE} -Djettra.shell.config.file=${MICROPROFILE_CONFIG_FILE} -Djettra.cluster_state_config_dir=${JETTRA_CLUSTER_STATE_CONFIG_DIR:-/app/cluster-state} -Dcluster.ip.nodes=${CLUSTER_IP_NODES:-127.0.0.1:50001} -jar /app/JettraStoreShell.jar \"$@\"", "--"]
