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

`JettraStore` es un motor de base de datos multimodelo nativo que ejecuta simultáneamente 8 motores especializados en memoria y disco, coordinados por consenso y protegidos por el Centinela `JettraPolice`. A través de `JettraClient`, las aplicaciones Java 25 pueden interactuar directamente con cada motor mediante APIs fluidas, tipadas y optimizadas para evitar la presión sobre el Garbage Collector.

### 6.1 Motor de Documentos (`DocumentEngine`)

El motor de documentos almacena estructuras semiestructuradas JSON basadas internamente en `UnifiedMap`, eliminando la sobrecarga de nodos internos de `HashMap`. Es ideal para catálogos de productos, perfiles de usuario, facturación y configuraciones dinámicas.

#### Operaciones Soportadas:
* **Inserción individual y en lote:** `insert(id, doc)` e `insertBatch(batchMap)`.
* **Búsqueda por clave primaria:** `findById(id)`.
* **Actualización atómica en memoria:** `update(id, doc)`.
* **Streaming protegido anti-OOM:** `streamAll()`, `streamAll(limit)` y `forEachBatch(batchSize, consumer)`.
* **Eliminación y conteo:** `delete(id)`, `count()` y `clear()`.

#### Ejemplo de Código:
```java
package io.jettra.driver.example;

import io.jettra.driver.JettraClient;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.core.StreamResponse;
import io.jettra.store.engine.models.DocumentEngine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DocumentEngineDemo {
    public static void main(String[] args) {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase("store");
            DocumentEngine catalog = db.getDocumentEngine("catalog");

            // 1. Inserción individual
            Map<String, Object> serverDoc = Map.of(
                "name", "UltraServer 25 Enterprise",
                "category", "Compute",
                "cores", 64,
                "ramGb", 256,
                "price", 8499.50,
                "active", true
            );
            catalog.insert("item_9", serverDoc);

            // 2. Inserción masiva por lotes (Batch Insert)
            Map<String, Map<String, Object>> batch = new HashMap<>();
            batch.put("item_10", Map.of("name", "Blade Server B20", "cores", 32, "price", 4200.0));
            batch.put("item_11", Map.of("name", "Storage Array SAN 40TB", "type", "NVMe", "price", 11500.0));
            catalog.insertBatch(batch);

            // 3. Búsqueda por ID (_id)
            Map<String, Object> found = catalog.findById("item_9");
            if (found != null) {
                System.out.printf("Producto: %s | Núcleos: %s | Precio: $%.2f%n",
                    found.get("name"), found.get("cores"), found.get("price"));
            }

            // 4. Actualización en memoria (con persistencia SSTable / Off-Heap)
            catalog.update("item_9", Map.of(
                "price", 7999.00,
                "stock", 8,
                "discountApplied", true
            ));

            // 5. Iteración segura por lotes (Zero GC Heap Spike)
            catalog.forEachBatch(50, chunk -> {
                System.out.printf("Procesando lote seguro de %d documentos...%n", chunk.size());
                for (Map<String, Object> doc : chunk) {
                    // Procesamiento O(1) de memoria
                }
            });

            // 6. Streaming reactivo con metadatos de Sentinel Anti-OOM
            try (StreamResponse<Map<String, Object>> stream = catalog.streamAll()) {
                stream.forEachChunk(chunk -> {
                    System.out.printf("Chunk recibido: %d registros.%n", chunk.size());
                });
            }

            // 7. Eliminación
            boolean deleted = catalog.delete("item_11");
            System.out.printf("Total documentos restantes en catálogo: %d%n", catalog.count());
        }
    }
}
```

---

### 6.2 Motor Vectorial (`VectorEngine` & Búsqueda Semántica ANN)

El motor vectorial gestiona representaciones numéricas densas (`float[]`) comúnmente generadas por modelos de Inteligencia Artificial (LLMs, CLIP, BERT). Permite búsqueda por similitud de cosenos, distancia euclidiana y álgebra vectorial de ultra-alta velocidad.

#### Operaciones Soportadas:
* **Indexación con dimensiones estrictas:** `index(id, embedding)` e `indexBatch(batch)`.
* **Búsqueda por Similitud Coseno (k-NN / ANN):** `searchCosine(queryVector, limit)`.
* **Búsqueda por Distancia Euclidiana:** `searchEuclidean(queryVector, limit)`.
* **Métricas directas inter-vectores:** `cosineSimilarity(id1, id2)`, `euclideanDistance(id1, id2)`, `dotProduct(id1, id2)`, `norm(id)`.
* **Cálculo del Centroide del espacio:** `centroid()`.

