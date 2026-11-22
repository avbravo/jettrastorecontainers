package io.jettra.example.se;

import io.jettra.driver.JettraClient;
import io.jettra.driver.config.JettraClientConfig;
import io.jettra.example.se.model.NotaLibre;
import io.jettra.example.se.model.Pais;
import io.jettra.example.se.model.Producto;
import io.jettra.example.se.repository.NotaLibreRepository;
import io.jettra.example.se.repository.PaisRepository;
import io.jettra.example.se.repository.ProductoRepository;

import java.util.List;
import java.util.Map;

/**
 * Aplicación Java SE 25 de referencia que demuestra el flujo completo sobre JettraStore:
 * <ol>
 *   <li>Conexión autenticada con {@link JettraClient} y obtención de {@code cluster.config} del PRIMARY.</li>
 *   <li>Creación de la base de datos {@code examplesedb}.</li>
 *   <li>Creación de buckets estructurados ({@code DEFINED}) y sin estructura ({@code NONE}).</li>
 *   <li>Operaciones CRUD usando Java {@code record}, Patrón Repository y {@code JettraRules}.</li>
 *   <li>Administración de índices estándar, numéricos ({@code NUMERIC}) y autoincrementables ({@code AUTO_INCREMENT}).</li>
 * </ol>
 */
public final class JettraStoreExampleSEApp {

    public static final String DATABASE = "examplesedb";

    public static void main(String[] args) {
        String host = System.getProperty("jettra.host", "127.0.0.1");
        int port = Integer.getInteger("jettra.port", 50001);
        String user = System.getProperty("jettra.user", "admin");
        String pass = System.getProperty("jettra.password", "admin-jettra");

        JettraClientConfig config = JettraClientConfig.builder()
                .addClusterNode(host, port)
                .credentials(user, pass)
                .role("admin")
                .defaultDatabase(DATABASE)
                .build();

        try (JettraClient client = JettraClient.connect(config)) {
            System.out.println("=== 1. Topología cluster.config recuperada del PRIMARY ===");
            String clusterCfg = client.readPrimaryClusterConfig();
            System.out.println(clusterCfg.isBlank() ? "(Usando cluster.config local)" : clusterCfg);

            System.out.println("=== 2. Creando base de datos '" + DATABASE + "' ===");
            client.createDatabase(DATABASE);

            System.out.println("=== 3. Inicializando buckets (DEFINED y NONE) e índices ===");
            PaisRepository paisRepo = new PaisRepository(client, DATABASE);
            ProductoRepository prodRepo = new ProductoRepository(client, DATABASE);
            NotaLibreRepository notaRepo = new NotaLibreRepository(client, DATABASE);

            paisRepo.initBucketAndIndexes();
            prodRepo.initBucketAndIndexes();
            notaRepo.initBucket();

            System.out.println("=== 4. Insertando registros con Java record + JettraRules + AUTO_INCREMENT ===");
            paisRepo.save("pa-1", new Pais(null, "Panama", "PA"));
            paisRepo.save("cr-1", new Pais(null, "Costa Rica", "CR"));

            prodRepo.save("prod-1", new Producto(null, "Teclado Mecánico", "Periféricos", 79.99));
            prodRepo.save("prod-2", new Producto(null, "Monitor 4K", "Pantallas", 349.50));
            prodRepo.save("prod-3", new Producto(null, "Mouse Óptico", "Periféricos", 24.90));

            notaRepo.save("nota-1", new NotaLibre("nota-1", Map.of("id", 1, "name", "aristides")));
            notaRepo.save("nota-2", new NotaLibre("nota-2", Map.of("apellido", "Perez", "pais", "Panama")));

            System.out.println("Países registrados: " + paisRepo.findAll());
            System.out.println("Productos en rango [20.0, 100.0] vía índice NUMERIC: " + prodRepo.findByPrecioRange(20.0, 100.0));
            System.out.println("Notas libres (NONE): " + notaRepo.findAll());

            System.out.println("=== 5. Índices registrados en '" + DATABASE + "' ===");
            List<?> indices = client.listIndexes(DATABASE);
            indices.forEach(idx -> System.out.println("  • " + idx));
        }
    }
}
