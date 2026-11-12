package io.jettra.store.explorer.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Información de un registro o documento en un Bucket/Motor de JettraStore,
 * incluyendo control de versiones histórico y capacidad de restauración.
 */
public class EngineRecordInfo implements Serializable {
    private String id;
    private String engineType;
    private String bucketName;
    private String summary;
    private String details;
    private String timestamp;
    private long version;
    private final List<EngineRecordVersion> versionHistory = new ArrayList<>();

    public EngineRecordInfo() {
        this.version = 1L;
    }

    public EngineRecordInfo(String id, String engineType, String bucketName, String summary, String details, String timestamp, long version) {
        this.id = id;
        this.engineType = engineType;
        this.bucketName = bucketName;
        this.summary = summary;
        this.details = details;
        this.timestamp = timestamp;
        this.version = Math.max(1L, version);
        String effectiveTs = (timestamp != null && !timestamp.isBlank()) ? timestamp : "2026-10-01 00:00:00";
        // Registrar versión actual y garantizar nodo raíz v1 en la cascada histórica
        if (this.version > 1L) {
            this.versionHistory.add(new EngineRecordVersion(this.version, effectiveTs, summary, details, "admin", "Actualización de registro (v" + this.version + ")"));
            this.versionHistory.add(new EngineRecordVersion(1L, effectiveTs, summary, details, "admin", "Creación inicial del registro (v1)"));
        } else {
            this.versionHistory.add(new EngineRecordVersion(1L, effectiveTs, summary, details, "admin", "Creación inicial del registro (v1)"));
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEngineType() { return engineType; }
    public void setEngineType(String engineType) { this.engineType = engineType; }
    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) { this.bucketName = bucketName; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    /**
     * Garantiza que la línea temporal incluya la versión actual y el nodo raíz (versión 1)
     * cuando exista historial incompleto, deserialización delta o registros heredados con v > 1.
     */
    private void ensureRootVersionPresent() {
        long currentVer = Math.max(1L, this.version);
        this.version = currentVer;
        String effectiveTs = (timestamp != null && !timestamp.isBlank()) ? timestamp : "2026-10-01 00:00:00";

        if (versionHistory.isEmpty()) {
            if (currentVer > 1L) {
                versionHistory.add(new EngineRecordVersion(currentVer, effectiveTs, summary, details, "admin", "Estado registrado (v" + currentVer + ")"));
            }
            versionHistory.add(new EngineRecordVersion(1L, effectiveTs, summary, details, "admin", "Creación inicial del registro (v1)"));
            return;
        }

        boolean hasV1 = versionHistory.stream().anyMatch(v -> v.getVersion() == 1L);
        if (!hasV1) {
            EngineRecordVersion oldest = versionHistory.get(versionHistory.size() - 1);
            String rootTs = (oldest.getTimestamp() != null && !oldest.getTimestamp().isBlank()) ? oldest.getTimestamp() : effectiveTs;
            versionHistory.add(new EngineRecordVersion(1L, rootTs, oldest.getSummary(), oldest.getDetails(), "admin", "Creación inicial del registro (v1)"));
        }
    }

    public synchronized List<EngineRecordVersion> getVersionHistory() {
        ensureRootVersionPresent();
        return Collections.unmodifiableList(new ArrayList<>(versionHistory));
    }

    public synchronized void recordNewVersion(String newSummary, String newDetails, String author, String note) {
        ensureRootVersionPresent();
        this.summary = newSummary;
        this.details = newDetails;
        this.version++;
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        this.versionHistory.add(0, new EngineRecordVersion(this.version, this.timestamp, this.summary, this.details, author, note));
    }

    public synchronized boolean restoreVersion(long targetVersionNumber) {
        ensureRootVersionPresent();
        for (EngineRecordVersion v : versionHistory) {
            if (v.getVersion() == targetVersionNumber) {
                this.summary = v.getSummary();
                this.details = v.getDetails();
                this.version++;
                this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                this.versionHistory.add(0, new EngineRecordVersion(this.version, this.timestamp, this.summary, this.details, "admin", "Restaurado a partir de v" + targetVersionNumber));
                return true;
            }
        }
        return false;
    }
}
