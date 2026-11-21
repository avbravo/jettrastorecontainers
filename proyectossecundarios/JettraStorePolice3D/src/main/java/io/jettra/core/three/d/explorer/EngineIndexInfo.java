package io.jettra.core.three.d.explorer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Representa la metadata y estado de un índice de acceso rápido (BTREE, HASH, HNSW, RTREE, etc.)
 * para un bucket de datos en JettraStore.
 */
public class EngineIndexInfo {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String name;
    private String engineType;
    private String bucketName;
    private String field;
    private String type; // PRIMARY_HASH, BTREE, HNSW_VECTOR, RTREE_SPATIAL, BITMAP, FULLTEXT
    private boolean unique;
    private long entriesCount;
    private String status; // ACTIVO, RECONSTRUYENDO
    private String createdAt;

    public EngineIndexInfo(String name, String engineType, String bucketName, String field, String type, boolean unique, long entriesCount) {
        this.name = name;
        this.engineType = engineType;
        this.bucketName = bucketName;
        this.field = field;
        this.type = type;
        this.unique = unique;
        this.entriesCount = entriesCount;
        this.status = "ACTIVO";
        this.createdAt = LocalDateTime.now().format(FMT);
    }

    public EngineIndexInfo(String name, String engineType, String bucketName, String field, String type, boolean unique, long entriesCount, String status, String createdAt) {
        this.name = name;
        this.engineType = engineType;
        this.bucketName = bucketName;
        this.field = field;
        this.type = type;
        this.unique = unique;
        this.entriesCount = entriesCount;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEngineType() { return engineType; }
    public void setEngineType(String engineType) { this.engineType = engineType; }

    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) { this.bucketName = bucketName; }

    public String getField() { return field; }
    public void setField(String field) { this.field = field; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isUnique() { return unique; }
    public void setUnique(boolean unique) { this.unique = unique; }

    public long getEntriesCount() { return entriesCount; }
    public void setEntriesCount(long entriesCount) { this.entriesCount = entriesCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