#### Ejemplo de Código:
```java
package io.jettra.driver.example;

import io.jettra.driver.JettraClient;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.engine.models.VectorEngine;
import io.jettra.store.engine.models.VectorEngine.VectorMatch;

import java.util.List;
import java.util.Map;

public class VectorEngineDemo {
    public static void main(String[] args) {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase("store");
            
            // Inicializar motor para embeddings de 4 dimensiones
            VectorEngine aiEngine = db.getVectorEngine("products_ai", 4);

            // 1. Indexar embeddings individuales
            aiEngine.index("server_xeon", new float[]{0.91f, 0.12f, -0.38f, 0.44f});
            aiEngine.index("server_epyc", new float[]{0.89f, 0.15f, -0.40f, 0.42f});
            aiEngine.index("gpu_accelerator", new float[]{-0.20f, 0.88f, 0.45f, -0.10f});

            // 2. Indexación en lote (Batch Indexing)
            Map<String, float[]> batchVectors = Map.of(
                "switch_cisco", new float[]{0.15f, -0.65f, 0.72f, 0.18f},
                "san_storage", new float[]{0.35f, -0.25f, 0.80f, 0.30f}
            );
            aiEngine.indexBatch(batchVectors);

            // 3. Búsqueda semántica por Similitud de Coseno (Top-2 más cercanos)
            float[] queryVector = new float[]{0.90f, 0.13f, -0.39f, 0.43f};
            List<VectorMatch> cosineResults = aiEngine.searchCosine(queryVector, 2);

            System.out.println("--- Resultados de Búsqueda Semántica (Coseno) ---");
            for (VectorMatch match : cosineResults) {
                System.out.printf("Item: %-18s | Score de Similitud: %.4f%n", match.id(), match.score());
            }

            // 4. Búsqueda por Distancia Euclidiana (menor valor = mayor proximidad)
            List<VectorMatch> euclideanResults = aiEngine.searchEuclidean(queryVector, 2);
            System.out.println("\n--- Resultados por Distancia Euclidiana ---");
            euclideanResults.forEach(m -> 
                System.out.printf("Item: %-18s | Distancia: %.4f%n", m.id(), m.score()));

            // 5. Comparación directa entre dos vectores indexados
            float similarity = aiEngine.cosineSimilarity("server_xeon", "server_epyc");
            float distance = aiEngine.euclideanDistance("server_xeon", "gpu_accelerator");
            float dot = aiEngine.dotProduct("server_xeon", "server_epyc");
            float norm = aiEngine.norm("server_xeon");

            System.out.printf("%nSimilitud (Xeon vs Epyc): %.4f%n", similarity);
            System.out.printf("Distancia (Xeon vs GPU): %.4f%n", distance);
            System.out.printf("Producto Punto: %.4f | Norma Xeon: %.4f%n", dot, norm);

            // 6. Centroide del espacio vectorial
            float[] center = aiEngine.centroid();
            System.out.printf("Centroide computado con %d dimensiones. Total vectores: %d%n", 
                center.length, aiEngine.size());
        }
    }
}
```

---

### 6.3 Motor de Grafos (`GraphEngine`)

El motor de grafos modela redes complejas, relaciones de negocio, dependencias de infraestructura y grafos de conocimiento mediante un modelo de lista de adyacencia concurrente de alto rendimiento (*Property Graph*).

#### Operaciones Soportadas:
* **Creación de vértices (nodos):** `addVertex(vertexId)`.
* **Creación de aristas dirigidas con propiedades:** `addEdge(from, to, label, properties)`.
* **Carga masiva de aristas:** `addEdgesBatch(batchMap)`.
* **Inspección de adyacencia saliente:** `getOutboundEdges(vertexId)`.
* **Navegación de relaciones:** Inspección de `targetVertex()`, `label()` y `properties()`.
* **Topología global y limpieza:** `getVertices()`, `getAllEdges()`, `size()`, `clear()`.

