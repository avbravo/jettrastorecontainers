# JettraStoreFX: Manual Maestro de la Suite Gráfica de Escritorio 3D (`book.md`)

**Plataforma de Administración Visual Avanzada en JavaFX, Modelado Tridimensional de Clústeres, Dashboard de Telemetría, Asistentes de Backup/Restore y Consola LQL/SQL**
*Plataforma: JavaFX 25+ / JettraStore Ecosystem 1.0*

---

## 1. Visión General de `JettraStoreFX`

**`JettraStoreFX`** es la consola de administración visual oficial de `JettraStore`. Diseñada con una estética futurista inspirada en centros de mando de operaciones de misión crítica, implementa un motor híbrido de renderizado **2D Glassmorphic + 3D JavaFX Mesh Engine**. Permite a arquitectos de bases de datos, administradores y operadores supervisar en tiempo real la topología del clúster de 3 nodos, la persistencia en archivos `.jettra`, el balanceo en anillo por saturación de RAM, la ejecución interactiva de consultas y la gestión de copias de seguridad.

---

## 2. Pantalla de Bienvenida y Gestión de Perfiles de Conexión

### 2.1 Asistente de Conexión Inicial
Al iniciar `JettraStoreFX`, la interfaz despliega el panel de conexión holográfico:
* **URL de Conexión:** Formato `jettra://<ip-or-host>:<port>` (ej. `jettra://192.168.1.101:9091`).
* **Credenciales Recomendadas por Defecto:**
  * **Usuario:** `admin`
  * **Contraseña:** `admin-jettra`
* **Etiqueta de Entorno:** Permite clasificar la conexión como `PRODUCCIÓN`, `STAGING` o `LABORATORIO`.

### 2.2 Administrador de Múltiples Perfiles
`JettraStoreFX` almacena en un almacén local cifrado (`~/.jettra/fx_profiles.dat`) perfiles de conexión hacia múltiples instancias o clústeres. El usuario puede alternar de entorno con un solo clic en la barra de herramientas superior, manteniendo sesiones concurrentes protegidas por sus respectivos tokens **`JettraJWT`**.

---

## 3. Módulo de Usuarios, Roles y Seguridad Inviolable

### 3.1 Gestión de Cuentas y Privilegios
* Permite crear, modificar y suspender cuentas secundarias asignando roles granulares (`DB_ADMIN`, `DATA_READER`, `ANALYTICS_USER`, `AUDITOR`).
* Los tokens `JettraJWT` se refrescan automáticamente en segundo plano sin interrumpir las sesiones de trabajo del operador.

### 3.2 Protección e Inviolabilidad del Superusuario
La interfaz aplica validaciones visuales y de protocolo estrictas:
* El usuario `admin` aparece identificado con una insignia dorada de **`SUPER_ADMIN (IMMUTABLE)`**.
* Los botones para eliminar usuario, revocar permisos o degradar roles se deshabilitan permanentemente para la cuenta `admin`.
* Incluso si un usuario secundario con rol `DB_ADMIN` intenta ejecutar alteraciones sobre el superusuario a través de la consola integrada, la interfaz captura el error de protocolo y despliega una advertencia en rojo brillante, notificando la alerta registrada por `JettraPolice`.

---

## 4. Gestión Visual de Motores Multimodelo y Operaciones CRUD

La barra lateral de navegación organiza jerárquicamente las bases de datos y los 8 motores:
* **Explorador de Documentos:** Visor tipo árbol interactivo con resaltado de sintaxis JSON y editor de propiedades in-place.
* **Visor de Embeddings Vectoriales:** Gráfico de dispersión 2D/3D con reducción de dimensionalidad PCA/t-SNE para inspeccionar clústeres de similitud semántica.
* **Explorador de Grafos:** Renderizado interactivo de nodos y aristas con física de resortes (*force-directed layout*).
* **Visor de Series Temporales:** Gráficos continuos de alta resolución con agregación dinámica de métricas.
* **Control de Carga Perezosa (*Lazy Load*):** Cada registro con referencias inter o intra-engine muestra una insignia interactiva `[LazyRef: unresolved]`. Al hacer clic sobre ella, la interfaz emite la petición de resolución y despliega la entidad enlazada de forma suave.

---

## 5. Módulo Visual de Respaldo y Restauración (Backup & Restore)

`JettraStoreFX` proporciona asistentes gráficos paso a paso para salvaguardar y recuperar datos sin requerir conocimientos de terminal:

