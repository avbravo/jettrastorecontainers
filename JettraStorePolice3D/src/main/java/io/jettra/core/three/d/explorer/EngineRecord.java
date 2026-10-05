package io.jettra.core.three.d.explorer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Representa un objeto o registro individual almacenado dentro de un engine especializado de JettraStore.
 * Incluye soporte multimodelo con campos tipados, anotaciones JettraRules,
 * historial de auditoria de versiones y capacidad de restauracion en caliente.
 */
public class EngineRecord {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String id;
    private final String engineType;
    private final String bucketName;
    private String summary;
    private String details;
    private String timestamp;
    private int currentVersion = 1;
    private final List<RecordVersion> versionHistory = new ArrayList<>();
    private final List<RecordFieldInfo> recordFields = new ArrayList<>();

    public EngineRecord(String id, String engineType, String bucketName, String summary, String details, String timestamp) {
        this(id, engineType, bucketName, summary, details, timestamp, null);
    }

    public EngineRecord(String id, String engineType, String bucketName, String summary, String details, String timestamp, List<RecordFieldInfo> fields) {
        this.id = id;
        this.engineType = engineType;
        this.bucketName = bucketName;
        this.summary = summary;
        this.details = details;
        this.timestamp = (timestamp != null && !timestamp.isBlank()) ? timestamp : LocalDateTime.now().format(FMT);
        this.currentVersion = 1;

        if (fields != null && !fields.isEmpty()) {
            for (RecordFieldInfo f : fields) {
                this.recordFields.add(f.copy());
            }
        } else if ("JAVA_RECORD".equals(engineType)) {
            this.recordFields.addAll(RecordFieldInfo.parseFromDetails(details, summary, id));
        }

        List<RecordFieldInfo> vFields = new ArrayList<>();
        for (RecordFieldInfo f : this.recordFields) {
            vFields.add(f.copy());
        }
        this.versionHistory.add(new RecordVersion(1, this.summary, this.details, this.timestamp, "Version inicial creada", vFields));
    }

    public synchronized void update(String newSummary, String newDetails, String note) {
        update(newSummary, newDetails, note, null);
    }

    public synchronized void update(String newSummary, String newDetails, String note, List<RecordFieldInfo> newFields) {
        this.currentVersion++;
        this.summary = newSummary;
        this.details = newDetails;
        this.timestamp = LocalDateTime.now().format(FMT);

        if (newFields != null) {
            this.recordFields.clear();
            for (RecordFieldInfo f : newFields) {
                this.recordFields.add(f.copy());
            }
        } else if ("JAVA_RECORD".equals(engineType)) {
            this.recordFields.clear();
            this.recordFields.addAll(RecordFieldInfo.parseFromDetails(newDetails, newSummary, id));
        }

        List<RecordFieldInfo> vFields = new ArrayList<>();
        for (RecordFieldInfo f : this.recordFields) {
            vFields.add(f.copy());
        }

        this.versionHistory.add(new RecordVersion(
            this.currentVersion,
            newSummary,
            newDetails,
            this.timestamp,
            (note != null && !note.isBlank()) ? note : "Edicion de registro (v" + this.currentVersion + ")",
            vFields
        ));
    }

    public synchronized boolean restoreVersion(int targetVersion) {
        for (RecordVersion rv : versionHistory) {
            if (rv.version() == targetVersion) {
                this.currentVersion++;
                this.summary = rv.summary();
                this.details = rv.details();
                this.timestamp = LocalDateTime.now().format(FMT);

                this.recordFields.clear();
                for (RecordFieldInfo f : rv.safeFields()) {
                    this.recordFields.add(f.copy());
                }

                List<RecordFieldInfo> vFields = new ArrayList<>();
                for (RecordFieldInfo f : this.recordFields) {
                    vFields.add(f.copy());
                }

                this.versionHistory.add(new RecordVersion(
                    this.currentVersion,
                    this.summary,
                    this.details,
                    this.timestamp,
                    "Restaurado desde version v" + targetVersion,
                    vFields
                ));
                return true;
            }
        }
        return false;
    }

    public synchronized RecordVersion getVersion(int v) {
        for (RecordVersion rv : versionHistory) {
            if (rv.version() == v) return rv;
        }
        return null;
    }

    public String getId() { return id; }
    public String getEngineType() { return engineType; }
    public String getBucketName() { return bucketName; }
    public synchronized String getSummary() { return summary; }
    public synchronized String getDetails() { return details; }
    public synchronized String getTimestamp() { return timestamp; }
    public synchronized int getCurrentVersion() { return currentVersion; }
    public synchronized List<RecordVersion> getVersionHistory() {
        return Collections.unmodifiableList(new ArrayList<>(versionHistory));
    }

    public synchronized List<RecordFieldInfo> getRecordFields() {
        if (recordFields.isEmpty() && "JAVA_RECORD".equals(engineType)) {
            recordFields.addAll(RecordFieldInfo.parseFromDetails(details, summary, id));
        }
        List<RecordFieldInfo> copy = new ArrayList<>();
        for (RecordFieldInfo f : recordFields) {
            copy.add(f.copy());
        }
        return copy;
    }

    public synchronized void setRecordFields(List<RecordFieldInfo> fields) {
        this.recordFields.clear();
        if (fields != null) {
            for (RecordFieldInfo f : fields) {
                this.recordFields.add(f.copy());
            }
        }
    }
}
