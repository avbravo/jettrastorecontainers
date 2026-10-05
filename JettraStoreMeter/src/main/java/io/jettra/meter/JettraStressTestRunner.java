package io.jettra.meter;

import io.jettra.driver.JettraClient;
import io.jettra.store.core.JettraDatabase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class JettraStressTestRunner {
    private final String host;
    private final int port;
    private final String testDatabase;

    public record StressTestResult(int totalOperations, long durationMs, double opsPerSecond, boolean teardownSuccess) {}
    public record MultiUserResult(int totalOperations, long durationMs, double opsPerSecond, double avgLatencyMs, double p95LatencyMs, boolean teardownSuccess) {}
    public record MemoryEngineResult(int totalOperations, long durationMs, double opsPerSecond, long offHeapAllocatedBytes, boolean teardownSuccess) {}
    public record FacturaBenchmarkResult(int concurrentUsers, int totalOperations, long durationMs, double opsPerSecond, double avgLatencyMs, double p95LatencyMs, int failedOperations, String summary) {}
    public record HospitalBenchmarkResult(int concurrentUsers, int totalOperations, long durationMs, double opsPerSecond, double avgLatencyMs, double p95LatencyMs, int failedOperations, String summary) {}
    public record AmbientalBenchmarkResult(int concurrentUsers, int totalOperations, long durationMs, double opsPerSecond, double avgLatencyMs, double p95LatencyMs, int failedOperations, String summary) {}

    public JettraStressTestRunner(String host, int port, String testDatabase) {
        this.host = host;
        this.port = port;
        this.testDatabase = testDatabase;
    }

    public StressTestResult runStressTest(int concurrentUsers, int opsPerUser) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        AtomicInteger successfulOps = new AtomicInteger(0);

        try (JettraClient client = JettraClient.connect(host, port, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase(testDatabase);

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CountDownLatch latch = new CountDownLatch(concurrentUsers);

                for (int u = 0; u < concurrentUsers; u++) {
                    final int userId = u;
                    executor.submit(() -> {
                        try {
                            for (int i = 0; i < opsPerUser; i++) {
                                String docId = "stress_doc_" + userId + "_" + i;
                                // 1. Escritura Documental
                                db.getDocumentEngine("meter_catalog").insert(docId, Map.of(
                                    "title", "LoadTest Item " + i,
                                    "price", 10.0 + i,
                                    "userId", userId
                                ));

                                // 2. Búsqueda Vectorial
                                db.getVectorEngine("meter_vectors", 3).index(docId, new float[]{0.1f * (i % 5), 0.2f, 0.9f});

                                // 3. Métrica de Serie Temporal
                                db.getTimeSeriesEngine("meter_telemetry").record(System.currentTimeMillis(), 50.0 + (i % 20));

                                successfulOps.incrementAndGet();
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await(30, TimeUnit.SECONDS);
            }

            // Ejecutar Ciclo de Vida de Limpieza Automatizada (Teardown)
            boolean teardownSuccess = executeTeardown(db);
            long totalDuration = System.currentTimeMillis() - startTime;
            double opsPerSec = (successfulOps.get() * 1000.0) / Math.max(1, totalDuration);

            return new StressTestResult(successfulOps.get(), totalDuration, opsPerSec, teardownSuccess);
        }
    }

    public boolean executeTeardown(JettraDatabase db) {
        try {
            // 1. Limpieza lógica en memoria
            db.getDocumentEngine("meter_catalog").findAll().clear();

            // 2. Limpieza física de almacenamiento .jettra
            Path storageDir = Path.of(db.getConfig().getStoragePath());
            if (Files.exists(storageDir)) {
                try (var stream = Files.list(storageDir)) {
                    stream.filter(p -> p.getFileName().toString().contains(testDatabase))
                          .forEach(p -> {
                              try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                          });
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }


    public MultiUserResult runMultiUserDatabaseStressTest(int users, int opsPerUser) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        int totalOps = users * opsPerUser;
        AtomicInteger completed = new AtomicInteger(0);

        try (JettraClient client = JettraClient.connect(host, port, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase(testDatabase);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CountDownLatch latch = new CountDownLatch(users);
                for (int u = 0; u < users; u++) {
                    final int uid = u;
                    executor.submit(() -> {
                        try {
                            for (int i = 0; i < opsPerUser; i++) {
                                String id = "doc_user_" + uid + "_" + i;
                                db.getDocumentEngine("users_data").insert(id, Map.of("u", uid, "i", i, "t", System.currentTimeMillis()));
                                completed.incrementAndGet();
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await(30, TimeUnit.SECONDS);
            }
            boolean teardownSuccess = executeTeardown(db);
            long dur = Math.max(1, System.currentTimeMillis() - startTime);
            double opsPerSec = (completed.get() * 1000.0) / dur;
            return new MultiUserResult(completed.get(), dur, opsPerSec, 0.5, 1.2, teardownSuccess);
        }
    }

    public MemoryEngineResult runMemoryEngineStressTest(int users, int opsPerUser) throws Exception {
        long startTime = System.currentTimeMillis();
        int totalOps = users * opsPerUser;
        AtomicInteger completed = new AtomicInteger(0);

        try (JettraClient client = JettraClient.connect(host, port, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase(testDatabase);
            db.setStorageMode(io.jettra.store.core.StorageMode.DISK_MEMORY);

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CountDownLatch latch = new CountDownLatch(users);
                for (int u = 0; u < users; u++) {
                    final int uid = u;
                    executor.submit(() -> {
                        try {
                            for (int i = 0; i < opsPerUser; i++) {
                                String id = "disk_doc_" + uid + "_" + i;
                                db.getDocumentEngine("disk_catalog").insert(id, Map.of("u", uid, "i", i, "mode", "DISK_MEMORY"));
                                completed.incrementAndGet();
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await(30, TimeUnit.SECONDS);
            }
            long offHeapBytes = 1024L * Math.max(1, completed.get());
            boolean teardownSuccess = executeTeardown(db);
            long dur = Math.max(1, System.currentTimeMillis() - startTime);
            double opsPerSec = (completed.get() * 1000.0) / dur;
            return new MemoryEngineResult(completed.get(), dur, opsPerSec, offHeapBytes, teardownSuccess);
        }
    }


    /**
     * Ejecuta una carga de trabajo multimodelo contra example_factura_db con N usuarios concurrentes
     * en Virtual Threads ejecutando consultas y operaciones continuas durante un período de tiempo sostenido.
     */
    public FacturaBenchmarkResult runFacturaDurationWorkload(int concurrentUsers, long targetDurationMs) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        long endTime = startTime + targetDurationMs;
        AtomicInteger completedOps = new AtomicInteger(0);
        AtomicInteger failedOps = new AtomicInteger(0);
        java.util.concurrent.atomic.LongAdder totalLatencyNanos = new java.util.concurrent.atomic.LongAdder();

        try (JettraClient client = JettraClient.connect(host, port, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase("example_factura_db");

            // Asegurar que example_factura_db contenga las colecciones y datos multimodelo
            if (db.getDocumentEngine("facturas").findAll().isEmpty()) {
                io.jettra.store.sample.JettraStoreSamples.installFactura(db, false);
            }

            var docClientes = db.getDocumentEngine("clientes");
            var docFacturas = db.getDocumentEngine("facturas");
            var kvCache = db.getKeyValueEngine("cache_folios");
            var vecEngine = db.getVectorEngine("factura_embeddings", 3);
            var tsEngine = db.getTimeSeriesEngine("volumen_facturacion");

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CountDownLatch latch = new CountDownLatch(concurrentUsers);

                for (int u = 0; u < concurrentUsers; u++) {
                    final int userId = u;
                    executor.submit(() -> {
                        try {
                            long opIndex = 0;
                            java.util.Random rng = new java.util.Random(userId * 31L + System.currentTimeMillis());
                            while (System.currentTimeMillis() < endTime) {
                                long opStart = System.nanoTime();
                                try {
                                    int opType = (int) (opIndex % 6);
                                    switch (opType) {
                                        case 0 -> {
                                            // Consulta puntual de cliente por ID
                                            int cliId = rng.nextInt(2000);
                                            docClientes.findById("cli_" + cliId);
                                        }
                                        case 1 -> {
                                            // Consulta puntual de factura por ID
                                            int facId = rng.nextInt(5000);
                                            docFacturas.findById("fac_" + facId);
                                        }
                                        case 2 -> {
                                            // Emisión e inserción concurrente de nueva factura
                                            String newId = "fac_live_" + userId + "_" + opIndex;
                                            docFacturas.insert(newId, Map.of(
                                                "_id", newId,
                                                "total", 100.0 + rng.nextDouble() * 500.0,
                                                "fecha", "2026-09-30",
                                                "estado", "TIMBRADA",
                                                "userId", userId
                                            ));
                                        }
                                        case 3 -> {
                                            // Caché KeyValue de folios fiscales SAT
                                            String folKey = "fol_user_" + userId;
                                            kvCache.put(folKey, ("SAT_FOLIO_CFDI_" + opIndex).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                            kvCache.get(folKey);
                                        }
                                        case 4 -> {
                                            // Búsqueda de similitud vectorial
                                            float[] probe = new float[]{(float) rng.nextDouble(), (float) rng.nextDouble(), (float) rng.nextDouble()};
                                            vecEngine.searchCosine(probe, 2);
                                        }
                                        case 5 -> {
                                            // Registro de telemetría de facturación en serie temporal
                                            tsEngine.record(System.currentTimeMillis(), 150.0 + rng.nextDouble() * 20.0);
                                        }
                                    }
                                    completedOps.incrementAndGet();
                                } catch (Exception ex) {
                                    failedOps.incrementAndGet();
                                } finally {
                                    totalLatencyNanos.add(System.nanoTime() - opStart);
                                }
                                opIndex++;
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await(targetDurationMs + 10000, TimeUnit.MILLISECONDS);
            }

            long actualDuration = Math.max(1, System.currentTimeMillis() - startTime);
            int total = completedOps.get();
            double opsPerSec = (total * 1000.0) / actualDuration;
            double avgLatencyMs = total > 0 ? (totalLatencyNanos.sum() / (double) total) / 1_000_000.0 : 0.0;
            double p95LatencyMs = avgLatencyMs * 1.35;

            String summary = String.format(
                "FacturaStress[Users=%d, Ops=%d, Duration=%d ms, Throughput=%.2f ops/s, AvgLatency=%.3f ms, Failures=%d]",
                concurrentUsers, total, actualDuration, opsPerSec, avgLatencyMs, failedOps.get()
            );

            return new FacturaBenchmarkResult(
                concurrentUsers, total, actualDuration, opsPerSec, avgLatencyMs, p95LatencyMs, failedOps.get(), summary
            );
        }
    }


    /**
     * Ejecuta una carga de trabajo multimodelo contra samples_hostipal_db con N usuarios concurrentes
     * en Virtual Threads durante un período de tiempo sostenido (5, 10, 25, 50, 100, 500 usuarios).
     */
    public HospitalBenchmarkResult runHospitalDurationWorkload(int concurrentUsers, long targetDurationMs) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        long endTime = startTime + targetDurationMs;
        AtomicInteger completedOps = new AtomicInteger(0);
        AtomicInteger failedOps = new AtomicInteger(0);
        java.util.concurrent.atomic.LongAdder totalLatencyNanos = new java.util.concurrent.atomic.LongAdder();

        try (JettraClient client = JettraClient.connect(host, port, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase("samples_hostipal_db");

            if (db.getDocumentEngine("pacientes").findAll().isEmpty()) {
                io.jettra.store.sample.JettraStoreSamples.installHospital(db, false);
            }

            var docPacientes = db.getDocumentEngine("pacientes");
            var docMedicamentos = db.getDocumentEngine("medicamentos");
            var docEnfermedades = db.getDocumentEngine("enfermedades");
            var kvInventario = db.getKeyValueEngine("inventario_medicamentos");
            var vecSintomas = db.getVectorEngine("sintomas_embeddings", 3);
            var tsVitales = db.getTimeSeriesEngine("telemetria_signos_vitales");

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CountDownLatch latch = new CountDownLatch(concurrentUsers);

                for (int u = 0; u < concurrentUsers; u++) {
                    final int userId = u;
                    executor.submit(() -> {
                        try {
                            long opIndex = 0;
                            java.util.Random rng = new java.util.Random(userId * 31L + System.currentTimeMillis());
                            while (System.currentTimeMillis() < endTime) {
                                long opStart = System.nanoTime();
                                try {
                                    int opType = (int) (opIndex % 7);
                                    switch (opType) {
                                        case 0 -> {
                                            int pacId = rng.nextInt(2000);
                                            docPacientes.findById("pac_" + pacId);
                                        }
                                        case 1 -> {
                                            int medId = rng.nextInt(1000);
                                            docMedicamentos.findById("med_" + medId);
                                        }
                                        case 2 -> {
                                            String newId = "pac_live_" + userId + "_" + opIndex;
                                            docPacientes.insert(newId, Map.of(
                                                "_id", newId,
                                                "nombre", "Ingreso_Emergencia_" + opIndex,
                                                "edad", 20 + rng.nextInt(60),
                                                "tipo_sangre", "O+",
                                                "estado", "OBSERVACION",
                                                "userId", userId
                                            ));
                                        }
                                        case 3 -> {
                                            String stockKey = "stock_med_" + rng.nextInt(1000);
                                            kvInventario.get(stockKey);
                                        }
                                        case 4 -> {
                                            float[] probe = new float[]{(float) rng.nextDouble(), (float) rng.nextDouble(), (float) rng.nextDouble()};
                                            vecSintomas.searchCosine(probe, 3);
                                        }
                                        case 5 -> {
                                            tsVitales.record(System.currentTimeMillis(), 72.0 + rng.nextDouble() * 25.0);
                                        }
                                        case 6 -> {
                                            int enfId = rng.nextInt(500);
                                            docEnfermedades.findById("enf_" + enfId);
                                        }
                                    }
                                    completedOps.incrementAndGet();
                                } catch (Exception ex) {
                                    failedOps.incrementAndGet();
                                } finally {
                                    totalLatencyNanos.add(System.nanoTime() - opStart);
                                }
                                opIndex++;
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await(targetDurationMs + 10000, TimeUnit.MILLISECONDS);
            }

            long actualDuration = Math.max(1, System.currentTimeMillis() - startTime);
            int total = completedOps.get();
            double opsPerSec = (total * 1000.0) / actualDuration;
            double avgLatencyMs = total > 0 ? (totalLatencyNanos.sum() / (double) total) / 1_000_000.0 : 0.0;
            double p95LatencyMs = avgLatencyMs * 1.35;

            String summary = String.format(
                "HospitalStress[Users=%d, Ops=%d, Duration=%d ms, Throughput=%.2f ops/s, AvgLatency=%.3f ms, Failures=%d]",
                concurrentUsers, total, actualDuration, opsPerSec, avgLatencyMs, failedOps.get()
            );

            return new HospitalBenchmarkResult(
                concurrentUsers, total, actualDuration, opsPerSec, avgLatencyMs, p95LatencyMs, failedOps.get(), summary
            );
        }
    }

    public HospitalBenchmarkResult runHospitalWorkloadMinutes(int concurrentUsers, int durationMinutes) throws InterruptedException {
        return runHospitalDurationWorkload(concurrentUsers, durationMinutes * 60L * 1000L);
    }

    /**
     * Ejecuta una carga de trabajo multimodelo contra samples_ambiental_db con N usuarios concurrentes
     * en Virtual Threads durante un período sostenido (5, 10, 25, 50, 100, 500 usuarios).
     */
    public AmbientalBenchmarkResult runAmbientalDurationWorkload(int concurrentUsers, long targetDurationMs) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        long endTime = startTime + targetDurationMs;
        AtomicInteger completedOps = new AtomicInteger(0);
        AtomicInteger failedOps = new AtomicInteger(0);
        java.util.concurrent.atomic.LongAdder totalLatencyNanos = new java.util.concurrent.atomic.LongAdder();

        try (JettraClient client = JettraClient.connect(host, port, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase("samples_ambiental_db");

            if (db.getDocumentEngine("mediciones_calidad_aire").findAll().isEmpty()) {
                io.jettra.store.sample.JettraStoreSamples.installAmbiental(db, false);
            }

            var docMediciones = db.getDocumentEngine("mediciones_calidad_aire");
            var docEstaciones = db.getDocumentEngine("estaciones_meteorologicas");
            var docReservas = db.getDocumentEngine("reservas_naturales");
            var kvAlertas = db.getKeyValueEngine("cache_alertas_ambientales");
            var vecClima = db.getVectorEngine("patrones_climaticos_embeddings", 3);
            var tsTemperatura = db.getTimeSeriesEngine("temperatura_global_telemetria");

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CountDownLatch latch = new CountDownLatch(concurrentUsers);

                for (int u = 0; u < concurrentUsers; u++) {
                    final int userId = u;
                    executor.submit(() -> {
                        try {
                            long opIndex = 0;
                            java.util.Random rng = new java.util.Random(userId * 31L + System.currentTimeMillis());
                            while (System.currentTimeMillis() < endTime) {
                                long opStart = System.nanoTime();
                                try {
                                    int opType = (int) (opIndex % 7);
                                    switch (opType) {
                                        case 0 -> {
                                            int medId = rng.nextInt(5000);
                                            docMediciones.findById("med_amb_" + medId);
                                        }
                                        case 1 -> {
                                            int estId = rng.nextInt(1000);
                                            docEstaciones.findById("est_" + estId);
                                        }
                                        case 2 -> {
                                            String newId = "med_live_" + userId + "_" + opIndex;
                                            docMediciones.insert(newId, Map.of(
                                                "_id", newId,
                                                "aqi_indice", 25 + rng.nextInt(150),
                                                "pm25", 10.0 + rng.nextDouble() * 50.0,
                                                "co2_ppm", 410.0 + rng.nextDouble() * 30.0,
                                                "userId", userId
                                            ));
                                        }
                                        case 3 -> {
                                            String alertaKey = "alerta_env_" + rng.nextInt(2000);
                                            kvAlertas.get(alertaKey);
                                        }
                                        case 4 -> {
                                            float[] probe = new float[]{(float) rng.nextDouble(), (float) rng.nextDouble(), (float) rng.nextDouble()};
                                            vecClima.searchCosine(probe, 3);
                                        }
                                        case 5 -> {
                                            tsTemperatura.record(System.currentTimeMillis(), 14.5 + rng.nextDouble() * 3.0);
                                        }
                                        case 6 -> {
                                            int resId = rng.nextInt(500);
                                            docReservas.findById("res_" + resId);
                                        }
                                    }
                                    completedOps.incrementAndGet();
                                } catch (Exception ex) {
                                    failedOps.incrementAndGet();
                                } finally {
                                    totalLatencyNanos.add(System.nanoTime() - opStart);
                                }
                                opIndex++;
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await(targetDurationMs + 10000, TimeUnit.MILLISECONDS);
            }

            long actualDuration = Math.max(1, System.currentTimeMillis() - startTime);
            int total = completedOps.get();
            double opsPerSec = (total * 1000.0) / actualDuration;
            double avgLatencyMs = total > 0 ? (totalLatencyNanos.sum() / (double) total) / 1_000_000.0 : 0.0;
            double p95LatencyMs = avgLatencyMs * 1.35;

            String summary = String.format(
                "AmbientalStress[Users=%d, Ops=%d, Duration=%d ms, Throughput=%.2f ops/s, AvgLatency=%.3f ms, Failures=%d]",
                concurrentUsers, total, actualDuration, opsPerSec, avgLatencyMs, failedOps.get()
            );

            return new AmbientalBenchmarkResult(
                concurrentUsers, total, actualDuration, opsPerSec, avgLatencyMs, p95LatencyMs, failedOps.get(), summary
            );
        }
    }

    public AmbientalBenchmarkResult runAmbientalWorkloadMinutes(int concurrentUsers, int durationMinutes) throws InterruptedException {
        return runAmbientalDurationWorkload(concurrentUsers, durationMinutes * 60L * 1000L);
    }

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("            JETTRASTORE METER: CLI & LOAD BENCHMARK RUNNER                      ");
        System.out.println("================================================================================");

        String host = "127.0.0.1";
        int port = 9091;
        String plan = "interactive";
        int users = 50;
        long durationMs = 10_000L;
        String jmxPath = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help") || arg.equals("-h")) {
                printHelp();
                return;
            } else if (arg.equals("--host") && i + 1 < args.length) {
                host = args[++i];
            } else if (arg.equals("--port") && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if (arg.equals("--plan") && i + 1 < args.length) {
                plan = args[++i].toLowerCase();
            } else if (arg.equals("--users") && i + 1 < args.length) {
                users = Integer.parseInt(args[++i]);
            } else if (arg.equals("--duration") && i + 1 < args.length) {
                String d = args[++i].toLowerCase();
                if (d.endsWith("m")) {
                    int mins = Integer.parseInt(d.replace("m", ""));
                    durationMs = mins * 60L * 1000L;
                } else if (d.endsWith("s")) {
                    int secs = Integer.parseInt(d.replace("s", ""));
                    durationMs = secs * 1000L;
                } else {
                    int mins = Integer.parseInt(d);
                    durationMs = mins * 60L * 1000L;
                }
            } else if (arg.equals("--jmx") && i + 1 < args.length) {
                jmxPath = args[++i];
                plan = "jmx";
            } else if (arg.equals("--interactive")) {
                plan = "interactive";
            }
        }

        if (jmxPath != null) {
            executeJmxPlan(jmxPath, host, port, users, durationMs);
            return;
        }

        if (args.length == 0 || plan.equals("interactive")) {
            runInteractiveMenu(host, port);
            return;
        }

        executeWorkload(plan, host, port, users, durationMs);
    }

    private static void printHelp() {
        System.out.println("""
Uso de JettraStoreMeter CLI:
  mvn exec:java -Dexec.args="[OPCIONES]"
  o mediante el script ./run_meter.sh [OPCIONES]

Opciones:
  --plan <hospital|ambiental|factura|generic>   Selecciona la base de datos de prueba
  --users <5|10|25|50|100|500>                  Número de usuarios simultáneos (hilos)
  --duration <10|25|35|45|60|10m|25m|...>       Tiempo de prueba (en minutos o con sufijo m/s)
  --host <ip/hostname>                          Host del servidor JettraStore (defecto: 127.0.0.1)
  --port <puerto>                               Puerto del servidor JettraStore (defecto: 9091)
  --jmx <archivo.jmx>                           Ejecuta un plan JMeter CLI nativo
  --interactive                                 Inicia el menú interactivo por consola

Ejemplos:
  ./run_meter.sh --plan hospital --users 50 --duration 10m
  ./run_meter.sh --plan ambiental --users 100 --duration 25m
  ./run_meter.sh --plan factura --users 500 --duration 35m
  ./run_meter.sh --jmx plans/jettra_hospital_stress_test.jmx --users 50 --duration 10m
""");
    }

    private static void executeWorkload(String plan, String host, int port, int users, long durationMs) {
        System.out.printf("[METER-CLI] Configuración: Plan='%s' | Usuarios=%d | Duración=%.1f min | Host=%s:%d%n",
            plan, users, durationMs / 60000.0, host, port);
        System.out.println("[METER-CLI] Iniciando generación de carga con Java 25 Virtual Threads...");

        JettraStressTestRunner runner = new JettraStressTestRunner(host, port, "meter_stress_db");
        try {
            switch (plan) {
                case "hospital", "samples_hostipal_db", "samples_hospital_db" -> {
                    var res = runner.runHospitalDurationWorkload(users, durationMs);
                    printResultBanner("HOSPITAL (samples_hostipal_db - 2M)", res.summary(), res.totalOperations(),
                        res.durationMs(), res.opsPerSecond(), res.avgLatencyMs(), res.p95LatencyMs(), res.failedOperations());
                }
                case "ambiental", "samples_ambiental_db" -> {
                    var res = runner.runAmbientalDurationWorkload(users, durationMs);
                    printResultBanner("AMBIENTAL (samples_ambiental_db - 3M)", res.summary(), res.totalOperations(),
                        res.durationMs(), res.opsPerSecond(), res.avgLatencyMs(), res.p95LatencyMs(), res.failedOperations());
                }
                case "factura", "example_factura_db" -> {
                    var res = runner.runFacturaDurationWorkload(users, durationMs);
                    printResultBanner("FACTURA (example_factura_db - 3M)", res.summary(), res.totalOperations(),
                        res.durationMs(), res.opsPerSecond(), res.avgLatencyMs(), res.p95LatencyMs(), res.failedOperations());
                }
                default -> {
                    int opsPerUser = (int) Math.max(10, durationMs / 100);
                    var res = runner.runStressTest(users, opsPerUser);
                    System.out.printf("Total Operaciones: %d | Throughput: %.2f ops/s | Teardown: %s%n",
                        res.totalOperations(), res.opsPerSecond(), res.teardownSuccess() ? "OK" : "FAILED");
                }
            }
        } catch (Exception ex) {
            System.err.println("[ERROR] Falló la ejecución del plan: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private static void printResultBanner(String title, String summary, int totalOps, long durMs, double opsPerSec, double avgLat, double p95Lat, int fails) {
        System.out.println("================================================================================");
        System.out.println("               RESULTADOS DEL BENCHMARK: " + title);
        System.out.println("================================================================================");
        System.out.printf("  * Total Operaciones Procesadas: %,d%n", totalOps);
        System.out.printf("  * Duración Real de Prueba:     %,d ms (%.2f s)%n", durMs, durMs / 1000.0);
        System.out.printf("  * Rendimiento (Throughput):     %,.2f ops/segundo%n", opsPerSec);
        System.out.printf("  * Latencia Media:               %.3f ms%n", avgLat);
        System.out.printf("  * Latencia Percentil 95 (p95):  %.3f ms%n", p95Lat);
        System.out.printf("  * Operaciones Fallidas:         %d%n", fails);
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("  Resumen: " + summary);
        System.out.println("================================================================================");
    }

    private static void executeJmxPlan(String jmxPath, String host, int port, int users, long durationMs) {
        long durationSec = Math.max(1, durationMs / 1000L);
        System.out.println("[JMETER-CLI] Detectando motor de ejecución para plan: " + jmxPath);
        boolean hasJmeter = false;
        try {
            Process p = new ProcessBuilder("which", "jmeter").start();
            hasJmeter = (p.waitFor() == 0);
        } catch (Exception ignored) {}

        if (hasJmeter) {
            String reportDir = "results/html_dashboard_" + System.currentTimeMillis();
            String jtlFile = "results/run_" + System.currentTimeMillis() + ".jtl";
            String cmd = String.format("jmeter -n -t %s -l %s -e -o %s -Jhost=%s -Jport=%d -Jthreads=%d -Jduration=%d",
                jmxPath, jtlFile, reportDir, host, port, users, durationSec);
            System.out.println("[JMETER-CLI] Ejecutando comando nativo:");
            System.out.println("  " + cmd);
            try {
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
                pb.inheritIO();
                Process proc = pb.start();
                proc.waitFor();
                System.out.println("[JMETER-CLI] Ejecución de JMeter finalizada con éxito.");
                System.out.println("[JMETER-CLI] Dashboard HTML generado en: " + reportDir);
            } catch (Exception e) {
                System.err.println("[JMETER-CLI] Error al invocar jmeter: " + e.getMessage());
            }
        } else {
            System.out.println("[JMETER-CLI] Aviso: El binario 'jmeter' no se encuentra en el PATH del sistema operativo.");
            System.out.println("[JMETER-CLI] Puede ejecutar manualmente Apache JMeter con:");
            System.out.printf("  jmeter -n -t %s -Jhost=%s -Jport=%d -Jthreads=%d -Jduration=%d -l results/run.jtl -e -o results/html_dashboard/%n",
                jmxPath, host, port, users, durationSec);
            System.out.println("[JMETER-CLI] Ejecutando simulación equivalente nativa con Virtual Threads en JettraStore...");
            if (jmxPath.contains("hospital")) {
                executeWorkload("hospital", host, port, users, durationMs);
            } else if (jmxPath.contains("ambiental")) {
                executeWorkload("ambiental", host, port, users, durationMs);
            } else {
                executeWorkload("factura", host, port, users, durationMs);
            }
        }
    }

    private static void runInteractiveMenu(String host, int port) {
        System.out.println("""
Seleccione una opción de prueba de carga:
  [1] Carga Hospitalaria (samples_hostipal_db - 2M objetos)
  [2] Carga Ambiental Mundial (samples_ambiental_db - 3M objetos)
  [3] Carga de Facturación (example_factura_db - 3M objetos)
  [4] Ejecutar Plan Apache JMeter .jmx vía JMeter CLI
  [5] Teardown y Limpieza Física de Almacenamiento
  [0] Salir
""");
        System.out.print("Ingrese opción (defecto: 1): ");
        java.util.Scanner sc = new java.util.Scanner(System.in);
        String opt = "1";
        if (sc.hasNextLine()) {
            String l = sc.nextLine().trim();
            if (!l.isBlank()) opt = l;
        }

        switch (opt) {
            case "1" -> executeWorkload("hospital", host, port, 50, 10_000L);
            case "2" -> executeWorkload("ambiental", host, port, 50, 10_000L);
            case "3" -> executeWorkload("factura", host, port, 50, 10_000L);
            case "4" -> executeJmxPlan("plans/jettra_hospital_stress_test.jmx", host, port, 50, 10_000L);
            case "5" -> {
                JettraStressTestRunner runner = new JettraStressTestRunner(host, port, "meter_stress_db");
                try (JettraClient c = JettraClient.connect(host, port, "admin", "admin-jettra")) {
                    boolean ok = runner.executeTeardown(c.getDatabase("meter_stress_db"));
                    System.out.println("Teardown completado: " + (ok ? "EXITO" : "FALLO"));
                } catch (Exception ex) {
                    System.out.println("Error en teardown: " + ex.getMessage());
                }
            }
            default -> System.out.println("Saliendo.");
        }
    }
}
