package io.jettra.store.explorer.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class EngineBucketInfo implements Serializable {
    private String databaseName;
    private String bucketName;
    private String engineType; // DOCUMENT, RELATIONAL, KEY_VALUE, GRAPH, VECTOR, TIMESERIES, GEOSPATIAL, COLUMNAR
    private long totalObjects;
    private long totalBytes;
    private String description;
    private String structureType = "NONE"; // NONE o DEFINED
    private List<io.jettra.store.core.JettraBucketSchema.AttributeRule> attributes = new ArrayList<>();
    private List<EngineRecordInfo> sampleRecords = new ArrayList<>();
    private List<EngineIndexInfo> indexes = new ArrayList<>();
    private final Map<String, EngineRecordInfo> recordOverrides = new ConcurrentHashMap<>();
    private final Set<String> deletedRecordIds = ConcurrentHashMap.newKeySet();

    public EngineBucketInfo() {}

    public EngineBucketInfo(String databaseName, String bucketName, String engineType, long totalObjects, long totalBytes, String description) {
        this.databaseName = databaseName;
        this.bucketName = bucketName;
        this.engineType = engineType;
        this.totalObjects = totalObjects;
        this.totalBytes = totalBytes;
        this.description = description;
    }

    public String getDatabaseName() { return databaseName; }
    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) {
        if (bucketName != null && !bucketName.isBlank()) {
            this.bucketName = bucketName.trim();
        }
    }
    public String getEngineType() { return engineType; }
    public long getTotalObjects() { return totalObjects; }
    public void setTotalObjects(long o) { this.totalObjects = o; }
    public long getTotalBytes() { return totalBytes; }
    public String getDescription() { return description; }
    public void setDescription(String description) {
        this.description = description != null ? description.trim() : "";
    }
    public String getStructureType() { return structureType != null ? structureType : "NONE"; }
    public void setStructureType(String structureType) {
        this.structureType = (structureType != null && "DEFINED".equalsIgnoreCase(structureType.trim())) ? "DEFINED" : "NONE";
    }
    public List<io.jettra.store.core.JettraBucketSchema.AttributeRule> getAttributes() { return attributes; }
    public void setAttributes(List<io.jettra.store.core.JettraBucketSchema.AttributeRule> attributes) {
        this.attributes = attributes != null ? new ArrayList<>(attributes) : new ArrayList<>();
    }
    public List<EngineRecordInfo> getSampleRecords() { return sampleRecords; }
    public List<EngineIndexInfo> getIndexes() { return indexes; }
    public Map<String, EngineRecordInfo> getRecordOverrides() { return recordOverrides; }
    public Set<String> getDeletedRecordIds() { return deletedRecordIds; }

    public String getFormattedSize() {
        if (totalBytes < 1024) return totalBytes + " B";
        if (totalBytes < 1024 * 1024) return String.format("%.1f KB", totalBytes / 1024.0);
        if (totalBytes < 1024 * 1024 * 1024) return String.format("%.1f MB", totalBytes / (1024.0 * 1024));
        return String.format("%.2f GB", totalBytes / (1024.0 * 1024 * 1024));
    }
}
