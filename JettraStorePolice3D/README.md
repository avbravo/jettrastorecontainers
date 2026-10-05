# JettraStorePolice3D

Proyecto unificado de monitoreo 3D en tiempo real para **JettraStore**, integrando la capa profunda de IA (`jettra-dl`) y el motor gráfico nativo (`jettra-3d-java`) en una sola aplicación interactiva con soporte para submundos cuánticos de nodos servidores, gestión avanzada de conexiones y evaluación policial en vivo por **JettraStorePolice**.

---

## 1. Mapeo de Objetos 3D con Telemetría en Tiempo Real

Todos los elementos y sus movimientos en el mundo 3D no son aleatorios; representan **datos en tiempo real tomados directamente de JettraStore en ejecución**:

| Objeto 3D | Entidad en JettraStore | Comportamiento y Telemetría en Tiempo Real |
|---|---|---|
| **👤 Personas** | **Usuarios Conectados** | Representan las sesiones activas de usuarios conectadas a las bases de datos de JettraStore. Caminan entre sus sedes y los nodos servidores ejecutando consultas (`INSERT`, `SELECT`, `GROUP BY`, `KNN_SEARCH`, `TimeSeries PUSH`) y mostrando en sus pensamientos los resultados y latencias exactas. |
| **🏢 Edificios** | **Zonas y Sedes de Conexión** | Representan los lugares físicos e instalaciones desde donde se conectan los usuarios. Los usuarios se agrupan automáticamente por proximidad de subred IP (`192.168.1.x`, `10.0.4.x`, `172.16.8.x`, `127.0.0.x`) y se concentran en edificios comunes que muestran la cantidad de usuarios activos y el ancho de banda. |
| **🐾 Perros (Caninos)** | **Agentes JettraPolice** | Representan a los agentes guardianes de `JettraPolice` activados en tiempo real en JettraStore: **Heap Sentinel** (vigila saturación Heap y Off-Heap Panama), **Raft Quorum K9** (vigila latidos de réplicas y desvía tráfico ante fallos), **MemTable Purge Dog** (audita flush a SSTables) y **Security Patrol** (audita permisos y autenticación). Patrullan físicamente en círculos alrededor de sus servidores asignados y ladran/alertan en rojo ante cualquier anomalía. |
| **🚚 Camiones** | **Tráfico de Datos del Clúster** | Representan el tráfico de paquetes, transacciones y replicación que ocurre entre los diferentes nodos analizando el clúster de JettraStore en tiempo real. Circulan por las arterias de red transportando lotes de datos con métricas en vivo (MB/s, tipo de replicación Raft, sync de vectores o compactación SSTable). |
| **🖥️ Servidores / Racks** | **Nodos del Clúster JettraStore** | Nodos del clúster (`node-01-master`, `node-02-replica`, etc.) que muestran su estado operativo (En Línea / Fuera de Servicio) y telemetría de memoria. |

---

## 2. Panel de Gestión de Conexiones a JettraStore

La aplicación incluye un panel interactivo completo para administrar las conexiones a JettraStore:

* **Solicitud de Datos**: Solicita `Nombre`, `URL de la Base de Datos` (ej. `tcp://127.0.0.1:8765`), `Usuario` y `Contraseña`.
* **Lista de Conexiones**: Muestra todas las conexiones registradas con indicadores de estado (`[★ DEFAULT]`, `[● EN LÍNEA]`).
* **Operaciones CRUD**: Permite **almacenar** nuevas conexiones, **editar** las existentes y **eliminar** conexiones obsoletas con persistencia automática en `memory/connections.json`.
* **Conexión Predeterminada**: El usuario puede marcar cualquier perfil como **predeterminado** para que se cargue al iniciar la aplicación.
* **Conexión en Caliente**: Botón **`⚡ CONECTAR`** que conmuta inmediatamente la conexión activa del monitor y sincroniza los edificios, usuarios y agentes en tiempo real sin reiniciar la aplicación.
* **Acceso Rápido**: Botón **`🔌 CONEXIONES`** en la barra lateral derecha o mediante la tecla de acceso directo **`K`**.

---

## 3. Expansión a Submundos Interiores de Nodo

* Al **hacer clic** sobre cualquier servidor de la red, el nodo se expande e introduce al operador a su dimensión interna.
* **Bases de Datos Alojadas**: Pedestales 3D con `example_factura_db`, `samples_hostipal_db`, `samples_ambiental_db` y `system_metadata_db`.
* **Panel de Recursos Consumidos**: Métrica en vivo de Heap JVM, Memoria Off-Heap Panama, Hilos Virtuales Loom, MemTable y archivos SSTables.
* **Puerta al Mundo Principal**: Portal monumental para regresar de forma fluida a la ciudad principal al interactuar con él o presionar `ESC`.

---

## 4. Soporte para Topología Multinodo (`cluster.multinode.active`)

JettraStorePolice3D sincroniza su motor visual y monitorización en tiempo real con la propiedad `cluster.multinode.active` definida en `database.properties`:

* **`cluster.multinode.active = on` (Predeterminado):**  
  Modo clúster distribuido activo con algoritmo de consenso integrado (Raft y Dynamic Ring). La barra superior HUD muestra `[MULTINODO: ON]`, y los camiones cuánticos representan la transferencia de datos y réplicas entre nodos.
* **`cluster.multinode.active = off`:**  
  Modo servidor único (*standalone*). Se desactiva el comportamiento de distribución y se asume que todas las operaciones se realizan exclusivamente en el servidor local donde se ejecuta JettraStore sin distribuir los datos. La barra HUD notifica `[STANDALONE: OFF]` y el panel de recursos interior indica que la distribución inter-nodo está deshabilitada.

---

## 5. Controles e Interacción

| Acción | Control |
|---|---|
| **Gestionar Conexiones JettraStore** | Tecla **`K`** o botón `[🔌 CONEXIONES]` en el panel lateral |
| **Sincronizar Mundo en Tiempo Real** | Tecla **`R`** o botón `[RESET JETTRASTORE]` |
| **Expandir y entrar al mundo del nodo** | Clic izquierdo sobre un servidor |
| **Inspeccionar recursos del nodo** | Clic derecho sobre un servidor o pulsar tecla **`N`** |
| **Inspeccionar base de datos 3D** | Clic sobre su pedestal dentro del mundo interior |
| **Atravesar Puerta y regresar** | Clic sobre el Portal 3D, tecla **`ESC`** o botón superior derecho |
| **Activar / Silenciar Voz del Sistema** | Botón `[🔊 VOZ]` en barra lateral (usa `spd-say`) |
| **Moverse / Cámara libre** | Teclas `W`, `A`, `S`, `D` y arrastre con clic derecho |
| **Zoom de cámara** | Rueda del ratón |

---

## 6. Compilación y Ejecución

Compilar y verificar tests unitarios:
```bash
mvn clean test
```

Ejecutar la aplicación nativa en Java 25:
```bash
mvn process-classes org.codehaus.mojo:exec-maven-plugin:3.5.1:exec
# o mediante el script directo:
./buildandrun.sh
```
