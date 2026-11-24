package io.jettra.driver.version;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonArray;
import io.jettra.json.JsonObject;
import io.jettra.store.core.JettraStoreConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestor de historial de versiones por base de datos, bucket y registro para JettraStoreDriver (RF-18).
 * <p>
 * Mantiene instantáneas inmutables ordenadas secuencialmente ({@code v1, v2, ...}) en memoria
 * con persistencia atómica en {@code <storagePath>/<databaseName>_versions.json}, garantizando
 * trazabilidad completa sin destruir versiones previas y limpieza total al eliminar bases de datos (RF-16).
 */
public class JettraVersionManager {

    // databaseName -> ( "bucket::recordId" -> List<RecordVersion> )
    private final Map<String, Map<String, List<RecordVersion>>> store = new ConcurrentHashMap<>();
    private final Set<String> loadedDatabases = ConcurrentHashMap.newKeySet();
    private final JettraJson json = new JettraJson();

    private static String compositeKey(String bucketName, String recordId) {
        return bucketName.trim() + "::" + recordId.trim();
    }

    private static String normalizeDb(String databaseName) {
        if (databaseName == null || databaseName.isBlank()) {
            throw new IllegalArgumentException("El nombre de la base de datos es obligatorio.");
        }
        return databaseName.trim();
    }

