package com.jettra.memory.cluster;

import com.jettra.memory.engine.DiskStorageEngine;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Gestor de replicación y consistencia distribuida para el clúster de tres nodos de JettraMemory.
 * Garantiza que toda operación de escritura o borrado alcance el quórum mínimo de confirmación
 * (2 de 3 nodos) antes de ser considerada confirmada (Committed), previniendo pérdida de datos
 * y manteniendo consistencia entre nodos pares.
 */
public final class ClusterReplicationManager implements AutoCloseable {

    private final NodeCoordinator coordinator;
    private final DiskStorageEngine storageEngine;
    private final AtomicLong sequenceGenerator = new AtomicLong(0);
    private final Map<String, Consumer<ReplicationFrame>> remotePeerDispatchers = new ConcurrentHashMap<>();

    public ClusterReplicationManager(NodeCoordinator coordinator, DiskStorageEngine storageEngine) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator requerido");
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine requerido");
    }

    /**
     * Registra un despachador para comunicarse con un nodo par (in-memory o socket).
     */
    public void registerPeerDispatcher(String peerNodeId, Consumer<ReplicationFrame> dispatcher) {
        remotePeerDispatchers.put(peerNodeId, dispatcher);
    }

    /**
     * Conecta directamente un motor de almacenamiento de otro nodo par (ideal para pruebas y multi-instancia local).
     */
    public void linkDirectPeerEngine(String peerNodeId, DiskStorageEngine peerEngine) {
        registerPeerDispatcher(peerNodeId, frame -> {
            try {
                if (frame.operation() == ReplicationFrame.OperationType.PUT) {
                    peerEngine.put(frame.key(), frame.payload());
                } else if (frame.operation() == ReplicationFrame.OperationType.DELETE) {
                    peerEngine.delete(frame.key());
                }
            } catch (IOException e) {
                throw new RuntimeException("Fallo en la replicación directa al nodo " + peerNodeId, e);
            }
        });
    }

    /**
     * Replica una operación PUT en el clúster asegurando quórum de 2 de 3 nodos.
     */
    public boolean replicatePut(String key, byte[] payload) throws IOException {
        if (!coordinator.hasQuorum()) {
            throw new IOException("Fallo de quórum en clúster de 3 nodos: menos de 2 nodos disponibles.");
        }

        // 1. Escritura local
        storageEngine.put(key, payload);
        long seq = sequenceGenerator.incrementAndGet();
        ReplicationFrame frame = ReplicationFrame.put(seq, key, payload, coordinator.getLocalNode().getNodeId());

        // 2. Dispersión concurrente a los nodos pares con hilos virtuales
        int acks = 1; // El nodo local ya confirmó la escritura exitosa
        acks += broadcastFrameToPeers(frame);

        return acks >= NodeCoordinator.QUORUM_MAJORITY;
    }

    /**
     * Replica una operación DELETE en el clúster asegurando quórum de 2 de 3 nodos.
     */
    public boolean replicateDelete(String key) throws IOException {
        if (!coordinator.hasQuorum()) {
            throw new IOException("Fallo de quórum en clúster de 3 nodos: menos de 2 nodos disponibles.");
        }

        // 1. Eliminación local
        boolean deletedLocally = storageEngine.delete(key);
        long seq = sequenceGenerator.incrementAndGet();
        ReplicationFrame frame = ReplicationFrame.delete(seq, key, coordinator.getLocalNode().getNodeId());

        // 2. Dispersión concurrente
        int acks = 1;
        acks += broadcastFrameToPeers(frame);

        return deletedLocally && (acks >= NodeCoordinator.QUORUM_MAJORITY);
    }

    /**
     * Procesa una trama de replicación entrante desde un nodo par coordinador.
     */
    public void handleIncomingFrame(ReplicationFrame frame) throws IOException {
        if (frame.originNodeId().equals(coordinator.getLocalNode().getNodeId())) {
            return; // Ignorar tramas originadas por el mismo nodo
        }

        if (frame.operation() == ReplicationFrame.OperationType.PUT) {
            storageEngine.put(frame.key(), frame.payload());
        } else if (frame.operation() == ReplicationFrame.OperationType.DELETE) {
            storageEngine.delete(frame.key());
        }
    }

    /**
     * Sincroniza el estado completo hacia un nodo par que se reincorpora al clúster.
     */
    public int synchronizeTargetNode(DiskStorageEngine targetEngine) throws IOException {
        var liveEntries = storageEngine.getIndexManager().getAllLiveEntries();
        int syncCount = 0;
        for (String key : liveEntries.keySet()) {
            byte[] data = storageEngine.get(key);
            if (data != null) {
                targetEngine.put(key, data);
                syncCount++;
            }
        }
        return syncCount;
    }

    private int broadcastFrameToPeers(ReplicationFrame frame) {
        if (remotePeerDispatchers.isEmpty()) {
            return 0;
        }

        int acks = 0;
        // Usar hilos virtuales para enviar en paralelo a los pares
        List<CompletableFuture<Boolean>> futures = new java.util.ArrayList<>();
        for (Map.Entry<String, Consumer<ReplicationFrame>> entry : remotePeerDispatchers.entrySet()) {
            String peerId = entry.getKey();
            Consumer<ReplicationFrame> dispatcher = entry.getValue();

            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    dispatcher.accept(frame);
                    ClusterNode peer = coordinator.getPeerNodes().get(peerId);
                    if (peer != null) {
                        peer.recordHeartbeat();
                    }
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }, task -> Thread.ofVirtual().name("Jettra-Repl-" + peerId).start(task)));
        }

        for (CompletableFuture<Boolean> f : futures) {
            try {
                if (f.get(1500, TimeUnit.MILLISECONDS)) {
                    acks++;
                }
            } catch (Exception ignored) {
            }
        }

        return acks;
    }

    public long getCurrentSequence() {
        return sequenceGenerator.get();
    }

    public NodeCoordinator getCoordinator() {
        return coordinator;
    }

    @Override
    public void close() {
        remotePeerDispatchers.clear();
    }
}