#### Ejemplo de Código:
```java
package io.jettra.driver.example;

import io.jettra.driver.JettraClient;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.engine.models.GraphEngine;
import io.jettra.store.engine.models.GraphEngine.Edge;

import java.util.List;
import java.util.Map;

public class GraphEngineDemo {
    public static void main(String[] args) {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase("store");
            GraphEngine topology = db.getGraphEngine("microservices_mesh");

            // 1. Declarar vértices de la arquitectura
            topology.addVertex("gateway_api");
            topology.addVertex("service_auth");
            topology.addVertex("service_orders");
            topology.addVertex("service_inventory");
            topology.addVertex("db_postgres_cluster");

            // 2. Establecer relaciones dirigidas con metadatos y pesos
            topology.addEdge("gateway_api", "service_auth", "ROUTES_TO", 
                Map.of("protocol", "gRPC", "timeoutMs", 150, "authRequired", false));

            topology.addEdge("gateway_api", "service_orders", "ROUTES_TO", 
                Map.of("protocol", "gRPC", "timeoutMs", 300, "rateLimit", 1000));

            topology.addEdge("service_orders", "service_inventory", "VERIFIES_STOCK", 
                Map.of("retryCount", 3, "circuitBreaker", true));

            topology.addEdge("service_orders", "db_postgres_cluster", "PERSISTS_IN", 
                Map.of("isolationLevel", "SERIALIZABLE", "readWrite", true));

            // 3. Inspeccionar llamadas salientes desde el API Gateway
            System.out.println("--- Conexiones salientes desde 'gateway_api' ---");
            List<Edge> routes = topology.getOutboundEdges("gateway_api");
            for (Edge edge : routes) {
                System.out.printf("  ↳ [%s] -> %s (Protocolo: %s, Timeout: %sms)%n",
                    edge.label(),
                    edge.targetVertex(),
                    edge.properties().get("protocol"),
                    edge.properties().get("timeoutMs"));
            }

            // 4. Navegación en profundidad: Cadena de dependencias de 'service_orders'
            System.out.println("\n--- Dependencias aguas abajo de 'service_orders' ---");
            List<Edge> orderDependencies = topology.getOutboundEdges("service_orders");
            for (Edge dep : orderDependencies) {
                System.out.printf("  ↳ Relación '%s' con nodo '%s' | Config: %s%n",
                    dep.label(), dep.targetVertex(), dep.properties());
            }

            // 5. Métricas del grafo
            System.out.printf("%nTotal de nodos en la topología: %d%n", topology.size());
            System.out.printf("Nodos registrados: %s%n", topology.getVertices());
        }
    }
}
```

---

### 6.4 Motor de Series Temporales (`TimeSeriesEngine`)

El motor de series temporales organiza métricas cronológicas de forma ordenada en memoria utilizando estructuras `ConcurrentSkipListMap`. Está diseñado para telemetría de servidores, métricas de red, sensores de Internet de las Cosas (IoT) y cotizaciones financieras.

#### Operaciones Soportadas:
* **Registro de métricas por timestamp:** `record(timestamp, value)`.
* **Inserción masiva en lote:** `recordBatch(batchMap)`.
* **Consultas por ventana temporal (Range Query):** `range(fromEpoch, toEpoch)`.
* **Cálculo de promedios instantáneos en ventana:** `average(fromEpoch, toEpoch)`.
* **Inspección completa:** `getAll()`, `size()`, `getMetricName()`.

