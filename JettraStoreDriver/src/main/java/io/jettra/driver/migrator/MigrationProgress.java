package io.jettra.driver.migrator;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Progreso y métricas en vivo de la ejecución de la migración de PostgreSQL a JettraStore.
 */
public class MigrationProgress implements Serializable {

    private String currentTable = "";
    private int tablesMigrated = 0;
    private int totalTables = 0;
    private long recordsMigrated = 0;
    private long totalRecords = 0;
    private List<String> errors = new ArrayList<>();
    private List<String> logs = new ArrayList<>();
    private boolean completed = false;
    private boolean hasErrors = false;
    private String message = "Iniciando migración...";
    private long elapsedMs = 0;
    private byte[] generatedZipBytes;

    public MigrationProgress() {}

    public void addLog(String log) {
        this.logs.add(log);
        this.message = log;
    }

    public void addError(String err) {
        this.errors.add(err);
        this.hasErrors = true;
    }

    public int getPercentComplete() {
        if (totalTables <= 0) return 0;
        int pct = (int) Math.round(((double) tablesMigrated / totalTables) * 100);
        return Math.min(100, Math.max(0, pct));
    }

    public String getCurrentTable() { return currentTable; }
    public void setCurrentTable(String currentTable) { this.currentTable = currentTable; }

    public int getTablesMigrated() { return tablesMigrated; }
    public void setTablesMigrated(int tablesMigrated) { this.tablesMigrated = tablesMigrated; }

    public int getTotalTables() { return totalTables; }
    public void setTotalTables(int totalTables) { this.totalTables = totalTables; }

    public long getRecordsMigrated() { return recordsMigrated; }
    public void setRecordsMigrated(long recordsMigrated) { this.recordsMigrated = recordsMigrated; }

    public long getTotalRecords() { return totalRecords; }
    public void setTotalRecords(long totalRecords) { this.totalRecords = totalRecords; }

    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> errors) { this.errors = errors; }

    public List<String> getLogs() { return logs; }
    public void setLogs(List<String> logs) { this.logs = logs; }

    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }

    public boolean isHasErrors() { return hasErrors; }
    public void setHasErrors(boolean hasErrors) { this.hasErrors = hasErrors; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }

    public byte[] getGeneratedZipBytes() { return generatedZipBytes; }
    public void setGeneratedZipBytes(byte[] generatedZipBytes) { this.generatedZipBytes = generatedZipBytes; }
}
