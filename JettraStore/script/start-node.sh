#!/bin/bash
# ==============================================================================
# Script simplificado de inicio para nodo JettraStore (Java 25 LTS - RF-37)
# ==============================================================================
# Resuelve identidad, rol, IP, puertos gRPC/REST y jettra.cluster_state_config_dir
# directamente desde microprofile-config.properties (sin exigir cluster.config en SECONDARY).
# Uso: ./start-node.sh [NODE_ID] [NODE_ROLE] [CLUSTER_CONFIG] [MICROPROFILE_CONFIG]
# ==============================================================================

if [ -n "$4" ]; then
    MP_CONFIG="$4"
elif [ -n "${MICROPROFILE_CONFIG_FILE:-}" ] && [ -f "${MICROPROFILE_CONFIG_FILE}" ]; then
    MP_CONFIG="${MICROPROFILE_CONFIG_FILE}"
elif [ -f "config/microprofile-config.properties" ]; then
    MP_CONFIG="config/microprofile-config.properties"
else
    MP_CONFIG="microprofile-config.properties"
fi

read_mp_prop() {
    local key="$1"
    if [ -f "${MP_CONFIG}" ]; then
        grep -E "^[[:space:]]*${key}[[:space:]]*=" "${MP_CONFIG}" | tail -n 1 | cut -d'=' -f2- | xargs 2>/dev/null || true
    fi
}

NODE_ID="${1:-${CLUSTER_NODE_ID:-${JETTRA_NODE_ID:-$(read_mp_prop 'cluster\.node\.id')}}}"
NODE_ID="${NODE_ID:-node-01}"

NODE_ROLE="${2:-${CLUSTER_NODE_ROLE:-${JETTRA_NODE_ROLE:-$(read_mp_prop 'cluster\.node\.role')}}}"
if [ -z "${NODE_ROLE}" ]; then
    [ "${NODE_ID}" = "node-01" ] && NODE_ROLE="PRIMARY" || NODE_ROLE="SECONDARY"
fi
NODE_ROLE=$(printf '%s' "${NODE_ROLE}" | tr '[:lower:]' '[:upper:]')

NODE_IP="${CLUSTER_NODE_IP:-${JETTRA_NODE_IP:-$(read_mp_prop 'cluster\.node\.ip')}}"
NODE_IP="${NODE_IP:-127.0.0.1}"
GRPC_PORT="${CLUSTER_NODE_GRPC_PORT:-${JETTRA_GRPC_PORT:-$(read_mp_prop 'cluster\.node\.grpc\.port')}}"
GRPC_PORT="${GRPC_PORT:-50001}"
REST_PORT="${CLUSTER_NODE_REST_PORT:-${JETTRA_REST_PORT:-$(read_mp_prop 'cluster\.node\.rest\.port')}}"
REST_PORT="${REST_PORT:-60001}"

STATE_DIR="${JETTRA_CLUSTER_STATE_CONFIG_DIR:-$(read_mp_prop 'jettra\.cluster_state_config_dir')}"
STATE_DIR="${STATE_DIR:-~/jettra-cluster-state}"
case "${STATE_DIR}" in
    "~") STATE_DIR="${HOME}" ;;
    "~/"*) STATE_DIR="${HOME}/${STATE_DIR#~/}" ;;
esac
mkdir -p "${STATE_DIR}"
STATE_FILE="${STATE_DIR}/cluster-state.config"

CLUSTER_CONFIG=""
if [ "${NODE_ROLE}" = "PRIMARY" ]; then
    EXPLICIT_CLUSTER="${3:-${JETTRA_CLUSTER_CONFIG_PATH:-}}"
    if [ -n "${EXPLICIT_CLUSTER}" ]; then
        CLUSTER_CONFIG="${EXPLICIT_CLUSTER}"
    elif [ -f "config/cluster.config" ]; then
        CLUSTER_CONFIG="config/cluster.config"
    elif [ -f "cluster.config" ]; then
        CLUSTER_CONFIG="cluster.config"
    elif [ -f "${STATE_DIR}/cluster.config" ]; then
        CLUSTER_CONFIG="${STATE_DIR}/cluster.config"
    fi

    MULTINODE_CFG="$(read_mp_prop 'cluster\.multinode\.active')"
    MULTINODE_CFG="${MULTINODE_CFG:-on}"
    if [ ! -f "${STATE_FILE}" ] && [ -z "${CLUSTER_CONFIG}" ] && [ "${MULTINODE_CFG}" != "off" ] && [ "${MULTINODE_CFG}" != "false" ]; then
        echo "[ERROR] El nodo PRIMARY requiere cluster.config (o cluster-state.config existente en '${STATE_DIR}') cuando multinodo está activo."
        exit 1
    fi
fi

JAR_FILE="JettraStore-1.0-SNAPSHOT-uber.jar"
for candidate in "JettraStore-1.0-SNAPSHOT-uber.jar" "target/JettraStore-1.0-SNAPSHOT-uber.jar" "JettraStore.jar"; do
    if [ -f "${candidate}" ]; then
        JAR_FILE="${candidate}"
        break
    fi
done

JETTRA_JAVA_OPTS="${JAVA_OPTS:--Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=256m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:CICompilerCount=2}"
CLUSTER_FLAG=""
[ -n "${CLUSTER_CONFIG}" ] && [ -f "${CLUSTER_CONFIG}" ] && CLUSTER_FLAG="-Dcluster.config.path=${CLUSTER_CONFIG}"

echo "=============================================================================="
echo "JettraStore Node : ${NODE_ID} (${NODE_ROLE}) @ ${NODE_IP} [gRPC:${GRPC_PORT}, REST:${REST_PORT}]"
if [ -n "${CLUSTER_FLAG}" ]; then
    echo "Semilla Clúster  : ${CLUSTER_CONFIG}"
elif [ "${NODE_ROLE}" = "SECONDARY" ]; then
    echo "Semilla Clúster  : (no requerida en SECONDARY; espera detección/sync desde PRIMARY)"
else
    echo "Semilla Clúster  : (usando cluster-state.config existente o modo single-node)"
fi
echo "Estado Clúster   : ${STATE_DIR} (cluster-state.config)"
echo "MicroProfile     : ${MP_CONFIG}"
echo "JAR Binario      : ${JAR_FILE}"
echo "=============================================================================="

# shellcheck disable=SC2086
exec java --enable-preview \
     --enable-native-access=ALL-UNNAMED \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+UseCompactObjectHeaders \
     ${JETTRA_JAVA_OPTS} \
     -Dcluster.node.id="${NODE_ID}" \
     -Dcluster.node.role="${NODE_ROLE}" \
     -Dcluster.node.ip="${NODE_IP}" \
     -Dcluster.node.grpc.port="${GRPC_PORT}" \
     -Dcluster.node.rest.port="${REST_PORT}" \
     -Djettra.cluster_state_config_dir="${STATE_DIR}" \
     -Dmicroprofile.config.file="${MP_CONFIG}" \
     ${CLUSTER_FLAG} \
     -jar "${JAR_FILE}"