#### Ejemplo de Código:
```java
package io.jettra.driver.example;

import io.jettra.driver.JettraClient;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.engine.models.TimeSeriesEngine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;

public class TimeSeriesEngineDemo {
    public static void main(String[] args) {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase("store");
            TimeSeriesEngine cpuTelemetry = db.getTimeSeriesEngine("cpu_load_percent");

            long now = System.currentTimeMillis();

            // 1. Inserción individual de lecturas cronológicas
            cpuTelemetry.record(now - 180_000, 34.2); // Hace 3 minutos
            cpuTelemetry.record(now - 120_000, 48.7); // Hace 2 minutos
            cpuTelemetry.record(now - 60_000,  76.5); // Hace 1 minuto
            cpuTelemetry.record(now,           92.1); // Actual

            // 2. Registro masivo de puntos por lote (Batch Telemetry)
            Map<Long, Double> minuteTelemetry = new LinkedHashMap<>();
            for (int sec = 1; sec <= 30; sec++) {
                long timestamp = now + (sec * 1000L);
                double simulatedLoad = 50.0 + (Math.sin(sec / 5.0) * 20.0);
                minuteTelemetry.put(timestamp, simulatedLoad);
            }
            cpuTelemetry.recordBatch(minuteTelemetry);

            // 3. Consulta de rango temporal (últimos 3 minutos)
            long windowStart = now - 180_000;
            long windowEnd = now;
            NavigableMap<Long, Double> historical = cpuTelemetry.range(windowStart, windowEnd);

            System.out.printf("--- Muestras en ventana [%d - %d] ---%n", windowStart, windowEnd);
            historical.forEach((t, val) -> {
                System.out.printf("  Timestamp: %tT | Carga CPU: %.2f%%%n", t, val);
            });

            // 4. Agregación automática: Promedio en ventana temporal
            double avgLoad = cpuTelemetry.average(windowStart, windowEnd);
            System.out.printf("%nCarga promedio en los últimos 3 minutos: %.2f%%%n", avgLoad);

            // 5. Análisis estadístico descriptivo utilizando JettraClient
            var summary = client.statsSummary(historical.values().stream().toList());
            System.out.printf("Estadísticas -> Media: %.2f%% | Desv. Estándar: %.2f | Mín: %.2f%% | Máx: %.2f%%%n",
                summary.mean(), summary.stddev(), summary.min(), summary.max());
        }
    }
}
```

---

### 6.5 Motor Clave-Valor (`KeyValueEngine`)

El motor Key-Value ofrece almacenamiento atómico de arreglos de bytes (`byte[]`) indexados por claves alfanuméricas dentro de espacios de nombres (*namespaces*). Es ideal para cachés de alta velocidad, estados de sesión HTTP, tokens de autenticación y buffers binarios.

#### Operaciones Soportadas:
* **Escritura atómica:** `put(key, bytes)` y `putBatch(batchMap)`.
* **Lectura binaria:** `get(key)`.
* **Comprobación de existencia:** `containsKey(key)`.
* **Eliminación y vaciado:** `remove(key)` y `clear()`.
* **Listado global:** `getAll()`, `size()`.

#### Ejemplo de Código:
```java
package io.jettra.driver.example;

import io.jettra.driver.JettraClient;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.engine.models.KeyValueEngine;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class KeyValueEngineDemo {
    public static void main(String[] args) {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase("store");
            KeyValueEngine sessionStore = db.getKeyValueEngine("user_sessions");

            // 1. Escritura de claves con datos serializados en UTF-8
            String token = "jwt_bearer_9824_xyz";
            String sessionPayload = "{\"userId\":\"usr_991\",\"role\":\"ADMIN\",\"tenantId\":\"enterprise_pa\"}";
            sessionStore.put(token, sessionPayload.getBytes(StandardCharsets.UTF_8));

            // Guardar flag binario
            sessionStore.put("system:read_only_mode", new byte[]{0});

            // 2. Inserción de lote (Batch Put)
            Map<String, byte[]> batch = Map.of(
                "rate:ip_192_168_1_10", "15".getBytes(StandardCharsets.UTF_8),
                "rate:ip_192_168_1_25", "4".getBytes(StandardCharsets.UTF_8)
            );
            sessionStore.putBatch(batch);

            // 3. Verificación de existencia rápida O(1)
            if (sessionStore.containsKey(token)) {
                System.out.println("Token de sesión activo y verificado en memoria.");
            }

            // 4. Lectura de clave
            byte[] rawData = sessionStore.get(token);
            if (rawData != null) {
                String payload = new String(rawData, StandardCharsets.UTF_8);
                System.out.println("Payload recuperado: " + payload);
            }

            // 5. Eliminación de sesión (Logout)
            boolean revoked = sessionStore.remove(token);
            System.out.printf("Sesión revocada: %s. Total claves activas: %d%n", 
                revoked, sessionStore.size());
        }
    }
}
```

---

### 6.6 Motor Geoespacial (`GeospatialEngine`)

El motor geoespacial permite registrar coordenadas de latitud y longitud y realizar búsquedas de proximidad por radio utilizando la fórmula trigonométrica de Haversine de alta precisión.

