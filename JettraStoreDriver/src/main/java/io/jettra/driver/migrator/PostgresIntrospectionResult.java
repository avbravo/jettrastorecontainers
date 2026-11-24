package io.jettra.driver.migrator;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Resultado completo del análisis e introspección de esquema de PostgreSQL.
 */
public class PostgresIntrospectionResult implements Serializable {

    private String host;
    private int port;
    private String database;
    private String schema;
    private List<String> availableDatabases = new ArrayList<>();
    private List<String> availableSchemas = new ArrayList<>();
    private List<PostgresTableMeta> tables = new ArrayList<>();
    private long totalRows;
    private boolean success = true;
    private String errorMessage;
    private long analysisTimeMs;

    public PostgresIntrospectionResult() {}

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }

    public List<PostgresTableMeta> getTables() { return tables; }
    public void setTables(List<PostgresTableMeta> tables) { this.tables = tables; }

    public long getTotalRows() { return totalRows; }
    public void setTotalRows(long totalRows) { this.totalRows = totalRows; }

    public List<String> getAvailableDatabases() { return availableDatabases; }
    public void setAvailableDatabases(List<String> availableDatabases) { this.availableDatabases = availableDatabases != null ? availableDatabases : new ArrayList<>(); }

    public List<String> getAvailableSchemas() { return availableSchemas; }
    public void setAvailableSchemas(List<String> availableSchemas) { this.availableSchemas = availableSchemas != null ? availableSchemas : new ArrayList<>(); }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getAnalysisTimeMs() { return analysisTimeMs; }
    public void setAnalysisTimeMs(long analysisTimeMs) { this.analysisTimeMs = analysisTimeMs; }
}
