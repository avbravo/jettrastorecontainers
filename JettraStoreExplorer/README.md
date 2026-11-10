# 🚀 JettraStore Explorer

**Gestor Web Empresarial para la Base de Datos Multimodelo JettraStore**  
Desarrollado con componentes **JettraFlux** puros sobre el servidor de aplicaciones **JettraServer** (Java 25 y Project Loom Virtual Threads).

---

## 📋 Resumen del Proyecto

`JettraStoreExplorer` proporciona una consola web interactiva, moderna y profesional para la administración integral de bases de datos multimodelo, clústeres distribuidos, telemetría y operaciones CRUD directas sobre memoria y disco, utilizando la arquitectura de componentes y plantilla reactiva creada en `WebExample` con **JettraFlux**.

---

## ✨ Características Principales

### 1. 🌐 Administración y Monitor de Nodos del Clúster (`/cluster`)
- **Topología en Vivo:** Visualización del quórum Raft y estado de nodos (LEADER, FOLLOWER, EDGE).
- **Métricas por Nodo:** Utilización de CPU, Heap JVM (Loom), Memoria Directa Off-Heap (Panama Foreign Function & Memory), almacenamiento SSTable, QPS y latencia en microsegundos.
- **Acciones Rápidas:**
  - Registro dinámico de nuevos nodos en el clúster.
  - Conmutador de encendido/apagado de nodo en caliente.
  - Expulsión de nodos del quórum con rebalanceo automático.
  - Alternador de modo de clúster (**MULTINODO Raft** vs **STANDALONE Mononodo**).
- **Matriz de Replicación:** Estado de latidos, latencia entre pares y consistencia WAL.

### 2. 🩺 Estatus y Telemetría del Servidor (`/status`)
- **Estado General:** Tiempo de actividad (uptime), cores disponibles y tiempo de arranque en frío.
- **Modo de Almacenamiento Activo:** Soporte configurado para `OFF_HEAP` (Slotted-Pages Panama), `DISK_MEMORY` (LSM SSTable) o `JVM_RAM`.
- **Telemetría IO:** Medición de latencia de lectura/escritura (µs), tasa de compactación y ratio de compresión ZSTD SIMD.
- **Centinelas Guardián JettraPolice:**
  - *Heap Sentinel:* Monitoreo de memoria directa y presión GC cero.
  - *Raft Quorum K9:* Verificación de quórum y consistencia de réplicas.
  - *MemTable Purge Dog:* Auditoría de vaciado de MemTable a SSTables inmutables.
  - *Security Patrol:* Supervisión de tokens y autenticación.
- **Bitácora de Incidentes:** Registro histórico con severidad (OK, WARN, ERROR) y resolución aplicada.

### 3. 🗄️ Administración de Bases de Datos y Motores (`/databases`)
- **Catálogo Maestro:** Resumen KPI de bases de datos, motores configurados, total de objetos y volumen en disco/memoria.
- **Creación de Bases de Datos:** Formulario para registrar nuevas bases de datos con bucket y motor inicial.
- **Gestión Granular de Motores por Base de Datos:**
  - **DOCUMENT:** Almacén documental JSON / BSON.
  - **RELATIONAL:** Almacén relacional estructurado con claves primarias y foráneas.
  - **KEY_VALUE:** Caché clave-valor en memoria de acceso ultra rápido.
  - **GRAPH:** Grafo de conocimiento con nodos, aristas y adyacencias.
  - **VECTOR:** Embeddings vectoriales de alta dimensión con índices HNSW para IA semántica.
  - **TIMESERIES:** Series temporales para métricas IoT con índices BRIN.
  - **COLUMNAR:** Almacén columnar analítico OLAP.
- **Configuración de Índices:** Índices B-Tree, Hash, Vector HNSW, R-Tree espacial y BRIN.

### 4. 📦 Explorador de Registros con Operaciones CRUD (`/explorer`)
- **Navegación Intuitiva:** Selección ágil de Base de Datos y Bucket mediante pestañas estilizadas con colores por motor.
- **Buscador y Filtro:** Filtrado en tiempo real por identificador o texto dentro del payload JSON.
- **Operaciones CRUD Completas:**
  - **Create (➕):** Creación de nuevos registros con generación de ID, resumen y payload JSON formateado.
  - **Read (👁️):** Visor enriquecido con visualización coloreada del documento y metadatos.
  - **Update (✏️):** Modificación en caliente de registros con control automático de versiones (`v1`, `v2`, etc.).
  - **Delete (🗑️):** Eliminación de registros con confirmación previa y ajuste de contadores.

### 5. 💻 Consola Interactiva SQL / JQL (`/query`)
- Ejecución directa de consultas multimodelo JettraSQL y JettraQL sobre cualquier base de datos y colección.
- Plantillas de consulta rápida (filtros numéricos, texto LIKE, expresiones FIND).
- Tiempo de ejecución en microsegundos y tabla dinámica de resultados.

### 6. 🔌 Conexiones, Seguridad y Backups
- **Conexiones (`/connections`):** Gestión de perfiles de conexión TCP/Raft con conexión en caliente y selección de perfil predeterminado.
- **Seguridad (`/users`):** Administración de credenciales y asignación de permisos granulares por base de datos (`ADMIN`, `READ_WRITE`, `READ_ONLY`, `NONE`).
- **Copias de Seguridad (`/backups`):** Generación de snapshots inmutables con compresión ZSTD SIMD / LZ4 Panama, verificación de firmas criptográficas SHA-256 y restauración Zero-Copy.

---

## 🎨 Plantilla Web y Experiencia de Usuario (JettraFlux)

Basada 100% en `FluxTemplateView`:
- **Árbol de Widgets Puro:** Cero HTML manual; construcción declarativa mediante `Div`, `Row`, `Column`, `Header`, `Button`, `Link`, `Badge`, `RawHtml`.
- **Diseño Responsive:** Menú hamburguesa tipo drawer para dispositivos móviles y barra lateral colapsable en escritorio.
- **Conmutador de Tema Claro / Oscuro:** Botón interactivo en el TopBar con persistencia en `localStorage` y adaptación inmediata mediante variables CSS.
- **TopBar de Comando:** Visualización en vivo del modo de almacenamiento, conmutador interactivo de topología multinodo, insignia de conexión activa y menú de usuario.

---

## 🛠️ Requisitos de Compilación y Ejecución

- **JDK:** Java 25 (con `--enable-preview`).
- **Maven:** 3.9 o superior.
- **Frameworks Jettra:** `jettra-server-core`, `jettra-server-flux`, `JettraFlux`, `JettraTest`.

### Compilación:
```bash
mvn clean compile
```

### Ejecución de Pruebas Automatizadas (JettraTest):
```bash
mvn test
```

### Arranque del Servidor:
```bash
mvn exec:java
```
El servidor arrancará por defecto en:
👉 **`http://localhost:8085/`**

Credenciales de acceso iniciales:
- **Usuario:** `admin`
- **Contraseña:** `admin123`

---

## 🐳 Despliegue con Docker

### Construir la imagen Docker:
```bash
docker build -t jettrastore-explorer:latest .
```

### Ejecutar contenedor:
```bash
docker run -d -p 8085:8085 --name jettrastore-explorer jettrastore-explorer:latest
```