#### Operaciones Soportadas:
* **Registro de puntos espaciales:** `insertPoint(id, latitude, longitude)`.
* **Inserción masiva:** `insertBatch(batchMap)` mediante objetos `GeoPoint`.
* **Búsqueda en radio geográfico:** `findWithinRadius(centerLat, centerLon, radiusKm)` retornando `GeoDistanceResult(id, distanceKm)` ordenado por distancia.
* **Inspección de capa espacial:** `getAllPoints()`, `size()`, `getLayerName()`.

#### Ejemplo de Código:
```java
package io.jettra.driver.example;

import io.jettra.driver.JettraClient;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.engine.models.GeospatialEngine;
import io.jettra.store.engine.models.GeospatialEngine.GeoDistanceResult;
import io.jettra.store.engine.models.GeospatialEngine.GeoPoint;

import java.util.List;
import java.util.Map;

public class GeospatialEngineDemo {
    public static void main(String[] args) {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase("store");
            GeospatialEngine logistics = db.getGeospatialEngine("distribution_centers");

            // 1. Registro individual de almacenes (Latitud, Longitud)
            logistics.insertPoint("hub_panama_pacifico", 8.9180, -79.5980);
            logistics.insertPoint("hub_colon_free_zone", 9.3598, -79.9014);
            logistics.insertPoint("hub_tocumen_cargo",    9.0682, -79.3789);
            logistics.insertPoint("hub_david_chiriqui",   8.4274, -82.4310);
            logistics.insertPoint("hub_santiago_veraguas",8.1000, -80.9700);

            // 2. Registro masivo de puntos con GeoPoint
            Map<String, GeoPoint> regionalHubs = Map.of(
                "hub_san_jose_cr", new GeoPoint("hub_san_jose_cr", 9.9281, -84.0907),
                "hub_medellin_co", new GeoPoint("hub_medellin_co", 6.2442, -75.5812)
            );
            logistics.insertBatch(regionalHubs);

            // 3. Consulta de proximidad: Encontrar almacenes en un radio de 80 Km desde el centro de la ciudad
            double clientLat = 8.9824; // Ciudad de Panamá
            double clientLon = -79.5199;
            double radiusKm = 80.0;

            List<GeoDistanceResult> nearbyHubs = logistics.findWithinRadius(clientLat, clientLon, radiusKm);

            System.out.printf("--- Centros de Distribución a menos de %.1f Km ---%n", radiusKm);
            for (GeoDistanceResult result : nearbyHubs) {
                System.out.printf("  Almacén: %-22s | Distancia: %.2f Km%n", 
                    result.id(), result.distanceKm());
            }

            System.out.printf("%nTotal de puntos indexados en capa '%s': %d%n",
                logistics.getLayerName(), logistics.size());
        }
    }
}
```

---

### 6.7 Motor Pure Object / Java 25 Records (`RecordsEngine`)

El motor de records permite persistir y consultar entidades de dominio Java inmutables (`record`) sin necesidad de anotaciones complejas ni frameworks ORM pesados. Ofrece máxima seguridad de tipos en tiempo de compilación y compatibilidad con serialización compacta.

#### Operaciones Soportadas:
* **Persistencia tipada:** `persist(id, instance)` e `insertBatch(batchMap)`.
* **Recuperación tipada directa:** `find(id)`.
* **Listado completo y conteo:** `listAll()`, `getAll()`, `count()`.
* **Eliminación:** `remove(id)`.

