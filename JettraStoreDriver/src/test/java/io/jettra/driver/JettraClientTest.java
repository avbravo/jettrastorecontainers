package io.jettra.driver;

import io.jettra.driver.config.JettraClientConfig;
import io.jettra.store.core.JettraDatabase;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import io.jettra.driver.listener.JettraPoliceEventListener;
import io.jettra.store.core.StreamResponse;
import io.jettra.store.police.JettraPoliceNotification;

import static io.jettra.test.core.JettraAssert.*;

public class JettraClientTest {

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
}
