//! # JettraRDB
//!
//! Motor de Base de Datos Multimodelo Distribuido de Alto Rendimiento escrito en Rust.
//! Diseñado a partir de la arquitectura de **JettraStore**, proporcionando:
//!
//! 1. **8 Motores Multimodelo por Base de Datos (`engines`)**:
//!    - **Documental (`DocumentEngine`)**: Documentos JSON/Map, cursores perezosos (`StreamResponse`) y referencias cruzadas (`JettraRef`).
//!    - **Clave-Valor (`KeyValueEngine`)**: Almacenamiento binario de ultra-baja latencia.
//!    - **Vectorial (`VectorEngine`)**: Embeddings `f32`, búsqueda por similitud Coseno y distancia Euclidiana Top-K.
//!    - **Grafos (`GraphEngine`)**: Grafo dirigido de propiedades con vértices y aristas etiquetadas.
//!    - **Series de Tiempo (`TimeSeriesEngine`)**: Puntos métricos ordenados por timestamp (`BTreeMap<i64, f64>`).
//!    - **Geoespacial (`GeospatialEngine`)**: Coordenadas GIS con búsqueda radial Haversine.
//!    - **Columnar OLAP (`ColumnarEngine`)**: Columnas numéricas y de texto orientadas a analítica.
//!    - **Registros Tipados (`RecordsEngine`)**: Registros estructurados e inmutables de auditoría.
//! 2. **Almacenamiento Nativo y SSTables (`storage`)**:
//!    - `NativeMemTable` sin recolector de basura (Zero-GC) y archivos binarios `.jettra` (`0x4A53` `"JS"`).
//!    - `BackupManager` con snapshots en caliente y verificación CRC32.
//! 3. **Indexación Secundaria (`index`)**:
//!    - Índices `HASH` y `BTREE` con soporte de unicidad y búsqueda por rangos.
//! 4. **Motores de Cálculo, Finanzas, Estadística y Álgebra Vectorial (`calc`)**:
//!    - `JettraMath`, `JettraFinance`, `JettraStatistics`, `JettraVectorMath` y `JettraAggregation`.
//! 5. **Procesadores de Consultas (`query`)**:
//!    - `JettraSQLProcessor` (SQL extendido) y `JettraQLProcessor` (JQL multimodelo).
//! 6. **Centinela Autónomo de Memoria (`police`)**:
//!    - `JettraPolice` para prevención predictiva de agotamiento de RAM y paginación perezosa.
//! 7. **Clúster Distribuido Raft & Anillo Dinámico (`cluster`)**:
//!    - `DynamicRingEngine`, protocolo binario `JettraClusterProtocol` (`0x4A54` `"JT"`), tramas `JettraRaftFrame`, replicación Primaria/Secundaria y failover automático.
//! 8. **Seguridad RBAC y Tokens (`security`)**:
//!    - `JettraSecurityManager` con firma SHA-256 `JettraJWT`, superusuario `admin` inmutable y roles por base de datos.

pub mod calc;
pub mod cluster;
pub mod config;
pub mod database;
pub mod engines;
pub mod index;
pub mod json;
pub mod police;
pub mod query;
pub mod security;
pub mod server;
pub mod storage;

