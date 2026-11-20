# JettraStore Explorer — Guía Oficial de Arquitectura y Operación

**JettraStore Explorer** es la consola web de administración, monitoreo y exploración multimodelo para **JettraStore**, construida con **JettraFlux** y **JettraServer** en **Java 25+**.

---

## 1. Configuración Inicial (`microprofile-config.properties`)

La conexión inicial al clúster de **JettraStore** se configura mediante la propiedad `cluster.nodes` en `src/main/resources/META-INF/microprofile-config.properties` (o en `./microprofile-config.properties` / `./config/microprofile-config.properties`):

```properties
# JettraStore Explorer Configuration (Eclipse MicroProfile Config 3.1)
server.host=127.0.0.1
server.port=8085
server.contextpath=/
storage.mode=OFF_HEAP
app.title=JettraStore Explorer
cluster.multinode.active=on
cluster.healthcheck.timeout.ms=1000
cluster.nodes=node-01:127.0.0.1:50001,node-02:127.0.0.1:50002,node-03:127.0.0.1:50003
```

### Sincronización Bidireccional al Añadir o Expulsar Nodos
Cuando desde `/cluster` se agrega un nuevo nodo o se expulsa un nodo secundario:
1. **Actualización local en JettraStoreExplorer:** Se actualiza automáticamente la propiedad `cluster.nodes` en `microprofile-config.properties` preservando comentarios y demás parámetros.
2. **Actualización y replicación de `cluster.config`:** Se genera el archivo `cluster.config` actualizado (con `id`, `role`, `ip`, `grpc.port` y `rest.port`, **sin** propiedades `cluster.node.<N>.storage.path`, ya que cada nodo define su propio `jettra.storage.path` en su `microprofile-config.properties`) y se envía mediante la trama Raft `TYPE_SYNC_CLUSTER_CONFIG` (`0x17`) primero al nodo `PRIMARY` y luego a los nodos `SECONDARY`, replicándose en todo el clúster.
3. **Sincronización Automática de Datos desde el PRIMARY:**
   Cada vez que un nodo se añade al clúster o cuando fue detenido y luego se reinicia (o se restablece su enlace de red), este nodo se actualiza automáticamente con el estado del nodo PRIMARY, obteniendo la réplica completa de la base de datos y objetos desde el nodo PRIMARY para que el clúster se mantenga permanentemente actualizado.
4. **Reflejo Inmediato de Estatus en `/map` y `/cluster`:**
   Al detenerse o reiniciarse cualquier nodo, el motor de telemetría y eventos Server-Sent Events (SSE) notifica en tiempo real a las vistas web, actualizando de inmediato los indicadores visuales de disponibilidad y latencia sin requerir recargar la página.

---

## 2. Páginas y Funcionalidades Principales

| Ruta | Módulo | Descripción |
| :--- | :--- | :--- |
| `/cluster` | **Nodos del Clúster** | Monitoreo en tiempo real (`ACTIVE`/`INACTIVE`, `PRIMARY`/`SECONDARY`), encendido/apagado de nodos, alta y expulsión con sincronización automática de `cluster.nodes` y `cluster.config`. |
| `/map` | **Mapa de Transferencia** | Visualización gráfica en tiempo real de la topología del clúster, estado de nodos, enlaces de replicación con latencia en `ms`, animación de paquetes Raft/datos en tránsito y bitácora en vivo de transferencias. |
| `/databases` | **Bases de Datos y Motores** | Creación de bases de datos multimodelo (con los 9 motores nativos de JettraStore), administración de buckets/motores y gestión de índices (`BTREE`, `HASH`, `NUMBER`, `SEQUENTIAL`, `VECTOR_HNSW`, `SPATIAL_R_TREE`, etc.) con persistencia directa en el `jettra.storage.path` del nodo `PRIMARY` y replicación al clúster. |
| `/examples` | **Bases de Datos de Ejemplo** | JettraStoreExplorer inicia limpio sin bases de ejemplo precargadas. Desde `/examples` se listan las bases de muestra (`example_factura_db`, `samples_hostipal_db`, `samples_ambiental_db`, `system_metadata_db`) permitiendo instalarlas o eliminarlas individualmente o en lote. |
| `/explorer` | **Explorador CRUD** | Consulta, inserción, edición, eliminación, paginación y visualización de versiones adaptada al tipo de motor (`DOCUMENT`, `OBJECT`, `JAVA_RECORD`, `GRAPH`, `KEY_VALUE`, `GEOSPATIAL`, `VECTOR`, `TIMESERIES`, `COLUMNAR`). |
| `/query` | **Consola SQL / JQL** | Ejecución interactiva de consultas SQL/JQL y comandos administrativos. |
| `/status` | **Estatus y Telemetría** | Métricas del servidor, memoria Off-Heap Panama FFM y salud del clúster en tablas responsive. |
| `/police` | **Centinelas JettraPolice** | Alertas preventivas anti-OOM y auditoría de incidentes del clúster. |
| `/connections` | **Conexiones** | Gestión de perfiles de conexión hacia instancias de JettraStore. |
| `/users` | **Usuarios y Roles** | Administración de usuarios y permisos de acceso por base de datos. |
| `/backups` | **Respaldos y Snapshots** | Gestión y restauración de copias de seguridad. |

---

## 3. Diseño Responsivo con JettraFlux
Todas las vistas están construidas exclusivamente con componentes **JettraFlux** (`FluxView`, `Div`, `Datatable`, `Modal`, `JettraButton`, `SelectOne`, etc.), incluyendo soporte de tema claro/oscuro, diálogos superpuestos sobre el formulario origen y tablas responsive adaptadas a resoluciones móviles (ej. `360x780`).
