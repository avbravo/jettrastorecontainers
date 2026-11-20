package io.jettra.driver.admin;

import java.io.Serializable;

/**
 * Opciones de configuración para copias de seguridad (Hot Backup) programáticas en JettraStore.
 */
public record BackupOptions(
    String targetDirectory,
    boolean compress,
    boolean includeSecondaryIndexes
) implements Serializable {

    public BackupOptions {
        if (targetDirectory == null || targetDirectory.isBlank()) {
            targetDirectory = "./backup";
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String targetDirectory = "./backup";
        private boolean compress = false;
        private boolean includeSecondaryIndexes = true;

        public Builder targetDirectory(String targetDirectory) {
            this.targetDirectory = targetDirectory;
            return this;
        }

        public Builder compress(boolean compress) {
            this.compress = compress;
            return this;
        }

        public Builder includeSecondaryIndexes(boolean includeSecondaryIndexes) {
            this.includeSecondaryIndexes = includeSecondaryIndexes;
            return this;
        }

        public BackupOptions build() {
            return new BackupOptions(targetDirectory, compress, includeSecondaryIndexes);
        }
    }
}
