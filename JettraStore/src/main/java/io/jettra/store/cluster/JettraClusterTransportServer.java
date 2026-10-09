package io.jettra.store.cluster;

import io.jettra.store.JettraStoreServer;
import io.jettra.store.core.JettraDatabase;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Servidor de transporte de clúster de alto rendimiento basado en Java 25 Virtual Threads.
 * Escucha en el puerto inter-nodo (gRPC/TCP, default 9091) para procesar tramas de replicación
 * Raft y sincronización continua de datos entre nodos primarios y secundarios.
 */
public final class JettraClusterTransportServer implements AutoCloseable {

    private final int port;
    private final JettraStoreServer server;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService virtualThreadExecutor;

    public JettraClusterTransportServer(int port, JettraStoreServer server) {
        this.port = port;
        this.server = server;
    }

    public void start() throws IOException {
        if (running.compareAndSet(false, true)) {
            this.serverSocket = new ServerSocket(port);
            this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

            // Hilo virtual de despacho de conexiones
            Thread.ofVirtual().name("jettra-cluster-listener-" + port).start(() -> {
                System.out.printf("[JettraClusterTransport] Servidor TCP de Replicación Raft activo en 0.0.0.0:%d (Virtual Threads)%n", port);
                while (running.get() && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        clientSocket.setTcpNoDelay(true);
                        virtualThreadExecutor.submit(() -> handleConnection(clientSocket));
                    } catch (IOException e) {
                        if (!running.get()) break;
                        System.err.printf("[JettraClusterTransport] Error aceptando conexión en puerto %d: %s%n", port, e.getMessage());
                    }
                }
            });
        }
    }

    private void handleConnection(Socket socket) {
        try (socket;
             BufferedInputStream bis = new BufferedInputStream(socket.getInputStream(), 65536);
             BufferedOutputStream bos = new BufferedOutputStream(socket.getOutputStream(), 65536)) {

            while (running.get() && !socket.isClosed()) {
                JettraRaftFrame frame;
                try {
                    frame = JettraClusterProtocol.readFrame(bis);
                } catch (Exception e) {
                    break; // Fin de conexión o stream cerrado
                }

                JettraRaftFrame response = processFrame(frame);
                if (response != null) {
                    JettraClusterProtocol.writeFrame(bos, response);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private JettraRaftFrame processFrame(JettraRaftFrame frame) {
        switch (frame.frameType()) {
            case JettraRaftFrame.TYPE_HEARTBEAT -> {
                if (server != null) {
                    server.recordLeaderHeartbeat(frame.senderNodeId());
                    if (server.getRingEngine() != null) {
                        ClusterNode peer = server.getRingEngine().getPeer(frame.senderNodeId());
                        if (peer != null) {
                            peer.start();
                        }
                    }
                }
                return JettraRaftFrame.heartbeatAck(frame.term(), server != null ? server.getConfig().getNodeId() : "node-local");
            }

            case JettraRaftFrame.TYPE_CREATE_DATABASE -> {
                String dbName = frame.databaseName();
                byte[] payload = frame.payload();
                System.out.printf("[JettraClusterTransport] ↳ [REPLICATE] Recibida orden Raft de crear base de datos: '%s' (payload: %d bytes) desde nodo '%s'%n",
                    dbName, payload != null ? payload.length : 0, frame.senderNodeId());
                try {
                    if (server != null) {
                        Path storageDir = Path.of(server.getConfig().getStoragePath());
                        Files.createDirectories(storageDir);
                        Path dbDir = storageDir.resolve(dbName);
                        Files.createDirectories(dbDir);
                        Path memDir = dbDir.resolve("jettra_memory");
                        Files.createDirectories(memDir);

                        Path targetMeta = storageDir.resolve(dbName + "_meta.json");
                        if (payload != null && payload.length > 0) {
                            Files.write(targetMeta, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                        }
                        JettraDatabase db = server.getOrCreateDatabaseInternal(dbName, false);
                        if (db != null) {
                            if (Files.exists(targetMeta)) {
                                db.loadFromDisk(targetMeta);
                            } else {
                                db.loadFromDisk();
                            }
                            db.saveToDisk();
                        }
                    }
                    JettraClusterEventBus.getInstance().publish(
                        ClusterLiveEvent.TYPE_DATABASE_CREATED,
                        frame.senderNodeId(),
                        server != null ? server.getConfig().getNodeId() : "local",
                        String.format("Base de datos '%s' replicada exitosamente desde nodo '%s'", dbName, frame.senderNodeId()),
                        "db=" + dbName
                    );
                    JettraClusterEventBus.getInstance().publish(
                        ClusterLiveEvent.TYPE_DATA_TRANSFER,
                        frame.senderNodeId(),
                        server != null ? server.getConfig().getNodeId() : "local",
                        String.format("Transferencia de creación de base '%s' completada", dbName),
                        "db=" + dbName
                    );
                    return JettraRaftFrame.ack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", 
                        "Base de datos '" + dbName + "' replicada exitosamente");
                } catch (Exception ex) {
                    System.err.printf("[JettraClusterTransport] Error replicando base de datos '%s': %s%n", dbName, ex.getMessage());
                    return JettraRaftFrame.nack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", ex.getMessage());
                }
            }

            case JettraRaftFrame.TYPE_DISTRIBUTE_DATABASE -> {
                String dbName = frame.databaseName();
                byte[] payload = frame.payload();
                System.out.printf("[JettraClusterTransport] ↳ [DISTRIBUTE] Recibida orden Raft de migración integral de base de datos: '%s' (%d bytes) desde nodo '%s'%n",
                    dbName, payload != null ? payload.length : 0, frame.senderNodeId());
                try {
                    if (server != null) {
                        Path storageDir = Path.of(server.getConfig().getStoragePath());
                        Files.createDirectories(storageDir);
                        Path dbDir = storageDir.resolve(dbName);
                        Files.createDirectories(dbDir);
                        Path memDir = dbDir.resolve("jettra_memory");
                        Files.createDirectories(memDir);

                        Path targetMeta = storageDir.resolve(dbName + "_meta.json");
                        if (payload != null && payload.length > 0) {
                            Files.write(targetMeta, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                        }
                        JettraDatabase db = server.getOrCreateDatabaseInternal(dbName, false);
                        if (db != null) {
                            if (Files.exists(targetMeta)) {
                                db.loadFromDisk(targetMeta);
                            } else {
                                db.loadFromDisk();
                            }
                            db.saveToDisk();
                        }
                    }
                    JettraClusterEventBus.getInstance().publish(
                        ClusterLiveEvent.TYPE_DATABASE_DISTRIBUTED,
                        frame.senderNodeId(),
                        server != null ? server.getConfig().getNodeId() : "local",
                        String.format("Base de datos '%s' migrada integralmente desde '%s'", dbName, frame.senderNodeId()),
                        "db=" + dbName + ",bytes=" + (payload != null ? payload.length : 0)
                    );
                    JettraClusterEventBus.getInstance().publish(
                        ClusterLiveEvent.TYPE_DATA_TRANSFER,
                        frame.senderNodeId(),
                        server != null ? server.getConfig().getNodeId() : "local",
                        String.format("Transferencia integral de base '%s' (%d bytes) recibida", dbName, payload != null ? payload.length : 0),
                        "db=" + dbName
                    );
                    return JettraRaftFrame.ack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", 
                        "Base de datos '" + dbName + "' distribuida y sincronizada exitosamente con todos sus registros");
                } catch (Exception ex) {
                    System.err.printf("[JettraClusterTransport] Error distribuyendo base de datos '%s': %s%n", dbName, ex.getMessage());
                    return JettraRaftFrame.nack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", ex.getMessage());
                }
            }

            case JettraRaftFrame.TYPE_DROP_DATABASE -> {
                String dbName = frame.databaseName();
                System.out.printf("[JettraClusterTransport] ↳ [REPLICATE] Recibida orden Raft de eliminar base de datos: '%s' desde nodo '%s'%n",
                    dbName, frame.senderNodeId());
                try {
                    if (server != null) {
                        server.dropDatabaseInternal(dbName, false);
                    }
                    return JettraRaftFrame.ack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", 
                        "Base de datos '" + dbName + "' eliminada exitosamente");
                } catch (Exception ex) {
                    return JettraRaftFrame.nack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", ex.getMessage());
                }
            }

            case JettraRaftFrame.TYPE_CREATE_ENGINE -> {
                String dbName = frame.databaseName();
                String engineName = frame.collectionName();
                String engineType = frame.key();
                System.out.printf("[JettraClusterTransport] ↳ [ENGINE] Recibida orden Raft de crear motor: '%s' [%s] en base '%s' desde nodo '%s'%n",
                    engineName, engineType, dbName, frame.senderNodeId());
                try {
                    if (server != null) {
                        JettraDatabase db = server.getOrCreateDatabaseInternal(dbName, false);
                        if (db != null) {
                            db.ensureEngineInternal(engineType, engineName, frame.payload());
                            db.saveToDisk();
                        }
                    }
                    JettraClusterEventBus.getInstance().publish(
                        ClusterLiveEvent.TYPE_ENGINE_CREATED,
                        frame.senderNodeId(),
                        server != null ? server.getConfig().getNodeId() : "local",
                        String.format("Motor '%s' [%s] replicado y creado en base '%s'", engineName, engineType, dbName),
                        "db=" + dbName + ",engine=" + engineName + ",type=" + engineType
                    );
                    JettraClusterEventBus.getInstance().publish(
                        ClusterLiveEvent.TYPE_DATA_TRANSFER,
                        frame.senderNodeId(),
                        server != null ? server.getConfig().getNodeId() : "local",
                        String.format("Transferencia de creación de motor '%s' [%s] completada", engineName, engineType),
                        "db=" + dbName + ",engine=" + engineName
                    );
                    return JettraRaftFrame.ack(frame.term(), frame.logIndex(),
                        server != null ? server.getConfig().getNodeId() : "local",
                        "Motor '" + engineName + "' [" + engineType + "] en base '" + dbName + "' replicado exitosamente");
                } catch (Exception ex) {
                    System.err.printf("[JettraClusterTransport] Error replicando motor '%s' en base '%s': %s%n", engineName, dbName, ex.getMessage());
                    return JettraRaftFrame.nack(frame.term(), frame.logIndex(),
                        server != null ? server.getConfig().getNodeId() : "local", ex.getMessage());
                }
            }

            case JettraRaftFrame.TYPE_PUT_RECORD -> {
                String dbName = frame.databaseName();
                String colName = frame.collectionName();
                String key = frame.key();
                try {
                    if (server != null) {
                        JettraDatabase db = server.getOrCreateDatabaseInternal(dbName, false);
                        if (db != null) {
                            db.getKeyValueEngine(colName != null && !colName.isEmpty() ? colName : "default").applyReplicatedPut(key, frame.payload());
                            db.putOffHeapBinary(key, frame.payload());
                            db.saveToDisk();
                        }
                    }
                    JettraClusterEventBus.getInstance().publish(
                        ClusterLiveEvent.TYPE_RECORD_REPLICATED,
                        frame.senderNodeId(),
                        server != null ? server.getConfig().getNodeId() : "local",
                        String.format("Registro KV '%s' en '%s' de base '%s' replicado exitosamente", key, colName, dbName),
                        "db=" + dbName + ",ns=" + colName + ",key=" + key
                    );
                    JettraClusterEventBus.getInstance().publish(
                        ClusterLiveEvent.TYPE_DATA_TRANSFER,
                        frame.senderNodeId(),
                        server != null ? server.getConfig().getNodeId() : "local",
                        String.format("Transferencia de registro KV '%s' en '%s' de base '%s' completada", key, colName, dbName),
                        "db=" + dbName + ",ns=" + colName + ",key=" + key
                    );
                    return JettraRaftFrame.ack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", "Record replicated");
                } catch (Exception ex) {
                    return JettraRaftFrame.nack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", ex.getMessage());
                }
            }

            case JettraRaftFrame.TYPE_DELETE_RECORD -> {
                String dbName = frame.databaseName();
                String colName = frame.collectionName();
                String key = frame.key();
                try {
                    if (server != null) {
                        JettraDatabase db = server.getOrCreateDatabaseInternal(dbName, false);
                        if (db != null) {
                            db.getKeyValueEngine(colName != null && !colName.isEmpty() ? colName : "default").applyReplicatedRemove(key);
                            db.saveToDisk();
                        }
                    }
                    return JettraRaftFrame.ack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", "Record deleted");
                } catch (Exception ex) {
                    return JettraRaftFrame.nack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", ex.getMessage());
                }
            }

            case JettraRaftFrame.TYPE_PUT_DOCUMENT -> {
                String dbName = frame.databaseName();
                String colName = frame.collectionName();
                String id = frame.key();
                try {
                    if (server != null) {
                        JettraDatabase db = server.getOrCreateDatabaseInternal(dbName, false);
                        if (db != null) {
                            String jsonStr = frame.getPayloadAsString();
                            Map<String, Object> doc = new io.jettra.json.JettraJson().fromJson(jsonStr, Map.class);
                            db.getDocumentEngine(colName).applyReplicatedInsert(id, doc);
                            db.getIndexManager().onDocumentInsert(colName, id, doc);
                            db.saveToDisk();
                        }
                    }
                    JettraClusterEventBus.getInstance().publish(
                        ClusterLiveEvent.TYPE_DOCUMENT_REPLICATED,
                        frame.senderNodeId(),
                        server != null ? server.getConfig().getNodeId() : "local",
                        String.format("Documento '%s' en colección '%s' de base '%s' replicado exitosamente", id, colName, dbName),
                        "db=" + dbName + ",col=" + colName + ",id=" + id
                    );
                    JettraClusterEventBus.getInstance().publish(
                        ClusterLiveEvent.TYPE_DATA_TRANSFER,
                        frame.senderNodeId(),
                        server != null ? server.getConfig().getNodeId() : "local",
                        String.format("Transferencia de documento '%s' en '%s' de base '%s' completada", id, colName, dbName),
                        "db=" + dbName + ",col=" + colName + ",id=" + id
                    );
                    return JettraRaftFrame.ack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", "Document replicated");
                } catch (Exception ex) {
                    return JettraRaftFrame.nack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", ex.getMessage());
                }
            }

            case JettraRaftFrame.TYPE_DELETE_DOCUMENT -> {
                String dbName = frame.databaseName();
                String colName = frame.collectionName();
                String id = frame.key();
                try {
                    if (server != null) {
                        JettraDatabase db = server.getOrCreateDatabaseInternal(dbName, false);
                        if (db != null) {
                            db.getDocumentEngine(colName).applyReplicatedDelete(id);
                            db.getIndexManager().onDocumentDelete(colName, id, null);
                            db.saveToDisk();
                        }
                    }
                    return JettraRaftFrame.ack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", "Document deleted");
                } catch (Exception ex) {
                    return JettraRaftFrame.nack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", ex.getMessage());
                }
            }

            case JettraRaftFrame.TYPE_CREATE_INDEX -> {
                String dbName = frame.databaseName();
                String colName = frame.collectionName();
                String indexName = frame.key();
                String meta = frame.getPayloadAsString(); // field:type:unique
                try {
                    if (server != null) {
                        JettraDatabase db = server.getOrCreateDatabaseInternal(dbName, false);
                        if (db != null) {
                            String[] parts = meta.split(":", 3);
                            String field = parts.length > 0 ? parts[0] : indexName;
                            String type = parts.length > 1 ? parts[1] : "BTREE";
                            boolean unique = parts.length > 2 && Boolean.parseBoolean(parts[2]);
                            if (db.getIndexManager().getIndex(indexName) == null) {
                                db.getIndexManager().applyReplicatedIndex(colName, indexName, field, type, unique, db.getDocumentEngine(colName));
                            }
                            db.saveToDisk();
                        }
                    }
                    JettraClusterEventBus.getInstance().publish(
                        ClusterLiveEvent.TYPE_INDEX_CREATED,
                        frame.senderNodeId(),
                        server != null ? server.getConfig().getNodeId() : "local",
                        String.format("Índice '%s' en colección '%s' de base '%s' replicado exitosamente", indexName, colName, dbName),
                        "db=" + dbName + ",col=" + colName + ",index=" + indexName
                    );
                    return JettraRaftFrame.ack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", "Index created");
                } catch (Exception ex) {
                    return JettraRaftFrame.nack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", ex.getMessage());
                }
            }

            case JettraRaftFrame.TYPE_DROP_INDEX -> {
                String dbName = frame.databaseName();
                String indexName = frame.key();
                try {
                    if (server != null) {
                        JettraDatabase db = server.getOrCreateDatabaseInternal(dbName, false);
                        if (db != null) {
                            db.getIndexManager().applyReplicatedDropIndex(indexName);
                            db.saveToDisk();
                        }
                    }
                    JettraClusterEventBus.getInstance().publish(
                        ClusterLiveEvent.TYPE_INDEX_DROPPED,
                        frame.senderNodeId(),
                        server != null ? server.getConfig().getNodeId() : "local",
                        String.format("Índice '%s' en base '%s' eliminado por replicación", indexName, dbName),
                        "db=" + dbName + ",index=" + indexName
                    );
                    return JettraRaftFrame.ack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", "Index dropped");
                } catch (Exception ex) {
                    return JettraRaftFrame.nack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", ex.getMessage());
                }
            }

            case JettraRaftFrame.TYPE_SYNC_DATA_REQ -> {
                String dbName = frame.databaseName();
                try {
                    byte[] bytes = new byte[0];
                    if (server != null) {
                        bytes = server.getDatabaseSnapshotBytes(dbName);
                    }
                    if (bytes == null || bytes.length == 0) {
                        Path metaFile = JettraDatabase.resolveMetaFile(dbName, server != null ? server.getConfig() : null);
                        bytes = (metaFile != null && Files.exists(metaFile)) ? Files.readAllBytes(metaFile) : new byte[0];
                    }
                    return JettraRaftFrame.syncDataResp(frame.term(), 
                        server != null ? server.getConfig().getNodeId() : "local", dbName, bytes);
                } catch (Exception ex) {
                    return JettraRaftFrame.syncDataResp(frame.term(), 
                        server != null ? server.getConfig().getNodeId() : "local", dbName, new byte[0]);
                }
            }

            case JettraRaftFrame.TYPE_NODE_STOPPING -> {
                String sender = frame.senderNodeId();
                String msg = frame.getPayloadAsString();
                System.out.printf("[JettraClusterTransport] ⚠️ Nodo '%s' notifica parada controlada (%s)%n", sender, msg);
                if (server != null) {
                    server.handleNodeStopping(sender, msg);
                }
                return JettraRaftFrame.ack(frame.term(), frame.logIndex(), 
                    server != null ? server.getConfig().getNodeId() : "local", "Node stop acknowledged");
            }

            case JettraRaftFrame.TYPE_NEW_LEADER_PROMOTED -> {
                String newLeader = frame.senderNodeId();
                System.out.printf("[JettraClusterTransport] ⚡ Nodo '%s' ha sido promovido a nuevo PRIMARY (Term %d)%n",
                    newLeader, frame.term());
                if (server != null) {
                    server.handleNewLeaderPromoted(newLeader, frame.term());
                }
                return JettraRaftFrame.ack(frame.term(), frame.logIndex(), 
                    server != null ? server.getConfig().getNodeId() : "local", "Leader promotion accepted");
            }

            case JettraRaftFrame.TYPE_CLUSTER_LIVE_EVENT -> {
                String json = frame.getPayloadAsString();
                if (server != null) {
                    server.handleLiveEvent(json);
                }
                return JettraRaftFrame.ack(frame.term(), frame.logIndex(), 
                    server != null ? server.getConfig().getNodeId() : "local", "Live event received");
            }

            case JettraRaftFrame.TYPE_SYNC_CATALOG_REQ -> {
                List<String> dbs = server != null ? server.listDatabaseNames() : List.of();
                String csv = String.join(",", dbs);
                String role = server != null ? server.getConfig().getNodeRole().name() : "SECONDARY";
                String nodeId = server != null ? server.getConfig().getNodeId() : "local";
                return JettraRaftFrame.syncCatalogResp(frame.term(), nodeId, role, csv);
            }

            default -> {
                return JettraRaftFrame.ack(frame.term(), frame.logIndex(), 
                    server != null ? server.getConfig().getNodeId() : "local", "OK");
            }
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (Exception ignored) {}
            if (virtualThreadExecutor != null) {
                virtualThreadExecutor.shutdownNow();
            }
            System.out.printf("[JettraClusterTransport] Servidor TCP de replicación en puerto %d detenido.%n", port);
        }
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running.get();
    }
}
