#!/bin/bash
# Script de inicio para nodo JettraStore
# Lee los parámetros principales (storage.path / jettra.storage.path, jettra.network.grpc.port, jettra.network.rest.port)
# directamente desde microprofile-config.properties y sincroniza con cluster.config.
#
# Uso:
#   ./start-node.sh [NODE_ID] [NODE_ROLE]
# Ejemplos:
#   ./start-node.sh node-01 PRIMARY
#   ./start-node.sh node-02 SECONDARY
#   ./start-node.sh node-03 SECONDARY

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
     -Dcluster.config.path=config/cluster.config \
     -Dmicroprofile.config.file=config/microprofile-config.properties \
     -jar target/JettraStore-1.0-SNAPSHOT-uber.jar
