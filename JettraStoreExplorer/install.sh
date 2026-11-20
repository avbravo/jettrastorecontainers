#!/bin/bash
# ==============================================================================
# Instalador local para JettraStoreExplorer
# Instala el JAR, microprofile-config.properties y start.sh en ~/jettrastoreexplorer
# Uso: ./install.sh [DIRECTORIO_DESTINO]
# ==============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="${1:-$HOME/jettrastoreexplorer}"

echo "================================================================================"
echo " Instalando JettraStoreExplorer en: $INSTALL_DIR"
echo "================================================================================"

# 1. Localizar o compilar el JAR si no existe
JAR_SRC=$(find "$SCRIPT_DIR/target" -maxdepth 1 -type f -name "JettraStoreExplorer-*.jar" ! -name "original-*" 2>/dev/null | head -n 1)
if [ -z "$JAR_SRC" ] || [ ! -f "$JAR_SRC" ]; then
    echo "[INFO] JAR no encontrado en target/. Compilando JettraStoreExplorer..."
    (cd "$SCRIPT_DIR" && mvn package -DskipTests)
    JAR_SRC=$(find "$SCRIPT_DIR/target" -maxdepth 1 -type f -name "JettraStoreExplorer-*.jar" ! -name "original-*" 2>/dev/null | head -n 1)
fi

if [ -z "$JAR_SRC" ] || [ ! -f "$JAR_SRC" ]; then
    echo "[ERROR] No se pudo generar ni localizar el JAR de JettraStoreExplorer."
    exit 1
fi

# 2. Crear estructura de directorios    
mkdir -p "$INSTALL_DIR/config" "$INSTALL_DIR/memory"

# 3. Copiar JAR ejecutable
cp "$JAR_SRC" "$INSTALL_DIR/JettraStoreExplorer.jar"

# 4. Copiar microprofile-config.properties
MP_SRC="$SCRIPT_DIR/src/main/resources/META-INF/microprofile-config.properties"
if [ -f "$MP_SRC" ]; then
    cp "$MP_SRC" "$INSTALL_DIR/config/microprofile-config.properties"
else
    echo "[WARN] No se encontró $MP_SRC"
fi

# 5. Copiar start.sh con permisos de ejecución
cp "$SCRIPT_DIR/start.sh" "$INSTALL_DIR/start.sh"
chmod +x "$INSTALL_DIR/start.sh"

echo "--------------------------------------------------------------------------------"
echo "✅ Instalación completada con éxito."
echo "   Directorio : $INSTALL_DIR"
echo "   JAR        : $INSTALL_DIR/JettraStoreExplorer.jar"
echo "   Config     : $INSTALL_DIR/config/microprofile-config.properties"
echo "   Inicio     : $INSTALL_DIR/start.sh"
echo ""
echo "Para ejecutar JettraStoreExplorer:"
echo "   cd $INSTALL_DIR && ./start.sh"
echo "================================================================================"
