# JettraStoreMeter: Guía de Arquitectura de Pruebas de Carga, Estrés Masivo y Teardown Automatizado (`book.md`)

**Planes de Rendimiento Extremo con Apache JMeter y Virtual Threads Java 25, Simulación Multiusuario con JettraJWT, Workloads Multimodelo y Ciclo de Vida de Limpieza**  
*Herramienta: Apache JMeter 5.6+ / JettraStore Ecosystem CLI*

---

## 1. Visión General de `JettraStoreMeter`

**`JettraStoreMeter`** es el framework especializado de validación de carga, estrés y rendimiento del ecosistema `JettraStore`. Diseñado para someter a bases de datos multimodelo a condiciones extremas de concurrencia e inyección de datos en memoria off-heap (Panama FFM) y almacenamiento directo `.jettra`, permite:
* Simular **concurrencia masiva desde 5 hasta 500 usuarios simultáneos** mediante Virtual Threads de Java 25 y grupos de hilos de Apache JMeter.
* Evaluar cargas continuas en ventanas de tiempo sostenido de **10, 25, 35, 45 y 60 minutos**.
* Inyectar y consultar **millones de registros multimodelo** en tiempo real: documentos BSON, índices secundarios B-Tree y Hash, embeddings vectoriales con similitud coseno, series temporales continuas, grafos y registros clave-valor en memoria de acceso ultra rápido.
* Evaluar las bases de datos masivas preconfiguradas:
  * **`samples_hostipal_db`**: 2,000,000 de objetos de salud clínica, pacientes, CIE-10, medicamentos y telemetría vital.
  * **`samples_ambiental_db`**: 3,000,000 de objetos de sensores mundiales, calidad del aire AQI, reservas naturales y emisiones.
  * **`example_factura_db`**: 3,000,000 de objetos de facturación electrónica y folios fiscales.
* Forzar el comportamiento preventivo del umbral del $85\%$ de memoria RAM para verificar la estabilidad de las MemTables y la transición hacia el anillo distribuido (*Consistent Ring Topology*).
* Ejecutar un **ciclo de vida de limpieza riguroso (*Teardown*)**, restaurando el estado base del almacenamiento físico.

---

## 2. Arquitectura de Simulación y Autenticación con `JettraJWT`

Cada hilo (*Thread*) en los planes de JMeter o worker en Virtual Threads simula un cliente independiente que interactúa con la base de datos:

```
                 [ Apache JMeter Test Plan / JettraMeter CLI ]
                                       │
            ┌──────────────────────────┴──────────────────────────┐
            ▼                                                     ▼
 [ Thread Group: Escritores / Ingesta ]        [ Thread Group: Lectores / Analítica ]
      (5, 10, 25, 50, 100, 500 hilos)              (5, 10, 25, 50, 100, 500 hilos)
            │                                                     │
            ├─► [ HTTP Header Manager: Bearer JettraJWT ]          ├─► [ HTTP Header Manager: Bearer JettraJWT ]
            │                                                     │
            ▼                                                     ▼
    POST /api/v1/doc/insert                                POST /api/v1/query/sql
    POST /api/v1/vector/search                             GET  /api/v1/kv/get
            │                                                     │
            └──────────────────────────┬──────────────────────────┘
                                       │
                                       ▼
                       [ JettraStore Cluster / Node ]
                 (MemTable Off-Heap Panama + Storage .jettra)
```

### Gestión de Tokens en JMeter
Antes de iniciar los bucles de prueba masiva, un `setUp Thread Group` ejecuta una solicitud de autenticación con las credenciales maestras:
```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "admin-jettra"
}
```
La respuesta extrae la propiedad `token` mediante un *JSON Extractor* y la asigna a la variable global `${JETTRA_TOKEN}`, la cual se inyecta automáticamente en las cabeceras `Authorization: JettraJWT ${JETTRA_TOKEN}` de cada petición subsecuente.

