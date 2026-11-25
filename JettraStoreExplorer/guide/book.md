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

### 7.1 Vista Simplificada de `/explorer` (RF-05, RF-06 y RF-07)
Al acceder a `/explorer?db=<base_de_datos>`:
- **Base de datos focalizada y navegación única en el Tree (RF-05 y RF-07):** Se muestra **únicamente la base de datos activa/seleccionada** y su jerarquía (`Base de Datos` → `Engine` → `Bucket`), sin listar las demás bases de datos y **sin una sección redundante de Engines/Buckets debajo de `🗄️ Base de Datos Activa`**. Toda la navegación entre Engines y Buckets se realiza exclusivamente desde el **Tree principal**, manteniendo el área central dedicada a la búsqueda y tabla de registros. Para cambiar de base de datos o administrar motores/buckets, se incluye un acceso directo a **`/database`**.
- **Sin botón `+ Nuevo Bucket`:** La creación, edición y eliminación de buckets, estructuras e índices está centralizada en **`/database`**.
- **Diálogo modal de consulta `📐 Estructura del Bucket`:** Al seleccionar un bucket en el árbol y hacer clic en **📐 Estructura** (`/explorer?db=<db>&bucket=<bucket>&section=schema`), se abre un **diálogo modal superpuesto** sobre `/explorer` (cerrable con **`✖ Cerrar`** o `Esc`, sin perder el contexto del árbol ni los registros) que presenta los atributos del bucket en formato **`DataTable`** (`Nombre`, `Tipo`, `Reglas`, `Descripción`), o un mensaje claro si el bucket opera en modo `NONE` (esquema libre). Este modal es exclusivamente de **consulta visual** y no incluye botones de *Ver JSON*, *Editar Estructura* ni *Eliminar Estructura*.
- **Diálogo modal de consulta `⚡ Índices del Bucket` (RF-06):** Al seleccionar un bucket en el árbol y hacer clic en **⚡ Índices** (`/explorer?db=<db>&bucket=<bucket>&section=indexes`), se abre un **diálogo modal superpuesto** con el título **`Índices del Bucket '<nombre-bucket>' (<base-de-datos> / <engine>)`** (cerrable con **`✖ Cerrar`**, **`Cerrar`** o `Esc`) que lista los índices del bucket en formato **`DataTable`** (`Nombre del índice`, `Campo(s)`, `Tipo / Estrategia`, `Unicidad`, `Estado`) o un estado vacío claro si no hay índices configurados. Es de **solo lectura** (sin botones **Reconstruir** ni **Eliminar**); las operaciones de creación, reconstrucción y eliminación se realizan desde **`/database`**.

```text
▾ 📂 ventas_db (2)
   └─ ⚙️ DOCUMENT (2 bucket(s))
       ├─ ▾ 📦 Productos [DEFINED]
       │    ├─ 📐 Estructura (4 campos)   → abre modal DataTable solo lectura sobre /explorer
       │    ├─ ⚡ Índices (2)             → abre modal DataTable solo lectura sobre /explorer
       │    └─ 📄 Registros (12)          → /explorer?db=ventas_db&bucket=Productos&section=records
       └─ ▸ 📦 NotasLibres [NONE]
```

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


---

## 10. Identificador Automático `JettraId` en `/explorer` (`➕ Crear Nuevo Registro`)

El diálogo **`➕ Crear Nuevo Registro en [ <db> / <bucket> ] • Motor: ... • Estructura: ...`** en `/explorer` integra **`JettraId`** (16 bytes / 32 caracteres hexadecimales en orden big-endian: **4 bytes** timestamp Unix + **5 bytes** ID aleatorio de proceso/máquina + **4 bytes** hash FNV-1a de `database:bucket` + **3 bytes** contador atómico de 24 bits):

1. **Prellenado automático:** Al abrir `+ Nuevo Registro (Create)`, el campo **`Identificador Único (ID / Clave Primaria)`** se prellena automáticamente con un `JettraId` generado para la base de datos y bucket activos.
2. **Edición libre y regeneración rápida:** El usuario puede sustituir el `JettraId` por cualquier identificador personalizado o pulsar **`🔄 Regenerar JettraId`** (consulta `GET /api/jettraid/generate?db=<db>&bucket=<bucket>`) para obtener un nuevo `JettraId` sin perder los demás datos del formulario.
3. **Descripción opcional:** El campo **`Descripción / Resumen Corto (Opcional)`** no es obligatorio (`required = false`), permitiendo guardar el registro aun cuando se deje vacío.
4. **Integridad y validación sin pérdida de datos:**
   - Si el usuario vacía el campo de ID antes de guardar, el servidor asigna automáticamente un `JettraId`.
   - Si el identificador ingresado ya existe en el bucket, se muestra una notificación de error clara dentro del mismo diálogo **sin cerrarlo ni perder los datos capturados**.


