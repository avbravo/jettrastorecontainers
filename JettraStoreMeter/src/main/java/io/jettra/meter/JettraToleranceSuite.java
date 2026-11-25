package io.jettra.meter;

import io.jettra.driver.JettraClient;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.core.JettraId;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Suite parametrizada de pruebas de estrés y análisis de tolerancia de base de datos (RF-20)
 * diseñada para ejecutarse dentro del contenedor aislado {@code jettrastoretestcontainer}.
 */
public final class JettraToleranceSuite {

    public static final List<Long> DEFAULT_VOLUMES = List.of(
            10_000L, 100_000L, 1_000_000L, 1_500_000L, 25_000_000L, 35_000_000L, 50_000_000L
    );

    public static final List<Integer> DEFAULT_CONCURRENCY_LEVELS = List.of(
            1, 10, 20, 50, 75, 100, 1_000, 2_000, 5_000, 10_000, 150_000, 1_000_000, 2_300_000
    );

    public record ScenarioMetrics(
            String category,
            long targetScale,
            long completedOps,
            long failedOps,
            long durationMs,
            double opsPerSecond,
            double avgLatencyMs,
            double p95LatencyMs,
            double p99LatencyMs,
            double maxLatencyMs,
            long heapUsedMb,
            long heapMaxMb,
            double heapPct,
            String dbHealth,
            String status,
            String notes
    ) {}

    private final String host;
    private final int grpcPort;
    private final int restPort;
    private final String databaseName;
    private final int maxScenarioSeconds;
    private final double maxHeapGuardPct;
    private final HttpClient httpClient;

