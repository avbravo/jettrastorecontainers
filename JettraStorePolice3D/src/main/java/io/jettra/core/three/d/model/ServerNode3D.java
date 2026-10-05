package io.jettra.core.three.d.model;

import com.raylib.BoundingBox;
import com.raylib.Vector3;
import io.jettra.store.cluster.ClusterNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ServerNode3D {
    private final String id;
    private final String name;
    private final String host;
    private final int port;
    private ClusterNode.Role role;
    private ClusterNode.RaftState raftState;
    private ClusterNode.NodeStatus status;
    private boolean online;
    private boolean simulatedOffline;
    private long lastHeartbeat;
    private long latencyMs;
    private String statusMessage;

    // Métricas de recursos consumidos
    private long heapUsedMb;
    private long heapTotalMb;
    private long heapMaxMb;
    private double heapSaturationPercent;
    private long panamaDirectMemMb;
    private int cpuCores;
    private int activeVirtualThreads;
    private long memTableMb;
    private long diskSSTablesBytes;
    private int diskFilesCount;
    private long offloadedBytes;
    private String policeDiagnosis;

    // 3D
    private float x, y, z;
    private float width = 2.6f;
    private float height = 5.2f;
    private float depth = 2.6f;
    private float pulsePhase = 0f;

    // Bases de datos alojadas en este nodo
    private final List<DatabaseInfo3D> databases = new ArrayList<>();

    public ServerNode3D(String id, String name, String host, int port, ClusterNode.Role role, float x, float y, float z) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
        this.role = role;
        this.raftState = (role == ClusterNode.Role.PRIMARY) ? ClusterNode.RaftState.LEADER : ClusterNode.RaftState.FOLLOWER;
        this.status = ClusterNode.NodeStatus.RUNNING;
        this.online = true;
        this.simulatedOffline = false;
        this.lastHeartbeat = System.currentTimeMillis();
        this.latencyMs = 1;
        this.statusMessage = "EN LÍNEA (Quórum Activo)";
        this.policeDiagnosis = "SALUDABLE: Estabilidad operativa garantizada por JettraPolice";
        this.x = x;
        this.y = y;
        this.z = z;
        this.cpuCores = Runtime.getRuntime().availableProcessors();
        this.activeVirtualThreads = 16;
        this.panamaDirectMemMb = 64;
        this.memTableMb = 128;

        initDatabases();
    }

    private void initDatabases() {
        databases.clear();
        boolean isMaster = (role == ClusterNode.Role.PRIMARY);

        // 1. example_factura_db
        databases.add(new DatabaseInfo3D(
            "example_factura_db",
            "Facturación Electrónica Multimodelo",
            "Facturación comercial con soporte JSON, KeyValue, Vector IA, Graph y TimeSeries.",
            "Multimodelo Off-Heap (Panama FFM)",
            3_750_000L,
            "1.2 GB",
            Arrays.asList("facturas (1M)", "detalles_factura (1M)", "clientes (200k)", "cache_folios (300k)",
                          "factura_embeddings (200k)", "red_comercial (200k)", "volumen_facturacion (50k)",
                          "tiendas_coordenadas (50k)", "auditoria (750k)"),
            isMaster ? 24500 : 18200,
            online ? (isMaster ? "LEADER / SYNCED" : "REPLICA / SYNCED") : "OFFLINE / UNREACHABLE",
            -6.5f, 0.0f, -2.5f,
            255, 190, 30
        ));

        // 2. samples_hostipal_db
        databases.add(new DatabaseInfo3D(
            "samples_hostipal_db",
            "Red Hospitalaria y Salud Pública",
            "Gestión médica masiva: pacientes, medicamentos, afecciones, doctores y camas UCI.",
            "LSM Disk + Off-Heap MemTable",
            2_000_000L,
            "850 MB",
            Arrays.asList("pacientes (400k)", "medicamentos (300k)", "afecciones (250k)", "enfermedades (250k)",
                          "hospitales (100k)", "doctores (200k)", "admisiones (300k)", "camas_uci (200k)"),
            isMaster ? 19200 : 14800,
            online ? (isMaster ? "LEADER / SYNCED" : "REPLICA / SYNCED") : "OFFLINE / UNREACHABLE",
            -2.2f, 0.0f, -6.5f,
            0, 220, 220
        ));

        // 3. samples_ambiental_db
        databases.add(new DatabaseInfo3D(
            "samples_ambiental_db",
            "Monitoreo Ambiental Mundial",
            "Telemetría mundial de sensores climáticos: CO2, temperatura, radiación UV y calidad de aire.",
            "TimeSeries Engine + Vector Index",
            3_000_000L,
            "1.45 GB",
            Arrays.asList("estaciones (300k)", "mediciones_co2 (800k)", "temperatura_global (700k)",
                          "radiacion_uv (500k)", "calidad_aire (400k)", "eventos_climaticos (300k)"),
            isMaster ? 28100 : 21000,
            online ? (isMaster ? "LEADER / SYNCED" : "REPLICA / SYNCED") : "OFFLINE / UNREACHABLE",
            2.2f, 0.0f, -6.5f,
            50, 220, 100
        ));

        // 4. system_metadata_db
        databases.add(new DatabaseInfo3D(
            "system_metadata_db",
            "Catálogo y Consenso Raft",
            "Metadatos de esquemas, credenciales, logs de consenso Raft y auditorías policiales.",
            "Records Off-Heap Engine",
            25_000L,
            "45 MB",
            Arrays.asList("schemas", "user_auth", "raft_logs", "police_audits"),
            isMaster ? 8700 : 6200,
            online ? (isMaster ? "LEADER / SYNCED" : "REPLICA / SYNCED") : "OFFLINE / UNREACHABLE",
            6.5f, 0.0f, -2.5f,
            180, 70, 240
        ));
    }

    public BoundingBox getBoundingBox() {
        Vector3 min = new Vector3().x(x - width / 2.0f).y(y).z(z - depth / 2.0f);
        Vector3 max = new Vector3().x(x + width / 2.0f).y(y + height).z(z + depth / 2.0f);
        return new BoundingBox(min, max);
    }

    public void toggleOffline() {
        this.simulatedOffline = !this.simulatedOffline;
        if (this.simulatedOffline) {
            this.online = false;
            this.status = ClusterNode.NodeStatus.OFFLINE;
            this.statusMessage = "FUERA DE SERVICIO (Simulado / Desconexión)";
            this.policeDiagnosis = "CRÍTICO: Nodo fuera de servicio. Desconexión detectada por JettraStorePolice.";
        } else {
            this.online = true;
            this.status = ClusterNode.NodeStatus.RUNNING;
            this.statusMessage = "EN LÍNEA (Quórum Activo)";
            this.policeDiagnosis = "SALUDABLE: Servicio restaurado e inspeccionado por JettraPolice.";
            this.lastHeartbeat = System.currentTimeMillis();
        }
        initDatabases();
    }

    public List<DatabaseInfo3D> getDatabases() {
        return Collections.unmodifiableList(databases);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public ClusterNode.Role getRole() { return role; }
    public void setRole(ClusterNode.Role role) { this.role = role; }
    public ClusterNode.RaftState getRaftState() { return raftState; }
    public void setRaftState(ClusterNode.RaftState raftState) { this.raftState = raftState; }
    public ClusterNode.NodeStatus getStatus() { return status; }
    public void setStatus(ClusterNode.NodeStatus status) { this.status = status; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }
    public boolean isSimulatedOffline() { return simulatedOffline; }
    public void setSimulatedOffline(boolean simulatedOffline) { this.simulatedOffline = simulatedOffline; }
    public long getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(long lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }

    public long getHeapUsedMb() { return heapUsedMb; }
    public void setHeapUsedMb(long heapUsedMb) { this.heapUsedMb = heapUsedMb; }
    public long getHeapTotalMb() { return heapTotalMb; }
    public void setHeapTotalMb(long heapTotalMb) { this.heapTotalMb = heapTotalMb; }
    public long getHeapMaxMb() { return heapMaxMb; }
    public void setHeapMaxMb(long heapMaxMb) { this.heapMaxMb = heapMaxMb; }
    public double getHeapSaturationPercent() { return heapSaturationPercent; }
    public void setHeapSaturationPercent(double heapSaturationPercent) { this.heapSaturationPercent = heapSaturationPercent; }
    public long getPanamaDirectMemMb() { return panamaDirectMemMb; }
    public void setPanamaDirectMemMb(long panamaDirectMemMb) { this.panamaDirectMemMb = panamaDirectMemMb; }
    public int getCpuCores() { return cpuCores; }
    public void setCpuCores(int cpuCores) { this.cpuCores = cpuCores; }
    public int getActiveVirtualThreads() { return activeVirtualThreads; }
    public void setActiveVirtualThreads(int activeVirtualThreads) { this.activeVirtualThreads = activeVirtualThreads; }
    public long getMemTableMb() { return memTableMb; }
    public void setMemTableMb(long memTableMb) { this.memTableMb = memTableMb; }
    public long getDiskSSTablesBytes() { return diskSSTablesBytes; }
    public void setDiskSSTablesBytes(long diskSSTablesBytes) { this.diskSSTablesBytes = diskSSTablesBytes; }
    public int getDiskFilesCount() { return diskFilesCount; }
    public void setDiskFilesCount(int diskFilesCount) { this.diskFilesCount = diskFilesCount; }
    public long getOffloadedBytes() { return offloadedBytes; }
    public void setOffloadedBytes(long offloadedBytes) { this.offloadedBytes = offloadedBytes; }
    public String getPoliceDiagnosis() { return policeDiagnosis; }
    public void setPoliceDiagnosis(String policeDiagnosis) { this.policeDiagnosis = policeDiagnosis; }

    public float getX() { return x; }
    public float getY() { return y; }
    public float getZ() { return z; }
    public float getWidth() { return width; }
    public float getHeight() { return height; }
    public float getDepth() { return depth; }
    public float getPulsePhase() { return pulsePhase; }
    public void setPulsePhase(float pulsePhase) { this.pulsePhase = pulsePhase; }
}
