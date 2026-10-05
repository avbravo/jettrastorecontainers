package io.jettra.store.police;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Centinela y Supervisor Autónomo de Estabilidad de Memoria (JettraPolice).
 *
 * Analiza de forma continua y predictiva la presión en el Heap (JVM Heap Space).
 * Cuando una operación (como SELECT masivos, escaneos o procesamientos de millones de registros)
 * amenaza con agotar la memoria del Heap, JettraPolice se activa proactivamente:
 *  1. Interviene y previene la materialización monolítica en memoria RAM.
 *  2. Impone de forma obligatoria paginación automática (Auto-Pagination) con límites seguros.
 *  3. Distribuye la carga a través de cursores y streaming perezoso (Lazy Load).
 *  4. Emite alertas y telemetría de estabilidad para el clúster y la interfaz 3D (JettraStorePoliceFX).
 */
public final class JettraPolice implements Runnable {
    private static final JettraPolice INSTANCE = new JettraPolice();
    public static JettraPolice getInstance() { return INSTANCE; }

    public enum PoliceAction {
        PERMITTED,
        AUTO_PAGINATE_LAZY,
        ADAPTIVE_THROTTLE,
        REJECT_MEMORY_OVERFLOW
    }

    public record PoliceDecision(
        boolean interventionRequired,
        PoliceAction action,
        int enforcedLimit,
        int recommendedPageSize,
        double heapSaturationPercent,
        long estimatedBytesRequired,
        String rationale
    ) {}

    public record PoliceAlert(Instant timestamp, String code, String message) {}

    private final AtomicBoolean active = new AtomicBoolean(true);
    private final List<PoliceAlert> alerts = new CopyOnWriteArrayList<>();
    private final List<Consumer<PoliceDecision>> decisionListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<JettraPoliceNotification>> notificationListeners = new CopyOnWriteArrayList<>();
    private volatile JettraPoliceNotification lastNotification;

    private long intervalMs = 500;
    private double ramWarningThreshold = 75.0;  // % de saturación de Heap para advertencia
    private double ramCriticalThreshold = 85.0; // % de saturación crítica de Heap
    private int maxSafeBatchSize = 100;         // Límite máximo de filas por página bajo intervención
    private Thread policeThread;

    private JettraPolice() {}

