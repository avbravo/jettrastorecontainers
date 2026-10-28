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
6. [Administración de Nodos del Clúster Raft y Supervisión en Vivo](#6-administración-de-nodos-del-clúster-raft)
   - [6.1 Parámetro `cluster.multinode.active`](#61-parámetro-clustermultinodeactive-distribución-de-datos-vs-servidor-único)
   - [6.2 Comandos de Distribución de Clúster (`cluster distributed`)](#62-comandos-de-distribución-de-clúster-cluster-distributed)
   - [6.3 Supervisión en Tiempo Real con `cluster live`](#63-supervisión-en-tiempo-real-con-cluster-live)
   - [6.4 Mecanismo de Failover y Reconexión Automática](#64-mecanismo-de-failover-y-reconexión-automática)
7. [Administración de Bases de Datos y Muestras (`SHOW DBS` y `SHOW SAMPLES`)](#7-administración-de-bases-de-datos-y-muestras-show-dbs-y-show-samples)
8. [Lenguajes de Consulta: JettraQL y JettraSQL](#8-lenguajes-de-consulta-jettraql-y-jettrasql)
   - [8.1 JettraQL](#81-jettraql-motor-declarativo-multimodelo)
   - [8.2 JettraSQL](#82-jettrasql-dialecto-relacional-extendido)
   - [8.3 Prevención Anti-OOM: Sentinel y Streaming Progresivo](#83-prevención-anti-oom-sentinel-y-streaming-progresivo)
9. [Registros Referenciados Multimodelo (`JettraRef`) y Lazy Loading](#9-registros-referenciados-multimodelo-jettraref-y-lazy-loading)
10. [Administración Integral de Índices](#10-administración-integral-de-índices)
11. [Administración de Usuarios y Roles de Base de Datos (RBAC Granular)](#11-administración-de-usuarios-y-roles-de-base-de-datos-rbac-granular)
12. [Exploración de Buckets/Units, Inspección de Registros y Conteo Multimodelo](#12-exploración-de-bucketsunits-inspección-de-registros-y-conteo-multimodelo)
13. [Operaciones CRUD sobre Unidades y Registros por cada Motor Multimodelo](#13-operaciones-crud-sobre-unidades-y-registros-por-cada-motor-multimodelo)
   - [13.1 Ciclo de Vida y Creación de Motores/Unidades](#131-ciclo-de-vida-de-base-de-datos-y-creación-de-motores-unidades--buckets)
   - [13.2 CRUD en Motor Documental (DOCUMENT)](#132-crud-completo-en-motores-documentales-document)
   - [13.3 CRUD en Motor Clave-Valor (KEYVALUE / KV)](#133-crud-completo-en-motores-clave-valor-keyvalue--kv)
   - [13.4 CRUD en Motor Vectorial (VECTOR)](#134-crud-completo-en-motores-vectoriales-vector)
   - [13.5 CRUD en Motor de Grafos (GRAPH)](#135-crud-completo-en-motores-de-grafos-graph)
   - [13.6 CRUD en Motor de Series Temporales (TIMESERIES)](#136-crud-completo-en-motores-de-series-temporales-timeseries)
14. [Ejemplo Práctico Completo 1: Base de Datos de un Hospital Inteligente (`hospital_db`)](#14-ejemplo-práctico-completo-base-de-datos-de-un-hospital-inteligente-hospital_db)
15. [Ejemplo Práctico Multimodelo Completo 2: Órdenes de Compra, Facturación y Servicios Técnicos (`orders_billing_db`)](#15-ejemplo-práctico-multimodelo-completo-2-órdenes-de-compra-facturación-y-servicios-técnicos-orders_billing_db)
   - [15.1 Visión del Dominio y Modelo de Datos Multimodal](#151-visión-del-dominio-y-modelo-de-datos-multimodal)
   - [15.2 Fase 1: Creación de Base de Datos y Aprovisionamiento de Unidades](#152-fase-1-creación-de-base-de-datos-y-aprovisionamiento-de-unidades)
   - [15.3 Fase 2: Inserción de Documentos Maestros (Empresas, Clientes, Productos, Técnicos, Órdenes y Facturas)](#153-fase-2-inserción-de-documentos-maestros-empresas-clientes-productos-técnicos-órdenes-y-facturas)
   - [15.4 Fase 3: Motor Clave-Valor (KV) para Disponibilidad de Técnicos y Folios Fiscales](#154-fase-3-motor-clave-valor-kv-para-disponibilidad-de-técnicos-y-folios-fiscales)
   - [15.5 Fase 4: Motor Vectorial (VECTOR) para Diagnóstico Predictivo y Búsqueda de Piezas](#155-fase-4-motor-vectorial-vector-para-diagnóstico-predictivo-y-búsqueda-de-piezas)
   - [15.6 Fase 5: Motor de Grafos (GRAPH) para Trazabilidad Integral de Órdenes y Servicios](#156-fase-5-motor-de-grafos-graph-para-trazabilidad-integral-de-órdenes-y-servicios)
   - [15.7 Fase 6: Motor de Series Temporales (TIMESERIES) para Telemetría de Campo](#157-fase-6-motor-de-series-temporales-timeseries-para-telemetría-de-campo)
   - [15.8 Fase 7: Motor Geoespacial (GIS) y Despacho Inteligente de Técnicos en Ruta](#158-fase-7-motor-geoespacial-gis-y-despacho-inteligente-de-técnicos-en-ruta)
   - [15.9 Fase 8: Motor Columnar (OLAP) para Analítica Fiscal y Márgenes](#159-fase-8-motor-columnar-olap-para-analítica-fiscal-y-márgenes)
   - [15.10 Fase 9: Java 25 Records para Auditoría Criptográfica Inmutable](#1510-fase-9-java-25-records-para-auditoría-criptográfica-inmutable)
   - [15.11 Fase 10: Punteros Cruzados Multimodelo JettraRef y Resolución Lazy](#1511-fase-10-punteros-cruzados-multimodelo-jettraref-y-resolución-lazy)
   - [15.12 Fase 11: Consultas Políglotas (JettraSQL, JettraQL) y Paginación Interactiva](#1512-fase-11-consultas-políglotas-jettrasql-jettraql-y-paginación-interactiva)
   - [15.13 Fase 12: Analítica CLI y Cálculos Financieros (AGGREGATE, MATH, FINANCE)](#1513-fase-12-analítica-cli-y-cálculos-financieros-aggregate-math-finance)
   - [15.14 Fase 13: Verificación con DB STATS, Conteo Global y Snapshot de Respaldo](#1514-fase-13-verificación-con-db-stats-conteo-global-y-snapshot-de-respaldo)
16. [Respaldos Físicos en Caliente y Restauración (Backup & Restore)](#16-respaldos-físicos-en-caliente-y-restauración-backup--restore)
17. [Gestión del Modo de Almacenamiento: `JVM-RAM` vs `DISK-MEMORY (JettraMemory)`](#17-gestión-del-modo-de-almacenamiento-jvm-ram-vs-disk-memory-jettramemory)
18. [Módulo de Analítica, Cálculos Cuantitativos y Álgebra Vectorial CLI](#18-módulo-de-analítica-cálculos-cuantitativos-y-álgebra-vectorial-cli)
19. [Historial Persistente, Expansión Bang y Autocompletado de Comandos CLI (TAB y Flechas ↑/↓)](#19-historial-persistente-expansión-bang-y-autocompletado-de-comandos-cli)
20. [Estadísticas Multimodelo Completas (DB STATS con 8 Motores Especializados y Java Records)](#20-estadísticas-multimodelo-completas-db-stats-con-8-motores-especializados-y-java-records)
21. [Comandos de Distribución en Clúster (`cluster-distributed`) y Supervisión en Vivo (`cluster live`)](#21-comandos-de-distribución-en-clúster-cluster-distributed-y-supervisión-en-vivo-cluster-live)
22. [Tutorial Práctico Extremo a Extremo (Paso a Paso)](#22-tutorial-práctico-extremo-a-extremo-paso-a-paso)
23. [Tabla Rápida de Comandos y Referencia General (`help`)](#23-tabla-rápida-de-comandos-y-referencia-general-help)

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
# Modo Multinodo y Algoritmo de Consenso Distribuido (on: cluster distribuido, off: servidor único)
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

4. CLÚSTER & DISTRIBUCIÓN (CONSENSO):
   - Modo Multinodo (cluster.multinode.active): ON (Consenso y distribución ACTIVO)
   - Algoritmo de Consenso:      RAFT Integrado & Dynamic Ring
   - Nodos Pares Registrados:    2
==============================================================================================
```

---

## 6. Administración de Nodos del Clúster Raft

JettraStore opera sobre una topología distribuida basada en consenso Raft y un motor de anillo dinámico (*Dynamic Ring Engine*).

### Comandos de Nodos y Modo Multinodo:
| Comando | Descripción | Ejemplo |
| :--- | :--- | :--- |
| `show nodes` / `list nodes` | Muestra la topología completa del clúster Raft y estado multinodo. | `show nodes` |
| `multinode` / `show multinode` | Consulta el estado actual de `cluster.multinode.active` (ON / OFF). | `multinode` |
| `multinode on` / `set multinode on` | Activa la distribución de datos con consenso integrado. | `multinode on` |
| `multinode off` / `set multinode off` | Desactiva la distribución (modo servidor único standalone sin distribuir datos). | `multinode off` |
| `add node <id> <ip> <port> [ROLE]`| Registra un nuevo nodo en el clúster. | `add node node-04 192.168.1.104 9091 SECONDARY` |
| `stop node <id>` | Pausa el nodo réplica (detiene la descarga de memoria).| `stop node node-02` |
| `start node <id>` | Reactiva el nodo para recibir transferencias del anillo.| `start node node-02` |
| `remove node <id>` | Remueve un nodo secundario del clúster. | `remove node node-04` |
| `cluster distributed <db>` | Distribuye y replica la base de datos y sus registros hacia los secundarios. | `cluster distributed facturas_db` |
| `cluster distributed all` | Distribuye y replica todas las bases de datos registradas hacia los secundarios. | `cluster distributed all` |
| `cluster distributed info` | Muestra la matriz de topología y distribución de datos del clúster. | `cluster distributed info` |
| `cluster live [limit]` | Streaming interactivo y visualización en tiempo real de eventos del clúster. | `cluster live 50` |

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
Modo Multinodo (cluster.multinode.active): ON (Algoritmo de consenso y distribución de datos ACTIVO)
Total: 3 nodo(s) registrados en el anillo dinámico. Quórum: Activo (Consenso distribuido).
```

### 6.1 Parámetro `cluster.multinode.active` (Distribución de Datos vs Servidor Único)
En el archivo `database.properties` de JettraStore, la propiedad `cluster.multinode.active` controla si la instancia distribuye datos entre nodos o trabaja de forma autónoma:
- **`on`**: Activa el sistema de distribución de datos mediante algoritmo de consenso integrado (Raft y Dynamic Ring). Las transiciones de memoria y saturación derivan datos a los nodos secundarios.
- **`off`**: Desactiva el comportamiento de distribución. Se asume que todas las operaciones se realizarán solamente en el servidor donde se está ejecutando JettraStore sin distribuir los datos.

### 6.2 Comandos de Distribución de Clúster (`cluster distributed`)
Para forzar la sincronización íntegra de una base de datos o de todo el clúster:
* **`cluster distributed <database>`**: Crea y serializa el snapshot en el nodo primario e instruye a todos los nodos secundarios configurados para que carguen la estructura y los registros de la base de datos indicada.
* **`cluster distributed all`**: Itera sobre todas las bases de datos activas en el nodo primario y sincroniza su estado y registros con los nodos secundarios réplica.
* **`cluster distributed info`**: Despliega una tabla con el estado de replicación, número de bases de datos compartidas y disponibilidad por nodo.

### 6.3 Supervisión en Tiempo Real con `cluster live`

#### 6.3.1 Propósito y Sintaxis
El comando interactivo `cluster live [limit]` (o la opción `[9]` en el menú interactivo `menu`, y documentado en `help cluster live`) proporciona una ventana de observabilidad en vivo sobre la telemetría, el protocolo de consenso Raft y los eventos operativos entre todos los nodos del clúster sin requerir inspección manual de archivos de logs en disco.

* **Sintaxis:**
  ```bash
  cluster live [límite]
  ```
  Donde `límite` es un parámetro numérico opcional (por defecto `30`) que define cuántos eventos recientes del búfer circular deben desplegarse en pantalla.

* **Propósito:**
  - Auditar en tiempo real la replicación de documentos (`DOCUMENT_REPLICATED`, `DOCUMENT_DELETED`), registros (`RECORD_REPLICATED`, `RECORD_DELETED`) e índices (`INDEX_CREATED`, `INDEX_DROPPED`).
  - Monitorear altas y bajas de nodos (`NODE_ONLINE`, `NODE_OFFLINE`, `NODE_STOPPED`).
  - Visualizar la distribución masiva de bases de datos (`DATABASE_DISTRIBUTED`).
  - Observar en vivo el proceso de elección y promoción de nuevo primario (`LEADER_ELECTION`, `LEADER_PROMOTED`).

#### 6.3.2 Ejemplo de Uso y Salida en Consola
```text
jettra-shell [admin@127.0.0.1:9091/facturas_db]> cluster live 10

========================================================================================================================
                                        JETTRASTORE CLUSTER LIVE EVENT STREAM                                           
========================================================================================================================
+-------------------------+----------------------+---------+---------+-------------------------------------------------+
| Marca Temporal          | Tipo de Evento       | Origen  | Destino | Mensaje / Trazabilidad Operativa                |
+-------------------------+----------------------+---------+---------+-------------------------------------------------+
| 2026-10-07 16:50:10.102 | NODE_ONLINE          | node-01 | ALL     | Nodo en línea y listo (REST: 18080, gRPC: 19090)|
| 2026-10-07 16:50:11.215 | DATABASE_DISTRIBUTED | node-01 | node-02 | Sincronización inicial completa de tienda_db    |
| 2026-10-07 16:50:12.040 | DOCUMENT_REPLICATED  | node-01 | node-02 | Replicando documento prod_100 en productos      |
| 2026-10-07 16:50:13.310 | RECORD_REPLICATED    | node-01 | node-02 | Replicando par clave-valor config:moneda        |
| 2026-10-07 16:50:14.500 | NODE_STOPPED         | node-01 | ALL     | Parada controlada de node-01                    |
| 2026-10-07 16:50:14.545 | LEADER_ELECTION      | node-02 | cluster | Iniciado protocolo de elección de nuevo PRIMARY |
| 2026-10-07 16:50:14.610 | LEADER_PROMOTED      | node-02 | cluster | ¡Failover Exitoso! Nodo promovido a PRIMARY     |
+-------------------------+----------------------+---------+---------+-------------------------------------------------+
Eventos en búfer mostrados: 7 | Canal de Eventos en Tiempo Real: ACTIVO
```

#### 6.3.3 Consenso Dinámico ante Nodos Desconectados
En arquitecturas distribuidas con alta disponibilidad, uno o más nodos pueden sufrir fallos de red o desconexiones temporales. `JettraStore` implementa un algoritmo de **Consenso Dinámico**:
- Cuando se envía una trama Raft (creación/borrado de base de datos, mutación de documentos, claves o índices), el cliente de consenso evalúa la disponibilidad de los nodos en tiempo real.
- Si un nodo secundario no responde (`Conexión rehusada`, `SocketTimeout`), se marca automáticamente como `OFFLINE` y queda excluido del cálculo del quórum.
- El consenso opera **exclusivamente sobre el subconjunto de nodos activos y disponibles en ese momento**:
  $$\text{Quórum Requerido} = \left\lfloor \frac{\text{Nodos Activos}}{2} \right\rfloor + 1$$
- De esta manera, si en un clúster de 3 nodos (1 primario y 2 secundarios) uno o ambos secundarios caen, el nodo primario ajusta el quórum de forma inmediata y continúa procesando operaciones transaccionales sin degradación ni bloqueos inducidos por los nodos sin conexión. Al recuperarse los nodos secundarios, se reconectan automáticamente e integran al quórum general.

#### 6.3.4 Flujo de Arranque y Sincronización de Nodos Secundarios (`SECONDARY`)
Para asegurar la consistencia eventual fuerte de los datos tras reinicios o adiciones dinámicas de nodos:
1. **Solicitud de Catálogo:** Al arrancar, el nodo con rol `SECONDARY` envía una trama `TYPE_SYNC_CATALOG_REQ` al nodo primario activo (`PRIMARY`).
2. **Transferencia de Instantánea Integral:** Por cada base de datos reportada por el primario, el secundario solicita `TYPE_SYNC_DATA_REQ`. El primario consolida la instantánea completa en memoria y disco (`getDatabaseSnapshotBytes`) y la envía comprimida/serializada.
3. **Carga y Activación Local:** El nodo secundario almacena la instantánea en su directorio físico (`_meta.json`), ejecuta la carga Off-Heap en sus motores multimodelo (`loadFromDisk`) y persiste el estado local.
4. **Incorporación Formal al Clúster:** Solo una vez completada y verificada la sincronización de todas las bases de datos y registros existentes, el nodo secundario activa su centinela supervisor de failover y se une formalmente al flujo de operaciones distribuidas del clúster.

### 6.4 Mecanismo de Failover y Reconexión Automática
El Shell interactivo incorpora un receptor de eventos en segundo plano que detecta eventos `LEADER_PROMOTED`:
- Si el nodo primario se detiene o pierde conexión (`NODE_STOPPED` / timeout de latidos), los nodos secundarios eligen un nuevo primario de forma determinista.
- `JettraStoreShell` intercepta el cambio de topología, actualiza su enrutador interno hacia el nuevo primario y notifica al usuario mediante una alerta informativa en pantalla, permitiendo continuar operando de forma continua.

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

---

## 13. Operaciones CRUD sobre Unidades y Registros por cada Motor Multimodelo

`JettraStoreShell` permite manipular de forma exhaustiva tanto el ciclo de vida de los motores (unidades/buckets) como las operaciones **CRUD** (Crear, Leer, Actualizar, Eliminar) sobre los registros en cualquiera de los motores soportados.

### 13.1 Ciclo de Vida de Base de Datos y Creación de Motores (Unidades / Buckets)

```sql
-- 1. Crear una base de datos lógica
CREATE DATABASE clinica_db;

-- 2. Conmutar a la base de datos de trabajo
USE clinica_db;

-- 3. Crear unidades/motores especializados (sintaxis CREATE ENGINE o CREATE COLLECTION)
CREATE ENGINE pacientes TYPE DOCUMENT;
CREATE ENGINE inventario_rapido TYPE KEYVALUE;
CREATE ENGINE diagnostico_vectores TYPE VECTOR;
CREATE ENGINE red_afiliados TYPE GRAPH;
CREATE ENGINE telemetria_pulso TYPE TIMESERIES;
CREATE ENGINE sedes_clinicas TYPE GEOSPATIAL;
CREATE ENGINE costos_servicios TYPE COLUMNAR;

-- 4. Inspeccionar las unidades creadas
SHOW BUCKETS;

-- 5. Eliminar una unidad/motor específico
DROP ENGINE costos_servicios;

-- 6. Eliminar una base de datos completa
-- (Nota: Para eliminar la base de datos activa, conmute primero a otra con USE)
USE default_db;
DROP DATABASE clinica_db;
```

---

### 13.2 CRUD Completo en Motores Documentales (`DOCUMENT`)

El motor documental soporta documentos tipo JSON con identificador único `_id`.

#### C - Create (Inserción de Registros)
```sql
USE clinica_db;

-- Inserción con sintaxis JSON declarativa:
INSERT INTO pacientes ID pac_01 JSON {"nombre": "Maria Elena Vega", "edad": 42, "habitacion": 201, "estado": "Estable"};

-- Inserción con sintaxis VALUES de estilo SQL:
INSERT INTO pacientes VALUES ('pac_02', '{"nombre": "Carlos Alberto Mora", "edad": 61, "habitacion": 104, "estado": "Observacion"}');
```

#### R - Read (Lectura y Consultas)
```sql
-- Lectura directa por ID primario (con resolución de referencias si están activas):
GET pacientes pac_01;

-- Escaneo masivo con límite de resultados:
FIND ALL pacientes LIMIT 10;

-- Consulta relacional ANSI SQL:
SELECT _id, nombre, edad, habitacion, estado FROM pacientes WHERE edad > 40;

-- Consulta declarativa JettraQL:
JQL FROM pacientes WHERE estado = Estable;

-- Conteo de registros en la colección:
COUNT pacientes;

-- Inspección interactiva de registros con diagnóstico:
SHOW RECORDS pacientes LIMIT 5;
```

#### U - Update (Actualización de Registros)
```sql
-- Modificación de campos específicos por identificador _id:
UPDATE pacientes SET {habitacion: 205, estado: "Alta Medica"} WHERE _id = 'pac_01';

-- Comprobación inmediata del registro actualizado:
GET pacientes pac_01;
```

#### D - Delete (Eliminación de Registros)
```sql
-- Eliminación mediante cláusula WHERE:
DELETE FROM pacientes WHERE _id = 'pac_02';

-- Eliminación directa indicando colección e ID:
DELETE pacientes pac_01;

-- Verificación de eliminación:
COUNT pacientes;
```

---

### 13.3 CRUD Completo en Motores Clave-Valor (`KEYVALUE` / `KV`)

Almacenamiento ultra veloz en memoria y disco persistente para sesiones, cachés, identificadores de hardware y folios.

#### C - Create / U - Update (Almacenamiento y Sobrescritura)
```sql
-- Inserción o actualización de pares clave-valor (KV PUT <unidad> <clave> <valor>):
KV PUT inventario_rapido cam_uci_01 "OCUPADA | Monitor Cardiaco Activo";
KV PUT inventario_rapido cam_uci_02 "DISPONIBLE | Desinfectada y Lista";
KV PUT inventario_rapido token_admin_guardia "JWT.Bearer.SecretSession2026";

-- Actualización (sobrescribe el valor existente):
KV PUT inventario_rapido cam_uci_01 "DISPONIBLE | Mantenimiento Concluido";
```

#### R - Read (Lectura Puntual y Listado)
```sql
-- Recuperación puntual por clave:
KV GET inventario_rapido cam_uci_01;
KV GET inventario_rapido cam_uci_02;

-- Inspección de todos los registros en el bucket KV:
SHOW RECORDS inventario_rapido;

-- Conteo de claves registradas:
COUNT inventario_rapido;
```

#### D - Delete (Eliminación)
```sql
-- Eliminación de la unidad clave-valor completa:
DROP ENGINE inventario_rapido;
```

---

### 13.4 CRUD Completo en Motores Vectoriales (`VECTOR`)

Almacenamiento de embeddings multidimensionales para modelos de Inteligencia Artificial, diagnóstico predictivo y similitud semántica.

#### C - Create (Indexación de Vectores)
```sql
-- Indexar vectores float[] multidimensionales (VECTOR INDEX <unidad> <id> [v1, v2, ...]):
VECTOR INDEX diagnostico_vectores vec_cuadro_gripal [0.85, 0.72, 0.14];
VECTOR INDEX diagnostico_vectores vec_infarto_agudo [0.12, 0.96, 0.89];
VECTOR INDEX diagnostico_vectores vec_migrana_severa [0.68, 0.25, 0.40];
```

#### R - Read (Búsqueda Vecinos más Cercanos k-NN Cosine Similarity)
```sql
-- Búsqueda de similitud coseno vectorial:
VECTOR SEARCH diagnostico_vectores [0.80, 0.70, 0.20] K 2;

-- Inspección de registros vectoriales indexados:
SHOW RECORDS diagnostico_vectores;

-- Conteo de vectores:
COUNT diagnostico_vectores;
```

#### U - Update (Recalibración de Vectores)
```sql
-- Para actualizar un vector, se re-indexa el mismo ID con las coordenadas actualizadas:
VECTOR INDEX diagnostico_vectores vec_cuadro_gripal [0.88, 0.75, 0.11];
```

#### D - Delete (Eliminación de la Unidad Vectorial)
```sql
DROP ENGINE diagnostico_vectores;
```

---

### 13.5 CRUD Completo en Motores de Grafos (`GRAPH`)

Redes topológicas dirigidas de conocimiento compuestas por nodos (vértices) y relaciones con atributos y ponderación (aristas).

#### C - Create (Inserción de Vértices y Aristas Dirigidas)
```sql
-- 1. Creación de nodos (vértices):
GRAPH ADD VERTEX red_afiliados dr_fernandez;
GRAPH ADD VERTEX red_afiliados pac_maria;
GRAPH ADD VERTEX red_afiliados pabellon_uci;

-- 2. Creación de conexiones (aristas dirigidas ponderadas):
GRAPH ADD EDGE red_afiliados dr_fernandez pac_maria LABEL TRATA_A WEIGHT 1.0;
GRAPH ADD EDGE red_afiliados pac_maria pabellon_uci LABEL ASIGNADO_A WEIGHT 0.95;
```

#### R - Read (Consultas Topológicas de Conectividad)
```sql
-- Obtener las aristas salientes de un nodo:
GRAPH GET EDGES red_afiliados dr_fernandez;
GRAPH GET EDGES red_afiliados pac_maria;

-- Inspección de toda la topología del grafo:
SHOW RECORDS red_afiliados;

-- Conteo de vértices en el grafo:
COUNT red_afiliados;
```

#### U - Update (Reconfiguración de Aristas y Ponderación)
```sql
-- Re-conectar o actualizar el peso y la etiqueta de la relación entre nodos:
GRAPH ADD EDGE red_afiliados pac_maria pabellon_uci LABEL TRASLADADO_A WEIGHT 0.60;
```

#### D - Delete (Eliminación de la Red de Grafos)
```sql
DROP ENGINE red_afiliados;
```

---

### 13.6 CRUD Completo en Motores de Series Temporales (`TIMESERIES`)

Estructura append-only ultra rápida con compresión de marcas de tiempo delta para lecturas telemétricas e IoT médico continuo.

#### C - Create (Registro de Puntos Telemétricos con Timestamp)
```sql
-- Registrar métricas con marca de tiempo UNIX epoch (TS RECORD <unidad> <valor> [TIME <timestamp>]):
TS RECORD telemetria_pulso 72.0 TIME 1759160000;
TS RECORD telemetria_pulso 75.5 TIME 1759160060;
TS RECORD telemetria_pulso 88.0 TIME 1759160120;
TS RECORD telemetria_pulso 115.3 TIME 1759160180;
TS RECORD telemetria_pulso 74.0 TIME 1759160240;
```

#### R - Read (Consultas de Rangos Temporales y Monitoreo)
```sql
-- Consultar rango temporal cronológico:
TS RANGE telemetria_pulso 1759160000 1759160240;

-- Inspección de puntos telemétricos:
SHOW RECORDS telemetria_pulso LIMIT 10;

-- Conteo total de puntos almacenados:
COUNT telemetria_pulso;
```

#### U - Update / D - Delete
Por su naturaleza arquitectónica append-only orientada a auditoría inmutable, los puntos temporales no se mutan individualmente; nuevas lecturas rectificativas se registran cronológicamente, o la unidad completa se reinicia mediante `DROP ENGINE telemetria_pulso`.

---

## 14. Ejemplo Práctico Completo: Base de Datos de un Hospital Inteligente (`hospital_db`)

A continuación se desarrolla un escenario empresarial real de punta a punta: el diseño, aprovisionamiento, ingesta y manipulación de datos de un **Centro Hospitalario de Alta Complejidad**, implementando **los 8 motores multimodelo**, referencias cruzadas `JettraRef`, índices secundarios, paginación, auditoría y respaldo.

```
       +---------------------------------------------------------------+
       |             HOSPITAL_DB (Multimodelo Distribuido)             |
       +---------------------------------------------------------------+
                 |                   |                   |
                 v                   v                   v
        [DOCUMENT ENGINE]     [VECTOR ENGINE]     [GRAPH ENGINE]
           - pacientes          - sintomas_ia       - red_hospital
           - medicos          (Triaje predictivo) (Médicos, Pacientes, Camas)
                 |                   |                   |
                 +--------+----------+---------+---------+
                          |                    |
                          v                    v
                   [KEYVALUE ENGINE]   [TIMESERIES ENGINE]
                    - cache_camas        - signos_vitales
                    - farmacia_stock   (Telemetría de UCI)
```

---

### Fase 1: Creación de la Base de Datos Hospitalaria y Selección del Contexto

Iniciamos sesión como superusuario en el terminal interactivo de `JettraStoreShell`:

```text
admin@jettra-cluster:primary> CREATE DATABASE hospital_db
[SUCCESS] Base de datos 'hospital_db' creada exitosamente y registrada en el clúster.

admin@jettra-cluster:primary> USE hospital_db
[SUCCESS] Conmutado a base de datos activa: 'hospital_db'.

admin@jettra-cluster:primary> DB STATS
=== ESTADÍSTICAS DE BASE DE DATOS: 'hospital_db' ===
- Colecciones Totales: 0
- Documentos:          []
- Clave-Valor (KV):    []
- Vectores:            []
- Grafos:              []
- Series Temporales:   []
- Geoespacial (GIS):   []
- Columnar (OLAP):     []
- Java Records:        []
- Índices Secundarios: 0
- MemTable Utilizada:  0.00 KB
```

---

### Fase 2: Creación de Motores (Unidades / Buckets) Especializados

Creamos los motores específicos que gobernarán cada vertical de información dentro del hospital:

```text
admin@jettra-cluster:primary> CREATE ENGINE pacientes TYPE DOCUMENT
[SUCCESS] Unidad/Motor 'pacientes' creado con motor multimodelo 'DOCUMENT' en base de datos 'hospital_db'.

admin@jettra-cluster:primary> CREATE ENGINE medicos TYPE DOCUMENT
[SUCCESS] Unidad/Motor 'medicos' creado con motor multimodelo 'DOCUMENT' en base de datos 'hospital_db'.

admin@jettra-cluster:primary> CREATE ENGINE cache_camas TYPE KEYVALUE
[SUCCESS] Unidad/Motor 'cache_camas' creado con motor multimodelo 'KEYVALUE' en base de datos 'hospital_db'.

admin@jettra-cluster:primary> CREATE ENGINE farmacia_stock TYPE KEYVALUE
[SUCCESS] Unidad/Motor 'farmacia_stock' creado con motor multimodelo 'KEYVALUE' en base de datos 'hospital_db'.

admin@jettra-cluster:primary> CREATE ENGINE sintomas_ia TYPE VECTOR
[SUCCESS] Unidad/Motor 'sintomas_ia' creado con motor multimodelo 'VECTOR' en base de datos 'hospital_db'.

admin@jettra-cluster:primary> CREATE ENGINE red_hospital TYPE GRAPH
[SUCCESS] Unidad/Motor 'red_hospital' creado con motor multimodelo 'GRAPH' en base de datos 'hospital_db'.

admin@jettra-cluster:primary> CREATE ENGINE signos_vitales TYPE TIMESERIES
[SUCCESS] Unidad/Motor 'signos_vitales' creado con motor multimodelo 'TIMESERIES' en base de datos 'hospital_db'.

admin@jettra-cluster:primary> CREATE ENGINE ubicacion_centros TYPE GEOSPATIAL
[SUCCESS] Unidad/Motor 'ubicacion_centros' creado con motor multimodelo 'GEOSPATIAL' en base de datos 'hospital_db'.

admin@jettra-cluster:primary> CREATE ENGINE analitica_costos TYPE COLUMNAR
[SUCCESS] Unidad/Motor 'analitica_costos' creado con motor multimodelo 'COLUMNAR' en base de datos 'hospital_db'.
```

#### Verificación del aprovisionamiento:
```text
admin@jettra-cluster:primary> SHOW BUCKETS
==============================================================================================
                BUCKETS / UNITS EN BASE DE DATOS: 'hospital_db'                                        
==============================================================================================
+-------------+----------------------+--------------------+-----------+----------------------+
| Motor       | Tipo de Unidad       | Nombre de Unidad   | Registros | Estado               |
+-------------+----------------------+--------------------+-----------+----------------------+
| DOCUMENT    | Collection           | pacientes          | 0         | ACTIVE (In-Memory)   |
| DOCUMENT    | Collection           | medicos            | 0         | ACTIVE (In-Memory)   |
| VECTOR      | Vector Index [3d]    | sintomas_ia        | 0         | INDEXED (HNSW)       |
| GRAPH       | Property Graph       | red_hospital       | 0         | TOPOLOGY (In-Memory) |
| TIMESERIES  | Metric Series        | signos_vitales     | 0         | APPEND-ONLY (Delta)  |
| KEYVALUE    | KV Store             | cache_camas        | 0         | HASH-MAP (Persistent)|
| KEYVALUE    | KV Store             | farmacia_stock     | 0         | HASH-MAP (Persistent)|
| GEOSPATIAL  | Spatial Layer        | ubicacion_centros  | 0         | R-TREE (Spatial)     |
| COLUMNAR    | Column Family        | analitica_costos   | 0         | ARROW/SLOT (Compress)|
+-------------+----------------------+--------------------+-----------+----------------------+
Total: 9 bucket(s)/unit(s) registrados en la base de datos 'hospital_db'.
```

---

### Fase 3: Operaciones CRUD por Motor y Manipulación de Registros

---

#### 🏥 3.1 Motor Documental (`pacientes` y `medicos`)

##### [CREATE] Inserción de Médicos y Pacientes:
```text
-- Registro de médicos especialistas:
INSERT INTO medicos ID doc_101 JSON {"nombre": "Dr. Fernando Ruiz", "especialidad": "Cardiologia", "planta": 4};
INSERT INTO medicos ID doc_102 JSON {"nombre": "Dra. Carmen Morales", "especialidad": "Neumologia", "planta": 3};

-- Registro de pacientes con antecedentes clínicos y referencias cruzadas:
INSERT INTO pacientes ID pac_01 JSON {"nombre": "Ernesto Valdes", "edad": 54, "habitacion": 402, "estado": "Observacion", "prioridad": "Alta", "_ref_medico": "document::medicos#doc_101", "_ref_sintoma": "vector::sintomas_ia#emb_respiratorio_grave", "_ref_cama": "kv::cache_camas#cama_402"};

INSERT INTO pacientes ID pac_02 JSON {"nombre": "Lucia Santamaria", "edad": 38, "habitacion": 305, "estado": "Estable", "prioridad": "Media", "_ref_medico": "document::medicos#doc_102"};

-- Inserción alternativa con sintaxis VALUES:
INSERT INTO pacientes VALUES ('pac_03', '{"nombre": "Manuel Coronado", "edad": 72, "habitacion": 408, "estado": "Critico", "prioridad": "Urgente"}');
INSERT INTO pacientes VALUES ('pac_04', '{"nombre": "Diana Castrellon", "edad": 29, "habitacion": 102, "estado": "Pre-Alta", "prioridad": "Baja"}');
```

##### [READ] Consultas y Filtros:
```text
-- 1. Consulta individual del paciente pac_01:
admin@jettra-cluster:primary> GET pacientes pac_01
--- REGISTRO [pac_01] EN 'pacientes' ---
  _id             : pac_01
  nombre          : Ernesto Valdes
  edad            : 54
  habitacion      : 402
  estado          : Observacion
  prioridad       : Alta
  _ref_medico     : document::medicos#doc_101
    ↳ [JettraRef Resolución (Lazy Proxy On-Demand)]: Documento {_id=doc_101, nombre=Dr. Fernando Ruiz, especialidad=Cardiologia, planta=4}
  _ref_sintoma    : vector::sintomas_ia#emb_respiratorio_grave
  _ref_cama       : kv::cache_camas#cama_402

-- 2. Consulta ANSI SQL con proyección y filtro condicional:
admin@jettra-cluster:primary> SELECT _id, nombre, edad, habitacion, estado FROM pacientes WHERE edad >= 40
=== JETTRASQL RESULTADO (1 ms) ===
+--------------+----------------------+--------------+----------------+-----------------+
| _id          | nombre               | edad         | habitacion     | estado          |
+--------------+----------------------+--------------+----------------+-----------------+
| pac_01       | Ernesto Valdes       | 54           | 402            | Observacion     |
| pac_03       | Manuel Coronado      | 72           | 408            | Critico         |
+--------------+----------------------+--------------+----------------+-----------------+
Total: 2 fila(s) seleccionadas / afectadas.

-- 3. Consulta JettraQL declarativa:
admin@jettra-cluster:primary> JQL FROM pacientes WHERE prioridad = Urgente
=== JETTRASQL RESULTADO (0 ms) ===
+--------------+----------------------+--------------+----------------+-----------------+
| _id          | nombre               | edad         | habitacion     | estado          |
+--------------+----------------------+--------------+----------------+-----------------+
| pac_03       | Manuel Coronado      | 72           | 408            | Critico         |
+--------------+----------------------+--------------+----------------+-----------------+
Total: 1 fila(s) seleccionadas / afectadas.
```

##### [UPDATE] Modificación del Estado Clínico y Habitación:
```text
admin@jettra-cluster:primary> UPDATE pacientes SET {estado: "Estable", habitacion: 301, prioridad: "Media"} WHERE _id = 'pac_01'
[SUCCESS] Registro con _id 'pac_01' actualizado en 'pacientes'.

admin@jettra-cluster:primary> GET pacientes pac_01
--- REGISTRO [pac_01] EN 'pacientes' ---
  _id             : pac_01
  nombre          : Ernesto Valdes
  edad            : 54
  habitacion      : 301
  estado          : Estable
  prioridad       : Media
```

##### [DELETE] Eliminación de Paciente por Alta Definitiva o Traslado:
```text
admin@jettra-cluster:primary> DELETE FROM pacientes WHERE _id = 'pac_04'
[SUCCESS] Registro con _id 'pac_04' eliminado de 'pacientes'.

admin@jettra-cluster:primary> COUNT pacientes
[COUNT] [DOCUMENT] 'pacientes': 3 registro(s).
```

---

#### 🔑 3.2 Motor Clave-Valor (`cache_camas` y `farmacia_stock`)

##### [CREATE / UPDATE] Almacenamiento Rápido de Camas y Existencias de Fármacos:
```text
admin@jettra-cluster:primary> KV PUT cache_camas cama_402 "OCUPADA | Paciente: pac_01 | Oxigeno: SI"
[SUCCESS] Clave 'cama_402' guardada en tabla KV 'cache_camas'.

admin@jettra-cluster:primary> KV PUT cache_camas cama_305 "OCUPADA | Paciente: pac_02 | Oxigeno: NO"
[SUCCESS] Clave 'cama_305' guardada en tabla KV 'cache_camas'.

admin@jettra-cluster:primary> KV PUT cache_camas cama_408 "OCUPADA | Paciente: pac_03 | UCI-Avanzada"
[SUCCESS] Clave 'cama_408' guardada en tabla KV 'cache_camas'.

admin@jettra-cluster:primary> KV PUT farmacia_stock FARMA_PARACETAMOL "STOCK: 1540 ampollas | Lote: L2026-X"
[SUCCESS] Clave 'FARMA_PARACETAMOL' guardada en tabla KV 'farmacia_stock'.

admin@jettra-cluster:primary> KV PUT farmacia_stock FARMA_CEFTRIAXONA "STOCK: 320 viales | Lote: L2026-AB"
[SUCCESS] Clave 'FARMA_CEFTRIAXONA' guardada en tabla KV 'farmacia_stock'.
```

##### [READ] Consulta y Estado:
```text
admin@jettra-cluster:primary> KV GET cache_camas cama_402
[KV] OCUPADA | Paciente: pac_01 | Oxigeno: SI

admin@jettra-cluster:primary> KV GET farmacia_stock FARMA_CEFTRIAXONA
[KV] STOCK: 320 viales | Lote: L2026-AB

admin@jettra-cluster:primary> SHOW RECORDS cache_camas
=== REGISTROS DE KEYVALUE BUCKET 'cache_camas' (Mostrando 3 de 3) ===
  [01] Clave: cama_402             -> Valor: OCUPADA | Paciente: pac_01 | Oxigeno: SI
  [02] Clave: cama_305             -> Valor: OCUPADA | Paciente: pac_02 | Oxigeno: NO
  [03] Clave: cama_408             -> Valor: OCUPADA | Paciente: pac_03 | UCI-Avanzada
```

##### [UPDATE] Liberación y Desinfección de Cama:
```text
admin@jettra-cluster:primary> KV PUT cache_camas cama_402 "DISPONIBLE | Desinfectada con Luz UV"
[SUCCESS] Clave 'cama_402' guardada en tabla KV 'cache_camas'.

admin@jettra-cluster:primary> KV GET cache_camas cama_402
[KV] DISPONIBLE | Desinfectada con Luz UV
```

---

#### 🧬 3.3 Motor Vectorial (`sintomas_ia` - Triaje Médico con IA)

Indexamos representaciones vectoriales 3D generadas por modelos clínicos para diagnosticar la similitud entre la sintomatología de un paciente y cuadros clínicos conocidos:

##### [CREATE] Indexación de Embeddings de Síntomas:
```text
-- Vector para afección respiratoria severa (Disnea, Hipoxia, Fiebre):
admin@jettra-cluster:primary> VECTOR INDEX sintomas_ia emb_respiratorio_grave [0.93, 0.88, 0.15]
[SUCCESS] Vector 'emb_respiratorio_grave' indexado en 'sintomas_ia' (3 dimensiones).

-- Vector para síndrome coronario agudo (Dolor precordial, Diaforesis):
admin@jettra-cluster:primary> VECTOR INDEX sintomas_ia emb_coronario_agudo [0.12, 0.95, 0.91]
[SUCCESS] Vector 'emb_coronario_agudo' indexado en 'sintomas_ia' (3 dimensiones).

-- Vector para cuadro gastrointestinal infeccioso:
admin@jettra-cluster:primary> VECTOR INDEX sintomas_ia emb_gastro_infeccioso [0.45, 0.20, 0.82]
[SUCCESS] Vector 'emb_gastro_infeccioso' indexado en 'sintomas_ia' (3 dimensiones).
```

##### [READ] Búsqueda por Similaridad Coseno (k-NN) ante un Nuevo Ingreso:
Llega un paciente con sintomatología `[0.90, 0.82, 0.18]`. El motor calcula la similitud con las firmas clínicas indexadas:

```text
admin@jettra-cluster:primary> VECTOR SEARCH sintomas_ia [0.90, 0.82, 0.18] K 2
=== VECTOR COSINE EN 'sintomas_ia' (k=2) ===
  [01] Vector ID: emb_respiratorio_grave | Similaridad: 0.9984
  [02] Vector ID: emb_gastro_infeccioso  | Similaridad: 0.6210
```
*Diagnóstico Predictivo Inmediato:* La similitud del 99.84% clasifica prioritariamente al paciente hacia el protocolo de Neumología / Soporte Respiratorio.

##### [UPDATE] Calibración Fina del Vector:
```text
admin@jettra-cluster:primary> VECTOR INDEX sintomas_ia emb_respiratorio_grave [0.95, 0.89, 0.12]
[SUCCESS] Vector 'emb_respiratorio_grave' indexado en 'sintomas_ia' (3 dimensiones).
```

---

#### 🕸️ 3.4 Motor de Grafos (`red_hospital` - Relaciones Médicas y Logísticas)

Permite modelar la topología del hospital: asignación de médicos tratantes, interconsultas, camas y traslados.

##### [CREATE] Construcción de Nodos y Conexiones Clínicas:
```text
-- Creación de nodos:
admin@jettra-cluster:primary> GRAPH ADD VERTEX red_hospital doc_101
[SUCCESS] Vértice 'doc_101' agregado al grafo 'red_hospital'.

admin@jettra-cluster:primary> GRAPH ADD VERTEX red_hospital doc_102
[SUCCESS] Vértice 'doc_102' agregado al grafo 'red_hospital'.

admin@jettra-cluster:primary> GRAPH ADD VERTEX red_hospital pac_01
[SUCCESS] Vértice 'pac_01' agregado al grafo 'red_hospital'.

admin@jettra-cluster:primary> GRAPH ADD VERTEX red_hospital pac_02
[SUCCESS] Vértice 'pac_02' agregado al grafo 'red_hospital'.

admin@jettra-cluster:primary> GRAPH ADD VERTEX red_hospital sala_uci_central
[SUCCESS] Vértice 'sala_uci_central' agregado al grafo 'red_hospital'.

-- Creación de aristas dirigidas y ponderadas:
admin@jettra-cluster:primary> GRAPH ADD EDGE red_hospital doc_101 pac_01 LABEL TRATA_A WEIGHT 1.0
[SUCCESS] Arista (doc_101)-[TRATA_A, w=1.0]->(pac_01) agregada en grafo 'red_hospital'.

admin@jettra-cluster:primary> GRAPH ADD EDGE red_hospital pac_01 sala_uci_central LABEL INTERNADO_EN WEIGHT 0.95
[SUCCESS] Arista (pac_01)-[INTERNADO_EN, w=1.0]->(sala_uci_central) agregada en grafo 'red_hospital'.

admin@jettra-cluster:primary> GRAPH ADD EDGE red_hospital doc_102 pac_01 LABEL INTERCONSULTA WEIGHT 0.70
[SUCCESS] Arista (doc_102)-[INTERCONSULTA, w=0.7]->(pac_01) agregada en grafo 'red_hospital'.
```

##### [READ] Navegación de Aristas Salientes:
```text
admin@jettra-cluster:primary> GRAPH GET EDGES red_hospital doc_101
=== ARISTAS SALIENTES DESDE 'doc_101' EN 'red_hospital' ===
  [01] -> Destino: pac_01          | Relación: TRATA_A         | Props: {weight=1.0}

admin@jettra-cluster:primary> GRAPH GET EDGES red_hospital pac_01
=== ARISTAS SALIENTES DESDE 'pac_01' EN 'red_hospital' ===
  [01] -> Destino: sala_uci_central | Relación: INTERNADO_EN    | Props: {weight=0.95}
```

##### [UPDATE] Modificación de Asignación por Alta o Traslado:
```text
admin@jettra-cluster:primary> GRAPH ADD VERTEX red_hospital pabellon_recuperacion
[SUCCESS] Vértice 'pabellon_recuperacion' agregado al grafo 'red_hospital'.

admin@jettra-cluster:primary> GRAPH ADD EDGE red_hospital pac_01 pabellon_recuperacion LABEL TRASLADADO_A WEIGHT 0.85
[SUCCESS] Arista (pac_01)-[TRASLADADO_A, w=0.8]->(pabellon_recuperacion) agregada en grafo 'red_hospital'.
```

---

#### 📈 3.5 Motor de Series Temporales (`signos_vitales` - Monitoreo de UCI)

Ingesta append-only de lecturas de frecuencia cardíaca (lpm) del paciente `pac_01` en estado crítico:

##### [CREATE] Ingesta de Telemetría Cardíaca:
```text
admin@jettra-cluster:primary> TS RECORD signos_vitales 72.0 TIME 1759161000
[SUCCESS] Métrica (timestamp=1759161000, valor=72.00) registrada en 'signos_vitales'.

admin@jettra-cluster:primary> TS RECORD signos_vitales 78.5 TIME 1759161060
[SUCCESS] Métrica (timestamp=1759161060, valor=78.50) registrada en 'signos_vitales'.

admin@jettra-cluster:primary> TS RECORD signos_vitales 95.0 TIME 1759161120
[SUCCESS] Métrica (timestamp=1759161120, valor=95.00) registrada en 'signos_vitales'.

admin@jettra-cluster:primary> TS RECORD signos_vitales 128.4 TIME 1759161180
[SUCCESS] Métrica (timestamp=1759161180, valor=128.40) registrada en 'signos_vitales'.

admin@jettra-cluster:primary> TS RECORD signos_vitales 82.0 TIME 1759161240
[SUCCESS] Métrica (timestamp=1759161240, valor=82.00) registrada en 'signos_vitales'.
```

##### [READ] Detección de Episodios Críticos mediante Consulta de Rango Temporal:
```text
admin@jettra-cluster:primary> TS RANGE signos_vitales 1759161000 1759161240
=== TIME SERIES EN 'signos_vitales' [1759161000 a 1759161240] ===
  [01] Timestamp: 1759161000 | Valor: 72.0000
  [02] Timestamp: 1759161060 | Valor: 78.5000
  [03] Timestamp: 1759161120 | Valor: 95.0000
  [04] Timestamp: 1759161180 | Valor: 128.4000   <-- Taquicardia paroxística detectada
  [05] Timestamp: 1759161240 | Valor: 82.0000

admin@jettra-cluster:primary> COUNT signos_vitales
[COUNT] [TIMESERIES] 'signos_vitales': 5 punto(s).
```

---

### Fase 4: Referencias Cruzadas Multimodelo (`JettraRef`) y Lazy Loading

Vinculamos de forma explícita punteros inter-motor desde el registro del paciente hacia los otros motores especializados:

```text
-- 1. Vincular puntero hacia la firma de síntomas de IA (Vector):
admin@jettra-cluster:primary> INSERT REF pacientes pac_01 KEY _ref_ia TARGET vector::sintomas_ia#emb_respiratorio_grave
[SUCCESS] Referencia JettraRef '_ref_ia' vinculada en 'pacientes'[pac_01] -> 'vector::sintomas_ia#emb_respiratorio_grave'.

-- 2. Vincular puntero hacia el nodo del médico en el grafo:
admin@jettra-cluster:primary> INSERT REF pacientes pac_01 KEY _ref_grafo TARGET graph::red_hospital#doc_101
[SUCCESS] Referencia JettraRef '_ref_grafo' vinculada en 'pacientes'[pac_01] -> 'graph::red_hospital#doc_101'.

-- 3. Inspeccionar y resolver en vivo las referencias cruzadas:
admin@jettra-cluster:primary> SHOW REFS pacientes pac_01
=== REFERENCIAS CRUZADAS PARA [pac_01] EN 'pacientes' ===
  [01] Campo: _ref_medico        -> document::medicos#doc_101
       ↳ Resolución (Lazy On-Demand): Documento {_id=doc_101, nombre=Dr. Fernando Ruiz, especialidad=Cardiologia}
  [02] Campo: _ref_sintoma       -> vector::sintomas_ia#emb_respiratorio_grave
       ↳ Resolución (Lazy On-Demand): Vector [0.95, 0.89, 0.12]
  [03] Campo: _ref_cama          -> kv::cache_camas#cama_402
       ↳ Resolución (Lazy On-Demand): KV Valor: DISPONIBLE | Desinfectada con Luz UV
  [04] Campo: _ref_ia            -> vector::sintomas_ia#emb_respiratorio_grave
       ↳ Resolución (Lazy On-Demand): Vector [0.95, 0.89, 0.12]
  [05] Campo: _ref_grafo         -> graph::red_hospital#doc_101
       ↳ Resolución (Lazy On-Demand): Vértice de Grafo 'doc_101' (1 aristas conectadas)

-- 4. Resolución puntual manual:
admin@jettra-cluster:primary> RESOLVE REF kv::cache_camas#cama_408
[JettraRef Resolución]: kv::cache_camas#cama_408 -> KV Valor: OCUPADA | Paciente: pac_03 | UCI-Avanzada
```

---

### Fase 5: Administración de Índices Secundarios (BTREE y HASH)

Para acelerar búsquedas críticas por edad del paciente o por estado clínico:

```text
-- 1. Crear índice B-Tree sobre la edad para consultas de rango:
admin@jettra-cluster:primary> CREATE INDEX idx_pacientes_edad ON pacientes (edad) TYPE BTREE
[SUCCESS] Índice 'idx_pacientes_edad' creado sobre 'pacientes'(edad) tipo BTREE (Entradas indexadas: 3).

-- 2. Crear índice Hash sobre el estado para búsquedas de igualdad exacta:
admin@jettra-cluster:primary> CREATE INDEX idx_pacientes_estado ON pacientes (estado) TYPE HASH
[SUCCESS] Índice 'idx_pacientes_estado' creado sobre 'pacientes'(estado) tipo HASH (Entradas indexadas: 3).

-- 3. Listar los índices activos:
admin@jettra-cluster:primary> SHOW INDEXES ON pacientes
=== ÍNDICES DE BASE DE DATOS: 'hospital_db' ===
+----------------------+----------------------+----------------------+--------+--------+----------+
| Nombre de Índice     | Colección            | Campo Indexado       | Tipo   | Único  | Entradas |
+----------------------+----------------------+----------------------+--------+--------+----------+
| idx_pacientes_edad   | pacientes            | edad                 | BTREE  | NO     | 3        |
| idx_pacientes_estado | pacientes            | estado               | HASH   | NO     | 3        |
+----------------------+----------------------+----------------------+--------+--------+----------+

-- 4. Reconstruir índice secundario tras inserciones masivas:
admin@jettra-cluster:primary> ALTER INDEX idx_pacientes_edad REBUILD
[SUCCESS] Índice 'idx_pacientes_edad' reconstruido exitosamente. Total entradas indexadas: 3.

-- 5. Eliminar un índice en desuso:
admin@jettra-cluster:primary> DROP INDEX idx_pacientes_estado
[SUCCESS] Índice 'idx_pacientes_estado' eliminado correctamente.
```

---

### Fase 6: Paginación Interactiva de Consultas

Configuramos un tamaño de página de 2 registros para observar la paginación interactiva del shell:

```text
admin@jettra-cluster:primary> PAGE_SIZE 2
[PAGINACIÓN] Tamaño de página configurado a 2 registros para futuras consultas.

admin@jettra-cluster:primary> SELECT _id, nombre, edad, habitacion FROM pacientes
=== JETTRASQL RESULTADO (0 ms) ===
+--------------+----------------------+--------------+----------------+
| _id          | nombre               | edad         | habitacion     |
+--------------+----------------------+--------------+----------------+
| pac_01       | Ernesto Valdes       | 54           | 301            |
| pac_02       | Lucia Santamaria     | 38           | 305            |
... y 1 fila(s) más. Use LIMIT o aumente PAGE_SIZE para ver más.
+--------------+----------------------+--------------+----------------+
Total: 3 fila(s) seleccionadas / afectadas.
+------------------------------------------------------------------------------------------------------+
|  PÁGINA [ 1 / 2 ]  •  Mostrando filas 1 - 2 de 3 total  •  Tamaño de página: 2                       |
|  Navegación: [P]RIMERO (|<<)  •  [A]NTERIOR (<)  •  [S]IGUIENTE (>)  •  [U]LTIMO (>>|)                 |
|  Comandos: 'SIGUIENTE' | 'ANTERIOR' | 'PRIMERO' | 'ULTIMO' | 'PAGE <n>' | 'PAGE_SIZE <n>'           |
+------------------------------------------------------------------------------------------------------+

-- Avanzar a la siguiente página:
admin@jettra-cluster:primary> NEXT
=== JETTRASQL RESULTADO (0 ms) ===
+--------------+----------------------+--------------+----------------+
| _id          | nombre               | edad         | habitacion     |
+--------------+----------------------+--------------+----------------+
| pac_03       | Manuel Coronado      | 72           | 408            |
+--------------+----------------------+--------------+----------------+
Total: 1 fila(s) seleccionadas / afectadas.
+------------------------------------------------------------------------------------------------------+
|  PÁGINA [ 2 / 2 ]  •  Mostrando filas 3 - 3 de 3 total  •  Tamaño de página: 2                       |
|  Navegación: [P]RIMERO (|<<)  •  [A]NTERIOR (<)  •  [S]IGUIENTE (>)  •  [U]LTIMO (>>|)                 |
+------------------------------------------------------------------------------------------------------+
```

---

### Fase 7: Censo Total y Auditoría Global de la Base de Datos

```text
admin@jettra-cluster:primary> COUNT ALL
=== CONTEO TOTAL DE REGISTROS EN BASE DE DATOS: 'hospital_db' ===
  * [DOCUMENT]   pacientes              : 3 registro(s)
  * [DOCUMENT]   medicos                : 2 registro(s)
  * [VECTOR]     sintomas_ia            : 3 vector(es)
  * [GRAPH]      red_hospital           : 6 vértice(s)
  * [TIMESERIES] signos_vitales         : 5 punto(s)
  * [KEYVALUE]   cache_camas            : 3 clave(s)
  * [KEYVALUE]   farmacia_stock         : 2 clave(s)
  * [GEOSPATIAL] ubicacion_centros      : 0 coordenadas
  * [COLUMNAR]   analitica_costos       : 0 filas
  * [RECORDS]    (Sin registros tipados): 0 objetos
Gran Total en 'hospital_db': 24 registro(s) multimodelo.

admin@jettra-cluster:primary> DB STATS
=== ESTADÍSTICAS DE BASE DE DATOS: 'hospital_db' ===
- Colecciones Totales: 9
- Documentos:          [pacientes, medicos]
- Clave-Valor (KV):    [cache_camas, farmacia_stock]
- Vectores:            [sintomas_ia]
- Grafos:              [red_hospital]
- Series Temporales:   [signos_vitales]
- Geoespacial (GIS):   [ubicacion_centros]
- Columnar (OLAP):     [analitica_costos]
- Java Records:        []
- Índices Secundarios: 1
- MemTable Utilizada:  1.42 KB
```

---

### Fase 8: Respaldo en Caliente, Limpieza y Eliminación de Unidades

```text
-- 1. Generar snapshot físico completo con verificación CRC32:
admin@jettra-cluster:primary> BACKUP DATABASE hospital_db TO './data/jettra/hospital_db_snapshot.snap'
[SUCCESS] Snapshot físico de 'hospital_db' generado exitosamente en './data/jettra/hospital_db_snapshot.snap'.

-- 2. Eliminar una unidad/motor que ya no es necesaria:
admin@jettra-cluster:primary> DROP ENGINE farmacia_stock
[SUCCESS] Unidad/Colección/Motor 'farmacia_stock' eliminado.

-- 3. Verificar que la unidad fue removida del registro de buckets:
admin@jettra-cluster:primary> SHOW BUCKETS
(El bucket 'farmacia_stock' ya no figura en la lista de unidades)

-- 4. Para eliminar la base de datos completa, conmutar a otra sesión y ejecutar DROP:
admin@jettra-cluster:primary> USE default_db
[SUCCESS] Conmutado a base de datos activa: 'default_db'.

admin@jettra-cluster:primary> DROP DATABASE hospital_db
[SUCCESS] Base de datos 'hospital_db' eliminada del clúster y disco.

-- 5. Restauración instantánea desde el snapshot:
admin@jettra-cluster:primary> RESTORE DATABASE hospital_db FROM './data/jettra/hospital_db_snapshot.snap'
[SUCCESS] Base de datos 'hospital_db' restaurada exitosamente desde './data/jettra/hospital_db_snapshot.snap'.

admin@jettra-cluster:primary> USE hospital_db
[SUCCESS] Conmutado a base de datos activa: 'hospital_db'.

admin@jettra-cluster:primary> COUNT ALL
=== CONTEO TOTAL DE REGISTROS EN BASE DE DATOS: 'hospital_db' ===
(Todos los 24 registros y motores restaurados al 100%)
```

---

---

## 15. Ejemplo Práctico Multimodelo Completo 2: Órdenes de Compra, Facturación y Servicios Técnicos (`orders_billing_db`)

### 15.1 Visión del Dominio y Modelo de Datos Multimodal

Para ilustrar las capacidades completas del motor políglota de **`JettraStore`**, este caso de uso modela un ecosistema integral de aprovisionamiento corporativo, logística, asignación de tareas técnicas en campo y facturación electrónica fiscal:

1. **Empresas (`empresas`)**: Entidades matrices, filiales proveedoras y distribuidores de repuestos de tecnología y energía.
2. **Clientes (`clientes`)**: Organizaciones y clientes corporativos con Registro Único de Contribuyente (RUC/NIT) que emiten solicitudes de compra y contratos de servicio.
3. **Productos (`productos`)**: Catálogo maestro que incluye repuestos de hardware, piezas mecánicas especializadas y paquetes de servicios de ingeniería.
4. **Técnicos que realizan las tareas (`tecnicos`)**: Ficha de ingenieros y especialistas de campo certificados, cuadrillas de guardia, niveles de competencia y herramientas móviles.
5. **Órdenes de Compra (`ordenes_compra`)**: Documentos contractuales que consolidan los ítems solicitados por el cliente, la empresa emisora, los montos aprobados y el técnico designado para la ejecución de la tarea en campo.
6. **Facturación Fiscal (`facturas`)**: Facturas electrónicas oficiales timbradas y ligadas a las órdenes de compra.

#### Matriz de Motores Multimodelo en `orders_billing_db`:
| Motor | Unidad / Bucket | Propósito en el Dominio |
| :--- | :--- | :--- |
| **`DOCUMENT`** | `empresas`, `clientes`, `productos`, `tecnicos`, `ordenes_compra`, `facturas` | Almacenamiento JSON con validación de esquemas e índices secundarios Hash y B-Tree. |
| **`KEYVALUE (KV)`** | `cache_tecnicos_disponibles`, `cache_folios_fiscales` | Estado volátil ultra veloz: disponibilidad del técnico y control atómico de folios consecutivos de facturación. |
| **`VECTOR`** | `vector_catalogo_repuestos`, `vector_diagnosticos_tareas` | Embeddings multidimensionales para recomendación semántica de piezas y emparejamiento predictivo de técnicos según la avería. |
| **`GRAPH`** | `red_servicios_facturacion` | Grafo dirigido con relaciones `EMITE`, `SOLICITA`, `ASIGNADO_A`, `CONTIENE` y `FACTURADA_EN`. |
| **`TIMESERIES`** | `telemetria_tareas_campo`, `flujo_facturacion_horario` | Trazas append-only del progreso del técnico minuto a minuto y métricas de facturación acumulada. |
| **`GEOSPATIAL (GIS)`** | `sedes_empresas_clientes`, `gps_tecnicos_campo` | Coordenadas geoespaciales indexadas con R-Tree y despacho del técnico más cercano mediante `GEO RADIUS`. |
| **`COLUMNAR (OLAP)`** | `analitica_facturacion_olap` | Almacenamiento por columnas para agregaciones contables instantáneas (totales, IVA/ITBMS, márgenes). |
| **`JAVA RECORDS`** | `auditoria_facturacion_records` | Clases `record` inmutables de Java 25 con sellado criptográfico SHA-256 para auditoría fiscal. |

---

### 15.2 Fase 1: Creación de Base de Datos y Aprovisionamiento de Unidades

```text
admin@jettra-cluster:primary> CREATE DATABASE orders_billing_db
[SUCCESS] Base de datos 'orders_billing_db' creada exitosamente.

admin@jettra-cluster:primary> USE orders_billing_db
[SUCCESS] Conmutado a base de datos activa: 'orders_billing_db'.
```

A continuación aprovisionamos los buckets especializados para cada uno de los motores del sistema:

```text
-- 1. Unidades del Motor Documental
admin@jettra-cluster:primary> CREATE COLLECTION empresas
admin@jettra-cluster:primary> CREATE COLLECTION clientes
admin@jettra-cluster:primary> CREATE COLLECTION productos
admin@jettra-cluster:primary> CREATE COLLECTION tecnicos
admin@jettra-cluster:primary> CREATE COLLECTION ordenes_compra
admin@jettra-cluster:primary> CREATE COLLECTION facturas

-- 2. Unidades de Motores Especializados
admin@jettra-cluster:primary> CREATE BUCKET cache_tecnicos_disponibles TYPE KEYVALUE
admin@jettra-cluster:primary> CREATE BUCKET cache_folios_fiscales TYPE KEYVALUE
admin@jettra-cluster:primary> CREATE BUCKET vector_catalogo_repuestos TYPE VECTOR
admin@jettra-cluster:primary> CREATE BUCKET vector_diagnosticos_tareas TYPE VECTOR
admin@jettra-cluster:primary> CREATE BUCKET red_servicios_facturacion TYPE GRAPH
admin@jettra-cluster:primary> CREATE BUCKET telemetria_tareas_campo TYPE TIMESERIES
admin@jettra-cluster:primary> CREATE BUCKET flujo_facturacion_horario TYPE TIMESERIES
admin@jettra-cluster:primary> CREATE BUCKET sedes_empresas_clientes TYPE GEOSPATIAL
admin@jettra-cluster:primary> CREATE BUCKET gps_tecnicos_campo TYPE GEOSPATIAL
admin@jettra-cluster:primary> CREATE BUCKET analitica_facturacion_olap TYPE COLUMNAR
admin@jettra-cluster:primary> CREATE BUCKET auditoria_facturacion_records TYPE RECORDS
```

---

### 15.3 Fase 2: Inserción de Documentos Maestros (Empresas, Clientes, Productos, Técnicos, Órdenes y Facturas)

#### 1. Empresas Proveedoras y Matrices (`empresas`):
```text
admin@jettra-cluster:primary> INSERT INTO empresas ID emp_01 JSON {"nombre": "Jettra Industrial Corp", "ruc": "1559821-1-654920", "ciudad": "Panama", "telefono": "+507 200-8000", "categoria": "Proveedor Matriz"}
[SUCCESS] Documento [emp_01] insertado exitosamente en 'empresas'.

admin@jettra-cluster:primary> INSERT INTO empresas ID emp_02 JSON {"nombre": "Soluciones Globales de Hardware S.A.", "ruc": "2498112-2-819230", "ciudad": "Colon", "telefono": "+507 441-9200", "categoria": "Distribuidor Mayorista"}
[SUCCESS] Documento [emp_02] insertado exitosamente en 'empresas'.
```

#### 2. Clientes Corporativos (`clientes`):
```text
admin@jettra-cluster:primary> INSERT INTO clientes ID cli_101 JSON {"razon_social": "Corporacion Hotelera del Mar", "ruc": "981203-1-49210", "tipo": "CORPORATIVO", "contacto": "Lic. Mariana Rios", "email": "compras@hotelmar.com", "limite_credito": 50000.0}
[SUCCESS] Documento [cli_101] insertado exitosamente en 'clientes'.

admin@jettra-cluster:primary> INSERT INTO clientes ID cli_102 JSON {"razon_social": "Financiera del Istmo S.A.", "ruc": "1049218-1-58201", "tipo": "FINANCIERO", "contacto": "Ing. Roberto Solis", "email": "infraestructura@istmofin.com", "limite_credito": 120000.0}
[SUCCESS] Documento [cli_102] insertado exitosamente en 'clientes'.
```

#### 3. Catálogo de Productos, Repuestos y Servicios (`productos`):
```text
admin@jettra-cluster:primary> INSERT INTO productos ID prod_01 JSON {"sku": "SRV-RACK-2U", "nombre": "Servidor Rack 2U Enterprise Xeon 64C", "categoria": "Hardware", "precio_unitario": 4850.0, "stock": 14}
[SUCCESS] Documento [prod_01] insertado exitosamente en 'productos'.

admin@jettra-cluster:primary> INSERT INTO productos ID prod_02 JSON {"sku": "PWR-HS-800W", "nombre": "Fuente de Poder Redundante 800W Hot-Swap", "categoria": "Repuestos", "precio_unitario": 320.0, "stock": 45}
[SUCCESS] Documento [prod_02] insertado exitosamente en 'productos'.

admin@jettra-cluster:primary> INSERT INTO productos ID prod_03 JSON {"sku": "MEM-ECC-64GB", "nombre": "Modulo Memoria 64GB ECC DDR5 Registered", "categoria": "Repuestos", "precio_unitario": 280.0, "stock": 80}
[SUCCESS] Documento [prod_03] insertado exitosamente en 'productos'.

admin@jettra-cluster:primary> INSERT INTO productos ID prod_04 JSON {"sku": "SRV-INST-CRIT", "nombre": "Servicio de Instalacion y Reemplazo Critico 24/7", "categoria": "Servicios", "precio_unitario": 450.0, "duracion_horas": 4}
[SUCCESS] Documento [prod_04] insertado exitosamente en 'productos'.
```

#### 4. Técnicos Especialistas para Tareas en Campo (`tecnicos`):
```text
admin@jettra-cluster:primary> INSERT INTO tecnicos ID tec_701 JSON {"nombre": "Ing. Marcos Rivera", "cedula": "8-745-1204", "especialidad": "Servidores Blade y Alta Disponibilidad", "cuadrilla": "Alfa-1", "nivel": "SENIOR_L3", "telefono": "+507 6201-9988"}
[SUCCESS] Documento [tec_701] insertado exitosamente en 'tecnicos'.

admin@jettra-cluster:primary> INSERT INTO tecnicos ID tec_702 JSON {"nombre": "Ing. Sofia Morales", "cedula": "4-812-901", "especialidad": "Redes, Fibra Optica y Energia Redundante", "cuadrilla": "Beta-2", "nivel": "SPECIALIST_L2", "telefono": "+507 6590-4411"}
[SUCCESS] Documento [tec_702] insertado exitosamente en 'tecnicos'.
```

#### 5. Órdenes de Compra y Ejecución de Tareas (`ordenes_compra`):
```text
admin@jettra-cluster:primary> INSERT INTO ordenes_compra ID ord_5001 JSON {"numero_orden": "OC-2026-005001", "id_empresa": "emp_01", "id_cliente": "cli_101", "id_tecnico": "tec_701", "fecha_emision": "2026-10-28", "estado": "APROBADA", "tipo_tarea": "MANTENIMIENTO_CRITICO_DATACENTER", "subtotal": 5620.0, "itbms": 393.4, "total": 6013.4, "items": [{"prod_id": "prod_01", "cant": 1, "precio": 4850.0}, {"prod_id": "prod_02", "cant": 1, "precio": 320.0}, {"prod_id": "prod_04", "cant": 1, "precio": 450.0}]}
[SUCCESS] Documento [ord_5001] insertado exitosamente en 'ordenes_compra'.
```

#### 6. Factura Fiscal Electrónica (`facturas`):
```text
admin@jettra-cluster:primary> INSERT INTO facturas ID fac_8001 JSON {"numero_factura": "FAC-2026-000101", "id_orden": "ord_5001", "id_empresa": "emp_01", "id_cliente": "cli_101", "id_tecnico": "tec_701", "fecha_timbrado": "2026-10-28T10:15:00", "cufe": "CUFE-SHA256-992182049AAB881", "monto_bruto": 5620.0, "descuento": 100.0, "subtotal_neto": 5520.0, "impuesto_itbms": 386.4, "monto_total": 5906.4, "estado": "TIMBRADA_PAGADA"}
[SUCCESS] Documento [fac_8001] insertado exitosamente en 'facturas'.
```

---

### 15.4 Fase 3: Motor Clave-Valor (KV) para Disponibilidad de Técnicos y Folios Fiscales

El motor de acceso en memoria ultrarrápida gestiona estados altamente volátiles que requieren latencias sub-milisegundo:

```text
-- 1. Disponibilidad del personal técnico en tiempo real:
admin@jettra-cluster:primary> KV PUT cache_tecnicos_disponibles tec_701 "EN_TAREA_CAMPO | Orden: ord_5001 | Cliente: Hotel del Mar"
[OK] Clave-Valor almacenado en 'cache_tecnicos_disponibles'[tec_701].

admin@jettra-cluster:primary> KV PUT cache_tecnicos_disponibles tec_702 "DISPONIBLE_STANDBY | Base Operativa Panama Pacifico"
[OK] Clave-Valor almacenado en 'cache_tecnicos_disponibles'[tec_702].

-- 2. Folios fiscales consecutivos para garantizar unicidad y cero colisiones concurrentes:
admin@jettra-cluster:primary> KV PUT cache_folios_fiscales folio_ultimo_emp_01 "FAC-2026-000101"
[OK] Clave-Valor almacenado en 'cache_folios_fiscales'[folio_ultimo_emp_01].

-- 3. Consulta de estado instantáneo:
admin@jettra-cluster:primary> KV GET cache_tecnicos_disponibles tec_701
[KV GET] cache_tecnicos_disponibles[tec_701] = "EN_TAREA_CAMPO | Orden: ord_5001 | Cliente: Hotel del Mar"
```

---

### 15.5 Fase 4: Motor Vectorial (VECTOR) para Diagnóstico Predictivo y Búsqueda de Piezas

Indexamos vectores numéricos normalizados para resolver dos problemas de IA en operaciones:
1. **Búsqueda por similitud semántica de repuestos compatibles.**
2. **Diagnóstico predictivo de averías para emparejar la falla técnica con el especialista adecuado.**

```text
-- 1. Indexar vector de especificaciones de hardware (Dimensiones: Potencia, Confiabilidad, Nivel de Carga):
admin@jettra-cluster:primary> VECTOR INDEX vector_catalogo_repuestos prod_02 [0.92, 0.88, 0.45]
[SUCCESS] Vector [prod_02] indexado en 'vector_catalogo_repuestos'.

admin@jettra-cluster:primary> VECTOR INDEX vector_catalogo_repuestos prod_03 [0.85, 0.95, 0.60]
[SUCCESS] Vector [prod_03] indexado en 'vector_catalogo_repuestos'.

-- 2. Indexar vector del diagnóstico técnico de la orden ord_5001:
-- [0.95 (Servidor Caído), 0.89 (Falla Fuente Hot-Swap), 0.12 (Red)]
admin@jettra-cluster:primary> VECTOR INDEX vector_diagnosticos_tareas ord_5001 [0.95, 0.89, 0.12]
[SUCCESS] Vector [ord_5001] indexado en 'vector_diagnosticos_tareas'.

-- 3. Calcular la similitud de coseno entre el diagnóstico de la orden y el perfil de habilidad del técnico Marcos Rivera:
-- Vector de perfil de Marcos Rivera: [0.94, 0.91, 0.15]
admin@jettra-cluster:primary> VECTOR COSINE [0.95, 0.89, 0.12] [0.94, 0.91, 0.15]
=== OPERACIÓN VECTORIAL (VECTOR) ===
+-------------------------------------------------------------+
| OPERACIÓN: COSINE                                           |
| Vector A:  [0.95, 0.89, 0.12]                              |
| Vector B:  [0.94, 0.91, 0.15]                              |
+-------------------------------------------------------------+
| RESULTADO SIMILITUD COSENO: 0.9984                          |
| INTERPRETACIÓN: Coincidencia óptima (99.8%) para asignación |
+-------------------------------------------------------------+
```

---

### 15.6 Fase 5: Motor de Grafos (GRAPH) para Trazabilidad Integral de Órdenes y Servicios

La red de grafos vincula todos los vértices del ecosistema corporativo mediante aristas dirigidas y ponderadas:

```text
-- 1. Registrar vértices principales en la red:
admin@jettra-cluster:primary> GRAPH ADD VERTEX red_servicios_facturacion emp_01
admin@jettra-cluster:primary> GRAPH ADD VERTEX red_servicios_facturacion cli_101
admin@jettra-cluster:primary> GRAPH ADD VERTEX red_servicios_facturacion tec_701
admin@jettra-cluster:primary> GRAPH ADD VERTEX red_servicios_facturacion prod_01
admin@jettra-cluster:primary> GRAPH ADD VERTEX red_servicios_facturacion ord_5001
admin@jettra-cluster:primary> GRAPH ADD VERTEX red_servicios_facturacion fac_8001

-- 2. Trazar relaciones dirigidas (Edges):
-- La empresa emite la orden de compra:
admin@jettra-cluster:primary> GRAPH ADD EDGE red_servicios_facturacion emp_01 ord_5001 LABEL EMITE_A
-- La orden es solicitada por el cliente:
admin@jettra-cluster:primary> GRAPH ADD EDGE red_servicios_facturacion ord_5001 cli_101 LABEL PARA_CLIENTE
-- La orden asigna la tarea técnica a un técnico específico:
admin@jettra-cluster:primary> GRAPH ADD EDGE red_servicios_facturacion ord_5001 tec_701 LABEL ASIGNADA_A
-- La orden incluye el producto instalado:
admin@jettra-cluster:primary> GRAPH ADD EDGE red_servicios_facturacion ord_5001 prod_01 LABEL CONTIENE_PRODUCTO
-- La orden se liquida mediante la factura fiscal:
admin@jettra-cluster:primary> GRAPH ADD EDGE red_servicios_facturacion ord_5001 fac_8001 LABEL LIQUIDADA_EN

-- 3. Consulta de adyacencias y recorrido del grafo:
admin@jettra-cluster:primary> GRAPH GET EDGES red_servicios_facturacion ord_5001
=== GRAFO: ARISTAS CONECTADAS A [ord_5001] ===
  -> [cli_101] (Etiqueta: PARA_CLIENTE, Peso: 1.0)
  -> [tec_701] (Etiqueta: ASIGNADA_A, Peso: 1.0)
  -> [prod_01] (Etiqueta: CONTIENE_PRODUCTO, Peso: 1.0)
  -> [fac_8001] (Etiqueta: LIQUIDADA_EN, Peso: 1.0)

-- 4. Búsqueda de camino con JettraQL Pattern Matching:
admin@jettra-cluster:primary> JQL MATCH (ord_5001)-[ASIGNADA_A]->(tec_701) IN red_servicios_facturacion
=== JETTRAQL [MATCH] ===
Coincidencia de patrón encontrada en grafo 'red_servicios_facturacion':
  (ord_5001:Orden) ──[ASIGNADA_A]──> (tec_701:Tecnico)
```

---

### 15.7 Fase 6: Motor de Series Temporales (TIMESERIES) para Telemetría de Campo

Permite registrar cronológicamente la evolución de la tarea del técnico en campo y el acumulado de ingresos por hora:

```text
-- 1. Telemetría de la tarea técnica (hitos minuto a minuto):
-- Minuto 0: Arribo al datacenter del cliente (valor 1.0 = EN_SITIO)
admin@jettra-cluster:primary> TS RECORD telemetria_tareas_campo 1.0 TIME 1730110800000
-- Minuto 45: Diagnóstico completado y fuente desmontada (valor 2.0 = DIAGNOSTICO_OK)
admin@jettra-cluster:primary> TS RECORD telemetria_tareas_campo 2.0 TIME 1730113500000
-- Minuto 90: Nueva fuente instalada y servidor encendido (valor 3.0 = RECAMBIO_OK)
admin@jettra-cluster:primary> TS RECORD telemetria_tareas_campo 3.0 TIME 1730116200000
-- Minuto 120: Pruebas de estrés superadas y entrega a satisfacción (valor 4.0 = FINALIZADA)
admin@jettra-cluster:primary> TS RECORD telemetria_tareas_campo 4.0 TIME 1730118000000

-- 2. Flujo de facturación horaria (Monto facturado en USD):
admin@jettra-cluster:primary> TS RECORD flujo_facturacion_horario 6013.4 TIME 1730118000000
admin@jettra-cluster:primary> TS RECORD flujo_facturacion_horario 12850.0 TIME 1730121600000

-- 3. Consulta de rango temporal de telemetría de tareas:
admin@jettra-cluster:primary> TS RANGE telemetria_tareas_campo 1730110000000 1730120000000
=== SERIE TEMPORAL: 'telemetria_tareas_campo' ===
  [1730110800000] 1.0000 (Check-in en sitio cliente)
  [1730113500000] 2.0000 (Desmontaje y aislamiento de falla)
  [1730116200000] 3.0000 (Instalación de repuestos y puesta en marcha)
  [1730118000000] 4.0000 (Cierre de orden y conformidad de cliente)
Total: 4 punto(s) temporales registrados.
```

---

### 15.8 Fase 7: Motor Geoespacial (GIS) y Despacho Inteligente de Técnicos en Ruta

Registra coordenadas geográficas (latitud, longitud) y despacha cuadrillas técnicas evaluando proximidad geodésica:

```text
-- 1. Registrar sede del cliente (Hotel del Mar - Bahía de Panamá: Lat 8.9714, Lon -79.5180):
admin@jettra-cluster:primary> GEO INSERT sedes_empresas_clientes cli_101 8.9714 -79.5180
[SUCCESS] Punto GIS [cli_101] insertado en 'sedes_empresas_clientes' (8.971400, -79.518000).

-- 2. Registrar ubicación GPS en tiempo real de los técnicos en campo:
-- Marcos Rivera (tec_701) patrullando en Calle 50: Lat 8.9810, Lon -79.5220
admin@jettra-cluster:primary> GEO INSERT gps_tecnicos_campo tec_701 8.9810 -79.5220
-- Sofía Morales (tec_702) atendiendo en Costa del Este: Lat 9.0150, Lon -79.4680
admin@jettra-cluster:primary> GEO INSERT gps_tecnicos_campo tec_702 9.0150 -79.4680

-- 3. Buscar técnicos disponibles en un radio de 5.0 km alrededor de la sede del cliente cli_101:
admin@jettra-cluster:primary> GEO RADIUS gps_tecnicos_campo 8.9714 -79.5180 5.0
=== BÚSQUEDA GEOESPACIAL: 'gps_tecnicos_campo' (Radio: 5.0 km) ===
  * ID: tec_701 | Distancia Geodésica: 1.15 km | Lat: 8.9810, Lon: -79.5220 [ASIGNACIÓN ÓPTIMA]
Total: 1 técnico(s) dentro del perímetro de respuesta inmediata.
```

---

### 15.9 Fase 8: Motor Columnar (OLAP) para Analítica Fiscal y Márgenes

Permite ejecutar agregaciones financieras masivas sobre arrays de memoria vectorizada sin deserializar documentos:

```text
-- Cargar filas contables en el bucket columnar:
admin@jettra-cluster:primary> COL INSERT analitica_facturacion_olap fac_8001 {"subtotal": 5520.0, "impuesto": 386.4, "total": 5906.4, "costo_piezas": 2800.0, "margen_bruto": 2720.0}
[SUCCESS] Fila columnar [fac_8001] agregada a 'analitica_facturacion_olap'.

-- Inspección vectorizada:
admin@jettra-cluster:primary> COL SCAN analitica_facturacion_olap LIMIT 5
=== ESCANEO COLUMNAR OLAP: 'analitica_facturacion_olap' ===
+------------+------------+------------+------------+---------------+----------------+
| ID         | SUBTOTAL   | IMPUESTO   | TOTAL      | COSTO_PIEZAS  | MARGEN_BRUTO   |
+------------+------------+------------+------------+---------------+----------------+
| fac_8001   | 5520.00    | 386.40     | 5906.40    | 2800.00       | 2720.00        |
+------------+------------+------------+------------+---------------+----------------+
```

---

### 15.10 Fase 9: Java 25 Records para Auditoría Criptográfica Inmutable

El motor de `RECORDS` almacena tipos Java inmutables compilados en Java 25 con memoria zero-copy:

```text
-- Registro de auditoría fiscal con token criptográfico y timestamp UTC:
admin@jettra-cluster:primary> INSERT INTO auditoria_facturacion_records ID audit_fac_8001 JSON {"factura_id": "fac_8001", "orden_id": "ord_5001", "operador": "admin", "cufe_hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "monto_sellado": 5906.4, "verificado": true}
[SUCCESS] Java Record [audit_fac_8001] persistido inmutablemente en 'auditoria_facturacion_records'.
```

---

### 15.11 Fase 10: Punteros Cruzados Multimodelo JettraRef y Resolución Lazy

El sistema de punteros cruzados **`JettraRef`** conecta los motores desacoplados sin duplicar datos en disco:

```text
-- 1. Vincular la orden ord_5001 con el documento del técnico:
admin@jettra-cluster:primary> INSERT REF ordenes_compra ord_5001 KEY _ref_tecnico TARGET document::tecnicos#tec_701
[SUCCESS] Referencia JettraRef '_ref_tecnico' vinculada en 'ordenes_compra'[ord_5001] -> 'document::tecnicos#tec_701'.

-- 2. Vincular la orden con la factura fiscal emitida:
admin@jettra-cluster:primary> INSERT REF ordenes_compra ord_5001 KEY _ref_factura TARGET document::facturas#fac_8001
[SUCCESS] Referencia JettraRef '_ref_factura' vinculada en 'ordenes_compra'[ord_5001] -> 'document::facturas#fac_8001'.

-- 3. Vincular la orden con el vector de diagnóstico de averías:
admin@jettra-cluster:primary> INSERT REF ordenes_compra ord_5001 KEY _ref_ia_vector TARGET vector::vector_diagnosticos_tareas#ord_5001
[SUCCESS] Referencia JettraRef '_ref_ia_vector' vinculada en 'ordenes_compra'[ord_5001] -> 'vector::vector_diagnosticos_tareas#ord_5001'.

-- 4. Vincular la orden con el estado en memoria clave-valor del técnico:
admin@jettra-cluster:primary> INSERT REF ordenes_compra ord_5001 KEY _ref_kv_estado TARGET kv::cache_tecnicos_disponibles#tec_701
[SUCCESS] Referencia JettraRef '_ref_kv_estado' vinculada en 'ordenes_compra'[ord_5001] -> 'kv::cache_tecnicos_disponibles#tec_701'.

-- 5. Inspeccionar y resolver en vivo las referencias cruzadas de la orden de compra:
admin@jettra-cluster:primary> SHOW REFS ordenes_compra ord_5001
=== REFERENCIAS CRUZADAS PARA [ord_5001] EN 'ordenes_compra' ===
  [01] Campo: _ref_tecnico       -> document::tecnicos#tec_701
       ↳ Resolución (Lazy On-Demand): Documento {_id=tec_701, nombre=Ing. Marcos Rivera, especialidad=Servidores Blade y Alta Disponibilidad}
  [02] Campo: _ref_factura       -> document::facturas#fac_8001
       ↳ Resolución (Lazy On-Demand): Documento {_id=fac_8001, numero_factura=FAC-2026-000101, monto_total=5906.4}
  [03] Campo: _ref_ia_vector     -> vector::vector_diagnosticos_tareas#ord_5001
       ↳ Resolución (Lazy On-Demand): Vector [0.95, 0.89, 0.12]
  [04] Campo: _ref_kv_estado     -> kv::cache_tecnicos_disponibles#tec_701
       ↳ Resolución (Lazy On-Demand): KV Valor: "EN_TAREA_CAMPO | Orden: ord_5001 | Cliente: Hotel del Mar"

-- 6. Obtención integral con GET y Lazy Load:
admin@jettra-cluster:primary> GET ordenes_compra ord_5001
{
  "_id": "ord_5001",
  "numero_orden": "OC-2026-005001",
  "total": 6013.4,
  "estado": "APROBADA",
  "_ref_tecnico": "document::tecnicos#tec_701",
  "_ref_factura": "document::facturas#fac_8001",
  "_ref_ia_vector": "vector::vector_diagnosticos_tareas#ord_5001",
  "_ref_kv_estado": "kv::cache_tecnicos_disponibles#tec_701"
}
[JettraRef Resolución]: _ref_tecnico -> Ing. Marcos Rivera (SENIOR_L3)
[JettraRef Resolución]: _ref_factura -> FAC-2026-000101 ($5,906.40)
```

---

### 15.12 Fase 11: Consultas Políglotas (JettraSQL, JettraQL) y Paginación Interactiva

#### Consultas Relacionales JettraSQL:
```text
admin@jettra-cluster:primary> SQL SELECT numero_orden, id_cliente, id_tecnico, total, estado FROM ordenes_compra WHERE estado = 'APROBADA'
=== JETTRASQL RESULTADO (0 ms) ===
+-----------------+---------------+---------------+------------+------------+
| NUMERO_ORDEN    | ID_CLIENTE    | ID_TECNICO    | TOTAL      | ESTADO     |
+-----------------+---------------+---------------+------------+------------+
| OC-2026-005001  | cli_101       | tec_701       | 6013.40    | APROBADA   |
+-----------------+---------------+---------------+------------+------------+
Total: 1 fila(s) seleccionadas / afectadas.
```

#### Paginación Interactiva de Facturas:
```text
admin@jettra-cluster:primary> PAGE_SIZE 1
[PAGINACIÓN] Tamaño de página configurado a 1 registros para futuras consultas.

admin@jettra-cluster:primary> SELECT numero_factura, cufe, monto_total, estado FROM facturas
=== JETTRASQL RESULTADO (0 ms) ===
+--------------------+------------------------------+---------------+-------------------+
| NUMERO_FACTURA     | CUFE                         | MONTO_TOTAL   | ESTADO            |
+--------------------+------------------------------+---------------+-------------------+
| FAC-2026-000101    | CUFE-SHA256-992182049AAB881  | 5906.40       | TIMBRADA_PAGADA   |
+--------------------+------------------------------+---------------+-------------------+
Total: 1 fila(s) seleccionadas / afectadas.
+------------------------------------------------------------------------------------------------------+
|  PÁGINA [ 1 / 1 ]  •  Mostrando filas 1 - 1 de 1 total  •  Tamaño de página: 1                       |
|  Navegación: [P]RIMERO (|<<)  •  [A]NTERIOR (<)  •  [S]IGUIENTE (>)  •  [U]LTIMO (>>|)                 |
+------------------------------------------------------------------------------------------------------+
```

#### Consultas Declarativas JettraQL:
```text
admin@jettra-cluster:primary> JQL FROM productos WHERE categoria = Hardware
=== JETTRAQL [FROM] ===
Colección: 'productos'
  [01] ID: prod_01 | Datos: {sku=SRV-RACK-2U, nombre=Servidor Rack 2U Enterprise Xeon 64C, precio_unitario=4850.0}

admin@jettra-cluster:primary> JQL FETCH ordenes_compra ord_5001 RESOLVE REFS
=== JETTRAQL [FETCH] ===
Documento recuperado con referencias resueltas:
  Orden: OC-2026-005001 (Total: $6,013.40)
  -> Técnico Responsable: Ing. Marcos Rivera (+507 6201-9988)
  -> Factura Fiscal: FAC-2026-000101
```

---

### 15.13 Fase 12: Analítica CLI y Cálculos Financieros (AGGREGATE, MATH, FINANCE)

#### 1. Agregaciones de Compras y Facturación:
```text
admin@jettra-cluster:primary> AGGREGATE facturas GROUP BY estado SUM monto_total AVG monto_total COUNT
=== JETTRASQL RESULTADO (0 ms) ===
+--------------------+---------------------+---------------------+---------+
| ESTADO             | SUM(MONTO_TOTAL)    | AVG(MONTO_TOTAL)    | COUNT   |
+--------------------+---------------------+---------------------+---------+
| TIMBRADA_PAGADA    | 5906.40             | 5906.40             | 1       |
+--------------------+---------------------+---------------------+---------+
```

#### 2. Cálculo Financiero de Financiamiento para Órdenes Corporativas (`FINANCE PMT`):
Si el cliente `cli_101` opta por financiar su orden de compra de **$6,013.40** en **12 cuotas mensuales** con una tasa de interés del **1.2% mensual**:
```text
admin@jettra-cluster:primary> FINANCE PMT 0.012 12 6013.40
=== OPERACIÓN FINANCIERA (FINANCE) ===
+-------------------------------------------------------------+
| OPERACIÓN: PMT (Cuota Nivelada / Amortización Francesa)    |
| Tasa Periódica:    1.2000% mensual                          |
| Total de Pagos:    12 cuotas                                |
| Monto Financiado:  $6,013.40                                |
+-------------------------------------------------------------+
| CUOTA MENSUAL FIJA: $541.28                                 |
| TOTAL A PAGAR:      $6,495.36                               |
| TOTAL INTERESES:    $481.96                                 |
+-------------------------------------------------------------+
```

#### 3. Proyección Analítica con Evaluador Matemático (`MATH`):
```text
admin@jettra-cluster:primary> MATH 5906.40 * 1.07 - (100.0 * 2)
=== EVALUADOR MATEMÁTICO (MATH) ===
+-------------------------------------------------------------+
| EXPRESIÓN: 5906.40 * 1.07 - (100.0 * 2)                     |
| RESULTADO: 6119.8480                                        |
+-------------------------------------------------------------+
```

---

### 15.14 Fase 13: Verificación con DB STATS, Conteo Global y Snapshot de Respaldo

Verificamos la radiografía integral de los 8 motores en `orders_billing_db`:

```text
admin@jettra-cluster:primary> DB STATS
=== ESTADÍSTICAS DE BASE DE DATOS: 'orders_billing_db' ===
- Colecciones Totales: 11
- Documentos:          [empresas, clientes, productos, tecnicos, ordenes_compra, facturas]
- Clave-Valor (KV):    [cache_tecnicos_disponibles, cache_folios_fiscales]
- Vectores:            [vector_catalogo_repuestos, vector_diagnosticos_tareas]
- Grafos:              [red_servicios_facturacion]
- Series Temporales:   [telemetria_tareas_campo, flujo_facturacion_horario]
- Geoespacial (GIS):   [sedes_empresas_clientes, gps_tecnicos_campo]
- Columnar (OLAP):     [analitica_facturacion_olap]
- Java Records:        [auditoria_facturacion_records]
- MemTable Utilizada:  0.00 KB (Zero-Leakage Panama FFM)

admin@jettra-cluster:primary> COUNT ALL
=== CONTEO TOTAL DE REGISTROS EN BASE DE DATOS: 'orders_billing_db' ===
  * [DOCUMENT]   'empresas'                      : 2 registro(s)
  * [DOCUMENT]   'clientes'                      : 2 registro(s)
  * [DOCUMENT]   'productos'                     : 4 registro(s)
  * [DOCUMENT]   'tecnicos'                      : 2 registro(s)
  * [DOCUMENT]   'ordenes_compra'                : 1 registro(s)
  * [DOCUMENT]   'facturas'                      : 1 registro(s)
  * [KEYVALUE]   'cache_tecnicos_disponibles'    : 2 clave-valor(es)
  * [KEYVALUE]   'cache_folios_fiscales'         : 1 clave-valor(es)
  * [VECTOR]     'vector_catalogo_repuestos'     : 2 vector(es)
  * [VECTOR]     'vector_diagnosticos_tareas'    : 1 vector(es)
  * [GRAPH]      'red_servicios_facturacion'     : 6 vértices / 5 aristas
  * [TIMESERIES] 'telemetria_tareas_campo'       : 4 punto(s)
  * [TIMESERIES] 'flujo_facturacion_horario'     : 2 punto(s)
  * [GEOSPATIAL] 'sedes_empresas_clientes'       : 1 punto(s) GIS
  * [GEOSPATIAL] 'gps_tecnicos_campo'            : 2 punto(s) GIS
  * [COLUMNAR]   'analitica_facturacion_olap'    : 1 fila(s)
  * [RECORDS]    'auditoria_facturacion_records' : 1 record(s) inmutables
----------------------------------------------------------------------
Gran Total: 35 entidades multimodelo integradas y sincronizadas al 100%.
```

#### Snapshot de Respaldo Inmediato (Hot Backup):
```text
admin@jettra-cluster:primary> BACKUP DATABASE orders_billing_db TO './data/jettra/orders_billing_2026.snap'
[SUCCESS] Respaldo de 'orders_billing_db' generado exitosamente en './data/jettra/orders_billing_2026.snap' (Checksum CRC32 Validado).
```

---

## 16. Respaldos Físicos en Caliente y Restauración (Backup & Restore)

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

## 17. Gestión del Modo de Almacenamiento: `JVM-RAM` vs `DISK-MEMORY (JettraMemory)`

`JettraStoreShell` permite consultar y conmutar en caliente el modo de almacenamiento entre la memoria RAM de la JVM y el motor de disco off-heap `JettraMemory`:

### 17.1 Comandos de Consola
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

### 17.2 Comportamiento de los Modos
* **`JVM-RAM`:** Las colecciones se cargan y consultan en las áreas de memoria Stack y Heap de Java con estructuras `UnifiedMap`, aprovechando ZGC y Compact Object Headers.
* **`DISK-MEMORY`:** Los registros se escriben y leen directamente en disco mediante `JettraMemory` utilizando punteros nativos Panama `MemorySegment`, asegurando 0% de impacto en el montículo.

---

## 18. Módulo de Analítica, Cálculos Cuantitativos y Álgebra Vectorial CLI

`JettraStoreShell` incorpora comandos directos y sintaxis declarativa para realizar cálculos analíticos instantáneos con salida en tablas ASCII formateadas:

### 18.1 Comandos de Agregación (`AGGREGATE` y `SELECT ... GROUP BY`)
* **Comando Directo:**
  ```bash
  AGGREGATE facturas GROUP BY estado SUM total AVG total MEDIAN total COUNT
  ```
* **Sintaxis SQL:**
  ```sql
  SELECT region, SUM(monto) AS total_ventas, AVG(monto) AS promedio, MEDIAN(monto) AS mediana FROM ventas GROUP BY region;
  ```

### 18.2 Evaluador Matemático Cuantitativo (`MATH` / `CALC`)
Permite evaluar cualquier expresión aritmética, trigonométrica o combinatoria:
```bash
MATH cbrt(1000) + sqrt(144) * 2 - hypot(3, 4) + fact(5)
```
* Funciones soportadas: `sqrt`, `cbrt`, `pow`, `abs`, `round`, `ceil`, `floor`, `factorial` / `fact`, `gcd`, `lcm`, `hypot`, `sin`, `cos`, `tan`, `atan2`.

### 18.3 Operaciones Financieras (`FINANCE`)
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

### 18.4 Estadística Descriptiva e Inferencial (`STATS`)
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

### 18.5 Álgebra Vectorial y Búsqueda Multidimensional (`VECTOR`)
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

## 19. Historial Persistente, Expansión Bang y Autocompletado de Comandos CLI

Para maximizar la ergonomía y productividad del operador de consola, `JettraStoreShell` incorpora un subsistema avanzado de interactividad CLI impulsado por JLine 3 con enlaces ANSI nativos.

### 19.1 Autocompletado de Comandos con Tecla `[TAB]`

Al escribir un prefijo o comando parcial en el prompt y presionar la tecla **`[TAB]`**:
1. **Coincidencia Única:** Si existe una única opción válida que coincida con el prefijo ingresado, el comando se autocompleta inmediatamente en la pantalla.
   * *Ejemplo:* Escribir `LOAD SAMPLE ex` y presionar `[TAB]` $\rightarrow$ autocompleta a `LOAD SAMPLE example_factura_db `.
   * *Ejemplo:* Escribir `STATU` y presionar `[TAB]` $\rightarrow$ autocompleta a `STATUS `.
2. **Múltiples Coincidencias:** Si existen múltiples comandos o argumentos que coincidan, el shell despliega una lista organizada por grupos temáticos (*Bases de Datos*, *Colecciones y Buckets*, *CRUD*, *Índices*, *Cluster*, etc.) junto a una descripción funcional:
   ```text
   jettra-shell [admin@127.0.0.1:9091/default_db]> SH[TAB]
   === Bases de Datos ===
   SHOW DATABASES       -- Lista todas las bases de datos registradas
   SHOW SAMPLES         -- Muestra bases de datos de demostración predefinidas
   SHOW SAMPLE DATABASES

   === Colecciones y Buckets ===
   SHOW BUCKETS         -- Lista colecciones/buckets de la base de datos activa
   SHOW COLLECTIONS     -- Lista colecciones o tablas de la base de datos activa
   SHOW RECORDS         -- Muestra registros de una colección o bucket
   SHOW TABLES
   SHOW UNITS

   === Índices ===
   SHOW INDEXES         -- Lista índices secundarios y vectoriales creados

   === Cluster y Nodos ===
   SHOW NODES           -- Lista la topología de nodos del clúster
   SHOW MULTINODE
   ```
3. **Autocompletado Contextual:** Al ingresar un comando con subargumentos (`USE `, `DROP DATABASE `, `SHOW RECORDS `, `COUNT `, `CONNECT `), el motor sugiere dinámicamente los nombres de bases de datos, colecciones activas y perfiles guardados.

* **Comandos equivalentes en línea:**
  ```bash
  COMPLETE SH            # Lista sugerencias para prefijo SH
  TAB LOAD               # Lista sugerencias para comando LOAD
  COMPLETE USE           # Sugiere bases de datos disponibles
  ```

---

### 19.2 Navegación por Historial con Flechas `[↑]` y `[↓]`

El shell registra automáticamente cada comando ejecutado durante la sesión:

* **Flecha Arriba (`↑`):** Desplaza la línea de comandos hacia atrás en el historial, recuperando los comandos previos secuencialmente desde el más reciente hasta el más antiguo.
* **Flecha Abajo (`↓`):** Desplaza la línea de comandos hacia adelante en el historial hacia los comandos más nuevos.
* **Actualización Limpia del Búfer:** Gracias al mapeo explícito de secuencias de escape ANSI (`\033[A` para arriba, `\033[B` para abajo, `\033[C` para derecha, `\033[D` para izquierda, `Home`, `End` y `Delete`), el prompt se redibuja limpiamente sin dejar residuos como `[A` o `[B`.
* **Persistencia Dual Automática:**
  * Archivo de historial cronológico plano: `~/.jettra/history.log`.
  * Archivo de historial estructurado de terminal: `~/.jettra/.jline_history`.
  * Ambos se sincronizan al arrancar el shell, permitiendo reanudar comandos de sesiones previas con solo presionar la flecha `[↑]`.

---

### 19.3 Gestión Directa del Historial (`HISTORY`) y Expansión Bang (`!`)

* **Ver historial completo o acotado:**
  ```bash
  HISTORY           # Muestra todos los comandos registrados
  HISTORY 20        # Muestra los últimos 20 comandos
  ```
  *Salida de ejemplo:*
  ```text
  === HISTORIAL DE COMANDOS (3 registrados) ===
    [   1] CONNECT 127.0.0.1 9091
    [   2] USE orders_billing_db
    [   3] DB STATS
  --------------------------------------------------------------------------------
  Uso: !<num> ejecuta comando por número | !! ejecuta el último | !<prefijo> ejecuta por coincidencia
  ```
* **Búsqueda en el historial:**
  ```bash
  HISTORY SEARCH facturacion
  ```
* **Limpiar el historial:**
  ```bash
  HISTORY CLEAR     # Purga la memoria, vacía JLine y trunca history.log y .jline_history
  ```

#### Expansión Bang (`!`):
* `!!` : Re-ejecuta el último comando ingresado.
* `!n` : Ejecuta el comando en la posición `n` del historial (ejemplo: `!1`).
* `!<prefijo>` : Busca y ejecuta el comando más reciente que comience con `<prefijo>` (ejemplo: `!use` o `!stat`).

---

## 20. Estadísticas Multimodelo Completas (DB STATS con 8 Motores Especializados y Java Records)

El comando `DB STATS` audita e inspecciona la totalidad de los 8 motores multimodelo soportados en JettraStore, garantizando visibilidad transparente tanto de motores tradicionales como de motores analíticos y de alta velocidad:

### 20.1 Salida Completa de `DB STATS`
Al invocar `db stats` sobre una base de datos multimodal (por ejemplo `example_factura_db` u `orders_billing_db`), el reporte muestra detalladamente las unidades de cada motor:

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
- MemTable Utilizada:  0.00 KB
```

### 20.2 Motores Multimodelo Soportados y Representados
1. **Documentos (`DocumentEngine`):** Almacenamiento JSON/BSON con soporte de índices Hash, B-Tree y Sparse.
2. **Clave-Valor KV (`KeyValueEngine`):** Almacenamiento ultra veloz byte-array para caché de tokens, folios y estados volátiles.
3. **Vectores (`VectorEngine`):** Embeddings multidimensionales indexados mediante grafos HNSW y búsqueda k-NN por similitud coseno.
4. **Grafos (`GraphEngine`):** Redes de conocimiento orientadas a nodos y aristas dirigidas ponderadas con relaciones `JettraRef`.
5. **Series Temporales (`TimeSeriesEngine`):** Registro de métricas append-only con compresión Delta y consultas cronológicas por rangos.
6. **Geoespacial GIS (`GeospatialEngine`):** Puntos geoespaciales (latitud/longitud) indexados con R-Tree y consultas por radio geodésico y Bounding Box.
7. **Columnar OLAP (`ColumnarEngine`):** Almacenamiento por columnas vectorizado para analítica fiscal y agregaciones masivas.
8. **Java Records (`RecordsEngine`):** Objetos tipados de Java 25 (`record`) nativos, inmutables, con persistencia zero-copy en memoria y disco.

---

## 21. Comandos de Distribución en Clúster (`cluster-distributed`) y Supervisión en Vivo (`cluster live`)

JettraStoreShell proporciona comandos nativos para la orquestación y migración de datos entre nodos del clúster sin necesidad de llamadas HTTP manuales.

### 21.1 Sintaxis de Comandos `cluster-distributed`

```bash
# 1. Distribuir todas las bases de datos y sus registros hacia todos los nodos del clúster
cluster-distributed <all>
# O alternativamente:
cluster-distributed all

# 2. Distribuir una base de datos específica con todos sus registros hacia todos los nodos
cluster-distributed <nombre-base-datos>
# Ejemplo:
cluster-distributed example_factura_db
cluster-distributed orders_billing_db

# 3. Consultar la tabla de distribución del clúster (nodos y bases de datos alojadas)
cluster-distributed info
```

### 21.2 Visualización de `cluster-distributed info`
La salida formatea una tabla ASCII con la topología, estado de conexión, roles y bases de datos replicadas:

```text
jettra-shell [admin@127.0.0.1:9091]> cluster-distributed info
+---------+----------------+------+-----------+--------+-------+--------------------------------------------------------------+
| NODE ID | IP             | PORT | ROLE      | STATUS | DBS   | DATABASES                                                    |
+---------+----------------+------+-----------+--------+-------+--------------------------------------------------------------+
| node-01 | 192.168.60.243 | 9091 | PRIMARY   | ONLINE | 4     | orders_billing_db, example_factura_db, samples_hospital_db  |
| node-02 | 192.168.60.246 | 9091 | SECONDARY | ONLINE | 4     | orders_billing_db, example_factura_db, samples_hospital_db  |
+---------+----------------+------+-----------+--------+-------+--------------------------------------------------------------+
```

### 21.3 Supervisión en Tiempo Real con `cluster live`
Permite abrir un flujo de eventos continuos en vivo (mediante Server-Sent Events / EventBus) para observar las réplicas del clúster:
```bash
cluster live          # Muestra los eventos recientes de replicación
cluster live 10       # Limita la salida a los últimos 10 eventos
```

---

## 22. Tutorial Práctico Extremo a Extremo (Paso a Paso)

```sql
-- PASO 1: Iniciar sesión y validar telemetría
connect local_master
login admin admin-jettra
status

-- PASO 2: Instalar y persistir todas las bases de datos de prueba
INSTALL SAMPLES
SHOW DBS
SHOW SAMPLES

-- PASO 3: Seleccionar la base de datos de órdenes y facturación
USE orders_billing_db
SHOW BUCKETS
SHOW INDEXES

-- PASO 4: Consultar con JettraQL y JettraSQL
JQL FROM productos WHERE categoria = Hardware;
SQL SELECT numero_orden, id_cliente, total, estado FROM ordenes_compra;

-- PASO 5: Probar resolución de referencias JettraRef
lazy reference on
GET ordenes_compra ord_5001

-- PASO 6: Crear un índice secundario sobre montos y reconstruirlo
CREATE INDEX idx_ordenes_total ON ordenes_compra (total) TYPE BTREE
SHOW INDEXES
ALTER INDEX idx_ordenes_total REBUILD

-- PASO 7: Crear usuario de auditoría con privilegios específicos
CREATE USER auditor_fiscal PASSWORD auditor-pass-2026 ROLE DEVELOPER
GRANT READ_ONLY ON orders_billing_db TO auditor_fiscal
SHOW GRANTS FOR auditor_fiscal

-- PASO 8: Respaldar la base de datos y cerrar sesión
BACKUP DATABASE orders_billing_db TO './data/jettra/orders_billing_backup.snap'
logout
```

---

## 23. Tabla Rápida de Comandos y Referencia General (`help`)

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
  cluster-distributed <all>             Distribuye entre todos los nodos todas las bases de datos.
  cluster-distributed <nombre-bd>       Distribuye la base de datos indicada.
  cluster-distributed info              Muestra una tabla con los nodos y las bases de datos en cada nodo.
  cluster live [límite]                 Muestra trazas y eventos en tiempo real de lo que ocurre en el clúster.
  multinode / show multinode            Muestra el estado de cluster.multinode.active (ON/OFF).
  multinode on / multinode off          Activa o desactiva dinámicamente la distribución de datos.
  add node <id> <host> <port> [ROLE]    Agrega un nuevo nodo secundario al clúster Raft.
  remove node <id>                      Remueve un nodo réplica del anillo dinámico.
  start node <id>                       Inicia y activa el procesamiento para un nodo específico.
  stop node <id>                        Detiene un nodo réplica (pausa el tráfico de descarga).

3. BASES DE DATOS Y PERSISTENCIA EN DISCO:
  show databases / show dbs             Lista todas las bases de datos detectadas en disco y memoria.
  show samples / show sample dbs        Muestra las bases de datos de prueba preconfiguradas.
  LOAD SAMPLE example_factura_db        Carga la base de datos de facturación con 3,000,000 objetos.
  LOAD SAMPLE samples_hostipal_db       Carga la base de datos hospitalaria con 2,000,000 objetos.
  LOAD SAMPLE samples_ambiental_db      Carga la base de datos ambiental mundial con 3,000,000 objetos.
  create database <nombre>              Crea una nueva base de datos lógica.
  drop database <nombre>                Elimina la base de datos especificada.
  use <nombre>                          Conmuta la base de datos activa.
  db stats                              Muestra estadísticas de los 8 motores de la base de datos activa.
  INSTALL SAMPLES                       Instala y persiste las 5 bases de datos de ejemplo.
  backup database [nombre] [TO 'path']  Genera un snapshot físico .snap de la base de datos.
  restore database [nombre] FROM 'path' Restaura un snapshot .snap en una base de datos.

4. BUCKETS/UNITS, REGISTROS Y CONTEO:
  show buckets / show unit              Lista todos los buckets/units de almacenamiento por motor.
  show buckets <DOCUMENT|VECTOR|..>     Filtra las unidades por motor específico.
  show records <bucket> [LIMIT n]       Muestra los registros del bucket (documentos, vectores, etc.).
  count <bucket>                        Cuenta los registros contenidos en la unidad especificada.
  count all / count *                   Censo y conteo total de registros en todos los motores.

5. CONSULTAS POLÍGLOTAS (JETTRAQL Y JETTRASQL):
  JQL FROM <col> [WHERE campo = valor]  Consulta declarativa sobre documentos.
  JQL MATCH (a)-[r]->(b) IN <grafo>     Pattern matching sobre redes de grafos.
  JQL VECTOR SIMILARITY <col> TO [...]  Búsqueda de vecinos más cercanos por similaridad coseno.
  JQL FETCH <col> <id> [RESOLVE REFS]   Recupera un registro resolviendo referencias JettraRef.
  SQL SELECT * FROM <col> [WHERE k = v] Consulta relacional con tabla formateada de columnas y filas.
  SQL INSERT INTO <col> VALUES (id,..)  Inserta registro en la colección activa.
  SQL UPDATE <col> SET k = v WHERE ..   Actualiza campos de un registro.
  SQL DELETE FROM <col> WHERE _id = id  Elimina un registro mediante sintaxis SQL.

6. REGISTROS REFERENCIADOS (JETTRAREF) Y LAZY LOADING:
  lazy reference on / off               Alterna la resolución diferida (Lazy) o inmediata (Eager).
  insert ref <col> <id> KEY <k> TARGET <engine>::<col>#<id>  Vincula un puntero cruzado multimodelo.
  resolve ref <engine>::<col>#<id>      Resuelve manualmente el destino de una referencia.
  show refs <col> <id>                  Muestra todas las referencias de un registro y sus resoluciones.
  get <col> <id>                        Obtiene un documento y resuelve sus punteros _ref_*.

7. HISTORIAL Y AUTOCOMPLETADO INTERACTIVO:
  [TAB]                                 Autocompleta el comando o muestra opciones agrupadas con descripción.
  [Flecha Arriba ↑]                     Recorre hacia atrás los comandos ejecutados previamente.
  [Flecha Abajo ↓]                      Avanza hacia los comandos más recientes en el historial.
  COMPLETE <prefijo> / TAB <prefijo>    Consulta programática de sugerencias de autocompletado.
  HISTORY [n]                           Muestra los últimos 'n' comandos del historial.
  HISTORY SEARCH <termino>              Busca comandos que contengan el término ingresado.
  HISTORY CLEAR                         Limpia la memoria del historial y trunca los archivos.
  !! / !<n> / !<prefijo>                Expansión Bang para reejecución rápida de comandos.

8. ANALÍTICA, CÁLCULOS CUANTITATIVOS Y VECTORES:
  AGGREGATE <col> GROUP BY <c> SUM/AVG.. Agregaciones analíticas instantáneas.
  MATH <expresion>                      Evaluador matemático con funciones avanzadas.
  FINANCE PMT/FV/PV/CAGR/AMORTIZATION   Cálculos financieros corporativos (cuota, tasa, amortización).
  STATS SUMMARY/MEAN/MEDIAN/CORRELATION Estadísticas descriptivas y bivariadas.
  VECTOR COSINE/EUCLIDEAN/DOT/CROSS     Operaciones de álgebra lineal y similitud vectorial.
==============================================================================================
```



