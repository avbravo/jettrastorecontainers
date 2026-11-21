package io.jettra.core.three.d.explorer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Representa un contenedor o bucket dentro de un engine multimodelo de JettraStore.
 * Gestiona registros paginados, auditoria de versiones e indices de acceso rapido.
 */
public class EngineBucket {
    private final String engineType;
    private final String engineName;
    private final String bucketName;
    private final String description;
    private long totalObjects;
    private final List<EngineRecord> sampleRecords = new ArrayList<>();
    private final List<EngineIndexInfo> indexes = new ArrayList<>();

    public EngineBucket(String engineType, String engineName, String bucketName, String description, long totalObjects) {
        this.engineType = engineType;
        this.engineName = engineName;
        this.bucketName = bucketName;
        this.description = description;
        this.totalObjects = totalObjects;
    }

    public synchronized void addRecord(EngineRecord record) {
        sampleRecords.add(record);
        totalObjects++;
    }

    public synchronized boolean updateRecord(String id, String summary, String details, String note) {
        return updateRecord(id, summary, details, note, null);
    }

    public synchronized boolean updateRecord(String id, String summary, String details, String note, List<RecordFieldInfo> fields) {
        EngineRecord rec = findRecordById(id);
        if (rec != null) {
            rec.update(summary, details, note, fields);
            return true;
        }
        return false;
    }

    public synchronized boolean deleteRecord(String id) {
        boolean removed = sampleRecords.removeIf(r -> r.getId().equalsIgnoreCase(id));
        if (removed && totalObjects > 0) {
            totalObjects--;
        }
        return removed;
    }

    public synchronized boolean restoreRecordVersion(String id, int versionNumber) {
        EngineRecord rec = findRecordById(id);
        if (rec != null) {
            return rec.restoreVersion(versionNumber);
        }
        return false;
    }

    public synchronized EngineRecord findRecordById(String id) {
        for (EngineRecord r : sampleRecords) {
            if (r.getId().equalsIgnoreCase(id)) return r;
        }
        return null;
    }

    // --- ADMINISTRACION DE INDICES ---
    public synchronized List<EngineIndexInfo> getIndexes() {
        return Collections.unmodifiableList(new ArrayList<>(indexes));
    }

    public synchronized void addIndex(EngineIndexInfo index) {
        indexes.removeIf(idx -> idx.getName().equalsIgnoreCase(index.getName()));
        indexes.add(index);
    }

    public synchronized boolean updateIndex(String indexName, String field, String type, boolean unique) {
        EngineIndexInfo idx = findExistingIndex(indexName);
        if (idx != null) {
            idx.setField(field);
            idx.setType(type);
            idx.setUnique(unique);
            idx.setStatus("ACTIVO (RECONSTRUIDO)");
            return true;
        }
        return false;
    }

    public synchronized boolean deleteIndex(String indexName) {
        return indexes.removeIf(idx -> idx.getName().equalsIgnoreCase(indexName));
    }

    public synchronized EngineIndexInfo findExistingIndex(String indexName) {
        for (EngineIndexInfo idx : indexes) {
            if (idx.getName().equalsIgnoreCase(indexName)) return idx;
        }
        return null;
    }

    public String getEngineType() { return engineType; }
    public String getEngineName() { return engineName; }
    public String getBucketName() { return bucketName; }
    public String getDescription() { return description; }
    public synchronized long getTotalObjects() { return totalObjects; }
    public synchronized void setTotalObjects(long totalObjects) { this.totalObjects = totalObjects; }
    public synchronized List<EngineRecord> getSampleRecords() { return Collections.unmodifiableList(new ArrayList<>(sampleRecords)); }
}
