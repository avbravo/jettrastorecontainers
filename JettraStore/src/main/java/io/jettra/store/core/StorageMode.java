package io.jettra.store.core;

/**
 * Define el modo de almacenamiento y carga de datos en JettraStore:
 * - JVM_RAM: Carga y manipulación de datos en memoria RAM de la JVM (Heap y Stack).
 * - DISK_MEMORY: Modo directo en disco sin pausas de GC usando JettraMemory (LSM Off-Heap FFM).
 * - OFF_HEAP: Motor de páginas ranuradas nativas Off-Heap FFM (JettraStore-OffHeap) con cabeceras de 8 bytes y RAII.
 */
public enum StorageMode {
    JVM_RAM("JVM-RAM", "Memoria RAM estándar de la JVM (Heap & Stack)"),
    DISK_MEMORY("DISK-MEMORY", "Motor de disco directo Off-Heap LSM (JettraMemory)"),
    OFF_HEAP("OFF-HEAP", "Motor de páginas nativas Slotted-Page Off-Heap FFM (JettraStore-OffHeap)");

    private final String code;
    private final String description;

    StorageMode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public boolean isDiskMemory() {
        return this == DISK_MEMORY;
    }

    public boolean isJvmRam() {
        return this == JVM_RAM;
    }

    public boolean isOffHeap() {
        return this == OFF_HEAP;
    }

    public static StorageMode fromString(String val) {
        if (val == null || val.isBlank()) {
            return JVM_RAM;
        }
        String s = val.trim().toUpperCase().replace("-", "_");
        if (s.contains("OFF_HEAP") || s.contains("OFFHEAP")) {
            return OFF_HEAP;
        }
        if (s.contains("DISK") || s.contains("JETTRA_MEMORY") || s.contains("JETTRAMEMORY")) {
            return DISK_MEMORY;
        }
        return JVM_RAM;
    }
}
