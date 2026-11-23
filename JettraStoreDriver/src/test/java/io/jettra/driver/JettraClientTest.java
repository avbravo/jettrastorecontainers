package io.jettra.driver;

import io.jettra.driver.admin.BackupOptions;
import io.jettra.driver.admin.BackupResult;
import io.jettra.driver.admin.RestoreResult;
import io.jettra.driver.config.JettraClientConfig;
import io.jettra.driver.listener.JettraPoliceEventListener;
import io.jettra.driver.query.JettraResultSet;
import io.jettra.driver.query.JettraResults;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.core.StreamResponse;
import io.jettra.store.engine.index.JettraIndexManager;
import io.jettra.store.engine.models.JettraRef;
import io.jettra.store.police.JettraPoliceNotification;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.jettra.test.core.JettraAssert.*;

public class JettraClientTest {

    private static Path tempTestDir;
    private static Path tempClusterConfig;
    private static String prevClusterConfigProp;
    private static String prevStoragePathProp;
    private static String prevNodeRoleProp;

    @BeforeAll
    public static void setUpIsolatedDriverTestEnv() throws IOException {
        prevClusterConfigProp = System.getProperty("cluster.config.path");
        prevStoragePathProp = System.getProperty("jettra.storage.path");
        prevNodeRoleProp = System.getProperty("jettra.node.role");

        tempTestDir = Files.createTempDirectory("jettra_driver_test_");
        tempClusterConfig = tempTestDir.resolve("cluster-test_db.config");
        Files.writeString(tempClusterConfig,
            "cluster.name = jettra-driver-test\n"
            + "cluster.consensus.protocol = RAFT\n"
            + "cluster.node.1.id = node-01\n"
            + "cluster.node.1.role = PRIMARY\n"
            + "cluster.node.1.ip = 127.0.0.1\n"
            + "cluster.node.1.grpc.port = 59101\n"
            + "cluster.node.1.rest.port = 69101\n"
            + "cluster.node.2.id = node-02\n"
            + "cluster.node.2.role = SECONDARY\n"
            + "cluster.node.2.ip = 127.0.0.1\n"
            + "cluster.node.2.grpc.port = 59102\n"
            + "cluster.node.2.rest.port = 69102\n"
            + "cluster.node.3.id = node-03\n"
            + "cluster.node.3.role = SECONDARY\n"
            + "cluster.node.3.ip = 127.0.0.1\n"
            + "cluster.node.3.grpc.port = 59103\n"
            + "cluster.node.3.rest.port = 69103\n",
            StandardCharsets.UTF_8);

        System.setProperty("cluster.config.path", tempClusterConfig.toAbsolutePath().toString());
        System.setProperty("jettra.storage.path", tempTestDir.resolve("data").toAbsolutePath().toString());
        System.setProperty("jettra.node.role", "PRIMARY");
    }

