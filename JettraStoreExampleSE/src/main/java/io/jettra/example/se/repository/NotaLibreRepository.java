package io.jettra.example.se.repository;

import io.jettra.driver.JettraClient;
import io.jettra.example.se.model.NotaLibre;
import io.jettra.store.core.JettraBucketSchema;
import io.jettra.store.core.JettraId;

import java.util.*;

/**
 * Repositorio para el Bucket sin estructura fija ({@code NONE}) {@code NotasLibres}
 * en el engine {@code DOCUMENT}. Permite insertar, actualizar y consultar registros con atributos
 * arbitrarios identificados siempre por su {@link JettraId}.
 */
public final class NotaLibreRepository implements JettraRepository<NotaLibre> {

    public static final String ENGINE = "DOCUMENT";
    public static final String BUCKET = "NotasLibres";

    private final JettraClient client;
    private final String database;

    public NotaLibreRepository(JettraClient client, String database) {
        this.client = client;
        this.database = database;
    }

    public void initBucket() {
        client.createBucket(database, ENGINE, BUCKET, JettraBucketSchema.StructureType.NONE, List.of());
    }

    @Override
    public NotaLibre save(NotaLibre entity) {
        JettraId assignedId = entity.jettraId() != null
                ? entity.jettraId()
                : JettraId.generate(database, BUCKET);
        String idHex = assignedId.toHexString();

        Map<String, Object> payload = new LinkedHashMap<>();
        if (entity.atributos() != null) {
            payload.putAll(entity.atributos());
        }
        payload.put("jettraId", idHex);

        String storedId = client.insertBucketRecord(database, ENGINE, BUCKET, idHex, payload);
        JettraId resolvedId = (storedId != null && JettraId.isValidHex(storedId))
                ? JettraId.fromHex(storedId)
                : assignedId;
        return findById(resolvedId).orElseGet(() -> entity.withJettraId(resolvedId));
    }

    @Override
    public NotaLibre update(JettraId jettraId, NotaLibre entity) {
        Objects.requireNonNull(jettraId, "El JettraId es obligatorio para actualizar NotaLibre");
        String idHex = jettraId.toHexString();
        if (findById(jettraId).isEmpty()) {
            throw new NoSuchElementException("No existe NotaLibre con JettraId=" + idHex);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (entity.atributos() != null) {
            payload.putAll(entity.atributos());
        }
        payload.put("jettraId", idHex);

        client.upsertBucketRecord(database, ENGINE, BUCKET, idHex, payload);
        return findById(jettraId).orElseGet(() -> entity.withJettraId(jettraId));
    }

    @Override
    public Optional<NotaLibre> findById(JettraId jettraId) {
        if (jettraId == null) return Optional.empty();
        return findByRawId(jettraId.toHexString());
    }

    public Optional<NotaLibre> findByRawId(String rawId) {
        if (rawId == null || rawId.isBlank()) return Optional.empty();
        Map<String, Object> doc = client.findBucketRecordById(database, BUCKET, rawId.trim());
        if (doc == null) return Optional.empty();
        return Optional.of(toRecord(doc));
    }

    @Override
    public List<NotaLibre> findAll() {
        List<NotaLibre> out = new ArrayList<>();
        for (Map<String, Object> doc : client.findAllBucketRecords(database, BUCKET)) {
            out.add(toRecord(doc));
        }
        return out;
    }

    @Override
    public boolean deleteById(JettraId jettraId) {
        if (jettraId == null) return false;
        return client.deleteBucketRecord(database, ENGINE, BUCKET, jettraId.toHexString());
    }

    private static NotaLibre toRecord(Map<String, Object> doc) {
        String idHex = doc.get("jettraId") != null
                ? String.valueOf(doc.get("jettraId"))
                : (doc.get("_id") != null ? String.valueOf(doc.get("_id")) : null);
        JettraId jid;
        if (idHex != null && !idHex.isBlank() && JettraId.isValidHex(idHex)) {
            jid = JettraId.fromHex(idHex);
        } else if (idHex != null && !idHex.isBlank()) {
            // Soporte tolerante para registros legacy o IDs string personalizados
            jid = JettraId.generate("examplesedb", BUCKET);
        } else {
            jid = null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(doc);
        copy.remove("_id");
        copy.remove("jettraId");
        return new NotaLibre(jid, copy);
    }
}
