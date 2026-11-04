# 🐳 JettraStore - Despliegue en Docker y Docker Compose (Clúster Raft Distribuido)

Este documento describe la arquitectura, configuración y comandos para desplegar el clúster de **JettraStore** (Java 25+, Project Panama FFM, Lilliput Compact Headers y Virtual Threads) en contenedores Docker mediante **Docker Compose**, permitiendo el ajuste de los modos de almacenamiento (`storage.mode`).

---

## 🏛️ Arquitectura del Clúster

El entorno Docker Compose orquesta una topología distribuida de tres nodos con consenso Raft:

| Contenedor | Rol | Puerto REST (Host -> Container) | Puerto Clúster / Raft | Volumen de Datos Persistente |
|---|---|---|---|---|
| `jettra-node-01` | **PRIMARY (Líder Master)** | `8081 -> 8080` | `9091 -> 9091` | `jettra_data_node01` (`/jettra/data`) |
| `jettra-node-02` | **SECONDARY (Réplica 1)** | `8082 -> 8080` | `9092 -> 9091` | `jettra_data_node02` (`/jettra/data`) |
| `jettra-node-03` | **SECONDARY (Réplica 2)** | `8083 -> 8080` | `9093 -> 9091` | `jettra_data_node03` (`/jettra/data`) |

Cada nodo cuenta con su propio volumen Docker independiente para garantizar que los archivos de almacenamiento físico (`.jettra`, índices, SSTables y páginas Off-Heap) persistan aun si los contenedores son reiniciados o recreados.

---

## ⚡ Modos de Almacenamiento y Flags de Java 25

JettraStore soporta 3 modos de almacenamiento nativos configurables tanto por variable de entorno (`JETTRA_STORAGE_MODE`) como por propiedad del sistema (`-Djettra.storage.mode`):

1. **`OFF_HEAP` (Predeterminado en Docker):**
   * Motor de páginas ranuradas nativas *Slotted-Page* en memoria virtual no administrada vía *Project Panama FFM API*.
   * **Cero pausas de GC (GC Pauses = 0)** y liberación determinista por ámbito RAII (`Arena`).
2. **`DISK_MEMORY`:**
   * Motor de disco directo Off-Heap LSM con buffers mapeados en archivo (`JettraMemory`).
   * Persistencia directa en disco sin sobrecargar el Heap.
3. **`JVM_RAM`:**
   * Modo memoria RAM estándar de la JVM (Heap & Stack).

### Ejecución Directa en Host (Standalone)
```bash
java --enable-preview \
     --enable-native-access=ALL-UNNAMED \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+UseCompactObjectHeaders \
     -XX:+UseZGC \
     -Xms2g -Xmx4g \
     -Djettra.storage.mode=OFF_HEAP \
     -jar target/JettraStore-1.0-SNAPSHOT.jar
```

---

## 🔐 Autenticación Criptográfica con JettraJWT Token

La seguridad perimetral de JettraStore exige autenticación criptográfica mediante tokens **JettraJWT** (algoritmo Ed25519 con emisor inmutable `jettra-store-authority`).

### 1. Obtener Token JettraJWT

Realice una petición `POST` al endpoint `/api/v1/auth/login` o `/api/v1/auth/token` con las credenciales del superusuario (por defecto `admin` / `admin-jettra`):

```bash
# Obtener Token desde el Nodo 01 (Líder)
curl -X POST http://localhost:8081/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin-jettra"}'
```

**Respuesta JSON esperada:**
```json
{
  "token": "JettraJWT.YWRtaW58U1VQRVJfQURNSU58MTc5MDkxMTY1OQ.50ddc9f7c7aea1e4f37107742a84a42bae5576bfcaf4dfaf25402b0b0f675c1d",
  "token_type": "Bearer",
  "status": "AUTHENTICATED",
  "node_id": "node-01",
  "expires_in": 86400
}
```

### 2. Guardar Token en una variable de shell

```bash
export JETTRA_TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin-jettra"}' | grep -o '"token":"[^"]*' | cut -d'"' -f4)

echo "Token JettraJWT: $JETTRA_TOKEN"
```

### 3. Consumir Endpoints Protegidos con la Cabecera `Authorization: Bearer`

