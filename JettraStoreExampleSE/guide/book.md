# Guía Completa de `JettraStoreExampleSE` (Java 25+, Buckets, `JettraId` & `JettraStoreDriver`)

## 1. Descripción General
**JettraStoreExampleSE** es el proyecto Java SE 25 de referencia para construir aplicaciones cliente con **persistencia real** sobre un clúster **JettraStore** (`examplesedb`), alineado con el modelo unificado de **Buckets** y **`JettraId`**:
- **`JettraStoreDriver`** (`JettraClient`) para conexión autenticada al clúster (`cluster.config`), lectura de topología desde `PRIMARY`, creación/gestión de **Buckets** (`DEFINED` y `NONE`), persistencia en disco y replicación activa hacia los nodos del clúster.
- **`JettraId`** (`io.jettra.store.core.JettraId`) como identificador único primario explícito en todos los modelos de dominio (`Pais`, `Producto`, `NotaLibre`) y en todas las operaciones CRUD (`save`, `findById`, `update`, `deleteById`).
- **`JettraRules`** (`@NotBlank`, `@Size`, `@Positive`, `JettraRulesEngine.validate(...)`) para validación declarativa de entidades antes de persistir.
- **Java `record`** inmutables y **Patrón Repository** (`JettraRepository<T>`) operando exclusivamente sobre **Buckets** (sin referencias obsoletas a `COLLECTION`).
- **`JettraTest`** para pruebas automatizadas de persistencia real tras reconexión, `JettraId`, índices por Bucket y resiliencia del clúster.

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
`JettraStoreExampleSEApp.buildClusterConfig()` carga automáticamente todos los nodos definidos en `cluster.config` (priorizando el `PRIMARY`) y recupera la versión vigente del clúster con `client.readPrimaryClusterConfig()`.

---

## 3. Modelos Java `record` con `JettraId` y Modelo de `Bucket`

Todos los modelos incluyen explícitamente su **`JettraId`** y cada repositorio lo almacena y recupera junto con los atributos del registro:

1. **Bucket estructurado (`DEFINED`) `JAVA_RECORD::Pais` (`PaisRepository`):**
   - Campos: `jettraId` (`JettraId`), `codigo` (`AUTO_INCREMENT`), `name` (`@NotBlank`), `siglas` (`@NotBlank`, `@Size(2..3)`).
   - Índices del Bucket: `idx_pais_codigo` (`AUTO_INCREMENT`) e `idx_pais_siglas` (`BTREE UNIQUE`).
2. **Bucket estructurado (`DEFINED`) `DOCUMENT::Producto` (`ProductoRepository`):**
   - Campos: `jettraId` (`JettraId`), `sku` (`AUTO_INCREMENT`), `nombre` (`@NotBlank`), `categoria` (`@NotBlank`), `precio` (`@Positive`).
   - Índices del Bucket: `idx_producto_sku` (`AUTO_INCREMENT`) e `idx_producto_precio` (`NUMERIC`, con consultas por rango `findByPrecioRange(min, max)`).
3. **Bucket sin estructura (`NONE`) `DOCUMENT::NotasLibres` (`NotaLibreRepository`):**
   - Campos: `jettraId` (`JettraId`) + `Map<String, Object> atributos` flexibles.

### Ejemplo CRUD real con `JettraId` sobre `examplesedb`
```java
try (JettraClient client = JettraClient.connect(JettraStoreExampleSEApp.buildClusterConfig())) {
    client.createDatabase("examplesedb");

    PaisRepository paisRepo = new PaisRepository(client, "examplesedb");
    paisRepo.initBucketAndIndexes();

    // 1. Insertar (autogenera JettraId y codigo AUTO_INCREMENT=1)
    Pais panama = paisRepo.save(new Pais(null, "Panama", "PA"));
    JettraId idPanama = panama.jettraId();

    // 2. Consultar por JettraId y por índice BTREE
    Pais encontrado = paisRepo.findById(idPanama).orElseThrow();
    Pais porSiglas = paisRepo.findBySiglas("PA").orElseThrow();

    // 3. Actualizar por JettraId (conserva JettraId y codigo)
    Pais actualizado = paisRepo.update(
            idPanama,
            new Pais(idPanama, panama.codigo(), "República de Panamá", "PA"));

    // 4. Eliminar por JettraId
    ProductoRepository prodRepo = new ProductoRepository(client, "examplesedb");
    prodRepo.initBucketAndIndexes();
    Producto temp = prodRepo.save(new Producto(null, "Cable Temporal", "Accesorios", 9.99));
    boolean eliminado = prodRepo.deleteById(temp.jettraId());
}
```

---

## 4. Resiliencia del Clúster (3 nodos reales)
```java
// 1. No se permite remover un nodo activo (lanza IllegalStateException)
client.stopClusterNode("node-03");
client.removeStoppedClusterNode("node-03");
// node-01 (PRIMARY) y node-02 (SECONDARY) permanecen operativos sin interrupción
```

---

## 5. Compilación, Pruebas (`JettraTest`) y Ejecución
```bash
# Instalar JettraStoreDriver actualizado y compilar/probar JettraStoreExampleSE
mvn install -pl JettraStoreDriver,JettraStoreExampleSE

# Ejecutar la aplicación contra el clúster definido en cluster.config (almacena en examplesedb)
mvn exec:exec -pl JettraStoreExampleSE
```