    @AfterAll
    public static void tearDownIsolatedDriverTestEnv() {
        if (prevClusterConfigProp == null) System.clearProperty("cluster.config.path");
        else System.setProperty("cluster.config.path", prevClusterConfigProp);
        if (prevStoragePathProp == null) System.clearProperty("jettra.storage.path");
        else System.setProperty("jettra.storage.path", prevStoragePathProp);
        if (prevNodeRoleProp == null) System.clearProperty("jettra.node.role");
        else System.setProperty("jettra.node.role", prevNodeRoleProp);

        if (tempTestDir != null && Files.exists(tempTestDir)) {
            try (var walk = Files.walk(tempTestDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }

    public record TestOrderRecord(long customerId, String product, double amount, boolean active) {}
    public record TestAccountRecord(String accountId, String holder, double balance) {}

    @Test
    @DisplayName("Debe conectar cliente JettraClient y autenticar con JettraJWT")
    public void testClientConnectionAndAuth() {
        JettraClientConfig config = JettraClientConfig.builder()
            .addClusterNode("127.0.0.1", 9091)
            .credentials("admin", "admin-jettra")
            .build();

        try (JettraClient client = JettraClient.connect(config)) {
            assertNotNull(client.getSessionToken());
            assertTrue(client.getSessionToken().startsWith("JettraJWT."));

            // Probar ejecución SQL
            var result = client.sql("main_db", "SELECT * FROM products");
            assertNotNull(result);
            assertNotNull(result.columns());
        }
    }

    @Test
    @DisplayName("Debe ejecutar Backup y Restore programático a través del driver admin")
    public void testDriverBackupAndRestore() throws IOException {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase("analytics_db");
            db.getDocumentEngine("metrics").insert("m1", Map.of("cpu", 15.4));

            Path backupPath = Files.createTempFile("driver_bak", ".jettra_bak");
            var backupRes = client.admin().backupDatabase(db, backupPath);
            assertTrue(backupRes.success());
            assertTrue(backupRes.durationMs() >= 0);

            var restoreRes = client.admin().restoreDatabase(backupPath, db);
            assertTrue(restoreRes.success());
            Files.deleteIfExists(backupPath);
        }
    }

    @Test
    @DisplayName("Debe ejecutar cálculos, agregaciones, estadística, finanzas y vectores con JettraClient")
    public void testDriverCalcAndAggregations() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            // 1. Math
            assertEquals(5.0, client.sqrt(25.0), 0.001);
            assertEquals(120L, client.factorial(5));
            assertEquals(14.0, client.evalMath("sqrt(16) + 10"), 0.001);

            // 2. Stats
            var data = java.util.List.of(10.0, 20.0, 30.0, 40.0, 50.0);
            assertEquals(30.0, client.statsMean(data), 0.001);
            assertEquals(30.0, client.statsMedian(data), 0.001);
            assertEquals(20.0, client.statsIqr(data), 0.001);

            // 3. Finance
            double pmt = client.pmt(0.05 / 12.0, 360, 200000.0);
            assertTrue(pmt > 1000.0 && pmt < 1100.0);
            double cagr = client.cagr(100.0, 200.0, 3.0);
            assertTrue(cagr > 25.0 && cagr < 27.0);

            // 4. Vector
            float[] v1 = new float[]{1f, 0f, 0f};
            float[] v2 = new float[]{0f, 1f, 0f};
            assertEquals(0f, client.dotProduct(v1, v2), 0.001f);
            float[] cross = client.crossProduct(v1, v2);
            assertEquals(1f, cross[2], 0.001f);

            // 5. Aggregations on database
            JettraDatabase db = client.getDatabase("calc_driver_db");
            db.getDocumentEngine("sales").insert("s1", Map.of("cat", "X", "val", 100.0));
            db.getDocumentEngine("sales").insert("s2", Map.of("cat", "X", "val", 300.0));
            var agg = client.aggregateSum("calc_driver_db", "sales", "val", "cat");
            assertNotNull(agg);
            assertEquals(1, agg.totalGroups());
        }
    }

    @Test
    @DisplayName("Debe gestionar streaming por chunks y notificaciones de JettraPolice Sentinel")
    public void testSentinelNotificationAndChunkStreaming() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase("test_stream_db");
            var engine = db.getDocumentEngine("streaming_test_docs");
            for (int i = 0; i < 250; i++) {
                engine.insert("doc_" + i, Map.of("index", i, "data", "valor_" + i));
            }

            AtomicBoolean sentinelFired = new AtomicBoolean(false);
            AtomicReference<JettraPoliceNotification> notificationRef = new AtomicReference<>();

            JettraPoliceEventListener listener = n -> {
                sentinelFired.set(true);
                notificationRef.set(n);
            };
            client.addPoliceEventListener(listener);

            // Probar streamFindAll
            StreamResponse<Map<String, Object>> stream = client.streamFindAll("test_stream_db", "streaming_test_docs");
            assertNotNull(stream);
            assertTrue(stream.getSafeBatchSize() > 0);

            AtomicInteger chunkCount = new AtomicInteger(0);
            AtomicInteger recordCount = new AtomicInteger(0);

            stream.forEachChunk(chunk -> {
                chunkCount.incrementAndGet();
                recordCount.addAndGet(chunk.size());
                assertTrue(chunk.size() <= stream.getSafeBatchSize());
            });

            assertTrue(chunkCount.get() >= 1);
            assertTrue(recordCount.get() >= 100);

            // Probar findAll transparente
            var all = client.findAll("test_stream_db", "streaming_test_docs");
            assertNotNull(all);
            assertTrue(all.size() >= 100);

            // Probar notificación directa y desacoplada
            JettraPoliceNotification sample = JettraPoliceNotification.of(
                "TEST_SCAN", "streaming_test_docs", 250, 100, 45.0, 1024, "Prueba Sentinel");
            client.dispatchPoliceEvent(sample);

