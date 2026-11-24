package io.jettra.example.se;

import io.jettra.driver.JettraClient;
import io.jettra.driver.config.JettraClientConfig;
import io.jettra.example.se.model.NotaLibre;
import io.jettra.example.se.model.Pais;
import io.jettra.example.se.model.Producto;
import io.jettra.example.se.repository.NotaLibreRepository;
import io.jettra.example.se.repository.PaisRepository;
import io.jettra.example.se.repository.ProductoRepository;
import io.jettra.store.core.JettraConfigValidator;
import io.jettra.store.core.JettraId;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Aplicación Java SE 25 de referencia que demuestra el flujo completo de persistencia real sobre JettraStore:
 * <ol>
 *   <li>Conexión autenticada con {@link JettraClient} usando todos los nodos definidos en {@code cluster.config}.</li>
 *   <li>Obtención de {@code cluster.config} vigente desde el nodo {@code PRIMARY}.</li>
 *   <li>Creación de la base de datos {@code examplesedb} y sus Buckets ({@code DEFINED} y {@code NONE}).</li>
 *   <li>Operaciones CRUD completas (insert, findById, update, delete) usando {@link JettraId},
 *       Java {@code record}, Patrón Repository y validación {@code JettraRules}.</li>
 *   <li>Administración y consultas sobre índices por Bucket ({@code AUTO_INCREMENT}, {@code BTREE UNIQUE} y {@code NUMERIC}).</li>
 * </ol>
 */
public final class JettraStoreExampleSEApp {

    public static final String DATABASE = "examplesedb";

    public static JettraClientConfig buildClusterConfig() {
        String user = System.getProperty("jettra.user", "admin");
        String pass = System.getProperty("jettra.password", "admin-jettra");
        String hostOverride = System.getProperty("jettra.host");
        Integer portOverride = Integer.getInteger("jettra.port");

        JettraClientConfig.Builder builder = JettraClientConfig.builder()
                .credentials(user, pass)
                .role("admin")
                .defaultDatabase(DATABASE);

        if (hostOverride != null && !hostOverride.isBlank() && portOverride != null && portOverride > 0) {
            builder.addClusterNode(hostOverride.trim(), portOverride);
            return builder.build();
        }

        Path clusterFile = JettraConfigValidator.locateClusterConfigFile();
        Properties props = JettraConfigValidator.loadProperties(clusterFile, "/cluster.config", "/jettra.config");
        List<JettraConfigValidator.ClusterNodeInfo> nodes = JettraConfigValidator.parseClusterNodes(props);
        if (nodes.isEmpty()) {
            builder.addClusterNode("127.0.0.1", 50001);
        } else {
            nodes.sort((a, b) -> Boolean.compare("PRIMARY".equalsIgnoreCase(b.role()), "PRIMARY".equalsIgnoreCase(a.role())));
            for (var n : nodes) {
                if (n.ip() != null && !n.ip().isBlank() && n.grpcPort() > 0) {
                    builder.addClusterNode(n.ip().trim(), n.grpcPort());
                }
            }
        }
        return builder.build();
    }

    public static void main(String[] args) {
        JettraClientConfig config = buildClusterConfig();

        try (JettraClient client = JettraClient.connect(config)) {
            System.out.println("=== 1. Topología cluster.config recuperada del PRIMARY ===");
            String clusterCfg = client.readPrimaryClusterConfig();
            System.out.println(clusterCfg.isBlank() ? "(Usando cluster.config local)" : clusterCfg);

            System.out.println("=== 2. Creando / vinculando base de datos '" + DATABASE + "' ===");
            client.createDatabase(DATABASE);

            System.out.println("=== 3. Inicializando Buckets (DEFINED y NONE) e índices por Bucket ===");
            PaisRepository paisRepo = new PaisRepository(client, DATABASE);
            ProductoRepository prodRepo = new ProductoRepository(client, DATABASE);
            NotaLibreRepository notaRepo = new NotaLibreRepository(client, DATABASE);

            paisRepo.initBucketAndIndexes();
            prodRepo.initBucketAndIndexes();
            notaRepo.initBucket();

            System.out.println("=== 4. Insertando / actualizando registros reales con JettraId + Java record + JettraRules ===");
            Pais panama = paisRepo.findBySiglas("PA")
                    .orElseGet(() -> paisRepo.save(new Pais(null, "Panama", "PA")));
            Pais costaRica = paisRepo.findBySiglas("CR")
                    .orElseGet(() -> paisRepo.save(new Pais(null, "Costa Rica", "CR")));
            System.out.println("  • Pais persistido: " + panama + " [JettraId=" + panama.jettraId() + "]");
            System.out.println("  • Pais persistido: " + costaRica + " [JettraId=" + costaRica.jettraId() + "]");

            // Actualización por JettraId conservando el identificador y la secuencia AUTO_INCREMENT
            Pais panamaActualizado = paisRepo.update(
                    panama.jettraId(),
                    new Pais(panama.jettraId(), panama.codigo(), "República de Panamá", "PA"));
            System.out.println("  • Pais actualizado por JettraId: " + panamaActualizado);

            Producto teclado = prodRepo.save(new Producto(null, "Teclado Mecánico", "Periféricos", 79.99));
            Producto monitor = prodRepo.save(new Producto(null, "Monitor 4K", "Pantallas", 349.50));
            Producto mouse = prodRepo.save(new Producto(null, "Mouse Óptico", "Periféricos", 24.90));
            System.out.println("  • Productos en rango [20.0, 100.0] vía índice NUMERIC: "
                    + prodRepo.findByPrecioRange(20.0, 100.0));

            // Eliminación por JettraId sobre Bucket
            Producto temporal = prodRepo.save(new Producto(null, "Cable Temporal", "Accesorios", 9.99));
            boolean deleted = prodRepo.deleteById(temporal.jettraId());
            System.out.println("  • Producto temporal eliminado por JettraId (" + temporal.jettraId() + "): " + deleted);

            // Bucket NONE con JettraId autogenerado y explícito
            NotaLibre notaAuto = notaRepo.save(new NotaLibre(Map.of("titulo", "Nota 1", "autor", "Aristides")));
            JettraId customNotaId = JettraId.generate(DATABASE, NotaLibreRepository.BUCKET);
            NotaLibre notaCustom = notaRepo.save(new NotaLibre(customNotaId, Map.of("apellido", "Perez", "pais", "Panama")));
            System.out.println("  • Notas libres (Bucket NONE): " + List.of(notaAuto, notaCustom));

            System.out.println("=== 5. Buckets e índices persistidos en '" + DATABASE + "' ===");
            client.listBuckets(DATABASE).forEach(b -> System.out.println("  • Bucket: " + b.engineType() + "::" + b.bucketName() + " [" + b.structureType() + "]"));
            client.listIndexes(DATABASE).forEach(idx -> System.out.println("  • Índice: " + idx));
        }
    }
}
