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
                // Registrar actividad del peer en el anillo
                if (server != null && server.getRingEngine() != null) {
                    ClusterNode peer = server.getRingEngine().getPeer(frame.senderNodeId());
                    if (peer != null) {
                        peer.start();
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
                        if (payload != null && payload.length > 0) {
                            Path targetMeta = Path.of(server.getConfig().getStoragePath(), dbName + "_meta.json");
                            if (targetMeta.getParent() != null) {
                                Files.createDirectories(targetMeta.getParent());
                            }
                            Files.write(targetMeta, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                        }
                        JettraDatabase db = server.getOrCreateDatabaseInternal(dbName, false);
                        if (db != null) {
                            db.loadFromDisk();
                        }
                    }
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
                        if (payload != null && payload.length > 0) {
                            Path targetMeta = Path.of(server.getConfig().getStoragePath(), dbName + "_meta.json");
                            if (targetMeta.getParent() != null) {
                                Files.createDirectories(targetMeta.getParent());
                            }
                            Files.write(targetMeta, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                        }
                        JettraDatabase db = server.getOrCreateDatabaseInternal(dbName, false);
                        if (db != null) {
                            db.loadFromDisk();
                        }
                    }
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

            case JettraRaftFrame.TYPE_PUT_RECORD -> {
                String dbName = frame.databaseName();
                String key = frame.key();
                try {
                    if (server != null) {
                        JettraDatabase db = server.getOrCreateDatabase(dbName);
                        if (db != null) {
                            db.putOffHeapBinary(key, frame.payload());
                            db.saveToDisk();
                        }
                    }
                    return JettraRaftFrame.ack(frame.term(), frame.logIndex(), 
                        server != null ? server.getConfig().getNodeId() : "local", "Record replicated");
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
                        }
                    }
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
                        }
                    }
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
                    if (server != null) {
                        JettraDatabase db = server.getOrCreateDatabaseInternal(dbName, false);
                        if (db != null) {
                            db.saveToDisk();
                        }
                    }
                    Path metaFile = JettraDatabase.resolveMetaFile(dbName, server != null ? server.getConfig() : null);
                    byte[] bytes = (metaFile != null && Files.exists(metaFile)) ? Files.readAllBytes(metaFile) : new byte[0];
                    return JettraRaftFrame.syncDataResp(frame.term(), 
                        server != null ? server.getConfig().getNodeId() : "local", dbName, bytes);
                } catch (Exception ex) {
                    return JettraRaftFrame.syncDataResp(frame.term(), 
                        server != null ? server.getConfig().getNodeId() : "local", dbName, new byte[0]);
                }
            }

            case JettraRaftFrame.TYPE_SYNC_CATALOG_REQ -> {
                List<String> dbs = server != null ? server.listDatabaseNames() : List.of();
                String csv = String.join(",", dbs);
                return JettraRaftFrame.syncCatalogResp(frame.term(), 
                    server != null ? server.getConfig().getNodeId() : "local", csv);
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