### 5.1 Asistente de Backup Interactivo
1. **Selección de Base de Datos:** Selector desplegable con todas las bases de datos activas en el clúster.
2. **Explorador de Rutas Físicas:** Diálogo nativo para seleccionar la carpeta o disco de destino (ej. `/mnt/san_backup/jettra_snapshots`).
3. **Opciones Avanzadas:** Casillas para compresión ZSTD rápida, inclusión de índices secundarios dispersos y suma de verificación criptográfica CRC64.
4. **Ejecución y Progreso:** Barra de progreso en tiempo real que reporta la congelación de MemTables, el flushing del WAL y el tamaño final de los archivos `.jettra` respaldados.

### 5.2 Asistente de Restauración (Restore Wizard)
1. Selección del archivo `.jettra_bak` o directorio de respaldo.
2. Validación de integridad previa: análisis de firmas `CRC64` y verificación de compatibilidad de versión de formato `.jettra`.
3. Confirmación de montaje y sincronización automática del nuevo estado en los 3 nodos del clúster Raft.

---

## 6. Dashboard de Telemetría y Panel 3D del Clúster

### 6.1 Panel de Métricas de Recursos en Tiempo Real
* **Consumo de Memoria:** Gráficos circulares y lineales diferenciando el Heap de la JVM (ZGC) y la memoria nativa off-heap asignada mediante **Project Panama**.
* **E/S de Almacenamiento `.jettra`:** Tasas de escritura en MemTables, flushes a disco y latencia de compactación en nanosegundos.
* **Feed de Alertas `JettraPolice`:** Panel deslizante que muestra en tiempo real las notificaciones preventivas (picos de saturación, umbrales de disco y advertencias de seguridad).

### 6.2 Visualizador de Clúster en 3D Interactivo
El componente estrella de `JettraStoreFX` es su lienzo tridimensional impulsado por el motor 3D de JavaFX:
* **Representación Espacial:** Los 3 nodos se renderizan como estaciones nodales flotantes en un espacio 3D interactivo con rotación libre, zoom y paneo orbital con el mouse.
* **Identificación Visual:**
  * **Nodo 1 (Líder Primario):** Esfera o prisma central con aura pulsante azul cobalto.
  * **Nodos 2 y 3 (Secundarios):** Estaciones orbitales conectadas por rayos de luz que representan los canales de sincronización Raft vía `jettraGRPC`.
* **Supervisión Individualizada:** Al pasar el cursor o hacer clic sobre cualquier nodo, se despliega una tarjeta holográfica con su IP, puerto, uso exacto de RAM y cantidad de archivos `.jettra` montados.
* **Operaciones Dinámicas:** Botones interactivos para **agregar nuevos nodos** al clúster o desacoplar nodos de forma segura.
* **Animación en Vivo de la Transición al Anillo Distribuido:**
  * Cuando el monitor detecta que el nodo líder alcanza el umbral de saturación de RAM ($\ge 85\%$), el nodo central cambia su iluminación a un tono ámbar de advertencia.
  * Se dispara una animación 3D continua de partículas luminosas que fluyen desde el nodo central hacia los nodos secundarios en forma de anillo circular giratorio.
  * En pantalla se despliega el indicador: `[ESTADO: MIGRACIÓN EN ANILLO DINÁMICO ACTIVA]`.
  * Los operadores observan visualmente cómo la barra de ocupación del nodo principal se reduce hacia el $40\%$, mientras los secundarios absorben equilibradamente los segmentos off-heap, celebrando la continuidad del servicio sin pérdida de rendimiento.

---

## 7. Consola Integrada de Consultas (LQL y JettraSQL)

* Editor de código con resaltado sintáctico dual (SQL y expresiones Lambda de Java).
* Autocompletado de colecciones, campos y funciones especializadas (`VECTOR_COSINE_SIMILARITY`, `TIME_BUCKET`).
* Tabla interactiva de resultados con exportación inmediata a JSON, CSV o formato plano `.jettra`.
* Visor del plan de ejecución (Explain Plan) que reporta el uso de índices secundarios dispersos y filtros de Bloom en nanosegundos.

---

## 9. Paginación Inteligente y Control de Carga en el Explorador de Datos

Para manejar colecciones masivas de datos (como los 200,000 clientes o 1,000,000 facturas de `example_factura_db`), `JettraStoreFX` incorpora una barra de paginación interactiva situada directamente bajo la tabla de registros:

