#!/bin/bash
# Script para copiar jar a nodos

echo "============================================"
echo "    Configurando los nodos "
echo "============================================"

# Agrega IPs virtuales de forma segura (idempotente)

echo "--------------------------------------"
echo "Verificando y creando nodos virtuales..."

# Array con las IPs que quieres configurar
IPS=("127.0.0.2" "127.0.0.3" "127.0.0.4")

for ip in "${IPS[@]}"; do
    # Comprobamos si la IP ya está asignada a la interfaz lo
    if ip addr show lo | grep -q "$ip"; then
        echo "La IP virtual $ip ya existe."
    else
        echo "Creando ip virtual: $ip"
        sudo ip addr add "$ip/8" dev lo
    fi
done

echo "--------------------------------------"
echo "Verificando IPs virtuales creadas:"
ip addr show lo



# Configuración de ruta y nombre de archivo (usando guiones bajos)
Node_1_Path="$HOME/jettra-node-1-server"
Node_2_Path="$HOME/jettra-node-2-server"
Node_3_Path="$HOME/jettra-node-3-server"


# Rutas de ejemplo (origen para los nodos 1 y 2)
Node_1_OriginExample="$HOME/NetBeansProjects/jettrastack_local/jettrastorecontainers/JettraStore/nodes-examples/home/jettra-node-1/."
Node_2_OriginExample="$HOME/NetBeansProjects/jettrastack_local/jettrastorecontainers/JettraStore/nodes-examples/home/jettra-node-2/."
Node_3_OriginExample="$HOME/NetBeansProjects/jettrastack_local/jettrastorecontainers/JettraStore/nodes-examples/home/jettra-node-3/."

# Archivos
JettraStore_FileName="JettraStore-1.0-SNAPSHOT-uber.jar"
JettraStoreShell_FileName="JettraStoreShell-1.0-SNAPSHOT-uber.jar"
JettraStoreShell_FileName="JettraStoreShell-1.0-SNAPSHOT-uber.jar"

# Rutas completas de los archivos
JettraStore_1_FilePath="$Node_1_Path/$JettraStore_FileName"
JettraStore_2_FilePath="$Node_2_Path/$JettraStore_FileName"
JettraStore_3_FilePath="$Node_3_Path/$JettraStore_FileName"

JettraStoreShell_1_FilePath="$Node_1_Path/$JettraStoreShell_FileName"
JettraStoreShell_2_FilePath="$Node_2_Path/$JettraStoreShell_FileName"
JettraStoreShell_3_FilePath="$Node_3_Path/$JettraStoreShell_FileName"

# Archivos a copiar
JettraStore_Origin="$HOME/NetBeansProjects/jettrastack_local/jettrastorecontainers/JettraStore/target/JettraStore-1.0-SNAPSHOT-uber.jar"
JettraStoreShell_Origin="$HOME/NetBeansProjects/jettrastack_local/jettrastorecontainers/JettraStoreShell/target/JettraStoreShell-1.0-SNAPSHOT-uber.jar"






# 1. Verificar y crear carpetas si no existen
for dir in "$Node_1_Path" "$Node_2_Path" "$Node_3_Path"; do
    if [ ! -d "$dir" ]; then
        mkdir -p "$dir"
        echo "Carpeta $dir creada exitosamente."
    fi
 echo "Nodo configurado en $dir"
done


cp -r "$Node_1_OriginExample" "$Node_1_Path/"
cp -r "$Node_2_OriginExample" "$Node_2_Path/"
cp -r "$Node_3_OriginExample" "$Node_3_Path/"


# 2. Verificar si JettraStore existe, eliminarlo si es así, y copiar el nuevo archivo
for file in "$JettraStore_1_FilePath" "$JettraStore_2_FilePath" "$JettraStore_3_FilePath"; do
    if [ -f "$file" ]; then
        rm -f "$file"
    fi
    cp "$JettraStore_Origin" "$file"
done

# 3. Verificar si JettraStoreShell existe, eliminarlo si es así, y copiar el nuevo archivo
for file in "$JettraStoreShell_1_FilePath" "$JettraStoreShell_2_FilePath" "$JettraStoreShell_3_FilePath"; do
    if [ -f "$file" ]; then
        rm -f "$file"
    fi
    cp "$JettraStoreShell_Origin" "$file"
done



# 4. Dar permisos de ejecución a todos los archivos .sh dentro de las carpetas de los nodos
echo "----------------------------------------------"
echo "Asignando permisos de ejecución a scripts .sh..."
for dir in "$Node_1_Path" "$Node_2_Path" "$Node_3_Path"; do
    if [ -d "$dir" ]; then
        find "$dir" -type f -name "*.sh" -exec chmod +x {} \;
        echo "Permisos otorgados en: $dir"
    fi
done

echo "----------------------------------------------"
echo "Proceso finalizado"
echo "----------------------------------------------"