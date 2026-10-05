export JETTRA_NODE_ID=node-01
export JETTRA_NODE_ROLE=PRIMARY
export JETTRA_REST_PORT=8080
export JETTRA_GRPC_PORT=9091
export JETTRA_STORAGE_PATH=~/jettra/data

java --enable-preview \
     --enable-native-access=ALL-UNNAMED \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+UseCompactObjectHeaders \
     -XX:+UseZGC \
     -Xms2g -Xmx6g \
     -Djettra.config.path=jettra.config \
     -jar JettraStore-1.0-SNAPSHOT-uber.jar