```bash
# Consultar Estado del Clúster Raft y Pares en Nodo 01 (Líder)
curl -H "Authorization: Bearer $JETTRA_TOKEN" http://localhost:8081/api/v1/cluster/status

# Consultar Estado de JettraPolice (Alertas Anti-OOM en Memoria)
curl -H "Authorization: Bearer $JETTRA_TOKEN" http://localhost:8081/api/v1/police/alerts
```

---

## 🚀 Instrucciones de Instalación y Ejecución

### 1. Compilar el Proyecto (Maven)
Asegúrese de empaquetar el JAR:

```bash
mvn clean package -DskipTests
```

### 2. Construir la Imagen Docker
La imagen utiliza **Liberica Runtime Container JRE 25 (o Eclipse Temurin 25)** con ZGC, Compact Object Headers (Lilliput) y soporte para Project Panama FFM (`--enable-preview` y `--enable-native-access=ALL-UNNAMED`):

```bash
docker build -t jettrastore:1.0 .
```

### 3. Iniciar el Clúster con Docker Compose y Ajuste de Storage Mode

Por defecto, el clúster arranca en modo **`OFF_HEAP`**. Para conmutar el modo de almacenamiento deseado para todos los nodos:

```bash
# Iniciar en modo OFF_HEAP (Predeterminado)
JETTRA_STORAGE_MODE=OFF_HEAP docker compose up -d

# Iniciar en modo DISK_MEMORY (Direct Disk LSM)
JETTRA_STORAGE_MODE=DISK_MEMORY docker compose up -d

# Iniciar en modo JVM_RAM (Heap estándar)
JETTRA_STORAGE_MODE=JVM_RAM docker compose up -d
```

### 4. Ejecución de un Contenedor Autónomo (Standalone) con `docker run`

```bash
docker run -d \
  --name jettrastore-standalone \
  -p 8080:8080 \
  -p 9091:9091 \
  -e JETTRA_STORAGE_MODE=OFF_HEAP \
  -e JAVA_OPTS="--enable-preview --enable-native-access=ALL-UNNAMED -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:+UseZGC -Xms2g -Xmx4g -Djettra.storage.mode=OFF_HEAP" \
  -v /var/jettra/data:/jettra/data \
  jettrastore:1.0
```

### 5. Verificar Estado de los Contenedores y Salud

```bash
docker compose ps
```

Verifique los logs en tiempo real de cualquiera de los nodos:

```bash
# Logs del nodo principal
docker compose logs -f jettra-node-01

# Logs de todos los nodos
docker compose logs -f
```

En la salida del log se apreciará el banner de arranque con el modo activo:
```text
================================================================================
            JETTRASTORE DISTRIBUTED MULTI-MODEL DATABASE (JAVA 25+)            
================================================================================
Node ID: node-01 (IP: 127.0.0.1, REST: 8080, gRPC: 9091) | Role: PRIMARY | Storage Path: /jettra/data
Project Panama Off-Heap Direct: true | MemTable: 128 MB
Storage Engine Mode: OFF_HEAP (Motor de páginas nativas Slotted-Page Off-Heap FFM (JettraStore-OffHeap))
...
```

---

## ⚡ Arranque Instantáneo con CRaC y CRIU (< 20 ms)

La imagen Docker y los servicios de `docker-compose.yml` vienen configurados con las capacidades del kernel necesarias (`CHECKPOINT_RESTORE`, `SYS_PTRACE`, `seccomp:unconfined`) y volúmenes dedicados para snapshots de memoria CRIU (`jettra_checkpoint_node0*`).

### 1. Tomar Checkpoint dentro del Contenedor
Una vez iniciado el contenedor y precalentados los datos:

```bash
docker exec -it jettra-node-01 jcmd /app/JettraStore.jar JDK.checkpoint
```

### 2. Reiniciar el Clúster Instantáneamente desde el Snapshot
```bash
JETTRA_CRAC_RESTORE=true docker compose up -d
```
El servidor reanudará su ejecución en frío en menos de **20 ms**.

---

## 💾 Gestión de Volúmenes de Datos Persistentes

Para inspeccionar los volúmenes persistentes creados por Docker:

```bash
docker volume ls | grep jettra
```

Listará:
- `jettra_data_node01`
- `jettra_data_node02`
- `jettra_data_node03`

Los datos continuarán intactos entre ejecuciones de `docker compose down` y `docker compose up -d`.

---

## 🛑 Detener el Clúster

```bash
# Detener y remover contenedores conservando los volúmenes de datos
docker compose down

# Si desea reiniciar eliminando los datos (reset completo):
docker compose down -v
```
