# JettraMemory: Manual Maestro de Arquitectura, Almacenamiento Off-Heap en Disco, Recolector de Basura Autónomo y Clúster Distribuido en Java 25+

**Manual Maestro de Arquitectura de Almacenamiento Off-Heap, Desfragmentación Autónoma, Consistencia Quórum en 3 Nodos e Integración con el Ecosistema Jettra**  
*Versión de Plataforma: Java 25 LTS / Jettra Core 1.0*

---

## Tabla de Contenidos
1. [Visión General y Filosofía de JettraMemory](#1-visión-general-y-filosofía-de-jettramemory)
   - 1.1 El Problema del Heap y las Pausas del GC
   - 1.2 Principios de Diseño y Objetivos Core
2. [Arquitectura de Persistencia y Formatos Físicos](#2-arquitectura-de-persistencia-y-formatos-físicos)
   - 2.1 Archivo de Datos Binarios `.jettra`
   - 2.2 Archivo de Índice Físico `.idx`
   - 2.3 Mecanismo de Marcado Lógico con Tombstones
   - 2.4 Resiliencia y Auto-Recuperación del Índice
3. [Optimizaciones de la JVM en Java 25+](#3-optimizaciones-de-la-jvm-en-java-25)
   - 3.1 Project Panama: Foreign Function & Memory (FFM) API
   - 3.2 Canales NIO `FileChannel` y Memory-Mapping
   - 3.3 Hilos Virtuales (Project Loom) para Tareas de Fondo
   - 3.4 Verificación de Integridad Criptográfica por CRC32
4. [Recolector de Basura y Compactación Personalizada (`JettraGarbageCollector`)](#4-recolector-de-basura-y-compactación-personalizada-jettragarbagecollector)
   - 4.1 ¿Por qué un Custom GC Desacoplado de la JVM?
   - 4.2 Detección de Fragmentación y Métricas de Espacio Muerto
   - 4.3 Algoritmo de Compactación Atómica (`CompactionTask`)
   - 4.4 Modos de Ejecución: Manual vs. Autónomo
5. [Topología de Clúster Distribuido de Tres Nodos y Consenso](#5-topología-de-clúster-distribuido-de-tres-nodos-y-consenso)
   - 5.1 Arquitectura de 3 Nodos y Definición de Roles
   - 5.2 Algoritmo de Quórum Mayoritario (2 de 3 Nodos)
   - 5.3 Coordinación de Nodos y Monitoreo de Latidos (`NodeCoordinator`)
   - 5.4 Protocolo de Replicación Concurrente (`ClusterReplicationManager`)
   - 5.5 Recuperación y Sincronización en Caliente (*Catch-Up*)
6. [Integración Nativa con el Ecosistema Jettra](#6-integración-nativa-con-el-ecosistema-jettra)
   - 6.1 JettraCollections: `JettraOffHeapMap` y Fábricas de Colecciones
   - 6.2 JettraStore: Compatibilidad Binaria con `NativeMemTable`
   - 6.3 JettraEE & Helidon: Repositorios Jakarta EE y Sondas de Salud
7. [Referencia de API y Guía de Programación](#7-referencia-de-api-y-guía-de-programación)
   - 7.1 Arranque Rápido con `JettraMemoryBootstrap`
   - 7.2 Configuración Modular con `JettraMemoryConfig`
   - 7.3 Fachada Principal `JettraMemoryEngine`
   - 7.4 Telemetría y Diagnóstico con `StorageMetrics`
8. [Verificación Funcional con el Framework `JettraTest`](#8-verificación-funcional-con-el-framework-jettratest)
   - 8.1 Filosofía de Pruebas en Java 25 con `JettraTestRunner`
   - 8.2 Desglose de Pruebas de la Suite Core
9. [Guía de Operaciones, Rendimiento y Mejores Prácticas](#9-guía-de-operaciones-rendimiento-y-mejores-prácticas)

---

## 1. Visión General y Filosofía de JettraMemory

### 1.1 El Problema del Heap y las Pausas del GC
En aplicaciones empresariales modernas basadas en la Máquina Virtual de Java (JVM), almacenar grandes volúmenes de datos directamente en el Heap genera una sobrecarga drástica:
1. **Presión Excesiva sobre el Garbage Collector (GC):** A medida que millones de objetos transitorios o de larga duración saturan el Heap, incluso recolectores avanzados como ZGC o Shenandoah incurren en ciclos continuos de rastreo de referencias, compactación de páginas y degradación de la caché de CPU L1/L2/L3.
2. **Sobrecarga de Encabezados de Objetos:** Cada objeto Java convencional introduce un encabezado de metadatos (Mark Word + Klass Word), consumiendo entre 8 y 16 bytes adicionales por entrada, lo que multiplica el consumo de memoria real.
3. **Pausas y Jitter en Latencia:** En sistemas de baja latencia (sistemas financieros, bases de datos, procesamiento de eventos de telemetría), las variaciones inducidas por la gestión de memoria interna comprometen los Acuerdos de Nivel de Servicio (SLA).

### 1.2 Principios de Diseño y Objetivos Core
**`JettraMemory`** fue concebida como la capa de almacenamiento de objetos ultrarrápida, de bajo consumo y cero impacto en el Heap para todo el ecosistema Jettra. Sus fundamentos clave son:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        APLICACIÓN / FRAMEWORK                          │
│        (JettraStore / JettraCollections / JettraEE / Helidon)          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ LLAMADAS API (put, get, delete)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        JettraMemoryEngine                              │
├───────────────────────────────────┬────────────────────────────────────┤
│       NodeCoordinator             │     ClusterReplicationManager      │
│  (Topología de 3 Nodos / Quórum)  │  (Dispersión Concurrente Loom)     │
├───────────────────────────────────┼────────────────────────────────────┤
│      JettraGarbageCollector       │         IndexManager               │
│   (Virtual Thread Autonomous GC)  │   (.idx File / Zero-Heap Memory)   │
├───────────────────────────────────┴────────────────────────────────────┤
│                       DiskStorageEngine                                │
│       Java 25 Panama Foreign Function & Memory API (Arena / FFM)        │
│                NIO FileChannel Direct Access & Memory-Mapping          │
└──────────────────┬──────────────────────────────────┬──────────────────┘
                   │                                  │
                   ▼                                  ▼
      ┌─────────────────────────┐        ┌─────────────────────────┐
      │  store.jettra (DATOS)   │        │  store.idx (ÍNDICE)     │
      └─────────────────────────┘        └─────────────────────────┘
```

* **Operación Directa a Disco (Off-Heap):** Los payloads se transfieren directamente desde/hacia canales de disco mediante búferes nativos (`java.lang.foreign.MemorySegment` y `Arena.ofConfined()`), garantizando que los datos no pasen por el Heap ni permanezcan en él.
* **Autonomía de Limpieza (Custom GC):** En lugar de apoyarse en la JVM, la librería implementa su propio motor de recolección de basura (`JettraGarbageCollector`), el cual desfragmenta físicamente el archivo `.jettra` y recupera espacio en disco en segundo plano.
* **Consenso Distribuido de Tres Nodos:** Soporte de quórum mayoritario integrado de 3 nodos (requiriendo 2 confirmaciones para escrituras), garantizando tolerancia a particiones de red y continuidad operativa.
* **Arquitectura Plug-and-Play:** Inicializable con una sola línea de código a través de `JettraMemoryBootstrap` o inyectable en contenedores Jakarta EE / CDI.

---

## 2. Arquitectura de Persistencia y Formatos Físicos

JettraMemory divide la persistencia física en dos estructuras complementarias: el **Archivo de Datos (`.jettra`)** y el **Archivo de Índice (`.idx`)**.

### 2.1 Archivo de Datos Binarios `.jettra`
El archivo `.jettra` es un registro secuencial *append-only*. Todos los registros se escriben de manera contigua al final del archivo con alineación de 64 bits para optimizar transferencias por bloques de disco y DMA (Direct Memory Access).

#### Encabezado Global del Archivo `.jettra` (8 Bytes)
```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|   'J' (0x4A)  |   'S' (0x53)  |   'M' (0x4D)  |   '1' (0x31)  | Magic Header (4B)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       Format Version (2B)     |         Reserved (2B)         | Version & Flags
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

#### Estructura Físico-Binaria del Registro Individual
Cada registro (activo o borrado) contiene los siguientes campos contiguos:

| Offset Relativo | Tipo | Longitud | Campo | Descripción |
|---|---|---|---|---|
| `+0` | `byte` | 1 Byte | `Status` | `0x01` = Registro Activo (`RECORD_ACTIVE`), `0x02` = Marcador Tombstone (`RECORD_TOMBSTONE`) |
| `+1` | `int` | 4 Bytes | `Version` | Versión incremental del registro para resolución de conflictos |
| `+5` | `long` | 8 Bytes | `Timestamp` | Marca de tiempo UTC en milisegundos |
| `+13` | `int` | 4 Bytes | `KeyLength` | Longitud en bytes de la clave codificada en UTF-8 |
| `+17` | `byte[]` | $N$ Bytes | `KeyBytes` | Clave textual del registro |
| `+17+N` | `int` | 4 Bytes | `PayloadLength` | Longitud en bytes del contenido binario (0 para Tombstones) |
| `+21+N` | `byte[]` | $M$ Bytes | `PayloadBytes` | Contenido binario del registro (cero-copia) |
| `+21+N+M` | `long` | 8 Bytes | `CRC32 Checksum` | Suma de verificación del contenido binario para validación de integridad |

$$\text{Tamaño Total del Registro} = 1 + 4 + 8 + 4 + N + 4 + M + 8 = (29 + N + M) \text{ Bytes}$$

### 2.2 Archivo de Índice Físico `.idx`
El archivo `.idx` almacena un catálogo serializado de punteros que permite saltos directos al archivo `.jettra` en tiempo $\mathcal{O}(1)$ sin realizar escaneos secuenciales.

#### Encabezado del Archivo de Índice (10 Bytes)
* **Magic Header:** 4 bytes `['J', 'I', 'D', 'X']`.
* **Index Version:** 2 bytes (`short = 1`).
* **Entry Count:** 4 bytes (`int` con el total de entradas indexadas).

#### Estructura de Entrada Serializada (`IndexEntry`)
Para cada clave almacenada se persiste:
1. `KeyLength` (2 bytes, `short`)
2. `KeyBytes` ($N$ bytes, UTF-8)
3. `IndexEntry Data` (37 bytes fijos):
   - `offset` (8 bytes, `long`): Posición física exacta en el archivo `.jettra`.
   - `length` (4 bytes, `int`): Longitud total del bloque del registro.
   - `payloadLength` (4 bytes, `int`): Longitud útil del payload de datos.
   - `version` (4 bytes, `int`): Versión del registro.
   - `timestamp` (8 bytes, `long`): Estampa temporal de commit.
   - `isTombstone` (1 byte, `byte`): `1` si es lápida/eliminado, `0` si está activo.
   - `checksum` (8 bytes, `long`): CRC32 registrado en la cabecera.

### 2.3 Mecanismo de Marcado Lógico con Tombstones
La eliminación de un registro en JettraMemory no realiza costosas reescrituras inmediatas ni truncamientos en caliente:
1. Se calcula el tamaño de una trama *Tombstone* (payload de longitud cero).
2. Se escribe secuencialmente al final del archivo `.jettra` con el flag `0x02` (`RECORD_TOMBSTONE`).
3. El `IndexManager` actualiza la entrada asignándole `isTombstone = true` e incrementa el contador global de **Bytes Muertos** (`deadBytesAccumulator`).
4. Las lecturas posteriores detectan inmediatamente la lápida y retornan `null` sin acceder al canal de datos.

### 2.4 Resiliencia y Auto-Recuperación del Índice
Si el proceso de la aplicación es abortado abruptamente (pérdida de energía, señal SIGKILL) antes de que el archivo `.idx` se sincronice en disco:
* Al reiniciar, `DiskStorageEngine.initStorageFiles()` detecta la discrepancia o ausencia del índice.
* Se invoca automáticamente `rebuildIndexFromDataFile()`.
* El motor escanea secuencialmente el archivo `.jettra` desde el byte `0x08`, releyendo las cabeceras de cada registro e insertando las entradas vivas en el `IndexManager`.
* El sistema recupera el estado consistente previo al fallo en cuestión de milisegundos.

---

## 3. Optimizaciones de la JVM en Java 25+

JettraMemory está diseñado para aprovechar al máximo las capacidades de la plataforma Java 25:

### 3.1 Project Panama: Foreign Function & Memory (FFM) API
Se sustituye el uso tradicional de `sun.misc.Unsafe` y `ByteBuffer` indirectos por la API oficial estandarizada (`java.lang.foreign`):
* **`Arena.ofConfined()`:** Para operaciones de lectura y escritura puntuales. Se asigna un segmento de memoria nativa fuera del Heap con ciclo de vida determinista. Al cerrarse la Arena en un bloque *try-with-resources*, la memoria es liberada inmediatamente por el sistema operativo sin esperar al GC.
* **`MemorySegment` y `ValueLayout`:** Las lecturas y escrituras de tipos primitivos (`JAVA_BYTE`, `JAVA_INT_UNALIGNED`, `JAVA_LONG_UNALIGNED`) se realizan directamente sobre direcciones de memoria nativa con soporte para arquitecturas x86_64 y ARM64.
* **Cero Copia en el Puente Java/Nativo:** El método `MemorySegment.asByteBuffer()` genera una vista envolvente directa sin clonar arreglos de bytes en el montículo.

```java
try (Arena arena = Arena.ofConfined()) {
    MemorySegment recordSegment = arena.allocate(recordSize);
    recordSegment.set(ValueLayout.JAVA_BYTE, 0, RECORD_ACTIVE);
    recordSegment.set(ValueLayout.JAVA_INT_UNALIGNED, 1, 1);
    recordSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, 5, System.currentTimeMillis());
    // ...
    dataChannel.write(recordSegment.asByteBuffer(), currentOffset);
}
```

### 3.2 Canales NIO `FileChannel` y Memory-Mapping
* Las lecturas concurrentes operan a través de `FileChannel.read(ByteBuffer, long offset)` concurrente o mapeo de archivos a memoria (`FileChannel.map`), permitiendo lecturas múltiples sin contención de cerrojos globales.
* Control estricto de concurrencia mediante `ReentrantReadWriteLock(true)` (fair lock), garantizando que las lecturas no bloqueen a otras lecturas y que los reemplazos de compactación se ejecuten de manera atómica.

### 3.3 Hilos Virtuales (Project Loom) para Tareas de Fondo
* El recolector de basura autónomo (`JettraGarbageCollector`), los despachadores de replicación a nodos remotos y los monitores de latido de red se ejecutan sobre hilos virtuales ligeros (`Thread.ofVirtual()`).
* Esto permite mantener cientos de tareas de I/O en clúster concurrentes sin agotar los hilos de plataforma del sistema operativo.

### 3.4 Verificación de Integridad Criptográfica por CRC32
Cada escritura calcula el CRC32 del contenido útil y lo persiste en la cabecera. Cada lectura verifica este checksum antes de entregar el arreglo de bytes al usuario. Si se detecta corrupción de bits o un sector defectuoso en el disco, se arroja inmediatamente una excepción `IOException` descriptiva.

---

## 4. Recolector de Basura y Compactación Personalizada (`JettraGarbageCollector`)

### 4.1 ¿Por qué un Custom GC Desacoplado de la JVM?
En un motor de base de datos o almacenamiento en disco tradicional, cuando se actualiza un registro existente, el nuevo dato se escribe al final del archivo y el registro anterior queda huérfano. Con el tiempo, el archivo de datos acumula gigabytes de espacio desperdiciado.
El Garbage Collector de la JVM no puede desfragmentar un archivo en disco duro; solo limpia objetos en la RAM. `JettraGarbageCollector` es el recolector de basura de disco de JettraMemory.

### 4.2 Detección de Fragmentación y Métricas de Espacio Muerto
El motor mantiene métricas en tiempo real a través del record `StorageMetrics`:
* $\text{TotalAllocatedBytes}$: Bytes físicos totales ocupados por el archivo `.jettra`.
* $\text{ActiveBytes}$: Bytes correspondientes a payloads de datos vivos.
* $\text{DeadBytes}$: Bytes acumulados por registros obsoletos y *Tombstones*.
* **Ratio de Fragmentación:**
  $$\text{FragmentationRatio} = \frac{\text{DeadBytes}}{\text{TotalAllocatedBytes}}$$

Si el ratio de fragmentación supera el umbral configurado (por defecto **$25\%$** / `0.25`), el daemon en segundo plano dispara automáticamente el proceso de desfragmentación.

### 4.3 Algoritmo de Compactación Atómica (`CompactionTask`)

```
   [ Archivo Original .jettra ] (Fragmentado con espacio muerto)
   ┌────────┬────────┬───────────┬────────┬───────────┐
   │ Header │ K1 (v1)│ Tombstone │ K2 (v1)│ K1 (v2)   │
   └────────┴────┬───┴───────────┴────┬───┴─────┬─────┘
                 │ (Omitir)  (Omitir) │         │
                 ▼                    ▼         ▼
   ┌──────────────────────────────────────────────────┐
   │       CompactionTask (Streaming Directo)         │
   └──────────────────────────┬───────────────────────┘
                              ▼
   [ Archivo Temporal .jettra.compacting ] (Contiguo y 100% Útil)
   ┌────────┬────────┬────────┐
   │ Header │ K2 (v1)│ K1 (v2)│
   └────────┴────────┴────────┘
              │
              ▼ (Intercambio Atómico: Files.move con ATOMIC_MOVE)
   [ Archivo Definitivo .jettra ] <--- (100% Espacio Muerto Recuperado)
```

1. **Snapshot de Entradas Activas:** Se obtiene una instantánea inmutable de `IndexManager.getAllLiveEntries()`.
2. **Creación de Archivo Temporal:** Se abre el archivo de puesta en escena `<storeName>.jettra.compacting`.
3. **Transferencia Cero-Copia:** Se itera únicamente sobre los registros vivos. Cada registro se lee del canal fuente y se escribe secuencialmente en el archivo temporal usando búferes directos fuera del Heap.
4. **Cálculo de Nuevos Offsets:** Se construye un nuevo índice en memoria donde cada clave apunta a su nueva posición contigua en el archivo compactado.
5. **Sustitución Atómica de Archivos:**
   - Se adquiere el cerrojo de escritura `rwLock.writeLock()`.
   - Se cierra el canal de datos original.
   - Se invoca `Files.move(tempPath, dataPath, REPLACE_EXISTING, ATOMIC_MOVE)`.
   - Se reabre el canal de datos sobre el archivo compactado y se actualiza el índice principal.
   - Se reinicia el acumulador de bytes muertos a 0.
   - Se libera el cerrojo.
   
Todo el proceso transcurre sin bloquear las lecturas concurrentes hasta el instante del intercambio atómico (que dura menos de 1 milisegundo).

### 4.4 Modos de Ejecución: Manual vs. Autónomo
* **Modo Autónomo (Daemon):** Un hilo virtual supervisa periódicamente las métricas de disco cada 5 segundos y ejecuta la compactación al exceder el umbral.
* **Modo Manual:** El usuario o administrador del sistema puede invocar `engine.compact()` síncronamente o `engine.compactAsync()` retornando un `CompletableFuture<CompactionResult>`.

---

## 5. Topología de Clúster Distribuido de Tres Nodos y Consenso

Para entornos de alta disponibilidad, JettraMemory cuenta con un módulo de coordinación nativo diseñado específicamente para operar en un **clúster distribuido de tres nodos**.

```
                           ┌────────────────────────┐
                           │   Cliente / Mutación   │
                           └───────────┬────────────┘
                                       │ put("user:1", data)
                                       ▼
                       ┌────────────────────────────────┐
                       │  Nodo 1 (PRIMARY / LEADER)     │
                       │  Local Write: OK               │
                       └───────┬────────────────┬───────┘
                               │                │
            ReplicationFrame   │                │ ReplicationFrame
             (Virtual Thread)  │                │  (Virtual Thread)
                               ▼                ▼
        ┌──────────────────────────────┐   ┌──────────────────────────────┐
        │  Nodo 2 (SECONDARY)          │   │  Nodo 3 (SECONDARY)          │
        │  Status: ONLINE              │   │  Status: ONLINE              │
        │  Ack: OK                     │   │  Ack: OK                     │
        └──────────────────────────────┘   └──────────────────────────────┘
                               │                │
                               └────────┬───────┘
                                        │ (Quórum: 2 de 3 Acks Confirmados)
                                        ▼
                       [ Escritura Confirmada Exitosamente ]
```

### 5.1 Arquitectura de 3 Nodos y Definición de Roles
El clúster está compuesto exactamente por tres nodos identificados de manera única:
* **`PRIMARY` (Coordinador):** Recibe las operaciones de mutación, ejecuta la persistencia local y orquesta la replicación.
* **`SECONDARY` (Réplica activa):** Aplica las tramas de mutación recibidas del nodo primario en su motor de disco local.
* **Estados de Nodo:**
  - `ONLINE`: Nodo activo y respondiendo a latidos.
  - `SYNCING`: Nodo en proceso de sincronización en caliente.
  - `OFFLINE`: Nodo inalcanzable tras vencer el temporizador de *heartbeat*.

### 5.2 Algoritmo de Quórum Mayoritario (2 de 3 Nodos)
Para evitar anomalías de *Split-Brain* y garantizar consistencia fuerte:
$$\text{Quórum Requerido} = \left\lfloor \frac{N}{2} \right\rfloor + 1 = \left\lfloor \frac{3}{2} \right\rfloor + 1 = 2 \text{ Nodos}$$

* Una operación `put` o `delete` se considera **confirmada (committed)** únicamente cuando:
  1. El nodo local escribe exitosamente en su almacenamiento directo.
  2. Al menos 1 de los 2 nodos pares secundarios confirma la replicación exitosa.
* Si 2 de los 3 nodos caen simultáneamente, el sistema rechaza escrituras con `IOException` preventiva para proteger la integridad de los datos.

### 5.3 Coordinación de Nodos y Monitoreo de Latidos (`NodeCoordinator`)
La clase `NodeCoordinator` valida la topología:
* Registra a los pares (`registerPeer(ClusterNode)`).
* Verifica la vigencia del quórum con `hasQuorum()`.
* Ejecuta un monitor de latidos en segundo plano (`startHeartbeatMonitor(intervalMs, timeoutMs)`) sobre hilos virtuales para degradar nodos caídos a `OFFLINE` de forma autónoma.

### 5.4 Protocolo de Replicación Concurrente (`ClusterReplicationManager`)
Cada operación genera un `ReplicationFrame` inmutable con un número de secuencia monótono ascendente (`sequenceId`), tipo de operación (`PUT`, `DELETE`), clave y payload.
* La transmisión a los nodos secundarios se dispara en paralelo mediante `CompletableFuture` respaldados por hilos virtuales independientes (`Thread.ofVirtual().name("Jettra-Repl-" + peerId)`).
* Permite interconexión en memoria directa (`linkDirectPeerEngine`) para pruebas y entornos multi-tenant en el mismo host, o conectores de red para despliegue distribuido.

### 5.5 Recuperación y Sincronización en Caliente (*Catch-Up*)
Cuando un nodo secundario que estuvo temporalmente desconectado vuelve a estar en línea, el coordinador invoca `synchronizeTargetNode(targetEngine)`, transfiriendo en caliente el conjunto de registros vivos actuales para nivelar su estado sin detener las lecturas del clúster.

---

## 6. Integración Nativa con el Ecosistema Jettra

JettraMemory fue concebida como la infraestructura de almacenamiento subyacente para todos los módulos de JettraStack:

### 6.1 JettraCollections: `JettraOffHeapMap` y Fábricas de Colecciones
Proporciona la clase `JettraOffHeapMap<K, V>`, la cual implementa la interfaz estándar `java.util.Map<K, V>`:
* **Espacio de Nombres Aislado:** Múltiples mapas pueden convivir en el mismo motor de disco separando claves mediante prefijos (`namespace:key`).
* **Consumo de Heap Nulo:** Los valores `V` residen en el archivo `.jettra`. Al invocar `map.get(key)`, el valor se extrae bajo demanda desde el disco off-heap y se entrega; nunca se retiene en listas o árboles en la memoria RAM del Heap.
* **Fábricas Listas para Usar (`JettraOffHeapCollectionFactory`):**
  - `createStringMap(namespace, engine)`: Mapeo de cadenas de texto optimizado.
  - `createBinaryMap(namespace, engine)`: Mapeo de arreglos binarios puros.
  - `createCustomMap(...)`: Mapeo parametrizable con codificadores/decodificadores funcionales.

### 6.2 JettraStore: Compatibilidad Binaria con `NativeMemTable`
El serializador `JettraBinarySerializer` y el conector `JettraStoreConnector` aseguran plena compatibilidad con los formatos generados por `JettraStore`:
* Soporte del encabezado binario nativo: `[engineId (1B)][keyLength (4B)][keyBytes][payloadLength (4B)][payloadBytes]`.
* Permite que los 8 motores multimodelo de `JettraStore` (KeyValue, Document, Vector, TimeSeries, Columnar, Graph, Geospatial y Records) deleguen almacenamiento frío o caliente a JettraMemory.

### 6.3 JettraEE & Helidon: Repositorios Jakarta EE y Sondas de Salud
* **`JettraEERepository<T>`:** Repositorio genérico inyectable mediante `@Inject` en Jakarta CDI o utilizable en microservicios Helidon SE/MP para almacenar entidades que implementen `Serializable` directamente en disco sin saturar la memoria del contenedor.
* **`JettraMemoryHealthCheck`:** Implementa la especificación `io.jettra.ee.health.HealthCheck` de MicroProfile / JettraEE, exponiendo métricas de quórum de 3 nodos, bytes asignados y ratio de fragmentación para orquestadores como Kubernetes.

---

## 7. Referencia de API y Guía de Programación

### 7.1 Arranque Rápido con `JettraMemoryBootstrap`

#### Modo Standalone (Instancia Local Mononodo)
```java
import com.jettra.memory.api.JettraMemoryBootstrap;
import com.jettra.memory.api.JettraMemoryEngine;
import java.nio.file.Path;

try (JettraMemoryEngine engine = JettraMemoryBootstrap.standalone(Path.of("./data/my_store"))) {
    // Escritura directa off-heap
    engine.put("user:42", "{\"nombre\":\"Elena\",\"rol\":\"Arquitecta\"}".getBytes());

    // Lectura directa desde disco
    byte[] data = engine.get("user:42");
    System.out.println(new String(data));

    // Eliminación lógica con Tombstone
    engine.delete("user:42");

    // Sincronización a disco
    engine.flush();
}
```

#### Modo Clúster Distribuido de Tres Nodos
```java
import com.jettra.memory.api.JettraMemoryBootstrap;
import com.jettra.memory.api.JettraMemoryEngine;
import com.jettra.memory.cluster.ClusterNode;
import java.nio.file.Path;

ClusterNode peer2 = new ClusterNode("node-2", "192.168.1.102", 9102, ClusterNode.Role.SECONDARY);
ClusterNode peer3 = new ClusterNode("node-3", "192.168.1.103", 9103, ClusterNode.Role.SECONDARY);

try (JettraMemoryEngine engine = JettraMemoryBootstrap.cluster3Nodes(
        "node-1", "192.168.1.101", 9101, Path.of("./data/cluster_n1"), peer2, peer3)) {

    // Escritura replicada concurrentemente con quórum mayoritario (2 de 3)
    engine.put("balance:ES9921", "250000.00".getBytes());
}
```

### 7.2 Configuración Modular con `JettraMemoryConfig`
Para escenarios avanzados donde se requiera ajustar umbrales de fragmentación y puertos:

```java
import com.jettra.memory.api.JettraMemoryConfig;
import com.jettra.memory.api.JettraMemoryEngine;
import java.nio.file.Path;

JettraMemoryConfig config = JettraMemoryConfig.builder()
        .storageDirectory(Path.of("./data/high_throughput"))
        .storeName("financial_records")
        .compactionThreshold(0.20) // Disparar compactación al alcanzar 20% de espacio muerto
        .autoGcEnabled(true)       // Activar el recolector autónomo en segundo plano
        .nodeId("primary-dc1")
        .host("10.0.0.10")
        .port(9500)
        .build();

try (JettraMemoryEngine engine = new JettraMemoryEngine(config)) {
    // Operaciones del motor
}
```

### 7.3 Fachada Principal `JettraMemoryEngine`
Principales métodos expuestos por el motor:

| Método | Retorno | Descripción |
|---|---|---|
| `put(String key, byte[] data)` | `void` | Escribe un registro off-heap. Si el clúster está activo, replica con quórum mayoritario. |
| `get(String key)` | `byte[]` | Lee un registro directamente desde disco mediante FFM. Retorna `null` si no existe o fue eliminado. |
| `delete(String key)` | `boolean` | Registra una lápida (Tombstone) y replica en el clúster. Retorna `true` si existía. |
| `compact()` | `CompactionResult` | Ejecuta de forma síncrona la compactación del archivo `.jettra`, purgando datos muertos. |
| `flush()` | `void` | Vuelca de inmediato el archivo de índice `.idx` y fuerza `fsync` en el canal de datos `.jettra`. |
| `containsKey(String key)` | `boolean` | Consulta en el índice $\mathcal{O}(1)$ si la clave existe y está activa. |
| `size()` | `int` | Retorna el total de registros vivos actuales. |
| `getMetrics()` | `StorageMetrics` | Retorna estadísticas en tiempo real de bytes asignados, bytes muertos y operaciones. |
| `getOffHeapMap(String ns)` | `Map<String, String>` | Retorna una vista `Map` en disco para el espacio de nombres dado. |

### 7.4 Telemetría y Diagnóstico con `StorageMetrics`
```java
StorageMetrics metrics = engine.getMetrics();
System.out.printf("Asignado: %.2f MB | Espacio Muerto: %.2f MB | Fragmentación: %.1f%%\n",
        metrics.totalAllocatedBytes() / (1024.0 * 1024.0),
        metrics.deadBytes() / (1024.0 * 1024.0),
        metrics.fragmentationRatio() * 100.0);
```

---

## 8. Verificación Funcional con el Framework `JettraTest`

### 8.1 Filosofía de Pruebas en Java 25 con `JettraTestRunner`
El ecosistema Jettra cuenta con su propio framework de pruebas desacoplado de JUnit: **`JettraTest`**. JettraMemory incluye la suite completa de pruebas en [`JettraMemoryCoreTest.java`](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraMemory/src/test/java/com/jettra/memory/JettraMemoryCoreTest.java), anotada con `@NotRequiresRunningServer` y ejecutada por `io.jettra.test.runner.JettraTestRunner`.

### 8.2 Desglose de Pruebas de la Suite Core

#### 1. Operaciones CRUD y Persistencia Física (`testBasicPutGetDeleteAndTombstone`)
* Valida la creación en disco de los archivos `<name>.jettra` y `<name>.idx`.
* Comprueba las cabeceras mágicas `JSM1` y `JIDX`.
* Verifica la inserción, recuperación por offset exacto, suma de verificación CRC32 y la generación de un Tombstone en el borrado.

#### 2. Cero Impacto en el Heap de la JVM (`testZeroHeapImpactAndOffHeapDirectMemory`)
* Inserta 2,000 registros contiguos de 1 KB cada uno (~2 MB de datos binarios).
* Realiza mediciones de telemetría de memoria de la JVM mediante `Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()`.
* **Resultado:** Demuestra empíricamente que la variación de Heap consumida es prácticamente nula, validando que los datos se transmiten directamente al disco mediante `MemorySegment`.

#### 3. Recolección de Basura y Compactación (`testJettraGarbageCollectorAndCompaction`)
* Simula escrituras reiteradas y sobreescrituras en múltiples claves para generar más del **$40\%$** de fragmentación por espacio muerto.
* Ejecuta `engine.compact()`.
* **Resultado:** El recolector purga los bytes muertos recuperando el 100% del espacio desperdiciado, reduciendo el tamaño físico del archivo `.jettra` y confirmando la disponibilidad intacta de todas las claves vigentes.

#### 4. Clúster de Tres Nodos y Quórum Mayoritario (`testThreeNodeClusterReplicationAndQuorum`)
* Despliega tres nodos concurrentes (`node-1`, `node-2`, `node-3`) interconectados mediante `linkDirectPeerEngine`.
* Realiza escrituras en el nodo primario.
* **Resultado:** Comprueba que la operación se replica en paralelo a los dos nodos secundarios satisfaciendo el quórum mayoritario (2 de 3) y garantizando lectura consistente idéntica en los 3 nodos.

#### 5. Integración con JettraCollections (`testJettraCollectionsOffHeapMapIntegration`)
* Instancia un `JettraOffHeapMap` bajo el namespace `config_registry`.
* Ejecuta métodos estándar de `java.util.Map` (`put`, `get`, `containsKey`, `remove`, `size`).
* **Resultado:** Valida la manipulación fluida de estructuras de colección convencionales respaldadas de forma transparente en disco.

#### 6. Compatibilidad Binaria con JettraStore y JettraEE (`testJettraStoreAndEESerializationCompatibility`)
* Persiste registros con el formato exacto de `NativeMemTable` de `JettraStore` (`engineId = 2`, payload JSON).
* Persiste tramas `CompactBinaryHeader` y `JettraSerializedRecord` de `JettraEE`.
* **Resultado:** Comprueba la deserialización y lectura cruzada sin pérdida de fidelidad binaria.

---

## 9. Guía de Operaciones, Rendimiento y Mejores Prácticas

### 9.1 Parámetros de Ejecución Recomendados en Producción
Para maximizar el rendimiento en Java 25 LTS, se recomienda iniciar la aplicación con los siguientes flags de la JVM:

```bash
java --enable-preview \
     -XX:+UseZGC \
     -XX:+UseCompactObjectHeaders \
     -Xms2g -Xmx4g \
     -jar TuAplicacionJettra.jar
```

* **`--enable-preview`:** Habilita las características más recientes de la plataforma Java 25.
* **`-XX:+UseZGC`:** Habilita el recolector ZGC generacional de latencia sub-milisegundo.
* **`-XX:+UseCompactObjectHeaders` (JEP 450):** Reduce el encabezado de objetos en la JVM a 64 bits, disminuyendo aún más el consumo residual de memoria.

### 9.2 Selección de Almacenamiento Físico
* Se recomienda utilizar unidades **NVMe / SSD PCIe 4.0/5.0**. Al escribir secuencialmente mediante canales NIO `FileChannel`, JettraMemory alcanza tasas sostenidas de cientos de miles de operaciones por segundo (IOPS).
* No montar el directorio de almacenamiento en sistemas de archivos en red basados en NFS estándar si se busca latencia determinista; utilizar discos locales o volúmenes de bloques adjuntos de alta velocidad.

### 9.3 Estrategias de Compactación
* En cargas de trabajo con alto volumen de actualizaciones y eliminaciones (ej. colas de mensajes, tablas de estado temporal), configurar `compactionThreshold` entre `0.15` y `0.25`.
* En sistemas con ventanas de mantenimiento nocturnas, se puede desactivar el recolector automático (`autoGcEnabled = false`) y programar la ejecución explícita de `engine.compact()` durante periodos de baja carga.

---

*JettraMemory forma parte integral de la suite oficial JettraStack. Desarrollado para computación de alto rendimiento, baja latencia y alta concurrencia en la nube y centros de datos.*


---

## 7. Integración como Motor `DISK-MEMORY` en el Ecosistema JettraStore

`JettraMemory` proporciona el backend fundamental de almacenamiento fuera del Heap para el modo **`DISK-MEMORY`** disponible en:
* **`JettraStore`:** Persistencia directa de colecciones y registros mediante `JettraStoreConnector` sin impacto en el Heap.
* **`JettraStoreDriver`:** Invocación de operaciones binarias off-heap, métricas de fragmentación y compactación en caliente.
* **`JettraStoreShell`:** Conmutación dinámica con comandos `STORAGE_MODE <JVM_RAM | DISK_MEMORY>`.
* **`JettraStoreFX`:** Visualización y control de estado de memoria off-heap en la consola de mando visual JavaFX 25+.
