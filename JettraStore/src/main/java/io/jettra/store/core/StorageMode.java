package io.jettra.store.core;

/**
 * Define el modo de almacenamiento y carga de datos en JettraStore:
 * - JVM_RAM: Carga y manipulación de datos en memoria RAM de la JVM (Heap y Stack).
 * - DISK_MEMORY: Modo directo en disco sin pausas de GC usando JettraMemory (LSM Off-Heap FFM).
 */
public enum StorageMode {
    JVM_RAM("JVM-RAM", "Memoria RAM estándar de la JVM (Heap & Stack)"),
    DISK_MEMORY("DISK-MEMORY", "Motor de disco directo Off-Heap LSM (JettraMemory)");

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

    public static StorageMode fromString(String val) {
        if (val == null || val.isBlank()) {
            return JVM_RAM;
        }
        String s = val.trim().toUpperCase().replace("-", "_");
        if (s.contains("DISK") || s.contains("JETTRA_MEMORY") || s.contains("JETTRAMEMORY")) {
            return DISK_MEMORY;
        }
        return JVM_RAM;
    }
}