            assertTrue(sentinelFired.get());
            assertNotNull(notificationRef.get());
            assertEquals("TEST_SCAN", notificationRef.get().operation());
            assertEquals(100, notificationRef.get().safeBatchSize());

            client.removePoliceEventListener(listener);
        }
    }

    @Test
    @DisplayName("Debe configurar modo multinodo (cluster.multinode.active) en JettraClient")
    public void testClusterMultinodeActiveDriverSupport() {
        // 1. Modo Multinodo ON
        JettraClientConfig cfgOn = JettraClientConfig.builder()
            .addClusterNode("127.0.0.1", 9091)
            .credentials("admin", "admin-jettra")
            .clusterMultinodeActive("on")
            .build();
        assertTrue(cfgOn.isClusterMultinodeActive());
        assertEquals("on", cfgOn.getClusterMultinodeActive());

        try (JettraClient clientOn = JettraClient.connect(cfgOn)) {
            assertTrue(clientOn.isClusterMultinodeActive());
            assertEquals("on", clientOn.getClusterMultinodeActive());
            assertFalse(clientOn.getRingEngine().getPeers().isEmpty());
        }

        // 2. Modo Multinodo OFF (Standalone local)
        JettraClientConfig cfgOff = JettraClientConfig.builder()
            .addClusterNode("127.0.0.1", 9091)
            .credentials("admin", "admin-jettra")
            .clusterMultinodeActive("off")
            .build();
        assertFalse(cfgOff.isClusterMultinodeActive());
        assertEquals("off", cfgOff.getClusterMultinodeActive());

        try (JettraClient clientOff = JettraClient.connect(cfgOff)) {
            assertFalse(clientOff.isClusterMultinodeActive());
            assertEquals("off", clientOff.getClusterMultinodeActive());
            assertTrue(clientOff.getRingEngine().getPeers().isEmpty());
            assertFalse(clientOff.getRingEngine().isMultinodeActive());
        }
    }

    @Test
    @DisplayName("Debe ejecutar consultas fluidas SQL con JettraSqlCommand y JettraResultSet en Virtual Threads")
    public void testFluentSqlQueryExecution() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            var docEng = client.getDocumentEngine("main_db", "employees");
            docEng.insert("e1", Map.of("department", "Engineering", "salary", 80000.0, "active", true));
            docEng.insert("e2", Map.of("department", "Engineering", "salary", 95000.0, "active", true));
            docEng.insert("e3", Map.of("department", "Marketing", "salary", 60000.0, "active", true));

            // Ejecución sincrónica con cursor
            JettraResultSet rs = client.sql("SELECT department, salary, active FROM employees")
                .onDatabase("main_db")
                .execute();
            assertNotNull(rs);
            assertTrue(rs.size() >= 3);
            assertTrue(rs.next());
            assertNotNull(rs.getString("department"));
            assertTrue(rs.getDouble("salary") > 0);
            assertTrue(rs.getBoolean("active"));
            assertNotNull(rs.getCurrentRowAsMap());
            assertFalse(rs.asMaps().isEmpty());

            // Navegación por cursor
            rs.reset();
            assertEquals(-1, rs.getCursor());
            assertTrue(rs.next());
            assertEquals(0, rs.getCursor());

            // Ejecución asíncrona sobre Virtual Thread de Java 25
            JettraResultSet rsAsync = client.sql("SELECT * FROM employees")
                .onDatabase("main_db")
                .executeAsync().join();
            assertNotNull(rsAsync);
            assertTrue(rsAsync.size() >= 3);
        }
    }

    @Test
    @DisplayName("Debe ejecutar consultas fluidas tipadas con JettraFluentQuery y JettraResults")
    public void testFluentTypedLqlQuery() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            var recEng = client.getRecordsEngine("main_db", "orders", TestOrderRecord.class);
            recEng.persist("ord-1", new TestOrderRecord(5421L, "Laptop NVMe", 1499.99, true));
            recEng.persist("ord-2", new TestOrderRecord(5421L, "4K Monitor", 499.50, true));
            recEng.persist("ord-3", new TestOrderRecord(9999L, "Mouse USB", 25.00, false));

            // Consulta tipada sincrónica
            JettraResults<TestOrderRecord> orders = client.from("orders", TestOrderRecord.class)
                .useIndex("idx_customer_sparse")
                .filter(o -> o.customerId() == 5421L)
                .fetchMode(JettraRef.FetchMode.LAZY)
                .execute();

            assertNotNull(orders);
            assertEquals(2, orders.size());
            assertFalse(orders.isEmpty());
            assertNotNull(orders.firstOrNull());
            assertEquals(5421L, orders.firstOrNull().customerId());
            assertTrue(orders.durationMs() >= 0);
            assertNotNull(orders.summary());

            // Consulta tipada asíncrona sobre Virtual Thread
            JettraResults<TestOrderRecord> ordersAsync = client.from("orders", TestOrderRecord.class)
                .filter(o -> o.customerId() == 5421L)
                .executeAsync().join();

            assertEquals(2, ordersAsync.size());
        }
    }

    @Test
    @DisplayName("Debe ejecutar BackupOptions y RestoreResult avanzados en JettraAdminClient")
    public void testAdminBackupOptionsAndResults() throws IOException {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase("corporate_db");
            db.getDocumentEngine("contracts").insert("c1", Map.of("value", 500000.0, "status", "SIGNED"));

            Path backupDir = Files.createTempDirectory("driver_backup_test");
            BackupOptions options = BackupOptions.builder()
                .targetDirectory(backupDir.toString())
                .compress(false)
                .includeSecondaryIndexes(true)
                .build();

            BackupResult backupRes = client.admin().backupDatabase("corporate_db", options);
            assertTrue(backupRes.isSuccess());
            assertTrue(backupRes.success());
            assertNotNull(backupRes.snapshotPath());
            assertTrue(backupRes.durationMs() >= 0);
            assertTrue(Files.exists(Path.of(backupRes.snapshotPath())));

            RestoreResult restoreRes = client.admin().restoreDatabase("corporate_db", backupRes.snapshotPath());
            assertTrue(restoreRes.isSuccess());
            assertTrue(restoreRes.success());
            assertTrue(restoreRes.durationMs() >= 0);

            Files.deleteIfExists(Path.of(backupRes.snapshotPath()));
            Files.deleteIfExists(backupDir);
        }
    }

    @Test
    @DisplayName("Debe interactuar directamente con los 8 motores multimodelo y referencias JettraRef")
    public void testMultiModelEnginesAndJettraRef() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            // 1. DocumentEngine
            var doc = client.getDocumentEngine("cat_docs");
            doc.insert("d1", Map.of("title", "Doc 1"));
            assertNotNull(doc.findById("d1"));

            // 2. KeyValueEngine
            var kv = client.getKeyValueEngine("sessions");
            kv.put("sess-1", "user-admin".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertNotNull(kv.get("sess-1"));

            // 3. VectorEngine
            var vec = client.getVectorEngine("embeddings", 3);
            vec.index("vec-1", new float[]{1.0f, 0.0f, 0.5f});
            assertEquals(1, vec.size());

            // 4. GraphEngine
            var graph = client.getGraphEngine("network");
            graph.addVertex("A");
            graph.addVertex("B");
            graph.addEdge("A", "B", "CONNECTS", Map.of("weight", 1.0));
            assertTrue(graph.size() >= 2);

            // 5. TimeSeriesEngine
            var ts = client.getTimeSeriesEngine("metrics_ts");
            ts.record(1700000000L, 42.5);
            assertEquals(1, ts.size());

            // 6. GeospatialEngine
            var geo = client.getGeospatialEngine("pois");
            geo.insertPoint("poi-1", 8.98, -79.52);
            assertNotNull(geo.getAllPoints().get("poi-1"));

            // 7. ColumnarEngine
            var col = client.getColumnarEngine("metrics_col");
            col.appendRow(Map.of("val", 10.5));
            assertEquals(1, col.size());

            // 8. RecordsEngine
            var rec = client.getRecordsEngine("accounts", TestAccountRecord.class);
            rec.persist("acc-1", new TestAccountRecord("acc-1", "John", 1500.0));
            assertNotNull(rec.find("acc-1"));

            // JettraRef (Lazy & Eager)
            JettraRef<Map<String, Object>> refLazy = client.createLazyRef("cat_docs", "d1", () -> client.getDocumentEngine("cat_docs").findById("d1"));
            assertFalse(refLazy.isResolved());
            var resolved = refLazy.resolve();
            assertTrue(refLazy.isResolved());
            assertNotNull(resolved);
            assertEquals("Doc 1", resolved.get("title"));

            JettraRef<Map<String, Object>> refEager = client.createEagerRef("cat_docs", "d1", () -> client.getDocumentEngine("cat_docs").findById("d1"));
            assertTrue(refEager.isResolved());
            assertNotNull(refEager.resolve());
        }
    }

    @Test
    @DisplayName("Debe gestionar índices secundarios, registro dinámico de nodos y topología")
    public void testIndexManagementAndClusterOperations() {
        try (JettraClient client = JettraClient.connect("127.0.0.1", 9091, "admin", "admin-jettra")) {
            var docEngine = client.getDocumentEngine("products_idx");
            docEngine.insert("p1", Map.of("category", "Electronics", "price", 99.99));
            docEngine.insert("p2", Map.of("category", "Electronics", "price", 149.99));
            docEngine.insert("p3", Map.of("category", "Books", "price", 19.99));

            // Crear índice secundario
            JettraIndexManager.IndexInfo idxInfo = client.createIndex("products_idx", "idx_category", "category", "HASH", false);
            assertNotNull(idxInfo);
            assertEquals("idx_category", idxInfo.name());

            // Listar índices
            List<JettraIndexManager.IndexInfo> indexes = client.listIndexes();
            assertNotNull(indexes);
            assertTrue(indexes.stream().anyMatch(i -> i.name().equals("idx_category")));

            // Eliminar índice
            boolean dropped = client.dropIndex("idx_category");
            assertTrue(dropped);

            // Registro dinámico de nodo par en DynamicRingEngine
            boolean reg = client.registerDynamicPeer("peer-dynamic-1", "127.0.0.1", 9095, "SECONDARY");
            assertTrue(reg);
            assertNotNull(client.getRingEngine().getPeer("peer-dynamic-1"));

            // Eliminación de nodo par
            boolean rem = client.removeClusterPeer("peer-dynamic-1");
            assertTrue(rem);

            // Replicación de creación de motor
            boolean engRep = client.replicateCreateEngine("main_db", "replicated_docs", "DOCUMENT");
            assertTrue(engRep);

            // Verificación de salud del clúster
            boolean health = client.checkClusterHealth();
            assertTrue(health);
        }
    }

    @Test
    @DisplayName("Debe detener un nodo dinámico (node-04) real vía stopClusterNode (Raft/REST) y verificar cierre de puertos")
    public void testStopDynamicNode04RealShutdown() throws Exception {
        int restPort;
        int grpcPort;
        try (var s1 = new java.net.ServerSocket(0); var s2 = new java.net.ServerSocket(0)) {
            restPort = s1.getLocalPort();
            grpcPort = s2.getLocalPort();
        }
        java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("jettra-node04-test-");
        java.util.Properties p = new java.util.Properties();
        p.setProperty("node.id", "node-04");
        p.setProperty("node.role", "SECONDARY");
        p.setProperty("server.rest.port", String.valueOf(restPort));
        p.setProperty("server.grpc.port", String.valueOf(grpcPort));
        p.setProperty("cluster.multinode.active", "true");
        p.setProperty("storage.data.dir", tmpDir.toAbsolutePath().toString());
        p.setProperty("cluster.endpoints", "127.0.0.1:" + grpcPort);
        io.jettra.store.core.JettraStoreConfig node04Cfg = new io.jettra.store.core.JettraStoreConfig(p);
        io.jettra.store.JettraStoreServer node04Server = new io.jettra.store.JettraStoreServer(node04Cfg);
        node04Server.start();
        try (JettraClient client = JettraClient.connect("127.0.0.1", grpcPort, "admin", "admin-jettra")) {
            client.setActiveRole("admin");
            client.registerDynamicPeer("node-04", "127.0.0.1", grpcPort, "SECONDARY");

            var stopRes = client.stopClusterNode("node-04");
            assertTrue(stopRes.success(), "stopClusterNode debe reportar éxito: " + stopRes.message());
            assertTrue(stopRes.verifiedState(), "stopClusterNode debe verificar que los puertos de node-04 cerraron: " + stopRes.message());
        } finally {
            try { node04Server.stop(); } catch (Exception ignored) {}
        }
    }
}
