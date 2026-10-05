# 🐳 JettraStore - Despliegue en Docker y Docker Compose (Clúster Raft Distribuido)

Este documento describe la arquitectura, configuración y comandos para desplegar el clúster de **JettraStore** (Java 25+, Project Panama FFM y Virtual Threads) en contenedores Docker mediante **Docker Compose**.

---

## 🏛️ Arquitectura del Clúster

El entorno Docker Compose orquesta una topología distribuida de tres nodos con consenso Raft:

| Contenedor | Rol | Puerto REST (Host -> Container) | Puerto Clúster / Raft | Volumen de Datos Persistente |
|---|---|---|---|---|
| `jettra-node-01` | **PRIMARY (Líder Master)** | `8081 -> 8080` | `9091 -> 9091` | `jettra_data_node01` (`/jettra/data`) |
| `jettra-node-02` | **SECONDARY (Réplica 1)** | `8082 -> 8080` | `9092 -> 9091` | `jettra_data_node02` (`/jettra/data`) |
| `jettra-node-03` | **SECONDARY (Réplica 2)** | `8083 -> 8080` | `9093 -> 9091` | `jettra_data_node03` (`/jettra/data`) |

Cada nodo cuenta con su propio volumen Docker independiente para garantizar que los archivos de almacenamiento físico (`.jettra`, índices y SSTables) persistan aun si los contenedores son reiniciados o recreados.

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
Asegúrese de empaquetar el JAR y copiar las dependencias en `target/lib`:

```bash
cd /home/avbravo/NetBeansProjects/jettrastack_local/jettrastoreworkspace/JettraStore
mvn clean package -DskipTests
```

### 2. Construir la Imagen Docker
La imagen utiliza **Eclipse Temurin OpenJDK 25** con ZGC y soporte para Project Panama FFM (`--enable-preview` y `--enable-native-access=ALL-UNNAMED`):

```bash
docker build -t jettrastore:1.0 .
```

### 3. Iniciar el Clúster con Docker Compose
Inicia el nodo principal `jettra-node-01` y, una vez saludable, arranca automáticamente los dos nodos secundarios `jettra-node-02` y `jettra-node-03`:

```bash
docker compose up -d
```

### 4. Verificar Estado de los Contenedores y Salud

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

### 5. Healthcheck Individual (Sin Token)
El endpoint `/api/v1/health` es público para permitir sondeos de infraestructura:

```bash
curl http://localhost:8081/api/v1/health
curl http://localhost:8082/api/v1/health
curl http://localhost:8083/api/v1/health
```

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
