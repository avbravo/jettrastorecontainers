# Guía Completa de `JettraStoreExampleSE` (Java 25+ & `JettraStoreDriver`)

## 1. Descripción General
**JettraStoreExampleSE** es el proyecto Java SE 25 de referencia para construir aplicaciones cliente sobre un clúster **JettraStore** utilizando:
- **`JettraStoreDriver`** (`JettraClient`) para conexión autenticada, lectura de `cluster.config` desde el nodo `PRIMARY`, gestión de bases de datos, buckets, índices y operaciones zero-downtime.
- **`JettraRules`** (`@NotBlank`, `@Size`, `@Positive`, `JettraRulesEngine.validate(...)`) para validación declarativa de entidades.
- **Java `record`** inmutables (`Pais`, `Producto`, `NotaLibre`) y **Patrón Repository** (`JettraRepository<T, ID>`).
- **`JettraTest`** para pruebas automatizadas de extremo a extremo y pruebas de resiliencia del clúster.

---

## 2. Configuración del Clúster (`src/main/resources/cluster.config`)
```properties
cluster.multinode.active=on
cluster.node.1.id=node-01
cluster.node.1.role=PRIMARY
cluster.node.1.ip=127.0.0.1
cluster.node.1.grpc.port=50001
cluster.node.1.rest.port=60001
cluster.node.2.id=node-02
cluster.node.2.role=SECONDARY
cluster.node.2.ip=127.0.0.1
cluster.node.2.grpc.port=50002
cluster.node.2.rest.port=60002
cluster.node.3.id=node-03
cluster.node.3.role=SECONDARY
cluster.node.3.ip=127.0.0.1
cluster.node.3.grpc.port=50003
cluster.node.3.rest.port=60003
```

---

## 3. Flujo Completo en `examplesedb`

1. **Conexión y obtención de `cluster.config` del PRIMARY:**
   ```java
   JettraClientConfig config = JettraClientConfig.builder()
           .addClusterNode("127.0.0.1", 50001)
           .credentials("admin", "admin-jettra")
           .role("admin")
           .defaultDatabase("examplesedb")
           .build();
   try (JettraClient client = JettraClient.connect(config)) {
       String primaryClusterConfig = client.readPrimaryClusterConfig();
   }
   ```
2. **Creación de buckets estructurados (`DEFINED`) y sin estructura (`NONE`):**
   - `PaisRepository`: engine `JAVA_RECORD`, bucket `Pais` (`DEFINED`), índice `AUTO_INCREMENT` en `codigo` e índice `BTREE UNIQUE` en `siglas`.
   - `ProductoRepository`: engine `DOCUMENT`, bucket `Producto` (`DEFINED`), índice `AUTO_INCREMENT` en `sku` e índice `NUMERIC` en `precio` con consultas por rango `findByPrecioRange(min, max)`.
   - `NotaLibreRepository`: engine `DOCUMENT`, bucket `NotasLibres` (`NONE`, esquema libre).
3. **Validación con `JettraRules` + Java `record`:**
   ```java
   paisRepo.save("pa-1", new Pais(null, "Panama", "PA")); // autogenera codigo=1
   prodRepo.save("prod-1", new Producto(null, "Teclado Mecánico", "Periféricos", 79.99));
   List<Producto> ofertas = prodRepo.findByPrecioRange(20.0, 100.0);
   ```

---

## 4. Guía Paso a Paso de Pruebas de Resiliencia del Clúster

1. **Detener un nodo secundario y verificar continuidad:**
   ```java
   var stop = client.stopClusterNode("node-03");
   // node-01 (PRIMARY) y node-02 (SECONDARY) permanecen operativos (quórum 2/3)
   ```
2. **Reiniciar un nodo y verificar reincorporación y sincronización:**
   ```java
   var start = client.startClusterNode("node-03");
   // node-03 se sincroniza automáticamente con node-01 sin detener el clúster
   ```
3. **Remover un nodo detenido (regla estricta):**
   ```java
   // Si node-03 sigue activo, removeStoppedClusterNode("node-03") lanza IllegalStateException.
   client.stopClusterNode("node-03");
   client.removeStoppedClusterNode("node-03");
   ```

---

## 5. Compilación, Pruebas y Ejecución
```bash
# Compilar y ejecutar pruebas JettraTest (incluye resiliencia con 3 nodos reales)
mvn install -pl JettraStoreExampleSE

# Ejecutar la aplicación contra un clúster local activo en 127.0.0.1:50001
mvn exec:exec -pl JettraStoreExampleSE
```