* **Controles de Desplazamiento:**
  * `|<< Primero`: Salta a la página inicial (offset 0).
  * `< Anterior`: Retrocede una página de registros.
  * `Pág. X de Y (Z reg.)`: Indicador dinámico de estado en tiempo real.
  * `Siguiente >`: Avanza a la siguiente página.
  * `Último >>|`: Salta a la última página de la colección.
* **Selector Dinámico de Tamaño de Página:** Menú desplegable con opciones de 10, 25, 50, 100 y 250 registros por lote.
* **Carga Perezosa O(1):** El motor no vuelca la colección completa en el Heap de JavaFX, sino que recupera únicamente el lote activo mediante streaming delimitado, manteniendo la memoria de la interfaz en niveles mínimos y fluidos.

---

## 10. Auditoría de Seguridad, Sentinel Anti-OOM y Streaming Visual con JettraPolice

### 10.1 Recepción Desacoplada de Eventos Sentinel (`JettraPoliceEventListener`)
Al conectarse con cualquier nodo del clúster, `JettraStoreFX` registra un listener de eventos sobre el driver (`client.addPoliceEventListener(...)`) para enterarse de forma transparente cuando el Sentinel interviene en consultas masivas:

```java
client.addPoliceEventListener(notification -> {
    Platform.runLater(() -> {
        // Actualizar barra de estado con mensaje ámbar de advertencia
        statusBarLabel.setText(String.format("🛡️ [Sentinel Activo] Lote seguro: %d filas (Heap: %.1f%%, %d MB libres)",
            notification.safeBatchSize(), notification.heapUsagePercent(), notification.availableMemoryMb()));
        statusBarLabel.setStyle("-fx-text-fill: #F59E0B; -fx-font-weight: bold; -fx-font-size: 11px;");
        // Desplegar notificación flotante Toast
        showToast("🛡️ JettraPolice Sentinel (Anti-OOM):\n" + notification.warningMessage(), "#D97706");
    });
});
```

### 10.2 Notificaciones Flotantes Toast y Barra de Estado
* **Toast Emergente Dinámico:** Cuando una consulta o escaneo amenaza con agotar la memoria Heap de la JVM, aparece en la esquina superior derecha un Toast flotante animado (`FadeTransition`) informando el diagnóstico de seguridad.
* **Barra de Estado Proactiva:** La barra inferior destaca en color ámbar el tamaño del lote seguro adoptado y la memoria libre restante.

### 10.3 Alimentación Incremental de Componentes Visuales (`TableView`)
Para preservar la fluidez total de la interfaz gráfica a 60 fps durante consultas masivas sobre colecciones de cientos de miles de registros:
* La recepción de resultados se consume en streaming por bloques seguros (`chunks` de 50 a 100 filas).
* Cada bloque se añade progresivamente al modelo `ObservableList` de la `TableView` mediante `Platform.runLater`, evitando bloqueos del hilo de renderizado de JavaFX (JavaFX Application Thread).

### 10.4 Centro de Auditoría de Estabilidad (Botón `🛡️ Police`)
Desde la barra de acciones de bases de datos, el botón **`🛡️ Police`** despliega el diálogo de auditoría de estabilidad:
* **Indicador en Tiempo Real de Saturación de Heap:** Barra visual con código de colores (Verde < 60%, Ámbar 60-80%, Rojo > 80%).
* **Historial de Intervenciones:** Listado cronológico de alertas preventivas (ej. `HEAP_EXHAUSTION_PREVENTED`, `CRITICAL_RAM_PRESSURE`).
* **Botón de Inserción Masiva 3M:** Carga en segundo plano la base de datos de ejemplo `example_factura_db` con 3,000,000 de objetos multimodelo conectados mediante JettraRef sin bloquear la interfaz.

---

## 8. Arquitectura Desacoplada con `JettraStoreDriver` y Selector de Modo de Almacenamiento

### 8.1 Comunicación Exclusiva a través de `JettraStoreDriver`
A partir de la versión 1.0+, `JettraStoreFX` opera como un cliente 100% desacoplado que se comunica exclusivamente mediante el conector oficial **`JettraStoreDriver` (`JettraClient`)**:
* **Cero Acoplamiento:** La consola gráfica no accede a clases internas del motor ni a estructuras de bajo nivel en disco.
* **APIs de Alto Rendimiento:** Todas las operaciones de listado, conteo, paginación, inserción y borrado invocan métodos remotos o multiplexados de `JettraClient`.

