# JettraRDB — Motor de Base de Datos Multimodelo Distribuido en Rust

**JettraRDB** es la implementación en **Rust** (nativa, *Zero-GC*, segura en concurrencia y sin dependencias externas) del motor de base de datos multimodelo distribuido inspirado y compatible con **JettraStore**.

---

## Arquitectura y Funcionalidades Principales

### 1. Ocho (8) Motores Multimodelo por Base de Datos (`src/engines/mod.rs`)
Cada instancia de `JettraDatabase` coordina simultáneamente 8 motores especializados:
1. **Motor Documental (`DocumentEngine`)**: Almacenamiento de documentos JSON/Map, inserciones por lotes (`save_batch`), cursores paginados perezosos (`StreamResponse`), desalojo por porcentaje (`evict_percentage`) y referencias cruzadas entre motores (`JettraRef` en modo `Lazy` o `Eager`).
2. **Motor Clave-Valor (`KeyValueEngine`)**: Almacenamiento binario (`Vec<u8>`) y texto de ultra-baja latencia por espacios de nombres (`namespace`).
3. **Motor Vectorial (`VectorEngine`)**: Indexación de embeddings multidimensionales (`Vec<f32>`), búsqueda Top-K por similitud Coseno (`search_cosine`), distancia Euclidiana (`search_euclidean`) y cálculo de centroide (`centroid`).
4. **Motor de Grafos (`GraphEngine`)**: Grafo dirigido de propiedades con vértices (`add_vertex`), aristas etiquetadas con propiedades (`add_edge`) y recorrido de aristas salientes (`get_outbound_edges`).
5. **Motor de Series de Tiempo (`TimeSeriesEngine`)**: Puntos métricos ordenados por timestamp (`BTreeMap<i64, f64>`), consultas por ventana de tiempo (`range`) y promedios (`average`).
6. **Motor Geoespacial (`GeospatialEngine`)**: Almacenamiento de coordenadas GIS (`GeoPoint`) y búsqueda radial exacta mediante fórmula de Haversine (`find_within_radius`).
7. **Motor Columnar OLAP (`ColumnarEngine`)**: Almacenamiento en columnas contiguas numéricas (`f64`) y de texto (`String`) para agregaciones analíticas rápidas (`sum_column`).
8. **Motor de Registros Tipados (`RecordsEngine`)**: Persistencia de estructuras fuertemente tipadas e inmutables (como `FacturaAuditRecord`).

### 2. Almacenamiento Nativo, SSTables `.jettra` y Snapshots (`src/storage.rs`)
- **`NativeMemTable`**: MemTable en memoria nativa de Rust sin recolector de basura, compatible a nivel binario con los archivos `.jettra` (`Magic Header 0x4A 0x53 "JS"`, versión `1`).
- **`BackupManager`**: Copias de seguridad en caliente (`backup_file`) y restauración (`restore_file`) con suma de verificación **IEEE CRC32**.

### 3. Índices Secundarios `HASH` y `BTREE` (`src/index.rs`)
- **`JettraIndexManager`**: Soporta índices `HASH` (búsqueda puntual $O(1)$) y `BTREE` (búsqueda puntual y por rangos $O(\log N)$), restricción `unique` y reconstrucción automática (`rebuild_index`).

### 4. Motores Cuantitativos Integrados (`src/calc.rs`)
- **`JettraMath`**: Evaluador de expresiones matemáticas (`eval`) con soporte para operadores `+, -, *, /, %, ^` y funciones `sqrt, sin, cos, tan, log, ln, log10, exp, round, pow, gcd, lcm, factorial`.
- **`JettraFinance`**: `pmt`, `fv`, `pv`, `npv`, `irr` (Newton-Raphson), `mirr`, `roi`, `cagr`, `payback_period`, `depreciation_straight_line` y `amortization_schedule`.
- **`JettraStatistics`**: `mean`, `median`, `mode`, `variance`, `stddev`, `percentile`, `iqr`, `skewness`, `kurtosis`, `correlation`, `covariance`, `linear_regression` y `summary`.
- **`JettraVectorMath`**: `dot_product`, `norm`, `l1_norm`, `cosine_similarity`, `euclidean_distance`, `manhattan_distance`, `chebyshev_distance`, `normalize`, `centroid` y `cross_product`.
- **`JettraAggregation`**: Agrupamiento `GROUP BY` y funciones `COUNT, SUM, AVG, MIN, MAX, MEDIAN, MODE, STDDEV, VARIANCE, IQR, P50, P90, P95, P99`.

### 5. Procesadores de Consultas `JettraSQL` y `JettraQL` (`src/query.rs`)
- **`JettraSQLProcessor`**: `SELECT ... FROM ... WHERE ... GROUP BY ... LIMIT ...`, `INSERT INTO`, `UPDATE`, `DELETE FROM`, `AGGREGATE`, `MATH`, `FINANCE`, `STATS`, `VECTOR`, `INSTALL SAMPLE`.
- **`JettraQLProcessor`**: `FROM`, `FIND`, `FETCH ... RESOLVE REFS`, `MATCH (v) IN <graph>`, `VECTOR SIMILARITY <col> TO [...] LIMIT <k>`.

### 6. Centinela Autónomo de Memoria `JettraPolice` (`src/police.rs`)
- Monitorea el RSS real del proceso (`/proc/self/statm`), previene desbordes de memoria activando paginación perezosa (`AutoPaginateLazy`) y provee `LazyPagedCursor`.

### 7. Clúster Distribuido Raft y Anillo Dinámico (`src/cluster.rs`)
- **`JettraClusterProtocol` & `JettraRaftFrame`**: Protocolo binario compatible con `JettraStore` (`Magic 0x4A54 "JT"`, 22 tipos de trama).
- **`DynamicRingEngine`**: Gestión de roles `LEADER` y `FOLLOWER`, transición al anillo por saturación de RAM y elección automática de líder en failover (`trigger_failover_election`).

### 8. Seguridad `JettraJWT` y RBAC (`src/security.rs`)
- Implementación nativa de **FIPS 180-4 SHA-256** + **Base64URL** para tokens `JettraJWT`, superusuario `admin` inmutable (`SUPER_ADMIN`) y permisos granulares por base de datos.

---

## Compilación y Ejecución

```bash
# Ejecutar la suite completa de pruebas unitarias y de integración
cargo test

# Compilar binario optimizado de producción
cargo build --release

# Ejecutar comandos SQL/JQL desde línea de comandos
./target/release/jettrardb --exec "USE factura_db; INSTALL SAMPLE FACTURA; SELECT estado, COUNT(*), SUM(total) FROM facturas GROUP BY estado"

# Iniciar la consola interactiva JettraRDB Shell
./target/release/jettrardb

# Iniciar el servidor distribuido (HTTP REST en 9080 + Raft TCP en 9081)
./target/release/jettrardb --server
```

