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

## 7. Árbol Jerárquico, Subárboles por Bucket, CRUD de Estructura e Índices Numéricos / Autoincrementables (`/explorer` y `/databases`)

### 7.1 Árbol de Navegación (`Base de Datos` → `Engine` → `Bucket` → Subárboles)
Al acceder a `/explorer`, la barra lateral izquierda (`JettraFlux` sticky y responsiva) organiza todo el catálogo en cuatro niveles visuales con indicadores de expansión (`▾` / `▸`), etiquetas de motor por color, estado del esquema (`DEFINED` / `NONE`) y contadores en vivo:

```text
▾ 📂 ventas_db (3)
   ├─ ⚙️ DOCUMENT (2 bucket(s))
   │   ├─ ▾ 📦 Productos [DEFINED]
   │   │    ├─ 📐 Estructura (4 campos)   → /explorer?db=ventas_db&bucket=Productos&section=schema
   │   │    ├─ ⚡ Índices (2)             → /explorer?db=ventas_db&bucket=Productos&section=indexes
   │   │    └─ 📄 Registros (12)          → /explorer?db=ventas_db&bucket=Productos&section=records
   │   └─ ▸ 📦 NotasLibres [NONE]
   │        ├─ 📐 Estructura (NONE)       → /explorer?db=ventas_db&bucket=NotasLibres&section=schema
   │        │    └─ ➕ Añadir Estructura  → /explorer?db=ventas_db&bucket=NotasLibres&section=schema&action=new_schema
   │        ├─ ⚡ Índices (0)             → /explorer?db=ventas_db&bucket=NotasLibres&section=indexes
   │        └─ 📄 Registros (5)           → /explorer?db=ventas_db&bucket=NotasLibres&section=records
   └─ ⚙️ JAVA_RECORD (1 bucket(s))
       └─ ▸ 📦 Paises [DEFINED]
```

Cada bucket activo despliega tres subárboles dedicados:
1. **📐 Estructura (`section=schema`)**: administra el esquema del bucket (`NONE` o `DEFINED`).
2. **⚡ Índices (`section=indexes`)**: lista, crea, reconstruye y elimina índices secundarios del bucket.
3. **📄 Registros (`section=records`)**: explora, busca, pagina, inserta, edita, versiona y elimina registros.

---

### 7.2 CRUD Completo de Estructuras de Bucket (`NONE` ↔ `DEFINED`)
En el subárbol **📐 Estructura** se presentan botones explícitos según el estado actual del bucket:

| Operación CRUD | Estado del Bucket | Botón / Ruta en JettraStoreExplorer | Acción |
| :--- | :--- | :--- | :--- |
| **Create** | `NONE` | **`➕ Crear Estructura (DEFINED)`** (`?db=...&bucket=...&section=schema&action=new_schema`) | Abre el modal JettraFlux para definir el arreglo JSON de atributos y activar validación estricta. |
| **Read / View** | `DEFINED` | **`👁️ Ver JSON`** (`?db=...&bucket=...&section=schema&action=view_schema`) | Muestra el contrato JSON exacto de atributos (`atribute_name`, `type_atribute`, `jettra_rules`, `description`). |
| **Update / Edit** | `DEFINED` | **`✏️ Editar Estructura`** (`?db=...&bucket=...&section=schema&action=edit_schema`) | Permite agregar, modificar o quitar atributos y reglas de validación. |
| **Delete** | `DEFINED` | **`🗑️ Eliminar Estructura`** (`?db=...&bucket=...&section=schema&action=delete_schema`) | Elimina la estructura `DEFINED` y regresa el bucket a modo flexible `NONE` sin borrar registros existentes. |

#### Ejemplo real de estructura `DEFINED` para un bucket `Productos`
```json
[
  {
    "atribute_name": "codigo",
    "type_atribute": "Long",
    "jettra_rules": "AUTO_INCREMENT",
    "description": "Secuencia autoincrementable única del producto"
  },
  {
    "atribute_name": "sku",
    "type_atribute": "String",
    "jettra_rules": "NOT_NULL,UNIQUE,MIN_LENGTH:3",
    "description": "Código SKU único"
  },
  {
    "atribute_name": "nombre",
    "type_atribute": "String",
    "jettra_rules": "NOT_NULL,NOT_BLANK",
    "description": "Nombre comercial del producto"
  },
  {
    "atribute_name": "precio",
    "type_atribute": "BigDecimal",
    "jettra_rules": "NOT_NULL,POSITIVE",
    "description": "Precio unitario mayor a 0"
  }
]
```

#### Reglas `jettra_rules` soportadas
- `NOT_NULL`, `REQUIRED`, `NOT_EMPTY`, `NOT_BLANK`: campos obligatorios.
- `UNIQUE`: garantiza unicidad del valor frente a los registros existentes del bucket (al crear y al editar).
- `AUTO_INCREMENT` (o `SEQUENTIAL`): secuencia numérica autoincrementable; si se omite en el formulario de inserción, `JettraStoreExplorer` calcula e inyecta automáticamente el siguiente valor consecutivo (`1`, `2`, `3`, ...).
- `POSITIVE` (`> 0`), `NON_NEGATIVE` (`>= 0`), `MIN:<valor>`, `MAX:<valor>`: restricciones numéricas.
- `MIN_LENGTH:<n>`, `MAX_LENGTH:<n>`, `EMAIL`, `REGEX:<patron>`: restricciones de texto.