---

## 11. Cabecera Informativa del Bucket y Controles Expandir/Colapsar en `/explorer` (RF-09)

1. **Cabecera informativa del Bucket seleccionado antes del `DataTable`:**
   - Justo encima de la barra de búsqueda y de la tabla de registros (`ID | MOTOR | RESUMEN | VERSION | FECHA MUTACION | ACCIONES`), `/explorer` muestra una tarjeta de contexto (`data-selected-bucket-header="true"`) con el **nombre del Bucket activo**, su **Engine**, la **base de datos**, el **modo de estructura** (`DEFINED (N campos)` o `NONE (Esquema libre)`), la **cantidad de registros** y la **cantidad de índices**.
   - Si no hay ningún bucket seleccionado en la base de datos activa, muestra un estado vacío legible indicando cómo seleccionar un bucket desde el **Tree Explorador Jerárquico**.
2. **Controles `⊞ Expandir todo` y `⊟ Colapsar todo` en el Tree Explorador Jerárquico:**
   - Permiten desplegar de inmediato todos los Engines y subárboles de Buckets (`📐 Estructura`, `⚡ Índices`, `📄 Registros`) o plegarlos todos en un clic, **conservando intacta la selección actual y el registro activo** en `/explorer`.


---

## 12. Distribución Maestro-Detalle en Dos Columnas en `/explorer` (RF-10)

La vista `/explorer` utiliza un **layout maestro-detalle de dos columnas** (`data-explorer-master-detail="true"`) construido con componentes **JettraFlux** y CSS Grid responsivo debajo de la barra superior de **Base de Datos Activa**:

1. **Columna izquierda (`#explorerTreeSidebar`, `28%` con mínimo de `250px` en escritorio):**
   - Contiene el **Tree Explorador Jerárquico** (`Database -> Engine -> Bucket`) junto con sus controles **`⊞ Expandir todo`** y **`⊟ Colapsar todo`**.
   - Se fija con `position: sticky` y desplazamiento vertical independiente (`overflow-y: auto`) para navegar múltiples motores y buckets sin comprimir ni desplazar la tabla principal.
2. **Columna derecha (`#explorerMainCol`, `minmax(0, 1fr)`):**
   - Alineada en la parte superior junto al árbol, presenta de inmediato el bloque informativo **`📦 Bucket Seleccionado: [nombre-bucket]`** (Engine, base de datos, modo de estructura y conteo de registros/índices), seguido por la barra de búsqueda y el **`DataTable`** de registros (`| ID | MOTOR | RESUMEN | VERSION | FECHA MUTACION | ACCIONES |`).
   - Al seleccionar un Bucket desde el árbol izquierdo, el panel derecho se actualiza en sitio de forma inmediata y se persiste el estado expandido/colapsado y la posición de desplazamiento (`scrollTop`) del árbol en `sessionStorage`.
3. **Comportamiento responsivo:**
   - En **escritorio (`> 920px`)**, ambas columnas se muestran lado a lado alineadas superiormente.
   - En **móvil / tablet (`<= 920px`)**, el Grid cambia a una sola columna (`grid-template-columns: 1fr`), apilando el **Tree Explorador Jerárquico** encima del panel de **Bucket Seleccionado** y el **`DataTable`** para evitar cortes horizontales.


---

## 13. Catálogo de Tipos Sin Duplicados y Entrada Libre de Tipos en Buckets (`/databases`, RF-11)

En los diálogos **`➕ Añadir Nuevo Motor / Bucket en [db]`** y **`✏️ Editar Bucket [bucket] • Engine: [engine]`** de `/databases`, la columna **`tipo`** de la tabla de estructura (`| nombre | tipo | rules | descripcion |`) ofrece:

