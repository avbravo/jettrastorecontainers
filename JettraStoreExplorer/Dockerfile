# Dockerfile para JettraStore Explorer
#FROM eclipse-temurin:25-jdk
FROM bellsoft/liberica-runtime-container:jre-25-stream-musl
WORKDIR /app

COPY target/JettraStoreExplorer-1.0.0-SNAPSHOT.jar /app/app.jar

EXPOSE 8085

ENV SERVER_PORT=8085
ENV STORAGE_MODE=OFF_HEAP

ENTRYPOINT ["java", "--enable-preview", "-jar", "/app/app.jar"]

