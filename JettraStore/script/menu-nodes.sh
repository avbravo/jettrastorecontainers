#!/usr/bin/env bash
set -u

NODE_LABELS=("node-01" "node-02" "node-03" "node-04")
NODE_DIRS=("jettra-node-01" "jettra-node-02" "jettra-node-03" "jettra-node-04")

# Busca primero en el directorio actual de ejecución; si no existe ahí, prueba junto al script
resolve_base_dir() {
    local script_dir
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    if [[ -d "$(pwd)/jettra-node-01" ]]; then
        pwd
    else
        echo "${script_dir}"
    fi
}

BASE_DIR="$(resolve_base_dir)"
RUNTIME_DIR="${HOME}/.jettra-run"
LOG_DIR="${HOME}/jettra-logs"

mkdir -p "${RUNTIME_DIR}" "${LOG_DIR}"

get_pid_file() {
    local label="$1"
    echo "${RUNTIME_DIR}/${label}.pid"
}

get_log_file() {
    local label="$1"
    echo "${LOG_DIR}/${label}.log"
}

is_pid_running() {
    local pid="$1"
    [[ -n "${pid}" && "${pid}" =~ ^[0-9]+$ ]] && kill -0 "${pid}" 2>/dev/null
}

get_tracked_pid() {
    local label="$1"
    local pid_file
    pid_file="$(get_pid_file "${label}")"
    if [[ -f "${pid_file}" ]]; then
        local pid
        pid="$(cat "${pid_file}" 2>/dev/null | tr -d '[:space:]')"
        if is_pid_running "${pid}"; then
            echo "${pid}"
            return 0
        fi
    fi
    return 1
}

list_java_processes() {
    echo ""
    echo "=========================================================================="
    echo "  PROCESOS JAVA ACTIVOS"
    echo "=========================================================================="
    local java_procs
    java_procs="$(ps -eo pid,ppid,pgid,user,%mem,%cpu,cmd --sort=pid | awk 'NR==1 || $0 ~ /[j]ava/')"
    if [[ "$(echo "${java_procs}" | wc -l)" -le 1 ]]; then
        echo "No hay procesos Java en ejecución."
    else
        echo "${java_procs}"
    fi
    echo "=========================================================================="
}

list_nodes_status() {
    echo ""
    echo "=========================================================================="
    echo "  ESTADO DE NODOS (BASE: ${BASE_DIR})"
    echo "=========================================================================="
    printf "%-4s %-12s %-18s %-10s %-10s\n" "#" "NODO" "DIRECTORIO" "ESTADO" "PID"
    echo "--------------------------------------------------------------------------"
    local i
    for i in "${!NODE_LABELS[@]}"; do
        local label="${NODE_LABELS[$i]}"
        local dir_name="${NODE_DIRS[$i]}"
        local pid
        pid="$(get_tracked_pid "${label}" || true)"
        local status="DETENIDO"
        local pid_display="-"
        if [[ -n "${pid}" ]]; then
            status="ACTIVO"
            pid_display="${pid}"
        fi
        printf "%-4s %-12s %-18s %-10s %-10s\n" "$((i + 1))" "${label}" "${dir_name}" "${status}" "${pid_display}"
    done
    echo "=========================================================================="
}

iniciar_nodo() {
    local idx="$1"
    local label="${NODE_LABELS[$idx]}"
    local dir_name="${NODE_DIRS[$idx]}"
    local dir_path="${BASE_DIR}/${dir_name}"
    local pid_file
    local log_file
    pid_file="$(get_pid_file "${label}")"
    log_file="$(get_log_file "${label}")"

    echo "[DEBUG] Directorio objetivo: ${dir_path}"
    echo "[DEBUG] Script objetivo:    ${dir_path}/start-node.sh"

    if [[ ! -d "${dir_path}" ]]; then
        echo "[ERROR] No existe el directorio: ${dir_path}"
        return 1
    fi

    if [[ ! -f "${dir_path}/start-node.sh" ]]; then
        echo "[ERROR] No existe ${dir_path}/start-node.sh"
        return 1
    fi

    chmod +x "${dir_path}/start-node.sh"

    local existing_pid
    existing_pid="$(get_tracked_pid "${label}" || true)"
    if [[ -n "${existing_pid}" ]]; then
        echo "[AVISO] '${label}' ya tiene un proceso registrado con PID ${existing_pid}."
        return 0
    fi

    echo "[INFO] Cambiando a '${dir_path}' y ejecutando ./start-node.sh..."
    echo "[INFO] Log: ${log_file}"

    nohup bash -c "cd '${dir_path}' && exec ./start-node.sh" > "${log_file}" 2>&1 < /dev/null &
    local launched_pid=$!
    echo "${launched_pid}" > "${pid_file}"

    sleep 1

    if is_pid_running "${launched_pid}"; then
        echo "[OK] '${label}' iniciado en '${dir_path}'. PID capturado: ${launched_pid}"
    else
        echo "[AVISO] El PID ${launched_pid} ya terminó o lanzó un subproceso y salió. Últimas líneas de '${log_file}':"
        echo "--------------------------------------------------------------------------"
        tail -n 25 "${log_file}" 2>/dev/null || true
        echo "--------------------------------------------------------------------------"
    fi
}