1. **Catálogo depurado sin duplicados:** El selector de tipos sugeridos (`-- Tipo sugerido --`) incluye una sola entrada por tipo estándar y colección (`String`, `Integer`, `Long`, `Double`, `BigDecimal`, `Boolean`, `Instant`, `LocalDate`, `LocalDateTime`, `UUID`, `List`, `ArrayList`, `Set`, `Map`), eliminando entradas repetidas para `List` y `ArrayList`.
2. **Entrada libre de tipos personalizados y colecciones parametrizadas:** Junto al selector sugerido se incluye un campo de texto editable (`attr_type_<i>`) que permite escribir o modificar libremente cualquier tipo Java estándar, clase/`record` propio del usuario (`Cliente`, `Direccion`, `MiRecord`) o colección parametrizada (`List<String>`, `List<MiRecord>`, `ArrayList<Producto>`, `Set<Integer>`, `Map<String, List<Detalle>>`). Al abrir **`✏️ Editar Bucket`**, el valor parametrizado actual se precarga íntegramente sin perder su declaración genérica.
3. **Validación sintáctica previa al guardado:** Al guardar un Bucket en modo `DEFINED`, se valida que ningún campo tenga el `tipo` vacío y que cumpla una sintaxis válida de identificador Java o tipo genérico parametrizado (rechazando entradas vacías, genéricos sin tipo como `List<>` o delimitadores desbalanceados).


---

## 14. Unificación a Bucket en el Motor `DOCUMENT` (RF-12)

En todo el ecosistema **JettraStore** (`JettraStore`, `JettraStoreDriver`, `JettraStoreShell` y `JettraStoreExplorer`), el motor **`DOCUMENT`** opera exclusivamente bajo el modelo unificado de **Bucket** (eliminando las abstracciones y comandos de `COLLECTION`):

- En **`/database`**, el grupo **`⚙️ DOCUMENT`** dentro de **Motores de Almacenamiento y Buckets** permite crear (`➕ Añadir Bucket en DOCUMENT`), editar (`✏️ Editar`), eliminar (`🗑️ Remover`) e indexar (`⚡ Administrar Índices`) únicamente **Buckets** (`NONE` o `DEFINED`).
- En **`/explorer`**, el **Tree Explorador Jerárquico** (`Database -> Engine -> Bucket`), la cabecera **`📦 Bucket Seleccionado`**, el modal de **`📐 Estructura del Bucket`** y el modal de **`⚡ Índices del Bucket`** operan uniformemente sobre **Buckets** para `DOCUMENT` y el resto de motores.

---

## 8. Sincronización en Vivo de `/explorer` con `examplesedb` (`JettraStoreExampleSE`) — RF-14

Cuando una aplicación cliente como **`JettraStoreExampleSE`** crea la base de datos `examplesedb` con sus **Buckets** (`Pais` en `JAVA_RECORD`, `Producto` y `NotasLibres` en `DOCUMENT`), esquemas (`DEFINED`/`NONE`), índices (`AUTO_INCREMENT`, `BTREE`, `NUMERIC`) y registros con **`JettraId`**, la vista `/explorer` de **`JettraStoreExplorer`** los carga en vivo mediante **`JettraStoreDriver`**:

1. **Flujo de consulta en `/explorer`:**
   - `RecordExplorerFluxView` invoca `plugin.syncDatabaseContentsFromDriver(currentDb)`.
   - `JettraStorePlugin` solicita a `JettraClient.syncDatabaseFromPrimary(host, port, currentDb)` la instantánea más reciente (`TYPE_SYNC_DATA_REQ` → `<dbName>_meta.json`) desde el nodo `PRIMARY` (con respaldo en disco local si se comparte directorio de almacenamiento).
   - A partir de `JettraStoreDriver`, carga:
     - **Buckets reales:** `client.listBuckets(dbName)` y `client.getDocumentEngineNames(dbName)`.
     - **Esquemas (`DEFINED`/`NONE`):** `BucketMetadata.structureType()` y `BucketMetadata.attributes()`.
     - **Índices por Bucket:** `client.listIndexes(dbName)` filtrados por nombre de Bucket.
     - **Registros y `JettraId`:** `client.findAllBucketRecords(dbName, bucketName)`, mostrando el `JettraId` en la columna `ID`, resumen de campos, versión y fecha de mutación en la `DataTable`.
