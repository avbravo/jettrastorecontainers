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


---

## 4. Administración de Nodos con Rol `ADMIN` y Zero-Downtime (`/cluster` y `/cluster-config`)
- **Protección de rol `ADMIN`:** Añadir nodos, remover nodos, apagar nodos y editar/sincronizar `cluster.config` en `/cluster-config` requieren rol global `ADMIN`.
- **Bloqueo de remoción de nodos activos:** En la vista `/cluster`, si un nodo se encuentra `ACTIVE` (`RUNNING`), el botón **Expulsar** se muestra bloqueado (`Expulsar (Detener primero)`) e informa al operador que primero debe detener el nodo con **Apagar Nodo**. Solo los nodos en estado `INACTIVE` / `OFFLINE` pueden removerse del clúster.
- **Continuidad operativa (Zero-Downtime):** Al añadir un nuevo nodo o remover un nodo detenido, ni el nodo `PRIMARY` ni los `SECONDARY` activos se detienen; `cluster.config` y las bases de datos convergen automáticamente en segundo plano.

---

## 5. Navegación Jerárquica en Árbol (`Tree`), Buckets `NONE`/`DEFINED` e Índices por Bucket (`/explorer`)
- **Árbol jerárquico en `/explorer`:** Panel lateral construido con componentes JettraFlux que organiza la navegación en 4 niveles:
  `Base de Datos` → `Tipo de Engine` (`DOCUMENT`, `JAVA_RECORD`, `KEY_VALUE`, `VECTOR`, `GRAPH`, `TIMESERIES`, `COLUMNAR`, `GEOSPATIAL`, `RELATIONAL`) → `Bucket [NONE | DEFINED]` → `Registros`.
- **Creación de Buckets ilimitados (`+ Nuevo Bucket`):** Permite crear buckets dentro de cualquier engine eligiendo:
  - **`NONE`**: esquema flexible sin validación rígida.
  - **`DEFINED`**: esquema estructurado mediante atributos JSON (`atribute_name`, `type_atribute`, `jettra_rules`, `description`). Al insertar o editar registros, `JettraStoreExplorer` valida el contenido contra las reglas del bucket y muestra una alerta clara en caso de incumplimiento.
- **Panel de Índices por Bucket (`Accion-4`):** Debajo de la tabla de registros del bucket activo se muestra su esquema (`NONE` o tabla de atributos `DEFINED`), la tabla de índices asociados (`Nombre`, `Campo(s)`, `Tipo`, `Unicidad`, `Estado`, botón `Eliminar`) y un formulario directo para crear nuevos índices (`+ Crear Índice`).


---

## 6. Formulario Dinámico de Esquemas `DEFINED` e Índices `NUMERIC` / `AUTO_INCREMENT`
- **Diseño responsivo corregido en `/explorer`**: el árbol jerárquico (`tree`) se fija en una barra lateral dedicada (`sticky`, con scroll independiente y adaptación automática a columna única en pantallas `< 920px`) junto al explorador principal y los modales de creación/edición sin desbordamientos.
- **Carga dinámica de estructura (`DEFINED`)**: al crear o editar un registro en un bucket con estructura `DEFINED`, el formulario genera automáticamente los controles tipados para cada atributo (`number` con `step="1"` o `step="any"`, `email`, `dropdown` booleano, `required` para `NOT_NULL`, `min` para `POSITIVE`/`NON_NEGATIVE`, y etiqueta `[AUTO_INCREMENT]` para campos autogenerados), construye el JSON tipado y valida contra `JettraBucketSchema` antes de persistir.
- **Gestor de índices numéricos y autoincrementables**: tanto en `/explorer` como en `/databases`, el selector de tipos de índice incluye `NUMERIC` y `AUTO_INCREMENT`, propagando la creación al nodo `PRIMARY` vía Raft.


---

## 7. Subárboles por Bucket y CRUD Completo de Estructura (`/explorer`)

### 7.1 Jerarquía Completa del Árbol de Navegación
Al abrir `/explorer`, la barra lateral izquierda muestra la jerarquía completa en cuatro niveles:
```text
📂 examplesedb
  ⚙️ JAVA_RECORD
    ▾ 📦 Pais [DEFINED]
       ├─ 📐 Estructura (3 campos)   → /explorer?db=examplesedb&bucket=Pais&section=schema
       ├─ ⚡ Índices (2)             → /explorer?db=examplesedb&bucket=Pais&section=indexes
       └─ 📄 Registros (15)          → /explorer?db=examplesedb&bucket=Pais&section=records
  ⚙️ DOCUMENT
    ▸ 📦 NotasLibres [NONE]
```

### 7.2 CRUD Completo de Estructura (`NONE` ↔ `DEFINED`)
Desde el subárbol **📐 Estructura** de cualquier bucket:
- **Crear Estructura (`NONE` → `DEFINED`)**: clic en **`➕ Crear Estructura (DEFINED)`** y definir el arreglo JSON de atributos:
  ```json
  [
    { "atribute_name": "codigo", "type_atribute": "Long", "jettra_rules": "AUTO_INCREMENT", "description": "Código secuencial" },
    { "atribute_name": "name", "type_atribute": "String", "jettra_rules": "NOT_NULL", "description": "Nombre del país" },
    { "atribute_name": "precio", "type_atribute": "Double", "jettra_rules": "POSITIVE", "description": "Valor numérico" }
  ]
  ```
- **Consultar / Ver JSON**: clic en **`👁️ Ver JSON`** para inspeccionar la definición exacta del esquema.
- **Editar / Actualizar Estructura**: clic en **`✏️ Editar Estructura`** para agregar, modificar o quitar atributos y reglas `jettra_rules`.
- **Eliminar Estructura (`DEFINED` → `NONE`)**: clic en **`🗑️ Eliminar Estructura`** (con confirmación) para regresar el bucket a modo libre sin borrar los registros existentes.

### 7.3 Validación Estricta de Registros e Índices Numéricos / Autoincrementables
- Al abrir **`➕ Nuevo Registro`** o **`✏️ Editar`** sobre un bucket `DEFINED`, el formulario carga dinámicamente los campos de la estructura con sus restricciones (`required`, `number`, `step="1"`/`"any"`, `min`, `email`, `AUTO_INCREMENT`) y rechaza cualquier registro que incumpla una regla antes de persistir.
- En el subárbol **⚡ Índices**, el selector de campos lista automáticamente los atributos del bucket (incluyendo campos numéricos y autoincrementables) junto con los tipos `NUMERIC`, `AUTO_INCREMENT`, `BTREE`, `HASH`, `SPARSE`, `VECTOR_HNSW` y `SPATIAL_R_TREE`.
