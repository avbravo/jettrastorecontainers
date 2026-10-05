package io.jettra.store.backup;

import io.jettra.store.core.JettraDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;

public final class BackupManager {

    public record BackupMetadata(String databaseName, long timestamp, long sizeBytes, long checksum) {}

    public static BackupMetadata backupDatabase(JettraDatabase database, Path destinationPath) throws IOException {
        // 1. Flush de MemTable a disco
        database.flushMemTable();

        // 2. Copiar archivos físicos .jettra hacia destino
        Path sstable = Path.of(database.getConfig().getStoragePath(), 
                database.getDatabaseName() + "_sstable" + database.getConfig().getFileExtension());

        if (destinationPath.getParent() != null) {
            Files.createDirectories(destinationPath.getParent());
        }

        long size = 0;
        CRC32 crc = new CRC32();
        if (Files.exists(sstable)) {
            byte[] bytes = Files.readAllBytes(sstable);
            size = bytes.length;
            crc.update(bytes);
            Files.copy(sstable, destinationPath, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.writeString(destinationPath, "JETTRA_EMPTY_SNAPSHOT");
        }

        return new BackupMetadata(database.getDatabaseName(), System.currentTimeMillis(), size, crc.getValue());
    }

    public static boolean restoreDatabase(Path sourceSnapshot, JettraDatabase targetDatabase) throws IOException {
        if (!Files.exists(sourceSnapshot)) {
            throw new IllegalArgumentException("Snapshot file does not exist: " + sourceSnapshot);
        }

        Path targetFile = Path.of(targetDatabase.getConfig().getStoragePath(), 
                targetDatabase.getDatabaseName() + "_sstable" + targetDatabase.getConfig().getFileExtension());

        if (targetFile.getParent() != null) {
            Files.createDirectories(targetFile.getParent());
        }

        Files.copy(sourceSnapshot, targetFile, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }
}