2. **Diagnóstico si `JettraStoreShell` muestra datos y `/explorer` no:**
   - Verifique en `/cluster` que el nodo `PRIMARY` esté **ACTIVE** y apuntando al mismo puerto gRPC (`50001`) usado por `JettraStoreExampleSE` y `JettraStoreShell`.
   - Revise el banner flash de `/explorer` (los errores de sincronización del driver ya no se silencian y se registran en consola con prefijo `[JettraStoreExplorer]`).
   - Reinicie `JettraStoreExplorer` si se recompiló `JettraStoreDriver` o `JettraStoreExplorer`.

---

## 9. Despliegue Optimizado en Docker y Docker Compose (RF-15: Bajo Consumo RAM/CPU y Monitoreo Raft)

### 1. Dockerfile Multi-Etapa de Bajo Consumo (`JettraStoreExplorer/Dockerfile`)
- Build multi-etapa sobre `bellsoft/liberica-runtime-container:jre-25-stream-musl` con usuario no root (`1000:1000`).
- Footprint acotado: `mem_limit: 512m`, `cpus: 1.0`, `JAVA_OPTS="-Xms96m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=128m"` y `JAVA_TOOL_OPTIONS` con `-XX:+UseContainerSupport -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+UseCompactObjectHeaders`.
- Volúmenes persistentes `/app/memory` y configuración del clúster `/jettra/config/cluster.config`.

### 2. Despliegue 1 Nodo y 3 Nodos Raft
```bash
# 1 Nodo (JettraStore + Shell + Explorer en http://localhost:8085)
docker compose -f docker-compose-single-node.yml up -d

# 3 Nodos Raft (JettraStore x3 + Shell + Explorer en http://localhost:8085)
docker compose up -d
```

### 3. Monitoreo en Vivo de Consenso Raft y Failover en `/cluster` y `/explorer`
- Abrir `http://localhost:8085/cluster` (credenciales por defecto `admin` / `admin123`): verifica en tiempo real **1 `PRIMARY` (`LEADER`)** y **2 `SECONDARY` (`FOLLOWER`)**.
- Al detener `jettra-node-01` (`docker stop jettra-node-01`), `/cluster` y `/map` detectan la desconexión de `node-01` y reflejan automáticamente el nuevo `PRIMARY` promovido, manteniendo el acceso de lectura/escritura en `/explorer`.
- Al reiniciar `jettra-node-01` (`docker start jettra-node-01`), el nodo se reincorpora al quórum como `ACTIVE` conservando su volumen de almacenamiento persistente.

### 4. Construcción y Publicación en Docker Hub
```bash
docker build -t <usuario>/jettrastoreexplorer:1.0 -t <usuario>/jettrastoreexplorer:latest ./JettraStoreExplorer
docker login
docker push <usuario>/jettrastoreexplorer:1.0
docker push <usuario>/jettrastoreexplorer:latest
```

---

## 15. Migrador de PostgreSQL a JettraStore y Generador de Proyecto Backend (`/migrator`) — RF-17

El gestor web **JettraStore Explorer** integra en la ruta **`/migrator`** una herramienta visual completa para migrar bases de datos relacionales **PostgreSQL** hacia el ecosistema multimodelo **JettraStore**, preservando la fidelidad absoluta de tipos críticos y generando automáticamente un proyecto backend moderno en **Java 25+** empaquetado en formato `.zip`.

### 1. Parámetros de Conexión y Opciones de Migración en `/migrator`
En la vista **`/migrator`** (accesible desde el menú lateral en el grupo **🗄️ Catálogo y Motores** y en la barra de pestañas superiores):
- **Servidor Host PostgreSQL:** Dirección IP o hostname del servidor PostgreSQL (por defecto `127.0.0.1`).
- **Puerto:** Puerto TCP de PostgreSQL (por defecto `5432`).
- **Base de Datos Origen:** Nombre de la base de datos PostgreSQL a introspeccionar.
- **Esquema:** Esquema relacional a procesar (por defecto `public`).
- **Usuario y Contraseña:** Credenciales de autenticación en PostgreSQL.
- **Base de Datos Destino JettraStore:** Nombre de la base de datos a crear o poblar en el clúster JettraStore (por ejemplo `migrated_db`).
- **Motor Destino JettraStore:** Motor de destino para los Buckets (`DOCUMENT` o `JAVA_RECORD`).
- **Paquete Java Backend:** Paquete base para el código generado (por defecto `com.example.jettra.backend`).
- **Opciones de Red y Artefacto:**
  - `🔒 Conexión SSL`: Activa negociación TLS/SSL con el servidor PostgreSQL.
  - `📦 Generar Proyecto Backend (.zip)`: Empaqueta la arquitectura backend completa en un archivo descargable.

