
#!/bin/bash
# ==============================================================================
# Script para iniciar y gestionar todos los nodos JettraStore en paralelo
# ==============================================================================
# Inicia node-01 (PRIMARY) y node-02..node-04 (SECONDARY) desde sus respectivos
# directorios en $HOME/jettra-<id>, enviando la salida a logs individuales y
# deteniendo todos los nodos al presionar Ctrl+C.
#
# Uso:
#   ./run-all-node.sh                  # Inicia node-01 a node-04
#   ./run-all-node.sh node-01 node-02  # Inicia solo los nodos indicados
# ==============================================================================

set -u

LOG_DIR="${JETTRA_LOG_DIR:-$HOME/jettra-logs}"
START_DELAY="${JETTRA_START_DELAY:-2}"

# Definición por defecto de nodos (coincidente con createnode.sh)
declare -A NODE_ROLES=(
    ["node-01"]="PRIMARY"
    ["node-02"]="SECONDARY"
    ["node-03"]="SECONDARY"
)

DEFAULT_ORDER=("node-01" "node-02" "node-03" )

if [ "$#" -gt 0 ]; then
    TARGET_NODES=("$@")
else
    TARGET_NODES=("${DEFAULT_ORDER[@]}")
fi

mkdir -p "$LOG_DIR"

declare -a PIDS=()
declare -a STARTED_NODES=()

cleanup() {
    echo ""
    echo "=============================================================================="
    echo "Deteniendo nodos JettraStore..."
    echo "=============================================================================="

    for i in "${!PIDS[@]}"; do
        local pid="${PIDS[$i]}"
        local node="${STARTED_NODES[$i]}"
        if kill -0 "$pid" 2>/dev/null; then
            echo "Deteniendo ${node} (PID: ${pid})..."
            kill "$pid" 2>/dev/null || true
        fi
    done

    # Esperar brevemente a que cierren de forma ordenada
    sleep 2

    for i in "${!PIDS[@]}"; do
        local pid="${PIDS[$i]}"
        local node="${STARTED_NODES[$i]}"
        if kill -0 "$pid" 2>/dev/null; then
            echo "Forzando cierre de ${node} (PID: ${pid})..."
            kill -9 "$pid" 2>/dev/null || true
        fi
    done

    echo "Todos los nodos han sido detenidos."
    exit 0
}

trap cleanup SIGINT SIGTERM

echo "=============================================================================="
echo "Iniciando clúster local JettraStore"
echo "Directorio de logs : ${LOG_DIR}"
echo "Nodos objetivo     : ${TARGET_NODES[*]}"
echo "=============================================================================="

for node_id in "${TARGET_NODES[@]}"; do
    if [ -n "${NODE_ROLES[$node_id]+x}" ]; then
        role="${NODE_ROLES[$node_id]}"
    elif [ "$node_id" = "node-01" ]; then
        role="PRIMARY"
    else
        role="SECONDARY"
    fi

    node_dir="$HOME/jettra-${node_id}"
    start_script="$node_dir/start-node.sh"
    log_file="$LOG_DIR/${node_id}.log"

    if [ ! -d "$node_dir" ]; then
        echo "[ERROR] No existe el directorio del nodo: $node_dir (ejecute primero createnode.sh)"
        cleanup
    fi

    if [ ! -x "$start_script" ]; then
        echo "[ERROR] No existe o no es ejecutable: $start_script"
        cleanup
    fi

    echo "-> Arrancando ${node_id} (${role}) en ${node_dir}"
    echo "   Log: ${log_file}"

    (
        cd "$node_dir" || exit 1
        ./start-node.sh "$node_id" "$role" >"$log_file" 2>&1
    ) &

    pid=$!
    PIDS+=("$pid")
    STARTED_NODES+=("$node_id")

    # Pequeña pausa para escalonar el arranque del PRIMARY y los SECONDARY
    sleep "$START_DELAY"
done

echo ""
echo "=============================================================================="
echo "Nodos en ejecución. Presione Ctrl+C para detenerlos todos."
echo "Para ver logs en vivo: tail -f ${LOG_DIR}/node-*.log"
echo "=============================================================================="

# Esperar a que terminen los procesos en segundo plano
wait