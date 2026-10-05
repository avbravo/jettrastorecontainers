package io.jettra.store.explorer.model;

import java.io.Serializable;

public class EngineRecordInfo implements Serializable {
    private String id;
    private String engineType;
    private String bucketName;
    private String summary;
    private String details;
    private String timestamp;
    private long version;

    public EngineRecordInfo() {}

    public EngineRecordInfo(String id, String engineType, String bucketName, String summary, String details, String timestamp, long version) {
        this.id = id;
        this.engineType = engineType;
        this.bucketName = bucketName;
        this.summary = summary;
        this.details = details;
        this.timestamp = timestamp;
        this.version = version;
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
}