### 2. Flujo de Trabajo
1. **🔌 Conectar y Listar BDs (Descubrimiento Dinámico de Bases de Datos y Esquemas):**
   - El usuario introduce las credenciales (Host, Puerto, Usuario, Contraseña) y pulsa **"🔌 Conectar y Listar BDs"**.
   - JettraStore Explorer se conecta a la instancia de PostgreSQL, consulta el catálogo del servidor (`pg_database`, `information_schema.schemata`) y obtiene la lista completa de todas las bases de datos disponibles y los esquemas del servidor.
   - La interfaz actualiza reactivamente los selectores desplegables (**Base de Datos Origen** y **Esquema**) y despliega un panel visual con distintivos interactivos de las bases de datos detectadas.
   - El usuario **selecciona la base de datos y esquema que desea migrar** directamente desde el menú desplegable interactivo. Al seleccionarla, el campo de base de datos destino en JettraStore se ajusta automáticamente con el nombre seleccionado.
2. **🔍 Analizar Esquema (Introspección):**
   - Conecta a la base de datos y esquema seleccionados mediante el driver JDBC oficial (`org.postgresql:postgresql`) y consulta el catálogo relacional (`DatabaseMetaData`, `information_schema`).
   - Detecta tablas, claves primarias, columnas, restricciones de nulidad, secuencias e índices (`BTREE`, `NUMERIC`, `AUTO_INCREMENT`).
   - Muestra tarjetas resumen con métricas globales (tablas, columnas, índices, registros estimados) y tablas desglosadas con su equivalente a **Java Record** y **Bucket JettraStore**.
3. **🚀 Iniciar Migración Completa:**
   - Asegura la creación de la base de datos destino en JettraStore mediante `JettraClient.createDatabase(targetDb)`.
   - Crea cada **Bucket** con estructura definida (`JettraBucketSchema.StructureType.DEFINED`) y sus reglas de atributos (`AttributeRule`).
   - Replica los índices detectados llamando a `createBucketIndex(...)` o `createAutoIncrementIndex(...)`.
   - Lee datos por streaming (`ResultSet.TYPE_FORWARD_ONLY` con tamaño de lote configurable) y transfiere las filas conservando la integridad.
   - Si está habilitado, empaqueta el backend en memoria y muestra el botón `📦 Descargar Proyecto Backend (.zip)`.
4. **🧪 Simulación (Dry-Run):**
   - Ejecuta la introspección y genera el proyecto backend sin insertar registros ni mutar el almacenamiento de JettraStore.

### 3. Reglas de Mapeo y Fidelidad de Tipos Críticos
Para evitar cualquier pérdida de información o distorsión durante la migración:
- **Decimales de alta precisión (`NUMERIC`, `DECIMAL`):** Se extraen y manejan como **`java.math.BigDecimal`** usando representaciones de cadena exacta (`toPlainString()`). Queda estrictamente prohibida la conversión intermedia a `float` o `double` para evitar imprecisiones de coma flotante IEEE 754 en montos contables o financieros.
- **Fechas y Marcas de Tiempo (`TIMESTAMP`, `TIMESTAMPTZ`, `DATE`):**
  - `TIMESTAMPTZ` / `TIMESTAMP` se mapean a **`java.time.Instant`** o **`java.time.OffsetDateTime`**, serializados en formato estándar ISO-8601 con zona y microsegundos (`YYYY-MM-DDTHH:mm:ss.SSSSSSZ`).
  - `DATE` se mapea a **`java.time.LocalDate`** en formato `YYYY-MM-DD`.
  - `TIME` se mapea a **`java.time.LocalTime`** (`HH:mm:ss`).
- **Identificadores y Claves Primarias (`JettraId`):**
  - Si la fila no cuenta con un ID explícito, se asigna un **`JettraId`** canónico hexadecimal de 32 caracteres generado con el scope `database:bucket`.
