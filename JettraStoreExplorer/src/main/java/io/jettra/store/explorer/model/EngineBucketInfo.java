package io.jettra.store.explorer.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class EngineBucketInfo implements Serializable {
    private String databaseName;
    private String bucketName;
    private String engineType; // DOCUMENT, RELATIONAL, KEY_VALUE, GRAPH, VECTOR, TIMESERIES, GEOSPATIAL, COLUMNAR
    private long totalObjects;
    private long totalBytes;
    private String description;
    private List<EngineRecordInfo> sampleRecords = new ArrayList<>();
    private List<EngineIndexInfo> indexes = new ArrayList<>();

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
    public String getEngineType() { return engineType; }
    public long getTotalObjects() { return totalObjects; }
    public void setTotalObjects(long o) { this.totalObjects = o; }
    public long getTotalBytes() { return totalBytes; }
    public String getDescription() { return description; }
    public List<EngineRecordInfo> getSampleRecords() { return sampleRecords; }
    public List<EngineIndexInfo> getIndexes() { return indexes; }

    public String getFormattedSize() {
        if (totalBytes < 1024) return totalBytes + " B";
        if (totalBytes < 1024 * 1024) return String.format("%.1f KB", totalBytes / 1024.0);
        if (totalBytes < 1024 * 1024 * 1024) return String.format("%.1f MB", totalBytes / (1024.0 * 1024));
        return String.format("%.2f GB", totalBytes / (1024.0 * 1024 * 1024));
    }
}
