# ==============================================================================
# jettrastoretestcontainer - Contenedor aislado para pruebas de estrés (RF-20)
# Contiene JettraStoreMeter (Uber JAR Java 25+) y planes JMeter
# ==============================================================================
FROM bellsoft/liberica-runtime-container:jre-25-stream-musl AS packager
WORKDIR /staging
COPY target/JettraStoreMeter-*.jar /staging/
COPY plans/ /staging/plans/
RUN rm -f /staging/original-JettraStoreMeter-*.jar && \
    mv /staging/JettraStoreMeter-*.jar /staging/JettraStoreMeter.jar

FROM bellsoft/liberica-runtime-container:jre-25-stream-musl
LABEL maintainer="Jettra Ecosystem <dev@jettra.io>"
LABEL description="jettrastoretestcontainer - Entorno aislado de pruebas de estrés y tolerancia con JettraStoreMeter"

RUN if command -v apk > /dev/null; then \
        apk add --no-cache curl ca-certificates tzdata && rm -rf /var/cache/apk/* /tmp/*; \
    elif command -v apt-get > /dev/null; then \
        apt-get update && apt-get install -y --no-install-recommends curl ca-certificates tzdata && rm -rf /var/lib/apt/lists/* /tmp/*; \
    fi

WORKDIR /app

RUN mkdir -p /reports /tmp/jettra-meter-data && \
    chown -R 1000:1000 /app /reports /tmp/jettra-meter-data

COPY --from=packager --chown=1000:1000 /staging/JettraStoreMeter.jar /app/JettraStoreMeter.jar
COPY --from=packager --chown=1000:1000 /staging/plans/ /app/plans/

ENV JETTRA_HOST=jettra-stress-target \
    JETTRA_PORT=50001 \
    JETTRA_REST_PORT=60001 \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:+UseG1GC -XX:+UseStringDeduplication -XX:CICompilerCount=2 -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders --enable-preview --enable-native-access=ALL-UNNAMED" \
    JAVA_OPTS="-Xms256m -Xmx1536m -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=256m -Djettra.storage.path=/tmp/jettra-meter-data"

VOLUME ["/reports"]

USER 1000:1000

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp /app/JettraStoreMeter.jar io.jettra.meter.JettraToleranceSuite \"$@\"", "--"]
