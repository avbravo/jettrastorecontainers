# JettraStoreDriver: Manual de Referencia y Arquitectura del Conector Java 25+ (`book.md`)

**Conector Cliente de Ultra-Baja Latencia con Virtual Threads, jettra collection, Seguridad JettraJWT, Backup/Restore y Soporte Multimodelo**
*Versión de Plataforma: Java 25 LTS / Jettra Driver 1.0*

---

## 1. Visión General del Conector `JettraStoreDriver`

**`JettraStoreDriver`** es el conector cliente oficial de alta fidelidad desarrollado para interactuar con clústeres distribuidos `JettraStore`. A diferencia de los drivers de bases de datos convencionales basados en pools de hilos pesados del sistema operativo y serialización genérica en el montículo, `JettraStoreDriver` aprovecha al máximo:
* **Java 25 Virtual Threads:** Cada consulta asíncrona o flujo reactivo se ejecuta sobre Virtual Threads ligeros, eliminando la sobrecarga de cambio de contexto de la CPU.
* **Integración con `jettra collection`:** Cero uso de tipos de envoltura (`Long`, `Integer`, `Double`). Los conjuntos de resultados operan sobre arrays primitivos contiguos y buffers directos, eliminando la presión de recolección de basura (*GC pressure*).
* **Transporte Nativo `jettraGRPC`:** Multiplexación de conexiones binarias sobre HTTP/2 y sockets no bloqueantes.
* **Autenticación Obligatoria con `JettraJWT`:** Negociación y renovación transparente de tokens criptográficos.

---

## 2. Configuración y Conexión Segura

### 2.1 Dependencia Maven
```xml
<dependency>
    <groupId>io.jettra</groupId>
    <artifactId>jettrastoredriver</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 2.2 Inicialización del Cliente con `JettraClientConfig`
```java
package io.jettra.driver.example;

import io.jettra.driver.JettraClient;
import io.jettra.driver.config.JettraClientConfig;
import java.time.Duration;

public class ConnectionExample {
    public static void main(String[] args) {
        JettraClientConfig config = JettraClientConfig.builder()
            // Clúster de 3 nodos para failover automático
            .addClusterNode("192.168.1.101", 9091)
            .addClusterNode("192.168.1.102", 9091)
            .addClusterNode("192.168.1.103", 9091)
            // Credenciales administrativas por defecto
            .credentials("admin", "admin-jettra")
            .connectionTimeout(Duration.ofMillis(500))
            .enableVirtualThreads(true)
            .build();

        try (JettraClient client = JettraClient.connect(config)) {
            System.out.println("Conectado exitosamente. Token JettraJWT activo.");
        }
    }
}
```

### 2.3 Soporte para Topología Multinodo (`cluster.multinode.active`)

`JettraStoreDriver` incluye soporte nativo para la propiedad de configuración `cluster.multinode.active` introducida en `database.properties`, permitiendo gobernar el comportamiento de replicación y distribución tanto programáticamente como mediante archivos de configuración:

* **Modo Distribuido con Consenso (`cluster.multinode.active = on`):**
  Activa el reconocimiento del anillo dinámico y consenso distribuido integrado en JettraStore. El driver registra y coordina la presencia de los nodos réplica (`node-02`, `node-03`, etc.), permitiendo la delegación y particionamiento de datos según las políticas de saturación de memoria.
* **Modo Standalone / Servidor Único (`cluster.multinode.active = off`):**
  Desactiva el comportamiento de distribución. Todas las operaciones enviadas a través de `JettraClient` se concentran y resuelven exclusivamente en el servidor local sin distribuir datos ni coordinar con nodos secundarios.

#### Ejemplo de Configuración con `JettraClientConfig`:
```java
// Configuración explícita para modo Standalone (servidor único sin distribución)
JettraClientConfig standaloneConfig = JettraClientConfig.builder()
    .addClusterNode("127.0.0.1", 9091)
    .credentials("admin", "admin-jettra")
    .clusterMultinodeActive("off") // o .clusterMultinodeActive(false)
    .build();

