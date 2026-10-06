package io.jettra.store.cluster;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cliente de replicación y consenso Raft distribuido para JettraStore.
 * Gestiona el envío concurrente de tramas a nodos pares con Virtual Threads
 * y asegura el quórum de mayoría (mínimo 2 de 3 nodos) para operaciones de catálogo y datos.
 */
public final class JettraClusterReplicationClient implements AutoCloseable {

    private final String localNodeId;
    private final List<ClusterNode> peers;
    private final AtomicLong currentTerm = new AtomicLong(1);
    private final AtomicLong logIndex = new AtomicLong(0);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private static final int TIMEOUT_MS = 1000;

    public JettraClusterReplicationClient(String localNodeId, List<ClusterNode> peers) {
        this.localNodeId = localNodeId != null ? localNodeId : "node-01";
        this.peers = peers != null ? new CopyOnWriteArrayList<>(peers) : new CopyOnWriteArrayList<>();
    }

    /**
     * Envía una trama de creación de base de datos a todos los nodos secundarios configurados
     * y espera quórum mayoritario (1 local + al menos 1 secundario = 2 de 3).
     */
    public boolean broadcastCreateDatabase(String dbName) {
        return broadcastCreateDatabase(dbName, new byte[0]);
    }

    public boolean broadcastCreateDatabase(String dbName, byte[] payload) {
        long term = currentTerm.get();
        long idx = logIndex.incrementAndGet();
        JettraRaftFrame frame = JettraRaftFrame.createDatabase(term, idx, localNodeId, dbName, payload);
        return broadcastFrameWithQuorum(frame);
    }

    /**
     * Envía una trama de distribución masiva/integral de base de datos (con todos sus registros serializados)
     * a todos los nodos secundarios configurados y espera quórum mayoritario.
     */
    public boolean broadcastDistributeDatabase(String dbName, byte[] payload) {
        long term = currentTerm.get();
        long idx = logIndex.incrementAndGet();
        JettraRaftFrame frame = JettraRaftFrame.distributeDatabase(term, idx, localNodeId, dbName, payload);
        return broadcastFrameWithQuorum(frame);
    }

