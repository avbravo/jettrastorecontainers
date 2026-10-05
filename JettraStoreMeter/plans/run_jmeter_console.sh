#!/usr/bin/env bash
# ==============================================================================
# Script de Ejecución Directa para Apache JMeter CLI en JettraStoreMeter
# ==============================================================================

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$DIR")"
cd "$ROOT_DIR"

PLAN="${1:-plans/jettra_hospital_stress_test.jmx}"
THREADS="${2:-50}"
DURATION="${3:-600}"
HOST="${4:-127.0.0.1}"
PORT="${5:-8080}"

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULTS_DIR="results"
JTL_FILE="${RESULTS_DIR}/stress_${TIMESTAMP}.jtl"
DASHBOARD_DIR="${RESULTS_DIR}/dashboard_${TIMESTAMP}"

mkdir -p "$RESULTS_DIR"

echo "================================================================================"
echo "         JETTRASTORE METER: EJECUCIÓN CONSOLA APACHE JMETER CLI"
echo "================================================================================"
echo "Plan de Pruebas:   $PLAN"
echo "Usuarios (Hilos):  $THREADS"
echo "Duración:          $DURATION segundos"
echo "Host / Puerto:     $HOST:$PORT"
echo "Salida JTL:        $JTL_FILE"
echo "Dashboard HTML:    $DASHBOARD_DIR"
echo "================================================================================"

if command -v jmeter &> /dev/null; then
    jmeter -n -t "$PLAN"            -l "$JTL_FILE"            -e -o "$DASHBOARD_DIR"            -Jhost="$HOST"            -Jport="$PORT"            -Jthreads="$THREADS"            -Jduration="$DURATION"
    echo "[OK] Prueba completada exitosamente."
    echo "[OK] Abra el informe con: xdg-open ${DASHBOARD_DIR}/index.html"
else
    echo "[AVISO] El comando 'jmeter' no fue localizado en el PATH del sistema."
    echo "[INFO] Ejecutando plan con el motor concurrente nativo de JettraStoreMeter..."
    ./run_meter.sh --jmx "$PLAN" --users "$THREADS" --duration "${DURATION}s" --host "$HOST" --port "$PORT"
fi