try (JettraClient client = JettraClient.connect(standaloneConfig)) {
    System.out.println("Modo multinodo activo: " + client.isClusterMultinodeActive()); // false
    System.out.println("Estado multinodo: " + client.getClusterMultinodeActive());     // "off"
}
```

Al utilizar `JettraClient.connect(host, port, user, pass)`, el conector consulta automáticamente la propiedad `cluster.multinode.active` definida en `database.properties` para sincronizar su comportamiento de manera transparente.

---

## 3. Optimización con `jettra collection` (Zero-Boxing)

Al recuperar conjuntos de datos numéricos o agregaciones analíticas, el driver utiliza las colecciones nativas de alto rendimiento:

```java
import io.jettra.collection.primitive.JettraLongLongHashMap;
import io.jettra.collection.primitive.JettraDoubleArrayList;

// Lectura de millones de métricas sin instanciar objetos Double ni Long
JettraDoubleArrayList prices = client.getDatabase("analytics")
    .getColumnarEngine("market_data")
    .selectDoubleColumn("closing_price");

double sum = 0.0;
for (int i = 0; i < prices.size(); i++) {
    sum += prices.get(i); // Acceso directo por índice primitivo, cero unboxing
}
```

---

## 4. Consultas con JettraSQL y JettraQueryLanguage (LQL)

### 4.1 Ejecución de JettraSQL con Agrupaciones (`GROUP BY`) y Ordenación (`ORDER BY`)
```java
String sql = """
    SELECT department, COUNT(*) AS total_employees, AVG(salary) AS avg_salary
    FROM employees
    WHERE active = true
    GROUP BY department
    HAVING avg_salary > 65000.00
    ORDER BY avg_salary DESC
    """;

JettraResultSet rs = client.sql(sql).execute();
while (rs.next()) {
    System.out.printf("Dept: %s | Total: %d | Avg: %.2f%n",
        rs.getString("department"), rs.getLong("total_employees"), rs.getDouble("avg_salary"));
}
```

### 4.2 Fluent LQL con Índices Secundarios Dispersos
```java
JettraResults<Order> orders = client.from("orders", Order.class)
    .useIndex("idx_customer_sparse") // Fuerza el uso del sparse index en .jettra
    .filter(o -> o.customerId() == 5421L)
    .fetchMode(FetchMode.LAZY)
    .execute();
```

---

## 5. APIs Programáticas de Backup y Restore

El driver expone métodos de primer nivel para automatizar copias de seguridad y recuperaciones desde código Java:

```java
package io.jettra.driver.example;

import io.jettra.driver.JettraClient;
import io.jettra.driver.admin.BackupOptions;
import io.jettra.driver.admin.BackupResult;
import io.jettra.driver.admin.RestoreResult;

public class BackupRestoreExample {
    public static void main(String[] args) {
        try (JettraClient client = JettraClient.connect("localhost", 9091, "admin", "admin-jettra")) {
            
            // 1. Disparar Backup en Caliente (Hot Backup)
            BackupOptions options = BackupOptions.builder()
                .targetDirectory("/backup/daily_snapshots")
                .compress(true)
                .includeSecondaryIndexes(true)
                .build();

            BackupResult backupRes = client.admin().backupDatabase("corporate_db", options);
            System.out.printf("Backup completado en %d ms. Archivo: %s%n", 
                backupRes.durationMs(), backupRes.snapshotPath());

            // 2. Disparar Restauración de Base de Datos
            RestoreResult restoreRes = client.admin().restoreDatabase(
                "corporate_db", 
                backupRes.snapshotPath()
            );
            
            if (restoreRes.isSuccess()) {
                System.out.println("Base de datos restaurada y sincronizada en el clúster.");
            }
        }
    }
}
```

---

## 6. Ejemplos Completos por Motor Soportado

### 6.1 Motor de Documentos
```java
client.getDatabase("store").getDocumentEngine("catalog")
    .insert(new JettraDocument().put("_id", "item_9").put("name", "UltraServer 25"));
