#!/usr/bin/env bash
# Script de inicio rápido para JVM 3D Matrix
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
cd "$DIR"

# Asegurar que Go esté en PATH si está en software/go
if [ -d "/home/avbravo/software/go/bin" ]; then
    export PATH="/home/avbravo/software/go/bin:$PATH"
fi

echo "=================================================="
echo " Iniciando JVM 3D Matrix (Monitor 3D de Java)"
echo "=================================================="

# Compilar si el binario no existe o si hay cambios
if [ ! -f "$DIR/javaview3d" ] || [ "$DIR/main.go" -nt "$DIR/javaview3d" ]; then
    echo "➜ Compilando binario Golang..."
    go build -o "$DIR/javaview3d" .
fi

echo "➜ Ejecutando servidor y abriendo interfaz 3D..."
exec "$DIR/javaview3d" "$@"