#### Ejemplo de Código:
```java
package io.jettra.driver.example;

import io.jettra.driver.JettraClient;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.engine.models.RecordsEngine;

import java.util.List;
import java.util.Map;

public class RecordsEngineDemo {

    // Definición de dominio con Java Records inmutables
    public record HardwareTelemetry(
        String deviceSerial,
        String firmwareVersion,
        double batteryPercent,
        double temperatureCelsius,
        boolean isOnline,
        long timestamp
    ) implements java.lang.Record {}

    public static void main(String[] args) {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase("store");

            // 1. Obtener motor fuertemente tipado para HardwareTelemetry
            RecordsEngine<HardwareTelemetry> engine = db.getRecordsEngine("iot_telemetry", HardwareTelemetry.class);

            // 2. Persistir registro individual
            HardwareTelemetry node1 = new HardwareTelemetry(
                "DEV-IOT-9901", "v2.5.1", 98.4, 42.1, true, System.currentTimeMillis()
            );
            engine.persist("DEV-IOT-9901", node1);

            // 3. Persistir en lote (Batch Persist)
            Map<String, HardwareTelemetry> batch = Map.of(
                "DEV-IOT-9902", new HardwareTelemetry("DEV-IOT-9902", "v2.5.1", 64.0, 48.3, true, System.currentTimeMillis()),
                "DEV-IOT-9903", new HardwareTelemetry("DEV-IOT-9903", "v2.4.9", 12.5, 62.8, false, System.currentTimeMillis())
            );
            engine.persistBatch(batch);

            // 4. Búsqueda directa por ID tipado
            HardwareTelemetry retrieved = engine.find("DEV-IOT-9901");
            if (retrieved != null) {
                System.out.printf("Dispositivo: %s | Firmware: %s | Batería: %.1f%% | Temp: %.1f°C | Estado: %s%n",
                    retrieved.deviceSerial(),
                    retrieved.firmwareVersion(),
                    retrieved.batteryPercent(),
                    retrieved.temperatureCelsius(),
                    retrieved.isOnline() ? "ONLINE" : "OFFLINE");
            }

            // 5. Listado y filtrado funcional
            List<HardwareTelemetry> allDevices = engine.listAll();
            System.out.printf("%nTotal dispositivos registrados: %d%n", engine.count());

            long offlineCount = allDevices.stream().filter(d -> !d.isOnline()).count();
            System.out.printf("Dispositivos fuera de línea: %d%n", offlineCount);

            // 6. Eliminación de dispositivo retirado
            engine.remove("DEV-IOT-9903");
        }
    }
}
```

---

### 6.8 Motor Columnar (`ColumnarEngine`)

El motor columnar almacena los datos contiguamente por columna numérica y de texto en lugar de por filas. Es la opción predilecta para procesamiento analítico en línea (OLAP), cálculos agregados masivos (`SUM`, `AVG`), tableros de control financiero y Data Warehousing embebido.

#### Operaciones Soportadas:
* **Inserción por fila heterogénea:** `appendRow(Map<String, Object>)`.
* **Inserción masiva contigua:** `appendBatch(numericCols, textCols, count)`.
* **Lectura de vectores de columna:** `getNumericColumn(name)` y `getTextColumns()`.
* **Suma analítica O(N) sin intermediarios:** `sumColumn(name)`.
* **Conteo y estructura:** `getRowCount()`, `size()`, `getNumericColumns()`.

#### Ejemplo de Código:
```java
package io.jettra.driver.example;

import io.jettra.driver.JettraClient;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.engine.models.ColumnarEngine;

import java.util.List;
import java.util.Map;

public class ColumnarEngineDemo {
    public static void main(String[] args) {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase("store");
            ColumnarEngine olap = db.getColumnarEngine("financial_olap");

            // 1. Inserción individual de filas (los valores numéricos se canalizan a arrays continuos)
            olap.appendRow(Map.of(
                "region", "LATAM",
                "quarter", "Q1",
                "revenue", 185000.00,
                "cost", 74000.00,
                "tax", 18500.00
            ));

            olap.appendRow(Map.of(
                "region", "NORTH_AMERICA",
                "quarter", "Q1",
                "revenue", 340000.00,
                "cost", 132000.00,
                "tax", 34000.00
            ));

            // 2. Inserción masiva por bloques de columnas (High Throughput Batch Append)
            Map<String, List<Double>> numBatch = Map.of(
                "revenue", List.of(295000.0, 160000.0, 410000.0),
                "cost",    List.of(110000.0, 68000.0,  155000.0),
                "tax",     List.of(29500.0,  16000.0,  41000.0)
            );
            Map<String, List<String>> txtBatch = Map.of(
                "region",  List.of("EMEA", "APAC", "GLOBAL_CORP"),
                "quarter", List.of("Q1", "Q1", "Q1")
            );
            olap.appendBatch(numBatch, txtBatch, 3);

            // 3. Agregaciones aceleradas en memoria sobre columnas continuas
            double totalRevenue = olap.sumColumn("revenue");
            double totalCost = olap.sumColumn("cost");
            double totalTax = olap.sumColumn("tax");
            double grossMargin = totalRevenue - totalCost - totalTax;

            System.out.println("--- Resumen Financiero Trimestral (OLAP) ---");
            System.out.printf("Total Filas Analizadas: %d%n", olap.getRowCount());
            System.out.printf("Ingresos Brutos:        $%,12.2f%n", totalRevenue);
            System.out.printf("Costos Operativos:      $%,12.2f%n", totalCost);
            System.out.printf("Impuestos Totales:      $%,12.2f%n", totalTax);
            System.out.printf("Margen Neto Operativo:  $%,12.2f%n", grossMargin);

            // 4. Extracción de vector numérico para cálculo de promedio
            List<Double> revenues = olap.getNumericColumn("revenue");
            double avgRevenue = revenues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            System.out.printf("Ingreso Promedio por Región: $%,12.2f%n", avgRevenue);
        }
    }
}
```

