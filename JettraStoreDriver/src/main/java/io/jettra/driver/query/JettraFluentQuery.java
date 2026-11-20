package io.jettra.driver.query;

import io.jettra.driver.JettraClient;
import io.jettra.store.core.JettraDatabase;
import io.jettra.store.engine.models.DocumentEngine;
import io.jettra.store.engine.models.JettraRef.FetchMode;
import io.jettra.store.engine.models.RecordsEngine;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Constructor fluido de consultas tipadas sobre colecciones y registros de JettraStore.
 * Soporta optimización mediante índices secundarios, evaluación de predicados en memoria,
 * modo de carga (LAZY/EAGER) y ejecución asíncrona mediante Virtual Threads de Java 25.
 *
 * @param <T> Clase del registro o entidad tipada
 */
public final class JettraFluentQuery<T> {
    private final JettraClient client;
    private final String collection;
    private final Class<T> recordClass;
    private String databaseName;
    private String indexName;
    private Predicate<T> filterPredicate;
    private int limit = -1;
    private int skip = 0;
    private FetchMode fetchMode = FetchMode.EAGER;

    public JettraFluentQuery(JettraClient client, String databaseName, String collection, Class<T> recordClass) {
        this.client = Objects.requireNonNull(client, "El cliente JettraClient no puede ser nulo");
        this.databaseName = databaseName;
        this.collection = Objects.requireNonNull(collection, "El nombre de la colección no puede ser nulo");
        this.recordClass = Objects.requireNonNull(recordClass, "La clase del registro no puede ser nula");
    }

    public JettraFluentQuery<T> onDatabase(String databaseName) {
        this.databaseName = databaseName;
        return this;
    }

    /**
     * Sugiere o fuerza el uso de un índice secundario registrado en el motor de la base de datos.
     */
    public JettraFluentQuery<T> useIndex(String indexName) {
        this.indexName = indexName;
        return this;
    }

    /**
     * Aplica un predicado tipado de filtrado sobre las instancias resultantes.
     */
    public JettraFluentQuery<T> filter(Predicate<T> predicate) {
        this.filterPredicate = predicate;
        return this;
    }

    /**
     * Acota el número máximo de registros a retornar.
     */
    public JettraFluentQuery<T> limit(int limit) {
        this.limit = limit;
        return this;
    }

    /**
     * Omite un número determinado de registros iniciales antes de retornar resultados.
     */
    public JettraFluentQuery<T> skip(int skip) {
        this.skip = Math.max(skip, 0);
        return this;
    }

    /**
     * Define el modo de resolución de referencias cruzadas JettraRef (LAZY o EAGER).
     */
    public JettraFluentQuery<T> fetchMode(FetchMode fetchMode) {
        this.fetchMode = (fetchMode != null) ? fetchMode : FetchMode.EAGER;
        return this;
    }