---

## 3. Catálogo de Bases de Datos de Muestra y Cargas Masivas

### 3.1 `samples_hostipal_db` (2,000,000 Objetos)
Base de datos multimodelo orientada a sistemas integrados de salud y triaje hospitalario inteligente:

| Motor | Colección / Bucket | Registros (2M) | Descripción y Propósito |
|---|---|---|---|
| **Document** | `pacientes` | $500,000$ | Pacientes con datos demográficos, tipo de sangre, estado clínico y referencias inter-engine. |
| **Document** | `afecciones` | $400,000$ | Cuadros sintomáticos y afecciones observadas en triaje médico con intensidad de dolor. |
| **Document** | `medicamentos` | $200,000$ | Fármacos con principio activo, dosis miligramo, laboratorio y precio unitario. |
| **Document** | `enfermedades` | $100,000$ | Catálogo de patologías diagnosticadas con código CIE-10 internacional y categoría. |
| **Document** | `hospitales` | $50,000$ | Centros de atención hospitalaria con capacidad de camas, nivel y salas UCI. |
| **KeyValue** | `inventario_medicamentos` | $300,000$ | Caché de stock y disponibilidad de fármacos en farmacias hospitalarias. |
| **Vector** | `sintomas_embeddings` | $200,000$ | Vectores 3D de sintomatología clínica para similitud semántica y triaje asistido. |
| **Graph** | `red_hospitalaria` | $100,000$ | Relaciones dirigidas de internamiento entre pacientes y pabellones hospitalarios. |
| **TimeSeries** | `telemetria_signos_vitales` | $100,000$ | Registro continuo de ritmo cardíaco, presión arterial y saturación de oxígeno. |
| **Geospatial** | `ubicacion_hospitales` | $25,000$ | Coordenadas geoespaciales precisas de centros médicos y hospitales. |
| **Columnar** | `analitica_costos_salud` | $25,000$ | Filas analíticas vectorizadas de costos de tratamiento, cobertura y copagos. |
| **TOTAL** | **11 Buckets** | **2,000,000** | **Objetos interconectados con referencias JettraRef e índices B-Tree/Hash.** |

---

### 3.2 `samples_ambiental_db` (3,000,000 Objetos)
Base de datos multimodelo con datos ambientales mundiales para monitoreo climático en tiempo real:

| Motor | Colección / Bucket | Registros (3M) | Descripción y Propósito |
|---|---|---|---|
| **Document** | `mediciones_calidad_aire` | $1,000,000$ | Mediciones de índice AQI, partículas PM2.5, PM10, CO2 ppm y clasificación de aire. |
| **Document** | `estaciones_meteorologicas` | $200,000$ | Estaciones globales de monitoreo por continente, país y tipo de sensor atmosférico. |
| **Document** | `fuentes_emision` | $200,000$ | Plantas industriales, termoeléctricas y complejos con huella de carbono anual. |
| **Document** | `reservas_naturales` | $100,000$ | Parques nacionales, reservas de la biosfera y áreas protegidas de biodiversidad. |
| **Document** | `especies_afectadas` | $100,000$ | Registros de fauna y flora con estado de conservación (Vulnerable, En Peligro). |
| **KeyValue** | `cache_alertas_ambientales` | $400,000$ | Llaves de acceso rápido para alertas tempranas por superación de umbrales AQI. |
| **Vector** | `patrones_climaticos_embeddings` | $300,000$ | Vectores 3D de presión barométrica y patrones de circulación atmosférica. |
| **Graph** | `red_corredores_biologicos` | $200,000$ | Red de enlaces y corredores biológicos entre reservas y estaciones de vigilancia. |
| **TimeSeries** | `temperatura_global_telemetria` | $300,000$ | Serie temporal continua de anomalías térmicas y registros de temperatura mundial. |
| **Geospatial** | `coordenadas_estaciones` | $100,000$ | Coordenadas GIS mundiales de toda la red de estaciones meteorológicas. |
| **Columnar** | `analitica_emisiones_anuales` | $100,000$ | Columnas vectorizadas de cálculo analítico de emisiones de gases y créditos de carbono. |
| **TOTAL** | **11 Buckets** | **3,000,000** | **Objetos interconectados con referencias JettraRef e índices B-Tree/Hash.** |