    /**
     * Envía una trama de eliminación de base de datos a los nodos secundarios.
     */
    public boolean broadcastDropDatabase(String dbName) {
        long term = currentTerm.get();
        long idx = logIndex.incrementAndGet();
        JettraRaftFrame frame = JettraRaftFrame.dropDatabase(term, idx, localNodeId, dbName);

        int acks = 1;
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();

        for (ClusterNode peer : peers) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    JettraRaftFrame resp = sendFrameToPeer(peer, frame);
                    return resp != null && resp.frameType() == JettraRaftFrame.TYPE_ACK;
                } catch (Exception e) {
                    return false;
                }
            }, executor));
        }

        for (var f : futures) {
            try {
                if (f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    acks++;
                }
            } catch (Exception ignored) {}
        }

        return acks >= 2 || peers.isEmpty();
    }

    /**
     * Envía una trama de inserción/actualización de documento a todos los nodos secundarios.
     */
    public boolean broadcastPutDocument(String dbName, String colName, String id, byte[] jsonBytes) {
        long term = currentTerm.get();
        long idx = logIndex.incrementAndGet();
        JettraRaftFrame frame = JettraRaftFrame.putDocument(term, idx, localNodeId, dbName, colName, id, jsonBytes);
        return broadcastFrameWithQuorum(frame);
    }

    /**
     * Envía una trama de eliminación de documento a todos los nodos secundarios.
     */
    public boolean broadcastDeleteDocument(String dbName, String colName, String id) {
        long term = currentTerm.get();
        long idx = logIndex.incrementAndGet();
        JettraRaftFrame frame = JettraRaftFrame.deleteDocument(term, idx, localNodeId, dbName, colName, id);
        return broadcastFrameWithQuorum(frame);
    }

    /**
     * Envía una trama de creación de índice secundario a todos los nodos secundarios.
     */
    public boolean broadcastCreateIndex(String dbName, String colName, String indexName, String field, String type, boolean unique) {
        long term = currentTerm.get();
        long idx = logIndex.incrementAndGet();
        JettraRaftFrame frame = JettraRaftFrame.createIndex(term, idx, localNodeId, dbName, colName, indexName, field, type, unique);
        return broadcastFrameWithQuorum(frame);
    }

    /**
     * Envía una trama de eliminación de índice secundario a todos los nodos secundarios.
     */
    public boolean broadcastDropIndex(String dbName, String indexName) {
        long term = currentTerm.get();
        long idx = logIndex.incrementAndGet();
        JettraRaftFrame frame = JettraRaftFrame.dropIndex(term, idx, localNodeId, dbName, indexName);
        return broadcastFrameWithQuorum(frame);
    }

    private boolean broadcastFrameWithQuorum(JettraRaftFrame frame) {
        int acks = 1;
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (ClusterNode peer : peers) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    JettraRaftFrame resp = sendFrameToPeer(peer, frame);
                    if (resp != null && resp.frameType() == JettraRaftFrame.TYPE_ACK) {
                        return true;
                    } else if (resp != null) {
                        System.err.printf("[broadcastFrameWithQuorum] Peer %s returned type 0x%02X: %s%n",
                            peer.getId(), resp.frameType(), resp.getPayloadAsString());
                    }
                } catch (Exception e) {
                    System.err.printf("[broadcastFrameWithQuorum] Error sending to %s: %s%n", peer.getId(), e.getMessage());
                    return false;
                }
                return false;
            }, executor));
        }
        for (var f : futures) {
            try {
                if (f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    acks++;
                }
            } catch (Exception ignored) {}
        }
        return acks >= 2 || peers.isEmpty();
    }

    /**
     * Solicita al nodo primario los datos serializados completos de una base de datos para sincronización.
     */
    public byte[] requestDataSync(String host, int port, String dbName) {
        long term = currentTerm.get();
        JettraRaftFrame req = JettraRaftFrame.syncDataReq(term, localNodeId, dbName);
        try (Socket s = new Socket()) {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            s.setSoTimeout(3000);
            try (var bos = new BufferedOutputStream(s.getOutputStream(), 65536);
                 var bis = new BufferedInputStream(s.getInputStream(), 65536)) {
                JettraClusterProtocol.writeFrame(bos, req);
                JettraRaftFrame resp = JettraClusterProtocol.readFrame(bis);
                if (resp != null && resp.frameType() == JettraRaftFrame.TYPE_SYNC_DATA_RESP) {
                    return resp.payload();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Solicita al nodo primario la lista de bases de datos para sincronización en caliente.
     */
    public List<String> requestCatalogSync(String host, int port) {
        long term = currentTerm.get();
        JettraRaftFrame req = JettraRaftFrame.syncCatalogReq(term, localNodeId);
        try (Socket s = new Socket()) {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);
            try (var bos = new BufferedOutputStream(s.getOutputStream());
                 var bis = new BufferedInputStream(s.getInputStream())) {
                JettraClusterProtocol.writeFrame(bos, req);
                JettraRaftFrame resp = JettraClusterProtocol.readFrame(bis);
                if (resp != null && resp.frameType() == JettraRaftFrame.TYPE_SYNC_CATALOG_RESP) {
                    String csv = resp.getPayloadAsString();
                    if (!csv.isBlank()) {
                        return List.of(csv.split(","));
                    }
                    return List.of();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Consulta el inventario de bases de datos de todos los nodos del clúster concurrentemente.
     */
    public List<ClusterNodeDistributionInfo> getClusterDistributionInfo(String localIp, int localPort, String localRole, List<String> localDatabases) {
        List<ClusterNodeDistributionInfo> result = new ArrayList<>();
        result.add(new ClusterNodeDistributionInfo(
            localNodeId, localIp, localPort, localRole, "RUNNING", localDatabases.size(), localDatabases
        ));

        List<CompletableFuture<ClusterNodeDistributionInfo>> futures = new ArrayList<>();
        for (ClusterNode peer : peers) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    List<String> dbs = requestCatalogSync(peer.getIp(), peer.getPort());
                    if (dbs != null) {
                        peer.start();
                        return new ClusterNodeDistributionInfo(
                            peer.getId(), peer.getIp(), peer.getPort(), peer.getRole().name(), "RUNNING", dbs.size(), dbs
                        );
                    }
                } catch (Exception ignored) {}
                peer.setStatus(ClusterNode.NodeStatus.OFFLINE);
                return new ClusterNodeDistributionInfo(
                    peer.getId(), peer.getIp(), peer.getPort(), peer.getRole().name(), "OFFLINE", 0, List.of()
                );
            }, executor));
        }

        for (var f : futures) {
            try {
                result.add(f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * Envía latidos periódicos a todos los peers para mantener vivo el quórum y validar salud.
     */
    public void sendHeartbeats() {
        long term = currentTerm.get();
        JettraRaftFrame frame = JettraRaftFrame.heartbeat(term, localNodeId);
        for (ClusterNode peer : peers) {
            executor.submit(() -> {
                try {
                    JettraRaftFrame resp = sendFrameToPeer(peer, frame);
                    if (resp != null && resp.frameType() == JettraRaftFrame.TYPE_HEARTBEAT_ACK) {
                        peer.start();
                    } else {
                        peer.markOffline();
                    }
                } catch (Exception e) {
                    peer.markOffline();
                }
            });
        }
    }

    public JettraRaftFrame sendFrameToPeer(ClusterNode peer, JettraRaftFrame frame) throws IOException {
        try (Socket s = new Socket()) {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(peer.getIp(), peer.getPort()), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);

            try (BufferedOutputStream bos = new BufferedOutputStream(s.getOutputStream(), 65536);
                 BufferedInputStream bis = new BufferedInputStream(s.getInputStream(), 65536)) {
                JettraClusterProtocol.writeFrame(bos, frame);
                return JettraClusterProtocol.readFrame(bis);
            }
        }
    }

    public long getCurrentTerm() { return currentTerm.get(); }
    public void incrementTerm() { currentTerm.incrementAndGet(); }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
