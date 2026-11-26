package io.jettra.store.core;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Gestor de respaldo y restauración segura de {@code cluster-state.config} durante pruebas
 * automatizadas de {@code jettrastorecontainers} (RF-34).
 *
 * <p>Antes de que un test cree, modifique o sobrescriba {@code cluster-state.config} dentro de
 * {@code jettra.cluster_state_config_dir}, respalda el archivo existente como
 * {@code cluster-state.config-backup}. Al finalizar el test (incluso ante fallos), elimina el
 * {@code cluster-state.config} de prueba y restaura {@code cluster-state.config-backup} como
 * {@code cluster-state.config}.</p>
 */
public final class JettraClusterStateBackupManager {

    public static final String CLUSTER_STATE_BACKUP_FILENAME = "cluster-state.config-backup";

    private static final ConcurrentHashMap<Path, ReentrantLock> DIR_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Path, ActiveProtectionState> ACTIVE_STATES = new ConcurrentHashMap<>();

    private static final class ActiveProtectionState {
        final AtomicInteger depth = new AtomicInteger(0);
        boolean hadOriginalFile;
    }

    private JettraClusterStateBackupManager() {
    }

    private static ReentrantLock lockForDir(Path dir) {
        return DIR_LOCKS.computeIfAbsent(dir.toAbsolutePath().normalize(), k -> new ReentrantLock());
    }