```

### 6.2 Motor Vectorial (Embeddings)
```java
client.getDatabase("store").getVectorEngine("products_ai")
    .index("item_9", new float[]{0.15f, -0.82f, 0.44f, ...});
```

### 6.3 Motor de Grafos
```java
client.getDatabase("store").getGraphEngine("relations")
    .createEdge("item_9", "category_server", "BELONGS_TO", Map.of("weight", 1.0));
```

### 6.4 Motor de Series Temporales
```java
client.getDatabase("store").getTimeSeriesEngine("telemetry")
    .record(System.currentTimeMillis(), "cpu_load", 38.4);
```

### 6.5 Motor Key-Value
```java
client.getDatabase("store").getKeyValueEngine("session_cache")
    .put("sess_abc123", "active".getBytes());
```

### 6.6 Motor Geoespacial
```java
client.getDatabase("store").getGeospatialEngine("warehouses")
    .insertPoint("wh_panama", 8.9824, -79.5199);
```

### 6.7 Motor Pure Object / Records
```java
public record DeviceTelemetry(String serial, double battery, long timestamp) {}
client.getDatabase("store").getRecordsEngine("devices", DeviceTelemetry.class)
    .persist(new DeviceTelemetry("SN-882", 98.5, System.currentTimeMillis()));
```

### 6.8 Motor Columnar
```java
client.getDatabase("store").getColumnarEngine("financial_olap")
    .appendRow(Map.of("revenue", 125000.0, "quarter", "Q3"));
```

---

## 7. Paginación Segura y Streaming Lazy (`sqlPaged` y `LazyPagedCursor`)

Para prevenir de forma absoluta el desbordamiento de memoria (`OutOfMemoryError: Java heap space`) ante consultas sobre colecciones con cientos de miles o millones de registros, `JettraStoreDriver` incorpora soporte nativo para **paginación acotada** y **cursores perezosos distribuidos**:

### 7.1 Consultas Paginadas con `sqlPaged`
El método `sqlPaged` reescribe y acota dinámicamente cualquier consulta SQL, aplicando `LIMIT` y `OFFSET` calculados para procesar la página solicitada:

```java
// Recuperar la página 2 con 50 registros por lote
JettraSQLProcessor.QueryResult page2 = client.sqlPaged(
    "example_factura_db", 
    "SELECT * FROM clientes", 
    2,  // page (1-based)
    50  // pageSize
);

System.out.printf("Filas recuperadas en página: %d. Resumen: %s%n", 
    page2.rows().size(), page2.message());
```

### 7.2 Procesamiento en Lotes con `LazyPagedCursor`
Cuando una aplicación por lotes (*batch*) o un microservicio de exportación necesita recorrer una colección masiva (como 200,000 clientes o 1,000,000 facturas) sin acumular los datos en memoria:

```java
var cursor = client.cursor("example_factura_db", "clientes", 100);

