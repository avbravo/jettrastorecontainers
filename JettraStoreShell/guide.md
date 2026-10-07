# JettraStoreShell: Manual Integral de la Consola Interactiva y Guía Operativa

**Ecosistema de Base de Datos Multimodelo Distribuido en Java 25+**  
*Documentación Oficial y Manual de Referencia de Comandos CLI*

---

## 📑 Tabla de Contenidos
1. [Arquitectura y Visión General](#1-arquitectura-y-visión-general)
2. [Configuración de Almacenamiento en Disco y Descubrimiento (`jettra.storage.path`)](#2-configuración-de-almacenamiento-en-disco-y-descubrimiento-jettrastoragepath)
3. [Ciclo de Inicio, Conexión y Autenticación](#3-ciclo-de-inicio-conexión-y-autenticación)
4. [Gestor de Perfiles de Conexión](#4-gestor-de-perfiles-de-conexión)
5. [Telemetría de Recursos y Monitoreo del Clúster](#5-telemetría-de-recursos-y-monitoreo-del-clúster)
6. [Administración de Nodos del Clúster Raft](#6-administración-de-nodos-del-clúster-raft)
7. [Administración de Bases de Datos y Muestras (`SHOW DBS` y `SHOW SAMPLES`)](#7-administración-de-bases-de-datos-y-muestras-show-dbs-y-show-samples)
8. [Lenguajes de Consulta: JettraQL y JettraSQL](#8-lenguajes-de-consulta-jettraql-y-jettrasql)
   - [8.1 JettraQL](#81-jettraql-motor-declarativo-multimodelo)
   - [8.2 JettraSQL](#82-jettrasql-dialecto-relacional-extendido)
   - [8.3 Prevención Anti-OOM: Sentinel y Streaming Progresivo](#83-prevención-anti-oom-sentinel-y-streaming-progresivo)
9. [Registros Referenciados Multimodelo (`JettraRef`) y Lazy Loading](#9-registros-referenciados-multimodelo-jettraref-y-lazy-loading)
10. [Administración Integral de Índices](#10-administración-integral-de-índices)
11. [Administración de Usuarios y Roles de Base de Datos (RBAC Granular)](#11-administración-de-usuarios-y-roles-de-base-de-datos-rbac-granular)
12. [Exploración de Buckets/Units, Inspección de Registros y Conteo](#12-exploración-de-bucketsunits-inspección-de-registros-y-conteo-multimodelo)
13. [Operaciones CRUD sobre Registros](#13-operaciones-crud-sobre-registros)
14. [Motores Multimodelo Especializados](#14-motores-multimodelo-especializados)
15. [Respaldos Físicos en Caliente y Restauración (Backup & Restore)](#15-respaldos-físicos-en-caliente-y-restauración-backup--restore)
16. [Tutorial Práctico Extremo a Extremo (Paso a Paso)](#16-tutorial-práctico-extremo-a-extremo-paso-a-paso)
17. [Tabla Rápida de Comandos y Ayuda (`help`)](#17-tabla-rápida-de-comandos-y-ayuda-help)

---

## 1. Arquitectura y Visión General

`JettraStoreShell` es la consola interactiva oficial de línea de comandos (CLI) para administrar, monitorear, consultar y operar clústeres `JettraStore`. Diseñada íntegramente sobre **Java 25 LTS**:

- **Project Panama FFM (Foreign Function & Memory API)**: Almacenamiento directo off-heap de velocidad nativa C/C++ sin presión sobre el recolector de basura.
- **Java 25 Generational ZGC & Compact Object Headers**: Pausas de recolección de memoria garantizadas menores a 1 ms y ahorro del 22% en encabezados de objetos con `--XX:+UseCompactObjectHeaders`.
- **Java Virtual Threads (Project Loom)**: Capacidad de ejecutar miles de comandos y consultas concurrentes de forma ligera.
- **Seguridad Criptográfica JettraJWT**: Control de acceso granular con validación criptográfica y protección inmutable del superusuario `admin`.
- **Soporte Bilingüe de Consultas**: Ejecución simultánea de **JettraQL** (lenguaje declarativo multimodelo orientado a grafos, vectores y documentos) y **JettraSQL** (lenguaje relacional ANSI SQL extendido).

---

## 2. Configuración de Almacenamiento en Disco y Descubrimiento (`jettra.storage.path`)

### 2.1 Archivo `database.properties` y Rutas Físicas
La ubicación de los datos persistentes se define en el archivo `database.properties`:
```properties
# Modo Multinodo y Algoritmo de Consenso Distribuido (on: consenso activo, off: servidor único local)
cluster.multinode.active = on

# Ubicación física del directorio de almacenamiento en disco
jettra.storage.path = /jettra/data

# MemTable y Off-Heap
jettra.storage.memtable.size.mb = 128
jettra.storage.ram.global.limit.mb = 2048
jettra.storage.offheap.direct = true
jettra.storage.file.extension = .jettra
```

### 2.2 Diagnóstico y Solución de Carga en `SHOW DBS`
**¿Por qué previamente no se cargaban las bases de datos instaladas en `/jettra/data`?**
1. **Inspección en Memoria vs. Disco**: El cliente originalmente solo consultaba el mapa en memoria de instancias abiertas (`databases.keySet()`). Si el shell se iniciaba en frío, la memoria estaba limpia y no se escaneaba el disco físico.
2. **Permisos de Escritura del Sistema Operativo**: En entornos Linux no rooteados, `/jettra/data` puede requerir permisos de superusuario (`sudo`). Cuando `JettraStoreConfig` detectaba que `/jettra` no existía o no era escribible por el usuario actual, conmutaba de forma preventiva al almacenamiento local writable (`./data/jettra`), pero el listador no consultaba ambos directorios.

**Solución Implementada**:
- `JettraStoreConfig` almacena tanto la ruta configurada explícita (`getConfiguredStoragePath()`, ej: `/jettra/data`) como el directorio activo validado (`getStoragePath()`).
- `JettraClient.listDatabases()` implementa un escáner polifacético que inspecciona:
  1. La ruta activa `cfg.getStoragePath()`
  2. La ruta configurada en `database.properties` `cfg.getConfiguredStoragePath()` (`/jettra/data`)
  3. Los directorios locales `./data/jettra`, `data/jettra`, `../data/jettra`
- Todo archivo `<nombre>_sstable.jettra`, `<nombre>.jettra` o subcarpeta encontrada es reconocido automáticamente como una base de datos física preexistente y precargado en el motor, asegurando que `SHOW DBS` la muestre de inmediato.

---

## 3. Ciclo de Inicio, Conexión y Autenticación

Al iniciar el shell interactivo, el sistema solicita de forma guiada el host/puerto del clúster y las credenciales de acceso:

```text
================================================================================
               JETTRASTORE INTERACTIVE DISTRIBUTED SHELL (JAVA 25+)             
================================================================================
>> JettraStore Host [127.0.0.1]: 127.0.0.1
>> JettraStore Port [9091]: 9091
>> Username [admin]: admin
>> Password [hidden]: ********
[AUTH OK] Autenticado exitosamente como 'admin' (127.0.0.1:9091)
jettra-shell [admin@127.0.0.1:9091/default_db]>
```

### Comandos de Sesión:
| Comando | Descripción | Ejemplo |
| :--- | :--- | :--- |
| `connect <host> <puerto>` | Configura el endpoint del servidor JettraStore. | `connect 127.0.0.1 9091` |
| `connect <nombre-perfil>` | Conecta usando un perfil previamente guardado. | `connect local_master` |
| `login <user> <pass>` | Inicia sesión y genera token JettraJWT. | `login admin admin-jettra` |
| `logout` | Cierra la sesión activa y bloquea operaciones de base de datos. | `logout` |

---

## 4. Gestor de Perfiles de Conexión

Permite guardar alias para alternar rápidamente entre nodos del clúster:

```sql
-- Guardar la conexión actual con un alias
save connection local_master
save connection prod_replica 192.168.1.102 9091 admin

-- Listar conexiones guardadas
list connections

-- Conectar mediante perfil guardado
connect prod_replica
login admin admin-jettra

-- Eliminar un perfil guardado
remove connection prod_replica
```

---

## 5. Telemetría de Recursos y Monitoreo del Clúster

El comando `status` presenta una radiografía en tiempo real del motor:

```text
jettra-shell [admin@127.0.0.1:9091/default_db]> status

==============================================================================================
                      JETTRASTORE RESOURCE MONITOR & TELEMETRY (JAVA 25+)
==============================================================================================
1. RAM (MEMORIA):
   - Panama FFM Off-Heap Direct: Habilitado (Arena Compartida Cero Copia)
   - Heap JVM (ZGC Generational): Ocupada 18 MB / Total 64 MB (Máx JVM: 4096 MB)
   - MemTable Tamaño Asignado:   128 MB
   - Dynamic Ring Saturation:    Umbral 85% (Descarga automática a nodos secundarios)
   - Compact Object Headers:     Activo (--XX:+UseCompactObjectHeaders)

2. PROCESADOR (CPU):
   - Núcleos Lógicos del Host:   12 Cores
   - Virtual Threads (Loom):     Activos (I/O Concurrente No Bloqueante en red gRPC/REST)
   - Hilos de Compaction LSM:    En segundo plano (Prioridad baja)

3. DISCO (ALMACENAMIENTO):
   - Ruta Física Configurada:    /jettra/data
   - Directorio Activo de Datos: ./data/jettra
   - Tamaño Ocupado por SSTables: 48.20 KB (49356 bytes)
   - Archivos de Datos (.jettra): 5 archivo(s)
   - Formato de Almacenamiento:  Estructura LSM (.jettra) con Bloom Filters y Sparse Indexes
==============================================================================================
```

---

## 6. Administración de Nodos del Clúster Raft

JettraStore opera sobre una topología distribuida basada en consenso Raft y un motor de anillo dinámico (*Dynamic Ring Engine*).

### Comandos de Nodos:
| Comando | Descripción | Ejemplo |
| :--- | :--- | :--- |
| `show nodes` / `list nodes` | Muestra la topología completa del clúster Raft. | `show nodes` |
| `add node <id> <ip> <port> [ROLE]`| Registra un nuevo nodo en el clúster. | `add node node-04 192.168.1.104 9091 SECONDARY` |
| `stop node <id>` | Pausa el nodo réplica (detiene la descarga de memoria).| `stop node node-02` |
| `start node <id>` | Reactiva el nodo para recibir transferencias del anillo.| `start node node-02` |
| `remove node <id>` | Remueve un nodo secundario del clúster. | `remove node node-04` |

### Visualización de la Topología:
```text
jettra-shell [admin@127.0.0.1:9091/default_db]> show nodes

==============================================================================================
                          JETTRASTORE RAFT CLUSTER TOPOLOGY                                   
==============================================================================================
+----------+----------------------+-------+-----------+------------+----------+--------------+
| Nodo ID  | Dirección IP         | Puerto| Rol       | Estado Raft| Estado   | Offload Bytes|
+----------+----------------------+-------+-----------+------------+----------+--------------+
| node-01  | 127.0.0.1            | 9091  | PRIMARY   | LEADER     | RUNNING  | 0            |
| node-02  | 192.168.1.102        | 9091  | SECONDARY | FOLLOWER   | RUNNING  | 0            |
| node-03  | 192.168.1.103        | 9091  | SECONDARY | FOLLOWER   | RUNNING  | 0            |
+----------+----------------------+-------+-----------+------------+----------+--------------+
Total: 3 nodo(s) registrados en el anillo dinámico. Quórum: Activo.
```

---

## 7. Administración de Bases de Datos y Muestras (`SHOW DBS` y `SHOW SAMPLES`)

### 7.1 Listar Todas las Bases de Datos (`SHOW DBS`)
Detecta bases de datos en memoria y en almacenamiento físico, clasificándolas por tipo:

```text
jettra-shell [admin@127.0.0.1:9091/default_db]> show dbs

+------------------------------------+----------+-------------+----------------+
| Base de Datos                      | Tipo     | Colecciones | Estado         |
+------------------------------------+----------+-------------+----------------+
| default_db                         | SYSTEM   | 0           | * ACTIVA       |
| sample_ai_graph_db                 | SAMPLE   | 3           | DISPONIBLE     |
| sample_ecommerce_db                | SAMPLE   | 4           | DISPONIBLE     |
| sample_enterprise_db               | SAMPLE   | 6           | DISPONIBLE     |
| sample_financial_db                | SAMPLE   | 2           | DISPONIBLE     |
| sample_iot_telemetry_db            | SAMPLE   | 4           | DISPONIBLE     |
+------------------------------------+----------+-------------+----------------+
Total: 6 base(s) de datos detectadas. Base activa: 'default_db'
Ruta física en database.properties: '/jettra/data' | Directorio de lectura/escritura: './data/jettra'
```

### 7.2 Inspección Específica de Muestras (`SHOW SAMPLES`)
```text
jettra-shell [admin@127.0.0.1:9091/default_db]> show samples

==============================================================================================
                        BASES DE DATOS DE EJEMPLO (JettraStore Samples)                      
==============================================================================================
+-------------------------+--------------------+---------------------------------------------+
| Base de Datos           | Estado en Disco    | Motores & Propósito                         |
+-------------------------+--------------------+---------------------------------------------+
| sample_enterprise_db    | INSTALADA (Lista)  | Documentos, Vectores 3D, Grafos de Catálogo |
| sample_ecommerce_db     | INSTALADA (Lista)  | Clientes, Órdenes, Analítica Columnar       |
| sample_ai_graph_db      | INSTALADA (Lista)  | Red de Grafos de Conocimiento, Embeddings   |
| sample_iot_telemetry_db | INSTALADA (Lista)  | Sensores Temperatura/Vibración, Smart Devs  |
| sample_financial_db     | INSTALADA (Lista)  | Transacciones de Cuentas, Ledger y Series   |
| example_factura_db      | INSTALADA (Lista)  | Facturación 3M Objetos (1M Fac, 1M Det, etc)|
| samples_hostipal_db     | INSTALADA (Lista)  | Salud 2M Objetos (Pacientes, CIE10, Fármacos)|
| samples_ambiental_db    | INSTALADA (Lista)  | Medio Ambiente 3M Objetos (Estaciones, AQI) |
+-------------------------+--------------------+---------------------------------------------+
Para instalar o re-inicializar las muestras estándar, ejecute: INSTALL SAMPLES
Para cargar las muestras masivas, ejecute:
  LOAD SAMPLE example_factura_db   (3M objetos)
  LOAD SAMPLE samples_hostipal_db  (2M objetos)
  LOAD SAMPLE samples_ambiental_db (3M objetos)
```

---

## 8. Lenguajes de Consulta: JettraQL y JettraSQL

JettraStore Shell cuenta con un procesador dual de consultas que permite alternar entre la semántica declarativa multimodelo y el lenguaje relacional tradicional.

### 8.1 JettraQL (Motor Declarativo Multimodelo)
Orientado a grafos, similitud vectorial de alta dimensión y documentos jerárquicos:

```sql
-- 1. Filtrado de documentos
JQL FROM products WHERE category = Hardware;

-- 2. Pattern matching sobre grafos dirigidos
JQL MATCH (prod_01)-[BELONGS_TO]->(cat_hardware) IN catalog_graph;

-- 3. Búsqueda semántica por similitud coseno
JQL VECTOR SIMILARITY product_embeddings TO [0.15, -0.42, 0.88] LIMIT 5;

-- 4. Recuperación con resolución automática de referencias
JQL FETCH products prod_01 RESOLVE REFS;
```

**Ejemplo de Salida JettraQL**:
```text
=== JETTRAQL [FROM] (1 ms) ===
Resumen: Recuperados 1 documento(s) de 'products' (Filtro: category=Hardware)
Columnas: [_id, price, name, _ref_vector, category, _ref_category]
  [01] [prod_01, 4500.0, Quantum Neural Accelerator, vector::product_embeddings#emb_01, Hardware, graph::catalog_graph#cat_hardware]
Coincidencias encontradas: 1
```

### 8.2 JettraSQL (Motor Relacional ANSI SQL-92 Extendido)
Orientado a consultas tabulares con soporte de proyecciones, inserciones y eliminaciones:

```sql
-- Consultar registros con tabla formateada
SQL SELECT * FROM employees WHERE dept = 'R&D';

-- Inserción directa SQL
SQL INSERT INTO employees VALUES ('emp_100', '{"name":"Grace Hopper","dept":"R&D"}');

-- Actualización SQL
SQL UPDATE employees SET salary = 195000 WHERE _id = 'emp_100';

-- Eliminación SQL
SQL DELETE FROM employees WHERE _id = 'emp_100';
```

**Ejemplo de Salida JettraSQL**:
```text
=== JETTRASQL RESULTADO (2 ms) ===
Mensaje: Selected 1 record(s) from 'employees'
+--------------+-------------------+--------------+
| _id          | name              | dept         |
+--------------+-------------------+--------------+
| emp_100      | Grace Hopper      | R&D          |
+--------------+-------------------+--------------+
Total: 1 fila(s) seleccionadas / afectadas.
```

---

### 8.3 Prevención Anti-OOM: Sentinel y Streaming Progresivo

Cuando se ejecutan consultas masivas en el Shell (como `SELECT * FROM clientes` con cientos de miles de filas o comandos de inspección sin acotamiento), el **Centinela `JettraPolice`** interviene de forma automática y transparente a través del driver:

1. **Aviso Inmediato en Terminal:**
   Al activarse la protección de memoria, el Shell imprime de forma destacada el recuadro informativo de Sentinel:
   ```text
   ╔══════════════════════════════════════════════════════════════════════════════╗
   ║ 🛡️  [JETTRAPOLICE SENTINEL - PROTECCIÓN PREVENTIVA DE MEMORIA HEAP]         ║
   ╠══════════════════════════════════════════════════════════════════════════════╣
   ║  Operación: SQL_SELECT      | Colección: clientes                            ║
   ║  Total Estimado: 200000     | Lote Seguro (Batch): 100                       ║
   ║  Saturación Heap:  41.3%    | RAM Disponible: 2150 MB                        ║
   ║  Estrategia: Streaming por chunks activado para prevenir OutOfMemory.        ║
   ╚══════════════════════════════════════════════════════════════════════════════╝
   ```

2. **Renderizado Progresivo por Chunks:**
   En lugar de intentar recibir e imprimir 200,000 registros en un solo volcado (lo cual saturaría el búfer del terminal y agotaría la RAM de la máquina cliente), los registros se reciben y formatean en bloques de tamaño seguro (`Chunk #1`, `Chunk #2`, ...), liberando la memoria del lote anterior para el Garbage Collector.

3. **Inspección Segura con `SHOW RECORDS`:**
   El comando `SHOW RECORDS <bucket> [LIMIT <n>]` consume internamente el stream de chunks seguros (`streamFindAll`):
   ```text
   JettraStore> SHOW RECORDS clientes LIMIT 200;
   🛡️  [JETTRAPOLICE SENTINEL: STREAMING POR CHUNKS ACTIVADO]
      Partición defensiva en lotes de 100 registros para evitar OOM.
   --- [Chunk #1: 100 registro(s) recibidos progresivamente] ---
     [01] _id: doc_0           -> {nombre=Empresa A, saldo=1200.50}
     ...
   --- [Chunk #2: 100 registro(s) recibidos progresivamente] ---
     [101] _id: doc_100        -> {nombre=Empresa B, saldo=9450.00}
   ```

---

## 9. Registros Referenciados Multimodelo (`JettraRef`) y Lazy Loading

Las referencias cruzadas (`JettraRef`) conectan registros entre diferentes motores sin necesidad de desnormalización o copias redundantes.

### 9.1 Formato de Punteros Multimodelo:
El formato canónico de un puntero es:
`<motor>::<coleccion_o_tabla>#<id_objetivo>`

| Tipo | Ejemplo | Descripción |
| :--- | :--- | :--- |
| `document` | `document::customers#cust_101` | Puntero a un documento JSON en otra colección. |
| `vector` | `vector::product_embeddings#emb_01` | Puntero a un embedding float[] en el motor vectorial. |
| `graph` | `graph::catalog_graph#cat_hardware` | Puntero a un vértice y sus aristas adyacentes. |
| `timeseries`| `timeseries::telemetry#1759160000` | Puntero a una métrica temporal. |
| `kv` | `kv::inventory_cache#laptop_mac_m3` | Puntero a un valor en memoria ultra-rápida. |

### 9.2 Modos de Carga: Lazy (Perezosa) vs Eager (Inmediata)
```sql
-- Activar carga bajo demanda (Proxy Lazy)
lazy reference on

-- Activar carga inmediata en memoria
lazy reference off

-- Consultar estado actual
lazy reference status
```

### 9.3 Comandos para Referencias Cruzadas:
```sql
-- 1. Vincular un puntero cruzado en un registro
INSERT REF orders ord_9901 KEY _ref_customer TARGET document::customers#cust_101;

-- 2. Resolver una referencia manualmente
RESOLVE REF document::customers#cust_101;

-- 3. Ver todas las referencias de un registro
SHOW REFS orders ord_9901;

-- 4. Obtener el registro y ver su resolución integrada
GET orders ord_9901;
```

**Ejemplo de Salida de `GET` con Lazy Reference**:
```text
--- REGISTRO [ord_9901] EN 'orders' ---
  _id             : ord_9901
  total           : 899.5
  status          : PAID
  _ref_customer   : document::customers#cust_101
    ↳ [JettraRef Resolución (Lazy Proxy On-Demand)]: Documento {name=Elena Rostova, tier=VIP_PLATINUM, country=ES}
  _ref_product    : document::products#prod_01
    ↳ [JettraRef Resolución (Lazy Proxy On-Demand)]: Documento {name=Quantum Neural Accelerator, price=4500.0}
```

---

## 10. Administración Integral de Índices

Permite crear índices secundarios dispersos (*Sparse Indexes*), árboles B-Tree e índices Hashing sobre campos de documentos.

### Comandos de Índices:
| Comando | Descripción | Ejemplo |
| :--- | :--- | :--- |
| `CREATE INDEX <nombre> ON <col> (<campo>) [TYPE BTREE\|HASH\|SPARSE] [UNIQUE]` | Crea un índice sobre un campo. | `CREATE INDEX idx_tier ON customers (tier) TYPE HASH` |
| `SHOW INDEXES [ON <col>]` | Lista todos los índices y sus métricas. | `SHOW INDEXES` |
| `ALTER INDEX <nombre> REBUILD` / `REINDEX <nombre>` | Reconstruye el índice reescaneando los documentos. | `ALTER INDEX idx_tier REBUILD` |
| `DROP INDEX <nombre>` | Elimina el índice indicado. | `DROP INDEX idx_tier` |

**Ejemplo de Tabla de Índices**:
```text
jettra-shell [admin@127.0.0.1:9091/sample_enterprise_db]> show indexes

=== ÍNDICES DE BASE DE DATOS: 'sample_enterprise_db' ===
+----------------------+----------------------+----------------------+--------+--------+----------+
| Nombre de Índice     | Colección            | Campo Indexado       | Tipo   | Único  | Entradas |
+----------------------+----------------------+----------------------+--------+--------+----------+
| idx_emp_name         | employees            | name                 | BTREE  | NO     | 1        |
| idx_prod_cat         | products             | category             | HASH   | NO     | 1        |
+----------------------+----------------------+----------------------+--------+--------+----------+
```

---

## 11. Administración de Usuarios y Roles de Base de Datos (RBAC Granular)

JettraStore incorpora un sistema de seguridad granular basado en roles globales y privilegios específicos por base de datos.

### 11.1 Jerarquía de Roles
- **Roles Globales**:
  - `SUPER_ADMIN`: Control absoluto e inmutable sobre el clúster (reservado para `admin`).
  - `DB_ADMIN`: Administración general de bases de datos y creación de usuarios.
  - `OPERATOR`: Monitoreo y control de nodos del clúster.
  - `DEVELOPER`: Rol operativo estándar para desarrollo.
- **Roles por Base de Datos**:
  - `DB_OWNER`: Control total sobre una base de datos específica (CRUD, DDL, Índices, Drop).
  - `READ_WRITE`: Lectura, inserción, actualización y eliminación en las colecciones de esa base de datos.
  - `READ_ONLY`: Consultas de solo lectura (`SELECT`, `GET`, `FIND`, `JQL`).

### 11.2 Comandos de Seguridad:
```sql
-- 1. Listar usuarios y sus privilegios
SHOW USERS;

-- 2. Crear un nuevo usuario
CREATE USER dev_user PASSWORD secret-pass ROLE DEVELOPER;

-- 3. Otorgar permisos sobre una base de datos específica
GRANT READ_WRITE ON sample_ecommerce_db TO dev_user;
GRANT READ_ONLY ON sample_financial_db TO dev_user;

-- 4. Ver privilegios asignados al usuario
SHOW GRANTS FOR dev_user;

-- 5. Revocar permisos de base de datos
REVOKE sample_financial_db FROM dev_user;

-- 6. Modificar credenciales o rol global
ALTER USER dev_user PASSWORD new-strong-pass;
ALTER USER dev_user ROLE DB_ADMIN;

-- 7. Eliminar usuario
DROP USER dev_user;
```

**Ejemplo de Listado de Usuarios (`SHOW USERS`)**:
```text
==============================================================================================
                           USUARIOS Y ROLES DE BASE DE DATOS (RBAC)                           
==============================================================================================
+-----------------+-----------------+------------------------------------------+-------------+
| Usuario         | Rol Global      | Roles de Base de Datos                   | Inmutable   |
+-----------------+-----------------+------------------------------------------+-------------+
| admin           | SUPER_ADMIN     | *:DB_OWNER                               | SI (Protegido)
| dev_user        | DEVELOPER       | sample_ecommerce_db:READ_WRITE           | NO          |
+-----------------+-----------------+------------------------------------------+-------------+
```

---

## 12. Exploración de Buckets/Units, Inspección de Registros y Conteo Multimodelo

En la arquitectura multimodelo de `JettraStore`, cada motor organiza sus datos en unidades lógicas especializadas (**Buckets** o **Units**):
- **DOCUMENT**: Colecciones de documentos JSON estructurados/semi-estructurados (`Collection`).
- **VECTOR**: Índices vectoriales de embeddings densos con métricas de coseno (`Vector Index [dim]`).
- **GRAPH**: Redes de grafos de propiedades, vértices y aristas dirigidas (`Property Graph`).
- **TIMESERIES**: Métricas y series temporales ordenadas cronológicamente (`Metric Series`).
- **KEYVALUE**: Tablas clave-valor en memoria de acceso sub-milisegundo (`KV Store`).
- **GEOSPATIAL**: Capas espaciales con indexación R-Tree geográfica (`Spatial Layer`).
- **COLUMNAR**: Familias de columnas vectorizadas para analítica OLAP (`Column Family`).

---

### 12.1 Comando `SHOW BUCKETS` o `SHOW UNIT`
Muestra una tabla con todas las unidades de almacenamiento activas en la base de datos seleccionada, detallando el motor subyacente, tipo de unidad, nombre, cantidad de registros y estado en memoria/disco.

#### Sintaxis:
```sql
SHOW BUCKETS
SHOW UNIT
SHOW UNITS
SHOW BUCKETS <DOCUMENT|VECTOR|GRAPH|TIMESERIES|KEYVALUE|COLUMNAR|GEOSPATIAL>
```

#### Ejemplo de Salida:
```text
jettra-shell [admin@127.0.0.1:9091/sample_enterprise_db]> SHOW BUCKETS
==============================================================================================
                BUCKETS / UNITS EN BASE DE DATOS: 'sample_enterprise_db'                                        
==============================================================================================
+-------------+----------------------+--------------------+-----------+----------------------+
| Motor       | Tipo de Unidad       | Nombre de Unidad   | Registros | Estado               |
+-------------+----------------------+--------------------+-----------+----------------------+
| DOCUMENT    | Collection           | departments        | 1         | ACTIVE (In-Memory)   |
| DOCUMENT    | Collection           | employees          | 1         | ACTIVE (In-Memory)   |
| DOCUMENT    | Collection           | products           | 1         | ACTIVE (In-Memory)   |
| VECTOR      | Vector Index [3d]    | product_embeddings | 1         | INDEXED (HNSW)       |
| VECTOR      | Vector Index [3d]    | employee_biometrics| 1         | INDEXED (HNSW)       |
| GRAPH       | Property Graph       | catalog_graph      | 2         | TOPOLOGY (In-Memory) |
| TIMESERIES  | Metric Series        | telemetry          | 1         | APPEND-ONLY (Delta)  |
| KEYVALUE    | KV Store             | inventory_cache    | 1         | HASH-MAP (Persistent)|
+-------------+----------------------+--------------------+-----------+----------------------+
Total: 8 bucket(s)/unit(s) registrados en la base de datos 'sample_enterprise_db'.
```

#### Filtrado por Motor Específico:
```text
jettra-shell [admin@127.0.0.1:9091/sample_enterprise_db]> SHOW BUCKETS DOCUMENT
==============================================================================================
                BUCKETS / UNITS EN BASE DE DATOS: 'sample_enterprise_db'                                        
==============================================================================================
+-------------+----------------------+--------------------+-----------+----------------------+
| Motor       | Tipo de Unidad       | Nombre de Unidad   | Registros | Estado               |
+-------------+----------------------+--------------------+-----------+----------------------+
| DOCUMENT    | Collection           | departments        | 1         | ACTIVE (In-Memory)   |
| DOCUMENT    | Collection           | employees          | 1         | ACTIVE (In-Memory)   |
| DOCUMENT    | Collection           | products           | 1         | ACTIVE (In-Memory)   |
+-------------+----------------------+--------------------+-----------+----------------------+
Total: 3 bucket(s)/unit(s) registrados en la base de datos 'sample_enterprise_db'.
```

---

### 12.2 Comando `SHOW RECORDS`
Inspecciona visualmente el contenido de un bucket o unidad de almacenamiento en cualquiera de los 7 motores multimodelo. Permite limitar la cantidad de registros devueltos mediante la cláusula `LIMIT <n>` y resuelve automáticamente referencias `JettraRef` si `showReferences` o `lazy reference` están configuradas.

#### Sintaxis:
```sql
SHOW RECORDS <nombre_bucket> [LIMIT n]
SHOW RECORDS FROM <nombre_bucket> [LIMIT n]
```

#### Ejemplos por Motor:

**1. Bucket de Documentos (`DOCUMENT`):**
```text
jettra-shell [admin@127.0.0.1:9091/sample_enterprise_db]> SHOW RECORDS employees LIMIT 5
=== REGISTROS DE DOCUMENT BUCKET 'employees' (Mostrando 1 de 1) ===
  [01] _id: emp_01          -> {_ref_equipment=kv::inventory_cache#laptop_mac_m3, name=Ada Lovelace, _ref_vector=vector::employee_biometrics#bio_01, _ref_department=document::departments#dep_rd, _id=emp_01, salary=185000.0, title=Lead Architect}
       ↳ Ref [_ref_equipment]: KV Valor: MacBook Pro M3 Max 64GB
       ↳ Ref [_ref_vector]: Vector [0.92, 0.11, -0.05]
       ↳ Ref [_ref_department]: Documento {name=Research & Advanced Computing, _id=dep_rd, floor=12, budget=1.5E7}
```

**2. Bucket de Vectores (`VECTOR`):**
```text
jettra-shell [admin@127.0.0.1:9091/sample_ai_graph_db]> SHOW RECORDS concept_embeddings LIMIT 3
=== REGISTROS DE VECTOR BUCKET 'concept_embeddings' (Dim: 3 | Mostrando 1 de 1) ===
  [01] Vector ID: vec_neural_net  -> [0.88, 0.14, -0.42]
```

**3. Bucket de Grafos (`GRAPH`):**
```text
jettra-shell [admin@127.0.0.1:9091/sample_ai_graph_db]> SHOW RECORDS knowledge_network LIMIT 5
=== REGISTROS DE GRAPH BUCKET 'knowledge_network' (3 vértices) ===
  [01] Vértice: node_dl         (Aristas salientes: 1)
       ↳ (node_dl)-[SUBFIELD_OF, props={weight=0.95}]->(node_ai)
  [02] Vértice: node_ai         (Aristas salientes: 0)
  [03] Vértice: node_nlp        (Aristas salientes: 1)
       ↳ (node_nlp)-[LEVERAGES, props={weight=0.9}]->(node_dl)
```

**4. Bucket de Series Temporales (`TIMESERIES`):**
```text
jettra-shell [admin@127.0.0.1:9091/sample_enterprise_db]> SHOW RECORDS telemetry LIMIT 3
=== REGISTROS DE TIMESERIES BUCKET 'telemetry' (Mostrando 1 de 1) ===
  [01] Timestamp: 1759160500      -> Valor: 14.8000
```

**5. Bucket Clave-Valor (`KEYVALUE`):**
```text
jettra-shell [admin@127.0.0.1:9091/sample_enterprise_db]> SHOW RECORDS inventory_cache LIMIT 5
=== REGISTROS DE KEYVALUE BUCKET 'inventory_cache' (Mostrando 1 de 1) ===
  [01] Clave: laptop_mac_m3        -> Valor: MacBook Pro M3 Max 64GB
```

---

### 12.3 Comando `COUNT`
Proporciona el conteo de elementos almacenados en un bucket/unit específico o un censo global multimodelo de toda la base de datos activa.

#### Sintaxis:
```sql
COUNT <nombre_bucket>
COUNT FROM <nombre_bucket>
COUNT ALL
COUNT *
```

#### Ejemplos:

**Conteo de una unidad específica:**
```text
jettra-shell [admin@127.0.0.1:9091/sample_enterprise_db]> COUNT employees
[COUNT] [DOCUMENT] 'employees': 1 registro(s).

jettra-shell [admin@127.0.0.1:9091/sample_enterprise_db]> COUNT product_embeddings
[COUNT] [VECTOR] 'product_embeddings': 1 vector(es).

jettra-shell [admin@127.0.0.1:9091/sample_enterprise_db]> COUNT catalog_graph
[COUNT] [GRAPH] 'catalog_graph': 2 vértice(s).
```

**Conteo global de toda la base de datos:**
```text
jettra-shell [admin@127.0.0.1:9091/sample_enterprise_db]> COUNT ALL
=== CONTEO TOTAL DE REGISTROS EN BASE DE DATOS: 'sample_enterprise_db' ===
  * [DOCUMENT]   departments            : 1 registro(s)
  * [DOCUMENT]   employees              : 1 registro(s)
  * [DOCUMENT]   products               : 1 registro(s)
  * [VECTOR]     product_embeddings     : 1 vector(es)
  * [VECTOR]     employee_biometrics    : 1 vector(es)
  * [GRAPH]      catalog_graph          : 2 vértice(s)
  * [TIMESERIES] telemetry              : 1 punto(s)
  * [KEYVALUE]   inventory_cache        : 1 clave(s)
Gran Total en 'sample_enterprise_db': 9 registro(s) multimodelo.
```

---

## 13. Operaciones CRUD sobre Registros

```sql
USE sample_enterprise_db;

-- Inserción (Sintaxis amigable o SQL estándar)
INSERT INTO employees ID emp_02 JSON {"name": "Alan Turing", "dept": "Cryptanalysis"};
INSERT INTO employees VALUES ('emp_03', '{"name": "Donald Knuth", "dept": "Algorithms"}');

-- Lectura puntual
GET employees emp_02;

-- Escaneo masivo con paginación
FIND ALL employees LIMIT 10;

-- Actualización
UPDATE employees SET {salary: 210000} WHERE _id = 'emp_02';

-- Eliminación
DELETE employees emp_03;
DELETE FROM employees WHERE _id = 'emp_02';
```

---

## 14. Motores Multimodelo Especializados

### 13.1 Motor Vectorial (Embeddings IA)
```sql
-- Indexar vector float[] de 3 dimensiones
VECTOR INDEX product_embeddings emb_02 [0.85, 0.12, -0.33];

-- Búsqueda de vecinos más cercanos (k-NN Cosine Similarity)
VECTOR SEARCH product_embeddings [0.80, 0.10, -0.30] K 3;
```

### 13.2 Motor de Grafos
```sql
-- Crear vértices
GRAPH ADD VERTEX catalog_graph prod_02;
GRAPH ADD VERTEX catalog_graph cat_software;

-- Conectar arista dirigida con peso
GRAPH ADD EDGE catalog_graph prod_02 cat_software LABEL CATEGORIZED_IN WEIGHT 0.95;

-- Consultar aristas salientes
GRAPH GET EDGES catalog_graph prod_02;
```

### 13.3 Motor de Series Temporales (IoT)
```sql
-- Registrar punto métrico
TS RECORD server_cpu 14.8 TIME 1759160500;

-- Consultar rango temporal
TS RANGE server_cpu 1759160000 1759161000;
```

### 13.4 Motor Clave-Valor (Memoria de Ultra Alta Velocidad)
```sql
KV PUT cache session_admin_token "JettraJWT.abc123xyz";
KV GET cache session_admin_token;
```

---

## 15. Respaldos Físicos en Caliente y Restauración (Backup & Restore)

Generación de snapshots binarios con verificación de suma de comprobación CRC32:

```sql
-- Crear respaldo físico de la base de datos actual
BACKUP DATABASE;

-- Crear respaldo indicando base de datos y ruta destino explícita
BACKUP DATABASE sample_enterprise_db TO '/var/backups/enterprise_2026.snap';

-- Restaurar snapshot físico en caliente
RESTORE DATABASE sample_enterprise_db FROM '/var/backups/enterprise_2026.snap';
```

---

## 16. Tutorial Práctico Extremo a Extremo (Paso a Paso)

```sql
-- PASO 1: Iniciar sesión y validar telemetría
connect local_master
login admin admin-jettra
status

-- PASO 2: Instalar y persistir todas las bases de datos de prueba
INSTALL SAMPLES
SHOW DBS
SHOW SAMPLES

-- PASO 3: Seleccionar la base de datos empresarial y verificar colecciones
USE sample_enterprise_db
SHOW COLLECTIONS
SHOW INDEXES

-- PASO 4: Consultar con JettraQL y JettraSQL
JQL FROM products WHERE category = Hardware;
SQL SELECT * FROM employees;

-- PASO 5: Probar resolución de referencias JettraRef
lazy reference on
GET products prod_01
GET employees emp_01

-- PASO 6: Crear un índice secundario sobre salarios y reconstruirlo
CREATE INDEX idx_salaries ON employees (salary) TYPE BTREE
SHOW INDEXES
ALTER INDEX idx_salaries REBUILD

-- PASO 7: Crear usuario desarrollador con privilegios específicos
CREATE USER dev_analyst PASSWORD analyst-2026 ROLE DEVELOPER
GRANT READ_ONLY ON sample_enterprise_db TO dev_analyst
SHOW GRANTS FOR dev_analyst

-- PASO 8: Respaldar la base de datos y cerrar sesión
BACKUP DATABASE sample_enterprise_db TO './data/jettra/manual_backup.snap'
logout
```

---

## 17. Tabla Rápida de Comandos y Ayuda (`help`)

```text
==============================================================================================
                         JETTRASTORE SHELL - GUÍA COMPLETA DE COMANDOS
==============================================================================================
1. CONEXIÓN Y SESIÓN:
  connect <url> <port>                  Establece la dirección del nodo servidor JettraStore.
  connect <nombre-perfil>               Conecta utilizando un perfil previamente guardado.
  login <username> <password>           Autentica y obtiene un token de sesión criptográfico JettraJWT.
  logout                                Cierra la sesión activa y revoca el token JWT.
  save connection <nombre>              Guarda el perfil de conexión actual con un alias.
  remove connection <nombre>            Elimina un perfil de conexión guardado.
  list connections / list conections    Lista todos los perfiles de conexión guardados.

2. TELEMETRÍA Y CLÚSTER:
  status                                Monitorea RAM Panama FFM, CPU Loom y Disco LSM.
  show nodes / list nodes               Muestra la topología del clúster Raft y nodos del anillo.
  cluster-distributed <all>             distribuye entre todos los nodos todas las bases de datos
  cluster-distributed <nombre-base-datos>: distribuye la base de datos indicada
  cluster-distributed info :            Muestra una tabla con los nodos y las bases de datos en cada nodo.
  cluster live [límite]                 Muestra trazas y eventos en tiempo real de lo que ocurre en el clúster.
  multinode / show multinode            Muestra el estado de cluster.multinode.active (ON/OFF).
  multinode on / multinode off          Activa o desactiva dinámicamente la distribución de datos.
  add node <id> <host> <port> [ROLE]    Agrega un nuevo nodo secundario al clúster Raft.
  remove node <id>                      Remueve un nodo réplica del anillo dinámico.
  start node <id>                       Inicia y activa el procesamiento para un nodo específico.
  stop node <id>                        Detiene un nodo réplica (pausa el tráfico de descarga).

3. BASES DE DATOS Y PERSISTENCIA EN DISCO:
  show databases / show dbs             Lista todas las bases de datos detectadas en disco y memoria.
  show samples / show sample dbs        Muestra las bases de datos de prueba preconfiguradas (incluyendo 3M).
  LOAD SAMPLE example_factura_db        Carga la base de datos de facturación con 3,000,000 objetos multimodelo.
  LOAD SAMPLE samples_hostipal_db       Carga la base de datos de salud hospitalaria con 2,000,000 objetos.
  LOAD SAMPLE samples_ambiental_db      Carga la base de datos ambiental mundial con 3,000,000 objetos.
  create database <nombre>              Crea una nueva base de datos lógica.
  drop database <nombre>                Elimina la base de datos especificada.
  use <nombre>                          Conmuta la base de datos activa.
  db stats                              Muestra estadísticas de la base de datos activa.
  INSTALL SAMPLES                       Instala y persiste las 5 bases de datos de ejemplo.
  backup database [nombre] [TO 'path']  Genera un snapshot físico .snap de la base de datos.
  restore database [nombre] FROM 'path' Restaura un snapshot .snap en una base de datos.

4. BUCKETS/UNITS, REGISTROS Y CONTEO:
  show buckets / show unit              Lista todos los buckets/units de almacenamiento por motor.
  show buckets <DOCUMENT|VECTOR|..>     Filtra las unidades por motor específico.
  show records <bucket> [LIMIT n]       Muestra los registros del bucket (documentos, vectores, grafos, etc.).
  count <bucket>                        Cuenta los registros contenidos en la unidad especificada.
  count all / count *                   Censo y conteo total de registros en todos los motores de la base de datos.

5. CONSULTAS POLÍGLOTAS (JETTRAQL Y JETTRASQL):
  JQL FROM <col> [WHERE campo = valor]  Consulta declarativa sobre documentos.
  JQL MATCH (a)-[r]->(b) IN <grafo>     Pattern matching sobre redes de grafos.
  JQL VECTOR SIMILARITY <col> TO [...]  Búsqueda de vecinos más cercanos por similaridad coseno.
  JQL FETCH <col> <id> [RESOLVE REFS]   Recupera un registro resolviendo referencias JettraRef.
  SQL SELECT * FROM <col> [WHERE k = v] Consulta relacional con tabla formateada de columnas y filas.
  SQL INSERT INTO <col> VALUES (id, json) Inserta registro en la colección activa.
  SQL UPDATE <col> SET k = v WHERE _id = id Actualiza campos de un registro.
  SQL DELETE FROM <col> WHERE _id = id  Elimina un registro mediante sintaxis SQL.

6. REGISTROS REFERENCIADOS (JETTRAREF) Y LAZY LOADING:
  lazy reference on / off               Alterna la resolución diferida (Lazy) o inmediata (Eager).
  insert ref <col> <id> KEY <k> TARGET <engine>::<col>#<id>  Vincula un puntero cruzado multimodelo.
  resolve ref <engine>::<col>#<id>      Resuelve manualmente el destino de una referencia.
  show refs <col> <id>                  Muestra todas las referencias de un registro y sus resoluciones.
  get <col> <id>                        Obtiene un documento y resuelve sus punteros _ref_*.

7. ADMINISTRACIÓN DE ÍNDICES:
  create index <nombre> ON <col> (campo) [TYPE BTREE|HASH|SPARSE] [UNIQUE]  Crea índice secundario.
  drop index <nombre>                   Elimina el índice especificado.
  alter index <nombre> rebuild          Reconstruye el índice re-escaneando los documentos.
  show indexes [ON <col>]               Muestra la tabla de índices creados en la base de datos.

8. ADMINISTRACIÓN DE USUARIOS Y ROLES (RBAC):
  show users / list users               Muestra todos los usuarios, rol global y roles por base de datos.
  create user <user> PASSWORD <pass> [ROLE <role>] Crea un nuevo usuario en el sistema.
  drop user <user>                      Elimina un usuario (superuser 'admin' inmutable).
  alter user <user> PASSWORD <newPass>  Actualiza la contraseña del usuario.
  alter user <user> ROLE <newRole>      Actualiza el rol global del usuario.
  grant <DB_OWNER|READ_WRITE|READ_ONLY> ON <db> TO <user>  Asigna privilegios sobre una base de datos.
  revoke <db> FROM <user>               Revoca el acceso sobre la base de datos indicada.
  show grants for <user>                Muestra los privilegios asignados al usuario especificado.

9. MOTORES ESPECIALIZADOS (VECTORES, GRAFOS, TIME SERIES, KV):
  vector index <col> <id> [f1,f2,..]    Indexa vector float[] en el motor vectorial.
  vector search <col> [f1,f2] K <num>   Búsqueda k-NN por similaridad coseno.
  graph add vertex <grafo> <id>         Agrega un vértice a la red de grafos.
  graph add edge <g> <a> <b> [LABEL l]  Agrega arista dirigida ponderada.
  ts record <serie> <val> [TIME t]      Registra punto métrico en serie temporal.
  ts range <serie> <inicio> <fin>       Consulta métricas en rango de tiempo.
  kv put <tabla> <clave> <valor>        Almacena clave-valor en memoria de acceso ultra rápido.
  kv get <tabla> <clave>                Recupera el valor asociado a la clave.
==============================================================================================
```

---

## 7. Gestión del Modo de Almacenamiento: `JVM-RAM` vs `DISK-MEMORY (JettraMemory)`

`JettraStoreShell` permite consultar y conmutar en caliente el modo de almacenamiento entre la memoria RAM de la JVM y el motor de disco off-heap `JettraMemory`:

### 7.1 Comandos de Consola
* **Consultar Modo Activo:**
  ```text
  jettra-shell> STORAGE_MODE
  jettra-shell> SHOW STORAGE_MODE
  ```
* **Conmutar a Memoria RAM (JVM Heap/Stack):**
  ```text
  jettra-shell> STORAGE_MODE JVM_RAM
  jettra-shell> SET STORAGE_MODE = JVM-RAM
  ```
* **Conmutar a Disco Directo Off-Heap (JettraMemory LSM):**
  ```text
  jettra-shell> STORAGE_MODE DISK_MEMORY
  jettra-shell> SET STORAGE_MODE = DISK-MEMORY
  ```

### 7.2 Comportamiento de los Modos
* **`JVM-RAM`:** Las colecciones se cargan y consultan en las áreas de memoria Stack y Heap de Java con estructuras `UnifiedMap`, aprovechando ZGC y Compact Object Headers.
* **`DISK-MEMORY`:** Los registros se escriben y leen directamente en disco mediante `JettraMemory` utilizando punteros nativos Panama `MemorySegment`, asegurando 0% de impacto en el montículo.

---

## 16. Módulo de Analítica, Cálculos Cuantitativos y Álgebra Vectorial CLI

`JettraStoreShell` incorpora comandos directos y sintaxis declarativa para realizar cálculos analíticos instantáneos con salida en tablas ASCII formateadas:

### 16.1 Comandos de Agregación (`AGGREGATE` y `SELECT ... GROUP BY`)
* **Comando Directo:**
  ```bash
  AGGREGATE facturas GROUP BY estado SUM total AVG total MEDIAN total COUNT
  ```
* **Sintaxis SQL:**
  ```sql
  SELECT region, SUM(monto) AS total_ventas, AVG(monto) AS promedio, MEDIAN(monto) AS mediana FROM ventas GROUP BY region;
  ```

### 16.2 Evaluador Matemático Cuantitativo (`MATH` / `CALC`)
Permite evaluar cualquier expresión aritmética, trigonométrica o combinatoria:
```bash
MATH cbrt(1000) + sqrt(144) * 2 - hypot(3, 4) + fact(5)
```
* Funciones soportadas: `sqrt`, `cbrt`, `pow`, `abs`, `round`, `ceil`, `floor`, `factorial` / `fact`, `gcd`, `lcm`, `hypot`, `sin`, `cos`, `tan`, `atan2`.

### 16.3 Operaciones Financieras (`FINANCE`)
* **Cuota Mensual Fija PMT (Sistema Francés):**
  ```bash
  FINANCE PMT 0.004166 360 200000
  ```
* **Valor Futuro (FV) y Valor Presente (PV):**
  ```bash
  FINANCE FV 0.05 10 1000 5000
  FINANCE PV 0.05 10 1000 15000
  ```
* **Tasa de Crecimiento Anual Compuesto (CAGR %):**
  ```bash
  FINANCE CAGR 100000 350000 5
  ```
* **Valor Presente Neto (NPV / VAN) y TIR (IRR %):**
  ```bash
  FINANCE NPV 0.10 -100000 30000 40000 50000 35000
  FINANCE IRR -100000 30000 40000 50000 35000
  ```
* **Tabla Completa de Amortización Francesa:**
  ```bash
  FINANCE AMORTIZATION 10000 0.05 12
  ```

### 16.4 Estadística Descriptiva e Inferencial (`STATS`)
* **Resumen Integral:**
  ```bash
  STATS SUMMARY 12, 15, 18, 22, 25, 30, 35, 42, 50, 65, 80
  ```
* **Medidas Individuales:**
  ```bash
  STATS MEAN 10, 20, 30, 40, 50
  STATS MEDIAN 10, 20, 30, 40, 50
  STATS STDDEV 10, 20, 30, 40, 50
  STATS IQR 10, 20, 30, 40, 50
  ```
* **Correlación de Pearson y Regresión Lineal Bivariada:**
  ```bash
  STATS CORRELATION [1, 2, 3, 4, 5] [2.1, 4.0, 5.9, 8.1, 9.9]
  STATS REGRESSION [1, 2, 3, 4, 5] [2.1, 4.0, 5.9, 8.1, 9.9]
  ```

### 16.5 Álgebra Vectorial y Búsqueda Multidimensional (`VECTOR`)
* **Similitud Coseno y Distancia Euclidiana:**
  ```bash
  VECTOR COSINE [0.8, 0.2, 0.5] [0.75, 0.25, 0.45]
  VECTOR EUCLIDEAN [0.8, 0.2, 0.5] [0.75, 0.25, 0.45]
  ```
* **Producto Punto y Normalización:**
  ```bash
  VECTOR DOT [1, 2, 3] [4, 5, 6]
  VECTOR NORMALIZE [3, 4, 0]
  ```
* **Geometría 3D y Ángulos:**
  ```bash
  VECTOR CROSS [1, 0, 0] [0, 1, 0]
  VECTOR ANGLE [1, 0, 0] [0, 1, 0]
  ```


---

## 17. Historial Persistente, Expansión Bang y Autocompletado de Comandos CLI

Para maximizar la productividad del operador y permitir una experiencia de consola ágil similar a shells modernos (`bash`, `zsh`, `fish`), `JettraStoreShell` incorpora un subsistema de historial persistente y autocompletado en tiempo real.

### 17.1 Historial Persistente (`~/.jettra/history.log`)
Cada comando ejecutado en la consola interactiva se persiste automáticamente en el archivo `~/.jettra/history.log` y se mantiene en memoria compartida (hasta los últimos 1,000 comandos).

Comandos de gestión de historial:
* **Ver historial completo o limitado:**
  ```bash
  HISTORY           # Muestra todos los comandos registrados con su número de orden
  HISTORY 20        # Muestra los últimos 20 comandos ejecutados
  ```
  *Salida de ejemplo:*
  ```text
  === HISTORIAL DE COMANDOS (3 registrados) ===
    [   1] CONNECT 127.0.0.1 9091
    [   2] USE example_factura_db
    [   3] DB STATS
  --------------------------------------------------------------------------------
  Uso: !<num> ejecuta comando por número | !! ejecuta el último | !<prefijo> ejecuta por coincidencia
  ```
* **Búsqueda en el historial:**
  ```bash
  HISTORY SEARCH factura
  ```
* **Limpiar el historial:**
  ```bash
  HISTORY CLEAR     # Vacía el historial en memoria y trunca ~/.jettra/history.log
  ```

### 17.2 Expansión Bang (`!`)
Permite re-ejecutar comandos previos rápidamente:
* `!!` : Re-ejecuta el último comando ingresado.
* `!n` : Ejecuta el comando en la posición `n` del historial (ejemplo: `!1` para el primer comando).
* `!<prefijo>` : Busca y ejecuta el comando más reciente que comience con `<prefijo>` (ejemplo: `!use` o `!stat`).

### 17.3 Autocompletado Contextual (`COMPLETE` y `TAB`)
El subsistema de autocompletado analiza el prefijo actual y propone completados basados en:
1. **Palabras clave y comandos del sistema:** `SHOW DATABASES`, `DB STATS`, `CREATE INDEX`, `AGGREGATE`, `MATH`, `FINANCE`, `VECTOR`, etc.
2. **Bases de datos disponibles:** Al escribir `USE ` o `DROP DATABASE `, sugiere bases de datos instaladas y de muestra.
3. **Muestras preconfiguradas:** Al escribir `LOAD SAMPLE ` o `INSTALL SAMPLE `, sugiere `example_factura_db`, `samples_hostipal_db`, `samples_ambiental_db`.
4. **Buckets y colecciones activas:** Al escribir `SHOW RECORDS `, `COUNT ` o `SELECT * FROM `, sugiere los buckets existentes en la base de datos seleccionada.
5. **Historial de comandos previos:** Prioriza comandos frecuentes.

Sintaxis en consola:
```bash
COMPLETE SH             # Sugiere SHOW BUCKETS, SHOW DATABASES, SHOW RECORDS, SHOW SAMPLES, etc.
TAB LOAD                # Sugiere LOAD SAMPLE example_factura_db, etc.
COMPLETE USE            # Sugiere las bases de datos lógicas registradas en el clúster
```

---

## 18. Estadísticas Multimodelo Completas (DB STATS con 8 Motores Especializados y Java Records)

El comando `DB STATS` audita e inspecciona la totalidad de los 8 motores multimodelo soportados en JettraStore, garantizando visibilidad transparente tanto de motores tradicionales como de motores analíticos y de alta velocidad:

### 18.1 Salida Completa de `DB STATS`
Al invocar `db stats` sobre una base de datos multimodal (por ejemplo `example_factura_db` tras `LOAD SAMPLE example_factura_db`), el reporte muestra detalladamente las unidades de cada motor:

```text
jettra-shell [admin@127.0.0.1:9091/example_factura_db]> db stats
=== ESTADÍSTICAS DE BASE DE DATOS: 'example_factura_db' ===
- Colecciones Totales: 10
- Documentos:          [detalles_factura, facturas, clientes]
- Clave-Valor (KV):    [cache_folios]
- Vectores:            [factura_embeddings]
- Grafos:              [red_comercial]
- Series Temporales:   [volumen_facturacion]
- Geoespacial (GIS):   [sucursales_fiscales]
- Columnar (OLAP):     [analitica_fiscal]
- Java Records:        [auditoria_records]
- Índices Secundarios: 2
- MemTable Utilizada:  0,00 KB
```

### 18.2 Motores Multimodelo Soportados y Representados
1. **Documentos (`DocumentEngine`):** Almacenamiento JSON/BSON con soporte de índices Hash, B-Tree y Sparse.
2. **Clave-Valor KV (`KeyValueEngine`):** Almacenamiento ultra veloz byte-array para caché de tokens, folios y estados volátiles.
3. **Vectores (`VectorEngine`):** Embeddings multidimensionales indexados mediante grafos HNSW y búsqueda k-NN por similitud coseno.
4. **Grafos (`GraphEngine`):** Redes de conocimiento orientadas a nodos y aristas dirigidas ponderadas con relaciones `JettraRef`.
5. **Series Temporales (`TimeSeriesEngine`):** Registro de métricas append-only con compresión Delta y consultas cronológicas por rangos.
6. **Geoespacial GIS (`GeospatialEngine`):** Puntos geoespaciales (latitud/longitud) indexados con R-Tree y consultas por radio geodésico y Bounding Box.
7. **Columnar OLAP (`ColumnarEngine`):** Almacenamiento por columnas vectorizado para analítica fiscal y agregaciones masivas.
8. **Java Records (`RecordsEngine`):** Objetos tipados de Java 25 (`record`) nativos, inmutables, con persistencia zero-copy en memoria y disco (ejemplo: `FacturaAuditRecord`).
