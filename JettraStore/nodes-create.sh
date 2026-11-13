#!/bin/bash
# Crea tres nodos para prueba de la aplicacion

echo ":::::::::::::::::::::::::::::::::::::::::::::::::::::::"
echo " "
echo " "
echo " Creacion de nodos"
echo " "
echo " "
echo ":::::::::::::::::::::::::::::::::::::::::::::::::::::::"


# -----------------------------------------------------------------------------
# FUNCIÓN 1: Actualiza o inserta una propiedad/variable en un archivo
# Parámetros: 1) Ruta del archivo, 2) Clave, 3) Valor
# -----------------------------------------------------------------------------
update_property() {
    local file="$1"
    local key="$2"
    local value="$3"
    
    if [ -f "$file" ]; then
        # Busca líneas que comiencen con la clave (con o sin comillas, o con sintaxis de bash)
        if grep -q "^$key=" "$file" || grep -q "^$key[[:space:]]*=" "$file"; then
            sed -i "s/^$key=.*/$key=\"$value\"/" "$file"
        else
            echo "$key=\"$value\"" >> "$file"
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
    local node_dir="$HOME/jettra-$node_num"
    local config_file="$node_dir/config/microprofile-config.properties"
    local start_node_file="$node_dir/start-node.sh"

    echo "------------------------------------------------"
    echo "Configurando Nodo $node_num (Rol: $node_role)"

    # 1. Crear directorios
    mkdir -p "$node_dir/config"

    # 2. Copiar archivos base de configuración
    cp cluster.config "$node_dir/config/"
    cp microprofile-config.properties "$node_dir/config/"

    # 3. Copiar archivo start-node.sh
    cp start-node.sh "$node_dir/"

    # 4. Copiar el archivo uber jar
    cp target/JettraStore-1.0-SNAPSHOT-uber.jar "$node_dir/"

    # 5. Actualizar propiedades en microprofile-config.properties
    update_property "$config_file" "cluster.node.id" "$node_num"
    update_property "$config_file" "cluster.node.role" "$node_role"

    # 6. Actualizar variables en el script start-node.sh copiado
    update_property "$start_node_file" "NODE_ID" "$node_num"
    update_property "$start_node_file" "NODE_ROLE" "$node_role"

    # 7. Dar permisos de ejecución al script start-node.sh del nodo
    chmod +x "$start_node_file"

    echo "... Directorio de trabajo [$node_dir]"
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
echo "......  Verifique el archivo /cluster.config"
echo "......   Ejecute ./start-node.sh"
echo "_____________________________________________________________________________"