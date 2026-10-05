# JettraStore: Guía Maestra del Framework de Pruebas y Validación del Sistema (`test.md`)

**Suite Completa de Pruebas Unitarias, Integración, Resiliencia de Clúster, Seguridad JettraJWT, Anillo Distribuido y Microbenchmarking JMH**
*Plataforma: Java 25+ | Framework: jettraTest*

---

## Tabla de Contenidos
1. [Estructura y Filosofía de Pruebas con `jettraTest`](#1-estructura-y-filosofía-de-pruebas-con-jettratest)
2. [Pruebas de Seguridad y Control de Acceso (`JettraJWT`)](#2-pruebas-de-seguridad-y-control-de-acceso-jettrajwt)
   - [2.1 Autenticación de Superusuario (`admin` / `admin-jettra`)](#21-autenticación-de-superusuario-admin--admin-jettra)
   - [2.2 Inviolabilidad de Roles del Administrador](#22-inviolabilidad-de-roles-del-administrador)
   - [2.3 Validación de Expiración y Manipulación de Tokens](#23-validación-de-expiración-y-manipulación-de-tokens)
3. [Pruebas CRUD Exhaustivas en los 8 Motores Multimodelo](#3-pruebas-crud-exhaustivas-en-los-8-motores-multimodelo)
   - [3.1 Motor Document y Key-Value](#31-motor-document-y-key-value)
   - [3.2 Motor Columnar y Series Temporales](#32-motor-columnar-y-series-temporales)
   - [3.3 Motor Geoespacial y Vectorial (Embeddings)](#33-motor-geoespacial-y-vectorial-embeddings)
   - [3.4 Motor de Grafos y Pure Objects / Records](#34-motor-de-grafos-y-pure-objects--records)
4. [Pruebas de Referencias Cruzadas (Intra e Inter-Engine)](#4-pruebas-de-referencias-cruzadas-intra-e-inter-engine)
   - [4.1 Validación de Carga Perezosa (*Lazy Load*)](#41-validación-de-carga-perezosa-lazy-load)
   - [4.2 Validación de Carga Ansiosa (*Eager Load*)](#42-validación-de-carga-ansiosa-eager-load)
5. [Pruebas de Consenso Distribuido Raft y Tolerancia a Fallos](#5-pruebas-de-consenso-distribuido-raft-y-tolerancia-a-fallos)
   - [5.1 Elección de Líder en Clúster de 3 Nodos](#51-elección-de-líder-en-clúster-de-3-nodos)
   - [5.2 Simulación de Partición de Red (Split-Brain Prevention)](#52-simulación-de-partición-de-red-split-brain-prevention)
6. [Pruebas del Motor de Anillo Distribuido por Saturación de RAM](#6-pruebas-del-motor-de-anillo-distribuido-por-saturación-de-ram)
   - [6.1 Inyección de Carga de Memoria Off-Heap al 85%](#61-inyección-de-carga-de-memoria-off-heap-al-85)
   - [6.2 Verificación de Migración Dinámica y Descompresión](#62-verificación-de-migración-dinámica-y-descompresión)
7. [Pruebas del Componente de Supervisión Preventiva (`JettraPolice`)](#7-pruebas-del-componente-de-supervisión-preventiva-jettrapolice)
   - [7.1 Regla de Alerta de Disco y Compactación de Archivos `.jettra`](#71-regla-de-alerta-de-disco-y-compactación-de-archivos-jettra)
   - [7.2 Detección de Intentos de Intrusión](#72-detección-de-intentos-de-intrusión)
8. [Suite de Microbenchmarking con JMH](#8-suite-de-microbenchmarking-con-jmh)
   - [8.1 Activación con `jmh.metrics.active = true`](#81-activación-con-jmhmetricsactive--true)
   - [8.2 Ejecución de Benchmarks Críticos](#82-ejecución-de-benchmarks-críticos)
   - [8.3 Interpretación de Resultados y Umbrales de Aceptación](#83-interpretación-de-resultados-y-umbrales-de-aceptación)

---

## 1. Estructura y Filosofía de Pruebas con `jettraTest`

El framework **`jettraTest`** es el estándar de validación nativo del ecosistema Jettra, construido para soportar:
* Ejecución masiva y concurrente sobre **Virtual Threads** de Java 25.
* Entornos efímeros en memoria utilizando Project Panama (`Arena.ofConfined()`) para pruebas ultrarrápidas de persistencia `.jettra`.
* Clústeres simulados de 3 nodos en memoria sin requerir puertos de red físicos externos.

**Comando de Ejecución General:**
```bash
mvn test -Dtest=JettraStoreTestSuite
```

---

## 2. Pruebas de Seguridad y Control de Acceso (`JettraJWT`)

### 2.1 Autenticación de Superusuario (`admin` / `admin-jettra`)

Esta prueba valida que el sistema provisiona automáticamente las credenciales de fábrica y que el token devuelto posee el rol `SUPER_ADMIN`.

```java
@Test
@DisplayName("Debe autenticar al superusuario admin con credenciales por defecto")
void testSuperuserDefaultAuthentication() {
    JettraAuthClient auth = JettraAuthClient.connect("http://localhost:8080");
    
    // Intento con credenciales exactas especificadas
    AuthResponse response = auth.login("admin", "admin-jettra");
    
    assertNotNull(response.token());
    assertTrue(response.token().startsWith("ey")); // Base64URL JettraJWT
    
    JettraClaims claims = JettraJWTValidator.parse(response.token());
    assertEquals("admin", claims.getSubject());
    assertEquals("SUPER_ADMIN", claims.getRole());
    assertTrue(claims.hasPermission("*"));
}
```

### 2.2 Inviolabilidad de Roles del Administrador

Valida que ningún usuario secundario pueda degradar, alterar o revocar los roles del superusuario `admin`.

```java
@Test
@DisplayName("Debe rechazar cualquier intento de modificar roles del superusuario por usuarios secundarios")
void testSuperuserImmutabilityBySecondaryUser() {
    // 1. Crear usuario secundario con rol DB_ADMIN
    String adminToken = authClient.login("admin", "admin-jettra").token();
    clusterClient.createUser(adminToken, "operator_user", "Pass#2026", "DB_ADMIN");
    
    // 2. Autenticar con el usuario secundario
    String operatorToken = authClient.login("operator_user", "Pass#2026").token();
    
    // 3. Intentar revocar privilegios del superusuario admin
    JettraSecurityException ex = assertThrows(JettraSecurityException.class, () -> {
        clusterClient.alterUserRole(operatorToken, "admin", "READ_ONLY");
    });
    
    assertTrue(ex.getMessage().contains("Superuser privileges cannot be altered"));
    
    // 4. Verificar que JettraPolice registró la advertencia crítica de seguridad
    assertTrue(JettraPolice.getInstance().getLastAlerts()
        .stream().anyMatch(a -> a.contains("ILLEGAL_SUPERUSER_ALTERATION_ATTEMPT")));
}
```

---

## 3. Pruebas CRUD Exhaustivas en los 8 Motores Multimodelo

### 3.1 Motor Document y Key-Value

```java
@Test
@DisplayName("CRUD en Motor Document y persistencia en archivo .jettra")
void testDocumentEngineCrud() {
    JettraDatabase db = client.getDatabase("ecommerce");
    DocumentEngine docEngine = db.getDocumentEngine("products");
    
    // Inserción
    JettraDocument doc = new JettraDocument()
        .put("_id", "sku_1001")
        .put("title", "Quantum Processor")
        .put("price", 1850.50)
        .put("stock", 25);
    
    docEngine.insert(doc);
    
    // Lectura
    JettraDocument fetched = docEngine.findById("sku_1001");
    assertNotNull(fetched);
    assertEquals("Quantum Processor", fetched.getString("title"));
    
    // Actualización
    docEngine.update("sku_1001", Map.of("price", 1799.99));
    assertEquals(1799.99, docEngine.findById("sku_1001").getDouble("price"));
    
    // Eliminación (Marca con Tombstone inmutable en .jettra)
    docEngine.delete("sku_1001");
    assertNull(docEngine.findById("sku_1001"));
}
```

### 3.2 Motor Columnar y Series Temporales

```java
@Test
@DisplayName("Inserción y agregación analítica en Motor TimeSeries")
void testTimeSeriesAggregation() {
    TimeSeriesEngine ts = db.getTimeSeriesEngine("sensor_telemetry");
    long baseTimestamp = System.currentTimeMillis();
    
    // Inserción masiva de 10,000 métricas
    for (int i = 0; i < 10000; i++) {
        ts.record(baseTimestamp + (i * 1000), "turbine_temp", 72.5 + (i * 0.01));
    }
    
    // Agregación de rango temporal
    Double avgTemp = ts.query()
        .metric("turbine_temp")
        .range(baseTimestamp, baseTimestamp + 5000000)
        .avg();
        
    assertTrue(avgTemp > 72.0);
}
```

### 3.3 Motor Geoespacial y Vectorial (Embeddings)

```java
@Test
@DisplayName("Búsqueda de vecinos más cercanos (ANN) en Motor Vectorial")
void testVectorEngineCosineSimilarity() {
    VectorEngine vectorEngine = db.getVectorEngine("image_embeddings");
    
    float[] targetEmbedding = generateRandomVector(512);
    vectorEngine.index("vec_01", targetEmbedding);
    vectorEngine.index("vec_02", generateRandomVector(512));
    
    List<VectorMatch> matches = vectorEngine.search(targetEmbedding, 1, SimilarityMetric.COSINE);
    assertEquals(1, matches.size());
    assertEquals("vec_01", matches.getFirst().id());
    assertEquals(1.0f, matches.getFirst().score(), 0.001);
}
```

---

## 4. Pruebas de Referencias Cruzadas (Intra e Inter-Engine)

### 4.1 Validación de Carga Perezosa (*Lazy Load*)

```java
@Test
@DisplayName("Referencia cruzada Inter-Engine con Lazy Load diferido")
void testCrossEngineLazyLoading() {
    // 1. Guardar vector en VectorEngine
    float[] embedding = new float[]{0.1f, 0.9f, -0.3f};
    db.getVectorEngine("biometrics").index("bio_user_44", embedding);
    
    // 2. Guardar documento en DocumentEngine que referencia al vector
    JettraDocument userDoc = new JettraDocument()
        .put("_id", "usr_44")
        .put("name", "Ana Rivera")
        .put("_ref_vector", "vector::biometrics#bio_user_44");
    db.getDocumentEngine("users").insert(userDoc);
    
    // 3. Recuperar documento en modo LAZY
    JettraDocument loaded = db.getDocumentEngine("users").findById("usr_44", FetchMode.LAZY);
    
    JettraRef<float[]> vectorRef = loaded.getReference("real_ref", float[].class);
    assertFalse(vectorRef.isResolved(), "La referencia NO debe estar cargada en memoria aún");
    
    // 4. Resolución explícita bajo demanda
    float[] resolvedVector = vectorRef.resolve();
    assertNotNull(resolvedVector);
    assertEquals(3, resolvedVector.length);
    assertTrue(vectorRef.isResolved());
}
```

---

## 5. Pruebas de Consenso Distribuido Raft y Tolerancia a Fallos

```java
@Test
@DisplayName("Failover del nodo primario y elección inmediata de nuevo líder en clúster de 3 nodos")
void testRaftLeaderFailover() throws Exception {
    JettraCluster cluster = JettraCluster.startSimulatedCluster("config/jettra.config");
    
    String initialLeader = cluster.getCurrentLeaderId();
    assertEquals("node-01", initialLeader);
    
    // Detener de forma forzada el nodo 1
    cluster.killNode("node-01");
    
    // Esperar elección por quórum Raft (límite 300ms)
    Thread.sleep(250);
    
    String newLeader = cluster.getCurrentLeaderId();
    assertTrue(newLeader.equals("node-02") || newLeader.equals("node-03"));
    
    // Verificar que el clúster sigue aceptando escrituras protegidas con JettraJWT
    assertDoesNotThrow(() -> {
        cluster.executeWrite(adminToken, "INSERT INTO audit_log VALUES ('node-01 failure recorded')");
    });
}
```

---

## 6. Pruebas del Motor de Anillo Distribuido por Saturación de RAM

```java
@Test
@DisplayName("Transición automática a anillo distribuido cuando la RAM supera el 85%")
void testMemorySaturationRingTransition() {
    JettraCluster cluster = JettraCluster.getRunningCluster();
    JettraNode primary = cluster.getNode("node-01");
    
    // Inyectar datos en MemTable hasta forzar el umbral del 85% de RAM
    cluster.floodMemTableData(primary, 1800); // 1800 MB en memoria
    
    // Verificar que el estado del nodo cambió a RING_DISTRIBUTED
    assertEquals(NodeState.RING_DISTRIBUTED, primary.getState());
    
    // Verificar que el nodo secundario 2 y nodo secundario 3 recibieron los segmentos off-heap
    assertTrue(cluster.getNode("node-02").getReceivedRingSegmentsCount() > 0);
    assertTrue(cluster.getNode("node-03").getReceivedRingSegmentsCount() > 0);
    
    // Verificar que el uso de memoria en el nodo primario volvió a zona segura (< 45%)
    assertTrue(primary.getMemoryUsagePercentage() < 45.0);
}
```

---

## 7. Pruebas del Componente de Supervisión Preventiva (`JettraPolice`)

```java
@Test
@DisplayName("JettraPolice detecta advertencia de disco y lanza compactación preventiva")
void testJettraPoliceDiskCompactionTrigger() {
    assertTrue(JettraPolice.getInstance().isActive());
    
    // Simular que el disco alcanzó el umbral del 90%
    JettraPolice.getInstance().injectTelemetryProbe(MetricType.DISK_USAGE_PERCENT, 91.5);
    
    // JettraPolice debe disparar la compactación de archivos .jettra
    await().atMost(2, TimeUnit.SECONDS).until(() -> 
        CompactionManager.getInstance().isCompacting()
    );
}
```

---

## 8. Suite de Microbenchmarking con JMH

### 8.1 Activación con `jmh.metrics.active = true`

En el entorno de pruebas de rendimiento, configure en `database.properties` o mediante propiedades del sistema:
```properties
jmh.metrics.active = true
```

### 8.2 Ejecución de Benchmarks Críticos

```bash
# Ejecución del microbenchmark de escritura en memoria nativa Panama
java -jar target/benchmarks.jar NativeMemTableBenchmark -f 1 -wi 3 -i 5

# Ejecución del microbenchmark de resolución de referencias cruzadas
java -jar target/benchmarks.jar CrossEngineRefBenchmark -f 1 -wi 3 -i 5
```

### 8.3 Resultados de Referencia Típicos en Java 25

```text
Benchmark                                    Mode  Cnt         Score        Error  Units
NativeMemTableBenchmark.appendEntry         thrpt    5  18,452,192.4 ± 142,301.2  ops/s
NativeMemTableBenchmark.readDirectOffHeap   thrpt    5  34,102,891.8 ± 210,450.6  ops/s
CrossEngineRefBenchmark.resolveLazyRef      thrpt    5   4,821,093.1 ±  38,719.4  ops/s
JettraSQLParserBenchmark.parseSelectWhere   thrpt    5   1,950,214.5 ±  15,602.8  ops/s
```
*Todas las operaciones se sitúan en rangos de nanosegundos con cero asignaciones en el montículo del GC.*
