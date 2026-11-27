package io.jettra.store.cluster;

import io.jettra.store.JettraStoreServer;
import io.jettra.store.core.JettraDatabase;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Servidor de transporte de clúster de alto rendimiento basado en Java 25 Virtual Threads.
 * Escucha en el puerto inter-nodo (gRPC/TCP, default 9091) para procesar tramas de replicación
 * Raft y sincronización continua de datos entre nodos primarios y secundarios.
 */
public final class JettraClusterTransportServer implements AutoCloseable {

    private static final Set<JettraClusterTransportServer> ACTIVE_TRANSPORTS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);

    private final int port;
    private final JettraStoreServer server;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<Socket> activeClientSockets = ConcurrentHashMap.newKeySet();
    private ServerSocket serverSocket;
    private ExecutorService virtualThreadExecutor;
    private volatile Thread listenerThread;

    public JettraClusterTransportServer(int port, JettraStoreServer server) {
        this.port = port;
        this.server = server;
    }

    private static void registerShutdownHook() {
        if (SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(
                JettraClusterTransportServer::stopAllActiveTransports,
                "jettra-transport-shutdown-hook"
            ));
        }
    }

    /**
     * Detiene todas las instancias activas de {@link JettraClusterTransportServer}
     * para garantizar la liberación inmediata de sus puertos TCP al finalizar pruebas o el proceso JVM.
     */
    public static int stopAllActiveTransports() {
        int stoppedCount = 0;
        for (JettraClusterTransportServer transport : List.copyOf(ACTIVE_TRANSPORTS)) {
            try {
                transport.stop();
                stoppedCount++;
            } catch (Exception ignored) {}
        }
        return stoppedCount;
    }

    public static int getActiveTransportCount() {
        return ACTIVE_TRANSPORTS.size();
    }

    public int getPort() {
        return port;
    }

    public void start() throws IOException {
        if (running.compareAndSet(false, true)) {
            registerShutdownHook();
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                this.serverSocket = ss;
                this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
                ACTIVE_TRANSPORTS.add(this);
            } catch (IOException | RuntimeException e) {
                running.set(false);
                ACTIVE_TRANSPORTS.remove(this);
                if (this.serverSocket != null) {
                    try { this.serverSocket.close(); } catch (Exception ignored) {}
                    this.serverSocket = null;
                }
                if (this.virtualThreadExecutor != null) {
                    this.virtualThreadExecutor.shutdownNow();
                    this.virtualThreadExecutor = null;
                }
                throw e;
            }

            // Hilo virtual de despacho de conexiones
            final ServerSocket boundSocket = this.serverSocket;
            this.listenerThread = Thread.ofVirtual().name("jettra-cluster-listener-" + port).start(() -> {
                System.out.printf("[JettraClusterTransport] Servidor TCP de Replicación Raft activo en 0.0.0.0:%d (Virtual Threads)%n", port);
                while (running.get() && boundSocket != null && !boundSocket.isClosed()) {
                    try {
                        Socket clientSocket = boundSocket.accept();
                        clientSocket.setTcpNoDelay(true);
                        ExecutorService exec = this.virtualThreadExecutor;
                        if (running.get() && exec != null && !exec.isShutdown()) {
                            exec.submit(() -> handleConnection(clientSocket));
                        } else {
                            clientSocket.close();
                        }
                    } catch (IOException e) {
                        if (!running.get()) break;
                        System.err.printf("[JettraClusterTransport] Error aceptando conexión en puerto %d: %s%n", port, e.getMessage());
                    }
                }
            });
        }
    }

    private void handleConnection(Socket socket) {
        activeClientSockets.add(socket);
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
        } finally {
            activeClientSockets.remove(socket);
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
                        boolean shouldBroadcast = ("jettra-explorer".equalsIgnoreCase(frame.senderNodeId())
                                || "jettra-client".equalsIgnoreCase(frame.senderNodeId()))
                            && server.getConfig().getNodeRole() == ClusterNode.Role.PRIMARY;
                        JettraDatabase db = server.getOrCreateDatabaseInternal(dbName, shouldBroadcast);
                        if (db != null) {
                            if (Files.exists(targetMeta) && payload != null && payload.length > 0) {
                                db.loadFromDisk(targetMeta);
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
                        boolean shouldBroadcast = ("jettra-explorer".equalsIgnoreCase(frame.senderNodeId())
                                || "jettra-client".equalsIgnoreCase(frame.senderNodeId()))
                            && server.getConfig().getNodeRole() == ClusterNode.Role.PRIMARY;
                        JettraDatabase db = server.getOrCreateDatabaseInternal(dbName, shouldBroadcast);
                        if (db != null) {
                            if (Files.exists(targetMeta) && payload != null && payload.length > 0) {
                                db.loadFromDisk(targetMeta);
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
                        boolean shouldBroadcast = ("jettra-explorer".equalsIgnoreCase(frame.senderNodeId())
                                || "jettra-client".equalsIgnoreCase(frame.senderNodeId()))
                            && server.getConfig().getNodeRole() == ClusterNode.Role.PRIMARY;
                        server.dropDatabaseInternal(dbName, shouldBroadcast);
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
                String engineName = frame.bucketName();
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
                        if ("jettra-explorer".equalsIgnoreCase(frame.senderNodeId())
                                && server.getConfig().getNodeRole() == ClusterNode.Role.PRIMARY) {
                            server.replicateCreateEngine(dbName, engineName, engineType, frame.payload());
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
                String colName = frame.bucketName();
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
                String colName = frame.bucketName();
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
                String colName = frame.bucketName();
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
                        if ("jettra-explorer".equalsIgnoreCase(frame.senderNodeId())
                                && server.getConfig().getNodeRole() == ClusterNode.Role.PRIMARY) {
                            server.replicatePutDocument(dbName, colName, id, frame.payload());
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
                String colName = frame.bucketName();
                String id = frame.key();
                try {
                    if (server != null) {
                        JettraDatabase db = server.getOrCreateDatabaseInternal(dbName, false);
                        if (db != null) {
                            db.getDocumentEngine(colName).applyReplicatedDelete(id);
                            db.getIndexManager().onDocumentDelete(colName, id, null);
                            db.saveToDisk();
                        }
                        if ("jettra-explorer".equalsIgnoreCase(frame.senderNodeId())
                                && server.getConfig().getNodeRole() == ClusterNode.Role.PRIMARY) {
                            server.replicateDeleteDocument(dbName, colName, id);
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
                String colName = frame.bucketName();
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
                    if (msg != null && msg.startsWith("LIFECYCLE_SHUTDOWN:")) {
                        String targetId = msg.substring("LIFECYCLE_SHUTDOWN:".length()).trim();
                        if (targetId.isEmpty() || targetId.equalsIgnoreCase(server.getConfig().getNodeId())) {
                            String selfId = server.getConfig().getNodeId();
                            Thread.startVirtualThread(() -> {
                                try {
                                    Thread.sleep(80);
                                    server.stop();
                                } catch (Exception ignored) {}
                            });
                            return JettraRaftFrame.ack(frame.term(), frame.logIndex(), selfId, "Lifecycle shutdown initiated");
                        }
                    }
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

            case JettraRaftFrame.TYPE_SYNC_CLUSTER_CONFIG -> {
                byte[] cfgBytes = frame.payload();
                if (cfgBytes != null && cfgBytes.length > 0) {
                    try {
                        io.jettra.store.core.JettraConfigValidator.ValidationResult integrity =
                            io.jettra.store.core.JettraConfigValidator.validateClusterConfigIntegrity(cfgBytes);
                        if (!integrity.isValid()) {
                            System.err.printf("[JettraClusterTransport] ❌ Rechazado TYPE_SYNC_CLUSTER_CONFIG inválido desde '%s': %s%n",
                                frame.senderNodeId(), integrity.getSummary());
                            return JettraRaftFrame.nack(frame.term(), frame.logIndex(),
                                server != null ? server.getConfig().getNodeId() : "local",
                                "INVALID_CLUSTER_CONFIG: " + integrity.getSummary());
                        }
                        Path target = server != null
                            ? server.resolveClusterStateFile()
                            : io.jettra.store.core.JettraConfigValidator.resolveClusterStateFile();
                        java.util.Properties syncedProps = new java.util.Properties();
                        try (var bais = new java.io.ByteArrayInputStream(cfgBytes)) {
                            syncedProps.load(bais);
                        }
                        List<io.jettra.store.core.JettraConfigValidator.ClusterNodeInfo> parsedNodes =
                            io.jettra.store.core.JettraConfigValidator.parseExplicitClusterNodes(syncedProps);
                        if (!parsedNodes.isEmpty()) {
                            boolean written = io.jettra.store.core.JettraConfigValidator.writeClusterConfigAtomically(target, cfgBytes);
                            if (written) {
                                System.out.printf("[JettraClusterTransport] ✅ Archivo cluster-state.config sincronizado atómicamente (%d bytes, %d nodos) en '%s' desde '%s'.%n",
                                    cfgBytes.length, parsedNodes.size(), target.toAbsolutePath().normalize(), frame.senderNodeId());
                            }
                            if (server != null) {
                                String localId = server.getConfig().getNodeId();
                                boolean wasStandby = server.getConfig().isStandbyMode();

                                // 1. Conciliar rol local con el cluster-state.config canónico para evitar split-brain
                                boolean otherPrimaryExists = parsedNodes.stream().anyMatch(n ->
                                    "PRIMARY".equalsIgnoreCase(n.role()) && !n.id().equalsIgnoreCase(localId));
                                boolean selfMarkedSecondary = parsedNodes.stream().anyMatch(n ->
                                    n.id().equalsIgnoreCase(localId) && !"PRIMARY".equalsIgnoreCase(n.role()));
                                if (otherPrimaryExists && selfMarkedSecondary
                                        && server.getConfig().getNodeRole() == ClusterNode.Role.PRIMARY) {
                                    server.getConfig().setNodeRole(ClusterNode.Role.SECONDARY);
                                    server.recordLeaderHeartbeat(frame.senderNodeId());
                                    System.out.printf("[JettraClusterTransport] 🔄 Rol local de '%s' conciliado a SECONDARY según cluster-state.config canónico.%n", localId);
                                }

                                List<ClusterNode> newlyAddedPeers = server.handleClusterStateSynchronized(parsedNodes, syncedProps);

                                // 2. Idempotencia estricta: si cluster-state.config ya era idéntico y no hay nuevos peers,
                                // evitar re-difusiones o tormentas de sincronización (Broadcast Storm).
                                if (!written && !wasStandby && newlyAddedPeers.isEmpty()) {
                                    return JettraRaftFrame.ack(frame.term(), frame.logIndex(),
                                        localId, "Cluster state already up to date");
                                }

                                JettraClusterEventBus.getInstance().publish(
                                    ClusterLiveEvent.TYPE_DATA_TRANSFER,
                                    frame.senderNodeId(),
                                    localId,
                                    String.format("Archivo cluster-state.config actualizado (%d nodos, %d bytes)", parsedNodes.size(), cfgBytes.length),
                                    "cluster-state.config"
                                );

                                // 3. Evitar bucles ping-pong entre nodos del clúster:
                                // el PRIMARY solo re-difunde cuando la actualización proviene de un cliente externo
                                // (Driver/Explorer/Shell), nunca cuando proviene de otro nodo miembro del clúster.
                                String senderId = frame.senderNodeId() != null ? frame.senderNodeId().trim() : "";
                                boolean senderIsClusterPeer = parsedNodes.stream().anyMatch(n -> n.id().equalsIgnoreCase(senderId));

                                if (!server.getConfig().isStandbyMode()
                                        && server.getConfig().getNodeRole() == ClusterNode.Role.PRIMARY
                                        && !senderIsClusterPeer
                                        && !"cluster-master".equalsIgnoreCase(senderId)
                                        && !localId.equalsIgnoreCase(senderId)) {
                                    JettraClusterReplicationClient replClient = server.getOrCreateReplicationClient();
                                    if (replClient != null) {
                                        replClient.broadcastClusterConfigSync(cfgBytes);
                                    }
                                    if (!newlyAddedPeers.isEmpty()) {
                                        final List<ClusterNode> toSync = new java.util.ArrayList<>(newlyAddedPeers);
                                        Thread.ofVirtual().name("jettra-new-node-full-sync").start(() -> {
                                            for (ClusterNode newPeer : toSync) {
                                                server.distributeAllDatabasesToPeer(newPeer);
                                            }
                                        });
                                    }
                                } else if (!server.getConfig().isStandbyMode()
                                        && server.getConfig().getNodeRole() == ClusterNode.Role.SECONDARY) {
                                    // Sincronizar de forma coalescida (sin apilar hilos) solo si cambió el estado
                                    server.triggerAsyncSynchronizeFromPrimary();
                                }
                            }
                        }
                    } catch (Exception ex) {
                        System.err.printf("[JettraClusterTransport] Error sincronizando cluster.config: %s%n", ex.getMessage());
                    }
                }
                return JettraRaftFrame.ack(frame.term(), frame.logIndex(),
                    server != null ? server.getConfig().getNodeId() : "local", "CLUSTER_CONFIG_SYNCED");
            }

            case JettraRaftFrame.TYPE_JOIN_CLUSTER_REQ -> {
                if (server == null || server.getConfig().getNodeRole() != ClusterNode.Role.PRIMARY) {
                    return JettraRaftFrame.nack(frame.term(), frame.logIndex(),
                        server != null ? server.getConfig().getNodeId() : "local", "NOT_PRIMARY");
                }
                try {
                    java.util.Properties joinProps = new java.util.Properties();
                    if (frame.payload() != null && frame.payload().length > 0) {
                        try (var bais = new java.io.ByteArrayInputStream(frame.payload())) {
                            joinProps.load(bais);
                        }
                    }
                    String jId = joinProps.getProperty("id", frame.senderNodeId());
                    String jRole = joinProps.getProperty("role", "SECONDARY");
                    String jIp = joinProps.getProperty("ip", "127.0.0.1");
                    int jGrpc = Integer.parseInt(joinProps.getProperty("grpc.port", "9091"));
                    int jRest = Integer.parseInt(joinProps.getProperty("rest.port", "8082"));

                    byte[] canonicalCfg = server.handleJoinClusterRequest(jId, jRole, jIp, jGrpc, jRest);
                    if (canonicalCfg != null && canonicalCfg.length > 0) {
                        return JettraRaftFrame.joinClusterResp(frame.term(), server.getConfig().getNodeId(), canonicalCfg);
                    }
                    return JettraRaftFrame.nack(frame.term(), frame.logIndex(), server.getConfig().getNodeId(), "JOIN_FAILED");
                } catch (Exception ex) {
                    return JettraRaftFrame.nack(frame.term(), frame.logIndex(), server.getConfig().getNodeId(), ex.getMessage());
                }
            }

            case JettraRaftFrame.TYPE_GET_CLUSTER_CONFIG_REQ -> {
                String nodeId = server != null ? server.getConfig().getNodeId() : "local";
                try {
                    byte[] bytes = null;
                    if (server != null) {
                        bytes = server.readLiveClusterStateBytes();
                    } else {
                        Path stateTarget = io.jettra.store.core.JettraConfigValidator.resolveClusterStateFile();
                        if (stateTarget != null && java.nio.file.Files.exists(stateTarget) && java.nio.file.Files.size(stateTarget) > 0) {
                            bytes = java.nio.file.Files.readAllBytes(stateTarget);
                        } else {
                            Path seed = io.jettra.store.core.JettraConfigValidator.locateOptionalClusterSeedFile();
                            if (seed != null && java.nio.file.Files.exists(seed)) {
                                bytes = java.nio.file.Files.readAllBytes(seed);
                            }
                        }
                    }
                    return JettraRaftFrame.getClusterConfigResp(frame.term(), nodeId, bytes != null ? bytes : new byte[0]);
                } catch (Exception ex) {
                    return JettraRaftFrame.nack(frame.term(), frame.logIndex(), nodeId, "GET_CLUSTER_CONFIG_ERROR: " + ex.getMessage());
                }
            }

            default -> {
                return JettraRaftFrame.ack(frame.term(), frame.logIndex(), 
                    server != null ? server.getConfig().getNodeId() : "local", "OK");
            }
        }
    }

    public void stop() {
        ACTIVE_TRANSPORTS.remove(this);
        if (running.compareAndSet(true, false)) {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (Exception ignored) {}
            serverSocket = null;
            for (Socket client : activeClientSockets) {
                try {
                    client.close();
                } catch (Exception ignored) {}
            }
            activeClientSockets.clear();
            if (listenerThread != null) {
                listenerThread.interrupt();
                try {
                    listenerThread.join(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                listenerThread = null;
            }
            if (virtualThreadExecutor != null) {
                virtualThreadExecutor.shutdownNow();
                try {
                    virtualThreadExecutor.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                virtualThreadExecutor = null;
            }
            System.out.printf("[JettraClusterTransport] Servidor TCP de Replicación Raft en puerto %d detenido.%n", port);
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