detener_nodo() {
    local idx="$1"
    local label="${NODE_LABELS[$idx]}"
    local pid_file
    pid_file="$(get_pid_file "${label}")"

    if [[ ! -f "${pid_file}" ]]; then
        echo "[INFO] No hay PID registrado para '${label}'."
        return 0
    fi

    local pid
    pid="$(cat "${pid_file}" 2>/dev/null | tr -d '[:space:]')"

    if ! is_pid_running "${pid}"; then
        echo "[INFO] El PID ${pid} de '${label}' ya no está activo. Limpiando '${pid_file}'."
        rm -f "${pid_file}"
        return 0
    fi

    echo "[INFO] Deteniendo '${label}' (PID ${pid})..."
    pkill -TERM -P "${pid}" 2>/dev/null || true
    kill -TERM "${pid}" 2>/dev/null || true
    sleep 2

    if is_pid_running "${pid}"; then
        echo "[AVISO] Forzando cierre con SIGKILL de PID ${pid}..."
        pkill -KILL -P "${pid}" 2>/dev/null || true
        kill -KILL "${pid}" 2>/dev/null || true
    fi

    rm -f "${pid_file}"
    echo "[OK] '${label}' (PID ${pid}) detenido."
}

detener_java_por_pid() {
    list_java_processes
    read -r -p "Ingresa el PID del proceso Java a detener (0 para cancelar): " target_pid

    if [[ "${target_pid}" == "0" || -z "${target_pid}" ]]; then
        echo "Operación cancelada."
        return 0
    fi

    if ! [[ "${target_pid}" =~ ^[0-9]+$ ]]; then
        echo "[ERROR] PID inválido."
        return 1
    fi

    if ! is_pid_running "${target_pid}"; then
        echo "[ERROR] El PID ${target_pid} no existe o ya finalizó."
        return 1
    fi

    local cmd_info
    cmd_info="$(ps -p "${target_pid}" -o comm=,args= 2>/dev/null || true)"
    echo "[INFO] Deteniendo PID ${target_pid} (${cmd_info})..."
    kill -TERM "${target_pid}" 2>/dev/null || true
    sleep 2

    if is_pid_running "${target_pid}"; then
        echo "[AVISO] No respondió a SIGTERM; aplicando SIGKILL a ${target_pid}..."
        kill -KILL "${target_pid}" 2>/dev/null || true
    fi

    for f in "${RUNTIME_DIR}"/*.pid; do
        [[ -f "$f" ]] || continue
        if [[ "$(cat "$f" 2>/dev/null | tr -d '[:space:]')" == "${target_pid}" ]]; then
            rm -f "$f"
        fi
    done

    echo "[OK] Proceso ${target_pid} detenido."
}

submenu_iniciar_nodo() {
    while true; do
        echo ""
        echo "--------------------------------------------------------------------------"
        echo "  SUBMENÚ: INICIAR NODO"
        echo "--------------------------------------------------------------------------"
        echo "  1) node-01"
        echo "  2) node-02"
        echo "  3) node-03"
        echo "  4) node-04"
        echo "  0) Volver al menú principal"
        echo "--------------------------------------------------------------------------"
        read -r -p "Selecciona nodo a iniciar: " opt

        case "${opt}" in
            1|2|3|4)
                iniciar_nodo "$((opt - 1))"
                ;;
            0)
                return 0
                ;;
            *)
                echo "[ERROR] Opción no válida."
                ;;
        esac
    done
}

submenu_detener_nodo() {
    while true; do
        echo ""
        echo "--------------------------------------------------------------------------"
        echo "  SUBMENÚ: DETENER NODO"
        echo "--------------------------------------------------------------------------"
        echo "  1) node-01"
        echo "  2) node-02"
        echo "  3) node-03"
        echo "  4) node-04"
        echo "  0) Volver al menú principal"
        echo "--------------------------------------------------------------------------"
        read -r -p "Selecciona nodo a detener: " opt

        case "${opt}" in
            1|2|3|4)
                detener_nodo "$((opt - 1))"
                ;;
            0)
                return 0
                ;;
            *)
                echo "[ERROR] Opción no válida."
                ;;
        esac
    done
}

menu() {
    while true; do
        list_nodes_status
        echo ""
        echo "=========================================================================="
        echo "  MENÚ PRINCIPAL"
        echo "=========================================================================="
        echo "  1) Listar procesos Java corriendo"
        echo "  2) Detener un proceso Java por PID"
        echo "  3) Iniciar nodo"
        echo "  4) Detener nodo"
        echo "  0) Salir"
        echo "=========================================================================="
        read -r -p "Opción: " option

        case "${option}" in
            1)
                list_java_processes
                ;;
            2)
                detener_java_por_pid
                ;;
            3)
                submenu_iniciar_nodo
                ;;
            4)
                submenu_detener_nodo
                ;;
            0)
                echo "Saliendo..."
                exit 0
                ;;
            *)
                echo "[ERROR] Opción no válida."
                ;;
        esac
    done
}

menu