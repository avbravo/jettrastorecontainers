#!/bin/bash
# Script de inicio para nodo JettraStore
# Lee los parámetros principales (jettra.storage.path, jettra.network.grpc.port, jettra.network.rest.port)
# directamente desde database.properties y sincroniza con jettra.config.
#
# Uso:
#   ./runnode.sh [NODE_ID] [NODE_ROLE]
# Ejemplos:
#   ./runnode.sh node-01 PRIMARY
#   ./runnode.sh node-02 SECONDARY
#   ./runnode.sh node-03 SECONDARY

NODE_ID=${1:-node-01}
NODE_ROLE=${2:-PRIMARY}

java --enable-preview \
     --enable-native-access=ALL-UNNAMED \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+UseCompactObjectHeaders \
     -XX:+UseZGC \
     -Xms2g -Xmx6g \
     -Djettra.node.id="${NODE_ID}" \
     -Djettra.node.role="${NODE_ROLE}" \
     -Djettra.config.path=jettra.config \
     -Ddatabase.properties.path=database.properties \
     -jar JettraStore-1.0-SNAPSHOT-uber.jar
