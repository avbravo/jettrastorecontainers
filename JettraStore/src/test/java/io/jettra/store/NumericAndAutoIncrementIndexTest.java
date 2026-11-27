package io.jettra.store;

import io.jettra.store.core.JettraBucketSchema;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.core.JettraStoreConfig;
import io.jettra.store.engine.index.JettraIndexManager;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;
import io.jettra.test.core.JettraAssert;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class NumericAndAutoIncrementIndexTest {

    private JettraStoreConfig createIsolatedConfig(Path dir) {
        Properties props = new Properties();
        props.setProperty("jettra.storage.path", dir.toAbsolutePath().toString());
        props.setProperty("jettra.storage.mode", "OFF_HEAP");
        props.setProperty("cluster.multinode.active", "off");
        props.setProperty("cluster.node.id", "node-01");
        props.setProperty("cluster.node.role", "PRIMARY");
        return new JettraStoreConfig(props, new Properties(), props);
    }

    @Test
    @DisplayName("Debe crear índices AUTO_INCREMENT y NUMERIC, autogenerar secuencias, buscar por rango y persistir tras reinicio")
    public void testNumericAndAutoIncrementIndexesAndPersistence() throws Exception {
        Path tempDir = Files.createTempDirectory("jettra_rf01_idx_test_");
        JettraStoreConfig cfg = createIsolatedConfig(tempDir);

        try (JettraDatabase db = new JettraDatabase("ventas_db", cfg)) {
            List<JettraBucketSchema.AttributeRule> schema = List.of(
                    new JettraBucketSchema.AttributeRule("codigo", "Long", "AUTO_INCREMENT", "Código autoincremental"),
                    new JettraBucketSchema.AttributeRule("nombre", "String", "NOT_NULL", "Nombre del producto"),
                    new JettraBucketSchema.AttributeRule("precio", "Double", "POSITIVE", "Precio unitario")
            );
            db.createBucket("DOCUMENT", "productos", JettraBucketSchema.StructureType.DEFINED, schema);

            JettraIndexManager idxMgr = db.getIndexManager();
            var autoIdx = idxMgr.createIndex("productos", "idx_prod_codigo", "codigo", "AUTO_INCREMENT", true, db.getDocumentEngine("productos"));
            var numIdx = idxMgr.createIndex("productos", "idx_prod_precio", "precio", "NUMERIC", false, db.getDocumentEngine("productos"));

            JettraAssert.assertEquals("AUTO_INCREMENT", autoIdx.type(), "Tipo debe ser AUTO_INCREMENT");
            JettraAssert.assertTrue(autoIdx.unique(), "Índice AUTO_INCREMENT debe ser UNIQUE");
            JettraAssert.assertEquals("NUMERIC", numIdx.type(), "Tipo debe ser NUMERIC");

            // Inserciones sin 'codigo' -> autogenera 1, 2, 3
            String id1 = db.upsertBucketRecord("DOCUMENT", "productos", "p1", new LinkedHashMap<>(Map.of("nombre", "Teclado", "precio", 25.50)));
            String id2 = db.upsertBucketRecord("DOCUMENT", "productos", "p2", new LinkedHashMap<>(Map.of("nombre", "Monitor", "precio", 199.99)));
            String id3 = db.upsertBucketRecord("DOCUMENT", "productos", "p3", new LinkedHashMap<>(Map.of("nombre", "Mouse", "precio", 15.00)));

            JettraAssert.assertEquals(1L, ((Number) db.getDocumentEngine("productos").findById(id1).get("codigo")).longValue(), "Primer código debe ser 1");
            JettraAssert.assertEquals(2L, ((Number) db.getDocumentEngine("productos").findById(id2).get("codigo")).longValue(), "Segundo código debe ser 2");
            JettraAssert.assertEquals(3L, ((Number) db.getDocumentEngine("productos").findById(id3).get("codigo")).longValue(), "Tercer código debe ser 3");

            // Inserción con código explícito mayor (10) avanza la secuencia
            String id4 = db.upsertBucketRecord("DOCUMENT", "productos", "p4", new LinkedHashMap<>(Map.of("codigo", 10L, "nombre", "Laptop", "precio", 850.00)));
            JettraAssert.assertEquals(10L, ((Number) db.getDocumentEngine("productos").findById(id4).get("codigo")).longValue(), "Código explícito debe ser 10");

            // Siguiente autogenerado debe ser 11 (sin retrocesos ni colisiones)
            String id5 = db.upsertBucketRecord("DOCUMENT", "productos", "p5", new LinkedHashMap<>(Map.of("nombre", "Cable USB", "precio", 8.50)));
            JettraAssert.assertEquals(11L, ((Number) db.getDocumentEngine("productos").findById(id5).get("codigo")).longValue(), "Siguiente autogenerado debe ser 11");

            // Colisión de código duplicado debe rechazarse
            boolean collisionRejected = false;
            try {
                db.upsertBucketRecord("DOCUMENT", "productos", "p6", new LinkedHashMap<>(Map.of("codigo", 2L, "nombre", "Duplicado", "precio", 12.0)));
            } catch (IllegalArgumentException expected) {
                collisionRejected = true;
            }
            JettraAssert.assertTrue(collisionRejected, "Debe rechazar colisión en índice AUTO_INCREMENT");

            // Búsqueda exacta y por rango en índice NUMERIC
            Set<String> exact = idxMgr.findDocIds("productos", "precio", 25.50);
            JettraAssert.assertTrue(exact.contains("p1"), "Búsqueda exacta numérica debe encontrar p1");

            Set<String> range = idxMgr.findDocIdsByNumericRange("productos", "precio", 10.0, 50.0);
            JettraAssert.assertEquals(2, range.size(), "Rango [10.0, 50.0] debe encontrar p1 (25.50) y p3 (15.00)");
            JettraAssert.assertTrue(range.contains("p1") && range.contains("p3"), "Rango debe contener p1 y p3");
        }

        // Simular reinicio de nodo abriendo nueva instancia de JettraDatabase sobre el mismo directorio
        try (JettraDatabase reopened = new JettraDatabase("ventas_db", cfg)) {
            long currentSeq = reopened.getIndexManager().getAutoIncrementCurrentValue("productos", "codigo");
            JettraAssert.assertEquals(11L, currentSeq, "La secuencia persistida tras reinicio debe mantenerse en 11");

            String idAfterRestart = reopened.upsertBucketRecord("DOCUMENT", "productos", "p7",
                    new LinkedHashMap<>(Map.of("nombre", "Webcam", "precio", 49.99)));
            long nextCode = ((Number) reopened.getDocumentEngine("productos").findById(idAfterRestart).get("codigo")).longValue();
            JettraAssert.assertEquals(12L, nextCode, "Tras reinicio, el siguiente valor AUTO_INCREMENT debe ser 12 sin colisiones");
        }
    }
}