---

### 7.3 Validación Estricta en Registros (`DEFINED`) vs Flujo Flexible (`NONE`)
- **Bucket `DEFINED`**:
  - Al abrir **`➕ Nuevo Registro`** (`/explorer?db=ventas_db&bucket=Productos&action=new`) o **`✏️ Editar`** (`/explorer?db=ventas_db&bucket=Productos&edit_id=PROD-001`), el formulario carga dinámicamente los controles tipados (`type="number"`, `step="1"` o `step="any"`, `min`, `max`, `minlength`, `maxlength`, `pattern`, `required`, `email`, `Dropdown` booleano).
  - Antes de persistir (`POST /explorer` con `action=save_new` o `action=save_edit`), `JettraStorePlugin.validateRecordForBucket(...)` verifica tipos, obligatoriedad, rangos y unicidad (`UNIQUE`).
  - **Ejemplo rechazado** (precio negativo y `nombre` vacío):
    ```json
    { "sku": "TEC-01", "nombre": "", "precio": -15.0 }
    ```
    Resultado: se cancela la escritura y se muestra la alerta roja `⛔ Validación fallida: Atributo 'nombre' no puede estar vacío (regla NOT_NULL). | Atributo 'precio' debe ser un número positivo (> 0).`
  - **Ejemplo aceptado** (omitiendo `codigo` para autogeneración `AUTO_INCREMENT`):
    ```json
    { "sku": "TEC-01", "nombre": "Teclado Mecánico", "precio": 89.50 }
    ```
    Resultado: `JettraStoreExplorer` asigna automáticamente `"codigo": 1`, persiste el registro y lo replica al nodo `PRIMARY`.
- **Bucket `NONE`**:
  - Permite crear y editar registros usando el editor específico del motor (`DOCUMENT` JSON, `JAVA_RECORD`, `KEY_VALUE`, `GRAPH`, `VECTOR`, `TIMESERIES`, `GEOSPATIAL`, `COLUMNAR`, `RELATIONAL`) sin exigir esquema previo, manteniendo la acción directa `➕ Añadir Estructura (DEFINED)` en el árbol.

---

### 7.4 Gestor de Índices con Soporte de Campos Numéricos y Autoincrementables
Tanto en **`/explorer?db=<db>&bucket=<bucket>&section=indexes`** como en **`/databases?db=<db>&manage_indexes_db=<db>&index_bucket=<bucket>`**:

1. **Detección automática de campos numéricos y autoincrementables (`detectIndexableFields`)**:
   - Inspecciona los atributos del esquema `DEFINED`, los índices ya creados y hasta 50 registros de muestra del bucket (funcionando también en buckets `NONE`).
   - Clasifica y resalta con insignias dedicadas los campos **autoincrementables** (`🔢 codigo (Long • AUTO_INCREMENT)`) y **numéricos** (`#️⃣ precio (BigDecimal • NUMERIC)`), precargándolos en el selector de campos del formulario.
2. **Creación, Reconstrucción y Eliminación de índices**:
   - Tipos disponibles: `BTREE`, `HASH`, `NUMERIC`, `AUTO_INCREMENT`, `NUMBER`, `SEQUENTIAL`, `SPARSE`, `FULLTEXT`, `VECTOR_HNSW`, `GRAPH_ADJACENCY`, `TIMESERIES_BRIN`, `SPATIAL_R_TREE`.
   - Los índices `AUTO_INCREMENT` y `SEQUENTIAL` se crean automáticamente como `UNIQUE` y propagan la operación al nodo `PRIMARY` vía trama Raft.
   - Ejemplo real de creación vía formulario (`POST /explorer`):
     ```text
     action=create_bucket_index&db=ventas_db&bucket=Productos&indexName=idx_productos_codigo&indexFieldSelect=codigo&indexType=AUTO_INCREMENT&indexUnique=true
     action=create_bucket_index&db=ventas_db&bucket=Productos&indexName=idx_productos_precio&indexFieldSelect=precio&indexType=NUMERIC&indexUnique=false
     ```
   - Cada fila de la tabla de índices ofrece acciones directas de **`🔄 Reconstruir`** (`action=rebuild_bucket_index`) y **`🗑️ Eliminar`** (`action=delete_bucket_index`).

---

## 8. Administración de Índices, Motores de Almacenamiento y Buckets en `/database` (`/databases`)

La página **`/database`** (alias directo de `/databases`) centraliza dos diálogos modales JettraFlux sobre el catálogo maestro de bases de datos:

