#!/bin/sh
set -e

# Comprobar si se dispone de un servidor X11 conectado en /tmp/.X11-unix
HAS_X11_SOCKET=0
if [ -d "/tmp/.X11-unix" ] && [ "$(ls -A /tmp/.X11-unix 2>/dev/null)" ]; then
    HAS_X11_SOCKET=1
fi

# Si no hay socket X11 disponible o se solicita XVFB expresamente, iniciar Xvfb virtual
if [ "$HAS_X11_SOCKET" -eq 0 ] || [ "$HEADLESS" = "true" ] || [ "$HEADLESS" = "1" ]; then
    echo "[INFO] No se detectó socket X11 físico en /tmp/.X11-unix o modo HEADLESS activo."
    echo "[INFO] Iniciando Xvfb (Virtual Framebuffer en DISPLAY=:99, 1280x720x24)..."
    mkdir -p /tmp/.X11-unix 2>/dev/null || true
    chmod 1777 /tmp/.X11-unix 2>/dev/null || true
    Xvfb :99 -screen 0 1280x720x24 -ac +extension GLX +render -noreset &
    export DISPLAY=:99
    sleep 1
else
    echo "[INFO] Socket X11 detectado. Conectando a pantalla física: ${DISPLAY:-:0}"
    export DISPLAY="${DISPLAY:-:0}"
fi

# Ejecutar JettraStorePolice3D mediante su Uber JAR
exec java $JAVA_OPTS -jar /app/JettraStorePolice3D.jar "$@"