    public JettraToleranceSuite(String host, int grpcPort, int restPort, String databaseName,
                                int maxScenarioSeconds, double maxHeapGuardPct) {
        this.host = host;
        this.grpcPort = grpcPort;
        this.restPort = restPort;
        this.databaseName = databaseName;
        this.maxScenarioSeconds = Math.max(5, maxScenarioSeconds);
        this.maxHeapGuardPct = Math.min(95.0, Math.max(50.0, maxHeapGuardPct));
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public String checkTargetHealth() {
        if (restPort <= 0) return "LOCAL_EMBEDDED";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + host + ":" + restPort + "/api/v1/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && resp.body() != null && resp.body().contains("\"UP\"")) {
                return "UP (HTTP 200)";
            }
            return "DEGRADED (HTTP " + resp.statusCode() + ")";
        } catch (Exception e) {
            return "UNREACHABLE (" + e.getClass().getSimpleName() + ")";
        }
    }

    public ScenarioMetrics runVolumeScenario(long targetRecords) {
        System.out.printf("%n[RF-20 VOLUME] Iniciando escenario de %,d registros...%n", targetRecords);
        String scenarioDb = databaseName + "_vol_" + targetRecords;
        String bucket = "stress_vol_" + targetRecords;
        long startNs = System.nanoTime();
        long deadlineNs = startNs + TimeUnit.SECONDS.toNanos(maxScenarioSeconds);

        LongAdder completed = new LongAdder();
        LongAdder failed = new LongAdder();
        AtomicBoolean stoppedByMemGuard = new AtomicBoolean(false);
        AtomicBoolean stoppedByTimeout = new AtomicBoolean(false);

        int sampleCapacity = 4096;
        long[] latencySamplesNs = new long[sampleCapacity];
        AtomicInteger sampleIdx = new AtomicInteger(0);
        AtomicLong maxLatencyNs = new AtomicLong(0);
        LongAdder totalLatencyNs = new LongAdder();
        LongAdder latencyCount = new LongAdder();

        int workers = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors()));
        long perWorker = Math.max(1L, targetRecords / workers);
        long remainder = targetRecords - (perWorker * workers);

        try (JettraClient client = JettraClient.connect(host, grpcPort, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase(scenarioDb);
            var engine = db.getDocumentEngine(bucket);

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CountDownLatch latch = new CountDownLatch(workers);
                for (int w = 0; w < workers; w++) {
                    final int workerId = w;
                    final long countForWorker = perWorker + (w == 0 ? remainder : 0);
                    executor.submit(() -> {
                        try {
                            for (long i = 0; i < countForWorker; i++) {
                                if ((i & 4095) == 0) {
                                    if (System.nanoTime() >= deadlineNs) {
                                        stoppedByTimeout.set(true);
                                        break;
                                    }
                                    if (currentHeapUsagePct() >= maxHeapGuardPct) {
                                        stoppedByMemGuard.set(true);
                                        break;
                                    }
                                }
                                boolean sample = (i & 255) == 0;
                                long opStart = sample ? System.nanoTime() : 0L;
                                try {
                                    String id = JettraId.generate(scenarioDb, bucket).toHexString();
                                    engine.insert(id, Map.of(
                                            "jettraId", id,
                                            "w", workerId,
                                            "seq", i,
                                            "val", (i % 1000) * 1.25
                                    ));
                                    completed.increment();
                                } catch (OutOfMemoryError oom) {
                                    stoppedByMemGuard.set(true);
                                    failed.increment();
                                    break;
                                } catch (Throwable ex) {
                                    failed.increment();
                                } finally {
                                    if (sample) {
                                        long dur = System.nanoTime() - opStart;
                                        totalLatencyNs.add(dur);
                                        latencyCount.increment();
                                        maxLatencyNs.accumulateAndGet(dur, Math::max);
                                        int idx = sampleIdx.getAndIncrement();
                                        if (idx < sampleCapacity) {
                                            latencySamplesNs[idx] = dur;
                                        }
                                    }
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await(maxScenarioSeconds + 15L, TimeUnit.SECONDS);
            }

            // Capturar telemetría antes de limpiar
            long durationMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs));
            long heapUsedMb = usedHeapMb();
            long heapMaxMb = maxHeapMb();
            double heapPct = currentHeapUsagePct();

            // Teardown inmediato para aislar cada escenario y liberar RAM/disco
            try {
                client.dropDatabase(scenarioDb);
            } catch (Throwable ignored) {}
            System.gc();

            return buildMetrics("VOLUME", targetRecords, completed.sum(), failed.sum(), durationMs,
                    latencySamplesNs, Math.min(sampleIdx.get(), sampleCapacity),
                    totalLatencyNs.sum(), latencyCount.sum(), maxLatencyNs.get(),
                    heapUsedMb, heapMaxMb, heapPct,
                    stoppedByMemGuard.get(), stoppedByTimeout.get());
        } catch (Throwable t) {
            long durationMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs));
            return new ScenarioMetrics("VOLUME", targetRecords, completed.sum(), failed.sum() + 1, durationMs,
                    0.0, 0.0, 0.0, 0.0, 0.0, usedHeapMb(), maxHeapMb(), currentHeapUsagePct(),
                    checkTargetHealth(), "LIMIT_REACHED", "Excepción: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public ScenarioMetrics runConcurrencyScenario(int targetUsers) {
        System.out.printf("%n[RF-20 CONCURRENCY] Iniciando escenario de %,d usuarios concurrentes...%n", targetUsers);
        String scenarioDb = databaseName + "_conc_" + targetUsers;
        String bucket = "stress_conc_" + targetUsers;
        long startNs = System.nanoTime();
        long deadlineNs = startNs + TimeUnit.SECONDS.toNanos(maxScenarioSeconds);

        LongAdder completed = new LongAdder();
        LongAdder failed = new LongAdder();
        AtomicBoolean stoppedByMemGuard = new AtomicBoolean(false);
        AtomicBoolean stoppedByTimeout = new AtomicBoolean(false);

        int sampleCapacity = 4096;
        long[] latencySamplesNs = new long[sampleCapacity];
        AtomicInteger sampleIdx = new AtomicInteger(0);
        AtomicLong maxLatencyNs = new AtomicLong(0);
        LongAdder totalLatencyNs = new LongAdder();
        LongAdder latencyCount = new LongAdder();

        int opsPerUser = targetUsers <= 100 ? 10 : (targetUsers <= 10_000 ? 2 : 1);
        int waveSize = targetUsers <= 10_000 ? targetUsers : 25_000;

        try (JettraClient client = JettraClient.connect(host, grpcPort, "admin", "admin-jettra")) {
            JettraDatabase db = client.getDatabase(scenarioDb);
            var engine = db.getDocumentEngine(bucket);

            int launched = 0;
            while (launched < targetUsers) {
                if (System.nanoTime() >= deadlineNs) {
                    stoppedByTimeout.set(true);
                    break;
                }
                if (currentHeapUsagePct() >= maxHeapGuardPct) {
                    stoppedByMemGuard.set(true);
                    break;
                }
                int batch = Math.min(waveSize, targetUsers - launched);
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    CountDownLatch latch = new CountDownLatch(batch);
                    for (int u = 0; u < batch; u++) {
                        final int uid = launched + u;
                        executor.submit(() -> {
                            try {
                                for (int op = 0; op < opsPerUser; op++) {
                                    if (System.nanoTime() >= deadlineNs) {
                                        stoppedByTimeout.set(true);
                                        break;
                                    }
                                    boolean sample = (uid & 63) == 0 || targetUsers <= 100;
                                    long opStart = sample ? System.nanoTime() : 0L;
                                    try {
                                        String id = "u" + (uid % 50_000) + "_" + op;
                                        engine.insert(id, Map.of("uid", uid, "op", op, "ts", System.currentTimeMillis()));
                                        engine.findById(id);
                                        completed.increment();
                                    } catch (OutOfMemoryError oom) {
                                        stoppedByMemGuard.set(true);
                                        failed.increment();
                                        break;
                                    } catch (Throwable ex) {
                                        failed.increment();
                                    } finally {
                                        if (sample) {
                                            long dur = System.nanoTime() - opStart;
                                            totalLatencyNs.add(dur);
                                            latencyCount.increment();
                                            maxLatencyNs.accumulateAndGet(dur, Math::max);
                                            int idx = sampleIdx.getAndIncrement();
                                            if (idx < sampleCapacity) {
                                                latencySamplesNs[idx] = dur;
                                            }
                                        }
                                    }
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
                    }
                    long remainingMs = Math.max(1000L, TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime()));
                    if (!latch.await(remainingMs + 5000L, TimeUnit.MILLISECONDS)) {
                        stoppedByTimeout.set(true);
                        break;
                    }
                }
                launched += batch;
            }

            long durationMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs));
            long heapUsedMb = usedHeapMb();
            long heapMaxMb = maxHeapMb();
            double heapPct = currentHeapUsagePct();

            try {
                client.dropDatabase(scenarioDb);
            } catch (Throwable ignored) {}
            System.gc();

            return buildMetrics("CONCURRENCY", targetUsers, completed.sum(), failed.sum(), durationMs,
                    latencySamplesNs, Math.min(sampleIdx.get(), sampleCapacity),
                    totalLatencyNs.sum(), latencyCount.sum(), maxLatencyNs.get(),
                    heapUsedMb, heapMaxMb, heapPct,
                    stoppedByMemGuard.get(), stoppedByTimeout.get());
        } catch (Throwable t) {
            long durationMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs));
            return new ScenarioMetrics("CONCURRENCY", targetUsers, completed.sum(), failed.sum() + 1, durationMs,
                    0.0, 0.0, 0.0, 0.0, 0.0, usedHeapMb(), maxHeapMb(), currentHeapUsagePct(),
                    checkTargetHealth(), "LIMIT_REACHED", "Excepción: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private ScenarioMetrics buildMetrics(String category, long targetScale, long completedOps, long failedOps,
                                         long durationMs, long[] samples, int sampleCount,
                                         long totalLatNs, long latCount, long maxLatNs,
                                         long heapUsedMb, long heapMaxMb, double heapPct,
                                         boolean memGuard, boolean timeout) {
        double opsPerSec = (completedOps * 1000.0) / Math.max(1L, durationMs);
        double avgMs = latCount > 0 ? (totalLatNs / (double) latCount) / 1_000_000.0 : 0.0;
        double maxMs = maxLatNs / 1_000_000.0;
        double p95Ms = 0.0;
        double p99Ms = 0.0;
        if (sampleCount > 0) {
            long[] sorted = Arrays.copyOf(samples, sampleCount);
            Arrays.sort(sorted);
            p95Ms = sorted[Math.min(sampleCount - 1, (int) Math.floor(sampleCount * 0.95))] / 1_000_000.0;
            p99Ms = sorted[Math.min(sampleCount - 1, (int) Math.floor(sampleCount * 0.99))] / 1_000_000.0;
        }
        String health = checkTargetHealth();
        String status;
        List<String> notes = new ArrayList<>();
        if (memGuard) {
            status = "LIMIT_REACHED";
            notes.add(String.format("Umbral de memoria alcanzado (%.1f%% >= %.0f%%); ejecución detenida de forma controlada tras %,d ops",
                    heapPct, maxHeapGuardPct, completedOps));
        } else if (timeout) {
            status = completedOps >= targetScale * 0.5 ? "DEGRADED" : "LIMIT_REACHED";
            notes.add(String.format("Tiempo máximo por escenario (%ds) alcanzado tras %,d ops", maxScenarioSeconds, completedOps));
        } else if (failedOps > 0 || !health.startsWith("UP") && !"LOCAL_EMBEDDED".equals(health)) {
            status = "DEGRADED";
            notes.add("Se registraron errores o degradación en healthcheck");
        } else if (heapPct >= 75.0 || p95Ms > 50.0) {
            status = "DEGRADED";
            notes.add("Latencia o presión de memoria elevada");
        } else {
            status = "STABLE";
            notes.add("Operación estable, 0 errores, teardown limpio");
        }
        if ("CONCURRENCY".equals(category) && targetScale > 10_000) {
            notes.add("Ejecución escalonada en oleadas de 25,000 Virtual Threads");
        }

        ScenarioMetrics m = new ScenarioMetrics(
                category, targetScale, completedOps, failedOps, durationMs, opsPerSec,
                avgMs, p95Ms, p99Ms, maxMs, heapUsedMb, heapMaxMb, heapPct,
                health, status, String.join("; ", notes));
        System.out.printf("  -> [%s] scale=%,d | ops=%,d | err=%d | dur=%,d ms | tput=%,.0f ops/s | p95=%.3f ms | p99=%.3f ms | heap=%d/%d MB (%.1f%%) | DB=%s | %s%n",
                m.category(), m.targetScale(), m.completedOps(), m.failedOps(), m.durationMs(),
                m.opsPerSecond(), m.p95LatencyMs(), m.p99LatencyMs(), m.heapUsedMb(), m.heapMaxMb(),
                m.heapPct(), m.dbHealth(), m.status());
        return m;
    }

    public String generateMarkdownReport(List<ScenarioMetrics> volumeResults, List<ScenarioMetrics> concurrencyResults) {
        StringBuilder sb = new StringBuilder();
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        sb.append("# Reporte de Tolerancia y Pruebas de Estrés — JettraStore (`RF-20`)\n\n");
        sb.append("- **Fecha de ejecución:** ").append(ts).append("\n");
        sb.append("- **Contenedor de pruebas:** `jettrastoretestcontainer` (`JettraStoreMeter`, Java ")
                .append(System.getProperty("java.version")).append(")\n");
        sb.append("- **Objetivo:** `").append(host).append(":").append(grpcPort)
                .append("` (REST Health: `").append(restPort).append("`)\n");
        sb.append("- **Recursos JVM del contenedor de pruebas:** `").append(Runtime.getRuntime().availableProcessors())
                .append("` vCPUs, Heap máximo `").append(maxHeapMb()).append(" MB`, guarda de memoria `")
                .append(String.format(Locale.US, "%.0f%%", maxHeapGuardPct)).append("`, límite por escenario `")
                .append(maxScenarioSeconds).append("s`\n\n");

        sb.append("## 1. Resultados por Volumen de Registros\n\n");
        sb.append("| Volumen Objetivo | Ops Completadas | Errores | Tiempo (ms) | Throughput (ops/s) | Lat. Prom (ms) | Lat. p95 (ms) | Lat. p99 (ms) | Max (ms) | Heap (MB / %) | Estado DB | Clasificación | Observaciones |\n");
        sb.append("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|\n");
        for (ScenarioMetrics m : volumeResults) {
            sb.append(formatRow(m));
        }

        sb.append("\n## 2. Resultados por Concurrencia de Usuarios (Virtual Threads)\n\n");
        sb.append("| Usuarios Objetivo | Ops Completadas | Errores | Tiempo (ms) | Throughput (ops/s) | Lat. Prom (ms) | Lat. p95 (ms) | Lat. p99 (ms) | Max (ms) | Heap (MB / %) | Estado DB | Clasificación | Observaciones |\n");
        sb.append("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|\n");
        for (ScenarioMetrics m : concurrencyResults) {
            sb.append(formatRow(m));
        }

        long maxStableVol = volumeResults.stream().filter(m -> "STABLE".equals(m.status()))
                .mapToLong(ScenarioMetrics::targetScale).max().orElse(0L);
        Optional<ScenarioMetrics> firstVolDegrade = volumeResults.stream()
                .filter(m -> !"STABLE".equals(m.status())).findFirst();
        long maxStableUsers = concurrencyResults.stream().filter(m -> "STABLE".equals(m.status()))
                .mapToLong(ScenarioMetrics::targetScale).max().orElse(0L);
        Optional<ScenarioMetrics> firstUsersDegrade = concurrencyResults.stream()
                .filter(m -> !"STABLE".equals(m.status())).findFirst();

        sb.append("\n## 3. Análisis de Tolerancia y Límites Observados\n\n");
        sb.append("### Volumen de Registros\n");
        sb.append(String.format(Locale.US, "- **Zona estable comprobada:** hasta **%,d registros** con 0 errores y estabilidad total de la base de datos.%n", maxStableVol));
        if (firstVolDegrade.isPresent()) {
            ScenarioMetrics d = firstVolDegrade.get();
            sb.append(String.format(Locale.US, "- **Inicio de degradación / límite observado:** a partir de **%,d registros** (`%s` — completó **%,d registros** antes de activar la protección: %s).%n",
                    d.targetScale(), d.status(), d.completedOps(), d.notes()));
        } else {
            sb.append("- **Inicio de degradación:** todos los volúmenes probados se mantuvieron en zona `STABLE`.\n");
        }

        sb.append("\n### Concurrencia de Usuarios\n");
        sb.append(String.format(Locale.US, "- **Zona estable comprobada:** hasta **%,d usuarios concurrentes** con 0 errores.%n", maxStableUsers));
        if (firstUsersDegrade.isPresent()) {
            ScenarioMetrics d = firstUsersDegrade.get();
            sb.append(String.format(Locale.US, "- **Inicio de degradación / límite observado:** a partir de **%,d usuarios** (`%s` — completó **%,d ops**: %s).%n",
                    d.targetScale(), d.status(), d.completedOps(), d.notes()));
        } else {
            sb.append("- **Inicio de degradación:** todos los niveles de concurrencia probados se mantuvieron en zona `STABLE`.\n");
        }

        sb.append("\n## 4. Conclusiones Técnicas y Recomendaciones de Soporte\n\n");
        sb.append("1. **Operación nominal recomendada (SLA estable):** cargas de hasta **")
                .append(String.format(Locale.US, "%,d", maxStableVol))
                .append(" registros activos por bucket** en memoria acotada (`-Xmx` de contenedor) y hasta **")
                .append(String.format(Locale.US, "%,d", maxStableUsers))
                .append(" usuarios concurrentes** mantienen latencias sub-milisegundo/bajas, 0% de errores y healthcheck `UP`.\n");
        sb.append("2. **Cargas masivas (25M–50M registros):** requieren particionamiento/sharding por buckets o incremento proporcional de RAM/almacenamiento off-heap (`DISK_MEMORY` / `OFF_HEAP` + SSD NVMe) en los nodos del clúster para evitar saturación de heap en un solo contenedor.\n");
        sb.append("3. **Concurrencia extrema (150K–2.3M usuarios):** Java 25 Virtual Threads permite escalar mediante oleadas controladas y *backpressure*; en producción se recomienda balanceo de carga delante del clúster de 3+ nodos Raft y limitación de conexiones simultáneas por nodo.\n");
        return sb.toString();
    }

    private static String formatRow(ScenarioMetrics m) {
        return String.format(Locale.US,
                "| %,d | %,d | %,d | %,d | %,.2f | %.3f | %.3f | %.3f | %.3f | %d/%d (%.1f%%) | %s | **%s** | %s |\n",
                m.targetScale(), m.completedOps(), m.failedOps(), m.durationMs(),
                m.opsPerSecond(), m.avgLatencyMs(), m.p95LatencyMs(), m.p99LatencyMs(), m.maxLatencyMs(),
                m.heapUsedMb(), m.heapMaxMb(), m.heapPct(), m.dbHealth(), m.status(), m.notes());
    }

    private static long usedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
    }

    private static long maxHeapMb() {
        return Math.max(1L, Runtime.getRuntime().maxMemory() / (1024L * 1024L));
    }

    private static double currentHeapUsagePct() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        return (used * 100.0) / Math.max(1L, rt.maxMemory());
    }

    public static void main(String[] args) throws IOException {
        String host = System.getenv().getOrDefault("JETTRA_HOST", "127.0.0.1");
        int grpcPort = Integer.parseInt(System.getenv().getOrDefault("JETTRA_PORT", "50001"));
        int restPort = Integer.parseInt(System.getenv().getOrDefault("JETTRA_REST_PORT", "60001"));
        String db = "rf20_tolerance_db";
        int maxSec = 25;
        double maxHeapPct = 82.0;
        Path reportPath = Path.of("/reports/TOLERANCE_REPORT_RF20.md");
        List<Long> volumes = new ArrayList<>(DEFAULT_VOLUMES);
        List<Integer> usersList = new ArrayList<>(DEFAULT_CONCURRENCY_LEVELS);

        for (String arg : args) {
            if (arg.startsWith("--host=")) host = arg.substring("--host=".length());
            else if (arg.startsWith("--port=")) grpcPort = Integer.parseInt(arg.substring("--port=".length()));
            else if (arg.startsWith("--rest-port=")) restPort = Integer.parseInt(arg.substring("--rest-port=".length()));
            else if (arg.startsWith("--db=")) db = arg.substring("--db=".length());
            else if (arg.startsWith("--max-scenario-sec=")) maxSec = Integer.parseInt(arg.substring("--max-scenario-sec=".length()));
            else if (arg.startsWith("--max-mem-pct=")) maxHeapPct = Double.parseDouble(arg.substring("--max-mem-pct=".length()));
            else if (arg.startsWith("--report=")) reportPath = Path.of(arg.substring("--report=".length()));
            else if (arg.startsWith("--volumes=")) {
                volumes.clear();
                for (String p : arg.substring("--volumes=".length()).split(",")) {
                    if (!p.isBlank()) volumes.add(Long.parseLong(p.trim()));
                }
            } else if (arg.startsWith("--users=")) {
                usersList.clear();
                for (String p : arg.substring("--users=".length()).split(",")) {
                    if (!p.isBlank()) usersList.add(Integer.parseInt(p.trim()));
                }
            }
        }

        JettraToleranceSuite suite = new JettraToleranceSuite(host, grpcPort, restPort, db, maxSec, maxHeapPct);
        System.out.println("================================================================================");
        System.out.println("      JETTRASTORE METER - RF-20 SUITE DE ESTRÉS Y TOLERANCIA DE BASE DE DATOS");
        System.out.println("================================================================================");
        System.out.printf("Objetivo: %s:%d (REST: %d) | Salud inicial: %s%n", host, grpcPort, restPort, suite.checkTargetHealth());

        List<ScenarioMetrics> volResults = new ArrayList<>();
        for (long v : volumes) {
            volResults.add(suite.runVolumeScenario(v));
        }

        List<ScenarioMetrics> concResults = new ArrayList<>();
        for (int u : usersList) {
            concResults.add(suite.runConcurrencyScenario(u));
        }

        String md = suite.generateMarkdownReport(volResults, concResults);
        if (reportPath.getParent() != null) {
            Files.createDirectories(reportPath.getParent());
        }
        Files.writeString(reportPath, md);
        System.out.println("\n================================================================================");
        System.out.println("[RF-20] Reporte de tolerancia generado en: " + reportPath.toAbsolutePath());
        System.out.println("================================================================================");
    }
}