---

### 6.9 Flujo Multimodelo Integrado (Cross-Engine Interoperability)

La ventaja distintiva de `JettraStore` radica en coordinar operaciones simultáneas entre múltiples motores sobre la misma base de datos sin necesidad de microservicios intermediarios ni brokers externos:

```java
package io.jettra.driver.example;

import io.jettra.driver.JettraClient;
import io.jettra.store.core.JettraDatabase;
import java.util.Map;

public class CrossEngineWorkflowDemo {
    public static void main(String[] args) {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase("unified_enterprise");

            String sku = "SERVER-AI-X9";

            // 1. Motor de Documentos: Guardar ficha técnica y precio
            db.getDocumentEngine("products").insert(sku, Map.of(
                "name", "AI Supermicro Tensor Core Server",
                "price", 18900.00,
                "category", "AI Hardware"
            ));

            // 2. Motor Vectorial: Indexar embeddings del producto para recomendador semántico
            db.getVectorEngine("products_embeddings", 4)
                .index(sku, new float[]{0.94f, -0.12f, 0.44f, 0.78f});

            // 3. Motor Geoespacial: Asociar la ubicación del almacén físico con stock
            db.getGeospatialEngine("inventory_locations")
                .insertPoint(sku + "_loc", 8.9824, -79.5199);

            // 4. Motor de Grafos: Relacionar con categorías y proveedores
            db.getGraphEngine("knowledge_graph")
                .addEdge(sku, "vendor_nvidia", "SUPPLIED_BY", Map.of("leadTimeDays", 14));

            // 5. Motor de Series Temporales: Registrar la primera métrica de demanda
            db.getTimeSeriesEngine("product_views")
                .record(System.currentTimeMillis(), 1.0);

            System.out.println("✅ Entidad multimodal registrada exitosamente a través de 5 motores!");
        }
    }
}
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

### 11.3 Canal de Eventos en Tiempo Real y Streaming (`cluster live`)

`JettraClient` permite a las aplicaciones consumidoras suscribirse y recibir en tiempo real todos los eventos que ocurren dentro del clúster (cambios de topología, altas/bajas de nodos, transferencias de bases de datos y replicación de registros).

#### Métodos de Eventos en Vivo:
* **`client.getClusterLiveEvents()`**: Recupera la lista completa de eventos recientes en el búfer circular del servidor.
* **`client.getRecentClusterLiveEvents(int limit)`**: Recupera los últimos `limit` eventos cronológicos.
* **`client.subscribeClusterLive(Consumer<ClusterLiveEvent> listener)`**: Registra un observador reactivo que se ejecuta asíncronamente mediante Virtual Threads ante cada nuevo evento.
* **`client.unsubscribeClusterLive(Consumer<ClusterLiveEvent> listener)`**: Desregistra el observador.

#### Ejemplo de Consumo Reactivo de Eventos:
```java
// Suscripción en tiempo real a eventos del clúster
client.subscribeClusterLive(event -> {
    System.out.printf("[%s] NODO: %s -> %s | TIPO: %s | MSG: %s%n",
        event.formattedTimestamp(),
        event.sourceNodeId(),
        event.targetNodeId(),
        event.type(),
        event.message()
    );
});
```

### 11.4 Failover Automático de Primario y Reconexión Transparente

Para evitar fallos en aplicaciones de misión crítica cuando un nodo primario cae o es detenido:
* **`client.registerAutoFailoverHandler()`**: Activa un listener interno que monitoriza eventos `LEADER_PROMOTED`.
* Cuando un nodo secundario toma el rol de líder `PRIMARY`, el driver actualiza de manera atómica su anillo de enrutamiento (*Dynamic Ring Engine*), redirigiendo todas las operaciones de escritura y consultas transaccionales hacia el nuevo primario sin requerir reiniciar la conexión ni relanzar la aplicación cliente.
```

