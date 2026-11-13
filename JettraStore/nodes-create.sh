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
# FUNCIÓN 1: Actualiza o inserta una propiedad en un archivo .properties
# Parámetros: 1) Ruta del archivo, 2) Clave, 3) Valor
# -----------------------------------------------------------------------------
update_property() {
    local file="$1"
    local key="$2"
    local value="$3"
    
    if [ -f "$file" ]; then
        if grep -q "^$key=" "$file"; then
            sed -i "s/^$key=.*/$key=$value/" "$file"
        else
            echo "$key=$value" >> "$file"
        fi
    else
        echo "Advertencia: El archivo $file no existe."
    fi
}



# -----------------------------------------------------------------------------
# FUNCIÓN 2: Crea el directorio, copia los archivos y configura el nodo
# Parámetros: 1) Número de nodo, 2) Rol del nodo (ej: primary, replica)
# -----------------------------------------------------------------------------
setup_node() {
    local node_num="$1"
    local node_role="$2"
    local node_dir="$HOME/jettra-$node_num"
    local config_file="$node_dir/config/microprofile-config.properties"
   # local start-nodesh_file = "$node_dir/start-node.sh"

    echo "------------------------------------------------"
    echo "Configurando Nodo $node_num (Rol: $node_role)"

    # 1. Crear directorios
    mkdir -p "$node_dir/config"

    # 2. Copiar archivos base
    cp cluster.config "$node_dir/config/"
    cp microprofile-config.properties "$node_dir/config/"

   # 3. Copiar archivo star-node.sh
    cp start-node.sh "$node_dir/"

   # 4. Copiar el archivo uber jar

    cp target/JettraStore-1.0-SNAPSHOT-uber.jar "$node_dir/"

    

    # 5. Invocar la función de actualización de propiedades para este nodo
    update_property "$config_file" "cluster.node.id" "$node_num"
    update_property "$config_file" "cluster.node.role" "$node_role"

  # 6. Invocar la función de actualización de propiedades para este nodo
  #  update_property "$start-nodesh_file" "NODE_ID" "$node_num"
  #  update_property "$start-nodesh_file" "NODE_ROLE" "$node_role"


}

# -----------------------------------------------------------------------------
# EJECUCIÓN PRINCIPAL: Invocación de la función para cada nodo
# -----------------------------------------------------------------------------

setup_node "node-01" "PRIMARY"
setup_node "node-02" "SECONDARY"
setup_node "node-03" "SECONDARY"

echo ".................................................."
echo "......      PROCESO FINALIZADO      ......"