---

## 4. Matriz de Simulación Multiusuario y Tiempos de Carga Sostenida

`JettraStoreMeter` permite simular con precisión matemática y granularidad por segundo las siguientes combinaciones de concurrencia y duración:

### Matriz de Concurrencia
* **5 Usuarios Concurrentes:** Carga base para verificación funcional y latencia mínima.
* **10 Usuarios Concurrentes:** Carga estándar departamental.
* **25 Usuarios Concurrentes:** Flujo concurrente medio multimodelo.
* **50 Usuarios Concurrentes:** Carga de alta concurrencia con escrituras e índices paralelos.
* **100 Usuarios Concurrentes:** Saturación de red y validación de MemTables concurrentes.
* **500 Usuarios Concurrentes:** Estrés extremo evaluado mediante Virtual Threads de Java 25.

### Matriz de Duración Sostenida
| Duración | Segundos | Milisegundos | Propósito de Validación |
|---|---|---|---|
| **10 Minutos** | $600\text{ s}$ | $600,000\text{ ms}$ | Prueba rápida de estabilidad térmica y saturación de MemTable. |
| **25 Minutos** | $1,500\text{ s}$ | $1,500,000\text{ ms}$ | Ciclo de rotación de WAL y primer ciclo de compactación SSTable. |
| **35 Minutos** | $2,100\text{ s}$ | $2,100,000\text{ ms}$ | Evaluación de degradación de latencia y comportamiento off-heap prolongado. |
| **45 Minutos** | $2,700\text{ s}$ | $2,700,000\text{ ms}$ | Prueba de resistencia (Soak Test) intermedia con transiciones de anillo. |
| **60 Minutos** | $3,600\text{ s}$ | $3,600,000\text{ ms}$ | Carga masiva sostenida completa de 1 hora (*Production Readiness Benchmark*). |

---

## 5. Planes de Pruebas Apache JMeter (`plans/`)

Los planes `.jmx` incluidos en el directorio `plans/` están completamente parametrizados mediante funciones `${__P(propiedad, defecto)}`:

1. **`plans/jettra_hospital_stress_test.jmx`:**
   * Simula flujos de admisión de pacientes, consultas B-Tree por código CIE-10, búsqueda de similitud coseno vectorial de síntomas, lecturas de stock en caché KeyValue e ingesta de telemetría médica.
2. **`plans/jettra_ambiental_stress_test.jmx`:**
   * Simula ingesta continua de sensores de aire, búsquedas de estaciones meteorológicas por continente, búsquedas semánticas vectoriales climáticas y verificación de alertas ambientales.
3. **`plans/jettra_stress_test.jmx`:**
   * Plan general multimodelo con inserciones BSON, queries JettraSQL y validación del anillo distribuido.
4. **`plans/jettra_memory_stress_test.jmx`:**
   * Plan de estrés off-heap directo hacia almacenamiento persistente `JettraMemory`.

---

## 6. Ejecución de Pruebas desde Consola (CLI)

`JettraStoreMeter` ofrece múltiples alternativas para ejecutar las pruebas desde consola:

### 6.1 Vía Script Automatizado (`run_meter.sh`)
El script principal `run_meter.sh` detecta automáticamente si Apache JMeter está instalado en el sistema operativo; de lo contrario, delega la carga al motor nativo ultrarrápido de Virtual Threads:

