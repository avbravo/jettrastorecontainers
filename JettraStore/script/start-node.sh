#!/bin/bash
# ==============================================================================
# Script de inicio para nodo JettraStore (Java 25 LTS)
# ==============================================================================
# Modelo operativo PRIMARY / SECONDARY con estado persistente (RF-32 / RF-33 / RF-36):
#   - Solo el nodo PRIMARY requiere 'cluster.config' (semilla inicial cuando aún
#     no existe 'cluster-state.config' en 'jettra.cluster_state_config_dir').
#   - Los nodos SECONDARY arrancan limpiamente SIN exigir 'cluster.config', esperan
#     ser detectados o añadidos dinámicamente desde PRIMARY/Driver/Shell/Explorer
#     y reciben/persisten 'cluster-state.config' en 'jettra.cluster_state_config_dir'.
#
# Uso:
#   ./start-node.sh [NODE_ID] [NODE_ROLE] [CLUSTER_CONFIG] [MICROPROFILE_CONFIG]
# Ejemplos:
#   ./start-node.sh node-01 PRIMARY
#   ./start-node.sh node-02 SECONDARY
#   ./start-node.sh node-03 SECONDARY
#   ./start-node.sh node-04 SECONDARY "" config/microprofile-config.properties
# ==============================================================================

NODE_ID=${1:-node-01}
if [ -n "$2" ]; then
    NODE_ROLE="$2"
elif [ "$NODE_ID" = "node-01" ]; then
    NODE_ROLE="PRIMARY"
else
    NODE_ROLE="SECONDARY"
fi
NODE_ROLE=$(printf '%s' "$NODE_ROLE" | tr '[:lower:]' '[:upper:]')

# Detección de microprofile-config.properties
if [ -n "$4" ]; then
    MP_CONFIG="$4"
elif [ -n "${MICROPROFILE_CONFIG_FILE:-}" ] && [ -f "${MICROPROFILE_CONFIG_FILE}" ]; then
    MP_CONFIG="${MICROPROFILE_CONFIG_FILE}"
elif [ -f "config/microprofile-config.properties" ]; then
    MP_CONFIG="config/microprofile-config.properties"
elif [ -f "microprofile-config.properties" ]; then
    MP_CONFIG="microprofile-config.properties"
else
    MP_CONFIG="config/microprofile-config.properties"
fi

# Resolver jettra.cluster_state_config_dir respetando env -> microprofile-config.properties -> default
resolve_state_dir() {
    local raw=""
    if [ -n "${JETTRA_CLUSTER_STATE_CONFIG_DIR:-}" ]; then
        raw="${JETTRA_CLUSTER_STATE_CONFIG_DIR}"
    elif [ -f "${MP_CONFIG}" ]; then
        raw=$(grep -E '^[[:space:]]*jettra\.cluster_state_config_dir[[:space:]]*=' "${MP_CONFIG}" | tail -n 1 | cut -d'=' -f2- | xargs 2>/dev/null || true)
    fi
    if [ -z "${raw}" ]; then
        raw="~/jettra-cluster-state"
    fi
    case "${raw}" in
        "~") raw="${HOME}" ;;
        "~/"*) raw="${HOME}/${raw#~/}" ;;
    esac
    printf '%s' "${raw}"
}

STATE_DIR="$(resolve_state_dir)"
mkdir -p "${STATE_DIR}"
STATE_FILE="${STATE_DIR}/cluster-state.config"

# Resolución de cluster.config según rol (solo obligatorio/automático en PRIMARY)
EXPLICIT_CLUSTER_ARG="${3:-${JETTRA_CLUSTER_CONFIG_PATH:-}}"
CLUSTER_CONFIG=""
if [ -n "${EXPLICIT_CLUSTER_ARG}" ]; then
    CLUSTER_CONFIG="${EXPLICIT_CLUSTER_ARG}"
elif [ "${NODE_ROLE}" = "PRIMARY" ]; then
    if [ -f "config/cluster.config" ]; then
        CLUSTER_CONFIG="config/cluster.config"
    elif [ -f "cluster.config" ]; then
        CLUSTER_CONFIG="cluster.config"
    elif [ -f "${STATE_DIR}/cluster.config" ]; then
        CLUSTER_CONFIG="${STATE_DIR}/cluster.config"
    fi
fi

MULTINODE_CFG="on"
if [ -f "${MP_CONFIG}" ]; then
    MULTINODE_VAL=$(grep -E '^[[:space:]]*cluster\.multinode\.active[[:space:]]*=' "${MP_CONFIG}" | tail -n 1 | cut -d'=' -f2- | xargs 2>/dev/null || true)
    if [ -n "${MULTINODE_VAL}" ]; then
        MULTINODE_CFG="${MULTINODE_VAL}"
    fi
fi

if [ "${NODE_ROLE}" = "PRIMARY" ] && [ ! -f "${STATE_FILE}" ] && [ -z "${CLUSTER_CONFIG}" ] && [ "${MULTINODE_CFG}" != "off" ] && [ "${MULTINODE_CFG}" != "false" ]; then
    echo "[ERROR] El nodo PRIMARY requiere cluster.config (o cluster-state.config existente en '${STATE_DIR}') cuando multinodo está activo."
    exit 1
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
if [ -n "${CLUSTER_CONFIG}" ] && [ -f "${CLUSTER_CONFIG}" ]; then
    echo "Semilla Clúster : ${CLUSTER_CONFIG}"
elif [ "${NODE_ROLE}" = "SECONDARY" ]; then
    echo "Semilla Clúster : (no requerida en SECONDARY; espera detección/sync desde PRIMARY)"
else
    echo "Semilla Clúster : (usando estado persistente existente o modo single-node)"
fi
echo "Estado Clúster  : ${STATE_DIR} (cluster-state.config)"
echo "MicroProfile    : ${MP_CONFIG}"
echo "JAR Binario     : ${JAR_FILE}"
echo "JVM Opts        : ${JETTRA_JAVA_OPTS}"
echo "=============================================================================="

CLUSTER_FLAG=""
if [ -n "${CLUSTER_CONFIG}" ] && [ -f "${CLUSTER_CONFIG}" ]; then
    CLUSTER_FLAG="-Dcluster.config.path=${CLUSTER_CONFIG}"
fi
STATE_DIR_FLAG="-Djettra.cluster_state_config_dir=${STATE_DIR}"

# shellcheck disable=SC2086
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
