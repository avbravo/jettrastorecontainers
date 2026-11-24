package io.jettra.driver.migrator;

import java.io.Serializable;

/**
 * Configuración de conexión y parámetros de migración de PostgreSQL a JettraStore.
 */
public class PostgresConnectionConfig implements Serializable {

    private String host = "127.0.0.1";
    private int port = 5432;
    private String database = "postgres";
    private String user = "postgres";
    private String password = "";
    private String schema = "public";
    private boolean ssl = false;
    private String targetDatabase = "migrated_db";
    private String targetEngine = "DOCUMENT";
    private boolean generateBackend = true;
    private String outputZipPath;
    private boolean dryRun = false;
    private int batchSize = 1000;
    private String backendPackageName = "com.example.jettra.backend";

    public PostgresConnectionConfig() {}

    public PostgresConnectionConfig(String host, int port, String database, String user, String password, String schema, String targetDatabase) {
        this.host = host != null ? host.trim() : "127.0.0.1";
        this.port = port > 0 ? port : 5432;
        this.database = database != null ? database.trim() : "postgres";
        this.user = user != null ? user.trim() : "postgres";
        this.password = password != null ? password : "";
        this.schema = (schema != null && !schema.isBlank()) ? schema.trim() : "public";
        this.targetDatabase = (targetDatabase != null && !targetDatabase.isBlank()) ? targetDatabase.trim() : this.database;
    }

    public String toJdbcUrl() {
        return toJdbcUrl(this.database);
    }

    public String toJdbcUrl(String overrideDatabase) {
        String db = (overrideDatabase != null && !overrideDatabase.isBlank()) ? overrideDatabase.trim() : this.database;
        if (db == null || db.isBlank()) {
            db = "postgres";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("jdbc:postgresql://").append(host).append(":").append(port).append("/").append(db);
        if (schema != null && !schema.isBlank()) {
            sb.append("?currentSchema=").append(schema);
            sb.append(ssl ? "&ssl=true&sslmode=require" : "&ssl=false");
        } else {
            sb.append(ssl ? "?ssl=true&sslmode=require" : "?ssl=false");
        }
        sb.append("&applicationName=JettraStoreMigrator");
        return sb.toString();
    }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }

    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }

    public boolean isSsl() { return ssl; }
    public void setSsl(boolean ssl) { this.ssl = ssl; }

    public String getTargetDatabase() { return targetDatabase; }
    public void setTargetDatabase(String targetDatabase) { this.targetDatabase = targetDatabase; }

    public String getTargetEngine() { return targetEngine; }
    public void setTargetEngine(String targetEngine) { this.targetEngine = targetEngine; }

    public boolean isGenerateBackend() { return generateBackend; }
    public void setGenerateBackend(boolean generateBackend) { this.generateBackend = generateBackend; }

    public String getOutputZipPath() { return outputZipPath; }
    public void setOutputZipPath(String outputZipPath) { this.outputZipPath = outputZipPath; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public String getBackendPackageName() { return backendPackageName; }
    public void setBackendPackageName(String backendPackageName) { this.backendPackageName = backendPackageName; }

    @Override
    public String toString() {
        return "PostgresConnectionConfig{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", database='" + database + '\'' +
                ", user='" + user + '\'' +
                ", schema='" + schema + '\'' +
                ", targetDatabase='" + targetDatabase + '\'' +
                ", targetEngine='" + targetEngine + '\'' +
                ", generateBackend=" + generateBackend +
                ", dryRun=" + dryRun +
                '}';
    }
}