### 8.2 Selector de Modo de Almacenamiento: `JVM-RAM` vs `DISK-MEMORY (JettraMemory)`
`JettraStoreFX` incorpora un conmutador visual dinámico en la barra superior y en el explorador de datos:
1. **Modo `JVM-RAM` (Memoria RAM Stack & Heap):**
   * Manipulación en memoria de alto rendimiento mediante `UnifiedMap` y `JettraCollections`.
   * Máxima velocidad de consulta y acceso para conjuntos de datos operacionales en caliente.
2. **Modo `DISK-MEMORY` (JettraMemory Off-Heap LSM):**
   * Persistencia y lectura directa en disco fuera del Heap de la JVM mediante Project Panama (FFM API).
   * Elimina la presión del Garbage Collector (*zero GC pressure*) y previene desbordamientos de Heap ante volúmenes masivos.
   * Botón dedicado para ejecutar compactación en caliente de `JettraMemory` desde la pestaña de recursos.

---

## 8. Módulo Visual: Analítica & Cálculo (`🧮 Analítica & Cálculo`)

`JettraStoreFX` incorpora una pestaña especializada de **Analítica & Cálculo** diseñada para científicos de datos, analistas financieros y administradores de bases de datos.

### 8.1 Sección de Agregaciones Multimodelo y GROUP BY
* **Panel de Parámetros Dinámicos:** Permite seleccionar la colección destino (ej. `facturas`), el campo de agrupación (ej. `estado`), la función de agregación (`SUM`, `AVG`, `COUNT`, `MIN`, `MAX`, `MEDIAN`, `IQR`, `STDDEV`) y el campo numérico objetivo (ej. `total`).
* **Visualización de Resultados:** Muestra el desglose de grupos calculados en tiempo real con latencia sub-milisegundo.

### 8.2 Evaluador Matemático Cuantitativo
* **Entrada de Expresiones Aritméticas Complejas:** Admite funciones trigonométricas, raíces cúbicas, factoriales, MCD, MCM e hipotenusas (`cbrt(1000) + sqrt(144) * 2 - hypot(3, 4)`).
* **Badge de Resultados:** Presenta el valor computado con alta precisión flotante y resaltado sintáctico.

### 8.3 Motor Financiero y Tabla de Amortización Francesa
* **Calculadora de Cuota Mensual Fija (PMT):** Determina el pago periódico exacto ingresando préstamo, tasa de interés anual y número de meses.
* **Generador de Cronograma de Amortización Francesa:** Genera una tabla formateada que detalla para cada período la cuota constante, la cuota de capital, la cuota de interés devengada y el saldo insoluto pendiente.
* **Resumen Estadístico Descriptivo:** Procesa muestras numéricas para desplegar media, mediana, moda, desviación estándar, varianza, asimetría (skewness), curtosis, IQR y percentil 95.

### 8.4 Álgebra Vectorial y Similitud de Embeddings (IA)
* **Entrada de Vectores Numéricos:** Permite ingresar vectores multidimensionales en formato `[f1, f2, f3, ...]`.
* **Botones de Operación Inmediata:**
  * **🎯 Similitud Coseno:** Evalúa el coseno del ángulo entre los vectores y su porcentaje de proximidad semántica.
  * **📏 Distancia Euclidiana ($L_2$):** Métrica de proximidad cartesiana.
  * **⚡ Producto Punto:** Producto escalar multidimensional.
  * **✖ Cruz 3D:** Producto vectorial ortogonal para vectores tridimensionales.
  * **📐 Ángulo:** Despliega el ángulo entre ambos vectores en grados sexagesimales y radianes.

### 8.5 Plantillas de Consulta Rápida en la Consola SQL/LQL
En la pestaña **Consola SQL / LQL**, se integraron botones de snippets para insertar con un solo clic consultas modelo:
* `📊 Group By`: `SELECT estado, SUM(total) AS total_ventas, AVG(total) AS promedio, COUNT(*) AS facturas FROM facturas GROUP BY estado;`
* `📐 Math`: `MATH cbrt(64) + sqrt(144) * 2 - hypot(3, 4) + fact(5);`
* `💵 Finanzas`: `FINANCE AMORTIZATION 10000 0.05 12`
* `📈 Estadística`: `STATS SUMMARY 12, 15, 18, 22, 25, 30, 35, 42, 50, 65, 80`
* `🧭 Vector Similitud`: `VECTOR COSINE [0.8, 0.2, 0.5] [0.75, 0.25, 0.45]`
