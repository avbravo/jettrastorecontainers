package com.jettra.memory.gc;

import com.jettra.memory.engine.DiskStorageEngine;
import com.jettra.memory.engine.StorageMetrics;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Recolector de basura personalizado para JettraMemory (Custom Garbage Collector).
 * Totalmente desacoplado del GC de la JVM, opera directamente sobre el espacio de disco
 * y la fragmentación generada por actualizaciones sucesivas y registros Tombstones.
 * Ejecuta tareas de compactación automáticas o manuales sobre hilos virtuales (Virtual Threads).
 */
public final class JettraGarbageCollector implements AutoCloseable {

    private final DiskStorageEngine storageEngine;
    private final double fragmentationThreshold;
    private final AtomicBoolean isCompacting = new AtomicBoolean(false);
    private final AtomicInteger gcCyclesCount = new AtomicInteger(0);
    private final AtomicLong totalBytesReclaimed = new AtomicLong(0);
    private volatile long lastCompactionTimestamp = 0;
    private volatile boolean running = true;
    private Thread autoGcThread;

    static {
        try {
            Class.forName(StorageMetrics.class.getName());
        } catch (Throwable ignored) {}
    }

    public JettraGarbageCollector(DiskStorageEngine storageEngine, double fragmentationThreshold, boolean autoGcEnabled) {
        this.storageEngine = storageEngine;
        this.fragmentationThreshold = fragmentationThreshold;

        if (autoGcEnabled) {
            startBackgroundCollector();
        }
    }

    private void startBackgroundCollector() {
        Thread thread = Thread.ofVirtual().name("Jettra-Autonomous-GC").unstarted(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5000); // Evaluar cada 5 segundos
                    if (!running) break;

                    StorageMetrics metrics = storageEngine.getMetrics();
                    if (metrics != null && metrics.fragmentationRatio() >= fragmentationThreshold && metrics.deadBytes() > 0) {
                        compact();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    // Detener el bucle limpiamente si el ClassLoader ha sido cerrado o se presenta un Error de enlace
                    break;
                }
            }
        });
        thread.setContextClassLoader(JettraGarbageCollector.class.getClassLoader());
        this.autoGcThread = thread;
        thread.start();
    }

    /**
     * Ejecuta una compactación síncrona manual de disco.
     */
    public CompactionResult compact() throws Exception {
        if (!isCompacting.compareAndSet(false, true)) {
            // Ya hay una compactación en progreso
            return new CompactionResult(0, 0, 0, 0, 0, 0);
        }

        try {
            CompactionTask task = new CompactionTask(storageEngine);
            CompactionResult result = task.call();

            gcCyclesCount.incrementAndGet();
            totalBytesReclaimed.addAndGet(result.reclaimedBytes());
            lastCompactionTimestamp = System.currentTimeMillis();

            return result;
        } finally {
            isCompacting.set(false);
        }
    }

    /**
     * Dispara una compactación asíncrona sobre un Virtual Thread.
     */
    public CompletableFuture<CompactionResult> compactAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return compact();
            } catch (Exception e) {
                throw new RuntimeException("Fallo en la compactación asíncrona de JettraMemory", e);
            }
        }, task -> Thread.ofVirtual().name("Jettra-Async-Compaction").start(task));
    }

    public boolean isCompacting() {
        return isCompacting.get();
    }

    public int getGcCyclesCount() {
        return gcCyclesCount.get();
    }

    public long getTotalBytesReclaimed() {
        return totalBytesReclaimed.get();
    }

    public long getLastCompactionTimestamp() {
        return lastCompactionTimestamp;
    }

    public double getFragmentationThreshold() {
        return fragmentationThreshold;
    }

    @Override
    public void close() {
        running = false;
        if (autoGcThread != null && autoGcThread.isAlive()) {
            autoGcThread.interrupt();
        }
    }
}
