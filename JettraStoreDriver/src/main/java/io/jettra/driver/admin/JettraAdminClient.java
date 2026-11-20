package io.jettra.driver.admin;

import io.jettra.driver.JettraClient;
import io.jettra.store.backup.BackupManager;
import io.jettra.store.core.JettraDatabase;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Cliente de administración para operaciones de Backup en caliente, Restauración
 * y gestión de topología de clúster en JettraStore.
 */
public final class JettraAdminClient {
    private final String sessionToken;
    private final JettraClient client;
    private final HttpClient httpClient;

    public record AdminResult(boolean success, String message, long durationMs) {}

    public JettraAdminClient(String sessionToken) {
        this(sessionToken, null);
    }

    public JettraAdminClient(String sessionToken, JettraClient client) {
        this.sessionToken = sessionToken;
        this.client = client;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    /**
     * Ejecuta una copia de seguridad programática de la base de datos a la ruta especificada.
     */
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

    /**
     * Ejecuta una copia de seguridad programática con opciones configurables (directorio, compresión, índices).
     */
    public BackupResult backupDatabase(String databaseName, BackupOptions options) {
        long start = System.currentTimeMillis();
        if (databaseName == null || databaseName.isBlank()) {
            return new BackupResult(false, "", 0L, 0L, "El nombre de la base de datos no puede estar vacío");
        }
        BackupOptions opts = (options != null) ? options : BackupOptions.builder().build();

        try {
            Path targetDir = Path.of(opts.targetDirectory());
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }

            String filename = databaseName + "_" + System.currentTimeMillis() + (opts.compress() ? ".jettra_bak.gz" : ".jettra_bak");
            Path targetPath = targetDir.resolve(filename);

            JettraDatabase db = resolveDatabase(databaseName);
            if (db == null) {
                return new BackupResult(false, "", System.currentTimeMillis() - start, 0L, "Base de datos '" + databaseName + "' no encontrada.");
            }

            var meta = BackupManager.backupDatabase(db, targetPath);
            long dur = System.currentTimeMillis() - start;
            return new BackupResult(true, targetPath.toAbsolutePath().toString(), dur, meta.sizeBytes(), "Backup completado exitosamente");
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            return new BackupResult(false, "", dur, 0L, "Error en backup: " + e.getMessage());
        }
    }

    /**
     * Ejecuta una copia de seguridad programática de la base de datos hacia una ruta fija.
     */
    public BackupResult backupDatabase(String databaseName, Path targetPath) {
        long start = System.currentTimeMillis();
        try {
            JettraDatabase db = resolveDatabase(databaseName);
            if (db == null) {
                return new BackupResult(false, "", System.currentTimeMillis() - start, 0L, "Base de datos '" + databaseName + "' no encontrada.");
            }
            if (targetPath.getParent() != null && !Files.exists(targetPath.getParent())) {
                Files.createDirectories(targetPath.getParent());
            }
            var meta = BackupManager.backupDatabase(db, targetPath);
            long dur = System.currentTimeMillis() - start;
            return new BackupResult(true, targetPath.toAbsolutePath().toString(), dur, meta.sizeBytes(), "Backup completado exitosamente");
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            return new BackupResult(false, targetPath.toAbsolutePath().toString(), dur, 0L, "Error en backup: " + e.getMessage());
        }
    }

    /**
     * Restaura una base de datos objetivo a partir de un archivo snapshot de respaldo.
     */
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

    /**
     * Restaura una base de datos especificada a partir de la ruta del snapshot.
     */
    public RestoreResult restoreDatabase(String databaseName, Path snapshotPath) {
        long start = System.currentTimeMillis();
        try {
            JettraDatabase db = resolveDatabase(databaseName);
            if (db == null) {
                return new RestoreResult(false, System.currentTimeMillis() - start, "Base de datos '" + databaseName + "' no encontrada.");
            }
            boolean res = BackupManager.restoreDatabase(snapshotPath, db);
            long dur = System.currentTimeMillis() - start;
            return new RestoreResult(res, dur, res ? "Restauración exitosa desde " + snapshotPath : "Fallo en la restauración");
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            return new RestoreResult(false, dur, "Error en restauración: " + e.getMessage());
        }
    }

    /**
     * Restaura una base de datos especificada a partir de la ruta del snapshot en formato String.
     */
    public RestoreResult restoreDatabase(String databaseName, String snapshotPath) {
        if (snapshotPath == null || snapshotPath.isBlank()) {
            return new RestoreResult(false, 0L, "Ruta de snapshot inválida");
        }
        return restoreDatabase(databaseName, Path.of(snapshotPath));
    }

    /**
     * Consulta el estado de salud de un nodo del clúster a través del endpoint REST /api/v1/health.
     */
    public String getClusterHealth(String host, int port) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + "/api/v1/health"))
                .header("Authorization", "Bearer " + sessionToken)
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            return "{\"status\":\"UNREACHABLE\",\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Consulta el estado detallado de la topología del clúster a través del endpoint REST /api/v1/cluster/status.
     */
    public String getClusterStatus(String host, int port) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + "/api/v1/cluster/status"))
                .header("Authorization", "Bearer " + sessionToken)
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            return "{\"status\":\"UNREACHABLE\",\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Solicita el apagado controlado de un nodo a través del endpoint REST /api/v1/cluster/shutdown.
     */
    public boolean shutdownClusterNode(String host, int port) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + "/api/v1/cluster/shutdown"))
                .header("Authorization", "Bearer " + sessionToken)
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    private JettraDatabase resolveDatabase(String databaseName) {
        if (client != null) {
            return client.getDatabase(databaseName);
        }
        return new JettraDatabase(databaseName, io.jettra.store.core.JettraStoreConfig.load());
    }

    public String getSessionToken() {
        return sessionToken;
    }
}
