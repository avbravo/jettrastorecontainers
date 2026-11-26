package io.jettra.driver.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Gestor centralizado para la carga inicial desde {@code cluster.ip.nodes} y
 * la persistencia atómica de {@code conecction.config} en el directorio de ejecución
 * (o volumen Docker montado en dicho directorio).
 */
public final class JettraConnectionConfigManager {

    public static final String CONNECTION_CONFIG_FILENAME = "conecction.config";
    public static final String CLUSTER_IP_NODES_PROPERTY = "cluster.ip.nodes";
    public static final String DEFAULT_CLUSTER_IP_NODES = "127.0.0.1:50001";

    public record Endpoint(String host, int port) {
        public String toHostPort() {
            return host + ":" + port;
        }
    }

    public record ConnectionState(
            String clusterIpNodes,
            List<Endpoint> endpoints,
            String username,
            boolean loadedFromConnectionFile,
            Path connectionFile
    ) {
        public Endpoint primarySeed() {
            return (endpoints != null && !endpoints.isEmpty())
                    ? endpoints.get(0)
                    : new Endpoint("127.0.0.1", 50001);
        }
    }

    private JettraConnectionConfigManager() {
    }

    /**
     * Resuelve la ruta a {@code conecction.config} priorizando propiedad de sistema,
     * variable de entorno o directorio de ejecución actual ({@code user.dir}).
     */
    public static Path resolveConnectionConfigFile() {
        String explicitFile = System.getProperty("jettra.connection.config.file");
        if (explicitFile == null || explicitFile.isBlank()) {
            explicitFile = System.getenv("JETTRA_CONNECTION_CONFIG_FILE");
        }
        if (explicitFile != null && !explicitFile.isBlank()) {
            return Path.of(explicitFile.trim()).toAbsolutePath().normalize();
        }
        String explicitDir = System.getProperty("jettra.connection.config.dir");
        if (explicitDir == null || explicitDir.isBlank()) {
            explicitDir = System.getenv("JETTRA_CONNECTION_CONFIG_DIR");
        }
        if (explicitDir != null && !explicitDir.isBlank()) {
            return Path.of(explicitDir.trim()).toAbsolutePath().normalize().resolve(CONNECTION_CONFIG_FILENAME);
        }
        String workDir = System.getProperty("user.dir", ".");
        return Path.of(workDir).toAbsolutePath().normalize().resolve(CONNECTION_CONFIG_FILENAME);
    }

