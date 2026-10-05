package com.jettra.memory;

import com.jettra.memory.api.JettraMemoryBootstrap;
import com.jettra.memory.api.JettraMemoryConfig;
import com.jettra.memory.api.JettraMemoryEngine;
import com.jettra.memory.cluster.ClusterNode;
import com.jettra.memory.cluster.NodeCoordinator;
import com.jettra.memory.collections.JettraOffHeapMap;
import com.jettra.memory.engine.DiskStorageEngine;
import com.jettra.memory.engine.StorageMetrics;
import com.jettra.memory.gc.CompactionResult;
import com.jettra.memory.integration.JettraStoreConnector;
import com.jettra.memory.serializer.JettraBinarySerializer;
import com.jettra.memory.serializer.JettraStoreBinaryRecord;
import io.jettra.ee.serialization.JettraSerialization;
import io.jettra.ee.serialization.JettraSerializedRecord;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.NotRequiresRunningServer;
import io.jettra.test.annotation.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static io.jettra.test.core.JettraAssert.*;

@NotRequiresRunningServer
public class JettraMemoryCoreTest {

    @Test
    @DisplayName("Debe ejecutar operaciones CRUD directas en disco con generación de archivos .idx y .jettra")
    public void testBasicPutGetDeleteAndTombstone() throws Exception {
        Path tempDir = Files.createTempDirectory("jettra_test_crud_");
        try (JettraMemoryEngine engine = JettraMemoryBootstrap.standalone(tempDir)) {
            String key = "customer:1001";
            byte[] payload = "{\"name\":\"Carlos\",\"role\":\"Admin\"}".getBytes(StandardCharsets.UTF_8);

            // 1. Escritura (PUT)
            engine.put(key, payload);
            assertTrue(engine.containsKey(key));
            assertEquals(1, engine.size());

            // 2. Lectura directa fuera del Heap (GET)
            byte[] retrieved = engine.get(key);
            assertNotNull(retrieved);
            assertArrayEquals(payload, retrieved);

            // 3. Forzar volcado a disco y verificación de archivos físicos
            engine.flush();
            Path dataFile = engine.getStorageEngine().getDataPath();
            Path indexFile = engine.getStorageEngine().getIndexPath();
            assertTrue(Files.exists(dataFile));
            assertTrue(Files.exists(indexFile));
            assertTrue(Files.size(dataFile) > DiskStorageEngine.FILE_HEADER_SIZE);
            assertTrue(Files.size(indexFile) > 0);

            // 4. Eliminación lógica con Tombstone (DELETE)
            boolean deleted = engine.delete(key);
            assertTrue(deleted);
            assertNull(engine.get(key));
            assertFalse(engine.containsKey(key));
            assertEquals(0, engine.size());

            // El archivo contiene el registro activo + el tombstone
            StorageMetrics metrics = engine.getMetrics();
            assertTrue(metrics.deadBytes() > 0);
        } finally {
            deleteDirectoryRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("Debe garantizar cero impacto en el Heap de la JVM al almacenar datos masivos off-heap")
    public void testZeroHeapImpactAndOffHeapDirectMemory() throws Exception {
        Path tempDir = Files.createTempDirectory("jettra_test_zero_heap_");
        try (JettraMemoryEngine engine = JettraMemoryBootstrap.standalone(tempDir)) {
            System.gc();
            Thread.sleep(50);
            long initialHeapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            // Escribir 2,000 registros de 1 KB cada uno = ~2 MB persistidos directamente a disco
            byte[] sample1Kb = new byte[1024];
            Arrays.fill(sample1Kb, (byte) 0x7E);

            int recordCount = 2000;
            for (int i = 0; i < recordCount; i++) {
                engine.put("device_metric_" + i, sample1Kb);
            }

            assertEquals(recordCount, engine.size());
            StorageMetrics metrics = engine.getMetrics();
            assertTrue(metrics.totalAllocatedBytes() >= (recordCount * 1024L));

            System.gc();
            Thread.sleep(50);
            long finalHeapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            // Los datos no residen en el Heap; el delta de Heap ocupado por los datos brutos es prácticamente nulo
            long heapGrowth = Math.max(0, finalHeapUsed - initialHeapUsed);
            assertTrue(heapGrowth < (recordCount * 1024L), "El Heap de la JVM no debe absorber los payloads almacenados");

            // Validar recuperación selectiva sin impacto
            byte[] readBack = engine.get("device_metric_500");
            assertNotNull(readBack);
            assertEquals(1024, readBack.length);
            assertEquals((byte) 0x7E, readBack[0]);
        } finally {
            deleteDirectoryRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("Debe ejecutar el recolector de basura personalizado (JettraGarbageCollector) y compactar disco")
    public void testJettraGarbageCollectorAndCompaction() throws Exception {
        Path tempDir = Files.createTempDirectory("jettra_test_gc_");
        try (JettraMemoryEngine engine = JettraMemoryBootstrap.standalone(tempDir)) {
            // Escribir y sobrescribir múltiples claves para generar fragmentación y espacio muerto
            for (int version = 1; version <= 5; version++) {
                for (int k = 0; k < 100; k++) {
                    engine.put("sensor_" + k, ("value_version_" + version).getBytes(StandardCharsets.UTF_8));
                }
            }

            // Eliminar la mitad de los sensores para generar Tombstones adicionales
            for (int k = 0; k < 50; k++) {
                engine.delete("sensor_" + k);
            }

            StorageMetrics preGcMetrics = engine.getMetrics();
            long initialDiskSize = Files.size(engine.getStorageEngine().getDataPath());
            assertTrue(preGcMetrics.deadBytes() > 0);
            assertTrue(preGcMetrics.fragmentationRatio() > 0.40); // Más de 40% de espacio muerto

            // Ejecución manual de la compactación con JettraGarbageCollector
            CompactionResult result = engine.compact();
            assertNotNull(result);
            assertTrue(result.reclaimedBytes() > 0);
            assertTrue(result.finalSizeBytes() < initialDiskSize);

            // Verificar métricas posteriores a la compactación
            StorageMetrics postGcMetrics = engine.getMetrics();
            assertEquals(50, engine.size()); // Quedaron exactamente 50 sensores vivos
            assertEquals(0, postGcMetrics.deadBytes()); // Espacio muerto eliminado 100%

            // Validar que los datos supervivientes están íntegros
            for (int k = 50; k < 100; k++) {
                byte[] data = engine.get("sensor_" + k);
                assertNotNull(data);
                assertEquals("value_version_5", new String(data, StandardCharsets.UTF_8));
            }
            // Los eliminados siguen retornando null
            for (int k = 0; k < 50; k++) {
                assertNull(engine.get("sensor_" + k));
            }
        } finally {
            deleteDirectoryRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("Debe coordinar y sincronizar operaciones en un clúster distribuido de tres nodos con quórum (2 de 3)")
    public void testThreeNodeClusterReplicationAndQuorum() throws Exception {
        Path dir1 = Files.createTempDirectory("jettra_cluster_node1_");
        Path dir2 = Files.createTempDirectory("jettra_cluster_node2_");
        Path dir3 = Files.createTempDirectory("jettra_cluster_node3_");

        try {
            // Definición de los 3 nodos del clúster
            ClusterNode node1Meta = new ClusterNode("node-1", "127.0.0.1", 9001, ClusterNode.Role.PRIMARY);
            ClusterNode node2Meta = new ClusterNode("node-2", "127.0.0.1", 9002, ClusterNode.Role.SECONDARY);
            ClusterNode node3Meta = new ClusterNode("node-3", "127.0.0.1", 9003, ClusterNode.Role.SECONDARY);

            // Instanciación de los 3 nodos
            JettraMemoryConfig cfg1 = JettraMemoryConfig.builder()
                    .nodeId("node-1").storageDirectory(dir1).storeName("store_n1")
                    .clusterMode(true).addPeer(node2Meta).addPeer(node3Meta).build();

            JettraMemoryConfig cfg2 = JettraMemoryConfig.builder()
                    .nodeId("node-2").storageDirectory(dir2).storeName("store_n2")
                    .clusterMode(true).addPeer(node1Meta).addPeer(node3Meta).build();

            JettraMemoryConfig cfg3 = JettraMemoryConfig.builder()
                    .nodeId("node-3").storageDirectory(dir3).storeName("store_n3")
                    .clusterMode(true).addPeer(node1Meta).addPeer(node2Meta).build();

            try (JettraMemoryEngine engine1 = new JettraMemoryEngine(cfg1);
                 JettraMemoryEngine engine2 = new JettraMemoryEngine(cfg2);
                 JettraMemoryEngine engine3 = new JettraMemoryEngine(cfg3)) {

                // Conexión directa en memoria de los 3 nodos
                engine1.getReplicationManager().linkDirectPeerEngine("node-2", engine2.getStorageEngine());
                engine1.getReplicationManager().linkDirectPeerEngine("node-3", engine3.getStorageEngine());

                // Validación de topología de 3 nodos y quórum inicial
                NodeCoordinator coordinator = engine1.getNodeCoordinator();
                assertTrue(coordinator.isConfiguredAsThreeNodeCluster());
                assertTrue(coordinator.hasQuorum());
                assertEquals(3, coordinator.getOnlineNodesCount());

                // Escritura en el nodo primario con replicación a los 2 pares
                String clusterKey = "account:balance:9921";
                byte[] balancePayload = "{\"balance\": 75000.50}".getBytes(StandardCharsets.UTF_8);
                engine1.put(clusterKey, balancePayload);

                // Comprobación de consistencia fuerte en los 3 nodos
                assertArrayEquals(balancePayload, engine1.get(clusterKey));
                assertArrayEquals(balancePayload, engine2.get(clusterKey));
                assertArrayEquals(balancePayload, engine3.get(clusterKey));

                // Replicación de eliminación con quórum
                engine1.delete(clusterKey);
                assertNull(engine1.get(clusterKey));
                assertNull(engine2.get(clusterKey));
                assertNull(engine3.get(clusterKey));
            }
        } finally {
            deleteDirectoryRecursively(dir1);
            deleteDirectoryRecursively(dir2);
            deleteDirectoryRecursively(dir3);
        }
    }

    @Test
    @DisplayName("Debe integrar vistas de colecciones nativas con JettraCollections mediante JettraOffHeapMap")
    public void testJettraCollectionsOffHeapMapIntegration() throws Exception {
        Path tempDir = Files.createTempDirectory("jettra_test_collections_");
        try (JettraMemoryEngine engine = JettraMemoryBootstrap.standalone(tempDir)) {
            Map<String, String> offHeapMap = engine.getOffHeapMap("config_registry");

            // Operaciones como colección estándar Map
            offHeapMap.put("jwt.timeout", "3600");
            offHeapMap.put("cluster.heartbeat", "500");
            offHeapMap.put("db.max_connections", "256");

            assertEquals(3, offHeapMap.size());
            assertTrue(offHeapMap.containsKey("jwt.timeout"));
            assertEquals("3600", offHeapMap.get("jwt.timeout"));
            assertEquals("500", offHeapMap.get("cluster.heartbeat"));

            // Eliminación a través del Map
            String removed = offHeapMap.remove("cluster.heartbeat");
            assertEquals("500", removed);
            assertEquals(2, offHeapMap.size());
            assertFalse(offHeapMap.containsKey("cluster.heartbeat"));

            // Persistencia directa confirmada en el storage engine subyacente
            assertTrue(engine.containsKey("config_registry:jwt.timeout"));
        } finally {
            deleteDirectoryRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("Debe asegurar plena compatibilidad binaria con objetos de JettraStore y JettraEE")
    public void testJettraStoreAndEESerializationCompatibility() throws Exception {
        Path tempDir = Files.createTempDirectory("jettra_test_compatibility_");
        try (JettraMemoryEngine engine = JettraMemoryBootstrap.standalone(tempDir)) {
            // 1. Compatibilidad binaria con JettraStore
            JettraStoreConnector storeConnector = engine.getStoreConnector();
            byte engineId = 2; // DocumentEngine id
            String docKey = "doc_alpha_99";
            byte[] jsonContent = "{\"title\":\"Jettra Paper\",\"status\":\"PUBLISHED\"}".getBytes(StandardCharsets.UTF_8);

            storeConnector.putRecord(engineId, docKey, jsonContent);

            JettraStoreBinaryRecord retrievedStoreRecord = storeConnector.getRecord(engineId, docKey);
            assertNotNull(retrievedStoreRecord);
            assertEquals(engineId, retrievedStoreRecord.engineId());
            assertEquals(docKey, retrievedStoreRecord.key());
            assertArrayEquals(jsonContent, retrievedStoreRecord.payload());

            // 2. Compatibilidad binaria con registros JettraEE y CompactBinaryHeader
            String eeRecordId = "ee_transaction_45";
            byte[] eePayload = "TX_PAYLOAD_APPROVED".getBytes(StandardCharsets.UTF_8);
            byte[] eeSerialized = JettraBinarySerializer.serializeJettraEE(eeRecordId, 1, System.currentTimeMillis(), eePayload);

            engine.put(eeRecordId, eeSerialized);

            byte[] retrievedEE = engine.get(eeRecordId);
            assertNotNull(retrievedEE);
            assertTrue(JettraSerialization.isJettraBinary(retrievedEE));

            JettraSerializedRecord deserializedEE = JettraBinarySerializer.deserializeJettraEE(eeRecordId, retrievedEE);
            assertNotNull(deserializedEE);
            assertEquals(eeRecordId, deserializedEE.recordId());
            assertArrayEquals(eePayload, deserializedEE.payload());
        } finally {
            deleteDirectoryRecursively(tempDir);
        }
    }

    private static void deleteDirectoryRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