    @Override
    public void run() {
        while (active.get()) {
            try {
                Thread.sleep(intervalMs);
                runPreventiveChecks();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public synchronized void start(boolean enabled, long intervalMs) {
        this.intervalMs = intervalMs;
        this.active.set(enabled);
        if (!enabled) {
            recordAlert("POLICE_DISABLED", "JettraPolice supervisor is configured inactive (jettrapolice.active = false).");
            return;
        }

        recordAlert("POLICE_STARTED", "JettraPolice autonomous supervisor initialized in background daemon thread.");
        policeThread = Thread.ofVirtual().name("jettra-police-sentinel").start(this);
    }

    /**
     * Evalúa de forma predictiva si una consulta u operación agotaría la memoria del Heap (Java Heap Space).
     * Toma decisiones autónomas y forzosas para garantizar la estabilidad total del sistema.
     *
     * @param operation Nombre de la operación (e.g., "SQL_SELECT", "JQL_FROM", "SCAN_ALL")
     * @param collection Nombre de la colección o bucket
     * @param estimatedRecords Cantidad total estimada de registros en la colección
     * @param requestedLimit Límite solicitado por el cliente (<= 0 o Integer.MAX_VALUE = sin límite)
     * @param avgRecordSizeBytes Tamaño promedio estimado de cada registro en bytes
     * @return Decisión vinculante de JettraPolice con las directivas de seguridad
     */
    public PoliceDecision evaluateHeapSafety(String operation, String collection, long estimatedRecords, int requestedLimit, long avgRecordSizeBytes) {
        long maxMemory = Runtime.getRuntime().maxMemory();
        long totalMemory = Runtime.getRuntime().totalMemory();
        long freeMemory = Runtime.getRuntime().freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long availableMemory = maxMemory - usedMemory;

        double saturationPercent = ((double) usedMemory / (double) maxMemory) * 100.0;

        // Determinar cuántos registros intentaría traer la operación si no se acotase
        long targetCount = (requestedLimit > 0) ? Math.min(requestedLimit, estimatedRecords) : estimatedRecords;
        long safeAvgBytes = Math.max(avgRecordSizeBytes, 384L); // Estimación mínima por documento con mapa y referencias
        long estimatedBytes = targetCount * safeAvgBytes;

        // Criterio de Riesgo de Agotamiento de Heap:
        // 1. Si no hay límite explícito o es excesivo (> 500) y la colección supera los 200 registros.
        // 2. O si los bytes estimados superan el 20% de la memoria Heap disponible restante.
        // 3. O si la saturación actual del Heap ya supera el umbral de advertencia (e.g. >= 75%).
        boolean unconstrainedScan = (requestedLimit <= 0 || requestedLimit > 500) && estimatedRecords > 200;
        boolean memoryExhaustionRisk = (estimatedBytes > (availableMemory * 0.20));
        boolean heapUnderPressure = (saturationPercent >= ramWarningThreshold);

        if (unconstrainedScan || memoryExhaustionRisk || heapUnderPressure) {
            // JettraPolice se ACTIVA y toma el control de la operación
            int calculatedSafeLimit = calculateAdaptiveSafePageSize(availableMemory, safeAvgBytes);
            int enforcedLimit = (requestedLimit > 0) ? Math.min(requestedLimit, calculatedSafeLimit) : calculatedSafeLimit;

            String rationale = String.format(
                "JettraPolice activado: Operación '%s' sobre '%s' (%d registros solicitados) amenazaba con agotar el Heap " +
                "(Est: %d MB requeridos, Disp: %d MB, Saturación Heap: %.1f%%). Se forzó paginación lazy a %d filas para garantizar estabilidad.",
                operation, collection, targetCount,
                estimatedBytes / (1024 * 1024), availableMemory / (1024 * 1024),
                saturationPercent, enforcedLimit
            );

            recordAlert("HEAP_EXHAUSTION_PREVENTED", rationale);

            PoliceDecision decision = new PoliceDecision(
                true,
                PoliceAction.AUTO_PAGINATE_LAZY,
                enforcedLimit,
                enforcedLimit,
                saturationPercent,
                estimatedBytes,
                rationale
            );

            notifyListeners(decision);

            JettraPoliceNotification notification = JettraPoliceNotification.of(
                operation,
                collection,
                estimatedRecords,
                enforcedLimit,
                saturationPercent,
                availableMemory / (1024 * 1024),
                rationale
            );
            emitNotification(notification);

            return decision;
        }

        // Operación segura dentro de los límites de memoria
        return new PoliceDecision(
            false,
            PoliceAction.PERMITTED,
            requestedLimit > 0 ? requestedLimit : (int) estimatedRecords,
            50,
            saturationPercent,
            estimatedBytes,
            "Operación autorizada dentro de los márgenes seguros de memoria Heap."
        );
    }

    private int calculateAdaptiveSafePageSize(long availableMemoryBytes, long avgRecordBytes) {
        if (availableMemoryBytes <= 0) return 10;
        // Asignar a lo sumo el 2% de la memoria restante a este lote de consulta
        long safeQuotaBytes = (long) (availableMemoryBytes * 0.02);
        long calculated = safeQuotaBytes / Math.max(1, avgRecordBytes);
        return (int) Math.max(10, Math.min(maxSafeBatchSize, calculated));
    }

    public void addDecisionListener(Consumer<PoliceDecision> listener) {
        decisionListeners.add(listener);
    }

    private void notifyListeners(PoliceDecision decision) {
        for (Consumer<PoliceDecision> listener : decisionListeners) {
            try {
                listener.accept(decision);
            } catch (Exception ignored) {}
        }
    }

    public void addNotificationListener(Consumer<JettraPoliceNotification> listener) {
        notificationListeners.add(listener);
    }

    public void removeNotificationListener(Consumer<JettraPoliceNotification> listener) {
        notificationListeners.remove(listener);
    }

    public void emitNotification(JettraPoliceNotification notification) {
        this.lastNotification = notification;
        for (Consumer<JettraPoliceNotification> listener : notificationListeners) {
            try {
                listener.accept(notification);
            } catch (Exception ignored) {}
        }
    }

    public JettraPoliceNotification getLastNotification() {
        return lastNotification;
    }

    public void clearLastNotification() {
        this.lastNotification = null;
    }

    private void runPreventiveChecks() {
        long max = Runtime.getRuntime().maxMemory();
        long total = Runtime.getRuntime().totalMemory();
        long free = Runtime.getRuntime().freeMemory();
        long used = total - free;
        double saturation = ((double) used / (double) max) * 100.0;

        if (saturation >= ramCriticalThreshold) {
            recordAlert("CRITICAL_RAM_PRESSURE", 
                String.format("Presión crítica en Heap: %.1f%% (%d MB usados de %d MB). Activando modo ultra-defensivo.",
                    saturation, used / (1024 * 1024), max / (1024 * 1024)));
        } else if (saturation >= ramWarningThreshold) {
            recordAlert("WARNING_RAM_PRESSURE", 
                String.format("Presión moderada en Heap: %.1f%%. Recomendando streaming perezoso.", saturation));
        }
    }

    public void recordAlert(String code, String message) {
        PoliceAlert alert = new PoliceAlert(Instant.now(), code, message);
        alerts.add(alert);
        if (alerts.size() > 500) {
            alerts.removeFirst();
        }
    }

    public void stop() {
        active.set(false);
        if (policeThread != null) {
            policeThread.interrupt();
        }
    }

    public boolean isActive() { return active.get(); }
    public List<PoliceAlert> getAlerts() { return List.copyOf(alerts); }

    public double getRamWarningThreshold() { return ramWarningThreshold; }
    public void setRamWarningThreshold(double threshold) { this.ramWarningThreshold = threshold; }

    public double getRamCriticalThreshold() { return ramCriticalThreshold; }
    public void setRamCriticalThreshold(double threshold) { this.ramCriticalThreshold = threshold; }

    public int getMaxSafeBatchSize() { return maxSafeBatchSize; }
    public void setMaxSafeBatchSize(int size) { this.maxSafeBatchSize = size; }

    /**
     * Cursor perezoso de distribución de carga por páginas (Lazy Paged Cursor).
     * Permite recorrer colecciones masivas de forma escalonada, liberando páginas anteriores para el GC.
     */
    public static final class LazyPagedCursor<T> {
        private final BiFunction<Integer, Integer, List<T>> pageSupplier;
        private final int pageSize;
        private int currentOffset;
        private boolean hasMore = true;

        public LazyPagedCursor(int pageSize, BiFunction<Integer, Integer, List<T>> pageSupplier) {
            this.pageSize = Math.max(1, pageSize);
            this.pageSupplier = pageSupplier;
            this.currentOffset = 0;
        }

        public boolean hasNextPage() {
            return hasMore;
        }

        public List<T> fetchNextPage() {
            if (!hasMore) return Collections.emptyList();
            List<T> page = pageSupplier.apply(currentOffset, pageSize);
            currentOffset += page.size();
            if (page.size() < pageSize) {
                hasMore = false;
            }
            return page;
        }

        public int getCurrentOffset() { return currentOffset; }
        public int getPageSize() { return pageSize; }
    }
}