- **Autoincrementables (`SERIAL`, `BIGSERIAL`, `IDENTITY`):** Se mapean a tipos `Long` y se les asocia un índice **`AUTO_INCREMENT`** en el Bucket.
- **Cadenas y Restricciones:** Se mapean a `String` y se generan anotaciones **`JettraRules`** (`@NotNull`, `@NotBlank`, `@Size(max = N)`).

### 4. Estructura del Proyecto Backend Descargable (`.zip`)
El archivo `.zip` generado (descargable desde `/api/migrator/download`) contiene un proyecto Maven completo y ejecutable:
```text
jettra-backend/
├── pom.xml                                   # Configurado para Java 25+, JettraServer, JettraStoreDriver, JettraRules, JettraJWT
├── README.md                                 # Guía de compilación, ejecución y catálogo de endpoints
├── src/main/resources/
│   └── application.properties                # Configuración de puerto (8080), host JettraStore y secreto JWT
└── src/main/java/com/example/jettra/backend/
    ├── App.java                              # Servidor JettraServer principal con Virtual Threads (Loom)
    ├── model/
    │   └── {Tabla}.java                      # Java 25 Records inmutables con anotaciones JettraRules y JettraId
    ├── repository/
    │   ├── JettraRepository.java             # Interfaz genérica CRUD
    │   └── {Tabla}Repository.java            # Repositorios con JettraClient y validación JettraRulesEngine
    ├── security/
    │   └── SecurityConfig.java               # Filtro de autenticación y validación de tokens Bearer con JettraJWT
    └── endpoint/
        └── {Tabla}Endpoint.java              # Controladores REST (/api/{tabla}, GET, POST, PUT, DELETE)
```

---

## 16. Validación Previa de Disponibilidad de Puerto al Iniciar (`RF-27`)

Antes de inicializar `JettraStorePlugin`, las rutas **JettraFlux** o el servidor HTTP **JettraServer**, `App` verifica de forma temprana si el puerto TCP configurado en la interfaz de red seleccionada se encuentra disponible.

### 1. Precedencia de Configuración del Puerto y Host
El punto de arranque (`App.resolveStartupConfig(args)`) resuelve el host y el puerto en el siguiente orden de prioridad:
1. **Argumentos de línea de comandos:** `--server.port=<puerto>`, `--port=<puerto>`, `-port=<puerto>`, `-p <puerto>`, `--port <puerto>` y `--server.host=<host>`.
2. **Propiedades del sistema JVM y variables de entorno:** `-Dserver.port=...` / `-Dport=...` / `PORT` y `-Dserver.host=...` / `-Dhost=...` / `SERVER_HOST`.
3. **Archivo `microprofile-config.properties`:** `server.port` (por defecto `8085`) y `server.host` (por defecto `127.0.0.1`).

### 2. Mecanismo de Comprobación Segura (`ServerSocket` + `SO_REUSEADDR`)
* `App.isPortAvailable(host, port)` abre un `ServerSocket` de sonda dentro de un bloque `try-with-resources`, habilita `setReuseAddress(true)` para evitar falsos bloqueos tras reinicios rápidos y ejecuta `bind(...)` sobre la interfaz configurada (`0.0.0.0` / `*` o IP específica).
* El socket de prueba se cierra inmediatamente al salir del bloque `try-with-resources` antes de ceder el puerto a `JettraServer`.

### 3. Manejo Controlado de Conflictos de Puerto
Si el puerto ya está ocupado por otra instancia de `JettraStoreExplorer` u otro proceso del sistema:
* **En arranque CLI (`App.main`):** La aplicación aborta el inicio de manera controlada con código de salida `1` (`System.exit(1)`), evitando excepciones genéricas (`BindException`) y mostrando en consola:
  ```text
  ⚠️ Error: El puerto 8085 ya está en uso en la interfaz '127.0.0.1'. Detenga el proceso que lo ocupa o configure un puerto diferente para iniciar JettraStoreExplorer.
  ```
* **En arranque programático (`App.startApplication`):** Se lanza `App.PortAlreadyInUseException` antes de crear el servidor web o modificar sesiones activas.
* **Solución rápida:** Detener la instancia previa que ocupa el puerto o iniciar indicando un puerto libre:
  ```bash
  java -jar target/JettraStoreExplorer-1.0.0-SNAPSHOT.jar --server.port=8090
  ```


