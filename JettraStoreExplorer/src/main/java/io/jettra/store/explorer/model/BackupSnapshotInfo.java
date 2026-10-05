package io.jettra.store.explorer.model;

import java.io.Serializable;

public class BackupSnapshotInfo implements Serializable {
    private String id;
    private String databaseName;
    private String engineType;
    private String timestamp;
    private long totalObjects;
    private long uncompressedBytes;
    private long compressedBytes;
    private float compressionRatio;
    private String checksumSha256;
    private String compressionAlgo;
    private String filePath;
    private String status;

    public BackupSnapshotInfo() {}

    public BackupSnapshotInfo(String id, String databaseName, String engineType, String timestamp,
                              long totalObjects, long uncompressedBytes, long compressedBytes,
                              float compressionRatio, String checksumSha256, String compressionAlgo,
                              String filePath, String status) {
        this.id = id;
        this.databaseName = databaseName;
        this.engineType = engineType;
        this.timestamp = timestamp;
        this.totalObjects = totalObjects;
        this.uncompressedBytes = uncompressedBytes;
        this.compressedBytes = compressedBytes;
        this.compressionRatio = compressionRatio;
        this.checksumSha256 = checksumSha256;
        this.compressionAlgo = compressionAlgo;
        this.filePath = filePath;
        this.status = status;
    }

    public String getId() { return id; }
    public String getDatabaseName() { return databaseName; }
    public String getEngineType() { return engineType; }
    public String getTimestamp() { return timestamp; }
    public long getTotalObjects() { return totalObjects; }
    public long getUncompressedBytes() { return uncompressedBytes; }
    public long getCompressedBytes() { return compressedBytes; }
    public float getCompressionRatio() { return compressionRatio; }
    public String getChecksumSha256() { return checksumSha256; }
    public String getCompressionAlgo() { return compressionAlgo; }
    public String getFilePath() { return filePath; }
    public String getStatus() { return status; }

    public String getFormattedSize() {
        if (compressedBytes < 1024 * 1024) return String.format("%.1f KB", compressedBytes / 1024.0);
        if (compressedBytes < 1024 * 1024 * 1024) return String.format("%.1f MB", compressedBytes / (1024.0 * 1024));
        return String.format("%.2f GB", compressedBytes / (1024.0 * 1024 * 1024));
    }
}