int totalProcesados = 0;
while (cursor.hasNextPage()) {
    List<Map<String, Object>> pagina = cursor.fetchNextPage();
    if (pagina.isEmpty()) break;
    
    for (Map<String, Object> doc : pagina) {
        // Procesar documento de forma streaming O(1) de memoria
        totalProcesados++;
    }
    // Al salir del bucle, la página anterior queda disponible para el Garbage Collector (ZGC)
}
System.out.println("Total procesado con 0% impacto en Heap: " + totalProcesados);
```

---

## 8. Integración con el Centinela Autónomo `JettraPolice` y Streaming Anti-OOM

El driver expone métodos directos para comunicarse con el subsistema de telemetría, streaming por chunks y prevención de saturación de memoria **`JettraPolice`**:

### 8.1 Sistema Desacoplado de Eventos Sentinel (`JettraPoliceEventListener`)
Para mantener el código de negocio del cliente completamente limpio y desacoplado, cualquier aplicación cliente (como `JettraShell` o interfaces gráficas como `JettraStoreFX`) puede registrar un listener de eventos sin alterar las firmas de los métodos existentes (`findAll()`, `sql()`, `jql()`):

```java
// Registrar listener desacoplado para recibir notificaciones del Sentinel
client.addPoliceEventListener(notification -> {
    System.out.printf("🛡️ [Sentinel Activo] Operación: %s sobre %s%n", 
        notification.operation(), notification.targetCollection());
    System.out.printf("   Lote seguro forzado: %d registros | Heap: %.1f%% (%d MB libres)%n",
        notification.safeBatchSize(), notification.heapUsagePercent(), notification.availableMemoryMb());
    System.out.printf("   Diagnóstico: %s%n", notification.warningMessage());
});
```

### 8.2 Streaming por Chunks (`StreamResponse<T>`) y Consumo Transparente
Cuando el cliente invoca operaciones masivas, el driver consume el flujo continuo de bloques seguros provenientes de `JettraStore`:

```java
// Opción A: Streaming directo por chunks (vaciado y dereferenciado de memoria entre bloques)
try (StreamResponse<Map<String, Object>> stream = client.streamFindAll("example_factura_db", "clientes")) {
    if (stream.isSentinelActivated()) {
        System.out.println("Anti-OOM Sentinel activado: Lote seguro de " + stream.getSafeBatchSize());
    }
    stream.forEachChunk(chunk -> {
        System.out.printf("Procesando lote seguro de %d registros...%n", chunk.size());
        // Al terminar de procesar el lote, queda disponible de inmediato para el Garbage Collector
    });
}

// Opción B: Consumo transparente unificado (compatibilidad absoluta con findAll)
List<Map<String, Object>> allRecords = client.findAll("example_factura_db", "clientes");
System.out.printf("Recuperados %d registros de forma segura sin desbordar el Heap.%n", allRecords.size());
```

### 8.3 Evaluación Predictiva y Auditoría

```java
// 1. Evaluar si una consulta masiva agotaría el Heap antes de ejecutarla
JettraPolice.PoliceDecision decision = client.evaluateQuerySafety(
    "example_factura_db", 
    "clientes", 
    0 // 0 = sin límite explícito (SELECT * completo)
);

if (decision.interventionRequired()) {
    System.out.printf("[AVISO POLICE] %s%n", decision.rationale());
    System.out.printf("Límite forzado de seguridad: %d registros por lote.%n", 
        decision.enforcedLimit());
}

// 2. Consultar el historial de alertas preventivas registradas en el clúster
List<JettraPolice.PoliceAlert> alerts = client.getPolice().getAlerts();
for (var alert : alerts) {
    System.out.printf("[%s] %s: %s%n", alert.timestamp(), alert.code(), alert.message());
}
```

---

## 9. Integración de Almacenamiento Off-Heap de Ultra-Baja Latencia con `JettraMemory`

`JettraStoreDriver` integra de forma nativa el motor off-heap `JettraMemory`, permitiendo almacenar y recuperar buffers binarios nativos fuera del Garbage Collector mediante Project Panama (FFM API):

### 9.1 Almacenamiento y Recuperación Binaria Off-Heap
```java
// 1. Obtener acceso al motor JettraMemoryEngine para una base de datos
JettraMemoryEngine memEngine = client.getMemoryEngine("example_factura_db");

// 2. Almacenar payload binario directamente sin serialización en Heap
byte[] binaryPayload = Files.readAllBytes(Path.of("reporte_fiscal.pdf"));
client.putBinary("example_factura_db", "factura:pdf:F-100245", binaryPayload);