    /**
     * Resuelve los directorios configurados de {@code jettra.cluster_state_config_dir}
     * leyendo {@code microprofile-config.properties}, propiedades del sistema y variables de entorno.
     */
    public static List<Path> resolveConfiguredClusterStateDirs() {
        Set<Path> dirs = new LinkedHashSet<>();
        try {
            Path mpPath = JettraConfigValidator.locateMicroProfileConfigFile();
            Properties mpProps = (mpPath != null && Files.exists(mpPath))
                    ? JettraConfigValidator.loadProperties(
                            mpPath,
                            "/META-INF/microprofile-config.properties",
                            "/microprofile-config.properties")
                    : JettraConfigValidator.loadProperties(
                            null,
                            "/META-INF/microprofile-config.properties",
                            "/microprofile-config.properties");

            Path resolved = JettraConfigValidator.resolveClusterStateConfigDirPath(mpProps);
            if (resolved != null) {
                dirs.add(resolved.toAbsolutePath().normalize());
            }
            if (mpProps != null) {
                String rawMpDir = mpProps.getProperty("jettra.cluster_state_config_dir",
                        mpProps.getProperty("cluster.state.config.dir"));
                if (rawMpDir != null && !rawMpDir.isBlank()) {
                    String expanded = JettraConfigValidator.expandPathPlaceholders(rawMpDir);
                    if (!expanded.isBlank()) {
                        dirs.add(Path.of(expanded).toAbsolutePath().normalize());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (dirs.isEmpty()) {
            String fallback = JettraConfigValidator.expandPathPlaceholders(
                    JettraConfigValidator.DEFAULT_CLUSTER_STATE_CONFIG_DIR);
            if (!fallback.isBlank()) {
                dirs.add(Path.of(fallback).toAbsolutePath().normalize());
            }
        }
        return new ArrayList<>(dirs);
    }

    /**
     * Protege todas las rutas de {@code jettra.cluster_state_config_dir} configuradas
     * creando {@code cluster-state.config-backup} si {@code cluster-state.config} ya existe.
     */
    public static BackupHandle protectConfigured() {
        return protectAll(resolveConfiguredClusterStateDirs());
    }

    /**
     * Protege un directorio de estado específico (o archivo {@code cluster-state.config}).
     */
    public static BackupHandle protect(Path stateDirOrFile) {
        if (stateDirOrFile == null) {
            return BackupHandle.EMPTY;
        }
        Path dir = isClusterStateFilePath(stateDirOrFile)
                ? stateDirOrFile.toAbsolutePath().normalize().getParent()
                : stateDirOrFile.toAbsolutePath().normalize();
        if (dir == null) {
            return BackupHandle.EMPTY;
        }
        return protectAll(List.of(dir));
    }

    public static BackupHandle protectAll(List<Path> stateDirs) {
        if (stateDirs == null || stateDirs.isEmpty()) {
            return BackupHandle.EMPTY;
        }
        List<Path> normalized = new ArrayList<>();
        for (Path d : stateDirs) {
            if (d == null) {
                continue;
            }
            Path norm = d.toAbsolutePath().normalize();
            if (!normalized.contains(norm)) {
                normalized.add(norm);
                beginProtection(norm);
            }
        }
        if (normalized.isEmpty()) {
            return BackupHandle.EMPTY;
        }
        return new BackupHandle(normalized);
    }

    private static boolean isClusterStateFilePath(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && JettraConfigValidator.CLUSTER_STATE_FILENAME.equals(fileName.toString());
    }

    private static void beginProtection(Path stateDir) {
        ReentrantLock lock = lockForDir(stateDir);
        lock.lock();
        try {
            ActiveProtectionState state = ACTIVE_STATES.computeIfAbsent(stateDir, k -> new ActiveProtectionState());
            int newDepth = state.depth.incrementAndGet();
            if (newDepth == 1) {
                Path stateFile = stateDir.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
                Path backupFile = stateDir.resolve(CLUSTER_STATE_BACKUP_FILENAME);

                if (Files.exists(backupFile) && Files.size(backupFile) > 0) {
                    // Preservar respaldo previo existente en caso de interrupción previa sin restaurar
                    state.hadOriginalFile = true;
                } else if (Files.exists(stateFile)) {
                    Files.createDirectories(stateDir);
                    Path tempCopy = Files.createTempFile(stateDir, ".cluster-state-backup-", ".tmp");
                    try {
                        Files.copy(stateFile, tempCopy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                        moveAtomicallyOrReplace(tempCopy, backupFile);
                    } finally {
                        Files.deleteIfExists(tempCopy);
                    }
                    state.hadOriginalFile = true;
                } else {
                    state.hadOriginalFile = false;
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "No se pudo respaldar cluster-state.config en '" + stateDir + "': " + ex.getMessage(), ex);
        } finally {
            lock.unlock();
        }
    }

    private static void endProtection(Path stateDir) {
        ReentrantLock lock = lockForDir(stateDir);
        lock.lock();
        try {
            ActiveProtectionState state = ACTIVE_STATES.get(stateDir);
            if (state == null) {
                restoreDanglingBackupIfPresent(stateDir);
                return;
            }
            int remaining = state.depth.decrementAndGet();
            if (remaining <= 0) {
                ACTIVE_STATES.remove(stateDir);
                Path stateFile = stateDir.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
                Path backupFile = stateDir.resolve(CLUSTER_STATE_BACKUP_FILENAME);

                Files.deleteIfExists(stateFile);
                if (Files.exists(backupFile)) {
                    moveAtomicallyOrReplace(backupFile, stateFile);
                }
            }
        } catch (IOException ex) {
            System.err.printf("[JettraClusterStateBackup] Advertencia restaurando '%s': %s%n",
                    stateDir, ex.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Si quedó un {@code cluster-state.config-backup} huérfano en {@code stateDir},
     * elimina el {@code cluster-state.config} de prueba y restaura {@code cluster-state.config-backup}.
     */
    public static void restoreDanglingBackupIfPresent(Path stateDir) {
        if (stateDir == null) {
            return;
        }
        Path norm = stateDir.toAbsolutePath().normalize();
        ReentrantLock lock = lockForDir(norm);
        lock.lock();
        try {
            Path stateFile = norm.resolve(JettraConfigValidator.CLUSTER_STATE_FILENAME);
            Path backupFile = norm.resolve(CLUSTER_STATE_BACKUP_FILENAME);
            if (Files.exists(backupFile)) {
                Files.deleteIfExists(stateFile);
                moveAtomicallyOrReplace(backupFile, stateFile);
            }
        } catch (IOException ignored) {
        } finally {
            lock.unlock();
        }
    }

    private static void moveAtomicallyOrReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static final class BackupHandle implements AutoCloseable {
        public static final BackupHandle EMPTY = new BackupHandle(List.of());

        private final List<Path> stateDirs;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Thread shutdownHook;

        private BackupHandle(List<Path> stateDirs) {
            this.stateDirs = List.copyOf(stateDirs);
            if (!this.stateDirs.isEmpty()) {
                this.shutdownHook = new Thread(this::restoreInternal, "jettra-state-backup-restore");
                try {
                    Runtime.getRuntime().addShutdownHook(this.shutdownHook);
                } catch (IllegalStateException ignored) {
                }
            } else {
                this.shutdownHook = null;
            }
        }

        public void restore() {
            close();
        }

        @Override
        public void close() {
            restoreInternal();
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                }
            }
        }

        private void restoreInternal() {
            if (closed.compareAndSet(false, true)) {
                for (int i = stateDirs.size() - 1; i >= 0; i--) {
                    endProtection(stateDirs.get(i));
                }
            }
        }
    }
}
