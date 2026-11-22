package io.jettra.example.se.repository;

import io.jettra.driver.JettraClient;
import io.jettra.example.se.model.NotaLibre;
import io.jettra.store.core.JettraBucketSchema;

import java.util.*;

/**
 * Repositorio para el bucket sin estructura fija ({@code NONE}) {@code NotasLibres}
 * en el engine {@code DOCUMENT}. Permite insertar documentos con atributos arbitrarios.
 */
public final class NotaLibreRepository implements JettraRepository<NotaLibre, String> {

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
    public String save(String documentId, NotaLibre entity) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (entity.atributos() != null) {
            payload.putAll(entity.atributos());
        }
        return client.upsertBucketRecord(database, ENGINE, BUCKET, documentId != null ? documentId : entity.id(), payload);
    }

    @Override
    public Optional<NotaLibre> findById(String id) {
        Map<String, Object> doc = client.getDatabase(database).getDocumentEngine(BUCKET).findById(id);
        if (doc == null) return Optional.empty();
        Map<String, Object> copy = new LinkedHashMap<>(doc);
        copy.remove("_id");
        return Optional.of(new NotaLibre(id, copy));
    }

    @Override
    public List<NotaLibre> findAll() {
        List<NotaLibre> out = new ArrayList<>();
        for (Map<String, Object> doc : client.getDatabase(database).getDocumentEngine(BUCKET)) {
            String id = String.valueOf(doc.getOrDefault("_id", ""));
            Map<String, Object> copy = new LinkedHashMap<>(doc);
            copy.remove("_id");
            out.add(new NotaLibre(id, copy));
        }
        return out;
    }

    @Override
    public boolean deleteById(String id) {
        return client.deleteBucketRecord(database, ENGINE, BUCKET, id);
    }
}