// 3. Recuperar payload binario de forma instantánea
Optional<byte[]> data = client.getBinary("example_factura_db", "factura:pdf:F-100245");
if (data.isPresent()) {
    System.out.printf("Payload binario recuperado (%d bytes) sin presión en Heap.%n", data.get().length);
}

// 4. Métricas de memoria nativa y compactación en caliente
Map<String, Object> memMetrics = client.getMemoryMetrics("example_factura_db");
System.out.printf("Off-Heap en uso: %s bytes | Segmentos activos: %s%n", 
    memMetrics.get("offHeapBytesUsed"), memMetrics.get("activeSegments"));

// Ejecutar compactación fuera de banda
client.compactMemory("example_factura_db");
```

### 9.2 Gestión Programática de Modos: `JVM-RAM` vs `DISK-MEMORY`
El driver permite configurar el modo de almacenamiento por base de datos o de manera global:
```java
// Consultar el modo actual
StorageMode mode = client.getStorageMode("example_factura_db");

// Conmutar a modo DISK-MEMORY (JettraMemory Off-Heap LSM)
client.setStorageMode("example_factura_db", StorageMode.DISK_MEMORY);

// O conmutar a modo JVM-RAM
client.setStorageMode("example_factura_db", StorageMode.JVM_RAM);
```


---

## 10. Directrices de Arquitectura y Buenas Prácticas

1. **Evitar Consultas Abiertas sin Límite:** Siempre use `sqlPaged` o configure un `LIMIT` razonable al consultar colecciones de alta cardinalidad.
2. **Uso de Virtual Threads:** Ejecute las llamadas al driver en hilos virtuales creados con `Thread.ofVirtual().start(...)` para maximizar el throughput concurrente.
3. **Liberación de Recursos:** Siempre utilice bloques `try-with-resources` sobre `JettraClient` para garantizar la liberación de arenas nativas Off-Heap de Project Panama.
4. **Almacenamiento Híbrido Document + Off-Heap:** Use `JettraDocument` para esquemas de consulta y metadatos, y almacene adjuntos masivos (PDFs, firmas criptográficas, imágenes) a través de `client.putBinary(...)` delegando en `JettraMemory`.

---

## 11. Distribución y Replicación en Clúster (`Cluster-Distributed API`)

A partir de la versión 1.0, `JettraClient` incluye métodos nativos para orquestar la sincronización y distribución de bases de datos completas y registros a través del clúster Raft/multinodo.

### 11.1 Métodos Disponibles en `JettraClient`

```java
// 1. Distribuir una base de datos específica con todos sus registros a los nodos secundarios
boolean success = client.clusterDistributed("example_factura_db");

// 2. Distribuir todas las bases de datos registradas y sus registros a todos los nodos
boolean allSuccess = client.clusterDistributedAll();

// 3. Consultar la tabla de información de distribución de nodos y bases de datos
List<Map<String, Object>> nodes = client.getClusterDistributedInfo();
```

### 11.2 Ejemplo de Integración en Java 25

```java
package com.example;

import io.jettra.driver.JettraClient;
import java.util.List;
import java.util.Map;

public class ClusterDistributionDemo {
    public static void main(String[] args) {
        try (JettraClient client = JettraClient.builder()
                .host("192.168.60.243")
                .port(9091)
                .credentials("admin", "admin-jettra")
                .build()) {

            System.out.println("Distribuir base de datos 'example_factura_db'...");
            boolean ok = client.clusterDistributed("example_factura_db");
            System.out.println("Resultado de distribución: " + ok);

            System.out.println("\nConsultando estado de distribución en el clúster:");
            List<Map<String, Object>> topology = client.getClusterDistributedInfo();
            for (Map<String, Object> node : topology) {
                System.out.printf("- Nodo [%s] en %s:%s | Rol: %s | Estado: %s | Bases de datos: %s%n",
                        node.get("nodeId"),
                        node.get("ip"),
                        node.get("port"),
                        node.get("role"),
                        node.get("status"),
                        node.get("databases"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

