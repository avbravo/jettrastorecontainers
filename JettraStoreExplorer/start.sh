#!/bin/bash
# ==============================================================================
# Script de inicio para JettraStoreExplorer
# Lee los parámetros de servidor y clúster desde microprofile-config.properties
# ==============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 1. Resolver archivo microprofile-config.properties
if [ -n "${MICROPROFILE_CONFIG_FILE:-}" ] && [ -f "$MICROPROFILE_CONFIG_FILE" ]; then
    CONFIG_FILE="$MICROPROFILE_CONFIG_FILE"
elif [ -f "$SCRIPT_DIR/config/microprofile-config.properties" ]; then
    CONFIG_FILE="$SCRIPT_DIR/config/microprofile-config.properties"
elif [ -f "$SCRIPT_DIR/microprofile-config.properties" ]; then
    CONFIG_FILE="$SCRIPT_DIR/microprofile-config.properties"
elif [ -f "$SCRIPT_DIR/src/main/resources/META-INF/microprofile-config.properties" ]; then
    CONFIG_FILE="$SCRIPT_DIR/src/main/resources/META-INF/microprofile-config.properties"
else
    echo "[ERROR] No se encontró microprofile-config.properties en $SCRIPT_DIR"
    exit 1
fi

# 2. Resolver JAR ejecutable
if [ -f "$SCRIPT_DIR/JettraStoreExplorer.jar" ]; then
    JAR_FILE="$SCRIPT_DIR/JettraStoreExplorer.jar"
else
    JAR_FILE=$(find "$SCRIPT_DIR" "$SCRIPT_DIR/target" -maxdepth 1 -type f -name "JettraStoreExplorer-*.jar" ! -name "original-*" 2>/dev/null | head -n 1)
fi

if [ -z "$JAR_FILE" ] || [ ! -f "$JAR_FILE" ]; then
    echo "[ERROR] No se encontró el JAR de JettraStoreExplorer. Ejecute './install.sh' o 'mvn package -DskipTests'."
    exit 1
fi

JAVA_OPTS_DEFAULT="-Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=192m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:CICompilerCount=2 -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders --enable-preview --enable-native-access=ALL-UNNAMED"

STATE_DIR_ARG=""
if [ -n "${JETTRA_CLUSTER_STATE_CONFIG_DIR:-}" ]; then
    mkdir -p "${JETTRA_CLUSTER_STATE_CONFIG_DIR}"
    STATE_DIR_ARG="-Djettra.cluster_state_config_dir=${JETTRA_CLUSTER_STATE_CONFIG_DIR}"
fi

exec java ${JAVA_OPTS:-$JAVA_OPTS_DEFAULT} \
    -Dmicroprofile.config.file="${CONFIG_FILE}" \
    -Djettra.explorer.config.file="${CONFIG_FILE}" \
    ${STATE_DIR_ARG} \
    -jar "${JAR_FILE}" "$@"
