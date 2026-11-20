package io.jettra.driver.query;

import io.jettra.driver.JettraClient;
import io.jettra.store.engine.query.JettraSQLProcessor;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Comando fluido de ejecución SQL para JettraStore con soporte asíncrono sobre Virtual Threads.
 */
public final class JettraSqlCommand {
    private final JettraClient client;
    private final String query;
    private String databaseName;

    public JettraSqlCommand(JettraClient client, String databaseName, String query) {
        this.client = Objects.requireNonNull(client, "El cliente JettraClient no puede ser nulo");
        this.databaseName = databaseName;
        this.query = Objects.requireNonNull(query, "La consulta SQL no puede ser nula");
    }

    public JettraSqlCommand onDatabase(String databaseName) {
        this.databaseName = databaseName;
        return this;
    }

    public String getQuery() {
        return query;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    /**
     * Ejecuta la consulta SQL sincrónicamente y retorna el resultado navegable por cursor JettraResultSet.
     */
    public JettraResultSet execute() {
        String db = (databaseName != null && !databaseName.isBlank()) ? databaseName : client.getDefaultDatabase();
        JettraSQLProcessor.QueryResult qr = client.sql(db, query);
        return new JettraResultSet(qr);
    }

    /**
     * Ejecuta la consulta SQL asíncronamente en un Virtual Thread nativo de Java 25.
     */
    public CompletableFuture<JettraResultSet> executeAsync() {
        CompletableFuture<JettraResultSet> future = new CompletableFuture<>();
        Thread.ofVirtual().name("jettra-sql-async-", 1).start(() -> {
            try {
                future.complete(execute());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public String toString() {
        return "JettraSqlCommand[database=" + databaseName + ", query=" + query + "]";
    }
}
