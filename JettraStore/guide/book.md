# JettraStore: Arquitectura Definitiva del Ecosistema de Base de Datos Multimodelo Distribuida en Java 25+

**Manual Maestro de Arquitectura, Persistencia Off-Heap, Consenso Raft, Anillo por Saturación de Memoria y Operaciones**
*Versión de Plataforma: Java 25 LTS / Jettra Core 1.0*

---

## Tabla de Contenidos
1. [Introducción y Arquitectura General](#1-introducción-y-arquitectura-general)
   - [1.1 Visión General y Filosofía de JettraStore](#11-visión-general-y-filosofía-de-jettrastore)
   - [1.2 Arquitectura de Almacenamiento y Formato de Archivo `.jettra`](#12-arquitectura-de-almacenamiento-y-formato-de-archivo-jettra)
   - [1.3 Optimizaciones Extremas de la JVM (Java 25+)](#13-optimizaciones-extremas-de-la-jvm-java-25)
   - [1.4 Integración Nativa del Motor Off-Heap `JettraMemory` y `JettraStore-OffHeap`](#14-integración-nativa-del-motor-off-heap-jettramemory-y-jettrastore-offheap)
   - [1.5 Mecanismo Dinámico de Anillo Distribuido por Saturación de Memoria](#15-mecanismo-dinámico-de-anillo-distribuido-por-saturación-de-memoria)
   - [1.6 Arquitectura e Implementación de `JettraId` (Clave Primaria Interna por Defecto)](#16-arquitectura-e-implementación-de-jettraid-clave-primaria-interna-por-defecto)
2. [Modelo de Datos, Buckets, Índices y Consultas (LQL / JettraSQL)](#2-modelo-de-datos-buckets-índices-y-consultas-lql--jettrasql)
   - [2.1 Soporte Multimodelo y Referencias Cruzadas (Intra e Inter-Engine)](#21-soporte-multimodelo-y-referencias-cruzadas-intra-e-inter-engine)
   - [2.2 Buckets Ilimitados (`NONE` vs `DEFINED`) e Índices por Bucket (`JettraBucketSchema` / `JettraIndexManager`)](#22-buckets-ilimitados-none-vs-defined-e-índices-por-bucket-jettrabucketschema--jettraindexmanager)
   - [2.3 Lenguajes de Consulta: JettraQueryLanguage (LQL) y JettraSQL](#23-lenguajes-de-consulta-jettraquerylanguage-lql-y-jettrasql)
   - [2.4 Motor Cuantitativo y Analítico: Agregaciones, Matemáticas, Finanzas, Estadística y Álgebra Vectorial](#24-motor-cuantitativo-y-analítico-agregaciones-matemáticas-finanzas-estadística-y-álgebra-vectorial)
   - [2.5 Caso de Estudio Masivo: Base de Datos de Facturación (3,000,000 Objetos)](#25-caso-de-estudio-masivo-base-de-datos-de-facturación-3000000-objetos)
   - [2.6 Guía Práctica de Operaciones: Bases de Datos, Buckets, Registros e Índices (`NUMERIC` y `AUTO_INCREMENT`)](#26-guía-práctica-de-operaciones-bases-de-datos-buckets-registros-e-índices-numeric-y-autoincrement)
   - [2.7 Administración Avanzada de Buckets e Índices desde `JettraStoreShell` (RF-09)](#27-administración-avanzada-de-buckets-e-índices-desde-jettrastoreshell-rf-09)
3. [Seguridad, Supervisión (`JettraPolice`), Backup y Resiliencia](#3-seguridad-supervisión-jettrapolice-backup-y-resiliencia)
   - [3.1 Seguridad Estricta: Autenticación, Superusuario y `JettraJWT`](#31-seguridad-estricta-autenticación-superusuario-y-jettrajwt)
   - [3.2 Componente de Supervisión Preventiva: `JettraPolice`](#32-componente-de-supervisión-preventiva-jettrapolice)
   - [3.3 Capacidades Nativas de Backup y Restore](#33-capacidades-nativas-de-backup-y-restore)
   - [3.4 Métricas de Rendimiento y Microbenchmarking con JMH](#34-métricas-de-rendimiento-y-microbenchmarking-con-jmh)
   - [3.5 Ecosistema de Herramientas Avanzadas y Resiliencia de Plataforma (`JettraStoreMeter`, `JettraStorePoliceFX`, `JettraStoreFX`, `JettraGarbageCollector`)](#35-ecosistema-de-herramientas-avanzadas-y-resiliencia-de-plataforma-jettrastoremeter-jettrastorepolicefx-jettrastorefx-jettragarbagecollector)
4. [Clúster Raft, Replicación y Distribución de Nodos](#4-clúster-raft-replicación-y-distribución-de-nodos)
   - [4.1 Topología de Clúster de 3 Nodos y Consenso Raft](#41-topología-de-clúster-de-3-nodos-y-consenso-raft)
   - [4.2 Mecanismo de Distribución y Migración Integral de Bases de Datos (`Cluster-Distributed`)](#42-mecanismo-de-distribución-y-migración-integral-de-bases-de-datos-cluster-distributed)
   - [4.3 Persistencia Garantizada en `jettra.storage.path`, Sincronización de `cluster.config` y Arranque Limpio](#43-persistencia-garantizada-en-jettrastoragepath-sincronización-de-clusterconfig-y-arranque-limpio)
   - [4.4 Sincronización de Nuevos Nodos y Consistencia Zero-Downtime](#44-sincronización-de-nuevos-nodos-y-consistencia-zero-downtime)
   - [4.5 Gestión del Ciclo de Vida de Nodos y Verificación en Vivo desde `JettraStoreShell`](#45-gestión-del-ciclo-de-vida-de-nodos-y-verificación-en-vivo-desde-jettrastoreshell)
5. [Configuración del Sistema y Optimización de Recursos](#5-configuración-del-sistema-y-optimización-de-recursos)
   - [5.1 Archivo `microprofile-config.properties` (Identidad del Nodo y Parámetros del Motor)](#51-archivo-microprofile-configproperties-identidad-del-nodo-y-parámetros-del-motor)
   - [5.2 Archivo `cluster.config`, Validación Cruzada en Arranque y Generación Automática](#52-archivo-clusterconfig-validación-cruzada-en-arranque-y-generación-automática)
   - [5.3 Optimización del Consumo de Recursos, Memoria RAM y Perfiles JVM (`G1GC` / `JAVA_OPTS`)](#53-optimización-del-consumo-de-recursos-memoria-ram-y-perfiles-jvm-g1gc--javaopts)
6. [Despliegue en Docker, Docker Hub y Clúster en Producción](#6-despliegue-en-docker-docker-hub-y-clúster-en-producción)
   - [6.1 Contenedorización con Docker y Orquestación Básica (`Dockerfile` / `docker-compose.yml`)](#61-contenedorización-con-docker-y-orquestación-básica-dockerfile--docker-composeyml)
   - [6.2 Guía de Despliegue de Clúster en Producción: JARs Distribuidos y Docker Compose](#62-guía-de-despliegue-de-clúster-en-producción-jars-distribuidos-y-docker-compose)
   - [6.3 Despliegue Optimizado de Bajo Consumo RAM/CPU (1 Nodo y 3 Nodos Raft) y Publicación en Docker Hub](#63-despliegue-optimizado-de-bajo-consumo-ramcpu-1-nodo-y-3-nodos-raft-y-publicación-en-docker-hub)
   - [6.4 Despliegue desde Docker Hub (`avbravo/jettrastore:1.0-SNAPSHOT`), Persistencia y Conflictos de Puerto](#64-despliegue-desde-docker-hub-avbravojettrastore10-snapshot-persistencia-y-conflictos-de-puerto)
   - [6.5 Gestión Permanente del Estado de Membresía (`jettra.cluster_state_config_dir` y `cluster-state.config`) — RF-32](#65-gestión-permanente-del-estado-de-membresía-jettracluster_state_config_dir-y-cluster-stateconfig--rf-32)
7. [Integración con Clientes, Herramientas, Migrador y Pruebas (`JettraTest`)](#7-integración-con-clientes-herramientas-migrador-y-pruebas-jettratest)
   - [7.1 Cliente `JettraStoreDriver` y Proyecto de Referencia `JettraStoreExampleSE`](#71-cliente-jettrastoredriver-y-proyecto-de-referencia-jettrastoreexamplese)
   - [7.2 Consola Interactiva `JettraStoreShell` y Verificación Real de Nodos (`show nodes`)](#72-consola-interactiva-jettrastoreshell-y-verificación-real-de-nodos-show-nodes)
   - [7.3 Consola Web `JettraStoreExplorer` y Migrador PostgreSQL a JettraStore (RF-17)](#73-consola-web-jettrastoreexplorer-y-migrador-postgresql-a-jettrastore-rf-17)
   - [7.4 Pruebas Automatizadas Exclusivas con `JettraTest`](#74-pruebas-automatizadas-exclusivas-con-jettratest)

---

## 1. Introducción y Arquitectura General

### 1.1 Visión General y Filosofía de JettraStore

**`JettraStore`** es una plataforma de base de datos multimodelo NoSQL de siguiente generación construida desde cero en **Java 25 LTS**, concebida para romper las barreras tradicionales de latencia, consumo de memoria y sobrecarga de recolección de basura presentes en los motores de base de datos JVM convencionales.

#### Pilares Fundamentales
1. **Rendimiento Nativo sin Salir de Java:** Mediante el uso del **Project Panama (Foreign Function & Memory API - JEP 454)**, `JettraStore` asigna, manipula y descarga buffers de memoria nativa fuera del montículo (*off-heap*) con el mismo nivel de rendimiento y control que motores escritos en C++ o Rust, eliminando de raíz las pausas por Garbage Collection.
2. **Arquitectura Multimodelo Cohesiva:** En lugar de operar como un conjunto de bases de datos aisladas, los 8 motores de almacenamiento de `JettraStore` comparten un bus de persistencia unificado basado en el formato de archivo **`.jettra`**, permitiendo **referencias cruzadas directas intra e inter-motor** con carga perezosa (*lazy load*) o ansiosa (*eager load*).
3. **Resiliencia Predictiva y Desbordamiento en Anillo:** `JettraStore` supervisa proactivamente la densidad de RAM de cada nodo. Si el nodo primario se aproxima a su límite de saturación, el motor muta automáticamente a una topología de **anillo distribuido**, particionando y transfiriendo bloques de datos *off-heap* a los nodos secundarios en tiempo real mediante `jettraGRPC`, erradicando situaciones de Out-Of-Memory (OOM).
4. **Seguridad Absoluta Criptográfica:** Toda la comunicación interna (clúster Raft, migración en anillo) y externa (Drivers, Shell CLI, consolas FX y herramientas de benchmarking) se encuentra estrictamente protegida por **`JettraJWT`**.


### 1.2 Arquitectura de Almacenamiento y Formato de Archivo `.jettra`

#### 1.2.1 Persistencia Inmutable LSM y MemTable Off-Heap

El núcleo de persistencia de `JettraStore` se basa en un diseño **Log-Structured Merge-tree (LSM-Tree)** con separación estricta entre memoria volátil nativa y almacenamiento inmutable en disco:

```
[ Cliente / Driver ] 
         │ (Escritura mediante jettraGRPC + JettraJWT)
         ▼
[ Write-Ahead Log (WAL) .jettra ] ──(fsync secuencial off-heap)──▶ [ Disco Físico ]
         │
         ▼
[ Active MemTable (Off-Heap Panama Arena) ] ── (Límite: 128 MB)
         │
         ▼ (Saturación de MemTable)
[ Immutable MemTable ]
         │
         ▼ (Flushing Thread Pool con Virtual Threads)
[ SSTables Nivel 0 (.jettra) ] ──▶ [ Compaction Service (Leveled) ] ──▶ [ SSTables L1..Ln (.jettra) ]
```

* **MemTable Nativa:** Cada MemTable se gestiona dentro de un `Arena.ofShared()` de Project Panama. El tamaño predeterminado de cada MemTable activa es de **$128\text{ MB}$**, con un límite global estricto de memoria en RAM de **$2\text{ GB}$** asignado a buffers activos.
* **Write-Ahead Log (WAL):** Las mutaciones entrantes se serializan en un archivo `.jettra` de registro secuencial directo mediante canales de E/S nativa sin copia intermedia en memoria gestionada de la JVM.

#### 1.2.2 Estructura Binaria de Archivos `.jettra`

Todos los archivos generados por `JettraStore` (segmentos WAL, SSTables de datos, índices y diccionarios de metadatos) llevan la extensión unificada **`.jettra`**. El formato está alineado a límites de 64 bits para lectura directa mediante `MemorySegment`:

| Offset (Bytes) | Longitud | Campo | Descripción |
|---|---|---|---|
| `0x00 - 0x07` | 8 bytes | `MAGIC_HEADER` | Constante binaria `0x4A45545452415354` (`"JETTRAST"`) |
| `0x08 - 0x09` | 2 bytes | `VERSION` | Versión del formato binario (ej. `0x0100` = v1.0) |
| `0x0A - 0x0B` | 2 bytes | `ENGINE_ID` | Identificador del motor (Doc, Vector, Graph, etc.) |
| `0x0C - 0x13` | 8 bytes | `BLOCK_COUNT` | Número de bloques de registros en el archivo |
| `0x14 - 0x1B` | 8 bytes | `METADATA_OFFSET` | Puntero absoluto al pie de metadatos y Bloom Filter |
| `0x1C - 0x23` | 8 bytes | `SPARSE_INDEX_PTR`| Puntero al índice disperso al final del archivo |
| `0x24 - ...` | Variable | `DATA_PAYLOAD` | Bloques comprimidos (ZSTD/Off-heap Direct Buffer) |
| `EOF - 64` | 64 bytes | `CRC64_FOOTER` | Suma de comprobación criptográfica y estado de cierre |

#### 1.2.3 Índices Secundarios Dispersos y Filtros de Bloom

1. **Filtros de Bloom de Alta Precisión:** Embebidos directamente en la cabecera de cada archivo `.jettra`, calculados con funciones hash murmur3-128 con una tasa de falsos positivos configurada al $0.01\%$. Permiten descartar lecturas en disco sin necesidad de consultar el índice físico.
2. **Índices Secundarios Dispersos (*Sparse Indexes*):** Mapeados a la memoria virtual de la JVM a través de `MemorySegment.map(FileChannel.MapMode.READ_ONLY, ...)`. Los índices dispersos almacenan un puntero por cada bloque de $4\text{ KB}$ de registros ordenados, reduciendo el consumo de memoria del índice en un $92\%$ comparado con índices densos tradicionales.


### 1.3 Optimizaciones Extremas de la JVM (Java 25+)

#### 1.3.1 Project Panama: Foreign Function & Memory (FFM) API

`JettraStore` prescinde completamente de `sun.misc.Unsafe` y `ByteBuffer` indirectos, utilizando el estándar **JEP 454 (Foreign Function & Memory API)** disponible de forma permanente en las especificaciones modernas de Java:

```java
package io.jettra.store.engine.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public final class NativeMemTableSegment implements AutoCloseable {
    private final Arena arena;
    private final MemorySegment nativeBuffer;
    private long writeOffset = 0;

    public NativeMemTableSegment(long capacityBytes) {
        // Asignación de memoria nativa fuera del Heap de la JVM
        this.arena = Arena.ofShared();
        this.nativeBuffer = arena.allocate(capacityBytes, 8); // 8-byte alignment
    }

    public synchronized void appendEntry(byte engineId, byte[] key, byte[] payload) {
        long entrySize = 1 + 4 + key.length + 4 + payload.length;
        if (writeOffset + entrySize > nativeBuffer.byteSize()) {
            throw new IllegalStateException("Segment capacity exceeded");
        }
        
        nativeBuffer.set(ValueLayout.JAVA_BYTE, writeOffset++, engineId);
        nativeBuffer.set(ValueLayout.JAVA_INT, writeOffset, key.length);
        writeOffset += 4;
        MemorySegment.copy(MemorySegment.ofArray(key), 0, nativeBuffer, writeOffset, key.length);
        writeOffset += key.length;

        nativeBuffer.set(ValueLayout.JAVA_INT, writeOffset, payload.length);
        writeOffset += 4;
        MemorySegment.copy(MemorySegment.ofArray(payload), 0, nativeBuffer, writeOffset, payload.length);
        writeOffset += payload.length;
    }

    @Override
    public void close() {
        // Liberación determinística e inmediata de memoria física sin esperar al GC
        arena.close();
    }
}
```

#### 1.3.2 Compact Object Headers (JEP 450)

En Java 25+, las cabeceras de objetos en el montículo se reducen de 128 o 96 bits a solo **64 bits** (8 bytes) mediante la bandera `-XX:+UseCompactObjectHeaders`.
* **Impacto en JettraStore:** Los millones de objetos en memoria asociados con referencias de punteros, metadatos y descriptores de consultas experimentan una reducción del $25\%$ al $35\%$ en la huella de memoria total, lo que maximiza la densidad de registros procesables en un único nodo antes de activar la migración en anillo.

#### 1.3.3 Recolección de Basura ZGC de Latencia Sub-Milisegundo

`JettraStore` está afinado específicamente para **Generational ZGC**:
* La recolección concurrente maneja montículos de decenas o cientos de gigabytes con tiempos de pausa menores a **$1\text{ ms}$**.
* Dado que el $90\%$ de los datos en caliente residen fuera del Heap mediante Panama FFM, el montículo de la JVM se dedica exclusivamente a transacciones de Virtual Threads, compilación JIT y objetos de vida ultra-corta.

#### 1.3.4 CRaC y CRIU para Arranque Instantáneo

**Coordinated Restore at Checkpoint (CRaC)** es una iniciativa del proyecto OpenJDK diseñada para erradicar por completo los tiempos de calentamiento (*warm-up*) de la JVM mediante la tecnología del kernel Linux **CRIU (Checkpoint/Restore in Userspace)**.

En un motor de base de datos distribuida de alto rendimiento como `JettraStore`, el arranque convencional involucra:
1. Validación de configuraciones y topología Raft.
2. Inicialización de los gestores de memoria nativa Off-Heap (`JettraMemory` y `OffHeapPageManager`).
3. Mapeo de índices dispersos y tablas hash abiertas en memoria virtual.
4. Precalentamiento de estructuras y optimizaciones del compilador JIT (C2/Graal).

Con **CRaC y CRIU**, todo este proceso se ejecuta una sola vez: el estado completo de la memoria, descriptores de archivos abiertos y estructuras nativas se congela en un conjunto de archivos de imagen en disco (*checkpoint*). Posteriormente, la base de datos se restaura de forma **instantánea en frío en menos de $18\text{ milisegundos}$**.

---

##### 1. Requisitos Previos del Sistema Operativo y JVM

* **Sistema Operativo:** Linux (kernel $\ge 5.9$) con soporte para CRIU (`CONFIG_CHECKPOINT_RESTORE=y`).
* **Privilegios:** Permisos para operar CRIU (`sudo`, capacidades `CAP_CHECKPOINT_RESTORE`, `CAP_SYS_ADMIN` o `SYS_PTRACE`).
* **JVM con soporte CRaC:** Distribuciones como **BellSoft Liberica JDK with CRaC** (ej. Liberica 25 / 21) o **Azul Zulu with CRaC**.

---

##### 2. Guía de Ejecución Paso a Paso desde Consola (Host Linux)

###### Paso 1: Crear el Directorio para los Archivos de Checkpoint
```bash
mkdir -p /opt/jettra/crac-checkpoint
chmod 777 /opt/jettra/crac-checkpoint
```

###### Paso 2: Iniciar JettraStore en Modo Preparación de Checkpoint
Se ejecuta JettraStore con todas las banderas de optimización de Java 25 (Lilliput, ZGC, Panama FFM y el modo de almacenamiento deseado) agregando la bandera `-XX:CRaCCheckpointTo`:

```bash
java --enable-preview \
     --enable-native-access=ALL-UNNAMED \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+UseCompactObjectHeaders \
     -XX:+UseZGC \
     -Xms2g -Xmx4g \
     -Djettra.storage.mode=OFF_HEAP \
     -XX:CRaCCheckpointTo=/opt/jettra/crac-checkpoint \
     -jar target/JettraStore-1.0-SNAPSHOT.jar
```

El servidor iniciará normalmente, precargará las bases de datos de muestra (`example_factura_db`), inicializará los puertos gRPC (50001) y REST (60001), y quedará a la espera del comando de congelamiento.

###### Paso 3: Disparar el Checkpoint con `jcmd`
Desde otra consola o terminal, una vez verificado que el nodo está listo y caliente:

```bash
# Opción A: Mediante el nombre del artefacto JAR
jcmd target/JettraStore-1.0-SNAPSHOT.jar JDK.checkpoint

# Opción B: Mediante búsqueda del PID del proceso JettraStore
jcmd $(pgrep -f JettraStore) JDK.checkpoint
```

*Efecto:* La JVM detiene los hilos de ejecución de manera coordinada, vuelca la imagen completa del proceso a `/opt/jettra/crac-checkpoint` y finaliza.

###### Paso 4: Restaurar Instantáneamente desde el Checkpoint (`-XX:CRaCRestoreFrom`)
Para reiniciar JettraStore en cualquier momento posterior (o en nuevos pods/nodos):

```bash
java -XX:CRaCRestoreFrom=/opt/jettra/crac-checkpoint
```

*Resultado:* El servidor JettraStore queda completamente operativo en **~18 ms**, con todos los índices, buffers de páginas Off-Heap y conexiones REST inmediatamente listos sin penalización de arranque ni pausas de JIT.

---

##### 1.3. Integración en `Dockerfile`

El `Dockerfile` de `JettraStore` está configurado para admitir tanto la ejecución estándar como la restauración instantánea mediante la variable de entorno `JETTRA_CRAC_RESTORE`:

```dockerfile
# Directorio dedicado para persistencia del snapshot CRIU
RUN mkdir -p /jettra/checkpoint && chown -R 1000:1000 /jettra/checkpoint
VOLUME ["/jettra/data", "/jettra/checkpoint"]

ENV JETTRA_CRAC_CHECKPOINT_PATH=/jettra/checkpoint \
    JETTRA_CRAC_RESTORE=false

# Si JETTRA_CRAC_RESTORE=true y existen checkpoints, arranca en ~18 ms
ENTRYPOINT ["sh", "-c", "if [ \"$JETTRA_CRAC_RESTORE\" = \"true\" ] && [ -d \"$JETTRA_CRAC_CHECKPOINT_PATH\" ] && [ \"$(ls -A $JETTRA_CRAC_CHECKPOINT_PATH 2>/dev/null)\" ]; then \
        echo '[CRaC] Restaurando JettraStore instantáneamente desde checkpoint CRIU en '$JETTRA_CRAC_CHECKPOINT_PATH'...'; \
        exec java -XX:CRaCRestoreFrom=$JETTRA_CRAC_CHECKPOINT_PATH; \
    else \
        exec java $JAVA_OPTS -Djettra.storage.mode=${JETTRA_STORAGE_MODE:-OFF_HEAP} -jar /app/JettraStore.jar; \
    fi"]
```

---

##### 4. Integración en `docker-compose.yml`

Para que el contenedor tenga permisos de congelar y restaurar la memoria con CRIU en el kernel de Linux, `docker-compose.yml` incluye las capacidades `cap_add` y el volumen montado para los checkpoints:

```yaml
services:
  jettra-node-01:
    image: jettrastore:1.0
    container_name: jettra-node-01
    cap_add:
      - CHECKPOINT_RESTORE   # Permite invocar CRIU restore en Linux kernel
      - SYS_PTRACE           # Permite inspección y reanudación de descriptores
    security_opt:
      - seccomp:unconfined   # Permite llamadas de sistema CRIU sin restricciones
    environment:
      - JETTRA_STORAGE_MODE=OFF_HEAP
      - JETTRA_CRAC_RESTORE=${JETTRA_CRAC_RESTORE:-false}
      - JETTRA_CRAC_CHECKPOINT_PATH=/jettra/checkpoint
      - JAVA_OPTS=--enable-preview --enable-native-access=ALL-UNNAMED -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:+UseZGC -Xms2g -Xmx4g -Djettra.storage.mode=OFF_HEAP
    volumes:
      - jettra_data_node01:/jettra/data
      - jettra_checkpoint_node01:/jettra/checkpoint
```

###### Generar el Checkpoint dentro del Contenedor Docker:
```bash
# 1. Iniciar el clúster normalmente
docker compose up -d

# 2. Conectarse al contenedor y ejecutar el checkpoint
docker exec -it jettra-node-01 jcmd /app/JettraStore.jar JDK.checkpoint
```

###### Reiniciar el Clúster Instantáneamente con CRaC:
```bash
JETTRA_CRAC_RESTORE=true docker compose up -d
```
En los logs de Docker (`docker compose logs -f jettra-node-01`) se observará la reanudación ultra-rápida:
```text
[CRaC] Restaurando JettraStore instantáneamente desde checkpoint CRIU en /jettra/checkpoint...
Restored in 18.42 ms.
REST Service running with Virtual Threads on http://0.0.0.0:60001/
```

#### 1.3.5 Manipulación de Colecciones con `JettraCollection` y Prevención Rigurosa de `OutOfMemoryError`

El ecosistema estipula el **uso obligatorio y exclusivo de `JettraCollection`** (`io.jettra.collections.*`) para toda la manipulación interna de datos, índices, metadatos y búferes:

1. **Uso Obligatorio de `JettraCollection`:**
   * **`UnifiedMap<K, V>` y `UnifiedSet<E>`:** Implementan mapas y conjuntos basados en direccionamiento abierto plano (*open addressing flat table*). Eliminan el 100% de los objetos nodo (`HashMap$Node` o `ConcurrentHashMap$Node`), reduciendo el overhead de contenedor de ~36-40 bytes a solo **~5.3 bytes por entrada (ahorro de más del 80% en RAM)**.
   * **Colecciones Primitivas Especializadas (`IntLongHashMap`, `LongLongHashMap`, `IntArrayList`, `LongArrayList`):** Operan sobre arreglos contiguos primitivos o punteros `MemorySegment`, eliminando el *boxing/unboxing* de tipos primitivos (`long`, `int`, `double`) y la presión sobre el Garbage Collector.

2. **Prohibición Estricta de Conversiones Masivas:**
   * Queda **terminantemente prohibido** ejecutar conversiones masivas de colecciones enteras (como `.toArray()` sobre mapas concurrentes o volcado indiscriminado de millones de registros a listas globales en el Heap) para prevenir caídas por `java.lang.OutOfMemoryError: Java heap space`.

3. **Flujos Perezosos (*Lazy Load / Streams*) y Procesamiento por Lotes (*Chunking*):**
   * Las operaciones de consulta masiva, escaneo completo y reconstrucción de índices (`findAll` o reconstrucción de árboles) deben operar estrictamente mediante **flujos perezosos (`LazyDocumentList`, `stream()`, cursores bajo demanda)**.
   * El procesamiento de grandes volúmenes debe realizarse en **lotes acotados (*chunks* de tamaño fijo, ej. 1,000 a 5,000 elementos)** utilizando estructuras de `JettraCollection` con capacidad preasignada y de ciclo de vida efímero que se liberan inmediatamente tras su indexación o persistencia física.


### 1.4 Integración Nativa del Motor Off-Heap `JettraMemory` y `JettraStore-OffHeap`

`JettraStore` integra en su núcleo la arquitectura **`JettraMemory`** (`io.jettra.memory.*`), un motor de persistencia y almacenamiento binario nativo fuera del montículo (Off-Heap) basado en **Project Panama FFM API**.

#### 1.4.1 Arquitectura Panama LSM y Segmentos Binarios
* **Almacenamiento Off-Heap Directo:** Los registros y cargas binarias no residen como instancias en el Garbage Collector. Se alojan en `MemorySegment` creados dentro de un `Arena.ofShared()` o `Arena.ofConfined()`, garantizando cero impacto en el Heap.
* **Estructura LSM en Disco:** Cada base de datos gestiona su instancia `JettraMemoryEngine` con una estructura LSM propia:
  * Segmentos activos (`active.bin`) y segmentos congelados (*immutable segments*).
  * Índices en memoria mapeados con punteros de 64 bits.
  * Write-Ahead Log (`wal.bin`) con `fsync` selectivo.

#### 1.4.2 APIs Nativas en JettraDatabase y JettraClient
Tanto `JettraDatabase` en el core como `JettraClient` en el driver exponen métodos directos:
* `getMemoryEngine()`: Obtiene el motor `JettraMemoryEngine` subyacente.
* `putOffHeapBinary(key, data)` / `putBinary(...)`: Persiste un binario directamente sin serialización intermedia en heap.
* `getOffHeapBinary(key)` / `getBinary(...)`: Recupera un binario por clave de forma $O(1)$.
* `getMemoryMetrics()`: Provee métricas detalladas de fragmentación, segmentos activos y bytes off-heap en uso.
* `compactMemory()`: Ejecuta la compactación LSM en background fusionando segmentos obsoletos.

---


#### 1.4.3 Modos Triples de Almacenamiento: `JVM-RAM`, `DISK-MEMORY` y `OFF-HEAP`
JettraStore permite operar cada base de datos bajo tres paradigmas complementarios y conmutables en tiempo real:

1. **Modo `JVM-RAM` (Predeterminado):**
   * Almacenamiento y procesamiento en las áreas de memoria Stack y Heap de la Máquina Virtual de Java.
   * Utiliza colecciones optimizadas `UnifiedMap` con cabeceras compactas (Compact Object Headers de 64 bits de Project Lilliput) y Generational ZGC.
   * Ideal para cargas de trabajo de baja latencia con límites de memoria holgados.

2. **Modo `DISK-MEMORY` (Integración Nativa JettraMemory):**
   * Persistencia directa en disco mediante la arquitectura LSM de `JettraMemory` y mapeo fuera del Heap con Project Panama (`MemorySegment`).
   * Cero consumo de Heap para la carga de datos masivos, erradicando fallos por `OutOfMemoryError`.
   * Los registros se escriben a través de `JettraStoreConnector` con compatibilidad multimodelo total.
   * Conmutable en caliente por API (`db.setStorageMode(mode)`), en consola Shell (`STORAGE_MODE DISK_MEMORY`), en la interfaz gráfica `JettraStoreFX` o mediante `microprofile-config.properties` (`storage.mode = DISK_MEMORY`).

3. **Modo `OFF-HEAP` (Integración Nativa JettraStore-OffHeap):**
   * Almacenamiento nativo de ultra-alto rendimiento en regiones contiguas de memoria virtual gestionadas por Project Panama (`Arena` y `MemorySegment`).
   * **Arquitectura Slotted-Page:** Páginas binarias de 64 KB o 256 KB con directorio de slots que crecen bidireccionalmente.
   * **Zero-Copy Deserialization:** Extracción selectiva de campos primitivos mediante VarHandles no alineados sin instanciar objetos intermedios en el Heap de la JVM.
   * **Gestión Determinista RAII:** Ciclo de vida transaccional acotado con `JettraTransactionContext` que libera instantáneamente en $O(1)$ la memoria nativa sin esperar ciclos de GC.
   * Activación vía `microprofile-config.properties` (`storage.mode = OFF_HEAP` o `jettra.storage.mode = OFF_HEAP`), en consola Shell (`STORAGE_MODE OFF-HEAP`) o por código Java (`config.setStorageMode(StorageMode.OFF_HEAP)`).

---

#### 1.4.4 Módulo Nativo `JettraStore-OffHeap`: Arquitectura Slotted-Page, Zero-Copy y RAII Transaccional

El módulo `JettraStore-OffHeap` (`io.jettra.store.offheap.*`) introduce las técnicas más avanzadas de sistemas de bases de datos de bajo nivel (como las empleadas en PostgreSQL, RocksDB y WiredTiger) directamente sobre el entorno de ejecución de **Java 25+**:

##### 1. Layout de la Página Ranurada (`OffHeapPageBuffer`)

Cada página se reserva en memoria nativa con alineación a 8 bytes y un tamaño estándar de 64 KB (o 256 KB para grandes registros):

```
┌────────────────────────────────────────────────────────────────────────┐
│ Page Header (32 Bytes):                                                │
│  - Magic Number: 0x4A45545452415047 ("JETTRAPG", 8B)                   │
│  - Page ID: int64 (8B)                                                 │
│  - Transaction ID: int64 (8B)                                          │
│  - Slot Count: int32 (4B)                                              │
│  - Free Space Pointer: int32 (4B, apunta al final de la página)        │
├────────────────────────────────────────────────────────────────────────┤
│ Slot Directory:                                                        │
│  - Slot 0: [Offset: int32 (4B), Length: int32 (4B)]                   │
│  - Slot 1: [Offset: int32 (4B), Length: int32 (4B)]                   │
│  - Slot N: ... (crece hacia abajo)                                     │
│                                                                        │
│                      Espacio Libre Contiguo                            │
│                                                                        │
│  - Registro 1 (Payload crudo)                                          │
│  - Registro 0 (Payload crudo) (crece hacia arriba desde el final)      │
└────────────────────────────────────────────────────────────────────────┘
```

##### 2. Deserialización Selectiva Cero-Copia (`Zero-Copy Deserialization`)

En lugar de deserializar un documento o registro completo a objetos Java en el Heap, `OffHeapPageBuffer` permite consultar campos específicos mediante *VarHandles* tipados (`ValueLayout.JAVA_INT_UNALIGNED`, `ValueLayout.JAVA_LONG_UNALIGNED`, `ValueLayout.JAVA_DOUBLE_UNALIGNED`):

```java
// Lectura directa de campos en memoria nativa sin tocar el Heap
long id = page.readLong(slot, 0);           // Offset 0: ID (int64)
int items = page.readInt(slot, 8);          // Offset 8: Cantidad (int32)
double total = page.readDouble(slot, 12);   // Offset 12: Total (float64)
String code = page.readUtf8String(slot, 20, 8); // Offset 20: Código (8B UTF-8)
```

##### 3. Gestión Determinista del Ciclo de Vida Transaccional (Patrón RAII)

Mediante `JettraTransactionContext`, las operaciones complejas o transacciones multi-registro reservan memoria confinada al hilo (`Arena.ofConfined()`):

```java
// Toda la memoria asignada a esta transacción desaparece de forma instantánea al salir del bloque
try (JettraTransactionContext tx = database.newTransactionContext(txId)) {
    OffHeapPageBuffer page = tx.createPage(1L);
    int slot = page.insertRecord(recordBytes);
    
    // Trabajo con vistas directas cero-copia
    MemorySegment slice = page.getRecordSlice(slot);
    // ...
} // -> Cierre determinista de la Arena nativa en O(1) tiempo: ¡Sin pausas de GC!
```

---

#### 1.4.5 Ventajas de Rendimiento, Latencia y Ahorro Extremo de Recursos

| Dimensión | Enfoque Tradicional JVM | Con `JettraStore-OffHeap` + Lilliput | Beneficio Cuantitativo |
|---|---|---|---|
| **Cabecera de Objeto** | 16 bytes (128 bits) | **8 bytes (64 bits)** con `-XX:+UseCompactObjectHeaders` | **-50% en overhead de cabecera** por cada metadato. |
| **Presión sobre el Heap** | Almacena datos, índices y tuplas en Heap | **100% de datos en memoria nativa** fuera del Heap | **Cero presión de Heap** para millones de registros. |
| **Pausas del Garbage Collector** | Frecuentes *Stop-The-World* pausas de 10-150 ms | **Latencia predecible < 1 ms** con Generational ZGC | **Eliminación del jitter** de latencia en transacciones. |
| **Deserialización de Lectura** | Deserializa objetos completos con GC allocation | **Lectura cero-copia vía `MemorySegment.asSlice()`** | **+300% de rendimiento** en consultas y escaneos masivos. |
| **Liberación de Memoria** | Asíncrona, dependiente de ciclos de GC | **Determinista inmediata (RAII)** al cerrar la `Arena` | **Memoria devuelta de inmediato** al sistema operativo. |

---

#### 1.4.6 Matriz de Factibilidad e Integración en los Demás Proyectos del Ecosistema

Se ha evaluado exhaustivamente la viabilidad técnica de extender `JettraStore-OffHeap` a los demás proyectos presentes en `jettrastorecontainers`:

| Proyecto | Estado de Integración | Factibilidad | Alcance y Beneficios Clave |
|---|---|---|---|
| **`JettraStore`** | **Totalmente Integrado y Probado** | **100% (Producción)** | Soporte nativo de `StorageMode.OFF_HEAP`, `KeyValueEngine` respaldado por `OffHeapSlottedMap`, `DocumentEngine` con persistencia directa a páginas, `OffHeapPageManager` con persistencia NIO contigua y `JettraTransactionContext` transaccional. |
| **`JettraStoreDriver`** | **Altamente Factible** | **100% (Recomendado)** | Los clientes pueden enviar y recibir buffers `MemorySegment` nativos directamente hacia/desde sockets de red o IPC local (memoria compartida), evitando el boxing de `byte[]` en el cliente. |
| **`JettraStoreMeter`** | **Directamente Integrable** | **100% (Inmediato)** | Permite incorporar escenarios de benchmarking comparativo (`JVM-RAM` vs `DISK-MEMORY` vs `OFF-HEAP`) midiendo throughput de inserción masiva, consumo de RSS del proceso y pausas de GC. |
| **`JettraStoreShell`** | **Directamente Integrable** | **100% (Inmediato)** | Permite añadir comandos CLI nativos: `STORAGE_MODE OFF-HEAP`, `SHOW OFFHEAP METRICS` (bytes totales asignados, páginas activas, fragmentación de slots) y pruebas de inserción Slotted-Page. |
| **`JettraMemory`** | **Complementario y Sinérgico** | **100% (Compatible)** | Mientras `JettraMemory` se enfoca en persistencia LSM con compactación continua en disco, `JettraStore-OffHeap` proporciona el motor de páginas ranuradas contiguas (Slotted Pages) en RAM/disco y contextos transaccionales RAII. |
| **`JettraStorePolice3D` & `JettraStoreExplorer`** | **Integrable para Telemetría** | **100% (Visual)** | Monitorización gráfica en 3D de la densidad de páginas off-heap, número de slots libres vs utilizados, y visualización en tiempo real de la apertura/cierre de Arenas transaccionales. |

---

#### 1.4.7 Guía Operativa de Activación de los Tres Modos: JettraStore, JettraShell y JettraDriver

JettraStore unifica tres arquitecturas de almacenamiento complementarias que pueden elegirse globalmente o alternarse en caliente para cada base de datos:

1. **Modo `JVM-RAM` (Predeterminado):** Memoria RAM gestionada por la JVM (Heap y Stack) potenciada por `UnifiedMap` y cabeceras compactas de 8 bytes de Project Lilliput.
2. **Modo `DISK-MEMORY` (Integración Nativa JettraMemory):** Motor directo en disco con arquitectura LSM basada en Project Panama (`MemorySegment`), diseñado para erradicar el consumo de heap en almacenamiento masivo.
3. **Modo `OFF-HEAP` (Integración Nativa JettraStore-OffHeap):** Motor de páginas ranuradas nativas contiguas (Slotted-Page de 64 KB / 256 KB) con Project Panama (`Arena` y `MemorySegment`), deserialización selectiva cero-copia y liberación transaccional determinista RAII.

A continuación se detalla la activación de cada modo en los tres componentes fundamentales del ecosistema:

---

##### 1. Activación al Ejecutar el Servidor `JettraStore`

El motor de `JettraStore` evalúa el modo de almacenamiento en el arranque siguiendo una jerarquía estricta: Propiedades del Sistema (`-D`) $\to$ Variables de Entorno $\to$ Archivo `microprofile-config.properties` $\to$ Valor por defecto (`JVM_RAM` o `OFF_HEAP`).

###### A. Mediante archivo de configuración `microprofile-config.properties`:
En el archivo `microprofile-config.properties` ubicado en la raíz del proyecto o en el directorio de configuración (`config/microprofile-config.properties`):

```properties
# ==============================================================================
# CONFIGURACIÓN DEL MODO DE ALMACENAMIENTO DE JETTRASTORE
# ==============================================================================
# Opciones disponibles:
#   JVM_RAM     -> Modo memoria RAM estándar de la JVM (Heap & Stack).
#   DISK_MEMORY -> Motor de disco directo Off-Heap LSM (JettraMemory).
#   OFF_HEAP    -> Motor de páginas ranuradas nativas Slotted-Page (JettraStore-OffHeap).
# ==============================================================================

# 1. Para activar el Modo JVM-RAM (Predeterminado):
jettra.storage.mode = JVM_RAM

# 2. Para activar el Modo DISK-MEMORY (JettraMemory LSM):
# jettra.storage.mode = DISK_MEMORY

# 3. Para activar el Modo OFF-HEAP (JettraStore-OffHeap Slotted-Page):
# jettra.storage.mode = OFF_HEAP
```

###### B. Mediante variable de entorno del sistema operativo:
Útil en despliegues con Docker, Kubernetes o scripts de arranque continuo:

```bash
# Activar Modo JVM-RAM:
export JETTRA_STORAGE_MODE=JVM_RAM

# Activar Modo DISK-MEMORY:
export JETTRA_STORAGE_MODE=DISK_MEMORY

# Activar Modo OFF-HEAP:
export JETTRA_STORAGE_MODE=OFF_HEAP
```

###### C. Mediante parámetro en la línea de comandos de Java (`-D`):
```bash
# Ejemplo activando Modo OFF-HEAP:
java -Djettra.storage.mode=OFF_HEAP -jar JettraStore.jar
```

###### D. Comando de Ejecución Completo con Banderas JVM Mandatorias (Java 25+):
Para garantizar la compactación de cabeceras de objetos a 8 bytes y el acceso a memoria nativa sin restricciones de seguridad:

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

###### E. Despliegue en Docker y Docker Compose con Ajustes de `storage.mode`:
Tanto el `Dockerfile` como el `docker-compose.yml` de JettraStore vienen configurados de fábrica con estos flags y variables de entorno parametrizables:

```yaml
# En docker-compose.yml:
environment:
  - JETTRA_STORAGE_MODE=${JETTRA_STORAGE_MODE:-OFF_HEAP}
  - JAVA_OPTS=--enable-preview --enable-native-access=ALL-UNNAMED -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:+UseZGC -Xms2g -Xmx4g -Djettra.storage.mode=${JETTRA_STORAGE_MODE:-OFF_HEAP}
```

Para arrancar el clúster en el modo deseado:
```bash
# Modo OFF_HEAP (Predeterminado)
JETTRA_STORAGE_MODE=OFF_HEAP docker compose up -d

# Modo DISK_MEMORY (Direct Disk LSM)
JETTRA_STORAGE_MODE=DISK_MEMORY docker compose up -d

# Modo JVM_RAM (Heap estándar)
JETTRA_STORAGE_MODE=JVM_RAM docker compose up -d
```

---

##### 2. Activación e Interacción desde `JettraStoreShell`

La consola interactiva CLI de `JettraStoreShell` permite consultar y conmutar el modo de almacenamiento en caliente tanto para la base de datos seleccionada como a nivel global, sin necesidad de reiniciar el servidor.

###### A. Consultar el Modo Actual:
```text
JettraStore (admin@127.0.0.1:50001/facturacion_db)> STORAGE_MODE
```
O con el comando equivalente:
```text
JettraStore (admin@127.0.0.1:50001/facturacion_db)> SHOW STORAGE_MODE
```

*Salida en pantalla:*
```text
==============================================================================================
                MODO DE ALMACENAMIENTO ACTIVO PARA 'facturacion_db'                           
==============================================================================================
  Modo Actual      : OFF_HEAP (OFF-HEAP)
  Descripción      : Motor de páginas nativas Slotted-Page Off-Heap FFM (JettraStore-OffHeap)
  Motor Off-Heap   : JettraMemory (LSM Panama FFM) / JettraStore-OffHeap (Slotted-Page RAII)
  Comando para conmutar: STORAGE_MODE <JVM_RAM | DISK_MEMORY | OFF_HEAP>
==============================================================================================
```

###### B. Conmutar a Modo `JVM-RAM` (Heap Estándar):
```text
JettraStore (admin@127.0.0.1:50001/facturacion_db)> STORAGE_MODE JVM-RAM
```
*Respuesta:*
```text
[STORAGE_MODE] Modo de almacenamiento para 'facturacion_db' configurado a: JVM-RAM (Memoria RAM estándar de la JVM (Heap & Stack))
```

###### C. Conmutar a Modo `DISK-MEMORY` (JettraMemory LSM en Disco):
```text
JettraStore (admin@127.0.0.1:50001/facturacion_db)> STORAGE_MODE DISK-MEMORY
```
*Respuesta:*
```text
[STORAGE_MODE] Modo de almacenamiento para 'facturacion_db' configurado a: DISK-MEMORY (Motor de disco directo Off-Heap LSM (JettraMemory))
```

###### D. Conmutar a Modo `OFF-HEAP` (JettraStore-OffHeap Slotted-Page):
```text
JettraStore (admin@127.0.0.1:50001/facturacion_db)> STORAGE_MODE OFF-HEAP
```
*Respuesta:*
```text
[STORAGE_MODE] Modo de almacenamiento para 'facturacion_db' configurado a: OFF-HEAP (Motor de páginas nativas Slotted-Page Off-Heap FFM (JettraStore-OffHeap))
```

*(Nota: También se admite la sintaxis con el prefijo `SET`, por ejemplo: `SET STORAGE_MODE OFF-HEAP`).*

---

##### 3. Activación y Programación desde `JettraStoreDriver`

El driver Java `JettraStoreDriver` proporciona control programático fluido y tipado para configurar el modo de almacenamiento predeterminado de nuevas bases de datos, conmutar bases de datos existentes en caliente y acceder a las funciones Cero-Copia de `JettraStore-OffHeap`.

###### A. Configuración Inicial en el Builder del Cliente:
Al instanciar el cliente con `JettraClientConfig.builder()`, se define el modo predeterminado para todas las bases de datos creadas por el cliente:

```java
import io.jettra.driver.JettraClient;
import io.jettra.driver.config.JettraClientConfig;
import io.jettra.store.core.StorageMode;

// 1. Configurar cliente para operar nativamente en modo OFF-HEAP
JettraClientConfig config = JettraClientConfig.builder()
    .addClusterNode("127.0.0.1", 50001)
    .credentials("admin", "admin-jettra")
    .storageMode(StorageMode.OFF_HEAP) // Opciones: StorageMode.JVM_RAM, StorageMode.DISK_MEMORY, StorageMode.OFF_HEAP
    .enableVirtualThreads(true)
    .build();

JettraClient client = JettraClient.connect(config);
```

###### B. Conmutación en Caliente por Base de Datos:
```java
// Conmutar una base de datos específica a modo OFF-HEAP
client.setStorageMode("telemetria_db", StorageMode.OFF_HEAP);

// Conmutar otra base de datos a DISK-MEMORY
client.setStorageMode("historico_db", StorageMode.DISK_MEMORY);

// Conmutar a JVM-RAM
client.setStorageMode("cache_db", StorageMode.JVM_RAM);
```

###### C. Conmutación Global para Todas las Bases de Datos:
```java
client.setGlobalStorageMode(StorageMode.OFF_HEAP);
```

###### D. Consulta del Modo Activo:
```java
StorageMode mode = client.getStorageMode("telemetria_db");
if (mode.isOffHeap()) {
    System.out.println("La base de datos opera con páginas ranuradas nativas Off-Heap (Cero GC).");
}
```

###### E. Aprovechamiento de Lecturas Cero-Copia (`MemorySegment`) en Modo Off-Heap:
Cuando una base de datos opera en `StorageMode.OFF_HEAP`, el motor `KeyValueEngine` almacena registros en páginas `OffHeapPageBuffer` y permite lecturas directas tipo *Slice* sin instanciar arreglos intermedios en el Heap:

```java
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.engine.models.KeyValueEngine;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

JettraDatabase db = client.getDatabase("telemetria_db");
KeyValueEngine kv = db.getKeyValueEngine("sensores");

// Inserción off-heap directa
byte[] metricPayload = "{\"device\":\"SENSOR-99\",\"temp\":24.8}".getBytes(StandardCharsets.UTF_8);
kv.put("sensor:99", metricPayload);

// Lectura Cero-Copia sin impacto en el Garbage Collector:
MemorySegment slice = kv.getSlice("sensor:99");
System.out.printf("Registro nativo en offset 0x%x (%d bytes)%n", slice.address(), slice.byteSize());
```

###### F. Transacciones con Liberación Determinista RAII:
```java
import io.jettra.store.offheap.JettraTransactionContext;
import io.jettra.store.offheap.OffHeapPageBuffer;

// Bloque RAII: Toda la memoria nativa se devuelve al sistema operativo en O(1) al cerrar
try (JettraTransactionContext tx = db.newTransactionContext(8888L)) {
    OffHeapPageBuffer page = tx.createPage(1L);
    int slot = page.insertRecord("Transacción Crítica".getBytes(StandardCharsets.UTF_8));
    
    // Operaciones sobre la página en la Arena confinada...
    MemorySegment rec = page.getRecordSlice(slot);
} // -> tx.close() automático: Cero objetos pendientes para el GC
```


### 1.5 Mecanismo Dinámico de Anillo Distribuido por Saturación de Memoria

#### 1.5.1 Detección Preventiva de Umbrales de RAM

Cada nodo de `JettraStore` ejecuta un monitor de recursos en tiempo real que calcula la tasa de utilización de memoria:

$$\text{Tasa de Ocupación} = \frac{\text{RAM Off-Heap Asignada} + \text{Heap Activo}}{\text{Límite Máximo Configurado en } microprofile-config.properties}$$

* **Umbral Seguro ($< 70\%$):** Modo local estándar; las lecturas y escrituras se atienden en el nodo local con replicación Raft normal.
* **Umbral de Alerta Preventiva ($70\% - 85\%$):** `JettraPolice` emite notificaciones de advertencia y prepara las tablas de partición del anillo.
* **Umbral Crítico de Saturación ($\ge 85\%$):** Activación inmediata del **Motor de Anillo Distribuido**.

#### 1.5.2 Transición Automática a Motor de Anillo

Al superar el umbral crítico, el nodo principal no bloquea la admisión de datos ni arroja errores de Out-Of-Memory. En su lugar:

```
[ Nodo Principal (Líder) ] ---(Saturación >= 85% RAM)---▶ [ Disparo de Transición de Anillo ]
          │                                                               │
          ├─────────────────────────┬─────────────────────────────────────┤
          ▼                         ▼                                     ▼
 [ Stream Off-Heap ]       [ Stream Off-Heap ]                   [ Descompresión Local ]
          │                         │                                     │
          ▼                         ▼                                     ▼
[ Nodo Secundario 1 ]     [ Nodo Secundario 2 ]                 [ RAM Nodo Principal Cae a < 45% ]
(Recibe 50% partición)    (Recibe 50% partición)                (Continúa coordinando clúster)
```

1. **Topología de Anillo Dinámico:** El clúster pasa de un modelo cliente-servidor tradicional a un anillo virtual distribuido consistente (Consistent Hash Ring).
2. **Transferencia Directa de Bloques Off-Heap:** El nodo saturado toma las *Immutable MemTables* más antiguas y las transmite bit a bit mediante `jettraGRPC` a los nodos secundarios sin deserializar a objetos Java.
3. **Equilibrio de Recursos:** Los nodos secundarios integran los bloques en sus respectivas áreas de almacenamiento `.jettra` y memoria disponible, absorbiendo la carga de forma balanceada. El consumo de RAM en el nodo principal desciende por debajo del $45\%$ en segundos.


### 1.6 Arquitectura e Implementación de `JettraId` (Clave Primaria Interna por Defecto)

**`JettraId`** (`io.jettra.store.core.JettraId`) es el identificador único interno y clave primaria por defecto de **JettraStore**. Posee una longitud fija de **16 bytes** (representación canónica hexadecimal en minúsculas de **32 caracteres**) codificada en orden **big-endian**:

| Desplazamiento | Tamaño | Campo | Descripción |
| :--- | :---: | :--- | :--- |
| Bytes `0..3` | **4 bytes** | **Timestamp Unix** | Segundos desde `1970-01-01T00:00:00Z`. Permite ordenación aproximada por creación y extracción con `createdAt()`. |
| Bytes `4..8` | **5 bytes** | **ID de Proceso / Máquina** | Valor aleatorio de 40 bits generado una vez al arrancar la JVM mediante `SecureRandom`, evitando colisiones entre nodos o procesos concurrentes. |
| Bytes `9..12` | **4 bytes** | **Código de Base de Datos y Bucket** | Hash determinista FNV-1a de 32 bits calculado sobre `databaseName + ":" + bucketName` para aislar el espacio de claves de cada base de datos y bucket. |
| Bytes `13..15` | **3 bytes** | **Contador Incremental de Proceso** | Contador atómico de 24 bits (`AtomicInteger` enmascarado con `0xFFFFFF`, inicializado con semilla aleatoria) que garantiza unicidad en inserciones simultáneas dentro del mismo segundo con Virtual Threads. |

#### Uso de `JettraId` en Java 25+ (`JettraStore`)
```java
import io.jettra.store.core.JettraId;
import io.jettra.store.core.JettraDatabase;
import java.util.Map;

// 1. Generación explícita e inspección de metadatos
JettraId id = JettraId.generate("ventas_db", "Facturas");
String hex = id.toHexString();            // 32 caracteres hexadecimales en minúsculas
byte[] raw = id.toBytes();                // 16 bytes big-endian
JettraId parsed = JettraId.fromHex(hex);  // Parseo y validación
var created = parsed.createdAt();         // Instant extraído de los primeros 4 bytes
int scopeHash = parsed.databaseBucketHash();
int seq = parsed.counter();

// 2. Inserción automática sin ID (JettraStore genera JettraId, lo guarda en _id y lo retorna)
String assignedId = db.insertBucketRecord("DOCUMENT", "Facturas", null, Map.of("cliente", "ACME", "total", 1500.0));

// 3. Inserción con ID personalizado y protección contra duplicados de clave primaria
String customId = db.insertBucketRecord("DOCUMENT", "Facturas", "FAC-2026-001", Map.of("cliente", "Beta"));
// Un segundo insertBucketRecord con "FAC-2026-001" lanza IllegalStateException ([PRIMARY KEY VIOLATION])
```


---

## 2. Modelo de Datos, Buckets, Índices y Consultas (LQL / JettraSQL)

### 2.1 Soporte Multimodelo y Referencias Cruzadas (Intra e Inter-Engine)

#### 2.1.1 Los 8 Motores Nativos Integrados

| Motor | Tipo de Datos | Caso de Uso Óptimo | Formato en Archivo `.jettra` |
|---|---|---|---|
| **Document** | Documentos JSON / BSON | Esquemas flexibles, catálogos NoSQL | BSON nativo comprimido |
| **KeyValue** | Clave binaria $\rightarrow$ Valor | Caché de ultra-baja latencia, contadores | Bloques Hash contiguos |
| **Columnar** | Columnas y proyecciones | Analítica OLAP, agregaciones masivas | Run-Length Encoding columnar |
| **TimeSeries** | Marcas de tiempo + Telemetría | Métricas IoT, monitorización de logs | Delta-of-Delta + Gorilla |
| **Geospatial** | Coordenadas 2D/3D, Polígonos | Servicios GIS, cálculo de proximidad | R-Tree espacial con Haversine |
| **Pure Object / Records**| Clases y Java 25 Records | Persistencia directa de entidades Java | Serialización tipada zero-copy |
| **Graph** | Vértices, Aristas y Propiedades | Redes sociales, detección de fraude | Lista de Adyacencia CSR |
| **Vector** | Embeddings de coma flotante | Búsqueda semántica IA, similitud coseno | HNSW + Product Quantization |

#### 2.1.2 Referencias Cruzadas Multimodelo

`JettraStore` permite enlazar entidades entre el mismo motor (**intra-engine**) o a través de diferentes motores (**inter-engine**).

**Ejemplo de Referencia Inter-Engine:**
Un documento en el motor de Documentos (`users`) que referencia un vector en el motor Vectorial (`biometrics`) y un vértice en el motor de Grafos (`social_graph`):

```json
{
  "_id": "usr_99812",
  "name": "Carlos Mendoza",
  "email": "carlos@jettra.io",
  "_ref_vector": "vector::biometrics#emb_99812",
  "_ref_graph": "graph::social_graph#vertex_usr_99812"
}
```

#### 2.1.3 Estrategias de Carga: Lazy Load vs Eager Load

* **Lazy Load (Carga Perezosa - Predeterminada):** La referencia se almacena como un descriptor ligero `JettraRef<T>`. El registro referenciado no se lee del disco ni se transmite por la red hasta que la aplicación invoca explícitamente `.resolve()` o accede al campo correspondiente. Esto minimiza el consumo de RAM y el tráfico de red en consultas masivas.
* **Eager Load (Carga Ansiosa):** La consulta resuelve y ensambla inmediatamente todas las entidades referenciadas en un único paso de ejecución, optimizando los casos donde el cliente requiere el árbol completo del objeto de negocio.


### 2.2 Buckets Ilimitados (`NONE` vs `DEFINED`) e Índices por Bucket (`JettraBucketSchema` / `JettraIndexManager`)

#### 2.2.1 Arquitectura de Buckets Ilimitados por Engine (`JettraBucketSchema`)
Cada base de datos de **JettraStore** permite crear una cantidad **ilimitada de buckets** dentro de cada uno de sus motores (`DOCUMENT`, `JAVA_RECORD`, `KEY_VALUE`, `VECTOR`, `GRAPH`, `TIMESERIES`, `COLUMNAR`, `GEOSPATIAL`, `RELATIONAL`). Los metadatos del catálogo de buckets se persisten de forma atómica en `<jettra.storage.path>/<db>_buckets.json`.

Cada bucket opera bajo uno de dos modos de estructura:

1. **`NONE` (Esquema libre / flexible):** Permite insertar documentos o registros con cualquier conjunto de atributos sin validación rígida.
   - *Ejemplo:* Base `pruebadb`, engine `DOCUMENT`, bucket `Persona`, estructura `NONE`:
     - Registro 1: `{"id": 1, "name": "aristides"}`
     - Registro 2: `{"apellido": "Perez", "pais": "Panama"}`
2. **`DEFINED` (Estructura definida y validada):** Almacena una especificación de atributos y reglas de negocio que todo registro debe cumplir antes de ser persistido:
   - *Ejemplo:* Base `pruebadb`, engine `JAVA_RECORD`, bucket `Pais`, estructura `DEFINED`:
     ```json
     [
       { "atribute_name": "name", "type_atribute": "String", "jettra_rules": "NOT_NULL", "description": "nombre" },
       { "atribute_name": "siglas", "type_atribute": "String", "jettra_rules": "NOT_NULL", "description": "siglas" }
     ]
     ```
   - **Tipos soportados (`type_atribute`):** `String`, `Integer`/`Int`, `Long`, `Double`/`Float`/`Decimal`, `Boolean`, `List`/`Array`, `Map`/`JSON`, `Date`/`Object`.
   - **Reglas soportadas (`jettra_rules`, separables por coma):** `NOT_NULL`, `REQUIRED`, `NOT_EMPTY`, `NOT_BLANK`, `POSITIVE`, `NON_NEGATIVE`, `EMAIL`, `UNIQUE`, `MIN:<n>`, `MAX:<n>`, `MIN_LENGTH:<n>`, `MAX_LENGTH:<n>`, `REGEX:<patrón>`.

#### 2.2.2 Índices por Bucket (`JettraIndexManager`)
Cada bucket puede mantener múltiples índices secundarios (`BTREE`, `HASH`, `SPARSE`, `VECTOR_HNSW`, `SPATIAL_R_TREE`, únicos o no únicos). Las operaciones `upsertBucketRecord` y `deleteBucketRecord` en `JettraDatabase` actualizan automáticamente el índice invertido en memoria (`onDocumentUpsert` / `onDocumentDelete`) y propagan la creación o eliminación de índices en el clúster Raft.


### 2.3 Lenguajes de Consulta: JettraQueryLanguage (LQL) y JettraSQL

#### 2.3.1 JettraQueryLanguage (LQL) - Estilo Fluent Streams

Diseñado para desarrolladores Java modernos, `LQL` replica la elegancia de la Stream API:

```java
// Consulta tipada en LQL sobre el motor Document y Vector
JettraResults<User> results = db.from("users", User.class)
    .filter(u -> u.status().equals("ACTIVE") && u.balance() > 1500.0)
    .withSimilarity("biometrics", targetEmbedding, 0.85) // Inter-engine vector lookup
    .fetchMode(FetchMode.LAZY)
    .limit(50)
    .execute();
```

#### 2.3.2 JettraSQL - Dialecto SQL de Alto Rendimiento

Para integración con ecosistemas heredados y consolas analíticas, `JettraSQL` provee sintaxis ANSI SQL ejecutada directamente sobre las estructuras `.jettra`:

```sql
SELECT 
    u._id, 
    u.name, 
    u.email, 
    VECTOR_SIMILARITY(u._ref_vector, '[0.12, -0.45, 0.89, ...]') AS score
FROM users AS u
WHERE u.status = 'ACTIVE' AND u.balance > 1500.0
ORDER BY score DESC
LIMIT 50;
```


### 2.4 Motor Cuantitativo y Analítico: Agregaciones, Matemáticas, Finanzas, Estadística y Álgebra Vectorial

JettraStore incorpora en su núcleo arquitectónico un motor analítico y cuantitativo de alto rendimiento (`io.jettra.store.calc`), diseñado para ejecutar operaciones matemáticas, estadísticas, financieras, vectoriales y agregaciones multidimensionales con latencia sub-milisegundo sin requerir procesamiento externo ni bibliotecas pesadas de terceros.

#### 2.4.1 Motor de Agregaciones y Agrupamiento Multidimensional (`JettraAggregation`)

El motor `JettraAggregation` implementa algoritmos de agregación basados en particionamiento hash y streaming perezoso con control anti-saturación de memoria coordinado con `JettraPolice`:

* **Funciones de Agregación Nativas Soportadas:**
  * `COUNT`: Conteo de registros totales o valores no nulos.
  * `SUM`: Sumatoria acumulativa de alta precisión.
  * `AVG` / `MEAN`: Media aritmética del grupo.
  * `MIN` / `MAX`: Valores mínimo y máximo locales del grupo.
  * `MEDIAN` / `MED`: Mediana muestral exacta calculada sobre buckets ordenados.
  * `MODE`: Moda (valor más frecuente).
  * `RANGE`: Rango o amplitud (Max - Min).
  * `IQR`: Rango intercuartílico (P75 - P25).
  * `STDDEV` / `STD`: Desviación estándar muestral (N-1).
  * `VARIANCE` / `VAR`: Varianza muestral.
  * `SKEWNESS` / `SKEW`: Coeficiente de asimetría muestral de Fisher-Pearson.
  * `KURTOSIS` / `KURT`: Curtosis de exceso.
  * `P50`, `P90`, `P95`, `P99` / `P<n>`: Percentiles exactos sobre la distribución del grupo.
  * `FIRST` / `LAST`: Primer y último valor registrado en el bucket.

* **Sintaxis SQL Estándar:**
  ```sql
  SELECT categoria, SUM(monto) AS total_ventas, AVG(monto) AS promedio, MEDIAN(monto) AS mediana
  FROM facturas 
  WHERE estado = 'TIMBRADA'
  GROUP BY categoria;
  ```

* **Comando Directo Shell / CLI:**
  ```bash
  AGGREGATE facturas GROUP BY estado SUM total AVG total MEDIAN total COUNT
  ```

* **API Fluente en `JettraClient` y `JettraDriver`:**
  ```java
  var res = client.aggregateSum("main_db", "facturas", "total", "categoria");
  var custom = client.aggregate("main_db", "ventas", List.of("region", "vendedor"), 
      List.of(
          new AggregateSpec("SUM", "monto", "total_ventas"),
          new AggregateSpec("MEDIAN", "monto", "mediana_ventas"),
          new AggregateSpec("COUNT", "*", "total_pedidos")
      )
  );
  ```

---

#### 2.4.2 Motor Matemático Cuantitativo y Evaluador de Expresiones (`JettraMath`)

`JettraMath` provee rutinas aritméticas escalares de precisión IEEE-754 y un evaluador de expresiones matemáticas integrado (`JettraMath.eval(...)`):

* **Operaciones Aritméticas y Raíces:** `sqrt(x)`, `cbrt(x)`, `pow(base, exp)`, `mod(x, y)`, `abs(x)`, `sign(x)`, `round(x, decimals)`, `ceil(x)`, `floor(x)`, `clamp(val, min, max)`.
* **Combinatoria y Teoría de Números:**
  * `factorial(int n)`: Cálculo exacto de permutaciones ($n!$).
  * `gcd(long a, long b)`: Máximo Común Divisor mediante algoritmo euclidiano acelerado.
  * `lcm(long a, long b)`: Mínimo Común Múltiplo.
* **Geometría y Trigonometría:** `hypot(x, y)`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`, `toDegrees`, `toRadians`.
* **Evaluador de Expresiones Matemáticas:**
  ```java
  double valor = JettraMath.eval("cbrt(64) + sqrt(144) * 2 - hypot(3, 4) + fact(5)");
  // valor = 4.0 + 24.0 - 5.0 + 120.0 = 143.0
  ```
* **Comando Shell:**
  ```bash
  MATH cbrt(1000) + pow(2, 10) * sqrt(25)
  ```

---

#### 2.4.3 Motor Financiero Cuantitativo (`JettraFinance`)

Módulo diseñado para sistemas contables, entidades bancarias, software de facturación electrónica y análisis cuantitativo de inversiones:

* **Préstamos y Amortizaciones:**
  * `pmt(rate, nper, pv)`: Cuota mensual fija en sistema francés.
  * `amortizationSchedule(principal, annualRate, periods)`: Genera el cronograma detallado con desglose de cuota, capital amortizado, interés devengado y saldo insoluto para cada período.
  * `loanAffordability(monthlyPayment, annualRate, years)`: Capacidad máxima de endeudamiento a partir de la cuota mensual admisible.
* **Valor del Dinero en el Tiempo e Inversiones:**
  * `pv(rate, nper, pmt, fv)`: Valor presente de anualidades o flujos futuros.
  * `fv(rate, nper, pmt, pv)`: Valor futuro acumulado.
  * `npv(rate, cashFlows...)`: Valor Presente Neto (VAN).
  * `irr(cashFlows...)`: Tasa Interna de Retorno (TIR) aproximada por Newton-Raphson de convergencia rápida.
  * `mirr(financeRate, reinvestRate, cashFlows...)`: TIR modificada considerando tasas asimétricas de financiamiento y reinversión.
  * `cagr(beginningValue, endingValue, periods)`: Tasa de Crecimiento Anual Compuesto.
  * `roi(gain, cost)`: Retorno sobre la inversión en porcentaje.
  * `paybackPeriod(initialInvestment, annualInflows...)`: Período exacto de recuperación de la inversión en años.
* **Interés y Depreciaciones:**
  * `compoundInterest(principal, annualRate, compoundsPerYear, years)`: Interés compuesto $A = P(1 + r/n)^{nt}$.
  * `simpleInterest(principal, annualRate, years)`: Interés simple $A = P(1 + rt)$.
  * `effectiveRate(nominalRate, periodsPerYear)`: Tasa efectiva anual (EAR).
  * `depreciationStraightLine(cost, salvageValue, lifeYears)`: Depreciación lineal anual.
  * `depreciationDoubleDeclining(cost, salvageValue, lifeYears, period)`: Depreciación por saldo doble decreciente.

---

#### 2.4.4 Motor Estadístico Descriptivo e Inferencial (`JettraStatistics`)

* **Tendencia Central:** `mean(data)`, `median(data)`, `mode(data)`, `weightedMean(data, weights)`, `geometricMean(data)`, `harmonicMean(data)`.
* **Dispersión y Forma:**
  * `variance(data, sample)` y `stddev(data, sample)`.
  * `iqr(data)`: Rango intercuartílico.
  * `standardError(data)`: Error estándar de la media ($\sigma / \sqrt{n}$).
  * `skewness(data)`: Asimetría de la distribución.
  * `kurtosis(data)`: Curtosis de colas pesadas.
  * `percentile(data, p)`: Percentil $p$ de interpolación lineal continua.
* **Análisis Bivariado y Machine Learning:**
  * `covariance(x, y, sample)`: Covarianza muestral.
  * `correlation(x, y)`: Coeficiente de correlación lineal de Pearson $r \in [-1.0, 1.0]$.
  * `linearRegression(x, y)`: Ajuste por mínimos cuadrados ordinarios ($y = mx + b$) con coeficiente de determinación $R^2$.
  * `zscore(value, mean, stddev)`: Normalización estadística estándar ($Z$).
* **Resumen Integral `summary(data)`:** Genera el objeto inmutable `StatsSummary` con métricas precomputadas en un único recorrido de datos con tiempo $O(N \log N)$.

---

#### 2.4.5 Motor de Álgebra Vectorial y Búsqueda Multidimensional (`JettraVectorMath`)

Diseñado para optimizar las operaciones de embeddings, modelos de lenguaje (LLM), visión artificial y análisis espacial:

* **Métricas de Similaridad y Distancia Vectorial:**
  * `dotProduct(v1, v2)`: Producto escalar multidimensional.
  * `norm(v)`: Norma euclidiana ($L_2$).
  * `l1Norm(v)`: Norma Manhattan ($L_1$).
  * `cosineSimilarity(v1, v2)`: Similaridad coseno normalizada en $[-1.0, 1.0]$.
  * `euclideanDistance(v1, v2)`: Distancia euclidiana $L_2$.
  * `manhattanDistance(v1, v2)`: Distancia Manhattan $L_1$.
  * `chebyshevDistance(v1, v2)`: Distancia de Chebyshev ($L_\infty$).
  * `minkowskiDistance(v1, v2, p)`: Distancia de Minkowski generalizada para cualquier parámetro $p \ge 1$.
* **Operaciones de Álgebra Lineal y Geometría 3D:**
  * `normalize(v)`: Normalización a vector unitario de longitud 1.0.
  * `add(v1, v2)` y `subtract(v1, v2)`: Suma y resta vectorial.
  * `multiplyScalar(v, scalar)`: Escalamiento uniforme.
  * `multiply(v1, v2)` y `divide(v1, v2)`: Multiplicación y división elemento a elemento (Producto de Hadamard).
  * `angle(v1, v2)` y `vectorAngleDegrees(v1, v2)`: Ángulo entre vectores en radianes y grados sexagesimales.
  * `crossProduct(v1, v2)`: Producto cruz tridimensional $ec{v}_1 	imes ec{v}_2$.
  * `projection(v, onto)` y `rejection(v, from)`: Descomposición ortogonal y proyecciones.
  * `centroid(vectors)`: Baricentro o centroide de una nube de vectores en $K$ dimensiones.


### 2.5 Caso de Estudio Masivo: Base de Datos de Facturación (3,000,000 Objetos)

Para validar el ecosistema bajo condiciones extremas de concurrencia y volumen de datos, `JettraStore` integra la base de datos de pruebas maestras **`example_factura_db`**:

#### 2.5.1 Estructura Multimodelo Interconectada (9 Buckets Especializados)
* **[DOCUMENT] `facturas`:** 1,000,000 de facturas electrónicas timbradas con referencias cruzadas `_ref_detalle`, `_ref_cliente`, `_ref_vector`, `_ref_folio`.
* **[DOCUMENT] `detalles_factura`:** 1,000,000 de renglones e items con precios, cantidades y subtotales.
* **[DOCUMENT] `clientes`:** 200,000 clientes corporativos con RFC/RUC y límites de crédito.
* **[KEYVALUE] `cache_folios`:** 300,000 folios fiscales persistidos para verificación O(1).
* **[VECTOR] `factura_embeddings`:** 200,000 vectores 3D indexados para análisis semántico por IA.
* **[GRAPH] `red_comercial`:** 200,000 vértices y aristas que conectan clientes con sus facturas.
* **[TIMESERIES] `volumen_facturacion`:** 50,000 métricas históricas de facturación temporal.
* **[GEOSPATIAL] `sucursales_fiscales`:** 25,000 puntos espaciales de coordenadas GIS.
* **[COLUMNAR] `analitica_fiscal`:** 25,000 filas de cálculo analítico de IVA y totales.

#### 2.5.2 Métricas de Rendimiento Verificadas
* **Tiempo Total de Inserción y Timbrado:** ~5,200 ms (utilizando hilos virtuales de Java 25).
* **Índices Secundarios:** `idx_fac_cliente` (HASH) y `idx_cli_rfc` (BTREE) construidos con almacenamiento compacto Singleton (Zero-Set), eliminando más de 200,000 colecciones intermedias.
* **Consultas SQL Paginadas y Shell Interactivo:** `SELECT * FROM clientes` responde en **0 ms** con acotamiento de seguridad anti-OOM gestionado por `JettraPolice`.
* **Navegación Interactiva de Páginas en JettraStoreShell:**
  * Comandos de desplazamiento: `FIRST` / `PRIMERO`, `PREV` / `ANTERIOR`, `NEXT` / `SIGUIENTE`, `LAST` / `ULTIMO`, `PAGE <n>`.
  * Configuración dinámica de registros por página: `PAGE_SIZE <n>` (e.g. `PAGE_SIZE 25`).
  * Barra de estado interactiva en consola que muestra el rango de registros visibles, página actual y comandos disponibles.
* **Interfaces Visuales de Alta Fidelidad:**
  * **`JettraStoreFX`:** Incorpora barra de consultas rápidas SQL/JQL, selector de tamaño de página desplegable (`10, 25, 50, 100, 250`), salto directo de página y tarjetas de telemetría de `JettraPolice` y `JettraMemory`.
  * **`JettraStorePoliceFX`:** Visualizador 3D inmersivo con plano cartesiano optimizado, cuadrantes marcados en alto contraste, radar de pulso dinámico en tiempo real y panel de eventos (HUD) con actualización automática cada segundo.


### 2.6 Guía Práctica de Operaciones: Bases de Datos, Buckets, Registros e Índices (`NUMERIC` y `AUTO_INCREMENT`)

Esta guía presenta flujos completos y ejecutables desde **JettraStoreShell**, **JettraStoreDriver (Java 25+)** y **JettraStoreExplorer**.

---

#### 2.6.1 Creación y Administración de Bases de Datos

##### Desde `JettraStoreShell`
```sql
-- Crear una base de datos y seleccionarla como activa
CREATE DATABASE comercial_db
USE comercial_db

-- Listar todas las bases de datos almacenadas en el nodo PRIMARY
SHOW DATABASES

-- Eliminar una base de datos
DROP DATABASE comercial_db
```

##### Desde `JettraStoreDriver` (Java 25+)
```java
JettraClientConfig cfg = JettraClientConfig.builder()
        .addClusterNode("127.0.0.1", 50001)
        .credentials("admin", "admin-jettra")
        .role("admin")
        .defaultDatabase("comercial_db")
        .build();

try (JettraClient client = JettraClient.connect(cfg)) {
    client.createDatabase("comercial_db");
    List<String> dbs = client.listDatabases();
}
```

---

#### 2.6.2 Administración de Buckets con Estructura (`DEFINED`) y Sin Estructura (`NONE`)

Cada engine (`DOCUMENT`, `JAVA_RECORD`, `KEY_VALUE`, `VECTOR`, `GRAPH`, `TIMESERIES`, `COLUMNAR`, `GEOSPATIAL`, `RELATIONAL`) soporta **buckets ilimitados**:

##### A. Bucket sin estructura fija (`NONE`)
```sql
CREATE BUCKET Persona ENGINE DOCUMENT STRUCTURE NONE
DESCRIBE BUCKET Persona
```
```java
client.createBucket("comercial_db", "DOCUMENT", "Persona", "NONE", "[]");
```

##### B. Bucket con estructura definida (`DEFINED`)
```sql
CREATE BUCKET Pais ENGINE JAVA_RECORD STRUCTURE DEFINED [{"atribute_name":"codigo","type_atribute":"Long","jettra_rules":"AUTO_INCREMENT","description":"Código autoincremental"},{"atribute_name":"name","type_atribute":"String","jettra_rules":"NOT_NULL","description":"Nombre del país"},{"atribute_name":"siglas","type_atribute":"String","jettra_rules":"NOT_NULL,MIN_LENGTH:2,MAX_LENGTH:3","description":"Siglas ISO"}]
DESCRIBE BUCKET Pais
```
```java
String paisSchema = """
    [
      { "atribute_name": "codigo", "type_atribute": "Long", "jettra_rules": "AUTO_INCREMENT", "description": "Código autoincremental" },
      { "atribute_name": "name", "type_atribute": "String", "jettra_rules": "NOT_NULL", "description": "Nombre del país" },
      { "atribute_name": "siglas", "type_atribute": "String", "jettra_rules": "NOT_NULL,MIN_LENGTH:2,MAX_LENGTH:3", "description": "Siglas ISO" }
    ]
    """;
client.createBucket("comercial_db", "JAVA_RECORD", "Pais", "DEFINED", paisSchema);
```

---

#### 2.6.3 Administración de Registros (Inserción, Consulta, Actualización, Eliminación y Autoincremento)

##### Inserción en bucket `NONE` (esquema libre)
```sql
INSERT BUCKET Persona {"id": 1, "name": "aristides"}
INSERT BUCKET Persona {"apellido": "Perez", "pais": "Panama"}
```

##### Inserción con validación `DEFINED` y campo `AUTO_INCREMENT`
```sql
-- Al omitir 'codigo', el motor autogenera secuencialmente 1, 2, 3...
INSERT BUCKET Pais {"name": "Panama", "siglas": "PA"}
INSERT BUCKET Pais {"name": "Costa Rica", "siglas": "CR"}

-- Si falta un atributo obligatorio NOT_NULL, el motor rechaza la escritura con [VALIDATION ALERT]
INSERT BUCKET Pais {"name": "Colombia"}
```

##### CRUD completo desde `JettraStoreDriver`
```java
// Insertar (autogenera 'codigo' si existe índice AUTO_INCREMENT)
String id = client.upsertBucketRecord("comercial_db", "JAVA_RECORD", "Pais", "pa-1",
        new LinkedHashMap<>(Map.of("name", "Panama", "siglas", "PA")));

// Actualizar
client.upsertBucketRecord("comercial_db", "JAVA_RECORD", "Pais", "pa-1",
        new LinkedHashMap<>(Map.of("codigo", 1L, "name", "República de Panamá", "siglas", "PA")));

// Eliminar (actualiza automáticamente los índices del bucket)
client.deleteBucketRecord("comercial_db", "JAVA_RECORD", "Pais", "pa-1");
```

---

#### 2.6.4 Administración de Índices (Estándar, Numéricos y Autoincrementables)

JettraStore soporta índices `BTREE`, `HASH`, `SPARSE`, **`NUMERIC`** (enteros/decimales con consultas exactas y por rango $O(\log N)$) y **`AUTO_INCREMENT`** (secuencia atómica persistida en `<db>_sequences.json` y `<db>_indexes.json`, inmune a reinicios y failover).

##### Desde `JettraStoreShell`
```sql
-- Crear índice autoincrementable (único automáticamente)
CREATE BUCKET INDEX idx_pais_codigo ON Pais (codigo) TYPE AUTO_INCREMENT

-- Crear índice numérico para búsquedas exactas y por rango
CREATE BUCKET INDEX idx_prod_precio ON productos (precio) TYPE NUMERIC

-- Listar índices del bucket (incluye secuencia actual seq=N para AUTO_INCREMENT)
SHOW BUCKET INDEXES Pais
SHOW BUCKET INDEXES productos

-- Consultar por rango numérico inclusivo [min, max]
FIND NUMERIC productos precio 10.0 50.0

-- Eliminar índice
DROP BUCKET INDEX idx_prod_precio
```

##### Desde `JettraStoreDriver`
```java
client.createAutoIncrementIndex("comercial_db", "Pais", "idx_pais_codigo", "codigo");
client.createNumericIndex("comercial_db", "productos", "idx_prod_precio", "precio", false);

Set<String> idsEnRango = client.findIdsByNumericRange("comercial_db", "productos", "precio", 10.0, 50.0);
long secuenciaActual = client.getAutoIncrementCurrentValue("comercial_db", "Pais", "codigo");
client.dropBucketIndex("comercial_db", "idx_prod_precio");
```


### 2.7 Administración Avanzada de Buckets e Índices desde `JettraStoreShell` (RF-09)

Desde la consola interactiva **JettraStoreShell** es posible administrar el ciclo de vida completo de **Buckets** (tanto con estructura `DEFINED` como sin estructura `NONE`) y de **Índices** asociados a cada Bucket (incluyendo índices numéricos `NUMERIC` y autoincrementables `AUTO_INCREMENT`).

#### 1. Comandos de Buckets en `JettraStoreShell`

| Operación | Sintaxis | Descripción |
| :--- | :--- | :--- |
| **Crear Bucket** | `CREATE BUCKET <nombre> [ENGINE <engine>] [STRUCTURE NONE\|DEFINED <json_atributos>]` | Crea un bucket dentro del Engine (`DOCUMENT`, `JAVA_RECORD`, `KEY_VALUE`, `VECTOR`, `GRAPH`, `TIMESERIES`, `COLUMNAR`, `GEOSPATIAL`) con esquema libre (`NONE`) o estricto (`DEFINED`). |
| **Listar Buckets** | `SHOW BUCKETS` | Lista todos los buckets creados en la base de datos activa. |
| **Inspeccionar Estructura** | `DESCRIBE BUCKET <nombre>` / `SHOW BUCKET SCHEMA <nombre>` | Muestra el Engine, modo (`NONE`/`DEFINED`), atributos/reglas e índices del bucket. |
| **Insertar Registro** | `INSERT BUCKET <nombre> [ID <customId>] <json>` | Inserta y valida el registro contra la estructura del bucket (autogenerando `JettraId` si se omite `ID`). |
| **Eliminar Bucket** | `DROP BUCKET <nombre> [ENGINE <engine>]` | Elimina el bucket y sus metadatos de la base de datos activa. |

#### 2. Comandos de Índices sobre Buckets en `JettraStoreShell`

| Operación | Sintaxis | Descripción |
| :--- | :--- | :--- |
| **Crear Índice** | `CREATE BUCKET INDEX <nombre> ON <bucket> (<campo>) [TYPE BTREE\|HASH\|NUMERIC\|AUTO_INCREMENT\|SPARSE] [UNIQUE]` | Crea un índice sobre un bucket concreto. `AUTO_INCREMENT` fuerza unicidad y mantiene secuencia automática. |
| **Listar Índices** | `SHOW BUCKET INDEXES <bucket>` | Lista los índices asociados al bucket indicando campo, tipo, unicidad y entradas. |
| **Eliminar Índice** | `DROP BUCKET INDEX <nombre>` | Elimina el índice especificado de la base de datos activa. |

#### 3. Ejemplos Prácticos Paso a Paso

##### Escenario A: Bucket con estructura `DEFINED`, índices `AUTO_INCREMENT` y `NUMERIC`
```sql
CREATE DATABASE comercio_db
USE comercio_db

-- 1. Crear Bucket con estructura DEFINED en el Engine DOCUMENT
CREATE BUCKET Productos ENGINE DOCUMENT STRUCTURE DEFINED [{"atribute_name":"codigo","type_atribute":"Long","jettra_rules":"AUTO_INCREMENT","description":"Código secuencial"},{"atribute_name":"nombre","type_atribute":"String","jettra_rules":"NOT_NULL","description":"Nombre"},{"atribute_name":"precio","type_atribute":"Double","jettra_rules":"POSITIVE","description":"Precio unitario"}]
-- Salida esperada:
-- [SUCCESS] Bucket 'Productos' creado en DB 'comercio_db' | Engine: DOCUMENT | Estructura: DEFINED (3 atributo(s)).

-- 2. Crear índice autoincrementable y numérico sobre el Bucket Productos
CREATE BUCKET INDEX idx_prod_codigo ON Productos (codigo) TYPE AUTO_INCREMENT UNIQUE
CREATE BUCKET INDEX idx_prod_precio ON Productos (precio) TYPE NUMERIC

-- 3. Inspeccionar esquema e índices del Bucket
DESCRIBE BUCKET Productos
SHOW BUCKET INDEXES Productos

-- 4. Insertar registro válido (omite codigo para autogeneración secuencial y genera JettraId)
INSERT BUCKET Productos {"nombre":"Teclado Mecánico","precio":89.50}
-- Salida esperada:
-- [SUCCESS] Registro con JettraId '<32-hex>' insertado y validado en bucket 'Productos' (DOCUMENT).

-- 5. Intentar insertar registro inválido (precio negativo viola POSITIVE)
INSERT BUCKET Productos {"nombre":"Mouse","precio":-10.0}
-- Salida esperada:
-- [VALIDATION ALERT] Registro rechazado por estructura del bucket 'Productos': ...
```

##### Escenario B: Bucket sin estructura (`NONE`) y limpieza
```sql
-- 1. Crear Bucket flexible sin esquema fijo
CREATE BUCKET NotasLibres ENGINE DOCUMENT STRUCTURE NONE
-- Salida esperada:
-- [SUCCESS] Bucket 'NotasLibres' creado en DB 'comercio_db' | Engine: DOCUMENT | Estructura: NONE (0 atributo(s)).

-- 2. Insertar cualquier JSON libre (con o sin ID manual)
INSERT BUCKET NotasLibres {"titulo":"Recordatorio","prioridad":1,"tags":["urgente","ops"]}
INSERT BUCKET NotasLibres ID NOTA-01 {"contenido":"Nota con ID manual"}

-- 3. Eliminar índice o bucket cuando ya no se requieran
DROP BUCKET INDEX idx_prod_precio
DROP BUCKET NotasLibres ENGINE DOCUMENT
```


---

## 3. Seguridad, Supervisión (`JettraPolice`), Backup y Resiliencia

### 3.1 Seguridad Estricta: Autenticación, Superusuario y `JettraJWT`

#### 3.1.1 Superusuario Administrativo por Defecto (`admin` / `admin-jettra`)

Al inicializar una instancia o clúster de `JettraStore` por primera vez, el sistema provisiona de manera obligatoria la cuenta del superusuario con las siguientes credenciales exactas:
* **Username:** `admin`
* **Password:** `admin-jettra`
* **Rol:** `SUPER_ADMIN`
* **Privilegios:** Control total absoluto e irrestricto sobre bases de datos, engines, cluster management, backup/restore y seguridad.

> [!CAUTION]
> **Recomendación de Seguridad Crítica:**
> Por motivos de seguridad operativa, se recomienda enfáticamente modificar la contraseña predeterminada tras el primer inicio de sesión mediante el comando `ALTER USER admin IDENTIFIED BY '<nueva-clave-segura>'` en `JettraStoreShell` o a través del panel de seguridad en `JettraStoreFX`.

#### 3.1.2 Inviolabilidad y Jerarquía Máxima del Superusuario

1. El usuario `admin` posee la máxima prioridad en el sistema.
2. Ningún otro usuario, independientemente de sus privilegios asignados (`DB_ADMIN`, `OPERATOR`, `DEVELOPER`), tiene autorización para modificar, revocar, degradar roles o eliminar la cuenta `admin`.
3. Cualquier intento de ejecutar un comando de alteración sobre `admin` por parte de una sesión secundaria genera una excepción de seguridad inmediata `JettraSecurityException("Security violation: Superuser privileges cannot be altered by secondary users")` y emite una alerta crítica a través de `JettraPolice`.

#### 3.1.3 Arquitectura de Tokens `JettraJWT`

Toda solicitud entrante debe acompañarse de un token de cabecera `Authorization: JettraJWT <token>`.
* **Firma Criptográfica:** Algoritmo Ed25519 con claves rotadas periódicamente en memoria o HMAC-SHA512.
* **Estructura del Payload:**
```json
{
  "sub": "admin",
  "role": "SUPER_ADMIN",
  "cluster_id": "jettra-cluster-01",
  "permissions": ["*"],
  "iat": 1759140000,
  "exp": 1759226400,
  "jti": "d3b07384-d113-4f9e-bc22-e0f3e2b260d8"
}
```


### 3.2 Componente de Supervisión Preventiva: `JettraPolice`

#### 3.2.1 Ciclo de Vida del Daemon Autónomo

`JettraPolice` es un componente de supervisión preventiva que opera como un hilo demonio (*daemon thread*) de muy baja prioridad y bajo consumo computacional ($< 0.5\%$ de CPU):
* **Frecuencia de Muestreo:** Cada $500\text{ ms}$ sondea el estado de los descriptores de archivos `.jettra`, el avance de los punteros WAL, los buffers de memoria Panama y la latencia de red de los canales Raft.
* **Detección de Anomalías:** Emplea el motor `jettraRules` para evaluar condiciones de riesgo antes de que se manifiesten en fallos.

#### 3.2.2 Reglas Preventivas y Acciones Mitigadoras

1. **Prevención de Agotamiento de Espacio en Disco:** Si el directorio físico de almacenamiento configurado supera el $90\%$ de capacidad, `JettraPolice` activa automáticamente la compactación forzada de niveles SSTable y depuración de registros marcados con *tombstones*.
2. **Mitigación de Saturación de RAM:** Si la memoria del nodo alcanza el $75\%$, `JettraPolice` alerta a los subsistemas de clúster para pre-calentar los sockets de transferencia de anillo. Al alcanzar el $85\%$, ordena formalmente el desbordamiento de las MemTables hacia los nodos secundarios.
3. **Control de Intrusiones:** Detecta intentos reiterados de autenticación fallida o intentos ilegales de alteración del usuario `admin`, bloqueando las direcciones IP a nivel de socket de red.

#### 3.2.3 Activación y Configuración (`jettrapolice.active`)

El componente se controla de forma transparente en `microprofile-config.properties`:
```properties
# Habilitación del agente supervisor JettraPolice
jettrapolice.active = true
jettrapolice.interval.ms = 500
jettrapolice.ram.warning.threshold = 75
jettrapolice.ram.critical.threshold = 85
jettrapolice.auto.pagination.enabled = true
jettrapolice.max.safe.batch.size = 100
```

#### 3.2.4 Supervisión Predictiva de Heap y Prevención Autónoma Anti-OOM

Para evitar que una consulta masiva (e.g. `SELECT * FROM clientes` con 200,000 filas o escaneos de 1,000,000 de facturas) desborde el montículo de la JVM (`OutOfMemoryError: Java heap space`), `JettraPolice` incorpora el método predictivo **`evaluateHeapSafety(...)`**:

1. **Auditoría Predictiva de Memoria:**
   * Mide en tiempo real la memoria Heap disponible ($	ext{maxMemory} - (	ext{totalMemory} - 	ext{freeMemory})$).
   * Calcula el porcentaje de saturación actual del Heap y proyecta los bytes necesarios según los registros solicitados:
     $$	ext{estimatedBytes} = 	ext{recordsToLoad} 	imes \max(	ext{avgRecordSizeBytes}, 384	ext{ bytes})$$
2. **Acción Forzosa `AUTO_PAGINATE_LAZY`:**
   * Si la consulta no tiene límite o los bytes estimados superan el $20\%$ de la memoria disponible, o la saturación del Heap supera el $75\%$, JettraPolice interviene y **fuerza paginación automática**:
   * Calcula un tamaño de página seguro adaptativo asignando a lo sumo el $2\%$ de la memoria disponible restante.
   * Emite la alerta `HEAP_EXHAUSTION_PREVENTED` hacia el clúster y la interfaz 3D.
3. **Distribución de Carga con `LazyPagedCursor`:**
   * Permite iterar colecciones gigantescas bloque por bloque, posibilitando que el recolector de basura (ZGC) limpie cada bloque procesado con complejidad de memoria $O(1)$.


El componente se controla de forma transparente en `microprofile-config.properties`:
```properties
# Habilitación del agente supervisor JettraPolice
jettrapolice.active = true

# Intervalo de sondeo en milisegundos
jettrapolice.interval.ms = 500

# Umbral de advertencia de memoria RAM (porcentaje)
jettrapolice.ram.warning.threshold = 75
```

#### 3.2.5 Protocolo de Streaming por Chunks y Metadatos de Notificación (Sentinel)

Para evitar la saturación de memoria (*Heap Space*) cuando clientes o interfaces gráficas ejecutan operaciones masivas sobre colecciones de gran tamaño (e.g., `findAll()`, consultas JQL no restringidas o sentencias `SELECT *` sin cláusula `LIMIT`), `JettraStore` implementa un protocolo de comunicación basado en **Streaming por Chunks + Metadatos de Notificación de Sentinel**.

##### 3.2.5.1 Estructura de Metadatos: `JettraPoliceNotification`
Cuando el algoritmo predictivo de `JettraPolice` determina que una lectura masiva amenaza la estabilidad del Heap, genera un registro inmutable `JettraPoliceNotification` con los detalles diagnósticos de la intervención:
* `operation`: Identificador de la consulta en ejecución (ej. `SQL_SELECT`, `DOCUMENT_STREAM_ALL`).
* `targetBucket`: Nombre del bucket evaluado.
* `estimatedTotalRecords`: Cantidad total de registros presentes en el bucket.
* `safeBatchSize`: Tamaño de lote seguro calculado adaptativamente (ej. 100 registros por lote).
* `heapUsagePercent`: Porcentaje de saturación del Heap JVM al momento de la intervención.
* `availableMemoryMb`: Memoria disponible restante en Megabytes.
* `warningMessage`: Razón explícita y advertencia técnica del Sentinel.
* `timestamp`: Marca temporal instantánea de la activación.
* `forcedLazyPagination`: Bandera booleana que confirma que la paginación defensiva fue forzada de forma autónoma.

##### 3.2.5.2 Flujo Continuo: `StreamResponse<T>`
En el núcleo del motor (`DocumentEngine.streamAll()`, `JettraDatabase.streamBucket()`), los datos se leen y transmiten mediante `StreamResponse<T>`:
1. **Partición en Bloques Seguros:** La consulta se fragmenta en lotes de tamaño seguro (por defecto 100 registros).
2. **Liberación Iterativa para Garbage Collector (GC):** Cada chunk procesado es vaciado y dereferenciado de inmediato al iterar (`forEachChunk()`), permitiendo al recolector de basura (ZGC Generational en Java 25) recuperar memoria entre lote y lote sin retener la totalidad de los datos en el Heap.
3. **Entrega Transparente:** Provee adaptadores para iteración por lotes (`forEachChunk()`), iteración elemento a elemento (`forEachRecord()`), acumulación segura (`collectAll()`) y Streams de Java 25 (`stream()`).

```java
// Ejemplo de consumo en servidor o extensiones internas
try (StreamResponse<Map<String, Object>> stream = database.streamBucket("clientes", 10000)) {
    if (stream.isSentinelActivated()) {
        System.out.println("Sentinel activo: Lote seguro de " + stream.getSafeBatchSize() + " registros.");
    }
    stream.forEachChunk(chunk -> {
        // Procesa el lote de registros de forma aislada
        procesarLote(chunk);
        // Al terminar la lambda, el chunk queda libre para recolección inmediata por el GC
    });
}
```

##### 3.2.5.3 Sistema Desacoplado de Eventos: `JettraPoliceEventListener`
Para permitir que cualquier cliente gráfico (`JettraStoreFX`), consola de comandos (`JettraStoreShell`) o aplicación de negocio reciba notificaciones inmediatas del Sentinel sin alterar las firmas existentes de sus llamadas (`findAll()`, `sql()`, `jql()`), el driver (`JettraStoreDriver`) expone una interfaz funcional desacoplada:

```java
package io.jettra.driver.listener;

import io.jettra.store.police.JettraPoliceNotification;

@FunctionalInterface
public interface JettraPoliceEventListener {
    void onSentinelActivated(JettraPoliceNotification notification);
}
```

##### 3.2.5.4 Ejemplo Completo de Referencia en `JettraStoreExample`

El proyecto de ejemplos oficial `JettraStoreExample` incluye una demostración integral implementada en la clase **`io.jettra.examples.store.police.AntiOomStreamingSentinelExample`**, accesible mediante `JettraStoreExampleApp`:

```java
package io.jettra.examples.store.police;

import io.jettra.driver.listener.JettraPoliceEventListener;
import io.jettra.examples.store.driver.JettraDriver;
import io.jettra.examples.store.util.ConsoleColor;
import io.jettra.store.core.StorageMode;
import io.jettra.store.core.StreamResponse;
import io.jettra.store.engine.models.DocumentEngine;
import io.jettra.store.engine.query.JettraSQLProcessor;
import io.jettra.store.police.JettraPoliceNotification;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AntiOomStreamingSentinelExample {

    public static void run(JettraDriver driver) {
        ConsoleColor.printHeader("13. PROTECCIÓN ANTI-OOM: SENTINEL, STREAMING POR CHUNKS Y LISTENERS");

        String dbName = "streaming_sentinel_db";
        driver.getDatabase(dbName, StorageMode.JVM_RAM);

        // 1. Registro del Listener Desacoplado
        AtomicBoolean sentinelNotified = new AtomicBoolean(false);
        AtomicReference<JettraPoliceNotification> lastNotifRef = new AtomicReference<>();

        JettraPoliceEventListener listener = notification -> {
            sentinelNotified.set(true);
            lastNotifRef.set(notification);
            System.out.printf("🛡️ [SENTINEL ACTIVADO] Operación: %s | Bucket: %s%n",
                notification.operation(), notification.targetBucket());
            System.out.printf("   Lote seguro forzado: %d | Heap: %.1f%% | RAM libre: %d MB%n",
                notification.safeBatchSize(), notification.heapUsagePercent(), notification.availableMemoryMb());
        };

        driver.addPoliceEventListener(listener);

        // 2. Poblado de colección masiva de prueba (500 documentos)
        DocumentEngine catalog = driver.getDocumentEngine(dbName, "catalogo_masivo");
        for (int i = 1; i <= 500; i++) {
            catalog.insert("item_" + i, Map.of(
                "sku", "SKU-2026-" + i,
                "nombre", "Sensor Industrial Modelo #" + i,
                "precio", 49.99 + (i * 0.5),
                "stock", i * 10
            ));
        }

        // 3. Streaming por Chunks Seguro (StreamResponse<T>)
        try (StreamResponse<Map<String, Object>> stream = driver.streamFindAll(dbName, "catalogo_masivo")) {
            System.out.printf("Batch Seguro: %d | Sentinel Activo: %b%n",
                stream.getSafeBatchSize(), stream.isSentinelActivated());

            AtomicInteger chunkNum = new AtomicInteger(1);
            // forEachChunk libera explícitamente cada lote para el Garbage Collector
            stream.forEachChunk(chunk -> {
                System.out.printf("  • Chunk #%02d recibido con %d registros.%n",
                    chunkNum.getAndIncrement(), chunk.size());
            });
        }

        // 4. Consumo Transparente mediante findAll() sin alterar firmas
        List<Map<String, Object>> all = driver.findAll(dbName, "catalogo_masivo");
        System.out.println("driver.findAll() recuperó: " + all.size() + " registros.");

        // 5. Activación Automática de Sentinel en Consultas SQL no acotadas
        JettraSQLProcessor.QueryResult sqlRes = driver.sql(dbName, "SELECT * FROM catalogo_masivo");
        System.out.println("SQL Diagnóstico: " + sqlRes.message());
        System.out.println("Intervención Sentinel Confirmada: " + sentinelNotified.get());

        // Limpieza del listener
        driver.removePoliceEventListener(listener);
    }
}
```

##### 3.2.5.5 Impacto en la Arquitectura de Clientes UX
* **En `JettraStoreShell` (CLI):** Al detectar la notificación de Sentinel, imprime un marco de alerta con el porcentaje de saturación del Heap y la memoria restante, y renderiza los datos progresivamente por bloques para evitar saturar el búfer de la terminal.
* **En `JettraStoreFX` (GUI):** Captura el evento desacoplado para desplegar un Toast flotante animado (`FadeTransition`), actualiza la barra de estado en ámbar y alimenta la `TableView` por bloques de 50 registros para mantener la interfaz a 60 fps sin microcongelamientos.


### 3.3 Capacidades Nativas de Backup y Restore

#### 3.3.1 Procedimiento de Respaldo Hot-Snapshot

`JettraStore` permite realizar copias de seguridad consistentes en caliente sin detener el motor:
1. **Flushing Inmediato:** Se fuerza la congelación de la MemTable activa hacia un archivo SSTable inmutable `.jettra`.
2. **Creación de Hard-Links / Copia Directa:** Se genera un snapshot atómico en la ruta indicada mediante transferencias directas de bloques off-heap.
3. **Copia de Metadatos:** Se genera un archivo de manifiesto criptográfico `backup_manifest.jettra` con los hashes SHA-256 de todas las tablas e índices.

**Ejemplo mediante Shell CLI:**
```text
JettraStore> BACKUP DATABASE corporate_db TO '/backup/corporate_db_20261015.jettra_bak';
[SUCCESS] Snapshot created in 42ms. 14 SSTables and WAL flushed safely.
```

#### 3.3.2 Procedimiento de Restauración Consistente

El proceso de restauración valida la integridad de cada archivo `.jettra` antes de reabrir el motor:
1. Verificación de suma de comprobación `CRC64_FOOTER` de cada segmento.
2. Comprobación de consistencia del log Raft.
3. Reindexación automática de índices secundarios dispersos.

**Ejemplo mediante Shell CLI:**
```text
JettraStore> RESTORE DATABASE corporate_db FROM '/backup/corporate_db_20261015.jettra_bak';
[SUCCESS] Database 'corporate_db' restored successfully. Ready for transactions.
```


### 3.4 Métricas de Rendimiento y Microbenchmarking con JMH

#### 3.4.1 Integración de Java Microbenchmark Harness (JMH)

`JettraStore` incorpora clases de microbenchmark integradas con **JMH** para auditar el rendimiento en tiempo real y validar que no existan regresiones de rendimiento:
* Medición de latencia de escritura en `NativeMemTableSegment` (en nanosegundos).
* Rendimiento de lectura con Bloom Filter en archivos `.jettra`.
* Costo de resolución de referencias cruzadas Lazy vs Eager.
* Deserialización de vectores con operaciones SIMD de la Vector API de Java.

#### 3.4.2 Activación y Control (`jmh.metrics.active`)

Las métricas internas de microbenchmarking se activan o desactivan en tiempo de ejecución o compilación mediante la propiedad:
```properties
# Activa el recolector de métricas de precisión nanométrica JMH
jmh.metrics.active = true
```
Cuando se desactiva (`false`), el compilador JIT elimina los puntos de control de medición mediante *dead-code elimination*, asegurando cero impacto de sobrecarga (*zero runtime overhead*).


### 3.5 Ecosistema de Herramientas Avanzadas y Resiliencia de Plataforma (`JettraStoreMeter`, `JettraStorePoliceFX`, `JettraStoreFX`, `JettraGarbageCollector`)

#### 3.5.1 `JettraStoreMeter`: Pruebas de Estrés Concurrente Automatizadas con Maven

El módulo [`JettraStoreMeter`](file:///home/avbravo/NetBeansProjects/jettrastack_local/jettrastoreworkspace/JettraStoreMeter) proporciona un motor de pruebas de carga y estrés integrado directamente en el ciclo de vida de Maven (`mvn test -pl JettraStoreMeter`):

* **Escenarios Masivos Concurrente de Usuarios Virtuales:**
  * **25 Usuarios Simultáneos:** Carga base continua de consultas y validaciones sobre la base `example_factura_db`.
  * **50 Usuarios Simultáneos:** Carga media con mezcla de lecturas por clave primaria y consultas por índices secundarios.
  * **100 Usuarios Simultáneos:** Alta saturación transaccional con escrituras concurrentes e indexación HASH/BTREE.
  * **500 Usuarios Simultáneos:** Prueba extrema de saturación impulsada por **Virtual Threads de Java 25**, logrando tasas superiores a **420,000 operaciones por segundo** con $0\%$ de tasa de error.
* **Integración CI/CD:** Ejecutable desde la línea de comandos sin dependencias externas:
  ```bash
  mvn test -pl JettraStoreMeter -Dtest=JettraStoreMeterTest
  ```

---

#### 3.5.2 `JettraStorePoliceFX`: Plano Cartesiano en Primer Plano y Malla de Entidades Autónomas 3D

[`JettraStorePoliceFX`](file:///home/avbravo/NetBeansProjects/jettrastack_local/jettrastoreworkspace/JettraStorePoliceFX) es la consola de visualización dimensional y supervisión en tiempo real del motor `JettraPolice`:

* **Plano Cartesiano en Primer Plano (*Foreground Cartesian Plane*):** La cuadrícula espacial $XYZ$ se renderiza con prioridad visual frontal, destacando los 4 cuadrantes principales mediante colores de alto contraste cian, magenta y ámbar.
* **Malla de Entidades Autónomas (`AutonomousEntityMesh`):** Las entidades tridimensionales que representan bases de datos, buckets y particiones se mueven de manera autónoma y continua a través de fórmulas cinemáticas tridimensionales:
  * Curvas armónicas de Lissajous en el espacio 3D.
  * Órbitas elípticas con precesión giroscópica.
  * Barredores axiales de saturación de memoria.
  * Lemniscatas tridimensionales de sincronización Raft.
* **Radar Dinámico 3D y Panel HUD:** Haz de radar giratorio continuo que rastrea coordenadas $(X, Y, Z)$ en vivo e informa instantáneamente de posibles desviaciones o anomalías detectadas por `JettraPolice`.

---

#### 3.5.3 `JettraStoreFX`: Visualización 3D Cibernética, Replicación Raft y Tarjetas Holográficas

[`JettraStoreFX`](file:///home/avbravo/NetBeansProjects/jettrastack_local/jettrastoreworkspace/JettraStoreFX) provee la consola de administración visual de `JettraStore`:

* **Estética 3D Cyberpunk / Glassmorphism:**
  * Navegación 3D orbital con control interactivo mediante ratón (giro con arrastre y zoom con rueda).
  * Nodos de clúster renderizados como prismas holográficos 3D iluminados según su estado de salud (verde esmeralda = activo, ámbar = sincronizando, rojo = degradado).
  * Anillos holográficos de energía pulsantes alrededor de los nodos líderes y seguidores.
  * Sistema de partículas tridimensional continuo a 60 FPS que simula en tiempo real el tráfico de replicación Raft entre el nodo principal y los nodos secundarios.
* **Controles HUD 3D Reactivos:** Botones con relieve táctil 3D, sombras dinámicas y tarjetas de información translúcidas (*glassmorphism*) para telemetría de memoria Panama y estado del clúster.

---

#### 3.5.4 Recolector de Basura Autónomo (`JettraGarbageCollector`) y Cierre Limpio de Recursos (`AutoCloseable`)

* **Prevención del Error `NoClassDefFoundError: StorageMetrics`:** En entornos de ejecución Maven (como `exec-maven-plugin`), cuando el proceso principal concluye, el ClassLoader de Maven se destruye mientras que los Virtual Threads de fondo pueden intentar resolver clases tardíamente. Para prevenir fallos en `Jettra-Autonomous-GC`:
  1. Se implementó la precarga forzada de `StorageMetrics.class` durante la inicialización estática del recolector.
  2. Se vincula explícitamente el `ContextClassLoader` del hilo virtual al cargador de clases principal de `JettraStore`.
  3. Se encapsulan las rutinas de recolección en un bloque `catch (Throwable)` que detecta la descarga del ClassLoader y finaliza el hilo de manera limpia y silenciosa.
* **Patrón `AutoCloseable` en Cadena:** La clase maestra `JettraDatabase` implementa formalmente `AutoCloseable`:
  * Al cerrarse la base de datos o desconectarse el cliente `JettraClient`, se cierra en cascada el motor `JettraMemoryEngine`, liberando de forma segura los `Arena` y `MemorySegment` de Panama FFM.
  * Se detienen los Virtual Threads demonio de recolección de memoria (`stopBackgroundCollector()`) y se sincronizan los segmentos WAL pendientes con `fsync` inmediato en disco.


---

## 4. Clúster Raft, Replicación y Distribución de Nodos

### 4.1 Topología de Clúster de 3 Nodos y Consenso Raft

#### 4.1.1 Configuración de Nodos (Líder y Secundarios)

`JettraStore` opera de forma estándar sobre un clúster de tres nodos identificados de forma única:
* **Nodo 1 (Líder Primario):** Coordina transacciones distribuidas, lidera el quórum Raft y atiende escrituras prioritarias.
* **Nodo 2 (Secundario / Seguidor 1):** Mantiene réplica activa del log Raft, listo para asumir el liderazgo en $< 150\text{ ms}$ en caso de desconexión del líder.
* **Nodo 3 (Secundario / Seguidor 2):** Garantiza la formación de quórum de mayoría simple ($N/2 + 1 = 2$) y provee capacidad elástica para el desbordamiento en anillo.

#### 4.1.2 Canales Raft Sin Bloqueo con Virtual Threads y `jettraGRPC`

* **Virtual Threads por Conexión:** Cada flujo de replicación y latido Raft (*Heartbeat*) se procesa en un Virtual Thread independiente de la JVM, permitiendo millones de transacciones por segundo sin saturar el pool de hilos de la plataforma del sistema operativo.
* **Protocolo `jettraGRPC`:** Implementación gRPC de alto rendimiento optimizada para serialización binaria directa sobre archivos `.jettra`. Todas las tramas están firmadas criptográficamente con tokens de sesión **`JettraJWT`**.

#### 4.1.3 Replicación y Distribución Continua de Registros

En una arquitectura de datos distribuida, replicar únicamente la creación o metadatos de la base de datos es insuficiente: los nodos secundarios (`SECONDARY`) deben mantener una copia exacta en tiempo real tanto del estado histórico como del flujo continuo de operaciones de escritura.

`JettraStore` implementa un canal de sincronización dual:

1. **Distribución Inicial y Snapshots (`clusterDistributed`):**
   * Cuando se invoca `clusterDistributed(dbName)` o `clusterDistributedAll()`, el nodo primario serializa en disco y emite una trama binaria `TYPE_DATABASE_SNAPSHOT` (0x03) hacia cada nodo secundario configurado.
   * El nodo secundario recibe la carga de la base de datos, inicializa los subdirectorios locales de almacenamiento (e.g. `/jettra/node-2/dbName/`) e invoca `loadFromDisk()` mediante los métodos de aplicación de réplica (`applyReplicatedPut`, `applyReplicatedClear`), garantizando que los datos existentes se carguen en memoria sin violar el estado de solo lectura del seguidor.

2. **Replicación Continua de Mutaciones en Tiempo Real:**
   * Cualquier inserción, actualización o eliminación en `JettraDatabase` (tanto en `DocumentEngine` mediante `onDocumentInsert`/`onDocumentDelete`, como en `KeyValueEngine` mediante `onKeyValuePut`/`onKeyValueDelete`) genera inmediatamente una trama binaria Raft:
     * `TYPE_PUT_RECORD` (`0x10`): Contiene `dbName`, `engineType`, `key`, `value` y `metadata`.
     * `TYPE_DELETE_RECORD` (`0x11`): Contiene `dbName`, `engineType` y `key`.
   * El cliente de transporte `JettraClusterReplicationClient` emite de forma asíncrona no bloqueante (mediante Virtual Threads) estas tramas a todos los nodos secundarios activos.
   * Al ser procesadas en el nodo secundario por `JettraClusterTransportServer`, este actualiza la memoria del motor correspondiente y sus índices de búsqueda sin requerir recargar desde el disco.

#### 4.1.4 Protocolo de Failover y Elección Automática de Primario

La alta disponibilidad de `JettraStore` se basa en la autodetección de fallos y un protocolo de sucesión determinista:

1. **Detección Inmediata de Parada Limpia (`Graceful Shutdown`):**
   * Al invocarse `server.stop()`, el nodo emite una trama de control `TYPE_NODE_STOPPING` (`0x12`) a través del transporte del clúster y publica un evento `NODE_STOPPED` en el bus de eventos en vivo.
   * Si el nodo que se detiene era el `PRIMARY`, este mensaje desencadena de inmediato el procedimiento de failover en los nodos secundarios restantes sin esperar la expiración de temporizadores.

2. **Detección por Temporizador de Latidos (`Heartbeat Watchdog`):**
   * El nodo primario transmite latidos periódicos (`TYPE_HEARTBEAT` / `0x01`) cada **500 ms** hacia los secundarios.
   * Cada nodo secundario ejecuta un hilo centinela (*Failover Watchdog*) con un umbral de inactividad configurable de **1,500 ms** (`raft.heartbeat.timeout.ms`).
   * Si transcurre dicho lapso sin recibir latidos del nodo primario, el nodo secundario asume que el líder se encuentra caído o inaccesible e inicia la elección de liderazgo.

3. **Elección y Promoción Determinista:**
   * Los nodos secundarios activos evalúan la lista ordenada de identificadores de nodo (`nodeId`).
   * El nodo activo con el identificador menor elegible (por ejemplo, `node-02` en ausencia de `node-01`) se autoproclama de inmediato como el nuevo `PRIMARY`.
   * El nodo promovido cambia su rol a `ClusterNode.Role.PRIMARY`, asume el estado Raft `LEADER`, habilita el modo de escritura en sus bases de datos y notifica a la topología completa emitiendo una trama `TYPE_NEW_LEADER_PROMOTED` (`0x14`).
   * Todos los nodos secundarios supervivientes actualizan sus tablas de enrutamiento asignando al nuevo primario como destino de las operaciones de escritura.

4. **Notificación a Clientes y Servicios Periféricos:**
   * El nuevo primario propaga el evento `LEADER_PROMOTED` a todos los clientes conectados (`JettraStoreDriver`, `JettraStoreShell`, `JettraStorePolice3D`) a través del canal de eventos en tiempo real.
   * `JettraClient` adapta automáticamente su anillo de enrutamiento (*Dynamic Ring Engine*), redirigiendo las llamadas subsiguientes al nuevo líder sin interrupción del servicio para las aplicaciones consumidoras.

#### 4.1.5 Canal de Eventos en Tiempo Real y Streaming `cluster live`

Para proporcionar observabilidad integral del clúster en tiempo de ejecución, `JettraStore` integra el bus de eventos desacoplado **`JettraClusterEventBus`**:

* **Estructura del Evento (`ClusterLiveEvent`):**
  * `timestamp`: Milisegundos de época de alta resolución.
  * `type`: Tipo de evento (`NODE_ONLINE`, `NODE_OFFLINE`, `NODE_STOPPED`, `LEADER_ELECTION`, `LEADER_PROMOTED`, `DATABASE_DISTRIBUTED`, `RECORD_REPLICATED`, `DOCUMENT_REPLICATED`, `DOCUMENT_DELETED`).
  * `sourceNodeId` / `targetNodeId`: Nodos emisor y receptor involucrados en la acción.
  * `message`: Descripción legible de la operación en curso (e.g. *"Replicando documento doc_881 de facturas a node-02"*).
  * `details`: Metadatos auxiliares o serialización de claves afectadas.

* **Canal Server-Sent Events (SSE) y REST en HTTP:**
  * `GET /api/v1/cluster/events?limit=50`: Devuelve los últimos eventos registrados en el búfer circular en formato JSON estructurado.
  * `GET /api/v1/cluster/live`: Abre un flujo continuo en tiempo real (Content-Type: `text/event-stream`) permitiendo que cualquier cliente consuma instantáneamente los eventos a medida que ocurren.


### 4.2 Mecanismo de Distribución y Migración Integral de Bases de Datos (`Cluster-Distributed`)

JettraStore implementa un protocolo integral de distribución y migración de datos que garantiza una réplica exacta y consistente de todas las bases de datos y sus registros internos entre los nodos del clúster (Primario y Secundarios).

#### 4.2.1 Arquitectura del Proceso de Migración

Cuando se crea o distribuye una base de datos en el nodo `PRIMARY`:
1. **Generación del Snapshot Completo:** El servidor primario (`JettraStoreServer.getDatabaseSnapshotBytes(dbName)`) consolida tanto la configuración del esquema (`_meta.json`) como todas las colecciones y registros almacenados en memoria y disco.
2. **Difusión por Transporte Binario / Raft (`TYPE_DISTRIBUTE_DATABASE = 0x11`):** A través de `JettraClusterReplicationClient`, la trama binaria que contiene el nombre de la base de datos y el payload serializado es emitida hacia todos los nodos pares (`SECONDARY`).
3. **Persistencia y Recarga Atómica en Nodos Secundarios:** Al recibir la trama `0x11` o `0x01` (`TYPE_CREATE_DATABASE`), el nodo receptor (`JettraClusterTransportServer`):
   - Almacena atómicamente el payload binario en el archivo `<storagePath>/<dbName>/_meta.json`.
   - Inicializa la instancia `JettraDatabase` en modo seguidor.
   - Invoca `db.loadFromDisk()` / `db.loadUsingPanamaOffHeap()` para volcar todas las colecciones, documentos y registros a la memoria nativa Off-Heap mediante Project Panama FFM.
   - Reconstruye los índices B-Tree, Hash y geoespaciales en `JettraIndexManager`.

#### 4.2.2 Protección de Solo Lectura y Bypass de Ingesta Replicada

Los nodos secundarios operan con la política de **solo lectura (`assertWritable` bloquea mutaciones externas)**:
- Consultas externas (`INSERT`, `UPDATE`, `DELETE`) ejecutadas directamente por clientes contra un nodo secundario arrojan `UnsupportedOperationException("El nodo actual es secundario y opera en modo de solo lectura...")`.
- **Carga de Replicación Interna:** Para permitir que los nodos secundarios carguen los registros del snapshot sin violar la protección de solo lectura, `DocumentEngine` implementa los métodos internos `applyReplicatedClear()` y `applyReplicatedBatch(Map<String, Map<String, Object>> batch)`, permitiendo la inserción transparente de registros transferidos desde el primario.

#### 4.2.3 Protocolo de Transporte y Endpoints REST

| Endpoint / Operación | Método | Parámetros | Descripción |
| :--- | :--- | :--- | :--- |
| `/api/v1/cluster/distribute` | `POST` | `all=true` | Distribuye todas las bases de datos y sus registros hacia todos los nodos secundarios del clúster. |
| `/api/v1/cluster/distribute` | `POST` | `db=<nombre>` | Distribuye una base de datos específica con todos sus registros internos hacia los nodos secundarios. |
| `/api/v1/cluster/distribution-info` | `GET` | N/A | Retorna el reporte de topología en formato JSON (`ClusterNodeDistributionInfo`) con la lista y recuento de bases de datos por nodo. |
| Trama Binaria Raft | Frame `0x11` | `dbName` + `payload` | Difusión binaria de baja latencia vía TCP entre puertos de transporte gRPC/Raft del clúster. |

#### 4.2.4 Formato del Modelo `ClusterNodeDistributionInfo`

```json
[
  {
    "nodeId": "node-01",
    "ip": "192.168.60.243",
    "port": 50001,
    "role": "PRIMARY",
    "status": "ONLINE",
    "databaseCount": 3,
    "databases": ["example_factura_db", "samples_hospital_db", "samples_ambiental_db"]
  },
  {
    "nodeId": "node-02",
    "ip": "192.168.60.246",
    "port": 50001,
    "role": "SECONDARY",
    "status": "ONLINE",
    "databaseCount": 3,
    "databases": ["example_factura_db", "samples_hospital_db", "samples_ambiental_db"]
  }
]
```


### 4.3 Persistencia Garantizada en `jettra.storage.path`, Sincronización de `cluster.config` y Arranque Limpio

#### 4.3.1 Persistencia Directa en `jettra.storage.path` por Nodo
Cada nodo de `JettraStore` define su propia ruta de almacenamiento en `microprofile-config.properties` mediante `jettra.storage.path` (o mediante `JETTRA_STORAGE_PATH` / `-Djettra.storage.path` en contenedores Docker y Docker Compose):

```properties
jettra.storage.path = ~/jettra-storage-01/data
```

* Las propiedades `cluster.node.<N>.storage.path` fueron eliminadas de `cluster.config` para evitar colisiones entre nodos.
* Al crear una base de datos, un motor/bucket, un documento, un registro o un índice (ya sea vía API Java, REST, `JettraStoreShell` o `JettraStoreExplorer`), el nodo `PRIMARY` ejecuta inmediatamente `flushMemTable()` y `saveToDisk()` en su `jettra.storage.path` y replica la operación vía Raft hacia todos los nodos `SECONDARY`, que persisten los datos en su propio `jettra.storage.path`.

#### 4.3.2 Arranque Limpio sin Bases de Datos de Ejemplo Precargadas
Por defecto, `JettraStore` inicia **sin instalar automáticamente** bases de datos de muestra (`sample_*`, `samples_*`, `example_factura_db`). Las bases de ejemplo pueden instalarse o eliminarse bajo demanda desde el formulario `/examples` de **JettraStoreExplorer**, o activando explícitamente `-Djettra.samples.autoload=true` si se requiere en un entorno de demostración.

#### 4.3.3 Replicación Dinámica de `cluster-state.config` (Trama Raft `0x17`)
Cuando se añade o se expulsa un nodo desde **JettraStoreExplorer** o **JettraStoreShell**:
1. Se actualiza la topología consultando y sincronizando el estado del nodo `PRIMARY` (`cluster-state.config`).
2. Se transmite el nuevo archivo `cluster-state.config` mediante la trama binaria Raft `TYPE_SYNC_CLUSTER_CONFIG` (`0x17`) al nodo `PRIMARY` y a los nodos `SECONDARY`.
3. Cada nodo actualiza su `cluster-state.config` local dentro de `jettra.cluster_state_config_dir`, refresca los *peers* en `DynamicRingEngine` y publica un evento en `JettraClusterEventBus` visible en tiempo real en `/map`.


### 4.4 Sincronización de Nuevos Nodos y Consistencia Zero-Downtime

#### 4.4.1 Mecanismo Técnico de Sincronización de Nuevos Nodos

Para garantizar convergencia automática sin detener nodos `SECONDARY` activos:

- **Conciliación de rol, idempotencia y anti-eco (`JettraClusterTransportServer`):**
  - **Anti Split-Brain:** Si un nodo recibe un `cluster.config` canónico donde otro nodo es `PRIMARY` y él mismo figura como `SECONDARY`, se concilia automáticamente a `SECONDARY` y refresca el latido del líder.
  - **Idempotencia estricta:** Si `writeClusterConfigAtomically(...)` detecta que el archivo ya es idéntico byte a byte y no hay peers nuevos, responde `ACK` de inmediato sin re-difundir ni re-sincronizar.
  - **Anti-Eco entre nodos:** El `PRIMARY` solo re-difunde `TYPE_SYNC_CLUSTER_CONFIG` cuando la orden proviene de un cliente de administración (`JettraStoreExplorer` / `JettraStoreShell`), nunca cuando el remitente es otro nodo miembro del clúster.
- **Coalescencia de sincronizaciones (`JettraStoreServer`):**
  - El método `triggerAsyncSynchronizeFromPrimary()` está protegido con `AtomicBoolean secondarySyncInProgress` para evitar apilar hilos de sincronización en los `SECONDARY`.
  - Deduplicación por peer (`activePeerDistributions`) en `distributeAllDatabasesToPeer(...)` para evitar múltiples replicaciones simultáneas hacia el mismo nodo nuevo.
  - Refresco de `lastLeaderHeartbeatTime` dentro de `applySyncedPeersToRuntime(...)` para evitar falsos timeouts de failover durante la reconfiguración del clúster.
- **Sincronización segura de disco (`JettraDatabase`):**
  - `loadFromDisk(Path)` es `synchronized`, evitando colisiones concurrentes (`Already closed`) al aplicar instantáneas de replicación.

**Aplicación en despliegues locales:**
1. Detén los nodos locales que estén ejecutándose si vas a sustituir el JAR (`node-01`, etc.).
2. Ejecuta `./script/create-nodes.sh` (o copia `JettraStore/target/JettraStore-1.0-SNAPSHOT-uber.jar` a cada directorio `~/jettra-node-01..04/`).
3. Inicia los nodos e incorpora `node-04` desde `JettraStoreExplorer` o `JettraStoreShell`: `cluster.config` y las bases de datos se sincronizarán sin detener ningún `SECONDARY`.

#### 22.3 Cambios de Membresía Zero-Downtime y Reconciliación sin Paradas
Al incorporar un nuevo nodo o remover un nodo detenido mediante `TYPE_SYNC_CLUSTER_CONFIG`:
- **Conciliación de rol (Anti Split-Brain):** Si un nodo recién incorporado inició aislado como `PRIMARY`, al recibir el `cluster.config` canónico donde figura como `SECONDARY` se degrada automáticamente a `SECONDARY` sin detenerse.
- **Idempotencia estricta y Anti-Eco:** Si el archivo `cluster.config` no presenta diferencias respecto al ya persistido, no se re-difunden tramas ni se relanzan sincronizaciones; el `PRIMARY` nunca rebota actualizaciones originadas en otro nodo miembro.
- **Coalescencia de sincronización:** Los nodos `SECONDARY` utilizan `triggerAsyncSynchronizeFromPrimary()` y `loadFromDisk` sincronizado para converger bases de datos y metadatos sin detener ningún nodo activo del clúster.


### 4.5 Gestión del Ciclo de Vida de Nodos y Verificación en Vivo desde `JettraStoreShell`

```sql
-- Ver topología real en vivo (invariante de líder único: nunca duplica PRIMARY/LEADER)
SHOW NODES

-- Añadir un nuevo nodo al clúster en caliente (zero-downtime, solo rol admin)
ADD NODE node-04 127.0.0.1 50004 SECONDARY 60004

-- Detener un nodo limpiamente (envía trama Raft LIFECYCLE_SHUTDOWN + fallback REST)
STOP NODE node-04

-- Reiniciar un nodo detenido (lanza ~/jettra-node-04/start-node.sh y sincroniza estado)
START NODE node-04

-- Remover un nodo (regla estricta: primero STOP NODE; luego REMOVE NODE)
STOP NODE node-04
REMOVE NODE node-04
```


---

## 5. Configuración del Sistema y Optimización de Recursos

### 5.1 Archivo `microprofile-config.properties` (Identidad del Nodo y Parámetros del Motor)

JettraStore implementa una arquitectura de configuración limpia, moderna y libre de duplicidad alineada con **Eclipse MicroProfile Config 3.1** y **Jakarta EE**, separando con precisión:
1. **`microprofile-config.properties`:** Identifica el nodo local mediante `cluster.node.id` y `cluster.node.role`, define su ruta propia de almacenamiento físico mediante `jettra.storage.path`, y configura los parámetros internos del motor (memoria Off-Heap, modos de almacenamiento, `JettraPolice`, consultas y seguridad `JettraJWT`).
2. **`cluster.config`:** Centraliza únicamente la topología de red de cada nodo del clúster (`id`, `role`, `ip`, `grpc.port` y `rest.port`), incluyendo los nodos agregados dinámicamente en tiempo de ejecución. Las propiedades `cluster.node.<N>.storage.path` ya no se utilizan en `cluster.config` para evitar conflictos entre nodos.

Al iniciar JettraStore, el componente `JettraConfigValidator` enlaza el nodo definido en `cluster.node.id` con su bloque correspondiente `cluster.node.<N>.*` en `cluster.config`. Si el `cluster.node.id` indicado no existe en `cluster.config`, emite un mensaje de advertencia y error crítico e impide la ejecución de `JettraStore`.

---

#### 13.1 Archivo `microprofile-config.properties` (Identidad del Nodo y Parámetros del Motor)

El archivo estándar `microprofile-config.properties` reside en `META-INF/microprofile-config.properties` (o en la raíz del classpath), en la carpeta `config/` de cada nodo (`config/microprofile-config.properties`) o en el directorio de trabajo donde se ejecute el JAR.

##### 5.1.1 Eliminación de Duplicidad y Enlace con `cluster.config`
Para evitar duplicidad de propiedades con funciones similares entre archivos:
* **Identidad y Rol del Nodo Local (Paso 1):**  
  En `microprofile-config.properties` se definen únicamente:
  ```properties
  cluster.node.id=node-01
  cluster.node.role=PRIMARY
  ```
  *(Se prescinde de las propiedades redundantes `jettra.node.id` y `jettra.node.role`).*  
  El valor de `cluster.node.id` admite tanto el identificador completo (`node-01`, `node-02`, `node-03`) como el formato numérico abreviado (`01` o `1`), permitiendo localizar de inmediato la sección `cluster.node.<N>.*` dentro de `cluster.config`.

* **Almacenamiento por Nodo en `microprofile-config.properties` y Red Centralizada en `cluster.config` (Paso 2):**  
  Cada nodo define su propia ruta de almacenamiento en `microprofile-config.properties` mediante `jettra.storage.path` (evitando conflictos en `cluster.config`). Por su parte, los parámetros de red (`ip`, `grpc.port`, `rest.port` y `role`) se obtienen automáticamente del nodo correspondiente en `cluster.config`:
  - **`jettra.storage.path` (en `microprofile-config.properties`):** Directorio base de persistencia física exclusivo de este nodo (ej. `~/jettra-storage-01/data`), donde JettraStore almacena las bases de datos, sus objetos y el subdirectorio de índices `<jettra.storage.path>/indexes`. En contenedores Docker/Docker Compose puede sobreescribirse mediante la variable `JETTRA_STORAGE_PATH` o `-Djettra.storage.path`.
  - **`cluster.node.<N>.role` (en `cluster.config`):** Rol del nodo en el clúster (`PRIMARY` o `SECONDARY`).
  - **`cluster.node.<N>.ip` (en `cluster.config`):** Dirección IP de escucha y transporte del nodo.
  - **`cluster.node.<N>.grpc.port` (en `cluster.config`):** Puerto de transporte binario/gRPC (reemplaza a `jettra.network.grpc.port`).
  - **`cluster.node.<N>.rest.port` (en `cluster.config`):** Puerto HTTP REST del nodo (reemplaza a `jettra.network.rest.port`).

##### 5.1.2 Prioridad de Resolución
El orden de resolución de parámetros en tiempo de ejecución cumple la especificación MicroProfile Config:
1. **Propiedades del sistema JVM** pasadas como argumentos `-D` (ej. `-Dcluster.node.id=node-01`, `-Dcluster.node.role=PRIMARY`). Tienen la **máxima prioridad** y sobreescriben cualquier otra fuente.
2. **Variables de entorno del sistema** (ej. `CLUSTER_NODE_ID`, `CLUSTER_NODE_ROLE`, `JETTRA_NODE_ID`, `JETTRA_STORAGE_PATH`), ideales para despliegues en contenedores Docker y Kubernetes.
3. **Archivo de configuración externa** especificado mediante `-Dmicroprofile.config.file=...` o variable `MP_CONFIG_FILE`.
4. **Archivo de configuración del nodo en disco (`microprofile-config.properties`)** localizado en `config/microprofile-config.properties`, `./microprofile-config.properties`, o en el directorio hermano de `cluster.config`.
5. **Valores de red y almacenamiento resueltos desde `cluster.config`** según el nodo indicado en `cluster.node.id` (`cluster.node.<N>.*`).
6. **Valores predeterminados embebidos** en el classpath (`META-INF/microprofile-config.properties`).

##### 5.1.3 Parámetro `cluster.multinode.active` (Activación del Algoritmo de Consenso Distribuido)

La propiedad `cluster.multinode.active` define si JettraStore opera en modo distribuido con algoritmo de consenso o en modo servidor único local (*standalone*):

* **Valor `on` (Predeterminado):**  
  Activa el sistema de distribución de datos mediante el algoritmo de consenso integrado en JettraStore (Raft / Dynamic Ring). En este modo:
  - El motor registra y mantiene sincronizados los nodos pares (*peers*) configurados en la topología (`cluster.config`).
  - Al saturarse la memoria RAM al umbral configurado (ej. 85%), se activan las descargas y transferencias dinámicas de particiones hacia los nodos secundarios del anillo.
  - La replicación, el quórum y la consistencia de datos entre instancias distribuidas se encuentran plenamente operativas.

* **Valor `off`:**  
  Desactiva por completo el comportamiento de distribución de datos. En este modo:
  - Se asume que **todas las operaciones se realizarán solamente en el servidor donde se está ejecutando JettraStore**, sin distribuir los datos ni intentar transferencias hacia otros nodos.
  - El motor de anillo dinámico (*Dynamic Ring*) se mantiene en modo local/standalone y no inicia transferencias hacia nodos pares.
  - Ideal para entornos de desarrollo local, despliegues compactos en un único servidor dedicado, microservicios embebidos o pruebas unitarias donde no se requiere infraestructura de clúster ni tráfico de red inter-nodo.

##### 5.1.4 Plantilla Completa de `microprofile-config.properties`
```properties
# ==============================================================================
# ECLIPSE MICROPROFILE CONFIGURATION (MicroProfile Config 3.1)
# JettraStore Multi-Model Distributed Database (Java 25+ & Project Loom)
# ==============================================================================

# Perfil activo de configuración MicroProfile
mp.config.profile=production
jettra.storage.path=~/jettra-storage-01/data

# Identificador y Rol del Nodo Local en el Clúster (enlaza con cluster.config)
cluster.node.id=node-01
cluster.node.role=PRIMARY

# Topología Multinodo y Consenso
cluster.multinode.active=on

# Modo de Almacenamiento (OFF_HEAP, DISK_MEMORY, JVM_RAM)
jettra.storage.mode=OFF_HEAP
storage.mode=OFF_HEAP
jettra.storage.file.extension=.jettra

# Red y Virtual Threads (IP y puertos gRPC/REST se obtienen de cluster.config; almacenamiento definido en jettra.storage.path)
contextpath=/
server.contextpath=/
jettra.network.virtualthreads.enabled=true
server.virtual-threads=true

# MemTable y Memoria Off-Heap (Panama FFM)
jettra.storage.memtable.size.mb=128
jettra.storage.ram.global.limit.mb=2048
jettra.storage.offheap.direct=true
storage.offheap.page-size=65536

# Umbrales del Motor de Anillo Distribuido
jettra.ring.saturation.threshold.percent=85
jettra.ring.release.target.percent=45

# JettraPolice (Supervisión Preventiva Anti-OOM)
jettrapolice.active=true
jettrapolice.interval.ms=500
jettrapolice.ram.warning.threshold=75
jettrapolice.disk.warning.threshold=90
jettrapolice.ram.critical.threshold=85
jettrapolice.auto.pagination.enabled=true
jettrapolice.max.safe.batch.size=100

# Índices y Optimización de Consultas
jettra.index.initial.capacity=65536
jettra.index.max.inmemory.keys=100000
jettra.index.compact.storage=true
jettra.storage.autoflush.batch.size=50000
jettra.query.default.limit=50
jettra.query.max.limit=5000
jettra.query.pagesize=50

# Seguridad Criptográfica y JWT
jettra.security.jwt.algorithm=Ed25519
jettra.security.jwt.expiration.seconds=86400
jettra.security.jwt.issuer=jettra-store-authority
jwt.secret=jettra_store_super_secure_jwt_key_2026
jwt.expiration-millis=86400000

# Superusuario por Defecto
jettra.security.default.admin.username=admin
jettra.security.default.admin.password=admin-jettra

# Métricas y MicroProfile
jmh.metrics.active=false
mp.health.default.readiness.empty.response=UP
mp.metrics.appName=JettraStore
mp.metrics.tags=platform=JettraStore,runtime=Java25,engine=PanamaFFM
cors.enabled=true
```


### 5.2 Archivo `cluster.config`, Validación Cruzada en Arranque y Generación Automática

#### 5.2.2 Archivo `cluster.config` (Topología de Clúster Raft, Red y Nodos Dinámicos)

El archivo `cluster.config` establece la topología completa del clúster de consenso Raft y coordinación del anillo dinámico. Define para cada nodo su identidad (`id`), rol (`role`), dirección IP (`ip`), puerto inter-nodo (`grpc.port`) y puerto HTTP (`rest.port`). La ruta de almacenamiento físico ya no se incluye en `cluster.config`, sino de manera independiente en el archivo `microprofile-config.properties` de cada nodo mediante `jettra.storage.path`.

##### 5.2.2.1 Plantilla de Topología Centralizada (`cluster.config`)
```properties
################################################################################
# JettraStore Cluster Topology Configuration (cluster.config)
# Topología de Clúster Centralizada (Raft Consensus & Dynamic Ring)
################################################################################

cluster.name = jettra-production-cluster
cluster.consensus.protocol = RAFT
cluster.ring.enabled = true
cluster.heartbeat.interval.ms = 150
cluster.election.timeout.ms = 300

# ==============================================================================
# NODO 1: NODO PRINCIPAL / LÍDER (Primary)
# ==============================================================================
cluster.node.1.id = node-01
cluster.node.1.role = PRIMARY
cluster.node.1.ip = 192.168.60.243
cluster.node.1.grpc.port = 50001
cluster.node.1.rest.port = 60001

# ==============================================================================
# NODO 2: NODO SECUNDARIO / SEGUIDOR 1 (Secondary)
# ==============================================================================
cluster.node.2.id = node-02
cluster.node.2.role = SECONDARY
cluster.node.2.ip = 192.168.60.243
cluster.node.2.grpc.port = 50002
cluster.node.2.rest.port = 60002

# ==============================================================================
# NODO 3: NODO SECUNDARIO / SEGUIDOR 2 (Secondary)
# ==============================================================================
cluster.node.3.id = node-03
cluster.node.3.role = SECONDARY
cluster.node.3.ip = 192.168.60.243
cluster.node.3.grpc.port = 50003
cluster.node.3.rest.port = 60003

# Asignación de Capacidad de Índices y Buffers en Clúster
cluster.index.initial.capacity = 65536
cluster.index.max.inmemory.keys = 100000
```

##### 5.2.2.2 Registro Dinámico de Nuevos Nodos en `cluster.config`
El clúster permite incorporar nodos adicionales en tiempo de ejecución más allá de los nodos iniciales. Cuando se agrega dinámicamente un nodo al anillo mediante `DynamicRingEngine.registerDynamicPeer(...)` o `JettraConfigValidator.registerDynamicNodeInClusterConfig(...)`, el motor persiste automáticamente su bloque `cluster.node.<N>.*` en el archivo `cluster.config`:

```java
// Ejemplo: Agregar dinámicamente un cuarto nodo (node-04) al clúster y persistirlo en cluster.config
JettraConfigValidator.ClusterNodeInfo nuevoNodo = JettraConfigValidator.registerDynamicNodeInClusterConfig(
    "node-04",
    "SECONDARY",
    "192.168.60.244",
    50004,
    60004,
    "~/jettra/data"
);
```

---

#### 5.2.3 Validación Cruzada y Reglas de Integridad en el Arranque (`java -jar`)

Al iniciar JettraStore mediante `java -jar JettraStore.jar`, el motor invoca `JettraConfigValidator.validateAndBootstrapOrHalt()` para auditar la correspondencia entre `microprofile-config.properties` y `cluster.config`.

Las reglas principales de resolución y validación son:

| Regla | Parámetro en `microprofile-config.properties` | Resolución / Validación frente a `cluster.config` | Comportamiento |
| :--- | :--- | :--- | :--- |
| **Regla 1** | `cluster.node.id` | Busca el nodo en `cluster.config` por coincidencia exacta de `id` (ej. `node-01`) o por número de nodo (ej. `01` $\rightarrow$ `cluster.node.1.*`). | **Obligatorio:** Si el `cluster.node.id` no existe en `cluster.config`, emite una **advertencia y error crítico** y **no permite ejecutar `JettraStore`**. |
| **Regla 2** | `cluster.node.role` | Identifica el rol esperado del nodo y lo sincroniza con `cluster.node.<N>.role` en `cluster.config`. | `PRIMARY` o `SECONDARY`. |
| **Regla 3** | *(Resuelto de `cluster.config`)* | `cluster.node.<N>.ip`, `cluster.node.<N>.grpc.port` y `cluster.node.<N>.rest.port`. | Asigna automáticamente la IP y los puertos gRPC y REST del nodo sin duplicarlos en `microprofile-config.properties`. |
| **Regla 4** | `jettra.storage.path` | *(Independiente por nodo, no está en `cluster.config`)* | Asigna la ruta base de datos (`jettra.storage.path` en `microprofile-config.properties` o `JETTRA_STORAGE_PATH` en Docker), deriva automáticamente `<jettra.storage.path>/indexes` y **verifica al arrancar si el directorio existe; si no existe, lo crea automáticamente** para almacenar las bases de datos y sus objetos. |
| **Regla 5** | `cluster.multinode.active` | Determina si se activa la distribución por consenso (`on`) o servidor único local (`off`). | `on` u `off`. |

##### Acción Preventiva ante Nodo Inexistente
1. Si el valor de `cluster.node.id` especificado en `microprofile-config.properties` **no existe** entre los nodos registrados en `cluster.config`, `JettraConfigValidator` envía de inmediato un mensaje de advertencia por consola (`ADVERTENCIA: El identificador de nodo '...' indicado en 'cluster.node.id' no existe en el archivo cluster.config...`) y bloquea la ejecución de `JettraStore` mediante `System.exit(1)` (o lanza `JettraConfigurationException`).
2. Esto garantiza que ningún nodo pueda iniciar con una identidad huérfana o fuera de la topología conocida del clúster.

---

#### 5.2.4 Generación Automática de Archivos de Configuración Faltantes

Si al ejecutar JettraStore mediante `java -jar` no existen los archivos `config/microprofile-config.properties` o `config/cluster.config` en el sistema de archivos:

1. **Creación Automática de Directorios:** El sistema crea la carpeta `config/` si no está presente.
2. **Generación de `microprofile-config.properties`:** Se crea un archivo limpio con `jettra.storage.path=~/jettra-storage-01/data`, `cluster.node.id=node-01`, `cluster.node.role=PRIMARY` y `cluster.multinode.active=on`, sin duplicar puertos.
3. **Generación de `cluster.config`:** Se crea la topología del clúster (`node-01`, `node-02`, `node-03`) con sus respectivos roles, IPs y puertos `grpc.port` y `rest.port` (sin propiedades `storage.path`).
4. **Coherencia Inmediata:** Ambos archivos autogenerados quedan inmediatamente enlazados a través de `cluster.node.id`, permitiendo poner en marcha el sistema sin configuración manual previa.

---

#### 5.2.5 Despliegue en Entornos Contenedorizados con Docker y Docker Compose

Cuando JettraStore se despliega en contenedores (por ejemplo, mediante `docker compose up`):

* **Detección Automática del Entorno:** El sistema reconoce automáticamente la ejecución en contenedor mediante:
  * Variable de entorno `JETTRA_DOCKER_COMPOSE=true` o `JETTRA_DOCKER=true`.
  * Presencia del archivo de señalización del kernel `/.dockerenv`.
  * Verificación de grupos de control en `/proc/1/cgroup` (`docker`, `containerd`, `kubepods`).
  * Inyección de variables de orquestación de Docker Compose (`JETTRA_CLUSTER_PEERS` junto con volumen `/jettra/data`).
* **Gobernanza por `docker-compose.yml`:** En contenedores, cada nodo corre en un espacio de nombres y sistema de archivos aislado con volúmenes montados (ej. `jettra_data_node01:/jettra/data`). Por ende:
  * El motor **omite la detención preventiva por discrepancias de rutas locales del host**.
  * Los parámetros son provistos directamente por el bloque `environment:` de `docker-compose.yml` (`JETTRA_NODE_ID`, `JETTRA_NODE_ROLE`, `JETTRA_STORAGE_PATH=/jettra/data`, `JETTRA_REST_PORT=60001`, `JETTRA_GRPC_PORT=50001`).
  * JettraStore emite un mensaje informativo de confirmación:
    ```text
    [JettraStore] INFO: Entorno Docker detectado. La configuración está gestionada mediante docker-compose / variables de entorno.
    ```


### 5.3 Optimización del Consumo de Recursos, Memoria RAM y Perfiles JVM (`G1GC` / `JAVA_OPTS`)

El ecosistema **JettraStore** (compuesto por los nodos de base de datos `JettraStore`, la consola web `JettraStoreExplorer` y la consola interactiva `JettraStoreShell`) ha sido diseñado para ejecutarse de forma eficiente tanto en equipos de desarrollo con memoria acotada (por ejemplo, estaciones Linux/macOS/Windows con 8–16 GB de RAM corriendo un clúster multinodo completo) como en servidores de producción de alta densidad.

En Java 25 con **Project Panama (FFM)**, el consumo real de memoria residente (RSS) de un proceso JVM no depende únicamente del *Heap*, sino de la suma de cuatro regiones:

$$\text{RAM Total (RSS)} \approx \text{Heap JVM} + \text{Metaspace} + \text{Direct / Off-Heap Native (Panama FFM)} + \text{CodeCache \& Threads}$$

A continuación se documentan las optimizaciones arquitectónicas, de configuración y de JVM aplicadas de forma transversal en todo el ecosistema.

---

#### 5.3.1 Optimizaciones en el Motor `JettraStore` (Servidor)

##### 1. Asignación Perezosa (*Lazy Off-Heap Allocation*) en `NativeMemTable`
Anteriormente, cada instancia de `JettraDatabase` reservaba inmediatamente un segmento nativo contiguo de `128 MB` mediante `Arena.ofShared().allocate(...)` en el constructor de `NativeMemTable`, incluso cuando la base de datos estaba vacía o se encontraba en modo lectura. En un clúster de 3 nodos con 5 bases de datos, esto consumía casi **2 GB de RAM nativa** antes de insertar un solo registro.

* **Solución implementada:** `NativeMemTable` retrasa la creación del `Arena` y la reserva del `MemorySegment` hasta el primer `append(...)` (consumo inicial de **0 bytes nativos**).
* Adicionalmente, `flushToJettraFile(...)` utiliza un `ByteBuffer` temporal de 8 bytes para escribir la cabecera `.jettra` en lugar de asignar memoria adicional dentro del `Arena` nativo en cada volcado a disco, eliminando fugas progresivas de memoria nativa.

##### 2. Inicialización Perezosa de Motores Auxiliares en `JettraDatabase`
Cada base de datos soporta múltiples modos de almacenamiento (`OFF_HEAP`, `DISK_MEMORY`, `JVM_RAM`). Anteriormente, el constructor de `JettraDatabase` instanciaba simultáneamente los tres motores auxiliares (`JettraMemoryEngine` con su hilo de GC en segundo plano, `OffHeapSlottedMap` con su `Arena` y `OffHeapPageManager` con su `FileChannel`), aunque solo uno estuviera activo.

* **Solución implementada:** Los tres motores se inicializan bajo demanda mediante getters sincronizados con *double-checked locking* (`getMemoryEngine()`, `getOffHeapDatabaseStore()`, `getOffHeapPageManager()`).
* El método `close()` libera únicamente los motores que fueron efectivamente inicializados, reduciendo tanto la huella de RAM como la cantidad de descriptores de archivo e hilos activos.

##### 3. Dimensionamiento Proporcional en Índices Secundarios (`JettraIndexManager`)
Al reconstruir o crear un índice secundario, `JettraIndexManager` pre-asignaba un `UnifiedMap` con capacidad inicial de `65,536` entradas incluso para colecciones vacías o con pocos registros.

* **Solución implementada:** El mapa invertido ahora se dimensiona proporcionalmente a la cardinalidad real de la colección (`16–64` entradas para índices vacíos y crecimiento orgánico bajo demanda hasta el límite configurado).

##### 4. Valores por Defecto Eficientes en `JettraStoreConfig` y `microprofile-config.properties`
Se ajustaron los parámetros predeterminados para equilibrar rendimiento y consumo de RAM:

| Propiedad | Valor Anterior | Valor Optimizado | Impacto |
| :--- | :---: | :---: | :--- |
| `jettra.storage.memtable.size.mb` | `128` | `16` | Reduce 8× la reserva nativa por MemTable activa |
| `jettra.storage.ram.global.limit.mb` | `2048` | `512` | Acota la cuota global de RAM por nodo |
| `jettra.index.initial.capacity` | `65536` | `256` | Evita tablas hash sobredimensionadas en heap |
| `jettra.index.max.inmemory.keys` | `100000` | `50000` | Controla la retención de claves invertidas en RAM |
| `jettrapolice.interval.ms` | `500` | `2000` | Reduce 4× los ciclos de sondeo preventivo en reposo |

---

#### 5.3.2 Optimizaciones en `JettraStoreExplorer` y `JettraStoreShell`

##### 1. `JettraStoreShell` (Consola Interactiva CLI)
* **Reutilización de `HttpClient` compartido:** En el comando `show nodes`, anteriormente se instanciaba un nuevo `HttpClient.newBuilder().build()` por cada nodo consultado, creando hilos internos (`SelectorManager`) y pools de sockets que permanecían residentes. Ahora se reutiliza una única instancia compartida `sharedHealthHttpClient` configurada con `HTTP/1.1` y *timeout* acotado.
* **Acotamiento de JVM:** Se sustituyó `-XX:+UseZGC -Xmx1g` por `G1GC` con `-Xms64m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=128m -XX:+UseStringDeduplication -XX:CICompilerCount=2`.

##### 2. `JettraStoreExplorer` (Consola Web JettraFlux)
* **Eliminación de ZGC sin límite de Heap:** Anteriormente `start.sh` ejecutaba `-XX:+UseZGC` sin `-Xmx` explícito, provocando que la JVM reservara hasta el 25% de la RAM del host (4 GB en equipos de 16 GB) más el *overhead* de mapeo virtual de ZGC. Ahora opera con `G1GC`, `-Xms128m -Xmx512m`, `-XX:MaxMetaspaceSize=256m`, `-XX:MaxDirectMemorySize=192m`, `-XX:+UseStringDeduplication` y `-XX:CICompilerCount=2`.
* **Optimización del monitor de latidos (*Heartbeat*):** `JettraStorePlugin` utiliza `HTTP/1.1` en `healthHttpClient` (evitando buffers HTTP/2 innecesarios para sondeos locales) y eleva el intervalo de sondeo pasivo de `1000 ms` a `3000 ms` (configurable vía `-Djettra.explorer.heartbeat.interval.ms`), reduciendo 3× la creación de objetos temporales en reposo sin afectar la actualización inmediata bajo demanda en `/cluster`.

---

#### 5.3.3 Perfil de Flags JVM y Contenedores Docker

El uso de **G1GC** combinado con **Compact Object Headers** (Java 25 Lilliput), **String Deduplication** y límites explícitos de `Metaspace`, `DirectMemory` y compiladores JIT (`CICompilerCount=2`) ofrece una reducción drástica de RSS frente a ZGC en despliegues con múltiples procesos en un mismo host:

| Componente | Configuración Anterior | Configuración Optimizada (Por Defecto) | Límite Docker (`mem_limit`) |
| :--- | :--- | :--- | :---: |
| **JettraStore** (por nodo) | `-XX:+UseZGC -Xms2g -Xmx6g` (local) / `-Xms512m -Xmx2g` (Docker) | `-Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=256m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:CICompilerCount=2` | `1g` (`mem_reservation: 192m`) |
| **JettraStoreExplorer** | `-XX:+UseZGC` (sin `-Xmx` local) / `-Xms256m -Xmx1g` (Docker) | `-Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=192m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:CICompilerCount=2` | `768m` (`mem_reservation: 160m`) |
| **JettraStoreShell** | `-Xms128m -Xmx1g -XX:+UseZGC` (Maven) / `-Xmx512m` (Docker) | `-Xms64m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=128m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:CICompilerCount=2` | `384m` (`mem_reservation: 64m`) |
| **Clúster Completo (3 Nodos + Explorer + Shell)** | **~8 – 20 GB** comprometidos | **~0.7 – 1.5 GB en reposo** (máx. ~2.3 GB heap combinado) | **~4.1 GB** máx. total |

---

#### 5.3.4 Optimización de Compilación con Maven

En equipos con 16 GB de RAM, compilar todo el reactor multi-módulo (`jettrastorecontainers`) podía saturar la memoria al ejecutar el compilador `javac` y múltiples pruebas dentro del mismo proceso Maven. Para evitarlo:

1. **`.mvn/jvm.config`** en la raíz del reactor acota la JVM de Maven (`-Xms128m -Xmx768m -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -XX:CICompilerCount=2`).
2. **`exec-maven-plugin` (`jettra-test`)** ejecuta `JettraTestRunner` en un proceso aislado con `-Xms64m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=256m -XX:+UseG1GC -XX:CICompilerCount=2`, liberando toda la memoria al terminar cada módulo.
3. **`maven-surefire-plugin`** permanece desactivado (`<skipTests>true</skipTests>`) en los módulos que usan `JettraTestRunner` para evitar ejecuciones duplicadas.

---

#### 5.3.5 Escalado Personalizado en Producción (`JAVA_OPTS`)

Todos los scripts de arranque (`start-node.sh`, `JettraStoreExplorer/start.sh`, `JettraStoreShell/start.sh`) y contenedores Docker respetan la variable de entorno `JAVA_OPTS`. Si despliega `JettraStore` en un servidor dedicado de alta capacidad (por ejemplo, 32–128 GB de RAM) y desea priorizar latencia sub-milisegundo sobre huella de memoria, puede sobrescribir los parámetros sin modificar los scripts:

```bash
# Ejemplo para servidor dedicado de alta capacidad con ZGC Generacional
JAVA_OPTS="-Xms2g -Xmx8g -XX:+UseZGC -XX:+UseCompactObjectHeaders -XX:MaxDirectMemorySize=2g" \
  ./start-node.sh node-01 PRIMARY
```

De igual manera, tras actualizar y recompilar el proyecto, recuerde ejecutar `./script/create-nodes.sh` si desea propagar los nuevos scripts `start-node.sh`, `microprofile-config.properties` y el uber-JAR optimizado hacia los directorios locales `~/jettra-node-*`.


---

## 6. Despliegue en Docker, Docker Hub y Clúster en Producción

### 6.1 Contenedorización con Docker y Orquestación Básica (`Dockerfile` / `docker-compose.yml`)

`JettraStore` provee empaquetado nativo en contenedores Docker y orquestación multi-nodo mediante `docker-compose.yml`, optimizado para producción sobre **Java 25 LTS** con persistencia durable y seguridad criptográfica mandatoria.

#### 6.1.1 Arquitectura e Imagen Docker Optimizada (`Dockerfile`)

La imagen oficial se construye a partir de la distribución base ligera y de ultra-alto rendimiento `bellsoft/liberica-runtime-container:jre-25-stream-musl` (o glibc para CRIU completo), e incorpora directivas de seguridad para ejecución sin privilegios de root (`non-root user`), reducción de capas y recolección de memoria eficiente:

```dockerfile
# ==============================================================================
# JettraStore Distributed Multi-Model Database (Java 25+ Panama FFM & Raft)
# Compatible con CRaC / CRIU para arranque instantáneo en caliente (< 20 ms)
# Optimizado para bajo consumo de RAM con G1GC, Compact Headers y límites cgroup
# ==============================================================================
FROM bellsoft/liberica-runtime-container:jre-25-stream-musl
LABEL maintainer="Jettra Ecosystem <dev@jettra.io>"
LABEL description="JettraStore - Motor de Base de Datos Multimodelo Distribuido en Java 25+ con CRaC y Off-Heap FFM"

# Instalar curl para healthchecks y utilidades del sistema
RUN if command -v apk > /dev/null; then \
        apk add --no-cache curl ca-certificates tzdata && rm -rf /var/cache/apk/*; \
    elif command -v apt-get > /dev/null; then \
        apt-get update && apt-get install -y --no-install-recommends curl ca-certificates tzdata && rm -rf /var/lib/apt/lists/*; \
    fi

WORKDIR /app

# Crear directorio de datos persistentes, punto de checkpoint CRaC y permisos
RUN mkdir -p /jettra/data /jettra/config /jettra/checkpoint && \
    chown -R 1000:1000 /jettra /app

# Copiar JAR autónomo optimizado y archivos de configuración base (sin capas redundantes)
COPY target/JettraStore-*.jar /app/
COPY src/main/resources/META-INF/microprofile-config.properties /app/config/microprofile-config.properties
COPY src/main/resources/cluster.config /app/config/cluster.config

# Garantizar el archivo /app/JettraStore.jar sin duplicar capas pesadas en la imagen
RUN if [ -f /app/JettraStore-*-uber.jar ]; then \
        mv /app/JettraStore-*-uber.jar /app/JettraStore.jar && rm -f /app/JettraStore-*.jar; \
    elif [ -f /app/JettraStore-*.jar ]; then \
        mv /app/JettraStore-*.jar /app/JettraStore.jar; \
    fi

# Variables de entorno predeterminadas para nodo autónomo o clúster
# Configuración de alto rendimiento para base de datos con ZGC (Zero Garbage Collection Pauses < 1ms):
# -XX:+UseContainerSupport respeta los cgroups y límites de memoria del contenedor Docker.
# -XX:+UseZGC recolector de ultra-baja latencia concurrente para bases de datos de alto rendimiento.
# -XX:+UseCompactObjectHeaders (Java 25 Lilliput) reduce la sobrecarga de encabezados de objetos a 64 bits.
# -Xms512m -Xmx2g balance equilibrado entre alta tasa de transacciones concurrentes y consumo controlado de RAM.
ENV JETTRA_NODE_ID=node-01 \
    JETTRA_NODE_ROLE=PRIMARY \
    JETTRA_STORAGE_PATH=/jettra/data \
    JETTRA_STORAGE_MODE=OFF_HEAP \
    JETTRA_CRAC_CHECKPOINT_PATH=/jettra/checkpoint \
    JETTRA_CRAC_RESTORE=false \
    JETTRA_GRPC_PORT=50001 \
    JETTRA_REST_PORT=60001 \
    JETTRA_ADMIN_USERNAME=admin \
    JETTRA_ADMIN_PASSWORD=admin-jettra \
    JAVA_OPTS="--enable-preview --enable-native-access=ALL-UNNAMED -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:+UseContainerSupport -XX:+UseZGC -Xms512m -Xmx2g"

# Exponer puertos: REST HTTP (60001) y Puerto de Clúster / gRPC (50001)
EXPOSE 60001 50001

# Volúmenes para almacenamiento persistente y snapshots de memoria CRaC
VOLUME ["/jettra/data", "/jettra/checkpoint"]

# Verificación de salud periódica mediante endpoint REST
HEALTHCHECK --interval=10s --timeout=5s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:${JETTRA_REST_PORT}/api/v1/health || exit 1

# Usuario no root seguro
USER 1000:1000

# Inicio del servidor autónomo JettraStore
ENTRYPOINT ["sh", "-c", "if [ \"$JETTRA_CRAC_RESTORE\" = \"true\" ] && [ -d \"$JETTRA_CRAC_CHECKPOINT_PATH\" ] && [ \"$(ls -A $JETTRA_CRAC_CHECKPOINT_PATH 2>/dev/null)\" ]; then \
        echo '[CRaC] Restaurando JettraStore instantáneamente desde checkpoint CRIU en '$JETTRA_CRAC_CHECKPOINT_PATH'...'; \
        exec java -XX:CRaCRestoreFrom=$JETTRA_CRAC_CHECKPOINT_PATH; \
    else \
        exec java $JAVA_OPTS -Djettra.storage.mode=${JETTRA_STORAGE_MODE:-OFF_HEAP} -jar /app/JettraStore.jar; \
    fi"]
```

> [!TIP]
> Por máxima compatibilidad en diversos entornos CI/CD y sistemas operativos, se proporcionan los archivos gemelos [`Dockerfile`](file:///home/avbravo/NetBeansProjects/jettrastack_local/jettrastoreworkspace/JettraStore/Dockerfile) y [`DockerFile`](file:///home/avbravo/NetBeansProjects/jettrastack_local/jettrastoreworkspace/JettraStore/DockerFile).

---

#### 6.1.2 Despliegue de Clúster de 3 Nodos en Docker Compose (`docker-compose.yml` / `docker-compose.xml`)

El archivo [`docker-compose.yml`](file:///home/avbravo/NetBeansProjects/jettrastack_local/jettrastorecontainers/JettraStore/docker-compose.yml) (disponible también como alias de compatibilidad [`docker-compose.xml`](file:///home/avbravo/NetBeansProjects/jettrastack_local/jettrastorecontainers/JettraStore/docker-compose.xml)) orquesta la topología de consenso Raft de tres nodos sobre una red bridge privada (`jettra-cluster-net`). 

Incorpora soporte para CRaC/CRIU, límites estrictos de memoria por contenedor, compilación automatizada (`pull_policy: build`) para garantizar que la imagen local `jettrastore:1.0` se construya sin intentar descargas no autorizadas de registros remotos, y selección del modo de almacenamiento (`JVM_RAM`, `DISK_MEMORY` u `OFF_HEAP`):

```yaml
services:
  # ==============================================================================
  # NODO 01: NODO PRINCIPAL / LÍDER (Primary Raft Master)
  # Compatible con CRaC / CRIU y conmutación de modos de almacenamiento
  # ==============================================================================
  jettra-node-01:
    build:
      context: .
      dockerfile: Dockerfile
    pull_policy: build
    image: jettrastore:1.0
    container_name: jettra-node-01
    hostname: jettra-node-01
    restart: unless-stopped
    # Capacidades requeridas por el kernel Linux para CRIU / CRaC (Checkpoint & Restore)
    cap_add:
      - CHECKPOINT_RESTORE
      - SYS_PTRACE
    security_opt:
      - seccomp:unconfined
    ports:
      - "60001:60001"   # API REST / Autenticación JettraJWT en Host
      - "50001:50001"   # Protocolo de Clúster / Raft Sync
    environment:
      - JETTRA_NODE_ID=node-01
      - JETTRA_NODE_ROLE=PRIMARY
      - JETTRA_STORAGE_PATH=/jettra/data
      - JETTRA_STORAGE_MODE=${JETTRA_STORAGE_MODE:-OFF_HEAP}
      - JETTRA_CRAC_CHECKPOINT_PATH=/jettra/checkpoint
      - JETTRA_CRAC_RESTORE=${JETTRA_CRAC_RESTORE:-false}
      - JETTRA_REST_PORT=60001
      - JETTRA_GRPC_PORT=50001
      - JETTRA_ADMIN_USERNAME=admin
      - JETTRA_ADMIN_PASSWORD=admin-jettra
      - JETTRA_CLUSTER_PEERS=node-02:jettra-node-02:50002:SECONDARY,node-03:jettra-node-03:50003:SECONDARY
      - JAVA_OPTS=--enable-preview --enable-native-access=ALL-UNNAMED -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:+UseContainerSupport -XX:+UseZGC -Xms512m -Xmx2g -Djettra.storage.mode=${JETTRA_STORAGE_MODE:-OFF_HEAP}
    mem_limit: 3g
    mem_reservation: 512m
    volumes:
      - jettra_data_node01:/jettra/data
      - jettra_checkpoint_node01:/jettra/checkpoint
    networks:
      - jettra-cluster-net
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:60001/api/v1/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3
      start_period: 5s

  # ==============================================================================
  # NODO 02: PRIMER NODO SECUNDARIO (Secondary Raft Replica 1)
  # ==============================================================================
  jettra-node-02:
    build:
      context: .
      dockerfile: Dockerfile
    pull_policy: build
    image: jettrastore:1.0
    container_name: jettra-node-02
    hostname: jettra-node-02
    restart: unless-stopped
    cap_add:
      - CHECKPOINT_RESTORE
      - SYS_PTRACE
    security_opt:
      - seccomp:unconfined
    depends_on:
      jettra-node-01:
        condition: service_healthy
    ports:
      - "60002:60002"   # API REST Nodo 2
      - "50002:50002"   # Protocolo de Clúster Nodo 2
    environment:
      - JETTRA_NODE_ID=node-02
      - JETTRA_NODE_ROLE=SECONDARY
      - JETTRA_STORAGE_PATH=/jettra/data
      - JETTRA_STORAGE_MODE=${JETTRA_STORAGE_MODE:-OFF_HEAP}
      - JETTRA_CRAC_CHECKPOINT_PATH=/jettra/checkpoint
      - JETTRA_CRAC_RESTORE=${JETTRA_CRAC_RESTORE:-false}
      - JETTRA_REST_PORT=60002
      - JETTRA_GRPC_PORT=50002
      - JETTRA_ADMIN_USERNAME=admin
      - JETTRA_ADMIN_PASSWORD=admin-jettra
      - JETTRA_CLUSTER_PEERS=node-01:jettra-node-01:50001:PRIMARY,node-03:jettra-node-03:50003:SECONDARY
      - JAVA_OPTS=--enable-preview --enable-native-access=ALL-UNNAMED -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:+UseContainerSupport -XX:+UseZGC -Xms512m -Xmx2g -Djettra.storage.mode=${JETTRA_STORAGE_MODE:-OFF_HEAP}
    mem_limit: 3g
    mem_reservation: 512m
    volumes:
      - jettra_data_node02:/jettra/data
      - jettra_checkpoint_node02:/jettra/checkpoint
    networks:
      - jettra-cluster-net
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:60002/api/v1/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3
      start_period: 5s

  # ==============================================================================
  # NODO 03: SEGUNDO NODO SECUNDARIO (Secondary Raft Replica 2)
  # ==============================================================================
  jettra-node-03:
    build:
      context: .
      dockerfile: Dockerfile
    pull_policy: build
    image: jettrastore:1.0
    container_name: jettra-node-03
    hostname: jettra-node-03
    restart: unless-stopped
    cap_add:
      - CHECKPOINT_RESTORE
      - SYS_PTRACE
    security_opt:
      - seccomp:unconfined
    depends_on:
      jettra-node-01:
        condition: service_healthy
    ports:
      - "60003:60003"   # API REST Nodo 3
      - "50003:50003"   # Protocolo de Clúster Nodo 3
    environment:
      - JETTRA_NODE_ID=node-03
      - JETTRA_NODE_ROLE=SECONDARY
      - JETTRA_STORAGE_PATH=/jettra/data
      - JETTRA_STORAGE_MODE=${JETTRA_STORAGE_MODE:-OFF_HEAP}
      - JETTRA_CRAC_CHECKPOINT_PATH=/jettra/checkpoint
      - JETTRA_CRAC_RESTORE=${JETTRA_CRAC_RESTORE:-false}
      - JETTRA_REST_PORT=60003
      - JETTRA_GRPC_PORT=50003
      - JETTRA_ADMIN_USERNAME=admin
      - JETTRA_ADMIN_PASSWORD=admin-jettra
      - JETTRA_CLUSTER_PEERS=node-01:jettra-node-01:50001:PRIMARY,node-02:jettra-node-02:50002:SECONDARY
      - JAVA_OPTS=--enable-preview --enable-native-access=ALL-UNNAMED -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:+UseContainerSupport -XX:+UseZGC -Xms512m -Xmx2g -Djettra.storage.mode=${JETTRA_STORAGE_MODE:-OFF_HEAP}
    mem_limit: 3g
    mem_reservation: 512m
    volumes:
      - jettra_data_node03:/jettra/data
      - jettra_checkpoint_node03:/jettra/checkpoint
    networks:
      - jettra-cluster-net
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:60003/api/v1/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3
      start_period: 5s

# ==============================================================================
# VOLÚMENES PERSISTENTES PARA CADA NODO (Datos .jettra y Checkpoints CRaC)
# ==============================================================================
volumes:
  jettra_data_node01:
    name: jettra_data_node01
    driver: local
  jettra_data_node02:
    name: jettra_data_node02
    driver: local
  jettra_data_node03:
    name: jettra_data_node03
    driver: local
  jettra_checkpoint_node01:
    name: jettra_checkpoint_node01
    driver: local
  jettra_checkpoint_node02:
    name: jettra_checkpoint_node02
    driver: local
  jettra_checkpoint_node03:
    name: jettra_checkpoint_node03
    driver: local

# ==============================================================================
# RED BRIDGE PRIVADA PARA EL CLÚSTER RAFT DISTRIBUIDO
# ==============================================================================
networks:
  jettra-cluster-net:
    name: jettra-cluster-net
    driver: bridge
```

---

#### 6.1.3 Almacenamiento Persistente y Mapeo de Volúmenes (`/jettra/data`)

Para asegurar durabilidad ACID y supervivencia de los datos ante reinicios o recreaciones de contenedores:
* Cada nodo posee un volumen de Docker independiente (`jettra_data_node01`, `jettra_data_node02`, `jettra_data_node03`) montado en `/jettra/data`.
* Dentro de `/jettra/data`, el motor `JettraStore` escribe los segmentos binarios `.jettra`, el registro de transacciones Write-Ahead Log (`wal.bin`) y los metadatos de índices dispersos.
* Los volúmenes son desacoplados del ciclo de vida del contenedor, garantizando que un `docker compose down` mantenga íntegro el estado del clúster.
* Dentro de `/app/data`, el motor `JettraStore` escribe los segmentos binarios `.jettra`, el registro de transacciones Write-Ahead Log (`wal.bin`) y los metadatos de índices dispersos.
* Los volúmenes son desacoplados del ciclo de vida del contenedor, garantizando que un `docker compose down` mantenga íntegro el estado del clúster.

---

#### 6.1.4 Autenticación Obligatoria mediante Tokens `JettraJWT` en Contenedores

La API REST y el clúster gRPC implementan seguridad estricta mediante tokens **`JettraJWT`**.

##### 1. Obtención del Token de Acceso
El endpoint nativo `/api/v1/auth/token` autentica las credenciales del Superusuario:

```bash
# Solicitar Token JettraJWT al Nodo Principal (Puerto 60001)
TOKEN=$(curl -s -X POST http://localhost:60001/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin-jettra"}' \
  | grep -o '"token":"[^"]*' | cut -d'"' -f4)

echo "Token JettraJWT Obtenido: $TOKEN"
```

Respuesta JSON del servidor:
```json
{
  "status": "SUCCESS",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "type": "Bearer",
  "expiresIn": 86400,
  "user": "admin",
  "role": "SUPER_ADMIN"
}
```

##### 2. Consulta Protegida del Clúster
Cualquier petición administrativa o transaccional debe enviar la cabecera `Authorization: Bearer <TOKEN>`:

```bash
# Consultar estado del Nodo Principal
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:60001/api/v1/cluster/status

# Consultar estado del Nodo Secundario 1
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:60002/api/v1/cluster/status

# Consultar estado del Nodo Secundario 2
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:60003/api/v1/cluster/status
```

Respuesta autorizada del nodo:
```json
{
  "status": "ONLINE",
  "nodeId": "node-01",
  "role": "PRIMARY",
  "activeConnections": 1,
  "members": 3,
  "memoryUsedMb": 58,
  "walSequence": 0
}
```

> [!CAUTION]
> Si una petición no incluye la cabecera `Authorization` o el token es inválido o expirado, el servidor responde inmediatamente con código HTTP `401 Unauthorized` y bloquea el acceso.

---

#### 6.1.5 Operaciones del Ciclo de Vida, Monitoreo y Comandos CLI

| Operación | Comando |
|---|---|
| **Compilar Artefactos Maven** | `mvn clean install -DskipTests` |
| **Construir e Iniciar Clúster** | `docker compose up -d --build` |
| **Verificar Estado de Contenedores**| `docker compose ps` |
| **Verificar Logs en Vivo** | `docker compose logs -f` |
| **Logs de un Nodo Específico** | `docker compose logs -f jettra-node-01` |
| **Detener Preservando Datos** | `docker compose down` |
| **Detener Eliminando Volúmenes**| `docker compose down -v` |


### 6.2 Guía de Despliegue de Clúster en Producción: JARs Distribuidos y Docker Compose

JettraStore soporta despliegues de alta disponibilidad con consenso Raft y anillo elástico en topologías distribuidas. A continuación se detallan los procedimientos oficiales de instalación, archivos requeridos, configuraciones de red y comandos de ejecución para los tres escenarios operativos principales:

---

#### 6.2.1 Despliegue Mediante Archivos JAR en Máquinas Diferentes

Este escenario es el estándar para entornos bare-metal o máquinas virtuales corporativas donde cada nodo se ejecuta como un proceso Java nativo en un servidor físico o virtual independiente, aprovechando el acceso directo a memoria física mediante Project Panama FFM y Compact Object Headers.

##### 6.2.1.1 Inventario de Archivos a Colocar en Cada Servidor

En cada máquina donde se ejecutará un nodo de JettraStore, cree un directorio de despliegue (por ejemplo `/opt/jettra/` o `~/jettra/`) con los siguientes archivos:

```text
/opt/jettra/
├── bin/
│   └── JettraStore-1.0-SNAPSHOT-uber.jar    # Uber JAR autónomo (Fat JAR con todas las dependencias)
├── config/
│   ├── cluster.config                       # Topología de red del clúster Raft
│   └── microprofile-config.properties       # Parámetros del motor y persistencia (MicroProfile Config)
├── data/                                    # Directorio local de almacenamiento inmutable
└── start-node.sh                            # Script de arranque con flags JVM Java 25
```

> **Generación del paquete:** Para generar el Uber JAR autónomo ejecute en el proyecto:
> ```bash
> mvn clean package
> ```
> El plugin `maven-shade-plugin` compila y empaqueta automáticamente el artefacto autónomo `target/JettraStore-1.0-SNAPSHOT-uber.jar`. No se requiere transferir un directorio `lib/` ni copiar dependencias por separado, ya que el artefacto contiene todo el ecosistema de librerías embebido y listo para ejecución.

---

##### 6.2.1.2 Configuración del Archivo de Topología `cluster.config`

El archivo `cluster.config` define la topología de consenso del clúster. Debe colocarse en la carpeta `config/` de cada servidor (o compartirse idéntico entre ellos indicando las IPs reales de cada máquina en la LAN):

```properties
# /opt/jettra/config/cluster.config
################################################################################
# JettraStore Cluster Topology Configuration (cluster.config)
# Centralized 3-Node Topology for Raft Consensus and Dynamic Ring Discovery
################################################################################

cluster.name = jettra-production-cluster
cluster.consensus.protocol = RAFT
cluster.ring.enabled = true
cluster.heartbeat.interval.ms = 150
cluster.election.timeout.ms = 300
# ==============================================================================
# NODO 1: NODO PRINCIPAL / LÍDER (Primary)
# ==============================================================================
cluster.node.1.id = node-01
cluster.node.1.role = PRIMARY
cluster.node.1.ip = 192.168.1.101
cluster.node.1.grpc.port = 50001
cluster.node.1.rest.port = 60001

# ==============================================================================
# NODO 2: NODO SECUNDARIO / SEGUIDOR 1 (Secondary)
# ==============================================================================
cluster.node.2.id = node-02
cluster.node.2.role = SECONDARY
cluster.node.2.ip = 192.168.1.102
cluster.node.2.grpc.port = 50001
cluster.node.2.rest.port = 60001

# ==============================================================================
# NODO 3: NODO SECUNDARIO / SEGUIDOR 2 (Secondary)
# ==============================================================================
cluster.node.3.id = node-03
cluster.node.3.role = SECONDARY
cluster.node.3.ip = 192.168.1.103
cluster.node.3.grpc.port = 50001
cluster.node.3.rest.port = 60001

# Asignación de Capacidad de Índices y Buffers en Clúster
cluster.index.initial.capacity = 65536
cluster.index.max.inmemory.keys = 100000
```
---

##### 6.2.1.3 Configuración de `microprofile-config.properties` en Cada Servidor

```properties
# /opt/jettra/config/microprofile-config.properties
################################################################################
# JettraStore Core Engine Configuration (microprofile-config.properties)
# Low-level JVM tuning, Panama FFM settings, JettraPolice, and Cluster Node Link
################################################################################

# Ruta de almacenamiento local exclusiva de este nodo
jettra.storage.path = ~/jettra-storage-01/data

# Identificador y rol del nodo local en el clúster (enlaza con cluster.config)
cluster.node.id = node-01
cluster.node.role = PRIMARY

# Topología Multinodo y Algoritmo de Consenso Distribuido
#   on:  Activa el sistema de distribución de datos mediante algoritmo de consenso integrado en JettraStore.
#   off: Desactiva distribución y opera exclusivamente en modo local standalone.
cluster.multinode.active = on

# Red y Virtual Threads (IP y puertos gRPC/REST se obtienen de cluster.config; almacenamiento en jettra.storage.path)
jettra.network.virtualthreads.enabled = true

# Estructura LSM y Memoria Off-Heap con Project Panama
jettra.storage.memtable.size.mb = 128
jettra.storage.ram.global.limit.mb = 2048
jettra.storage.offheap.direct = true
# Modo de almacenamiento: OFF_HEAP, DISK_MEMORY o JVM_RAM
jettra.storage.mode = OFF_HEAP
jettra.storage.file.extension = .jettra

# Umbrales para la TransiciÃ³n DinÃ¡mica a Motor de Anillo Distribuido
# Cuando la RAM ocupada alcanza el 85%, el nodo principal descarga carga a los secundarios
jettra.ring.saturation.threshold.percent = 85
jettra.ring.release.target.percent = 45

# Componente AutÃ³nomo de SupervisiÃ³n Preventiva (JettraPolice)
# Habilitado por defecto en hilo demonio de bajo consumo
jettrapolice.active = true
jettrapolice.interval.ms = 500
jettrapolice.ram.warning.threshold = 75
jettrapolice.disk.warning.threshold = 90

# MÃ©tricas de Rendimiento y Microbenchmarking con JMH
# true para habilitar en pruebas/diagnÃ³stico, false para producciÃ³n de mÃ¡xima velocidad
jmh.metrics.active = false

# Seguridad y AutenticaciÃ³n CriptogrÃ¡fica con JettraJWT
jettra.security.jwt.algorithm = Ed25519
jettra.security.jwt.expiration.seconds = 86400
jettra.security.jwt.issuer = jettra-store-authority

# Superusuario por Defecto (Provisionamiento Obligatorio Inicial)
# Credenciales: admin / admin-jettra (MÃ¡xima prioridad y privilegios absolutos inmutables)
jettra.security.default.admin.username = admin
jettra.security.default.admin.password = admin-jettra


# ==============================================================================
# OptimizaciÃ³n de Almacenamiento de Ãndices y Ahorro de Memoria Heap (Anti-OOM)
# ==============================================================================
# Capacidad inicial optimizada para mapas de Ã­ndices secundarios (evita rehashes costosos)
jettra.index.initial.capacity = 65536

# LÃ­mite mÃ¡ximo de claves de Ã­ndice almacenadas en memoria antes de muestreo disperso
jettra.index.max.inmemory.keys = 100000

# Uso de almacenamiento compacto para Ã­ndices secundarios (Singletons sin Set)
jettra.index.compact.storage = true


# Umbral de descarga periÃ³dica de MemTable durante cargas masivas (Auto-Flush)
jettra.storage.autoflush.batch.size = 50000

# ==============================================================================
# Directivas de Consulta y LÃ­mites de Seguridad de Memoria (SQL & LQL Anti-OOM)
# ==============================================================================
# LÃ­mite por defecto para SELECT / FROM sin clÃ¡usula LIMIT explÃ­cita
jettra.query.default.limit = 50

# LÃ­mite mÃ¡ximo absoluto permitido por consulta en memoria para proteger el Heap
jettra.query.max.limit = 5000

# TamaÃ±o de pÃ¡gina para visualizaciÃ³n en consola interactiva (Shell / FX)
jettra.query.pagesize = 50

# Directivas de Estabilidad y Control de Memoria Heap de JettraPolice (Anti-OOM)
jettrapolice.ram.critical.threshold = 85
jettrapolice.auto.pagination.enabled = true
jettrapolice.max.safe.batch.size = 100

```

---

##### 6.2.1.4 Banderas JVM de Java 25 y Comandos de Ejecución por Servidor

JettraStore implementa la lectura unificada y validación cruzada automática de **ambos** archivos de configuración:
1. **`cluster.config`**: Provee la topología completa del clúster y para cada nodo define su `id`, `role`, `ip`, `grpc.port` y `rest.port` (sin propiedades `storage.path`).
2. **`microprofile-config.properties`**: Provee la ruta de almacenamiento propia del nodo (`jettra.storage.path`, a partir de la cual se deriva `<jettra.storage.path>/indexes`), la identidad del nodo local (`cluster.node.id`, `cluster.node.role`) para enlazar con su bloque en `cluster.config`, además de los límites de memoria, modo de almacenamiento y ajustes del motor.

###### Enlace Directo mediante `cluster.node.id`
Gracias a que la IP, el puerto REST (`cluster.node.<N>.rest.port`) y el puerto gRPC (`cluster.node.<N>.grpc.port`) se obtienen directamente desde `cluster.config` buscando el nodo que coincida con `cluster.node.id` (por ejemplo `cluster.node.id=node-01` o `cluster.node.id=01`), **no es necesario duplicar puertos en `microprofile-config.properties`**, mientras que cada nodo define su propio directorio de datos en `jettra.storage.path` sin generar conflictos en `cluster.config`. Si el `cluster.node.id` indicado no existe en `cluster.config`, JettraStore emite una advertencia y detiene su ejecución.

En cada servidor físico o VM, basta con indicar el nodo correspondiente en su archivo `microprofile-config.properties`:
* En Servidor 1: `cluster.node.id = node-01` y `cluster.node.role = PRIMARY`
* En Servidor 2: `cluster.node.id = node-02` y `cluster.node.role = SECONDARY`
* En Servidor 3: `cluster.node.id = node-03` y `cluster.node.role = SECONDARY`

O bien sobreescribirlo en la línea de comandos mediante `-Dcluster.node.id` y `-Dcluster.node.role`:

**En Servidor 1 (IP `192.168.1.101` - Nodo Primario):**
```bash
java --enable-preview \
     --enable-native-access=ALL-UNNAMED \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+UseCompactObjectHeaders \
     -XX:+UseZGC \
     -Xms2g -Xmx6g \
     -Dcluster.node.id=node-01 \
     -Dcluster.node.role=PRIMARY \
     -Dcluster.config.path=cluster.config \
     -Dmicroprofile.config.file=microprofile-config.properties \
     -jar JettraStore-1.0-SNAPSHOT-uber.jar
```

**En Servidor 2 (IP `192.168.1.102` - Nodo Secundario 1):**
```bash
java --enable-preview \
     --enable-native-access=ALL-UNNAMED \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+UseCompactObjectHeaders \
     -XX:+UseZGC \
     -Xms2g -Xmx6g \
     -Dcluster.node.id=node-02 \
     -Dcluster.node.role=SECONDARY \
     -Dcluster.config.path=cluster.config \
     -Dmicroprofile.config.file=microprofile-config.properties \
     -jar JettraStore-1.0-SNAPSHOT-uber.jar
```

**En Servidor 3 (IP `192.168.1.103` - Nodo Secundario 2):**
```bash
java --enable-preview \
     --enable-native-access=ALL-UNNAMED \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+UseCompactObjectHeaders \
     -XX:+UseZGC \
     -Xms2g -Xmx6g \
     -Dcluster.node.id=node-03 \
     -Dcluster.node.role=SECONDARY \
     -Dcluster.config.path=cluster.config \
     -Dmicroprofile.config.file=microprofile-config.properties \
     -jar JettraStore-1.0-SNAPSHOT-uber.jar
```

###### Ejecución Simplificada cuando `cluster.node.id` ya está configurado en `microprofile-config.properties`
Cuando cada servidor tiene su `cluster.node.id` y `cluster.node.role` definidos en su propio `microprofile-config.properties`:
```bash
java --enable-preview \
     --enable-native-access=ALL-UNNAMED \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+UseCompactObjectHeaders \
     -XX:+UseZGC \
     -Xms2g -Xmx6g \
     -Dcluster.config.path=cluster.config \
     -jar JettraStore-1.0-SNAPSHOT-uber.jar
```

*(Las variables de entorno `export JETTRA_NODE_ID=...`, `export JETTRA_STORAGE_PATH=...`, etc. continúan siendo plenamente compatibles para entornos basados en Docker y Docker Compose).*

**Secuencia y Verificación:**
1. Iniciar primero el Servidor 1 (Líder).
2. Iniciar los Servidores 2 y 3 (Seguidores).
3. Verificar en cualquier nodo:
   ```bash
   curl http://192.168.1.101:60001/api/v1/health
   curl http://192.168.1.102:60001/api/v1/health
   curl http://192.168.1.103:60001/api/v1/health
   ```

##### Alternativa ejecutando start-node.sh

El script `start-node.sh` automatiza la configuración de banderas de la máquina virtual Java 25, la detección inteligente del artefacto JAR y la resolución de las rutas de configuración:

```bash
# Otorgar permisos de ejecución al script si es necesario
chmod +x start-node.sh

# Sintaxis de ejecución:
#   ./start-node.sh [NODE_ID] [NODE_ROLE] [CLUSTER_CONFIG] [MICROPROFILE_CONFIG]

# Ejemplos de uso:
./start-node.sh node-01 PRIMARY
./start-node.sh node-02 SECONDARY
./start-node.sh node-03 SECONDARY

# Ejemplo especificando archivos de configuración personalizados:
./start-node.sh node-04 SECONDARY config/cluster.config config/microprofile-config.properties
```

---

##### 6.2.1.5 Procedimiento para la Incorporación de Nuevos Nodos al Clúster (Ejecución Directa Java JAR)

Para incorporar nuevos nodos dinámicamente a un clúster `JettraStore` en ejecución cuando se utilizan archivos JAR directos (en máquinas físicas independientes o máquinas virtuales), el proceso está diseñado para ser sumamente ágil, desacoplado y sin necesidad de reiniciar los nodos preexistentes.

###### 1. Estructura de Archivos del Nuevo Nodo

En el nuevo host o máquina donde operará el nodo, cree un directorio exclusivo que contenga los siguientes elementos:

```text
/opt/jettra-nuevo-nodo/
├── JettraStore-1.0-SNAPSHOT-uber.jar    # Binario ejecutable autónomo (Fat JAR con todas las dependencias)
├── start-node.sh                        # Script de inicio con flags de ejecución Java 25 y detección inteligente
└── config/
    ├── cluster.config                   # Configuración inicial del nuevo nodo (o cluster.info)
    └── microprofile-config.properties   # Parámetros del motor, almacenamiento local y enlace de identidad
```

> [!IMPORTANT]
> **Configuración Simplificada de `cluster.config` / `cluster.info` en el Nuevo Nodo:**
> Para que un nuevo nodo se conecte al clúster, **únicamente necesita definirse en el archivo `cluster.config` (o `cluster.info`) de ese nodo**.
> No es necesario registrar manualmente los otros nodos del clúster ni copiar la topología previa en este archivo, ya que el proceso formal de agregarlo al clúster o removerlo de él se realizará dinámicamente desde **JettraStoreExplorer** o desde **JettraStoreShell**.

###### 2. Contenido de `config/cluster.config` (o `cluster.info`) del Nuevo Nodo

En el archivo de topología local del nuevo nodo sólo se declaran los datos de su propio endpoint:

```properties
# config/cluster.config (o cluster.info) en el nuevo nodo
cluster.name = jettra-production-cluster
cluster.consensus.protocol = RAFT
cluster.ring.enabled = true
cluster.heartbeat.interval.ms = 150
cluster.election.timeout.ms = 300

# ==============================================================================
# Información exclusiva de este nodo (ejemplo: node-04)
# ==============================================================================
cluster.node.1.id = node-04
cluster.node.1.role = SECONDARY
cluster.node.1.ip = 192.168.1.104
cluster.node.1.grpc.port = 50001
cluster.node.1.rest.port = 60001

cluster.index.initial.capacity = 65536
cluster.index.max.inmemory.keys = 100000
```

###### 3. Contenido de `config/microprofile-config.properties` del Nuevo Nodo

Revise el archivo `microprofile-config.properties` del nuevo nodo para asegurar que la identidad del nodo y su almacenamiento exclusivo en disco coincidan:

```properties
# config/microprofile-config.properties en el nuevo nodo
# Ruta de almacenamiento local exclusiva de este nodo
jettra.storage.path = /opt/jettra-nuevo-nodo/data

# Identidad del nodo local (debe coincidir con el ID declarado en cluster.config / cluster.info)
cluster.node.id = node-04
cluster.node.role = SECONDARY
cluster.multinode.active = on

# Ajustes de almacenamiento y memoria Off-Heap con Project Panama FFM
jettra.storage.mode = OFF_HEAP
jettra.storage.memtable.size.mb = 128
jettra.storage.ram.global.limit.mb = 2048
jettra.storage.offheap.direct = true
jettra.network.virtualthreads.enabled = true
jettrapolice.active = true
```

###### 4. Contenido y Ajuste del Script de Arranque `start-node.sh`

Ajuste o ejecute el script `start-node.sh` indicando el nodo y su rol correspondiente:

```bash
# Iniciar nodo especificando su identificador y rol
./start-node.sh node-04 SECONDARY
```

El script configurará automáticamente las banderas de JVM para Java 25 LTS:
```bash
java --enable-preview \
     --enable-native-access=ALL-UNNAMED \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+UseCompactObjectHeaders \
     -XX:+UseZGC \
     -Xms2g -Xmx6g \
     -Dcluster.node.id=node-04 \
     -Dcluster.node.role=SECONDARY \
     -Djettra.node.id=node-04 \
     -Djettra.node.role=SECONDARY \
     -Dcluster.config.path=config/cluster.config \
     -Dmicroprofile.config.file=config/microprofile-config.properties \
     -jar JettraStore-1.0-SNAPSHOT-uber.jar
```

El proceso Java iniciará sus endpoints gRPC (puerto `50001`) y REST (puerto `60001`) quedando en estado de escucha a la espera de unirse al quórum distribuido.

###### 5. Incorporación Formal desde JettraStoreExplorer o JettraStoreShell

Una vez iniciado el nodo secundario con `start-node.sh`, se procede a añadirlo formalmente al clúster mediante cualquiera de las herramientas administrativas:

* **Opción A: Desde JettraStoreExplorer (Consola Web):**
  Acceda a la consola web en la ruta `/cluster`, haga clic en el botón **"Añadir Nodo"** e introduzca el ID (`node-04`), IP (`192.168.1.104`), puerto gRPC (`50001`), puerto REST (`60001`) y rol (`SECONDARY`).
  Asimismo, para dar de baja un nodo, simplemente presione el botón **"Expulsar"** junto al nodo deseado.

* **Opción B: Desde JettraStoreShell (Consola CLI):**
  Conéctese a cualquier nodo activo del clúster (o directamente al primario) y ejecute:
  ```bash
  CLUSTER ADD-NODE node-04 192.168.1.104 50001 SECONDARY 60001
  ```
  O con la sintaxis simplificada:
  ```bash
  ADD NODE node-04 192.168.1.104 50001 SECONDARY
  ```
  Para remover un nodo secundario:
  ```bash
  REMOVE NODE node-04
  # O bien:
  CLUSTER REMOVE-NODE node-04
  ```

###### 6. Replicación Canónica de Configuración (PRIMARY Primero, luego SECONDARY)

Tanto JettraStoreExplorer como JettraStoreShell replican la configuración consolidada `cluster.config` siguiendo un estricto orden jerárquico:
1. **Actualización prioritaria al nodo PRIMARY:** Se envía la trama de actualización de topología (`TYPE_SYNC_CLUSTER_CONFIG`) primero al nodo líder (**PRIMARY**), permitiendo que actualice el quórum Raft inmediatamente.
2. **Propagación a los nodos SECONDARY:** Acto seguido, la configuración unificada se transmite a todos los nodos secundarios existentes y al nuevo nodo recién añadido.

###### 7. Sincronización Automática Integral de Bases de Datos y Objetos desde el PRIMARY

> [!IMPORTANT]
> **Consistencia Total y Sincronización Automática:**
> **Cada vez que un nodo se añade al clúster o cuando fue detenido y luego se reinicia, este nodo se debe actualizar con el estado del nodo PRIMARY, es decir debe obtener la réplica completa de la base de datos y objetos desde el nodo PRIMARY para que el clúster se mantenga actualizado siempre.**
>
> * **Al añadirse un nuevo nodo:** Tanto desde JettraStoreExplorer como desde JettraStoreShell se dispara automáticamente la notificación hacia el PRIMARY, el cual consolida la instantánea de todas las bases de datos y la transmite de inmediato al nuevo nodo.
> * **Al reiniciarse o reconectarse un nodo:** Cuando un nodo que estaba detenido vuelve a estar en línea (o tras ejecutarse `start node` o recuperarse el latido de red), el clúster detecta el restablecimiento y el nodo PRIMARY le transmite automáticamente la instantánea con el estado más reciente de todas las colecciones, documentos, grafos, series temporales, vectores y objetos multimodelo.
> * **Actualización Inmediata de Estatus en JettraStoreExplorer:** Al detenerse o iniciarse un nodo registrado, las páginas `/map` y `/cluster` reflejan enseguida el cambio de estado (`RUNNING`, `STOPPED`, `OFFLINE`) en tiempo real mediante el canal Server-Sent Events (SSE).

---

#### 6.2.2 Despliegue Mediante Docker Compose Monolítico (Todos los Nodos en la Misma Máquina)

Este modo es ideal para desarrollo local, pruebas de integración, entornos CI/CD y validación de escenarios de conmutación por error en una única estación de trabajo o servidor de pruebas.

Para evitar colisiones de puertos en la misma interfaz de red del host:
* Cada contenedor expone un puerto REST y gRPC externo diferente (`60001`, `60002`, `60003` / `50001`, `50002`, `50003`).
* Internamente, los contenedores se comunican a través de la red virtual tipo bridge `jettra-cluster-net` utilizando sus nombres DNS internos (`jettra-node-01`, `jettra-node-02`, `jettra-node-03`).

##### 6.2.2.1 Archivo `docker-compose.yml` / `docker-compose.xml` Completo

El siguiente archivo define la orquestación integral del clúster de 3 nodos. Está disponible tanto con el nombre estándar [`docker-compose.yml`](file:///home/avbravo/NetBeansProjects/jettrastack_local/jettrastorecontainers/JettraStore/docker-compose.yml) como mediante el enlace simbólico [`docker-compose.xml`](file:///home/avbravo/NetBeansProjects/jettrastack_local/jettrastorecontainers/JettraStore/docker-compose.xml) para herramientas o flujos de trabajo que requieran dicha extensión.

> **Nota sobre el contexto de construcción (`context`):**
> * Si ejecuta `docker compose` desde el directorio **`JettraStore/`**, utilice `context: .`.
> * Si ejecuta `docker compose` desde el directorio raíz **`jettrastorecontainers/`**, utilice `context: ./JettraStore`.

```yaml
services:
  # ==============================================================================
  # NODO 01: NODO PRINCIPAL / LÍDER (Primary Raft Master)
  # Compatible con CRaC / CRIU y conmutación de modos de almacenamiento
  # ==============================================================================
  jettra-node-01:
    build:
      context: .
      dockerfile: Dockerfile
    pull_policy: build
    image: jettrastore:1.0
    container_name: jettra-node-01
    hostname: jettra-node-01
    restart: unless-stopped
    # Capacidades requeridas por el kernel Linux para CRIU / CRaC (Checkpoint & Restore)
    cap_add:
      - CHECKPOINT_RESTORE
      - SYS_PTRACE
    security_opt:
      - seccomp:unconfined
    ports:
      - "60001:60001"   # API REST / Autenticación JettraJWT en Host
      - "50001:50001"   # Protocolo de Clúster / Raft Sync
    environment:
      - JETTRA_NODE_ID=node-01
      - JETTRA_NODE_ROLE=PRIMARY
      - JETTRA_STORAGE_PATH=/jettra/data
      - JETTRA_STORAGE_MODE=${JETTRA_STORAGE_MODE:-OFF_HEAP}
      - JETTRA_CRAC_CHECKPOINT_PATH=/jettra/checkpoint
      - JETTRA_CRAC_RESTORE=${JETTRA_CRAC_RESTORE:-false}
      - JETTRA_REST_PORT=60001
      - JETTRA_GRPC_PORT=50001
      - JETTRA_ADMIN_USERNAME=admin
      - JETTRA_ADMIN_PASSWORD=admin-jettra
      - JETTRA_CLUSTER_PEERS=node-02:jettra-node-02:50002:SECONDARY,node-03:jettra-node-03:50003:SECONDARY
      - JAVA_OPTS=--enable-preview --enable-native-access=ALL-UNNAMED -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:+UseContainerSupport -XX:+UseZGC -Xms512m -Xmx2g -Djettra.storage.mode=${JETTRA_STORAGE_MODE:-OFF_HEAP}
    mem_limit: 3g
    mem_reservation: 512m
    volumes:
      - jettra_data_node01:/jettra/data
      - jettra_checkpoint_node01:/jettra/checkpoint
    networks:
      - jettra-cluster-net
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:60001/api/v1/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3
      start_period: 5s

  # ==============================================================================
  # NODO 02: PRIMER NODO SECUNDARIO (Secondary Raft Replica 1)
  # ==============================================================================
  jettra-node-02:
    build:
      context: .
      dockerfile: Dockerfile
    pull_policy: build
    image: jettrastore:1.0
    container_name: jettra-node-02
    hostname: jettra-node-02
    restart: unless-stopped
    cap_add:
      - CHECKPOINT_RESTORE
      - SYS_PTRACE
    security_opt:
      - seccomp:unconfined
    depends_on:
      jettra-node-01:
        condition: service_healthy
    ports:
      - "60002:60002"   # API REST Nodo 2
      - "50002:50002"   # Protocolo de Clúster Nodo 2
    environment:
      - JETTRA_NODE_ID=node-02
      - JETTRA_NODE_ROLE=SECONDARY
      - JETTRA_STORAGE_PATH=/jettra/data
      - JETTRA_STORAGE_MODE=${JETTRA_STORAGE_MODE:-OFF_HEAP}
      - JETTRA_CRAC_CHECKPOINT_PATH=/jettra/checkpoint
      - JETTRA_CRAC_RESTORE=${JETTRA_CRAC_RESTORE:-false}
      - JETTRA_REST_PORT=60002
      - JETTRA_GRPC_PORT=50002
      - JETTRA_ADMIN_USERNAME=admin
      - JETTRA_ADMIN_PASSWORD=admin-jettra
      - JETTRA_CLUSTER_PEERS=node-01:jettra-node-01:50001:PRIMARY,node-03:jettra-node-03:50003:SECONDARY
      - JAVA_OPTS=--enable-preview --enable-native-access=ALL-UNNAMED -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:+UseContainerSupport -XX:+UseZGC -Xms512m -Xmx2g -Djettra.storage.mode=${JETTRA_STORAGE_MODE:-OFF_HEAP}
    mem_limit: 3g
    mem_reservation: 512m
    volumes:
      - jettra_data_node02:/jettra/data
      - jettra_checkpoint_node02:/jettra/checkpoint
    networks:
      - jettra-cluster-net
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:60002/api/v1/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3
      start_period: 5s

  # ==============================================================================
  # NODO 03: SEGUNDO NODO SECUNDARIO (Secondary Raft Replica 2)
  # ==============================================================================
  jettra-node-03:
    build:
      context: .
      dockerfile: Dockerfile
    pull_policy: build
    image: jettrastore:1.0
    container_name: jettra-node-03
    hostname: jettra-node-03
    restart: unless-stopped
    cap_add:
      - CHECKPOINT_RESTORE
      - SYS_PTRACE
    security_opt:
      - seccomp:unconfined
    depends_on:
      jettra-node-01:
        condition: service_healthy
    ports:
      - "60003:60003"   # API REST Nodo 3
      - "50003:50003"   # Protocolo de Clúster Nodo 3
    environment:
      - JETTRA_NODE_ID=node-03
      - JETTRA_NODE_ROLE=SECONDARY
      - JETTRA_STORAGE_PATH=/jettra/data
      - JETTRA_STORAGE_MODE=${JETTRA_STORAGE_MODE:-OFF_HEAP}
      - JETTRA_CRAC_CHECKPOINT_PATH=/jettra/checkpoint
      - JETTRA_CRAC_RESTORE=${JETTRA_CRAC_RESTORE:-false}
      - JETTRA_REST_PORT=60003
      - JETTRA_GRPC_PORT=50003
      - JETTRA_ADMIN_USERNAME=admin
      - JETTRA_ADMIN_PASSWORD=admin-jettra
      - JETTRA_CLUSTER_PEERS=node-01:jettra-node-01:50001:PRIMARY,node-02:jettra-node-02:50002:SECONDARY
      - JAVA_OPTS=--enable-preview --enable-native-access=ALL-UNNAMED -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:+UseContainerSupport -XX:+UseZGC -Xms512m -Xmx2g -Djettra.storage.mode=${JETTRA_STORAGE_MODE:-OFF_HEAP}
    mem_limit: 3g
    mem_reservation: 512m
    volumes:
      - jettra_data_node03:/jettra/data
      - jettra_checkpoint_node03:/jettra/checkpoint
    networks:
      - jettra-cluster-net
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:60003/api/v1/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3
      start_period: 5s

# ==============================================================================
# VOLÚMENES PERSISTENTES PARA CADA NODO (Datos .jettra y Checkpoints CRaC)
# ==============================================================================
volumes:
  jettra_data_node01:
    name: jettra_data_node01
    driver: local
  jettra_data_node02:
    name: jettra_data_node02
    driver: local
  jettra_data_node03:
    name: jettra_data_node03
    driver: local
  jettra_checkpoint_node01:
    name: jettra_checkpoint_node01
    driver: local
  jettra_checkpoint_node02:
    name: jettra_checkpoint_node02
    driver: local
  jettra_checkpoint_node03:
    name: jettra_checkpoint_node03
    driver: local

# ==============================================================================
# RED BRIDGE PRIVADA PARA EL CLÚSTER RAFT DISTRIBUIDO
# ==============================================================================
networks:
  jettra-cluster-net:
    name: jettra-cluster-net
    driver: bridge
```

##### 6.2.2.2 Comandos Operativos

```bash
# 1. Construir imágenes y levantar clúster completo en segundo plano (usando docker-compose.yml o docker-compose.xml)
docker compose up -d --build
# o explícitamente:
docker compose -f docker-compose.xml up -d --build

# 2. Verificar estado de los contenedores
docker compose ps

# 3. Consultar telemetría de cada nodo desde el host
curl http://localhost:60001/api/v1/health
curl http://localhost:60002/api/v1/health
curl http://localhost:60003/api/v1/health

# 4. Detener el clúster preservando los volúmenes de datos
docker compose down
```

##### 6.2.2.3 Conexión e Integración con JettraStoreExplorer (`cluster.ip.nodes` y `cluster-state.config`)

El panel de administración web **`JettraStoreExplorer`** monitorea en tiempo real mediante *Virtual Threads* (Loom) el estado de salud, disponibilidad y latencia de todos los nodos del clúster en la vista `/cluster`, descubriendo la topología completa directamente desde `cluster-state.config` del nodo `PRIMARY`.

Para que `JettraStoreExplorer` conecte con el clúster definido en `docker-compose.yml`, se configura `cluster.ip.nodes` en `microprofile-config.properties` (o `conecction.config`):

```properties
# Punto de entrada al Clúster JettraStore en Docker Compose (Host Local)
cluster.ip.nodes=127.0.0.1:50001
```

###### Escenario Alternativo: JettraStoreExplorer dentro de la Red Docker (`jettra-cluster-net`)
Si `JettraStoreExplorer` se ejecuta dentro de un contenedor Docker conectado a `jettra-cluster-net`, utiliza el nombre de servicio DNS del nodo líder (`jettra-node-01:50001`):
```properties
cluster.ip.nodes=jettra-node-01:50001
```

---

#### 6.2.3 Despliegue Distribuido Mediante Docker Compose (Un Nodo por Máquina Física o VM)

En entornos de producción reales distribuidos, cada máquina física o VM ejecuta **únicamente un contenedor Docker** que representa su nodo en el clúster. Los contenedores se comunican entre servidores a través de la red física local o VPN corporativa.

* **Servidor A (IP `192.168.1.101`):** Ejecuta `jettra-node-01` (Líder / Primary).
* **Servidor B (IP `192.168.1.102`):** Ejecuta `jettra-node-02` (Seguidor / Secondary).
* **Servidor C (IP `192.168.1.103`):** Ejecuta `jettra-node-03` (Seguidor / Secondary).

##### 6.2.3.1 Configuración de Red y Puertos en Servidores Físicos

Asegúrese de que los siguientes puertos estén permitidos en los firewalls de cada servidor (`ufw` o `firewalld`):
* **Puerto TCP `60001`:** Servicio REST público y health-check.
* **Puerto TCP `50001`:** Canales Raft y sincronización de anillo `jettraGRPC`.

```bash
# En sistemas Ubuntu / Debian
sudo ufw allow 60001/tcp
sudo ufw allow 50001/tcp
sudo ufw reload
```

---

##### 6.2.3.2 Archivo `docker-compose.yml` para Servidor 1 (Máquina `192.168.1.101`)

```yaml
version: "3.8"

services:
  jettra-node-01:
    image: jettrastore:1.0
    container_name: jettra-node-01
    hostname: node-01
    restart: always
    network_mode: "host"    # Recomendado para máxima velocidad de red sin NAT
    environment:
      - JETTRA_NODE_ID=node-01
      - JETTRA_NODE_ROLE=PRIMARY
      - JETTRA_STORAGE_PATH=/jettra/data
      - JETTRA_REST_PORT=60001
      - JETTRA_GRPC_PORT=50001
      - JETTRA_ADMIN_USERNAME=admin
      - JETTRA_ADMIN_PASSWORD=admin-jettra
      # Peers apuntan a las direcciones IP reales de las máquinas secundarias
      - JETTRA_CLUSTER_PEERS=node-02:192.168.1.102:50001:SECONDARY,node-03:192.168.1.103:50001:SECONDARY
      - JAVA_OPTS=-Xms2g -Xmx8g -XX:+UseZGC -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders --enable-preview --enable-native-access=ALL-UNNAMED
    volumes:
      - /var/jettra/data:/jettra/data
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:60001/api/v1/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3
```

---

##### 6.2.3.3 Archivo `docker-compose.yml` para Servidor 2 (Máquina `192.168.1.102`)

```yaml
version: "3.8"

services:
  jettra-node-02:
    image: jettrastore:1.0
    container_name: jettra-node-02
    hostname: node-02
    restart: always
    network_mode: "host"
    environment:
      - JETTRA_NODE_ID=node-02
      - JETTRA_NODE_ROLE=SECONDARY
      - JETTRA_STORAGE_PATH=/jettra/data
      - JETTRA_REST_PORT=60001
      - JETTRA_GRPC_PORT=50001
      - JETTRA_ADMIN_USERNAME=admin
      - JETTRA_ADMIN_PASSWORD=admin-jettra
      # Peers apuntan a la máquina principal y al otro seguidor
      - JETTRA_CLUSTER_PEERS=node-01:192.168.1.101:50001:PRIMARY,node-03:192.168.1.103:50001:SECONDARY
      - JAVA_OPTS=-Xms2g -Xmx8g -XX:+UseZGC -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders --enable-preview --enable-native-access=ALL-UNNAMED
    volumes:
      - /var/jettra/data:/jettra/data
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:60001/api/v1/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3
```

---

##### 6.2.3.4 Archivo `docker-compose.yml` para Servidor 3 (Máquina `192.168.1.103`)

```yaml
version: "3.8"

services:
  jettra-node-03:
    image: jettrastore:1.0
    container_name: jettra-node-03
    hostname: node-03
    restart: always
    network_mode: "host"
    environment:
      - JETTRA_NODE_ID=node-03
      - JETTRA_NODE_ROLE=SECONDARY
      - JETTRA_STORAGE_PATH=/jettra/data
      - JETTRA_REST_PORT=60001
      - JETTRA_GRPC_PORT=50001
      - JETTRA_ADMIN_USERNAME=admin
      - JETTRA_ADMIN_PASSWORD=admin-jettra
      - JETTRA_CLUSTER_PEERS=node-01:192.168.1.101:50001:PRIMARY,node-02:192.168.1.102:50001:SECONDARY
      - JAVA_OPTS=-Xms2g -Xmx8g -XX:+UseZGC -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders --enable-preview --enable-native-access=ALL-UNNAMED
    volumes:
      - /var/jettra/data:/jettra/data
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:60001/api/v1/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3
```

> **Nota sobre `network_mode: "host"`:** El uso del modo de red host elimina el bridge virtual y el proxy NAT de Docker, permitiendo a Java 25 y Raft comunicarse directamente a través de las interfaces de red físicas del kernel Linux a velocidad nativa con latencia inferior a $100\,\mu	ext{s}$.

##### 6.2.3.5 Procedimiento de Despliegue y Arranque en Producción

1. **En Servidor 1 (`192.168.1.101`):**
   ```bash
   docker compose up -d
   docker compose logs -f
   ```
2. **En Servidor 2 (`192.168.1.102`):**
   ```bash
   docker compose up -d
   ```
3. **En Servidor 3 (`192.168.1.103`):**
   ```bash
   docker compose up -d
   ```
4. **Verificación de Quórum Distribuido:**
   Desde cualquier máquina de la red, verifique la salud y los pares registrados:
   ```bash
   curl -s http://192.168.1.101:60001/api/v1/health | jq .
   curl -s http://192.168.1.102:60001/api/v1/health | jq .
   curl -s http://192.168.1.103:60001/api/v1/health | jq .
   ```
   Las respuestas confirmarán el estado `UP`, el rol correspondiente (`PRIMARY` o `SECONDARY`) y la telemetría en tiempo real procesada por el clúster.


### 6.3 Despliegue Optimizado de Bajo Consumo RAM/CPU (1 Nodo y 3 Nodos Raft) y Publicación en Docker Hub

#### 1. Dockerfile Multi-Etapa de Bajo Consumo (`JettraStore/Dockerfile`)
- Imagen base: `bellsoft/liberica-runtime-container:jre-25-stream-musl` (Alpaquita Linux / musl) en build **multi-stage** (`packager` → `runtime`), eliminando cachés y artefactos intermedios.
- Flags JVM Java 25+ para bajo footprint:
  - `JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:+UseG1GC -XX:+UseStringDeduplication -XX:CICompilerCount=2 -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:MaxRAMPercentage=70.0 --enable-preview --enable-native-access=ALL-UNNAMED"`
  - `JAVA_OPTS="-Xms96m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=192m"`
- Persistencia permanente independiente por contenedor montada en `/jettra/data` y `/jettra/checkpoint`.

#### 2. Despliegue de 1 Nodo (`docker-compose-local-node.yml`)
```bash
# Desde la raíz del workspace (1 nodo JettraStore + Shell + Explorer)
docker compose -f docker-compose-local-node.yml up -d

# Solo JettraStore 1 nodo standalone
docker compose -f JettraStore/docker-compose-local-node.yml up -d
```

#### 3. Despliegue Multi-Nodo de 3 Nodos Raft (`docker-compose.yml`)
Cada nodo (`jettra-node-01`, `jettra-node-02`, `jettra-node-03`) cuenta con:
- Límite de recursos: `mem_limit: 640m`, `mem_reservation: 128m`, `cpus: 1.0`.
- Volumen dedicado independiente: `jettra_data_node01`, `jettra_data_node02`, `jettra_data_node03`.
- Topología de red interna `jettra-cluster-net` configurada mediante `docker/cluster-3nodes.config`.

```bash
# Levantar 3 nodos JettraStore + JettraStoreShell + JettraStoreExplorer
docker compose up -d

# Verificar salud, roles Raft (1 PRIMARY + 2 SECONDARY) y consumo real de RAM
docker compose ps
docker stats --no-stream jettra-node-01 jettra-node-02 jettra-node-03 jettrastore-explorer
curl -s http://localhost:60001/api/v1/cluster/status
```

#### 4. Verificación de Consenso Raft y Failover (`PRIMARY` → `SECONDARY`)
```bash
# 1. Detener el contenedor PRIMARY (node-01)
docker stop jettra-node-01

# 2. Confirmar que un SECONDARY (node-02 o node-03) asumió el rol PRIMARY sin pérdida de datos
curl -s http://localhost:60002/api/v1/cluster/status
curl -s http://localhost:60003/api/v1/cluster/status

# 3. Reiniciar node-01 y confirmar reincorporación limpia al quórum con su volumen persistente intacto
docker start jettra-node-01
```

#### 5. Construcción y Publicación de Imagen Limpia en Docker Hub (RF-18)
La imagen oficial de **JettraStore** se construye sin bases de datos de ejemplo (`sample_*`, `example_factura_db`) ni registros semilla precargados (`JETTRA_SAMPLES_AUTOLOAD=false`, `jettra.samples.autoload=false`), garantizando que al iniciar un contenedor únicamente existan las bases de datos creadas explícitamente por el usuario (o un catálogo vacío al inicio).

##### A. Construcción manual o multi-arquitectura con Docker Buildx
```bash
# 1. Empaquetar el artefacto JAR limpio con Maven
mvn clean package -DskipTests

# 2. Autenticarse en Docker Hub
docker login

# 3. Construir y publicar imagen multi-arquitectura limpia (amd64 y arm64)
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t jettra/jettrastore:1.0 \
  -t jettra/jettrastore:latest \
  -f Dockerfile . \
  --push
```

##### B. Script automatizado `docker/build-and-push.sh`
Para simplificar el empaquetado y publicación parametrizada:
```bash
# Construcción local de verificación (sin subir a Docker Hub)
./docker/build-and-push.sh

# Publicación directa en Docker Hub personal o corporativo
DOCKER_USER="mi-usuario" TAG_VERSION="1.0" PUSH="true" ./docker/build-and-push.sh
```

##### C. Variables de entorno relevantes para el contenedor
| Variable | Valor por Defecto | Descripción |
| :--- | :--- | :--- |
| `JETTRA_SAMPLES_AUTOLOAD` | `false` | Deshabilita la creación automática de bases de datos de ejemplo al iniciar el nodo. |
| `JETTRA_STORAGE_PATH` | `/jettra/data` | Directorio de almacenamiento persistente montado en volumen. |
| `JETTRA_STORAGE_MODE` | `OFF_HEAP` | Motor nativo de almacenamiento (`OFF_HEAP`, `JVM_RAM`, `DISK_MEMORY`). |
| `JETTRA_MULTINODE_ACTIVE` | `on` (`off` en standalone) | Activa o desactiva el motor distribuido de replicación Raft. |
| `JETTRA_NODE_ROLE` | `PRIMARY` | Rol del nodo dentro del clúster (`PRIMARY` o `SECONDARY`). |


### 6.4 Despliegue desde Docker Hub (`avbravo/jettrastore:1.0-SNAPSHOT`), Persistencia y Conflictos de Puerto

Esta sección describe cómo descargar, configurar y ejecutar **JettraStore** directamente utilizando la imagen oficial publicada en **Docker Hub**, tanto mediante `docker run` como con **Docker Compose** (`docker-compose-hub.yml`).

#### 1. Descarga de la Imagen

```bash
docker pull avbravo/jettrastore:1.0-SNAPSHOT
```

#### 2. Ejecución Directa con `docker run` (Autónoma con la Configuración Embebida)

La imagen `avbravo/jettrastore:1.0-SNAPSHOT` **ya incluye embebidos** en `/app/config/microprofile-config.properties` y `/app/config/cluster.config` dentro del contenedor, por lo que puedes ejecutarla en cualquier equipo sin necesitar los archivos del repositorio fuente. El contenedor expone los puertos **`50001`** (transporte binario Raft/gRPC) y **`60001`** (API REST y healthcheck `/api/v1/health`) bajo el usuario **`1000:1000`**:

```bash
docker run -d \
  --name jettrastore \
  --hostname jettra-node-01 \
  -p 50001:50001 \
  -p 60001:60001 \
  -e JETTRA_NODE_ID=node-01 \
  -e JETTRA_NODE_ROLE=PRIMARY \
  -e JETTRA_NODE_IP=jettra-node-01 \
  -e JETTRA_STORAGE_PATH=/jettra/data \
  -e JETTRA_STORAGE_MODE=OFF_HEAP \
  -e JETTRA_MULTINODE_ACTIVE=off \
  -e JETTRA_CLUSTER_CONFIG_PATH=/app/config/cluster.config \
  -e JETTRA_GRPC_PORT=50001 \
  -e JETTRA_REST_PORT=60001 \
  -e JETTRA_ADMIN_USERNAME=admin \
  -e JETTRA_ADMIN_PASSWORD=admin-jettra \
  -e JETTRA_SAMPLES_AUTOLOAD=false \
  -v jettrastore_data:/jettra/data \
  -v jettrastore_checkpoint:/jettra/checkpoint \
  avbravo/jettrastore:1.0-SNAPSHOT
```

> [!NOTE]
> **Consideraciones clave de configuración y portabilidad:**
> * **Sin dependencias de archivos locales del repositorio:** `microprofile-config.properties` y `cluster.config` ya vienen empaquetados dentro de la imagen en `/app/config/`. Toda la parametrización del nodo (ID, rol, puertos, almacenamiento, modo multinodo, credenciales y flags JVM) se realiza mediante variables de entorno en `docker run` o `docker-compose-hub.yml`, permitiendo desplegar en cualquier servidor copiando únicamente el archivo YAML.
> * **Sobrescritura opcional de archivos:** Si en un despliegue avanzado deseas reemplazar `/app/config/microprofile-config.properties` o `/app/config/cluster.config` con archivos propios del host, verifica primero que **existan como archivos regulares** antes de ejecutar Docker; si montas una ruta inexistente con `-v`, Docker creará un directorio vacío en el host y lanzará `not a directory: Are you trying to mount a directory onto a file`.
> * **Precedencia de propiedades (`JettraStoreConfig`):** Las propiedades `-D` del `ENTRYPOINT` (`JETTRA_STORAGE_PATH`, `JETTRA_STORAGE_MODE`, `JETTRA_MULTINODE_ACTIVE`, `JETTRA_SAMPLES_AUTOLOAD`) y las variables `JETTRA_*` / `JAVA_OPTS` tienen prioridad sobre `microprofile-config.properties`. Para ejecutar 1 nodo autónomo, mantén `JETTRA_MULTINODE_ACTIVE=off`.
> * **Permisos de volúmenes:** Al usar volúmenes nombrados (`jettrastore_data:/jettra/data`), Docker gestiona los permisos automáticamente. Si utilizas un directorio local del host (*bind mount*), asigna propiedad al UID `1000:1000` (`sudo chown -R 1000:1000 /ruta/local/data`).

#### 3. Despliegue Orquestado y Portable con `docker-compose-hub.yml`

El archivo `JettraStore/docker-compose-hub.yml` es totalmente autónomo: descarga la imagen desde Docker Hub, configura el nodo por variables de entorno usando la configuración interna de la imagen y monta volúmenes persistentes para datos, índices y checkpoints sin depender de archivos externos en el host:

```yaml
# ==============================================================================
# JettraStore - Docker Compose desde Docker Hub (avbravo/jettrastore:1.0-SNAPSHOT)
# Autónomo y portable: funciona en cualquier equipo sin requerir archivos locales
# del repositorio. Garantiza persistencia permanente en disco mediante volúmenes
# independientes del ciclo de vida del contenedor y de la imagen Docker.
#
# Mapeo flexible de puertos para evitar colisiones en el host y habilitar
# múltiples nodos/instancias:
#   - JETTRA_HOST_GRPC_PORT (fallback JETTRA_GRPC_PORT, default 50001): puerto del host
#   - JETTRA_CONTAINER_GRPC_PORT (default 50001): puerto gRPC/Raft interno del contenedor
#   - JETTRA_HOST_REST_PORT (fallback JETTRA_REST_PORT, default 60001): puerto del host
#   - JETTRA_CONTAINER_REST_PORT (default 60001): puerto REST/Health interno del contenedor
# ==============================================================================
services:
  jettrastore:
    image: ${JETTRA_DOCKER_IMAGE:-avbravo/jettrastore:1.0-SNAPSHOT}
    pull_policy: ${JETTRA_PULL_POLICY:-always}
    container_name: ${JETTRA_CONTAINER_NAME:-jettrastore}
    hostname: ${JETTRA_NODE_IP:-jettra-node-01}
    restart: unless-stopped
    stop_grace_period: 30s
    ports:
      - "${JETTRA_HOST_BIND:-0.0.0.0}:${JETTRA_HOST_GRPC_PORT:-${JETTRA_GRPC_PORT:-50001}}:${JETTRA_CONTAINER_GRPC_PORT:-50001}"
      - "${JETTRA_HOST_BIND:-0.0.0.0}:${JETTRA_HOST_REST_PORT:-${JETTRA_REST_PORT:-60001}}:${JETTRA_CONTAINER_REST_PORT:-60001}"
    environment:
      - JETTRA_NODE_ID=${JETTRA_NODE_ID:-node-01}
      - JETTRA_NODE_ROLE=${JETTRA_NODE_ROLE:-PRIMARY}
      - JETTRA_NODE_IP=${JETTRA_NODE_IP:-jettra-node-01}
      - JETTRA_STORAGE_PATH=/jettra/data
      - JETTRA_INDEX_STORAGE_PATH=/jettra/data/indexes
      - JETTRA_STORAGE_MODE=${JETTRA_STORAGE_MODE:-OFF_HEAP}
      - JETTRA_MULTINODE_ACTIVE=${JETTRA_MULTINODE_ACTIVE:-off}
      - JETTRA_CLUSTER_MULTINODE_ACTIVE=${JETTRA_MULTINODE_ACTIVE:-off}
      - JETTRA_CLUSTER_PEERS=${JETTRA_CLUSTER_PEERS:-}
      - JETTRA_CLUSTER_CONFIG_PATH=/app/config/cluster.config
      - JETTRA_GRPC_PORT=${JETTRA_CONTAINER_GRPC_PORT:-50001}
      - JETTRA_REST_PORT=${JETTRA_CONTAINER_REST_PORT:-60001}
      - JETTRA_ADMIN_USERNAME=${JETTRA_ADMIN_USERNAME:-admin}
      - JETTRA_ADMIN_PASSWORD=${JETTRA_ADMIN_PASSWORD:-admin-jettra}
      - JETTRA_SAMPLES_AUTOLOAD=${JETTRA_SAMPLES_AUTOLOAD:-false}
      - JAVA_OPTS=${JAVA_OPTS:--Xms96m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=192m}
    mem_limit: 640m
    mem_reservation: 128m
    cpus: 1.0
    volumes:
      # Persistencia permanente en disco (sobrevive a `docker compose down` y `docker rmi`):
      # Por defecto usa el volumen nombrado Docker; puede apuntar a una carpeta física del host
      # exportando JETTRA_DATA_VOLUME=/ruta/en/disco (asegurando permisos UID 1000:1000).
      - ${JETTRA_DATA_VOLUME:-jettrastore_hub_data}:/jettra/data
      - ${JETTRA_CHECKPOINT_VOLUME:-jettrastore_hub_checkpoint}:/jettra/checkpoint
    networks:
      - jettra-hub-net
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:${JETTRA_CONTAINER_REST_PORT:-60001}/api/v1/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3
      start_period: 8s

volumes:
  jettrastore_hub_data:
    name: ${JETTRA_DATA_VOLUME_NAME:-jettrastore_hub_data}
    driver: local
  jettrastore_hub_checkpoint:
    name: ${JETTRA_CHECKPOINT_VOLUME_NAME:-jettrastore_hub_checkpoint}
    driver: local

networks:
  jettra-hub-net:
    name: ${JETTRA_NETWORK_NAME:-jettra-hub-net}
    driver: bridge
```

#### 4. Persistencia Permanente en Disco y Ciclo de Vida de Volúmenes

**Sí, el almacenamiento de `JettraStore` es permanente en disco.** Aunque `JETTRA_STORAGE_MODE=OFF_HEAP` utilice memoria fuera del heap JVM para acelerar lecturas y escrituras, `JettraDatabase` y `JettraStoreServer` persisten los metadatos, registros, índices, secuencias y bitácoras de transacciones directamente en disco bajo `JETTRA_STORAGE_PATH` (`/jettra/data`) y `JETTRA_INDEX_STORAGE_PATH` (`/jettra/data/indexes`), generando artefactos como `<db>_meta.json`, `<db>_sstable.jettra`, `<db>_wal.jettra`, `<db>_indexes.json`, `<db>_sequences.json`, `<db>_versions.json`, `jettra_memory/` y páginas `_offheap.pages`.

Además, `docker-compose-hub.yml` define `stop_grace_period: 30s` para dar tiempo suficiente al *shutdown hook* de `JettraStoreServer` (`server.stop()`) a volcar y cerrar limpiamente todas las bases de datos al detener el contenedor.

##### ¿Por qué los datos sobreviven aunque borres el contenedor o la imagen Docker?
En Docker, el almacenamiento montado mediante **volúmenes** reside en el sistema de archivos del host, completamente desacoplado de las capas de solo lectura de la imagen y de la capa efímera del contenedor:
* Si ejecutas `docker compose -f docker-compose-hub.yml down` o `docker rm -f jettrastore`, **solo se destruye el contenedor**.
* Si ejecutas `docker rmi avbravo/jettrastore:1.0-SNAPSHOT`, **solo se elimina la imagen local**.
* Al descargar una nueva versión (`docker compose -f docker-compose-hub.yml pull`) y levantar el servicio (`up -d`), Docker vuelve a montar exactamente el mismo directorio del disco en `/jettra/data` y `JettraStoreServer` recupera automáticamente las bases de datos existentes.

##### Modalidades de almacenamiento disponibles en `docker-compose-hub.yml`

* **Opción A — Volumen nombrado Docker (`jettrastore_hub_data`, por defecto):**
  Docker almacena físicamente los datos en el disco del host (en Linux típicamente bajo `/var/lib/docker/volumes/jettrastore_hub_data/_data`) y administra automáticamente los permisos del usuario `1000:1000` de la imagen:
  ```bash
  docker compose -f docker-compose-hub.yml up -d
  # Inspeccionar la ubicación real en disco del volumen nombrado:
  docker volume inspect jettrastore_hub_data
  ```
* **Opción B — Carpeta física explícita en el disco del host (*Bind Mount*):**
  Si deseas guardar los archivos en una ruta específica de un disco o partición (por ejemplo `/srv/jettrastore/data`), crea las carpetas en el host, asigna propiedad al UID/GID `1000:1000` (definido en `USER 1000:1000` del `Dockerfile`) y define las variables `JETTRA_DATA_VOLUME` y `JETTRA_CHECKPOINT_VOLUME`:
  ```bash
  sudo mkdir -p /srv/jettrastore/data /srv/jettrastore/checkpoint
  sudo chown -R 1000:1000 /srv/jettrastore

  JETTRA_DATA_VOLUME=/srv/jettrastore/data \
  JETTRA_CHECKPOINT_VOLUME=/srv/jettrastore/checkpoint \
  docker compose -f docker-compose-hub.yml up -d
  ```





> [!CAUTION]
> **Cuándo sí se borran los datos:** Con un volumen nombrado, los datos únicamente se eliminan si ejecutas explícitamente `docker compose -f docker-compose-hub.yml down -v` (la bandera `-v` borra los volúmenes declarados) o `docker volume rm jettrastore_hub_data`. En la **Opción B** (*bind mount*), ni siquiera `down -v` ni `docker rmi` eliminan la carpeta `/srv/jettrastore/data` del host.

#### 5. Ciclo de Ejecución y Verificación con Docker Compose

Después de descargar la imagen con `pull`, inicia y verifica el servicio con los siguientes comandos:

```bash
# 1. Descargar la imagen desde Docker Hub
docker compose -f docker-compose-hub.yml pull

# 2. Iniciar el contenedor en segundo plano (usando la imagen descargada)
docker compose -f docker-compose-hub.yml up -d
# (Opcional: si ya ejecutaste pull y no deseas consultar Docker Hub de nuevo, añade --pull never)
# docker compose -f docker-compose-hub.yml up -d --pull never

# 3. Verificar estado del contenedor y healthcheck
docker compose -f docker-compose-hub.yml ps
curl -fsS http://localhost:60001/api/v1/health

# 4. Consultar logs en tiempo real
docker compose -f docker-compose-hub.yml logs -f jettrastore
```

##### Operación, Actualización de Imagen y Limpieza
```bash
# Detener el servicio conservando los datos en disco/volumen
docker compose -f docker-compose-hub.yml down

# Demostración de actualización / recreación tras eliminar la imagen (los datos permanecen intactos):
docker compose -f docker-compose-hub.yml down
docker rmi avbravo/jettrastore:1.0-SNAPSHOT
docker compose -f docker-compose-hub.yml pull
docker compose -f docker-compose-hub.yml up -d

# Detener el servicio y eliminar también los volúmenes nombrados (DESTRUYE los datos almacenados)
docker compose -f docker-compose-hub.yml down -v
```

#### 6. Diagnóstico y Resolución de Conflictos de Puerto (`50001/tcp` y `60001/tcp`) en Ubuntu 26.04

Si al ejecutar `docker compose -f docker-compose-hub.yml up -d` aparece el error:

```text
Error response from daemon: failed to set up container networking: driver failed programming external connectivity on endpoint jettrastore (...): failed to bind host port 0.0.0.0:50001/tcp: address already in use
```

indica que otro proceso en el host (frecuentemente una instancia local de `JettraStore-1.0-SNAPSHOT-uber.jar` en Java, otro contenedor Docker o una pila previa de `docker compose`) ya está enlazado al puerto `50001` (o `60001`). Además, el fallo parcial puede dejar el contenedor `jettrastore` en estado `Created`.

##### Paso 1: Diagnosticar qué proceso o contenedor ocupa el puerto
```bash
# Ver sockets TCP escuchando en 50001 / 60001 y el PID/programa asociado
ss -tlnp | grep -E "50001|60001"

# Identificar procesos Java de JettraStore activos en el host
ps aux | grep -i jettrastore

# Revisar contenedores activos, detenidos o huérfanos en estado Created
docker ps -a --format "table {{.ID}}\t{{.Names}}\t{{.Status}}\t{{.Ports}}"
```

##### Paso 2A: Liberar el puerto `50001` y limpiar el contenedor huérfano
Si deseas utilizar los puertos estándar `50001`/`60001` con Docker:
```bash
# 1. Detener la instancia Java local (sustituye <PID> por el PID reportado por ss/ps)
kill <PID>
# (Si corresponde a una pila previa de docker-compose.yml:)
# docker compose down --remove-orphans

# 2. Limpiar cualquier contenedor previo/huérfano llamado 'jettrastore'
docker compose -f docker-compose-hub.yml down --remove-orphans
docker rm -f jettrastore 2>/dev/null || true

# 3. Levantar nuevamente el contenedor de forma limpia
docker compose -f docker-compose-hub.yml up -d --pull never
```

##### Paso 2B: Remapear puertos del host sin detener el proceso existente
`docker-compose-hub.yml` desacopla el puerto expuesto en el **host** (`JETTRA_HOST_GRPC_PORT` / `JETTRA_HOST_REST_PORT`) del puerto **interno del contenedor** (`JETTRA_CONTAINER_GRPC_PORT` / `JETTRA_CONTAINER_REST_PORT`):

| Variable | Valor por defecto | Función |
| :--- | :--- | :--- |
| `JETTRA_HOST_GRPC_PORT` | `50001` (o `JETTRA_GRPC_PORT`) | Puerto publicado en la interfaz del host para clientes externos / Driver. |
| `JETTRA_CONTAINER_GRPC_PORT` | `50001` | Puerto TCP/Raft interno donde escucha `JettraStoreServer` dentro del contenedor. |
| `JETTRA_HOST_REST_PORT` | `60001` (o `JETTRA_REST_PORT`) | Puerto HTTP REST publicado en el host. |
| `JETTRA_CONTAINER_REST_PORT` | `60001` | Puerto REST interno usado por `/api/v1/health` y el `HEALTHCHECK`. |
| `JETTRA_HOST_BIND` | `0.0.0.0` | Interfaz del host donde se expone el puerto (p. ej. `127.0.0.1` para acceso solo local). |

Ejemplo ejecutando el contenedor en puertos alternativos del host (`50011` / `60011`) sin colisionar con la instancia local en `50001`:
```bash
JETTRA_CONTAINER_NAME=jettrastore-hub-01 \
JETTRA_HOST_GRPC_PORT=50011 \
JETTRA_HOST_REST_PORT=60011 \
docker compose -p jettrastore-hub-01 -f docker-compose-hub.yml up -d --pull never

curl -fsS http://localhost:60011/api/v1/health
```

##### Paso 3: Despliegue Multi-Nodo sin Colisiones con `docker-compose-hub.yml`
Puedes levantar múltiples nodos en la misma red `jettra-hub-net` asignando a cada instancia un proyecto (`-p`), nombre de contenedor, volumen (`JETTRA_DATA_VOLUME_NAME`) y puertos distintos:
```bash
# Nodo 1 (PRIMARY) -> host 50011/60011, interno 50001/60001
JETTRA_CONTAINER_NAME=jettra-hub-01 \
JETTRA_NODE_ID=node-01 JETTRA_NODE_ROLE=PRIMARY JETTRA_NODE_IP=jettra-hub-01 \
JETTRA_MULTINODE_ACTIVE=on \
JETTRA_CLUSTER_PEERS=node-02:jettra-hub-02:50002:SECONDARY \
JETTRA_HOST_GRPC_PORT=50011 JETTRA_CONTAINER_GRPC_PORT=50001 \
JETTRA_HOST_REST_PORT=60011 JETTRA_CONTAINER_REST_PORT=60001 \
JETTRA_DATA_VOLUME_NAME=jettrastore_hub_data_01 \
JETTRA_CHECKPOINT_VOLUME_NAME=jettrastore_hub_ckpt_01 \
docker compose -p jettra-hub-01 -f docker-compose-hub.yml up -d --pull never

# Nodo 2 (SECONDARY) -> host 50012/60012, interno 50002/60002
JETTRA_CONTAINER_NAME=jettra-hub-02 \
JETTRA_NODE_ID=node-02 JETTRA_NODE_ROLE=SECONDARY JETTRA_NODE_IP=jettra-hub-02 \
JETTRA_MULTINODE_ACTIVE=on \
JETTRA_CLUSTER_PEERS=node-01:jettra-hub-01:50001:PRIMARY \
JETTRA_HOST_GRPC_PORT=50012 JETTRA_CONTAINER_GRPC_PORT=50002 \
JETTRA_HOST_REST_PORT=60012 JETTRA_CONTAINER_REST_PORT=60002 \
JETTRA_DATA_VOLUME_NAME=jettrastore_hub_data_02 \
JETTRA_CHECKPOINT_VOLUME_NAME=jettrastore_hub_ckpt_02 \
docker compose -p jettra-hub-02 -f docker-compose-hub.yml up -d --pull never
```

##### Nota sobre puertos efímeros en Ubuntu 26.04 (`ip_local_port_range`)
En Ubuntu 26.04 el rango predeterminado de puertos efímeros del kernel es `32768–60999` (`/proc/sys/net/ipv4/ip_local_port_range`), por lo que el puerto `50001` o `60001` puede coincidir con conexiones salientes transitorias, contenedores previos en estado `Created` (p. ej. tras un arranque abortado) o procesos Java locales de `JettraStore` ejecutándose en segundo plano. Verificar siempre:

```bash
ss -tlnp | grep -E "50001|60001"
docker ps -a --format "table {{.ID}}\t{{.Names}}\t{{.Status}}\t{{.Ports}}"
cat /proc/sys/net/ipv4/ip_local_port_range
```


### 6.5 Gestión Permanente del Estado de Membresía (`jettra.cluster_state_config_dir` y `cluster-state.config`) — RF-32

Para garantizar que altas dinámicas de nodos, paradas (`STOPPED`), arranques (`RUNNING`) y remociones sobrevivan reinicios completos tanto con `java -jar` como en contenedores Docker sin sobrescribir configuraciones de despliegue, **JettraStore** separa estrictamente:

| Archivo / Propiedad | Naturaleza | Comportamiento en tiempo de ejecución |
| :--- | :--- | :--- |
| `jettra.cluster_state_config_dir` (env `JETTRA_CLUSTER_STATE_CONFIG_DIR`) | Directorio de estado persistente | Resuelve `~` / variables (`$HOME`, `${VAR}`). Si no existe, se crea automáticamente en el arranque (`Files.createDirectories`) y se validan permisos de lectura y escritura. Valor por defecto: `~/jettra-cluster-state/` (`java -jar`) o `/jettra/cluster-state` (Docker). |
| `<jettra.cluster_state_config_dir>/cluster-state.config` | Estado vivo del clúster | Fuente autoritativa de membresía (`PRIMARY`/`SECONDARY`, IPs, `grpc.port`, `rest.port`, `status` = `RUNNING`/`STOPPED`/`STANDBY` y `cluster.state.version`). Se persiste de forma atómica (`.tmp` + `fsync` mediante `FileChannel.force(true)` + `ATOMIC_MOVE`). |
| `cluster.config` (opcional) | Semilla estática read-only | Si `cluster-state.config` no existe y `cluster.config` está presente, siembra `cluster-state.config` una sola vez sin modificarse jamás. Si `cluster-state.config` ya existe, prevalece siempre sobre `cluster.config`. |

#### Arranque en Modo `STANDBY` (`UNASSIGNED`)
Si un nodo inicia sin `cluster-state.config` ni `cluster.config` (y sin pares configurados en `cluster.multinode.active=on`), **no fabrica nodos ficticios**: entra en modo **`STANDBY`**, expone `node_state="STANDBY"` y `role="UNASSIGNED"` en `/api/v1/health`, y mantiene su transporte Raft escuchando para ser incorporado desde `JettraStoreDriver`, `JettraStoreShell` o `JettraStoreExplorer`, momento en el que recibe y persiste `cluster-state.config` replicado desde el `PRIMARY`.

#### Replicación y Reconciliación Automática
* Toda mutación de membresía (`ADD NODE`, `STOP NODE`, `START NODE`, `REMOVE NODE`) actualiza primero `cluster-state.config` en el `PRIMARY` y replica los bytes canónicos a cada `SECONDARY` activo (`TYPE_SYNC_CLUSTER_CONFIG`).
* Si el clúster completo se detiene y se vuelve a iniciar, cada nodo recarga su `<jettra.cluster_state_config_dir>/cluster-state.config` conservando la última topología y el estado real de cada miembro.

#### Ejemplos Prácticos (`java -jar` y Docker Compose)

```properties
# microprofile-config.properties del nodo
cluster.node.id=node-01
cluster.node.role=PRIMARY
jettra.storage.path=~/jettra-storage-01/data
jettra.cluster_state_config_dir=~/jettra-storage-01/statedir
cluster.multinode.active=on
```

```bash
# Ejecución bare-metal con semilla inicial opcional en solo lectura
java -jar target/JettraStore-1.0-SNAPSHOT.jar \
  -Djettra.cluster_state_config_dir=$HOME/jettra-storage-01/statedir \
  -Dcluster.config.path=$HOME/jettra/config/cluster.config
```

```yaml
# Fragmento en docker-compose.yml / docker-compose-hub.yml (RF-32)
services:
  jettrastore:
    image: avbravo/jettrastore:1.0-SNAPSHOT
    environment:
      - JETTRA_CLUSTER_STATE_CONFIG_DIR=/jettra/cluster-state
      - JETTRA_STORAGE_PATH=/jettra/data
    volumes:
      - jettrastore_hub_data:/jettra/data
      - jettrastore_hub_state:/jettra/cluster-state
```

---

## 7. Integración con Clientes, Herramientas, Migrador y Pruebas (`JettraTest`)

### 7.1 Cliente `JettraStoreDriver` y Proyecto de Referencia `JettraStoreExampleSE`

El ecosistema incluye el módulo **`JettraStoreExampleSE`** (`JettraStoreExampleSE/guide/book.md`) como guía ejecutable en Java 25+ que integra `JettraStoreDriver`, `JettraRules`, modelos inmutables Java `record`, el Patrón Repository, la base `examplesedb` (buckets `DEFINED` y `NONE`), índices `NUMERIC` y `AUTO_INCREMENT`, y pruebas automatizadas de resiliencia ante parada, reinicio y remoción de nodos con `JettraTest`.


### 7.2 Consola Interactiva `JettraStoreShell` y Verificación Real de Nodos (`show nodes`)

**JettraStoreShell** proporciona una consola interactiva con soporte de historial, autocompletado, administración de conexiones y operación completa sobre bases de datos, buckets, índices, seguridad y clúster Raft.

#### 7.2.1 Verificación Real de Salud e Identidad en `show nodes` (Sin Falsos Positivos)
El comando `show nodes` (en coordinación con `JettraClient.probeJettraNode` en **JettraStoreDriver**) verifica en tiempo real tanto la salud como la identidad del nodo antes de marcarlo como activo:

1. Descarta direcciones comodín inválidas (`0.0.0.0`, `::`, `*`) para sondeos de cliente.
2. Consulta el endpoint REST `/api/v1/health` esperando código `HTTP 200` con JSON válido de JettraStore (`"status":"UP"` y campo `"node_id"`) coincidente con el nodo configurado.
3. Si REST no está expuesto, ejecuta un *handshake* binario Raft (`TYPE_HEARTBEAT` → `TYPE_HEARTBEAT_ACK`) sobre el puerto gRPC.
4. Si ningún nodo responde o si el puerto está ocupado por otro servicio distinto a JettraStore, el nodo se reporta de manera coherente como **Estado `STOPPED`**, **Estado Raft `DISCONNECTED`**, **Rol `UNKNOWN`** y **Offload Bytes `N/A`** (nunca como `RUNNING`, `LEADER` o `FOLLOWER`).
5. Cuando existen `0` nodos activos, `JettraStoreShell` bloquea de forma consistente los comandos operativos de base de datos manteniendo activos los comandos locales y de diagnóstico (`help`, `status`, `show nodes`, `connect`, `start node`, `exit`).


### 7.3 Consola Web `JettraStoreExplorer` y Migrador PostgreSQL a JettraStore (RF-17)

El ecosistema JettraStore integra capacidades de migración fluida desde motores relacionales **PostgreSQL**, permitiendo a las organizaciones modernizar sus esquemas hacia la arquitectura multimodelo de **JettraStore** (Buckets `DEFINED` o `NONE` con índices `BTREE`, `NUMERIC` y `AUTO_INCREMENT`) y generar automáticamente una arquitectura backend limpia en **Java 25+** con `JettraServer`, `JettraRules`, `JettraJWT` y `JettraStoreDriver`.

#### 1. Disponibilidad en Herramientas
- **Web UI (`JettraStoreExplorer`):** Ruta interactiva `/migrator` con formulario de conexión, inspección detallada de tablas/columnas, métricas en vivo, terminal de logs y descarga directa del `.zip` del proyecto backend.
- **CLI (`JettraStoreShell`):** Comando interactivo `migrate` con banderas completas (`--host`, `--port`, `--db`, `--user`, `--password`, `--schema`, `--target-db`, `--target-engine`, `--package`, `--generate-backend`, `--output-zip`, `--dry-run`, `--ssl`).

#### 2. Preservación Crítica de Tipos
- **Decimales de Alta Precisión:** Las columnas `NUMERIC` y `DECIMAL` se leen y transfieren mediante `BigDecimal` sin conversión a tipos de punto flotante primitivos, preservando escala y precisión absoluta.
- **Fechas y Marcas de Tiempo:**
  - `TIMESTAMPTZ` / `TIMESTAMP` se representan en formato estándar ISO-8601 (`YYYY-MM-DDTHH:mm:ss.SSSSSSZ`).
  - `DATE` se conserva como `LocalDate` (`YYYY-MM-DD`).
- **Identificadores `JettraId`:** Cada registro transferido conserva o recibe un `JettraId` canónico hexadecimal de 32 caracteres vinculado al contexto de la base de datos y Bucket destino.


### 7.4 Pruebas Automatizadas Exclusivas con `JettraTest`

Todo el ecosistema (`JettraStore`, `JettraStoreDriver`, `JettraStoreShell`, `JettraStoreExplorer`, `JettraStoreMeter`) utiliza **exclusivamente `JettraTest`** (`io.jettra.test.*`) como framework nativo de pruebas automatizadas en **Java 25+**, sin dependencias de Helidon ni de JUnit.

#### 7.4.1 Anotaciones y Ejecución en Maven
- **Anotaciones principales:** `@Test`, `@BeforeAll`, `@AfterAll`, `@BeforeEach`, `@AfterEach`, `@DisplayName` y aserciones mediante `JettraAssert`.
- **Ejecución aislada:** Cada módulo invoca `io.jettra.test.JettraTestRunner` mediante `exec-maven-plugin` (`mvn test` / `mvn install`), manteniendo `<skipTests>true</skipTests>` en `maven-surefire-plugin` para evitar ejecuciones duplicadas.

```bash
# Verificar módulo servidor JettraStore (Java 25+ con Panama FFM)
cd JettraStore && mvn test

# Instalar Driver y ejecutar pruebas de JettraStoreShell (incluyendo show nodes y validación de nodos activos)
cd ../JettraStoreDriver && mvn install
cd ../JettraStoreShell && mvn test
```


