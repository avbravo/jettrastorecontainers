#!/bin/bash

echo "===== JettraStorePolice3D Builder & Runner ====="
echo "Versión: Java 25 (Panama FFM & Virtual Threads)"

# 1. Compilación
echo "[1/2] Compilando proyecto unificado JettraStorePolice3D..."
mvn clean install -DskipTests

if [ $? -ne 0 ]; then
    echo "ERROR: La compilación falló."
    exit 1
fi

# 2. Ejecución
echo "[2/2] Iniciando JettraStorePolice3D..."
mvn exec:exec
