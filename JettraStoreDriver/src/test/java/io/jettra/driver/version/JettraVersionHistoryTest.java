package io.jettra.driver.version;

import io.jettra.driver.JettraClient;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static io.jettra.test.core.JettraAssert.*;

public class JettraVersionHistoryTest {

    private static Path createIsolatedStorage() throws Exception {
        return Files.createTempDirectory("jettra_rf18_versions_test_");
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("RF-18: Registro automático de versiones en CREATE, UPDATE y DELETE y consulta de historial")
    public void testCreateUpdateDeleteAndHistory() throws Exception {
        Path tempStorage = createIsolatedStorage();
        String prevStorage = System.getProperty("jettra.storage.path");
        System.setProperty("jettra.storage.path", tempStorage.toAbsolutePath().toString());
        String dbName = "rf18_driver_hist_" + System.nanoTime();
        String bucket = "productos";
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra")) {
            client.createDatabase(dbName);
            client.createBucket(dbName, bucket, "DOCUMENT");

            // 1. CREATE -> v1
            Map<String, Object> docV1 = new LinkedHashMap<>();
            docV1.put("nombre", "Teclado Mecánico");
            docV1.put("precio", 85);
            String id = client.insertDocumentReturningId(dbName, bucket, "prod-01", docV1);
            assertEquals("prod-01", id, "El ID asignado debe ser prod-01");

            // 2. UPDATE -> v2
            Map<String, Object> updated2 = client.updateDocument(dbName, bucket, id, Map.of("precio", 99));
            assertNotNull(updated2, "updateDocument debe retornar el documento actualizado");

            // 3. UPDATE -> v3
            Map<String, Object> updated3 = client.updateDocument(dbName, bucket, id, Map.of("nombre", "Teclado Pro", "precio", 120));
            assertNotNull(updated3, "La segunda actualización debe retornar el documento");

            // 4. DELETE -> v4
            boolean deleted = client.deleteDocument(dbName, bucket, id);
            assertTrue(deleted, "deleteDocument debe eliminar el documento");

            // 5. Verificar historial completo
            List<RecordVersion> history = client.getVersionHistory(dbName, bucket, id);
            assertEquals(4, history.size(), "Debe registrar 4 versiones (CREATE, UPDATE, UPDATE, DELETE)");
            assertEquals(1L, history.get(0).versionNumber(), "Primera versión debe ser 1");
            assertEquals(VersionOperation.CREATE, history.get(0).operation(), "v1 debe ser CREATE");
            assertEquals(85, ((Number) history.get(0).snapshot().get("precio")).intValue(), "Snapshot v1 inmutable");

            assertEquals(2L, history.get(1).versionNumber(), "Segunda versión debe ser 2");
            assertEquals(VersionOperation.UPDATE, history.get(1).operation(), "v2 debe ser UPDATE");
            assertEquals(99, ((Number) history.get(1).snapshot().get("precio")).intValue(), "Snapshot v2 debe tener precio 99");

            assertEquals(3L, history.get(2).versionNumber(), "Tercera versión debe ser 3");
            assertEquals(VersionOperation.UPDATE, history.get(2).operation(), "v3 debe ser UPDATE");
            assertEquals("Teclado Pro", history.get(2).snapshot().get("nombre"), "Snapshot v3 debe tener nombre actualizado");

            assertEquals(4L, history.get(3).versionNumber(), "Cuarta versión debe ser 4");
            assertEquals(VersionOperation.DELETE, history.get(3).operation(), "v4 debe ser DELETE");
            assertNotNull(history.get(3).timestamp(), "Debe registrar timestamp ISO-8601");

            // 6. Consulta puntual de una versión específica
            Optional<RecordVersion> v2Opt = client.getVersion(dbName, bucket, id, 2L);
            assertTrue(v2Opt.isPresent(), "Debe encontrar la versión v2");
            assertEquals("prod-01@v2", v2Opt.get().versionId(), "versionId debe seguir convención <id>@v<n>");

            client.dropDatabase(dbName);
            assertFalse(Files.exists(tempStorage.resolve(dbName + "_versions.json")),
                    "RF-16: dropDatabase debe eliminar el archivo _versions.json");
        } finally {
            if (prevStorage != null) System.setProperty("jettra.storage.path", prevStorage);
            else System.clearProperty("jettra.storage.path");
            deleteRecursively(tempStorage);
        }
    }

