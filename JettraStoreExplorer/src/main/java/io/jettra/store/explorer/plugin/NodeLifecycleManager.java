package io.jettra.store.explorer.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jettra.store.cluster.JettraClusterProtocol;
import io.jettra.store.cluster.JettraRaftFrame;
import io.jettra.store.explorer.model.ServerNodeInfo;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gestor del ciclo de vida de nodos JettraStore compatible con Java 25 ({@link ProcessHandle},
 * {@link UnixDomainSocketAddress}, Virtual Threads), contenedores Docker / Docker Compose
 * y el protocolo de transporte Raft/REST de JettraStore.
 */
public final class NodeLifecycleManager {

    public record ShutdownOutcome(
        boolean success,
        boolean offlineVerified,
        String mechanism,
        String message,
        long elapsedMs,
        String executionEnvironment,
        String dockerContainer
    ) {
        public ShutdownOutcome(boolean success, boolean offlineVerified, String mechanism, String message, long elapsedMs) {
            this(success, offlineVerified, mechanism, message, elapsedMs, "NATIVE", "");
        }
    }

    public record StartOutcome(
        boolean success,
        boolean onlineVerified,
        String mechanism,
        String message
    ) {}

    /**
     * Abstracción desacoplada para detectar entornos Docker / Docker Compose
     * y ejecutar parada/inicio limpio de contenedores de nodos JettraStore.
     */
    public interface DockerContainerControl {
        boolean isDockerEnvironment();
        Optional<String> findContainer(ServerNodeInfo node);
        boolean stopContainer(String containerNameOrId, int timeoutSeconds);
        boolean startContainer(String containerNameOrId);
    }

    private final Set<String> stoppedNodes = ConcurrentHashMap.newKeySet();
    private final Map<String, AutoCloseable> managedEndpoints = new ConcurrentHashMap<>();
    private final Map<String, ProcessHandle> managedProcesses = new ConcurrentHashMap<>();
    private final Map<String, Integer> restPortHints = new ConcurrentHashMap<>();
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private volatile DockerContainerControl dockerController = new DefaultDockerContainerControl(jsonMapper);

    public void setDockerController(DockerContainerControl controller) {
        this.dockerController = controller != null ? controller : new DefaultDockerContainerControl(jsonMapper);
    }

    public DockerContainerControl getDockerController() {
        return dockerController;
    }

