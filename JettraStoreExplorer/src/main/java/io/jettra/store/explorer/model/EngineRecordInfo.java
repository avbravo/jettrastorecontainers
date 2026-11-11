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

    public EngineRecordInfo() {}

    public EngineRecordInfo(String id, String engineType, String bucketName, String summary, String details, String timestamp, long version) {
        this.id = id;
        this.engineType = engineType;
        this.bucketName = bucketName;
        this.summary = summary;
        this.details = details;
        this.timestamp = timestamp;
        this.version = version;
        // Registrar versión inicial
        this.versionHistory.add(new EngineRecordVersion(version, timestamp, summary, details, "admin", "Creación inicial del registro"));
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

    public synchronized List<EngineRecordVersion> getVersionHistory() {
        if (versionHistory.isEmpty()) {
            versionHistory.add(new EngineRecordVersion(version, timestamp != null ? timestamp : "2026-10-01 00:00:00", summary, details, "admin", "Versión inicial v" + version));
        }
        return Collections.unmodifiableList(new ArrayList<>(versionHistory));
    }

    public synchronized void recordNewVersion(String newSummary, String newDetails, String author, String note) {
        this.summary = newSummary;
        this.details = newDetails;
        this.version++;
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        this.versionHistory.add(0, new EngineRecordVersion(this.version, this.timestamp, this.summary, this.details, author, note));
    }

    public synchronized boolean restoreVersion(long targetVersionNumber) {
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