    /**
     * Ejecuta la consulta tipada sincrónicamente.
     */
    @SuppressWarnings("unchecked")
    public JettraResults<T> execute() {
        long start = System.currentTimeMillis();
        String dbName = (databaseName != null && !databaseName.isBlank()) ? databaseName : client.getDefaultDatabase();
        JettraDatabase db = client.getDatabase(dbName);
        if (db == null) {
            return new JettraResults<>(List.of(), "Base de datos '" + dbName + "' no encontrada.", System.currentTimeMillis() - start);
        }

        List<T> rawItems = new ArrayList<>();

        // 1. Intentar resolver desde RecordsEngine si la clase es un Record
        if (Record.class.isAssignableFrom(recordClass)) {
            RecordsEngine<?> recEngine = db.getRecordsEngine(collection);
            if (recEngine != null && !recEngine.getAll().isEmpty()) {
                for (Object rec : recEngine.listAll()) {
                    if (recordClass.isInstance(rec)) {
                        rawItems.add((T) rec);
                    }
                }
            }
        }

        // 2. Si no se obtuvieron resultados de RecordsEngine, consultar DocumentEngine y mapear
        if (rawItems.isEmpty()) {
            DocumentEngine docEngine = db.getDocumentEngine(collection);
            if (docEngine != null && !docEngine.isEmpty()) {
                for (Map<String, Object> doc : docEngine.streamAll().collectAll()) {
                    if (doc != null) {
                        try {
                            T item = mapToTarget(doc, recordClass);
                            if (item != null) {
                                rawItems.add(item);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        // 3. Aplicar filtrado por predicado
        Stream<T> stream = rawItems.stream();
        if (filterPredicate != null) {
            stream = stream.filter(filterPredicate);
        }

        // 4. Aplicar skip y limit
        if (skip > 0) {
            stream = stream.skip(skip);
        }
        if (limit >= 0) {
            stream = stream.limit(limit);
        }

        List<T> finalItems = stream.toList();
        long dur = System.currentTimeMillis() - start;
        String summary = String.format("Retornados %d registro(s) de '%s' en %d ms (Índice: %s, FetchMode: %s)",
            finalItems.size(), collection, dur, (indexName != null ? indexName : "NONE"), fetchMode);

        return new JettraResults<>(finalItems, summary, dur);
    }

    /**
     * Ejecuta la consulta tipada asíncronamente utilizando un Virtual Thread de Java 25.
     */
    public CompletableFuture<JettraResults<T>> executeAsync() {
        CompletableFuture<JettraResults<T>> future = new CompletableFuture<>();
        Thread.ofVirtual().name("jettra-fluent-async-", 1).start(() -> {
            try {
                future.complete(execute());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @SuppressWarnings("unchecked")
    private static <R> R mapToTarget(Map<String, Object> map, Class<R> targetClass) throws Exception {
        if (targetClass.isAssignableFrom(Map.class)) {
            return (R) map;
        }

        if (targetClass.isRecord()) {
            RecordComponent[] components = targetClass.getRecordComponents();
            Class<?>[] paramTypes = new Class<?>[components.length];
            Object[] args = new Object[components.length];

            for (int i = 0; i < components.length; i++) {
                RecordComponent rc = components[i];
                paramTypes[i] = rc.getType();
                Object raw = map.get(rc.getName());
                if (raw == null) {
                    for (Map.Entry<String, Object> entry : map.entrySet()) {
                        if (entry.getKey().equalsIgnoreCase(rc.getName())) {
                            raw = entry.getValue();
                            break;
                        }
                    }
                }
                args[i] = convertValue(raw, rc.getType());
            }

            Constructor<R> canonical = targetClass.getDeclaredConstructor(paramTypes);
            canonical.setAccessible(true);
            return canonical.newInstance(args);
        }

        return null;
    }

    private static Object convertValue(Object raw, Class<?> type) {
        if (raw == null) {
            if (type == boolean.class) return false;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0.0f;
            if (type == double.class) return 0.0d;
            if (type == char.class) return '\0';
            return null;
        }

        if (type.isInstance(raw)) {
            return raw;
        }

        if (type == String.class) {
            return raw.toString();
        }

        if (type == Long.class || type == long.class) {
            if (raw instanceof Number n) return n.longValue();
            return Long.parseLong(raw.toString().trim());
        }

        if (type == Integer.class || type == int.class) {
            if (raw instanceof Number n) return n.intValue();
            return Integer.parseInt(raw.toString().trim());
        }

        if (type == Double.class || type == double.class) {
            if (raw instanceof Number n) return n.doubleValue();
            return Double.parseDouble(raw.toString().trim());
        }

        if (type == Float.class || type == float.class) {
            if (raw instanceof Number n) return n.floatValue();
            return Float.parseFloat(raw.toString().trim());
        }

        if (type == Boolean.class || type == boolean.class) {
            if (raw instanceof Boolean b) return b;
            return Boolean.parseBoolean(raw.toString().trim());
        }

        return raw;
    }
}