    @Test
    @DisplayName("RF-18: Restauración a versión anterior, múltiples restauraciones consecutivas y recuperación tras DELETE")
    public void testRestoreAndConsecutiveRestores() throws Exception {
        Path tempStorage = createIsolatedStorage();
        String prevStorage = System.getProperty("jettra.storage.path");
        System.setProperty("jettra.storage.path", tempStorage.toAbsolutePath().toString());
        String dbName = "rf18_driver_restore_" + System.nanoTime();
        String bucket = "clientes";
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra")) {
            client.createDatabase(dbName);
            client.createBucket(dbName, bucket, "DOCUMENT");

            String id = client.insertDocumentReturningId(dbName, bucket, "cli-1", new LinkedHashMap<>(Map.of("nombre", "Ana", "tier", "BRONZE")));
            client.updateDocument(dbName, bucket, id, Map.of("tier", "SILVER"));
            client.updateDocument(dbName, bucket, id, Map.of("tier", "GOLD"));

            // Restaurar a v1 (BRONZE) -> crea v4
            RecordVersion v4 = client.restoreVersion(dbName, bucket, id, 1L);
            assertEquals(4L, v4.versionNumber(), "La primera restauración debe generar v4");
            assertEquals(VersionOperation.RESTORE, v4.operation(), "Operación debe ser RESTORE");
            assertEquals(1L, v4.restoredFromVersion().longValue(), "Debe indicar restoredFromVersion = 1");
            assertEquals("BRONZE", client.findBucketRecordById(dbName, bucket, id).get("tier"), "Documento activo debe ser BRONZE");

            // Restauración consecutiva a v2 (SILVER) -> crea v5
            RecordVersion v5 = client.restoreVersion(dbName, bucket, id, 2L);
            assertEquals(5L, v5.versionNumber(), "La segunda restauración consecutiva debe generar v5");
            assertEquals(2L, v5.restoredFromVersion().longValue(), "Debe indicar restoredFromVersion = 2");
            assertEquals("SILVER", client.findBucketRecordById(dbName, bucket, id).get("tier"), "Documento activo debe ser SILVER");

            // Eliminar (v6) y restaurar desde v3 (GOLD) -> crea v7 resucitando el registro
            assertTrue(client.deleteDocument(dbName, bucket, id), "Debe eliminar el registro");
            assertNull(client.findBucketRecordById(dbName, bucket, id), "El registro no debe existir tras DELETE");

            RecordVersion v7 = client.restoreVersion(dbName, bucket, id, 3L);
            assertEquals(7L, v7.versionNumber(), "Resucitar tras DELETE debe registrar v7");
            assertEquals(VersionOperation.RESTORE, v7.operation(), "Operación debe ser RESTORE");
            assertEquals("GOLD", client.findBucketRecordById(dbName, bucket, id).get("tier"), "Registro recuperado debe ser GOLD");

            // Historial íntegro (v1..v7)
            assertEquals(7, client.getVersionHistory(dbName, bucket, id).size(),
                    "No se debe perder ninguna versión previa durante restauraciones consecutivas");

            client.dropDatabase(dbName);
        } finally {
            if (prevStorage != null) System.setProperty("jettra.storage.path", prevStorage);
            else System.clearProperty("jettra.storage.path");
            deleteRecursively(tempStorage);
        }
    }

    @Test
    @DisplayName("RF-18: Casos borde: versión inexistente, registro inexistente y restauración directa de DELETE")
    public void testEdgeCasesInvalidAndMissingVersions() throws Exception {
        Path tempStorage = createIsolatedStorage();
        String prevStorage = System.getProperty("jettra.storage.path");
        System.setProperty("jettra.storage.path", tempStorage.toAbsolutePath().toString());
        String dbName = "rf18_driver_edge_" + System.nanoTime();
        String bucket = "ordenes";
        try (JettraClient client = JettraClient.connect("127.0.0.1", 50001, "admin", "admin-jettra")) {
            client.createDatabase(dbName);
            client.createBucket(dbName, bucket, "DOCUMENT");

            // 1. Registro inexistente
            assertTrue(client.getVersionHistory(dbName, bucket, "no_existe").isEmpty(), "Historial de registro inexistente debe ser vacío");
            assertTrue(client.getVersion(dbName, bucket, "no_existe", 1L).isEmpty(), "getVersion de registro inexistente debe ser empty");

            boolean threwMissingRecord = false;
            try {
                client.restoreVersion(dbName, bucket, "no_existe", 1L);
            } catch (IllegalArgumentException e) {
                threwMissingRecord = true;
            }
            assertTrue(threwMissingRecord, "restoreVersion sobre registro inexistente debe lanzar IllegalArgumentException");

            // 2. Versión inexistente / inválida
            client.insertDocumentReturningId(dbName, bucket, "ord-1", new LinkedHashMap<>(Map.of("estado", "NUEVA")));
            boolean threwMissingVersion = false;
            try {
                client.restoreVersion(dbName, bucket, "ord-1", 99L);
            } catch (IllegalArgumentException e) {
                threwMissingVersion = true;
            }
            assertTrue(threwMissingVersion, "restoreVersion con versión inexistente debe lanzar IllegalArgumentException");

            // 3. Restaurar directamente a una versión DELETE debe rechazarse claramente
            client.deleteDocument(dbName, bucket, "ord-1");
            boolean threwDeleteRestore = false;
            try {
                client.restoreVersion(dbName, bucket, "ord-1", 2L);
            } catch (IllegalArgumentException e) {
                threwDeleteRestore = e.getMessage().contains("DELETE");
            }
            assertTrue(threwDeleteRestore, "restoreVersion hacia versión DELETE debe lanzar IllegalArgumentException explicando el motivo");

            client.dropDatabase(dbName);
        } finally {
            if (prevStorage != null) System.setProperty("jettra.storage.path", prevStorage);
            else System.clearProperty("jettra.storage.path");
            deleteRecursively(tempStorage);
        }
    }
}
