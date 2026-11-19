#!/bin/bash
# Crea tres nodos para prueba de la aplicacion

echo ":::::::::::::::::::::::::::::::::::::::::::::::::::::::"
echo " "
echo " "
echo " Creacion de nodos"
echo " "
echo " "
echo ":::::::::::::::::::::::::::::::::::::::::::::::::::::::"

# Obtener la ruta absoluta del directorio del proyecto (padre de la carpeta script)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# -----------------------------------------------------------------------------
# FUNCIÓN 1: Actualiza o inserta una propiedad/variable en un archivo
# Parámetros: 1) Ruta del archivo, 2) Clave, 3) Valor, 4) Separador opcional
# -----------------------------------------------------------------------------
update_property() {
    local file="$1"
    local key="$2"
    local value="$3"
    local sep="${4:-=}"
    local escaped_value

    escaped_value=$(printf '%s\n' "$value" | sed 's/[&/\]/\\&/g')

    if [ -f "$file" ]; then
        if grep -qE "^[[:space:]]*${key}[[:space:]]*=" "$file"; then
            sed -i -E "s|^[[:space:]]*${key}[[:space:]]*=.*|${key}${sep}${escaped_value}|" "$file"
        else
            printf '%s%s%s\n' "$key" "$sep" "$value" >> "$file"
        fi
    else
        echo "Advertencia: El archivo $file no existe."
    fi
}

# -----------------------------------------------------------------------------
# FUNCIÓN 2: Crea el directorio, copia los archivos y configura el nodo
# Parámetros: 1) Número de nodo, 2) Rol del nodo (ej: PRIMARY, SECONDARY)
# -----------------------------------------------------------------------------
setup_node() {
    local node_num="$1"
    local node_role="$2"
    local node_suffix="${node_num#node-}"
    local node_dir="$HOME/jettra-$node_num"
    local storage_fs_path="$HOME/jettra-storage-${node_suffix}/data"
    local storage_prop_path="~/jettra-storage-${node_suffix}/data"
    # Nota: microprofile-config.properties se copia dentro de config/, por lo que la ruta relativa interna es config/microprofile-config.properties
    local config_file="$node_dir/config/microprofile-config.properties"
    local start_node_file="$node_dir/start-node.sh"

    echo "------------------------------------------------"
    echo "Configurando Nodo $node_num (Rol: $node_role)"

    # 1. Crear directorios del nodo y de almacenamiento
    mkdir -p "$node_dir/config"
    mkdir -p "$storage_fs_path"

    # 2. Copiar archivos base de configuración desde sus nuevas rutas en src/main/resources
    cp "$PROJECT_ROOT/src/main/resources/cluster.config" "$node_dir/config/"
    cp "$PROJECT_ROOT/src/main/resources/META-INF/microprofile-config.properties" "$node_dir/config/"

    # 3. Copiar archivo start-node.sh desde la carpeta script/ y ajustar la ruta del jar
    cp "$PROJECT_ROOT/script/start-node.sh" "$node_dir/"
    if [ -f "$start_node_file" ]; then
        sed -i 's|-jar target/|-jar |g' "$start_node_file"
    fi

    # 4. Copiar el archivo uber jar desde la carpeta target/
    cp "$PROJECT_ROOT/target/JettraStore-1.0-SNAPSHOT-uber.jar" "$node_dir/"

    # 5. Actualizar propiedades en microprofile-config.properties dentro del nodo
    update_property "$config_file" "cluster.node.id" "$node_num" "="
    update_property "$config_file" "cluster.node.role" "$node_role" "="
    update_property "$config_file" "jettra.storage.path" "$storage_prop_path" " = "

    # 6. Actualizar variables en el script start-node.sh copiado
    update_property "$start_node_file" "NODE_ID" "$node_num" "="
    update_property "$start_node_file" "NODE_ROLE" "$node_role" "="

    # 7. Dar permisos de ejecución al script start-node.sh del nodo
    chmod +x "$start_node_file"

    echo "... Directorio de trabajo [$node_dir]"
    echo "... Ruta de almacenamiento [$storage_prop_path]"
}

# -----------------------------------------------------------------------------
# EJECUCIÓN PRINCIPAL: Invocación de la función para cada nodo
# -----------------------------------------------------------------------------

setup_node "node-01" "PRIMARY"
setup_node "node-02" "SECONDARY"
setup_node "node-03" "SECONDARY"

echo ""
echo "_____________________________________________________________________________"
echo "......      PROCESO FINALIZADO      ......"
echo "......   Ingrese a cada directorio"
echo "......  Verifique el archivo config/cluster.config"
echo "......   Ejecute ./start-node.sh"
echo "_____________________________________________________________________________"