```bash
# Otorgar permisos de ejecución si es necesario
chmod +x run_meter.sh

# 1. Ejecutar prueba sobre la base hospitalaria (50 usuarios durante 10 minutos)
./run_meter.sh --plan hospital --users 50 --duration 10m

# 2. Ejecutar prueba sobre la base ambiental mundial (100 usuarios durante 25 minutos)
./run_meter.sh --plan ambiental --users 100 --duration 25m

# 3. Ejecutar prueba sobre la base de facturación (500 usuarios durante 35 minutos)
./run_meter.sh --plan factura --users 500 --duration 35m

# 4. Ejecutar plan JMeter especificando el archivo .jmx
./run_meter.sh --jmx plans/jettra_hospital_stress_test.jmx --users 50 --duration 600s

# 5. Iniciar menú interactivo en consola
./run_meter.sh --interactive
```

### 6.2 Vía Maven Exec Plugin (`mvn exec:java`)
Puede ejecutarse en cualquier entorno donde Maven y Java 25 estén instalados sin dependencias externas:

```bash
# Ejecución directa con paso de argumentos
mvn exec:java -Dexec.args="--plan hospital --users 50 --duration 10m"
mvn exec:java -Dexec.args="--plan ambiental --users 100 --duration 25m"
mvn exec:java -Dexec.args="--help"
```

### 6.3 Vía Apache JMeter Nativo en Modo Non-GUI (`jmeter -n -t`)
Para entornos donde el binario `jmeter` se encuentra en el PATH del sistema o para integración continua (CI/CD):

```bash
# Ejecutar plan hospitalario con generación de reporte HTML y log JTL
jmeter -n -t plans/jettra_hospital_stress_test.jmx \
       -l results/run_hospital_$(date +%Y%m%d_%H%M%S).jtl \
       -e -o results/html_dashboard/ \
       -Jhost=127.0.0.1 \
       -Jport=8080 \
       -Jthreads=100 \
       -Jduration=1500

# O utilizar el script auxiliar incluido:
./plans/run_jmeter_console.sh plans/jettra_ambiental_stress_test.jmx 100 1500
```

---

## 7. Ciclo de Vida de Limpieza Automatizada (*Teardown*)

Para garantizar que ninguna prueba contamine el clúster o degrade el almacenamiento en disco:
1. **Teardown en Memoria:** Purgado ordenado de MemTables off-heap y estructuras volátiles.
2. **Teardown Físico (`teardown.sh`):**
   ```bash
   ./plans/teardown.sh --host 127.0.0.1 --port 9091 --db meter_stress_db
   ```
   Elimina archivos temporales `.jettra`, logs transaccionales WAL y snapshots temporales generados durante la prueba.

---

## 8. Criterios de Aceptación de Rendimiento y SLAs Validados

| Métrica / Indicador | Meta Requerida | Resultado Obtenido (`samples_hostipal_db`) | Resultado Obtenido (`samples_ambiental_db`) |
|---|---|---|---|
| **Tasa de Error Global** | $< 0.01\%$ | **$0.00\%$ (0 Fallos)** | **$0.00\%$ (0 Fallos)** |
| **Throughput Multimodelo** | $> 150,000\text{ ops/s}$ | **$> 1,900,000\text{ ops/s}$** | **$> 1,900,000\text{ ops/s}$** |
| **Latencia Media** | $< 5.0\text{ ms}$ | **$0.012 - 0.246\text{ ms}$** | **$0.011 - 0.260\text{ ms}$** |
| **Latencia Percentil 95 (p95)** | $< 10.0\text{ ms}$ | **$0.016 - 0.332\text{ ms}$** | **$0.015 - 0.351\text{ ms}$** |
| **Escalabilidad a 500 Usuarios** | Sin bloqueos de hilos | **Superado (Virtual Threads)** | **Superado (Virtual Threads)** |
| **Limpieza Física de Almacenamiento** | $100\%$ purgado | **Verificado por Teardown** | **Verificado por Teardown** |
