package io.jettra.store;

import io.jettra.store.core.JettraDatabase;
import io.jettra.store.core.JettraId;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;
import io.jettra.test.core.JettraAssert;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

@DisplayName("JettraId: Estructura Binaria (16B/32-hex), Concurrencia Virtual Threads e Integridad en JettraStore")
public class JettraIdTest {

    @Test
    @DisplayName("1. Estructura binaria big-endian (4B timestamp, 5B proceso, 4B hash DB:bucket, 3B contador) y parseo")
    public void testBinaryStructureAndParsing() {
        long fixedEpoch = 1_763_900_000L;
        JettraId id1 = JettraId.generate("ventas_db", "Facturas", fixedEpoch);
        JettraId id2 = JettraId.generate("ventas_db", "Facturas", fixedEpoch);
        JettraId idOtherScope = JettraId.generate("ventas_db", "Clientes", fixedEpoch);

        JettraAssert.assertEquals(16, id1.toBytes().length, "JettraId debe tener exactamente 16 bytes");
        JettraAssert.assertEquals(32, id1.toHexString().length(), "JettraId hex debe tener exactamente 32 caracteres");
        JettraAssert.assertTrue(JettraId.isValidHex(id1.toHexString()), "La representación hex debe ser válida");
        JettraAssert.assertEquals(fixedEpoch, id1.timestampSeconds(), "Bytes 0..3 deben almacenar el timestamp Unix");
        JettraAssert.assertEquals(Instant.ofEpochSecond(fixedEpoch), id1.createdAt(), "createdAt() debe coincidir con epochSeconds");
        JettraAssert.assertEquals(id1.processMachineId(), id2.processMachineId(), "El ID de proceso/máquina (5B) debe ser constante en la JVM");
        JettraAssert.assertEquals(JettraId.computeScopeHash("ventas_db", "Facturas"), id1.databaseBucketHash(),
                "Bytes 9..12 deben corresponder al hash determinista DB:bucket");
        JettraAssert.assertEquals(id1.databaseBucketHash(), id2.databaseBucketHash(), "Mismo DB+bucket debe producir el mismo hash");
        JettraAssert.assertFalse(id1.databaseBucketHash() == idOtherScope.databaseBucketHash(),
                "Distintos buckets deben producir distinto hash DB:bucket");
        JettraAssert.assertEquals((id1.counter() + 1) & 0x00FFFFFF, id2.counter(),
                "El contador de 24 bits debe incrementarse secuencialmente");

        JettraId roundTrip = JettraId.fromHex(id1.toHexString());
        JettraAssert.assertEquals(id1, roundTrip, "fromHex(toHexString()) debe reconstruir el mismo JettraId");
        JettraAssert.assertEquals(id1, JettraId.fromBytes(id1.toBytes()), "fromBytes(toBytes()) debe reconstruir el mismo JettraId");
        JettraAssert.assertFalse(JettraId.isValidHex("not-a-valid-hex"), "Hex inválido debe rechazarse");
    }

    @Test
    @DisplayName("2. Unicidad sin colisiones bajo concurrencia masiva con Virtual Threads")
    public void testVirtualThreadsConcurrencyNoCollisions() throws Exception {
        int total = 2_000;
        Set<String> generated = ConcurrentHashMap.newKeySet();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < total; i++) {
                executor.submit(() -> generated.add(JettraId.generate("conc_test_db", "Eventos").toHexString()));
            }
        }
        JettraAssert.assertEquals(total, generated.size(), "Todos los JettraId generados concurrentemente deben ser únicos");
    }

    @Test
    @DisplayName("3. Generación automática, IDs personalizados y rechazo de duplicados en JettraDatabase y motores")
    public void testAutoGenerationCustomIdAndDuplicateRejectionInStore() throws Exception {
        Path tempDir = Files.createTempDirectory("jettra_id_store_test_");
        Properties props = new Properties();
        props.setProperty("cluster.node.id", "node-01");
        props.setProperty("cluster.node.role", "PRIMARY");
        props.setProperty("cluster.multinode.active", "off");
        props.setProperty("jettra.storage.mode", "JVM_RAM");
        props.setProperty("jettra.storage.path", tempDir.toAbsolutePath().toString());
        JettraStoreConfig cfg = new JettraStoreConfig(props);

        try (JettraDatabase db = new JettraDatabase("jettraid_test_db", cfg)) {
            // 1. Inserción sin ID -> autogenera JettraId y lo guarda en _id
            String autoId = db.insertBucketRecord("DOCUMENT", "Ordenes", null, Map.of("cliente", "ACME", "total", 100.0));
            JettraAssert.assertTrue(JettraId.isValidHex(autoId), "El ID autogenerado debe ser un JettraId hex válido");
            JettraAssert.assertEquals(JettraId.computeScopeHash("jettraid_test_db", "Ordenes"),
                    JettraId.fromHex(autoId).databaseBucketHash(), "El JettraId debe codificar el hash de DB y bucket");
            var stored = db.getDocumentEngine("Ordenes").findById(autoId);
            JettraAssert.assertNotNull(stored, "El documento debe poder recuperarse por su JettraId");
            JettraAssert.assertEquals(autoId, stored.get("_id"), "El _id interno debe coincidir con el JettraId retornado");

            // 2. ID personalizado respetado y duplicado rechazado con IllegalStateException
            String customId = db.insertBucketRecord("DOCUMENT", "Ordenes", "ORD-CUSTOM-1", Map.of("cliente", "Beta"));
            JettraAssert.assertEquals("ORD-CUSTOM-1", customId, "Debe respetar el ID personalizado");
            boolean dupRejected = false;
            try {
                db.insertBucketRecord("DOCUMENT", "Ordenes", "ORD-CUSTOM-1", Map.of("cliente", "Dup"));
            } catch (IllegalStateException ex) {
                dupRejected = ex.getMessage().contains("PRIMARY KEY VIOLATION");
            }
            JettraAssert.assertTrue(dupRejected, "Debe rechazar inserción duplicada con PRIMARY KEY VIOLATION");

            // 3. KeyValueEngine autogenera JettraId y rechaza duplicados en putStrict
            String kvId = db.getKeyValueEngine("CacheKV").put("valor".getBytes(StandardCharsets.UTF_8));
            JettraAssert.assertTrue(JettraId.isValidHex(kvId), "KeyValueEngine.put(byte[]) debe generar JettraId");
            boolean kvDup = false;
            try {
                db.getKeyValueEngine("CacheKV").putStrict(kvId, "otro".getBytes(StandardCharsets.UTF_8));
            } catch (IllegalStateException ex) {
                kvDup = true;
            }
            JettraAssert.assertTrue(kvDup, "KeyValueEngine.putStrict debe rechazar claves duplicadas");
        } finally {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }
}