### 8.1 Diálogo "Administración de Índices en `[nombre-base-de-datos]`"
Al hacer clic en **`⚡ Administrar Índices`** (`/database?db=ventas_db&manage_indexes_db=ventas_db`):
1. **Agrupación jerárquica `Engine` → `Bucket`**: lista los motores activos de la base de datos y, dentro de cada motor, los **Buckets** disponibles para indexar.
2. **Regla estricta de asignación**:
   - Un índice **nunca** se asigna directamente al `Engine`.
   - Toda operación de consulta, creación (`add_index`), edición (`edit_index`), reconstrucción (`rebuild_index`) o eliminación (`delete_index`) se ejecuta **exclusivamente sobre un Bucket concreto** dentro de su Engine.
3. **Coherencia con la estructura del Bucket**:
   - En buckets con estructura **`DEFINED`**, el servidor verifica que todos los campos del índice pertenezcan a los atributos declarados en el esquema (además de `id`). Si se intenta indexar un campo inexistente, la operación es rechazada con un mensaje de error claro.
   - En buckets sin estructura (**`NONE`**), los campos indexados se aceptan de forma segura y los campos numéricos existentes se detectan automáticamente desde los registros de muestra.
4. **Acciones por índice**: **`➕ Crear Índice`**, **`✏️ Editar`**, **`🔄 Reconstruir`** y **`🗑️ Eliminar`**, con soporte completo para `BTREE`, `HASH`, `NUMERIC`, `AUTO_INCREMENT`, `NUMBER`, `SEQUENTIAL`, `FULLTEXT`, `VECTOR_HNSW`, `GRAPH_ADJACENCY`, `TIMESERIES_BRIN` y `SPATIAL_R_TREE`.

### 8.2 Diálogo "Motores de Almacenamiento y Buckets en `[nombre-base-de-datos]`"
Al hacer clic en **`⚙️ Administrar Motores`** (`/database?db=ventas_db&manage_engines=true`):
1. **Listado por Engine**: muestra todos los Engines de almacenamiento (`DOCUMENT`, `KEY_VALUE`, `GRAPH`, `TIMESERIES`, `GEOSPATIAL`, `JAVA_RECORD`, `VECTOR`, `COLUMNAR`, `OBJECT`, `RELATIONAL`) y, dentro de cada uno, sus **Buckets** asociados con estado de estructura (`DEFINED` / `NONE`), conteo de objetos e índices activos.
2. **Operaciones CRUD sobre Buckets**:
   - **Añadir Bucket (`action=add_bucket`)**: dentro del Engine seleccionado, eligiendo modo `NONE` (esquema libre) o `DEFINED` (validación estricta).
   - **Editar Bucket (`action=edit_bucket`)**: permite renombrar el bucket, actualizar su descripción, conmutar entre `NONE` y `DEFINED`, y editar su tabla de estructura.
   - **Remover Bucket (`action=delete_bucket`)**: elimina el bucket con confirmación explícita limpiando sus registros e índices asociados.
3. **Tabla de Definición de Estructura (`| nombre | tipo | rules | descripcion |`)**:
   Cuando el bucket utiliza modo **`DEFINED`**, sus campos se administran mediante la tabla estructurada con botón **`➕ Añadir Fila`**:

| nombre | tipo | rules | descripcion |
| :--- | :--- | :--- | :--- |
| `codigo` | `Long` | `AUTO_INCREMENT` | Identificador secuencial único |
| `creadoEn` | `Instant` | `NOT_NULL` | Fecha y hora UTC ISO-8601 |
| `etiquetas` | `List<String>` | `NOT_NULL` | Lista ordenada de etiquetas de texto |
| `categorias` | `Set<String>` | `NOT_NULL` | Conjunto de categorías sin duplicados |
| `puntajes` | `ArrayList<Integer>` | `NOT_NULL` | Arreglo dinámico de enteros |
| `direccion` | `Direccion` | `NOT_NULL` | Objeto de dominio definido por el usuario (`{ "calle": "...", "ciudad": "..." }`) |

- **Tipos soportados en la columna `tipo`**:
  - **Estándar Java**: `String`, `Integer`/`int`, `Long`/`long`, `Double`/`double`, `Float`/`float`, `Short`, `Byte`, `BigDecimal`, `Boolean`, `Instant`, `LocalDate`, `LocalDateTime`, `UUID`.
  - **Colecciones genéricas parametrizadas**: `List<T>`, `Set<T>` (valida ausencia de duplicados) y `ArrayList<T>` (valida cada elemento contra `T`, por ejemplo `List<String>`, `Set<MiObjeto>`, `ArrayList<Integer>`).
  - **Objetos/Records de dominio del usuario**: cualquier clase o `record` personalizado (ej. `Direccion`, `MiObjeto`, `io.jettra.domain.Cliente`), validado estrictamente como estructura JSON objeto (`{ ... }`).

---

## 9. Compilación, Pruebas (`JettraTest`) y Ejecución

```bash
# Compilar y ejecutar toda la suite de pruebas JettraTest (Java 25+)
cd JettraStoreExplorer
mvn clean verify

# Ejecutar JettraStoreExplorer (puerto por defecto 8085 según microprofile-config.properties)
java -jar target/JettraStoreExplorer-1.0.0-SNAPSHOT.jar
```
