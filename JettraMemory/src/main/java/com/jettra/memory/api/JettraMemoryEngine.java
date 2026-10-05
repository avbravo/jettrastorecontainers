package com.jettra.memory.api;

import com.jettra.memory.cluster.ClusterNode;
import com.jettra.memory.cluster.ClusterReplicationManager;
import com.jettra.memory.cluster.NodeCoordinator;
import com.jettra.memory.collections.JettraOffHeapCollectionFactory;
import com.jettra.memory.engine.DiskStorageEngine;
import com.jettra.memory.engine.StorageMetrics;
import com.jettra.memory.gc.CompactionResult;
import com.jettra.memory.gc.JettraGarbageCollector;
import com.jettra.memory.integration.JettraEERepository;
import com.jettra.memory.integration.JettraStoreConnector;

import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

/**
 * Fachada principal del motor JettraMemory.
 * Expone las operaciones fundamentales de lectura, escritura, eliminación,
 * compactación personalizada, y coordinación distribuida en tres nodos.
 */
public final class JettraMemoryEngine implements AutoCloseable {

    private final JettraMemoryConfig config;
    private final DiskStorageEngine storageEngine;
    private final JettraGarbageCollector garbageCollector;
    private final NodeCoordinator nodeCoordinator;
    private final ClusterReplicationManager replicationManager;
    private final JettraStoreConnector storeConnector;

    public JettraMemoryEngine(JettraMemoryConfig config) throws IOException {
        this.config = Objects.requireNonNull(config, "config requerido");

        // 1. Inicializar motor de disco off-heap
        this.storageEngine = new DiskStorageEngine(config.getStorageDirectory(), config.getStoreName());

        // 2. Inicializar recolector de basura autónomo
        this.garbageCollector = new JettraGarbageCollector(
                storageEngine,
                config.getCompactionThreshold(),
                config.isAutoGcEnabled()
        );

        // 3. Inicializar coordinación del clúster de tres nodos
        ClusterNode localNode = new ClusterNode(
                config.getNodeId(),
                config.getHost(),
                config.getPort(),
                ClusterNode.Role.PRIMARY
        );
        this.nodeCoordinator = new NodeCoordinator(localNode);
        for (ClusterNode peer : config.getPeerNodes()) {
            this.nodeCoordinator.registerPeer(peer);
        }

        // 4. Inicializar gestor de replicación con quórum
        this.replicationManager = new ClusterReplicationManager(nodeCoordinator, storageEngine);

        // 5. Conector JettraStore
        this.storeConnector = new JettraStoreConnector(storageEngine);
    }

    /**
     * Escribe un registro binario en disco fuera del Heap.
     * En modo clúster distribuido, replica concurrentemente asegurando quórum (2 de 3 nodos).
     */
    public void put(String key, byte[] data) throws IOException {
        if (config.isClusterModeEnabled() && !nodeCoordinator.getPeerNodes().isEmpty()) {
            replicationManager.replicatePut(key, data);
        } else {
            storageEngine.put(key, data);
        }
    }

    /**
     * Recupera un registro binario directamente desde el disco mediante acceso FFM sin pausas de GC.
     */
    public byte[] get(String key) throws IOException {
        return storageEngine.get(key);
    }

    /**
     * Elimina un registro generando un Tombstone en disco y replicando con quórum si está en clúster.
     */
    public boolean delete(String key) throws IOException {
        if (config.isClusterModeEnabled() && !nodeCoordinator.getPeerNodes().isEmpty()) {
            return replicationManager.replicateDelete(key);
        } else {
            return storageEngine.delete(key);
        }
    }

    /**
     * Ejecuta manualmente el proceso de desfragmentación y compactación de disco.
     */
    public CompactionResult compact() throws Exception {
        return garbageCollector.compact();
    }

    /**
     * Sincroniza y vuelca el índice y datos pendientes al almacenamiento físico en disco.
     */
    public void flush() throws IOException {
        storageEngine.flush();
    }

    public boolean containsKey(String key) {
        return storageEngine.containsKey(key);
    }

    public int size() {
        return storageEngine.size();
    }

    public StorageMetrics getMetrics() {
        return storageEngine.getMetrics();
    }

    public DiskStorageEngine getStorageEngine() {
        return storageEngine;
    }

    public JettraGarbageCollector getGarbageCollector() {
        return garbageCollector;
    }

    public NodeCoordinator getNodeCoordinator() {
        return nodeCoordinator;
    }

    public ClusterReplicationManager getReplicationManager() {
        return replicationManager;
    }

    public JettraStoreConnector getStoreConnector() {
        return storeConnector;
    }

    public Map<String, String> getOffHeapMap(String namespace) {
        return JettraOffHeapCollectionFactory.createStringMap(namespace, storageEngine);
    }

    public <T extends Serializable> JettraEERepository<T> createEERepository(Class<T> entityClass) {
        return new JettraEERepository<>(entityClass, storageEngine);
    }

    public JettraMemoryConfig getConfig() {
        return config;
    }

    @Override
    public void close() throws Exception {
        try {
            if (garbageCollector != null) garbageCollector.close();
        } finally {
            try {
                if (replicationManager != null) replicationManager.close();
            } finally {
                try {
                    if (nodeCoordinator != null) nodeCoordinator.close();
                } finally {
                    if (storageEngine != null) storageEngine.close();
                }
            }
        }
    }
}