    private static String normalizeBucket(String bucketName) {
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("El nombre del bucket es obligatorio.");
        }
        return bucketName.trim();
    }

    private static String normalizeRecordId(String recordId) {
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("El identificador del registro (_id) es obligatorio.");
        }
        return recordId.trim();
    }

    /**
     * Registra una nueva versión inmutable para el registro indicado y persiste el historial en disco.
     */
    public synchronized RecordVersion recordVersion(
            String databaseName,
            String bucketName,
            String recordId,
            VersionOperation operation,
            Map<String, Object> snapshot,
            String author,
            String note,
            Long restoredFromVersion) {

        String db = normalizeDb(databaseName);
        String bucket = normalizeBucket(bucketName);
        String id = normalizeRecordId(recordId);
        VersionOperation op = operation != null ? operation : VersionOperation.UPDATE;

        ensureLoaded(db);
        Map<String, List<RecordVersion>> dbMap = store.computeIfAbsent(db, k -> new ConcurrentHashMap<>());
        String key = compositeKey(bucket, id);
        List<RecordVersion> versions = dbMap.computeIfAbsent(key, k -> new ArrayList<>());

        long nextVersion = versions.isEmpty() ? 1L : versions.get(versions.size() - 1).versionNumber() + 1L;
        String versionId = id + "@v" + nextVersion;
        String effectiveAuthor = (author != null && !author.isBlank()) ? author.trim() : "system";
        String effectiveNote = (note != null && !note.isBlank()) ? note.trim() : defaultNote(op, restoredFromVersion);
        Map<String, Object> safeSnapshot = deepCopyMap(snapshot);

        RecordVersion entry = new RecordVersion(
                nextVersion,
                versionId,
                db,
                bucket,
                id,
                op,
                Instant.now().toString(),
                effectiveAuthor,
                effectiveNote,
                restoredFromVersion,
                safeSnapshot
        );
        versions.add(entry);
        persistDatabaseVersions(db, dbMap);
        return entry;
    }

    /**
     * Retorna el historial completo de versiones de un registro en orden ascendente (v1, v2, ...).
     */
    public synchronized List<RecordVersion> getHistory(String databaseName, String bucketName, String recordId) {
        if (databaseName == null || databaseName.isBlank()
                || bucketName == null || bucketName.isBlank()
                || recordId == null || recordId.isBlank()) {
            return Collections.emptyList();
        }
        String db = databaseName.trim();
        ensureLoaded(db);
        Map<String, List<RecordVersion>> dbMap = store.get(db);
        if (dbMap == null) return Collections.emptyList();
        List<RecordVersion> list = dbMap.get(compositeKey(bucketName, recordId));
        if (list == null || list.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    /**
     * Obtiene una versión puntual de un registro por su número de versión.
     */
    public synchronized Optional<RecordVersion> getVersion(
            String databaseName, String bucketName, String recordId, long versionNumber) {
        if (versionNumber <= 0) return Optional.empty();
        for (RecordVersion v : getHistory(databaseName, bucketName, recordId)) {
            if (v.versionNumber() == versionNumber) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    /**
     * Obtiene la versión más reciente de un registro.
     */
    public synchronized Optional<RecordVersion> getLatestVersion(String databaseName, String bucketName, String recordId) {
        List<RecordVersion> history = getHistory(databaseName, bucketName, recordId);
        if (history.isEmpty()) return Optional.empty();
        return Optional.of(history.get(history.size() - 1));
    }

    /**
     * Elimina en memoria y en disco el historial de versiones de un bucket completo.
     */
    public synchronized void dropBucketHistory(String databaseName, String bucketName) {
        if (databaseName == null || databaseName.isBlank() || bucketName == null || bucketName.isBlank()) return;
        String db = databaseName.trim();
        ensureLoaded(db);
        Map<String, List<RecordVersion>> dbMap = store.get(db);
        if (dbMap == null) return;
        String prefix = bucketName.trim() + "::";
        boolean removed = dbMap.keySet().removeIf(k -> k.startsWith(prefix));
        if (removed) {
            persistDatabaseVersions(db, dbMap);
        }
    }

    /**
     * Elimina completamente el historial de versiones de una base de datos en memoria y disco (RF-16).
     */
    public synchronized void dropDatabaseHistory(String databaseName) {
        if (databaseName == null || databaseName.isBlank()) return;
        String db = databaseName.trim();
        store.remove(db);
        loadedDatabases.remove(db);
        try {
            Path file = resolveVersionsFile(db);
            Files.deleteIfExists(file);
            Files.deleteIfExists(Path.of(file.toString() + ".tmp"));
        } catch (Exception ignored) {}
    }

    private static String defaultNote(VersionOperation op, Long restoredFrom) {
        return switch (op) {
            case CREATE -> "Creación inicial del registro";
            case UPDATE -> "Actualización de registro";
            case DELETE -> "Eliminación de registro";
            case RESTORE -> restoredFrom != null
                    ? "Restauración desde versión v" + restoredFrom
                    : "Restauración de registro";
        };
    }

    private Path resolveVersionsFile(String databaseName) {
        JettraStoreConfig cfg = JettraStoreConfig.load();
        String base = (cfg != null && cfg.getStoragePath() != null && !cfg.getStoragePath().isBlank())
                ? cfg.getStoragePath()
                : ".";
        return Path.of(base, databaseName + "_versions.json");
    }

    private void ensureLoaded(String db) {
        if (loadedDatabases.contains(db)) return;
        loadedDatabases.add(db);
        Path file = resolveVersionsFile(db);
        if (!Files.exists(file)) return;
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) return;
            Object parsed = json.parse(raw);
            if (!(parsed instanceof JsonObject root)) return;

            Map<String, List<RecordVersion>> dbMap = store.computeIfAbsent(db, k -> new ConcurrentHashMap<>());
            for (Map.Entry<String, Object> entry : root.getMap().entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                List<Object> arr = null;
                if (val instanceof JsonArray ja) {
                    arr = ja.getList();
                } else if (val instanceof List<?> l) {
                    arr = new ArrayList<>(l);
                }
                if (arr == null) continue;
                List<RecordVersion> versions = new ArrayList<>();
                for (Object item : arr) {
                    Map<String, Object> m = toPlainMap(item);
                    if (m == null) continue;
                    long vNum = toLong(m.get("versionNumber"), versions.size() + 1L);
                    String vId = strOrDefault(m.get("versionId"), key + "@v" + vNum);
                    String dbName = strOrDefault(m.get("databaseName"), db);
                    String bName = strOrDefault(m.get("bucketName"), "");
                    String rId = strOrDefault(m.get("recordId"), "");
                    VersionOperation op = parseOp(strOrDefault(m.get("operation"), "UPDATE"));
                    String ts = strOrDefault(m.get("timestamp"), Instant.now().toString());
                    String author = strOrDefault(m.get("author"), "system");
                    String note = strOrDefault(m.get("note"), "");
                    Long restoredFrom = m.get("restoredFromVersion") != null ? toLong(m.get("restoredFromVersion"), -1L) : null;
                    if (restoredFrom != null && restoredFrom <= 0) restoredFrom = null;
                    Map<String, Object> snap = toPlainMap(m.get("snapshot"));
                    versions.add(new RecordVersion(vNum, vId, dbName, bName, rId, op, ts, author, note, restoredFrom, snap));
                }
                if (!versions.isEmpty()) {
                    versions.sort(Comparator.comparingLong(RecordVersion::versionNumber));
                    dbMap.put(key, versions);
                }
            }
        } catch (Exception ignored) {
            // Si el archivo está corrupto o vacío, continuar con caché limpio sin bloquear
        }
    }

    private void persistDatabaseVersions(String db, Map<String, List<RecordVersion>> dbMap) {
        try {
            Path file = resolveVersionsFile(db);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Map<String, Object> serializedRoot = new LinkedHashMap<>();
            for (Map.Entry<String, List<RecordVersion>> entry : dbMap.entrySet()) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (RecordVersion v : entry.getValue()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("versionNumber", v.versionNumber());
                    item.put("versionId", v.versionId());
                    item.put("databaseName", v.databaseName());
                    item.put("bucketName", v.bucketName());
                    item.put("recordId", v.recordId());
                    item.put("operation", v.operation().name());
                    item.put("timestamp", v.timestamp());
                    item.put("author", v.author());
                    item.put("note", v.note());
                    if (v.restoredFromVersion() != null) {
                        item.put("restoredFromVersion", v.restoredFromVersion());
                    }
                    item.put("snapshot", v.snapshot());
                    list.add(item);
                }
                serializedRoot.put(entry.getKey(), list);
            }
            String content = json.toJson(serializedRoot);
            Path tmp = Path.of(file.toString() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicEx) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // En modos sin permisos de escritura en disco, el historial sigue operando en memoria
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        if (source == null) return new LinkedHashMap<>();
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            copy.put(e.getKey(), deepCopyValue(e.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object val) {
        if (val == null) return null;
        if (val instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), deepCopyValue(v)));
            return out;
        }
        if (val instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object item : c) {
                out.add(deepCopyValue(item));
            }
            return out;
        }
        return val;
    }

    private static Map<String, Object> toPlainMap(Object obj) {
        if (obj instanceof JsonObject jo) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : jo.getMap().entrySet()) {
                out.put(e.getKey(), unwrapJsonValue(e.getValue()));
            }
            return out;
        }
        if (obj instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), unwrapJsonValue(v)));
            return out;
        }
        return new LinkedHashMap<>();
    }

    private static Object unwrapJsonValue(Object val) {
        if (val instanceof JsonObject jo) {
            return toPlainMap(jo);
        }
        if (val instanceof JsonArray ja) {
            List<Object> out = new ArrayList<>();
            for (Object item : ja.getList()) {
                out.add(unwrapJsonValue(item));
            }
            return out;
        }
        return val;
    }

    private static long toLong(Object val, long fallback) {
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (Exception ignored) {}
        }
        return fallback;
    }

    private static String strOrDefault(Object val, String fallback) {
        return val != null ? String.valueOf(val) : fallback;
    }

    private static VersionOperation parseOp(String raw) {
        try {
            return VersionOperation.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return VersionOperation.UPDATE;
        }
    }
}
