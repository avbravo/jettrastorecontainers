# ==============================================================================
# JettraStorePolice3D Visualizer & Cluster Monitor (Java 25+ Panama FFM & Raylib)
# ==============================================================================
FROM bellsoft/liberica-runtime-container:jre-25-stream-glibc
LABEL maintainer="Jettra Ecosystem <dev@jettra.io>"
LABEL description="JettraStorePolice3D - Monitor visual 3D para clúster JettraStore con Raylib y Java 25"

# Instalar dependencias gráficas X11, Mesa OpenGL, fuentes, xvfb y utilidades
RUN if command -v apk > /dev/null; then \
        apk add --no-cache \
            mesa-gl \
            mesa-dri-gallium \
            libx11 \
            libxcursor \
            libxrandr \
            libxinerama \
            libxi \
            libxext \
            fontconfig \
            ttf-dejavu \
            xvfb \
            curl \
            ca-certificates \
            libstdc++ \
            tzdata; \
    elif command -v apt-get > /dev/null; then \
        apt-get update && apt-get install -y --no-install-recommends \
            libgl1-mesa-glx \
            libgl1-mesa-dri \
            libx11-6 \
            libxcursor1 \
            libxrandr2 \
            libxinerama1 \
            libxi6 \
            libxext6 \
            fonts-dejavu-core \
            xvfb \
            curl \
            ca-certificates \
            libstdc++6 \
            tzdata && rm -rf /var/lib/apt/lists/*; \
    fi

WORKDIR /app

# Crear directorios de configuración y usuario
RUN mkdir -p /app/config /home/jettra && \
    chown -R 1000:1000 /app /home/jettra

# Copiar el Uber JAR autónomo y archivos de configuración
COPY target/JettraStorePolice3D-*-uber.jar /app/JettraStorePolice3D.jar
COPY config/jettra.config /app/config/jettra.config
COPY entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh

# Variables de entorno predeterminadas
ENV HOME=/home/jettra \
    DISPLAY=:0 \
    JETTRA_DOCKER_COMPOSE=false \
    JAVA_OPTS="-Xms512m -Xmx2g -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders --enable-preview --enable-native-access=ALL-UNNAMED -Djettra.config.path=/app/config/jettra.config"

USER 1000:1000

# Punto de entrada con soporte para X11 físico o Xvfb headless
ENTRYPOINT ["/app/entrypoint.sh"]
