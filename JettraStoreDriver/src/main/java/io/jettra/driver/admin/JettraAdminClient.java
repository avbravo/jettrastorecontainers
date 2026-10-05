package io.jettra.driver.admin;

import io.jettra.store.backup.BackupManager;
import io.jettra.store.core.JettraDatabase;
import java.io.IOException;
import java.nio.file.Path;

public final class JettraAdminClient {
    private final String sessionToken;

    public record AdminResult(boolean success, String message, long durationMs) {}

    public JettraAdminClient(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public AdminResult backupDatabase(JettraDatabase database, Path targetPath) {
        long start = System.currentTimeMillis();
        try {
            var meta = BackupManager.backupDatabase(database, targetPath);
            long dur = System.currentTimeMillis() - start;
            return new AdminResult(true, "Backup completed to: " + targetPath + " (" + meta.sizeBytes() + " bytes)", dur);
        } catch (IOException e) {
            return new AdminResult(false, "Backup failed: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    public AdminResult restoreDatabase(Path sourceSnapshot, JettraDatabase targetDatabase) {
        long start = System.currentTimeMillis();
        try {
            boolean res = BackupManager.restoreDatabase(sourceSnapshot, targetDatabase);
            long dur = System.currentTimeMillis() - start;
            return new AdminResult(res, "Restore completed from: " + sourceSnapshot, dur);
        } catch (IOException e) {
            return new AdminResult(false, "Restore failed: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    public String getSessionToken() { return sessionToken; }
}