    /**
     * Carga la configuración de conexión respetando estrictamente la precedencia RF-32:
     * 1) Si {@code conecction.config} existe y no está vacío, carga desde {@code conecction.config}.
     * 2) En caso contrario, usa {@code cluster.ip.nodes} (sysprop, env o microprofile-config.properties,
     *    con fallback {@code 127.0.0.1:50001}).
     */
    public static ConnectionState loadInitialConnection(Properties microprofileProps) {
        Path connFile = resolveConnectionConfigFile();
        if (connFile != null && Files.exists(connFile)) {
            try {
                if (Files.size(connFile) > 0) {
                    String rawContent = Files.readString(connFile, StandardCharsets.UTF_8);
                    Properties props = new Properties();
                    try (InputStream is = new ByteArrayInputStream(rawContent.getBytes(StandardCharsets.UTF_8))) {
                        props.load(is);
                    }
                    String savedNodes = firstNonBlank(
                            props.getProperty(CLUSTER_IP_NODES_PROPERTY),
                            props.getProperty("url"),
                            extractBareEndpointsLine(rawContent));
                    List<Endpoint> parsed = parseEndpoints(savedNodes);
                    if (!parsed.isEmpty()) {
                        String normalized = joinEndpoints(parsed);
                        String user = firstNonBlank(props.getProperty("connection.username"), props.getProperty("username"), "admin");
                        return new ConnectionState(normalized, List.copyOf(parsed), user, true, connFile);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        String rawProp = firstNonBlank(
                System.getProperty(CLUSTER_IP_NODES_PROPERTY),
                System.getenv("CLUSTER_IP_NODES"),
                System.getenv("JETTRA_CLUSTER_IP_NODES"),
                microprofileProps != null ? microprofileProps.getProperty(CLUSTER_IP_NODES_PROPERTY) : null,
                DEFAULT_CLUSTER_IP_NODES);

        List<Endpoint> parsed = parseEndpoints(rawProp);
        if (parsed.isEmpty()) {
            parsed = parseEndpoints(DEFAULT_CLUSTER_IP_NODES);
        }
        String defaultUser = microprofileProps != null
                ? firstNonBlank(microprofileProps.getProperty("jettra.security.default.admin.username"),
                        microprofileProps.getProperty("connection.username"), "admin")
                : "admin";
        return new ConnectionState(joinEndpoints(parsed), List.copyOf(parsed), defaultUser, false, connFile);
    }

    /**
     * Valida y normaliza una o más direcciones {@code host:port} separadas por coma.
     */
    public static String normalizeAndValidateClusterIpNodes(String rawInput) {
        List<Endpoint> endpoints = parseEndpointsStrict(rawInput);
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("Debe indicar al menos una dirección host:port válida (ejemplo: 127.0.0.1:50001).");
        }
        return joinEndpoints(endpoints);
    }

    /**
     * Persiste de forma atómica (temporal + flush/fsync + ATOMIC_MOVE) la conexión en {@code conecction.config}.
     */
    public static synchronized ConnectionState saveConnectionAtomically(String rawEndpoints, String username) throws IOException {
        String normalized = normalizeAndValidateClusterIpNodes(rawEndpoints);
        List<Endpoint> endpoints = parseEndpointsStrict(normalized);
        String effectiveUser = (username != null && !username.isBlank()) ? username.trim() : "admin";

        Path connFile = resolveConnectionConfigFile();
        Path parent = connFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# JettraStore Active Cluster Connection (RF-32)\n");
        sb.append(CLUSTER_IP_NODES_PROPERTY).append("=").append(normalized).append('\n');
        sb.append("connection.username=").append(effectiveUser).append('\n');
        sb.append("connection.updated.at=").append(Instant.now().toString()).append('\n');
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);

        Path tmpFile = connFile.resolveSibling(CONNECTION_CONFIG_FILENAME + ".tmp." + System.nanoTime());
        try (FileChannel channel = FileChannel.open(
                tmpFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
        }
        try {
            Files.move(tmpFile, connFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmpFile, connFile, StandardCopyOption.REPLACE_EXISTING);
        }

        return new ConnectionState(normalized, List.copyOf(endpoints), effectiveUser, true, connFile);
    }

    public static List<Endpoint> parseEndpoints(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String cleaned = stripProtocolPrefix(raw);
        Map<String, Endpoint> unique = new LinkedHashMap<>();
        for (String token : cleaned.split(",")) {
            String item = token.trim();
            if (item.isEmpty()) {
                continue;
            }
            Endpoint ep = parseSingleTokenLenient(item);
            if (ep != null) {
                unique.putIfAbsent(ep.toHostPort().toLowerCase(), ep);
            }
        }
        return new ArrayList<>(unique.values());
    }

    public static List<Endpoint> parseEndpointsStrict(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("La dirección de conexión no puede estar vacía.");
        }
        String cleaned = stripProtocolPrefix(raw);
        Map<String, Endpoint> unique = new LinkedHashMap<>();
        for (String token : cleaned.split(",")) {
            String item = token.trim();
            if (item.isEmpty()) {
                continue;
            }
            Endpoint ep = parseSingleTokenStrict(item);
            unique.putIfAbsent(ep.toHostPort().toLowerCase(), ep);
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("No se encontró ninguna dirección host:port válida.");
        }
        return new ArrayList<>(unique.values());
    }

    public static String joinEndpoints(List<Endpoint> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return DEFAULT_CLUSTER_IP_NODES;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < endpoints.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(endpoints.get(i).toHostPort());
        }
        return sb.toString();
    }

    private static String stripProtocolPrefix(String raw) {
        String s = raw.trim();
        if (s.startsWith("tcp://") || s.startsWith("TCP://")) {
            s = s.substring(6);
        } else if (s.startsWith("http://") || s.startsWith("HTTP://")) {
            s = s.substring(7);
        }
        return s.trim();
    }

    private static Endpoint parseSingleTokenLenient(String token) {
        try {
            return parseSingleTokenStrict(token);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Endpoint parseSingleTokenStrict(String token) {
        String clean = stripProtocolPrefix(token);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Dirección vacía.");
        }
        // Soporte opcional para host:port o nodeId:host:port
        int colonCount = 0;
        for (int i = 0; i < clean.length(); i++) {
            if (clean.charAt(i) == ':') colonCount++;
        }
        String host;
        int port;
        if (colonCount == 0) {
            host = clean;
            port = 50001;
        } else if (colonCount == 1) {
            int idx = clean.indexOf(':');
            host = clean.substring(0, idx).trim();
            port = parseValidPort(clean.substring(idx + 1).trim(), token);
        } else if (colonCount == 2 && !clean.startsWith("[")) {
            // node-01:127.0.0.1:50001
            String[] parts = clean.split(":");
            host = parts[1].trim();
            port = parseValidPort(parts[2].trim(), token);
        } else {
            int lastColon = clean.lastIndexOf(':');
            host = clean.substring(0, lastColon).trim();
            port = parseValidPort(clean.substring(lastColon + 1).trim(), token);
        }
        if (host.isEmpty() || host.contains(" ")) {
            throw new IllegalArgumentException("Host inválido en dirección: '" + token + "'");
        }
        return new Endpoint(host, port);
    }

    private static int parseValidPort(String portStr, String rawToken) {
        try {
            int port = Integer.parseInt(portStr);
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Puerto fuera de rango (1-65535) en: '" + rawToken + "'");
            }
            return port;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Puerto numérico inválido en: '" + rawToken + "'");
        }
    }

    private static String extractBareEndpointsLine(String rawContent) {
        if (rawContent == null) return null;
        for (String line : rawContent.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!") || trimmed.contains("=")) {
                continue;
            }
            return trimmed;
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
