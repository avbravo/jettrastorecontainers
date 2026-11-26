#!/bin/bash
# ==============================================================================
# Script de inicio para nodo JettraStore (Java 25 LTS)
# ==============================================================================
# Lee los parámetros principales (storage.path, jettra.network.grpc.port, jettra.network.rest.port)
# directamente desde microprofile-config.properties y cluster.config.
#
# Para incorporar un nuevo nodo al clúster:
#   1. Cree un directorio con: JettraStore-1.0-SNAPSHOT-uber.jar, start-node.sh, y la carpeta config/
#   2. En config/cluster.config indique únicamente la información de este nuevo nodo
#   3. Revise config/microprofile-config.properties con el ID del nodo y su ruta de almacenamiento
#   4. Ajuste o ejecute start-node.sh indicando el ID del nodo (ej. ./start-node.sh node-04 SECONDARY)
#   5. Añada el nodo al clúster desde JettraStoreExplorer (interfaz web) o JettraStoreShell (CLI)
#
# Uso:
#   ./start-node.sh [NODE_ID] [NODE_ROLE] [CLUSTER_CONFIG] [MICROPROFILE_CONFIG]
# Ejemplos:
#   ./start-node.sh node-01 PRIMARY
#   ./start-node.sh node-02 SECONDARY
#   ./start-node.sh node-03 SECONDARY
#   ./start-node.sh node-04 SECONDARY config/cluster.config config/microprofile-config.properties
# ==============================================================================

NODE_ID=${1:-node-01}
if [ -n "$2" ]; then
    NODE_ROLE="$2"
elif [ "$NODE_ID" = "node-01" ]; then
    NODE_ROLE="PRIMARY"
else
    NODE_ROLE="SECONDARY"
fi

# Detección inteligente de archivos de configuración
if [ -n "$3" ]; then
    CLUSTER_CONFIG="$3"
elif [ -f "config/cluster.config" ]; then
    CLUSTER_CONFIG="config/cluster.config"
elif [ -f "cluster.config" ]; then
    CLUSTER_CONFIG="cluster.config"
else
    CLUSTER_CONFIG="config/cluster.config"
fi

if [ -n "$4" ]; then
    MP_CONFIG="$4"
elif [ -f "config/microprofile-config.properties" ]; then
    MP_CONFIG="config/microprofile-config.properties"
elif [ -f "microprofile-config.properties" ]; then
    MP_CONFIG="microprofile-config.properties"
else
    MP_CONFIG="config/microprofile-config.properties"
fi

# Detección de JAR ejecutable autónomo
if [ -f "JettraStore-1.0-SNAPSHOT-uber.jar" ]; then
    JAR_FILE="JettraStore-1.0-SNAPSHOT-uber.jar"
elif [ -f "target/JettraStore-1.0-SNAPSHOT-uber.jar" ]; then
    JAR_FILE="target/JettraStore-1.0-SNAPSHOT-uber.jar"
elif [ -f "JettraStore.jar" ]; then
    JAR_FILE="JettraStore.jar"
else
    JAR_FILE="JettraStore-1.0-SNAPSHOT-uber.jar"
fi

# Opciones JVM optimizadas para bajo consumo de RAM (sobrescribible con JAVA_OPTS)
JETTRA_JAVA_OPTS="${JAVA_OPTS:--Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=256m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:CICompilerCount=2}"

echo "=============================================================================="
echo "Iniciando nodo JettraStore: ID=${NODE_ID}, Rol=${NODE_ROLE}"
echo "Topología local : ${CLUSTER_CONFIG}"
echo "MicroProfile    : ${MP_CONFIG}"
echo "JAR Binario     : ${JAR_FILE}"
echo "JVM Opts        : ${JETTRA_JAVA_OPTS}"
echo "=============================================================================="

# shellcheck disable=SC2086
CLUSTER_FLAG=""
if [ -f "${CLUSTER_CONFIG}" ]; then
    CLUSTER_FLAG="-Dcluster.config.path=${CLUSTER_CONFIG}"
fi
STATE_DIR_FLAG=""
if [ -n "${JETTRA_CLUSTER_STATE_CONFIG_DIR:-}" ]; then
    STATE_DIR_FLAG="-Djettra.cluster_state_config_dir=${JETTRA_CLUSTER_STATE_CONFIG_DIR}"
fi

exec java --enable-preview \
     --enable-native-access=ALL-UNNAMED \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+UseCompactObjectHeaders \
     ${JETTRA_JAVA_OPTS} \
     -Dcluster.node.id="${NODE_ID}" \
     -Dcluster.node.role="${NODE_ROLE}" \
     -Djettra.node.id="${NODE_ID}" \
     -Djettra.node.role="${NODE_ROLE}" \
     ${CLUSTER_FLAG} \
     ${STATE_DIR_FLAG} \
     -Dmicroprofile.config.file="${MP_CONFIG}" \
     -jar "${JAR_FILE}"
