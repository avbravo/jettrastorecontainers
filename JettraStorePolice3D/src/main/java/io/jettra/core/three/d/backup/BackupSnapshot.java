package io.jettra.core.three.d.backup;

/**
 * Representa un snapshot o archivo de respaldo inmutable de una base de datos o engine
 * de JettraStore, con métricas de compresión y verificación de integridad SHA-256.
 */
public record BackupSnapshot(
    String id,
    String databaseName,
    String engineType,
    String timestamp,
    long totalObjects,
    long uncompressedBytes,
    long compressedBytes,
    float compressionRatio,
    String checksumSha256,
    String compressionAlgo, // "ZSTD_SIMD", "LZ4_PANAMA", "GZIP", "RAW_ZEROSET"
    String storagePath,
    String status // "COMPLETED", "VERIFIED", "RESTORING"
) {
    public String getFormattedSize() {
        if (compressedBytes > 1024 * 1024) {
            return String.format("%.2f MB", compressedBytes / (1024.0 * 1024.0));
        } else if (compressedBytes > 1024) {
            return String.format("%.2f KB", compressedBytes / 1024.0);
        }
        return compressedBytes + " B";
    }

    public String getFormattedUncompressedSize() {
        if (uncompressedBytes > 1024 * 1024) {
            return String.format("%.2f MB", uncompressedBytes / (1024.0 * 1024.0));
        } else if (uncompressedBytes > 1024) {
            return String.format("%.2f KB", uncompressedBytes / 1024.0);
        }
        return uncompressedBytes + " B";
    }
}
