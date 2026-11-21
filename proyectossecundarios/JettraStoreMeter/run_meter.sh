#!/usr/bin/env bash
# ==============================================================================
# JettraStoreMeter - CLI Runner
# Permite ejecutar los planes de pruebas y benchmarks tanto vía Java 25 Virtual Threads
# como a través de Apache JMeter en modo Non-GUI desde consola.
# ==============================================================================

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

# Si no se pasan argumentos, mostrar ayuda o menú interactivo
if [ $# -eq 0 ]; then
    echo "================================================================================"
    echo "            JETTRASTORE METER: EJECUTOR DE PRUEBAS DE CARGA (CLI)"
    echo "================================================================================"
    echo "Uso rápido:"
    echo "  ./run_meter.sh --plan hospital --users 50 --duration 10m"
    echo "  ./run_meter.sh --plan ambiental --users 100 --duration 25m"
    echo "  ./run_meter.sh --plan factura --users 500 --duration 35m"
    echo "  ./run_meter.sh --jmx plans/jettra_hospital_stress_test.jmx -Jthreads=50 -Jduration=600"
    echo "  ./run_meter.sh --help"
    echo "--------------------------------------------------------------------------------"
    echo "Iniciando modo interactivo..."
    mvn -q exec:java -Dexec.args="--interactive"
    exit $?
fi

# Detectar si el usuario pasó directamente un plan .jmx o el flag --jmx
if [[ "$*" == *"--jmx"* ]] || [[ "$1" == *.jmx ]]; then
    PLAN_ARG=""
    if [[ "$1" == *.jmx ]]; then
        PLAN_ARG="$1"
        shift
    fi
    
    # Si jmeter existe en PATH, delegar a jmeter CLI
    if command -v jmeter &> /dev/null; then
        echo "[METER-CLI] Apache JMeter detectado en PATH. Ejecutando en modo Non-GUI..."
        TIMESTAMP=$(date +%Y%m%d_%H%M%S)
        mkdir -p results/html_dashboard_${TIMESTAMP}
        jmeter -n -t "${PLAN_ARG:-plans/jettra_hospital_stress_test.jmx}"                -l results/run_${TIMESTAMP}.jtl                -e -o results/html_dashboard_${TIMESTAMP}/                "$@"
        echo "[METER-CLI] Reporte HTML generado en: results/html_dashboard_${TIMESTAMP}/"
        exit $?
    else
        echo "[METER-CLI] JMeter no está en PATH. Invocando simulador de alta velocidad en Java 25..."
        mvn -q exec:java -Dexec.args="$*"
        exit $?
    fi
fi

# Ejecutar mediante exec:java pasando todos los argumentos
mvn -q exec:java -Dexec.args="$*"
