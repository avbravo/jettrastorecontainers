# ==============================================================================
# JettraStoreExplorer Web Console (Java 25+ JettraFlux, JettraServer & Driver)
# Multi-stage Alpaquita/musl build optimizado para bajo consumo de RAM y CPU
# ==============================================================================
FROM bellsoft/liberica-runtime-container:jre-25-stream-musl AS packager
WORKDIR /staging
COPY target/JettraStoreExplorer-*.jar /staging/
COPY src/main/resources/META-INF/microprofile-config.properties /staging/config/microprofile-config.properties
COPY start.sh /staging/start.sh
RUN rm -f /staging/original-JettraStoreExplorer-*.jar && \
    if [ -f /staging/JettraStoreExplorer-*-uber.jar ]; then \
        mv /staging/JettraStoreExplorer-*-uber.jar /staging/JettraStoreExplorer.jar && rm -f /staging/JettraStoreExplorer-*.jar; \
    elif [ -f /staging/JettraStoreExplorer-*.jar ]; then \
        mv /staging/JettraStoreExplorer-*.jar /staging/JettraStoreExplorer.jar; \
    fi && \
    chmod +x /staging/start.sh

FROM bellsoft/liberica-runtime-container:jre-25-stream-musl
LABEL maintainer="Jettra Ecosystem <dev@jettra.io>"
LABEL description="JettraStoreExplorer - Consola Web Multimodelo de bajo consumo para JettraStore"

RUN if command -v apk > /dev/null; then \
        apk add --no-cache curl ca-certificates tzdata && rm -rf /var/cache/apk/* /tmp/*; \
    elif command -v apt-get > /dev/null; then \
        apt-get update && apt-get install -y --no-install-recommends curl ca-certificates tzdata && rm -rf /var/lib/apt/lists/* /tmp/*; \
    fi

WORKDIR /app

RUN mkdir -p /app/config /app/memory /app/cluster-state && \
    chown -R 1000:1000 /app

COPY --from=packager --chown=1000:1000 /staging/JettraStoreExplorer.jar /app/JettraStoreExplorer.jar
COPY --from=packager --chown=1000:1000 /staging/config/microprofile-config.properties /app/config/microprofile-config.properties
COPY --from=packager --chown=1000:1000 /staging/start.sh /app/start.sh

ENV SERVER_HOST=0.0.0.0 \
    SERVER_PORT=8085 \
    STORAGE_MODE=OFF_HEAP \
    JETTRA_DOCKER_COMPOSE=true \
    MICROPROFILE_CONFIG_FILE=/app/config/microprofile-config.properties \
    JETTRA_CONNECTION_CONFIG_DIR=/app/config \
    JETTRA_CLUSTER_STATE_CONFIG_DIR=/app/cluster-state \
    CLUSTER_IP_NODES=127.0.0.1:50001 \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:+UseG1GC -XX:+UseStringDeduplication -XX:CICompilerCount=2 -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:MaxRAMPercentage=70.0 --enable-preview --enable-native-access=ALL-UNNAMED" \
    JAVA_OPTS="-Xms96m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=128m"

EXPOSE 8085

VOLUME ["/app/memory", "/app/config", "/app/cluster-state"]

HEALTHCHECK --interval=10s --timeout=5s --start-period=8s --retries=3 \
    CMD curl -fsS http://localhost:${SERVER_PORT}/health || exit 1

USER 1000:1000

ENTRYPOINT ["sh", "-c", "mkdir -p \"${JETTRA_CLUSTER_STATE_CONFIG_DIR:-/app/cluster-state}\"; exec java $JAVA_OPTS -Dmicroprofile.config.file=${MICROPROFILE_CONFIG_FILE} -Djettra.explorer.config.file=${MICROPROFILE_CONFIG_FILE} -Djettra.cluster_state_config_dir=${JETTRA_CLUSTER_STATE_CONFIG_DIR:-/app/cluster-state} -Dcluster.ip.nodes=${CLUSTER_IP_NODES:-127.0.0.1:50001} -jar /app/JettraStoreExplorer.jar \"$@\"", "--"]
