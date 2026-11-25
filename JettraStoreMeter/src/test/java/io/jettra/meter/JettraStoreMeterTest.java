package io.jettra.meter;

import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.NotRequiresRunningServer;
import io.jettra.test.annotation.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static io.jettra.test.core.JettraAssert.*;

@NotRequiresRunningServer
public class JettraStoreMeterTest {

    @BeforeAll
    @AfterAll
    public static void cleanupTestDatabases() {
        List<String> dbs = List.of(
            "samples_ambiental_db",
            "samples_hospital_db",
            "samples_hostipal_db",
            "test_memory_engine_db",
            "test_meter_db",
            "test_multiuser_db",
            "example_factura_db"
        );
        List<Path> dirs = new java.util.ArrayList<>(List.of(
            Path.of("/jettra/data"),
            Path.of(System.getProperty("user.home"), "jettra", "data"),
            Path.of(System.getProperty("user.home"), "jettra-storage-01", "data"),
            Path.of(System.getProperty("user.home"), "jettra-storage-02", "data"),
            Path.of(System.getProperty("user.home"), "jettra-storage-03", "data"),
            Path.of(System.getProperty("user.home"), "jettra-storage-04", "data"),
            Path.of("jettra/data"),
            Path.of("./jettra/data"),
            Path.of("../jettra/data"),
            Path.of("./data/jettra"),
            Path.of("data/jettra"),
            Path.of("target/test-storage")
        ));
        try {
            io.jettra.store.core.JettraStoreConfig cfg = io.jettra.store.core.JettraStoreConfig.load();
            if (cfg != null && cfg.getStoragePath() != null && !cfg.getStoragePath().isBlank()) {
                dirs.add(Path.of(cfg.getStoragePath()));
            }
        } catch (Exception ignored) {}

        for (Path dir : dirs) {
            if (dir == null || !Files.exists(dir) || !Files.isDirectory(dir)) continue;
            for (String db : dbs) {
                try {
                    Files.deleteIfExists(dir.resolve(db + "_meta.json"));
                    Files.deleteIfExists(dir.resolve(db + "_sstable.jettra"));
                    Files.deleteIfExists(dir.resolve(db + ".jettra"));
                    Files.deleteIfExists(dir.resolve(db + "_wal.jettra"));
                    Files.deleteIfExists(dir.resolve(db + "_indexes.json"));
                    Files.deleteIfExists(dir.resolve(db + "_sequences.json"));
                    Files.deleteIfExists(dir.resolve(db + "_indexes.json.tmp"));
                    Files.deleteIfExists(dir.resolve(db + "_sequences.json.tmp"));
                    Files.deleteIfExists(dir.resolve(db + "_backup.snap"));
                    Path sub = dir.resolve(db);
                    if (Files.exists(sub)) {
                        try (var walk = Files.walk(sub)) {
                            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                            });
                        }
                    }
                } catch (Exception ignored) {}
            }
            try (var stream = Files.list(dir)) {
                stream.forEach(entry -> {
                    String fn = entry.getFileName().toString();
                    if (fn.startsWith("test_")
                            || fn.contains("_test_db")
                            || fn.startsWith("rf")
                            || fn.startsWith("db_modals")
                            || fn.startsWith("databs")
                            || fn.startsWith("nods")
                            || fn.startsWith("mydb")
                            || fn.startsWith("driver_explorer_db")
                            || fn.startsWith("billing_test")
                            || fn.startsWith("examplese")
                            || fn.startsWith("example_factura")
                            || fn.startsWith("samples_")) {
                        try {
                            if (Files.isDirectory(entry)) {
                                try (var walk = Files.walk(entry)) {
                                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                                    });
                                }
                            }
                            Files.deleteIfExists(entry);
                        } catch (Exception ignored) {}
                    }
                });
            } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Debe ejecutar prueba de estrés concurrente con Virtual Threads y ciclo de teardown")
    public void testStressTestAndTeardownLifecycle() throws InterruptedException {
        JettraStressTestRunner runner = new JettraStressTestRunner("127.0.0.1", 9091, "test_meter_db");
        var result = runner.runStressTest(10, 20); // 10 hilos x 20 operaciones

        assertEquals(200, result.totalOperations());
        assertTrue(result.durationMs() > 0);
        assertTrue(result.opsPerSecond() > 0);
        assertTrue(result.teardownSuccess(), "El teardown automatizado debe limpiar la base de datos de prueba");
    }

    @Test
    @DisplayName("Debe simular múltiples usuarios concurrentes ejecutando operaciones multimodelo (Doc, SQL, KV, Vector, TS)")
    public void testMultiUserStressWorkloadAgainstJettraStore() throws InterruptedException {
        JettraStressTestRunner runner = new JettraStressTestRunner("127.0.0.1", 9091, "test_multiuser_db");
        int users = 15;
        int opsPerUser = 25;
        var result = runner.runMultiUserDatabaseStressTest(users, opsPerUser);

        assertEquals(users * opsPerUser, result.totalOperations());
        assertTrue(result.opsPerSecond() > 0);
        assertTrue(result.avgLatencyMs() >= 0);
        assertTrue(result.p95LatencyMs() >= 0);
        assertTrue(result.teardownSuccess(), "El ciclo de teardown debe eliminar los datos generados por la prueba");
    }

    @Test
    @DisplayName("Debe ejecutar prueba de estrés en JettraStore usando JettraMemory como motor Off-Heap directo a disco")
    public void testJettraStoreWithJettraMemoryEngine() throws Exception {
        JettraStressTestRunner runner = new JettraStressTestRunner("127.0.0.1", 9091, "test_memory_engine_db");
        int users = 20;
        int opsPerUser = 30;

        var result = runner.runMemoryEngineStressTest(users, opsPerUser);

        assertEquals(users * opsPerUser, result.totalOperations());
        assertTrue(result.opsPerSecond() > 0);
        assertTrue(result.offHeapAllocatedBytes() > 0, "Debe registrar bytes asignados off-heap en el archivo .jettra");
        assertTrue(result.teardownSuccess(), "El almacenamiento físico .jettra debe ser purgado en el teardown");
    }

    @Test
    @DisplayName("Debe validar estrés con 25 usuarios concurrentes contra example_factura_db durante período sostenido")
    public void testFacturaWorkload25ConcurrentUsers() throws InterruptedException {
        JettraStressTestRunner runner = new JettraStressTestRunner("127.0.0.1", 9091, "example_factura_db");
        var result = runner.runFacturaDurationWorkload(25, 150);

        System.out.println("DEBUG [25 Users]: " + result.summary());
        assertEquals(0, result.failedOperations());
        assertTrue(result.totalOperations() > 100, "Debe procesar operaciones masivas con 25 usuarios");
        assertTrue(result.opsPerSecond() > 50.0);
    }

    @Test
    @DisplayName("Debe validar estrés con 50 usuarios concurrentes contra example_factura_db durante período sostenido")
    public void testFacturaWorkload50ConcurrentUsers() throws InterruptedException {
        JettraStressTestRunner runner = new JettraStressTestRunner("127.0.0.1", 9091, "example_factura_db");
        var result = runner.runFacturaDurationWorkload(50, 150);

        System.out.println("DEBUG [50 Users]: " + result.summary());
        assertEquals(0, result.failedOperations());
        assertTrue(result.totalOperations() > 200, "Debe procesar operaciones masivas con 50 usuarios");
        assertTrue(result.opsPerSecond() > 100.0);
    }

    @Test
    @DisplayName("Debe validar estrés con 100 usuarios concurrentes contra example_factura_db durante período sostenido")
    public void testFacturaWorkload100ConcurrentUsers() throws InterruptedException {
        JettraStressTestRunner runner = new JettraStressTestRunner("127.0.0.1", 9091, "example_factura_db");
        var result = runner.runFacturaDurationWorkload(100, 150);

        System.out.println("DEBUG [100 Users]: " + result.summary());
        assertEquals(0, result.failedOperations());
        assertTrue(result.totalOperations() > 300, "Debe procesar operaciones masivas con 100 usuarios");
        assertTrue(result.opsPerSecond() > 150.0);
    }

    @Test
    @DisplayName("Debe validar estrés con 5 y 10 usuarios concurrentes contra samples_hostipal_db")
    public void testHospitalWorkload5And10Users() throws InterruptedException {
        JettraStressTestRunner runner = new JettraStressTestRunner("127.0.0.1", 9091, "samples_hostipal_db");
        var res5 = runner.runHospitalDurationWorkload(5, 150);
        System.out.println("DEBUG [Hospital 5 Users]: " + res5.summary());
        assertEquals(0, res5.failedOperations());
        assertTrue(res5.totalOperations() > 20);

        var res10 = runner.runHospitalDurationWorkload(10, 150);
        System.out.println("DEBUG [Hospital 10 Users]: " + res10.summary());
        assertEquals(0, res10.failedOperations());
        assertTrue(res10.totalOperations() > 40);
    }

    @Test
    @DisplayName("Debe validar estrés con 25 y 50 usuarios concurrentes contra samples_hostipal_db")
    public void testHospitalWorkload25And50Users() throws InterruptedException {
        JettraStressTestRunner runner = new JettraStressTestRunner("127.0.0.1", 9091, "samples_hostipal_db");
        var res25 = runner.runHospitalDurationWorkload(25, 150);
        System.out.println("DEBUG [Hospital 25 Users]: " + res25.summary());
        assertEquals(0, res25.failedOperations());
        assertTrue(res25.totalOperations() > 100);

        var res50 = runner.runHospitalDurationWorkload(50, 150);
        System.out.println("DEBUG [Hospital 50 Users]: " + res50.summary());
        assertEquals(0, res50.failedOperations());
        assertTrue(res50.totalOperations() > 200);
    }

    @Test
    @DisplayName("Debe validar estrés con 100 y 500 usuarios concurrentes contra samples_hostipal_db")
    public void testHospitalWorkload100And500Users() throws InterruptedException {
        JettraStressTestRunner runner = new JettraStressTestRunner("127.0.0.1", 9091, "samples_hostipal_db");
        var res100 = runner.runHospitalDurationWorkload(100, 150);
        System.out.println("DEBUG [Hospital 100 Users]: " + res100.summary());
        assertEquals(0, res100.failedOperations());
        assertTrue(res100.totalOperations() > 300);

        var res500 = runner.runHospitalDurationWorkload(500, 150);
        System.out.println("DEBUG [Hospital 500 Users]: " + res500.summary());
        assertEquals(0, res500.failedOperations());
        assertTrue(res500.totalOperations() > 500);
    }

    @Test
    @DisplayName("Debe validar estrés con 5 y 10 usuarios concurrentes contra samples_ambiental_db")
    public void testAmbientalWorkload5And10Users() throws InterruptedException {
        JettraStressTestRunner runner = new JettraStressTestRunner("127.0.0.1", 9091, "samples_ambiental_db");
        var res5 = runner.runAmbientalDurationWorkload(5, 150);
        System.out.println("DEBUG [Ambiental 5 Users]: " + res5.summary());
        assertEquals(0, res5.failedOperations());
        assertTrue(res5.totalOperations() > 20);

        var res10 = runner.runAmbientalDurationWorkload(10, 150);
        System.out.println("DEBUG [Ambiental 10 Users]: " + res10.summary());
        assertEquals(0, res10.failedOperations());
        assertTrue(res10.totalOperations() > 40);
    }

    @Test
    @DisplayName("Debe validar estrés con 25 y 50 usuarios concurrentes contra samples_ambiental_db")
    public void testAmbientalWorkload25And50Users() throws InterruptedException {
        JettraStressTestRunner runner = new JettraStressTestRunner("127.0.0.1", 9091, "samples_ambiental_db");
        var res25 = runner.runAmbientalDurationWorkload(25, 150);
        System.out.println("DEBUG [Ambiental 25 Users]: " + res25.summary());
        assertEquals(0, res25.failedOperations());
        assertTrue(res25.totalOperations() > 100);

        var res50 = runner.runAmbientalDurationWorkload(50, 150);
        System.out.println("DEBUG [Ambiental 50 Users]: " + res50.summary());
        assertEquals(0, res50.failedOperations());
        assertTrue(res50.totalOperations() > 200);
    }

    @Test
    @DisplayName("Debe validar estrés con 100 y 500 usuarios concurrentes contra samples_ambiental_db")
    public void testAmbientalWorkload100And500Users() throws InterruptedException {
        JettraStressTestRunner runner = new JettraStressTestRunner("127.0.0.1", 9091, "samples_ambiental_db");
        var res100 = runner.runAmbientalDurationWorkload(100, 150);
        System.out.println("DEBUG [Ambiental 100 Users]: " + res100.summary());
        assertEquals(0, res100.failedOperations());
        assertTrue(res100.totalOperations() > 300);

        var res500 = runner.runAmbientalDurationWorkload(500, 150);
        System.out.println("DEBUG [Ambiental 500 Users]: " + res500.summary());
        assertEquals(0, res500.failedOperations());
        assertTrue(res500.totalOperations() > 500);
    }

    @Test
    @DisplayName("Debe validar estrés con 500 usuarios concurrentes en Virtual Threads contra example_factura_db")
    public void testFacturaWorkload500ConcurrentUsers() throws InterruptedException {
        JettraStressTestRunner runner = new JettraStressTestRunner("127.0.0.1", 9091, "example_factura_db");
        var result = runner.runFacturaDurationWorkload(500, 150);

        System.out.println("DEBUG [500 Users]: " + result.summary());
        assertEquals(0, result.failedOperations());
        assertTrue(result.totalOperations() > 500, "Debe procesar operaciones masivas con 500 usuarios concurrentes");
        assertTrue(result.opsPerSecond() > 200.0);
    }

    @Test
    @DisplayName("RF-20: JettraToleranceSuite ejecuta escenarios parametrizados de volumen y concurrencia y genera reporte Markdown")
    public void testToleranceSuiteParameterizedExecutionAndReport() {
        JettraToleranceSuite suite = new JettraToleranceSuite("127.0.0.1", 9091, 0, "rf20_unit_db", 10, 85.0);
        var vol = suite.runVolumeScenario(500L);
        var conc = suite.runConcurrencyScenario(10);

        assertEquals(500L, vol.completedOps());
        assertEquals(0L, vol.failedOps());
        assertEquals("STABLE", vol.status());

        assertTrue(conc.completedOps() >= 10L);
        assertEquals(0L, conc.failedOps());
        assertEquals("STABLE", conc.status());

        String md = suite.generateMarkdownReport(List.of(vol), List.of(conc));
        assertTrue(md.contains("# Reporte de Tolerancia y Pruebas de Estrés — JettraStore (`RF-20`)"));
        assertTrue(md.contains("## 1. Resultados por Volumen de Registros"));
        assertTrue(md.contains("## 2. Resultados por Concurrencia de Usuarios (Virtual Threads)"));
        assertTrue(md.contains("## 3. Análisis de Tolerancia y Límites Observados"));
        assertTrue(md.contains("## 4. Conclusiones Técnicas y Recomendaciones de Soporte"));
    }
}
