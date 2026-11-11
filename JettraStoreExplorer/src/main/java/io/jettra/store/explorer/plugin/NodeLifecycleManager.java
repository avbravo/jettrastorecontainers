package io.jettra.store.explorer.plugin;

import io.jettra.store.cluster.JettraClusterProtocol;
import io.jettra.store.cluster.JettraRaftFrame;
import io.jettra.store.explorer.model.ServerNodeInfo;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gestor del ciclo de vida de nodos JettraStore compatible con Java 25 ({@link ProcessHandle},
 * Virtual Threads) y el protocolo de transporte Raft/REST de JettraStore.
 */
public final class NodeLifecycleManager {

    public record ShutdownOutcome(
        boolean success,
        boolean offlineVerified,
        String mechanism,
        String message,
        long elapsedMs
    ) {}

    public record StartOutcome(
        boolean success,
        boolean onlineVerified,
        String mechanism,
        String message
    ) {}

    private final Set<String> stoppedNodes = ConcurrentHashMap.newKeySet();
    private final Map<String, AutoCloseable> managedEndpoints = new ConcurrentHashMap<>();
    private final Map<String, ProcessHandle> managedProcesses = new ConcurrentHashMap<>();
    private final Map<String, Integer> restPortHints = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(600))
        .build();

    public boolean isStopped(String nodeId) {
        return nodeId != null && stoppedNodes.contains(nodeId.trim().toLowerCase());
    }

    public void markStopped(String nodeId) {
        if (nodeId != null && !nodeId.isBlank()) {
            stoppedNodes.add(nodeId.trim().toLowerCase());
        }
    }

    public void clearStopped(String nodeId) {
        if (nodeId != null && !nodeId.isBlank()) {
            stoppedNodes.remove(nodeId.trim().toLowerCase());
        }
    }

    public void registerManagedEndpoint(String nodeId, AutoCloseable resource) {
        if (nodeId != null && resource != null) {
            managedEndpoints.put(nodeId.trim().toLowerCase(), resource);
            clearStopped(nodeId);
        }
    }

    public void registerRestPortHint(String nodeId, int restPort) {
        if (nodeId != null && restPort > 0) {
            restPortHints.put(nodeId.trim().toLowerCase(), restPort);
        }
    }

    public void registerProcess(String nodeId, ProcessHandle handle) {
        if (nodeId != null && handle != null) {
            managedProcesses.put(nodeId.trim().toLowerCase(), handle);
            clearStopped(nodeId);
        }
    }

    /**
     * Ejecuta el apagado real del nodo objetivo enviando señales a:
     * 1) Recurso/endpoint administrado localmente (si existe)
     * 2) Trama Raft {@code TYPE_NODE_STOPPING} con payload {@code LIFECYCLE_SHUTDOWN:<nodeId>}
     * 3) Endpoint REST {@code POST /api/v1/cluster/shutdown}
     * 4) {@link ProcessHandle} del proceso JVM del nodo objetivo (Java 25), excluyendo siempre el proceso actual.
     */
    public ShutdownOutcome shutdownNode(ServerNodeInfo node) {
        if (node == null) {
            return new ShutdownOutcome(false, false, "NONE", "Nodo nulo o inexistente", 0);
        }
        long startNs = System.nanoTime();
        String nodeId = node.getId().trim();
        String key = nodeId.toLowerCase();
        String host = node.getHost() != null ? node.getHost().trim() : "127.0.0.1";
        int port = node.getPort();
        List<String> mechanisms = new ArrayList<>();

        // 1. Cerrar endpoint administrado si existe (e.g. instancia embebida en el mismo JVM o nodo gestionado)
        AutoCloseable endpoint = managedEndpoints.remove(key);
        if (endpoint != null) {
            try {
                endpoint.close();
                mechanisms.add("MANAGED_ENDPOINT_CLOSE");
            } catch (Exception ignored) {}
        }

        // 2. Enviar señal REST /api/v1/cluster/shutdown si responde un servicio HTTP JettraStore
        if (tryRestShutdown(node, host, port)) {
            mechanisms.add("REST_SHUTDOWN");
        }

        // 3. Enviar trama Raft TYPE_NODE_STOPPING al puerto TCP/Cluster del nodo
        if (port > 0 && tryRaftShutdown(nodeId, host, port)) {
            mechanisms.add("RAFT_NODE_STOPPING");
        }

        // 4. Localizar y detener proceso JVM externo del nodo mediante Java 25 ProcessHandle
        if (tryProcessHandleTerminate(nodeId, host, port)) {
            mechanisms.add("PROCESS_HANDLE_TERMINATE");
        }

        // 5. Esperar brevemente confirmación de cierre del puerto TCP
        boolean portClosed = waitForPortClosed(host, port, 700);
        markStopped(nodeId);

        node.setOnline(false);
        node.setLatencyMs(0.0f);
        node.setLastError("Nodo detenido mediante LifecycleManager (OFFLINE)");
        node.setLastCheckedEpochMs(System.currentTimeMillis());

        long elapsedMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
        String mechSummary = mechanisms.isEmpty() ? "LIFECYCLE_MARK_STOPPED" : String.join("+", mechanisms);
        String msg = portClosed
            ? "Nodo " + nodeId + " detenido y desconectado de la red (" + mechSummary + ")"
            : "Orden de apagado enviada a " + nodeId + " (" + mechSummary + "); estado forzado a OFFLINE";

        return new ShutdownOutcome(true, portClosed, mechSummary, msg, elapsedMs);
    }

    /**
     * Limpia el bloqueo lógico de parada y sondea nuevamente si el nodo volvió a estar disponible.
     */
    public StartOutcome startNode(ServerNodeInfo node, JettraStorePlugin plugin) {
        if (node == null) {
            return new StartOutcome(false, false, "NONE", "Nodo nulo o inexistente");
        }
        String nodeId = node.getId().trim();
        clearStopped(nodeId);

        boolean reachable = plugin != null && plugin.checkNodeRealReachability(node, 800);
        if (!reachable && (node.getLastError() == null || node.getLastError().contains("LifecycleManager"))) {
            // Si el nodo es puramente simulado (sin daemon real levantado externamente)
            node.setOnline(false);
            return new StartOutcome(true, false, "HEALTHCHECK_PROBE",
                "Nodo " + nodeId + " habilitado en el clúster; esperando conexión de servicio en " + node.getHost() + ":" + node.getPort());
        }
        return new StartOutcome(true, reachable, "HEALTHCHECK_PROBE",
            reachable
                ? "Nodo " + nodeId + " reconectado y ONLINE (" + String.format("%.2f ms", node.getLatencyMs()) + ")"
                : "Nodo " + nodeId + " habilitado pero inaccesible (" + node.getLastError() + ")");
    }

    private boolean tryRaftShutdown(String nodeId, String host, int port) {
        if (host.isBlank() || port <= 0 || port > 65535) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            socket.setSoTimeout(500);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            // Enviar payload exacto esperado por JettraClusterTransportServer: LIFECYCLE_SHUTDOWN:<nodeId>
            JettraRaftFrame directFrame = new JettraRaftFrame(
                JettraRaftFrame.TYPE_NODE_STOPPING,
                1L,
                0L,
                "jettra-explorer-lifecycle",
                "",
                "",
                nodeId,
                ("LIFECYCLE_SHUTDOWN:" + nodeId).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                System.currentTimeMillis()
            );
            JettraClusterProtocol.writeFrame(out, directFrame);
            out.flush();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean tryRestShutdown(ServerNodeInfo node, String host, int nodePort) {
        if (host.isBlank() || nodePort <= 0) return false;
        List<Integer> candidatePorts = new ArrayList<>();
        Integer hint = restPortHints.get(node.getId().trim().toLowerCase());
        if (hint != null && hint > 0) candidatePorts.add(hint);
        candidatePorts.add(nodePort);
        if (nodePort == 9091) candidatePorts.add(8080);

        for (int candidate : candidatePorts) {
            if (candidate <= 0 || candidate > 65535) continue;
            // Evitar enviarse shutdown al propio servidor web de JettraStoreExplorer
            if (candidate == JettraStorePlugin.getInstance().getServerPort() && isLocalHost(host)) {
                continue;
            }
            if (!isTcpPortOpen(host, candidate, 250)) continue;
            try {
                String baseUrl = "http://" + host + ":" + candidate;
                String token = acquireJwtToken(baseUrl);
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/cluster/shutdown"))
                    .timeout(Duration.ofMillis(700))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"nodeId\":\"" + node.getId() + "\"}"));
                if (token != null && !token.isBlank()) {
                    builder.header("Authorization", "Bearer " + token);
                }
                HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private String acquireJwtToken(String baseUrl) {
        try {
            HttpRequest authReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/auth/login"))
                .timeout(Duration.ofMillis(500))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
            HttpResponse<String> authResp = httpClient.send(authReq, HttpResponse.BodyHandlers.ofString());
            if (authResp.statusCode() == 200 && authResp.body() != null) {
                Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(authResp.body());
                if (m.find()) {
                    return m.group(1);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean tryProcessHandleTerminate(String nodeId, String host, int port) {
        if (!isLocalHost(host)) return false;
        long selfPid = ProcessHandle.current().pid();
        String key = nodeId.toLowerCase();

        ProcessHandle registered = managedProcesses.remove(key);
        if (registered != null && registered.pid() != selfPid && registered.isAlive()) {
            return terminateHandle(registered);
        }

        // Búsqueda segura mediante Java 25 ProcessHandle.allProcesses(): solo procesos JettraStore específicos
        try {
            Optional<ProcessHandle> match = ProcessHandle.allProcesses()
                .filter(ph -> ph.pid() != selfPid && ph.isAlive())
                .filter(ph -> matchesTargetJettraStoreProcess(ph, nodeId, port))
                .findFirst();
            if (match.isPresent()) {
                return terminateHandle(match.get());
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean matchesTargetJettraStoreProcess(ProcessHandle ph, String nodeId, int port) {
        String cmdLine = ph.info().commandLine().orElse("");
        if (cmdLine.isBlank()) return false;
        String lower = cmdLine.toLowerCase();
        // Nunca terminar JettraStoreExplorer ni procesos no pertenecientes a JettraStore
        if (lower.contains("jettrastoreexplorer") || lower.contains("surefire")) return false;
        boolean isJettraStore = lower.contains("jettrastoreserver") || lower.contains("jettrastore-");
        if (!isJettraStore) return false;
        return lower.contains(nodeId.toLowerCase()) || (port > 0 && lower.contains(String.valueOf(port)));
    }

    private boolean terminateHandle(ProcessHandle ph) {
        try {
            ph.destroy();
            try {
                ph.onExit().get(450, TimeUnit.MILLISECONDS);
                return !ph.isAlive();
            } catch (Exception timeout) {
                ph.destroyForcibly();
                try {
                    ph.onExit().get(450, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {}
                return !ph.isAlive();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean waitForPortClosed(String host, int port, int maxWaitMs) {
        if (host.isBlank() || port <= 0 || port > 65535) return true;
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isTcpPortOpen(host, port, 120)) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return !isTcpPortOpen(host, port, 120);
    }

    private boolean isTcpPortOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isLocalHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase();
        return "127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h) || "0.0.0.0".equals(h);
    }
}