    public String detectExecutionEnvironment() {
        return dockerController.isDockerEnvironment() ? "DOCKER" : "NATIVE";
    }

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
     * Ejecuta el apagado real del nodo objetivo aplicando estrategia dual:
     * - Entorno Docker / Docker Compose: flush Raft/REST y detención limpia del contenedor ({@code docker.sock} / CLI {@code docker stop})
     * - Entorno Nativo / Bare-metal:
     *   1) Recurso/endpoint administrado localmente (si existe)
     *   2) Endpoint REST {@code POST /api/v1/cluster/shutdown}
     *   3) Trama Raft {@code TYPE_NODE_STOPPING} con payload {@code LIFECYCLE_SHUTDOWN:<nodeId>}
     *   4) {@link ProcessHandle} del proceso JVM del nodo objetivo (Java 25), excluyendo siempre el proceso actual.
     */
    public ShutdownOutcome shutdownNode(ServerNodeInfo node) {
        if (node == null) {
            return new ShutdownOutcome(false, false, "NONE", "Nodo nulo o inexistente", 0, detectExecutionEnvironment(), "");
        }
        long startNs = System.nanoTime();
        String nodeId = node.getId().trim();
        String key = nodeId.toLowerCase();
        String host = node.getHost() != null ? node.getHost().trim() : "127.0.0.1";
        int port = node.getPort();
        List<String> mechanisms = new ArrayList<>();

        boolean dockerEnv = dockerController.isDockerEnvironment();
        String envLabel = dockerEnv ? "DOCKER" : "NATIVE";
        Optional<String> containerOpt = dockerEnv ? dockerController.findContainer(node) : Optional.empty();
        String containerName = containerOpt.orElse("");

        // 1. Cerrar endpoint administrado si existe (e.g. instancia embebida en el mismo JVM o nodo gestionado)
        AutoCloseable endpoint = managedEndpoints.remove(key);
        if (endpoint != null) {
            try {
                endpoint.close();
                mechanisms.add("MANAGED_ENDPOINT_CLOSE");
            } catch (Exception ignored) {}
        }

        // 2. Volcado limpio previo vía REST /api/v1/cluster/shutdown si responde un servicio HTTP JettraStore
        if (tryRestShutdown(node, host, port)) {
            mechanisms.add("REST_SHUTDOWN");
        }

        // 3. Enviar trama Raft TYPE_NODE_STOPPING al puerto TCP/Cluster del nodo (salvo si vamos a parar el contenedor directo sin bloquear)
        if (port > 0 && tryRaftShutdown(nodeId, host, port)) {
            mechanisms.add("RAFT_NODE_STOPPING");
        }

        // 4. Estrategia dual Docker Compose vs Proceso nativo JVM
        boolean dockerStopped = false;
        if (!containerName.isEmpty()) {
            if (dockerController.stopContainer(containerName, 8)) {
                dockerStopped = true;
                mechanisms.add("DOCKER_STOP[" + containerName + "]");
            }
        }
        if (!dockerStopped) {
            if (tryProcessHandleTerminate(nodeId, host, port)) {
                mechanisms.add("PROCESS_HANDLE_TERMINATE");
            }
        }

        // 5. Esperar brevemente confirmación de cierre del puerto TCP
        boolean portClosed = waitForPortClosed(host, port, dockerStopped ? 1200 : 700);
        markStopped(nodeId);

        node.setOnline(false);
        node.setLatencyMs(0.0f);
        node.setRaftState("DISCONNECTED");
        node.setLastError(!containerName.isEmpty()
            ? "Contenedor Docker '" + containerName + "' detenido mediante LifecycleManager (OFFLINE)"
            : "Nodo detenido mediante LifecycleManager (OFFLINE)");
        node.setLastCheckedEpochMs(System.currentTimeMillis());

        long elapsedMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
        String mechSummary = mechanisms.isEmpty() ? "LIFECYCLE_MARK_STOPPED" : String.join("+", mechanisms);
        String msg = portClosed
            ? (!containerName.isEmpty()
                ? "Contenedor " + containerName + " (" + nodeId + ") detenido limpiamente (" + mechSummary + ")"
                : "Nodo " + nodeId + " detenido y desconectado de la red (" + mechSummary + ")")
            : "Orden de apagado enviada a " + nodeId + " (" + mechSummary + "); estado forzado a OFFLINE";

        return new ShutdownOutcome(true, portClosed, mechSummary, msg, elapsedMs, envLabel, containerName);
    }

    /**
     * Limpia el bloqueo lógico de parada, reactiva el contenedor Docker si aplica
     * y sondea nuevamente si el nodo volvió a estar disponible.
     */
    public StartOutcome startNode(ServerNodeInfo node, JettraStorePlugin plugin) {
        if (node == null) {
            return new StartOutcome(false, false, "NONE", "Nodo nulo o inexistente");
        }
        String nodeId = node.getId().trim();
        clearStopped(nodeId);

        String mechanism = "HEALTHCHECK_PROBE";
        if (dockerController.isDockerEnvironment()) {
            Optional<String> containerOpt = dockerController.findContainer(node);
            if (containerOpt.isPresent()) {
                String containerName = containerOpt.get();
                if (!isTcpPortOpen(node.getHost(), node.getPort(), 180) && dockerController.startContainer(containerName)) {
                    mechanism = "DOCKER_START[" + containerName + "]";
                    long deadline = System.currentTimeMillis() + 1500;
                    while (System.currentTimeMillis() < deadline && !isTcpPortOpen(node.getHost(), node.getPort(), 150)) {
                        try { Thread.sleep(100); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        boolean reachable = plugin != null && plugin.checkNodeRealReachability(node, 800);
        if (!reachable && (node.getLastError() == null || node.getLastError().contains("LifecycleManager"))) {
            // Si el nodo es puramente simulado (sin daemon real levantado externamente)
            node.setOnline(false);
            return new StartOutcome(true, false, mechanism,
                "Nodo " + nodeId + " habilitado en el clúster; esperando conexión de servicio en " + node.getHost() + ":" + node.getPort());
        }
        return new StartOutcome(true, reachable, mechanism,
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
        if (node.getRestPort() > 0) candidatePorts.add(node.getRestPort());
        Integer hint = restPortHints.get(node.getId().trim().toLowerCase());
        if (hint != null && hint > 0 && !candidatePorts.contains(hint)) candidatePorts.add(hint);
        if (nodePort >= 9091 && nodePort <= 9099) {
            int mapped = nodePort - 1010; // 9091->8081, 9092->8082, 9093->8083
            if (!candidatePorts.contains(mapped)) candidatePorts.add(mapped);
        }
        if (host.toLowerCase(Locale.ROOT).startsWith("jettra-") && !candidatePorts.contains(8080)) {
            candidatePorts.add(8080);
        }
        if (!candidatePorts.contains(nodePort)) candidatePorts.add(nodePort);
        if (nodePort == 9091 && !candidatePorts.contains(8080)) candidatePorts.add(8080);

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

    /**
     * Implementación por defecto para entornos Docker / Docker Compose usando
     * el socket UNIX de Docker Engine (/var/run/docker.sock) con Java 25 {@link UnixDomainSocketAddress}
     * y respaldo por CLI {@code docker} si está disponible.
     */
    static final class DefaultDockerContainerControl implements DockerContainerControl {
        private static final Path DOCKER_ENV_FILE = Path.of("/.dockerenv");
        private static final Path DOCKER_SOCK_FILE = Path.of("/var/run/docker.sock");
        private static final Path PROC_CGROUP_FILE = Path.of("/proc/1/cgroup");

        private final ObjectMapper mapper;

        DefaultDockerContainerControl(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public boolean isDockerEnvironment() {
            if ("true".equalsIgnoreCase(System.getenv("JETTRA_DOCKER_COMPOSE"))) {
                return true;
            }
            if (Files.exists(DOCKER_ENV_FILE) || Files.exists(DOCKER_SOCK_FILE)) {
                return true;
            }
            try {
                if (Files.isReadable(PROC_CGROUP_FILE)) {
                    String cgroup = Files.readString(PROC_CGROUP_FILE);
                    String lower = cgroup.toLowerCase(Locale.ROOT);
                    return lower.contains("docker") || lower.contains("containerd") || lower.contains("kubepods");
                }
            } catch (Exception ignored) {}
            return false;
        }

        @Override
        public Optional<String> findContainer(ServerNodeInfo node) {
            if (node == null) return Optional.empty();
            Optional<String> viaSock = findContainerViaSocket(node);
            if (viaSock.isPresent()) return viaSock;
            return findContainerViaCli(node);
        }

        @Override
        public boolean stopContainer(String containerNameOrId, int timeoutSeconds) {
            if (containerNameOrId == null || containerNameOrId.isBlank()) return false;
            int timeout = Math.max(2, timeoutSeconds);
            String encoded = URLEncoder.encode(containerNameOrId.trim(), StandardCharsets.UTF_8);
            UnixHttpResult sockRes = callDockerUnixSocket("POST", "/containers/" + encoded + "/stop?t=" + timeout);
            if (sockRes != null && (sockRes.status == 204 || sockRes.status == 304 || (sockRes.status >= 200 && sockRes.status < 300))) {
                return true;
            }
            return runDockerCli("stop", "-t", String.valueOf(timeout), containerNameOrId.trim());
        }

        @Override
        public boolean startContainer(String containerNameOrId) {
            if (containerNameOrId == null || containerNameOrId.isBlank()) return false;
            String encoded = URLEncoder.encode(containerNameOrId.trim(), StandardCharsets.UTF_8);
            UnixHttpResult sockRes = callDockerUnixSocket("POST", "/containers/" + encoded + "/start");
            if (sockRes != null && (sockRes.status == 204 || sockRes.status == 304 || (sockRes.status >= 200 && sockRes.status < 300))) {
                return true;
            }
            return runDockerCli("start", containerNameOrId.trim());
        }

        private Optional<String> findContainerViaSocket(ServerNodeInfo node) {
            UnixHttpResult res = callDockerUnixSocket("GET", "/containers/json?all=true");
            if (res == null || res.status != 200 || res.body == null || res.body.isBlank()) {
                return Optional.empty();
            }
            try {
                JsonNode array = mapper.readTree(res.body);
                if (!array.isArray()) return Optional.empty();
                String selfHost = Optional.ofNullable(System.getenv("HOSTNAME")).orElse("").trim().toLowerCase(Locale.ROOT);
                String nodeIdLower = node.getId().trim().toLowerCase(Locale.ROOT);
                String nodeHostLower = node.getHost() != null ? node.getHost().trim().toLowerCase(Locale.ROOT) : "";
                int nodePort = node.getPort();
                int nodeRestPort = node.getRestPort() > 0 ? node.getRestPort() : (nodePort >= 9091 && nodePort <= 9099 ? nodePort - 1010 : -1);

                for (JsonNode c : array) {
                    String id = c.path("Id").asText("");
                    if (!selfHost.isEmpty() && id.toLowerCase(Locale.ROOT).startsWith(selfHost)) {
                        continue;
                    }
                    String primaryName = "";
                    JsonNode names = c.path("Names");
                    if (names.isArray() && !names.isEmpty()) {
                        primaryName = names.get(0).asText("").replaceFirst("^/+", "");
                    }
                    String nameLower = primaryName.toLowerCase(Locale.ROOT);
                    if (nameLower.contains("explorer") || nameLower.equals(selfHost)) {
                        continue;
                    }
                    String serviceLabel = c.path("Labels").path("com.docker.compose.service").asText("").toLowerCase(Locale.ROOT);
                    if (serviceLabel.contains("explorer")) {
                        continue;
                    }

                    if (matchesCandidateName(nameLower, serviceLabel, nodeIdLower, nodeHostLower)) {
                        return Optional.of(!primaryName.isEmpty() ? primaryName : id);
                    }

                    // Coincidencia por puerto publicado únicamente en contenedores jettra
                    String imageLower = c.path("Image").asText("").toLowerCase(Locale.ROOT);
                    if (nameLower.contains("jettra") || imageLower.contains("jettra") || serviceLabel.contains("jettra")) {
                        JsonNode ports = c.path("Ports");
                        if (ports.isArray()) {
                            for (JsonNode p : ports) {
                                int pubPort = p.path("PublicPort").asInt(-1);
                                if (pubPort > 0 && (pubPort == nodePort || (nodeRestPort > 0 && pubPort == nodeRestPort))) {
                                    return Optional.of(!primaryName.isEmpty() ? primaryName : id);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            return Optional.empty();
        }

        private Optional<String> findContainerViaCli(ServerNodeInfo node) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    "docker", "ps", "-a", "--format", "{{.Names}}|{{.Ports}}|{{.Label \"com.docker.compose.service\"}}"
                );
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                boolean finished = proc.waitFor(1500, TimeUnit.MILLISECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    return Optional.empty();
                }
                if (proc.exitValue() != 0) return Optional.empty();

                String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String nodeIdLower = node.getId().trim().toLowerCase(Locale.ROOT);
                String nodeHostLower = node.getHost() != null ? node.getHost().trim().toLowerCase(Locale.ROOT) : "";
                String selfHost = Optional.ofNullable(System.getenv("HOSTNAME")).orElse("").trim().toLowerCase(Locale.ROOT);

                for (String line : out.split("\\R")) {
                    if (line.isBlank()) continue;
                    String[] parts = line.split("\\|", -1);
                    String name = parts[0].trim();
                    String portsStr = parts.length > 1 ? parts[1] : "";
                    String serviceLabel = parts.length > 2 ? parts[2].trim().toLowerCase(Locale.ROOT) : "";
                    String nameLower = name.toLowerCase(Locale.ROOT);
                    if (nameLower.contains("explorer") || serviceLabel.contains("explorer") || (!selfHost.isEmpty() && nameLower.equals(selfHost))) {
                        continue;
                    }
                    if (matchesCandidateName(nameLower, serviceLabel, nodeIdLower, nodeHostLower)) {
                        return Optional.of(name);
                    }
                    if ((nameLower.contains("jettra") || serviceLabel.contains("jettra")) && node.getPort() > 0 && portsStr.contains(":" + node.getPort() + "->")) {
                        return Optional.of(name);
                    }
                }
            } catch (Exception ignored) {}
            return Optional.empty();
        }

        private boolean matchesCandidateName(String nameLower, String serviceLabel, String nodeIdLower, String nodeHostLower) {
            if (nodeIdLower.isEmpty()) return false;
            String prefixed = "jettra-" + nodeIdLower;
            if (nameLower.equals(prefixed) || nameLower.equals(nodeIdLower)
                    || serviceLabel.equals(prefixed) || serviceLabel.equals(nodeIdLower)) {
                return true;
            }
            if (!nodeHostLower.isEmpty() && !nodeHostLower.equals("127.0.0.1") && !nodeHostLower.equals("localhost")) {
                return nameLower.equals(nodeHostLower) || serviceLabel.equals(nodeHostLower);
            }
            return false;
        }

        private boolean runDockerCli(String... args) {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add("docker");
                for (String a : args) cmd.add(a);
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                boolean ok = p.waitFor(10, TimeUnit.SECONDS);
                if (!ok) {
                    p.destroyForcibly();
                    return false;
                }
                return p.exitValue() == 0;
            } catch (Exception ignored) {
                return false;
            }
        }

        private UnixHttpResult callDockerUnixSocket(String method, String path) {
            if (!Files.exists(DOCKER_SOCK_FILE)) return null;
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(UnixDomainSocketAddress.of(DOCKER_SOCK_FILE));
                String req = method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
                ByteBuffer writeBuf = ByteBuffer.wrap(req.getBytes(StandardCharsets.US_ASCII));
                while (writeBuf.hasRemaining()) {
                    channel.write(writeBuf);
                }

                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                ByteBuffer readBuf = ByteBuffer.allocate(4096);
                while (channel.read(readBuf) != -1) {
                    readBuf.flip();
                    raw.write(readBuf.array(), 0, readBuf.limit());
                    readBuf.clear();
                }

                String responseStr = raw.toString(StandardCharsets.UTF_8);
                int headerEnd = responseStr.indexOf("\r\n\r\n");
                if (headerEnd < 0) return null;

                String headerBlock = responseStr.substring(0, headerEnd);
                String rawBody = responseStr.substring(headerEnd + 4);
                String[] headerLines = headerBlock.split("\r\n");
                if (headerLines.length == 0) return null;
                String[] statusParts = headerLines[0].split(" ");
                int status = statusParts.length >= 2 ? Integer.parseInt(statusParts[1].trim()) : 0;

                boolean chunked = headerBlock.toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked");
                String decodedBody = chunked ? decodeChunkedBody(rawBody) : rawBody;
                return new UnixHttpResult(status, decodedBody);
            } catch (Exception ignored) {
                return null;
            }
        }

        private String decodeChunkedBody(String chunked) {
            StringBuilder out = new StringBuilder();
            int idx = 0;
            while (idx < chunked.length()) {
                int crlf = chunked.indexOf("\r\n", idx);
                if (crlf < 0) break;
                String hexLen = chunked.substring(idx, crlf).trim();
                int semi = hexLen.indexOf(';');
                if (semi >= 0) hexLen = hexLen.substring(0, semi);
                int len;
                try {
                    len = Integer.parseInt(hexLen, 16);
                } catch (NumberFormatException nfe) {
                    break;
                }
                if (len == 0) break;
                int start = crlf + 2;
                int end = Math.min(chunked.length(), start + len);
                out.append(chunked, start, end);
                idx = end + 2;
            }
            return out.toString();
        }

        private record UnixHttpResult(int status, String body) {}
    }
}
