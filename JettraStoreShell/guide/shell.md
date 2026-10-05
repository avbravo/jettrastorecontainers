# JettraStoreShell: Guía Completa de la Consola Interactiva de Línea de Comandos (`shell.md`)

**Manual Operativo, Administración de Seguridad JettraJWT, Comandos CRUD, Gestión de Clúster, Backup y Restore**
*Versión de Shell: 1.0 | Compatible con JettraStore Cluster*

---

## 1. Visión General de `JettraStoreShell`

**`JettraStoreShell`** es la interfaz interactiva de terminal (CLI) oficial para la administración, depuración, mantenimiento y consulta en tiempo real de clústeres `JettraStore`. Construida en Java 25+, cuenta con autocompletado ANSI, soporte de historial de comandos, modo interactivo con menús guiados y conexión cifrada punto a punto mediante tokens **`JettraJWT`**.

---

## 2. Inicio y Conexión Segura

### 2.1 Sintaxis de Ejecución
Para iniciar el shell interactivo:
```bash
# Conexión interactiva solicitando credenciales
java -jar JettraStoreShell.jar --host 192.168.1.101 --port 9091

# Conexión directa mediante parámetros
java -jar JettraStoreShell.jar --host localhost --port 9091 -u admin -p admin-jettra
```

### 2.2 Autenticación con el Superusuario por Defecto
Al conectar por primera vez, el sistema solicita autenticación:
```text
================================================================================
                    __     __  __               _____ __          __  
                    \ \   / / / /              / ____|\ \        / /  
                     \ \_/ / / /_             | (___   \ \  /\  / /   
                      \   / / __ \    ______   \___ \   \ \/  \/ /    
                       | | / /_/ /   |______|  ____) |   \  /\  /     
                       |_|/_.___/             |_____/     \/  \/      
                                  JETTRASTORE SHELL v1.0
================================================================================
Connecting to JettraStore node at 127.0.0.1:9091... Connected via jettraGRPC.
Enter Username: admin
Enter Password: ************
[AUTH] Authenticated successfully as 'admin' (Role: SUPER_ADMIN).
[AUTH] JettraJWT session token issued [Valid: 24h]. Type 'help' or 'menu'.

admin@jettra-cluster:primary> 
```

---

## 3. Menú Interactivo y Asistente de Instalación de Ejemplos

Al ejecutar el comando `menu` en el prompt, el shell despliega un menú interactivo numerado:

```text
admin@jettra-cluster:primary> menu

========================= JETTRASTORE INTERACTIVE MENU =========================
  [1] Instalar Base de Datos de Ejemplos ('sample_enterprise_db')
  [2] Administrar Bases de Datos y Motores
  [3] Gestionar Usuarios y Permisos de Seguridad (JettraJWT)
  [4] Ejecutar Respaldo Inmediato (Hot Backup)
  [5] Restaurar Base de Datos desde Archivos .jettra
  [6] Inspeccionar Métricas de Clúster y Memoria Off-Heap
  [7] Salir del Menú Interactivo
================================================================================
Seleccione una opción [1-7]: 1

[SAMPLES] Provisionando base de datos 'sample_enterprise_db'...
[SAMPLES] Creando Motor Document: 'products' (10,000 registros)... [OK]
[SAMPLES] Creando Motor Vectorial: 'product_embeddings' (512-dim)... [OK]
[SAMPLES] Creando Motor Grafos: 'product_categories' (Vertices/Edges)... [OK]
[SAMPLES] Creando Motor TimeSeries: 'sales_telemetry'... [OK]
[SAMPLES] Estableciendo referencias cruzadas Inter-Engine con Lazy Load... [OK]
[SUCCESS] Base de datos de ejemplo instalada exitosamente en archivos .jettra.
```

### 3.2 Carga Masiva de Ejemplo de Facturación Multimodelo (`LOAD SAMPLE example_factura_db`)
Para pruebas de estrés y validación multimodelo de 3,000,000 de objetos:
```text
admin@jettra-cluster:primary> LOAD SAMPLE example_factura_db

==============================================================================================
        CARGA MASIVA EXITOSA: BASE DE DATOS 'example_factura_db' (3,000,000 OBJETOS)
==============================================================================================
[OK] Tiempo de Inserción y Timbrado Multimodelo: 5051 ms (Java 25 Virtual Threads)
[OK] Objetos Repartidos en 9 Buckets Especializados:
  * [DOCUMENT]   'facturas'              : 1,000,000 facturas electrónicas timbradas
  * [DOCUMENT]   'detalles_factura'      : 1,000,000 renglones/items vinculados
  * [DOCUMENT]   'clientes'              :   200,000 clientes empresariales con RFC/RUC
  * [KEYVALUE]   'cache_folios'          :   300,000 folios fiscales en caché ultrarrápida
  * [VECTOR]     'factura_embeddings'    :   200,000 vectores 3D indexados para IA
  * [GRAPH]      'red_comercial'         :   200,000 vértices conectados (clientes -> facturas)
  * [TIMESERIES] 'volumen_facturacion'   :    50,000 métricas históricas de facturación
  * [GEOSPATIAL] 'sucursales_fiscales'   :    25,000 puntos GIS de sucursales emisoras
  * [COLUMNAR]   'analitica_fiscal'      :    25,000 filas de cálculo analítico de IVA/Totales
----------------------------------------------------------------------------------------------
GRAN TOTAL EN 'example_factura_db': 3,000,000 objetos multimodelo conectados mediante JettraRef.
Índices Creados: idx_fac_cliente (HASH), idx_cli_rfc (BTREE)
Base de datos activa conmutada a: 'example_factura_db'
==============================================================================================
```
* **Garantía Anti-OOM:** Las inserciones se despachan en lotes (*chunks*) acotados de 25,000 a 50,000 registros mediante `UnifiedMap` de `JettraCollection`.
* **Zero `.toArray()`:** La indexación secundaria sobre 1,200,000 campos se ejecuta por streaming directo (`forEach`), evitando duplicaciones masivas en memoria.

### 3.3 Carga Masiva de Muestra Hospitalaria Multimodelo (`LOAD SAMPLE samples_hostipal_db`)
Para pruebas de estrés orientadas al sector salud con 2,000,000 de objetos:
```text
admin@jettra-cluster:primary> LOAD SAMPLE samples_hostipal_db

==============================================================================================
        CARGA MASIVA EXITOSA: BASE DE DATOS 'samples_hostipal_db' (2,000,000 OBJETOS)
==============================================================================================
[OK] Tiempo de Inserción y Procesamiento: 3410 ms (Java 25 Virtual Threads)
[OK] Objetos Repartidos en 11 Buckets Multimodelo:
  * [DOCUMENT]   'pacientes'                  :   500,000 pacientes con historial y referencias
  * [DOCUMENT]   'afecciones'                 :   400,000 afecciones clínicas y sintomatología
  * [DOCUMENT]   'medicamentos'               :   200,000 fármacos con principio activo y dosis
  * [DOCUMENT]   'enfermedades'               :   100,000 diagnósticos con código CIE-10
  * [DOCUMENT]   'hospitales'                 :    50,000 centros y hospitales con camas y UCI
  * [KEYVALUE]   'inventario_medicamentos'    :   300,000 registros de stock y disponibilidad
  * [VECTOR]     'sintomas_embeddings'        :   200,000 embeddings 3D para triaje predictivo
  * [GRAPH]      'red_hospitalaria'           :   100,000 relaciones internamiento / pabellones
  * [TIMESERIES] 'telemetria_signos_vitales'  :   100,000 lecturas continuas de ritmo/presión
  * [GEOSPATIAL] 'ubicacion_hospitales'       :    25,000 coordenadas geográficas de centros
  * [COLUMNAR]   'analitica_costos_salud'     :    25,000 filas de cálculo analítico de costos
----------------------------------------------------------------------------------------------
GRAN TOTAL EN 'samples_hostipal_db': 2,000,000 objetos multimodelo conectados mediante JettraRef.
Base de datos activa conmutada a: 'samples_hostipal_db'
==============================================================================================
```

### 3.4 Carga Masiva de Muestra Ambiental Mundial (`LOAD SAMPLE samples_ambiental_db`)
Para monitoreo climático y de emisiones mundiales con 3,000,000 de objetos:
```text
admin@jettra-cluster:primary> LOAD SAMPLE samples_ambiental_db

==============================================================================================
        CARGA MASIVA EXITOSA: BASE DE DATOS 'samples_ambiental_db' (3,000,000 OBJETOS)
==============================================================================================
[OK] Tiempo de Inserción y Procesamiento: 4890 ms (Java 25 Virtual Threads)
[OK] Objetos Repartidos en 11 Buckets Multimodelo:
  * [DOCUMENT]   'mediciones_calidad_aire'    : 1,000,000 mediciones (AQI, PM2.5, PM10, CO2)
  * [DOCUMENT]   'estaciones_meteorologicas'  :   200,000 estaciones de monitoreo mundial
  * [DOCUMENT]   'fuentes_emision'            :   200,000 industrias y plantas emisoras
  * [DOCUMENT]   'reservas_naturales'         :   100,000 reservas, biomas y parques
  * [DOCUMENT]   'especies_afectadas'         :   100,000 registros de biodiversidad
  * [KEYVALUE]   'cache_alertas_ambientales'  :   400,000 alertas globales en caché
  * [VECTOR]     'patrones_climaticos_embeddings': 300,000 vectores 3D de atmósfera/presión
  * [GRAPH]      'red_corredores_biologicos'  :   200,000 enlaces entre reservas y estaciones
  * [TIMESERIES] 'temperatura_global_telemetria': 300,000 puntos temporales de temperatura
  * [GEOSPATIAL] 'coordenadas_estaciones'     :   100,000 coordenadas GIS globales
  * [COLUMNAR]   'analitica_emisiones_anuales':   100,000 filas de cálculo analítico de CO2
----------------------------------------------------------------------------------------------
GRAN TOTAL EN 'samples_ambiental_db': 3,000,000 objetos multimodelo conectados mediante JettraRef.
Base de datos activa conmutada a: 'samples_ambiental_db'
==============================================================================================
```

---

## 4. Comandos de Administración de Usuarios y Seguridad

### 4.1 Creación de Usuarios Secundarios
```sql
CREATE USER 'operator_user' IDENTIFIED BY 'SecretPass#2026' ROLE 'DB_ADMIN';
```

### 4.2 Restricción Absoluta del Superusuario `admin`
Cualquier intento por parte de un usuario que no sea el superusuario para alterar los privilegios de `admin` será inmediatamente bloqueado:
```text
operator@jettra-cluster> ALTER USER 'admin' ROLE 'READ_ONLY';
[SECURITY ERROR] Security violation: Superuser privileges cannot be altered, modified, or revoked by secondary accounts.
[JettraPolice ALERT] Incident logged: Unauthorized role modification attempt on 'admin'.
```

### 4.3 Cambio Recomendado de Contraseña de `admin`
```sql
ALTER USER 'admin' IDENTIFIED BY 'SuperSecureK3y#2026!';
```

---

## 5. Gestión de Bases de Datos y Motores

```sql
-- Creación de una nueva base de datos
CREATE DATABASE financial_analytics;

-- Conmutación de contexto de base de datos
USE financial_analytics;

-- Creación de motores específicos
CREATE ENGINE transactions TYPE DOCUMENT;
CREATE ENGINE customer_vectors TYPE VECTOR DIMENSIONS 768 METRIC COSINE;
CREATE ENGINE fraud_graph TYPE GRAPH;
CREATE ENGINE stock_ticks TYPE TIMESERIES;

-- Listar motores y bases de datos activas
SHOW DATABASES;
SHOW ENGINES;
```

---

## 6. Operaciones CRUD, Paginación y Carga Perezosa

### 6.1 Inserción de Documentos y Referencias
```sql
INSERT INTO transactions (_id, amount, currency, customer_id, _ref_vector) 
VALUES ('tx_9901', 4500.00, 'USD', 'cust_12', 'vector::customer_vectors#emb_cust_12');
```

### 6.2 Control de Carga Perezosa (*Lazy Load*) y Paginación
```text
-- Configurar la sesión para no resolver referencias automáticamente (ahorro de RAM)
SET LAZY_LOAD = TRUE;

-- Mostrar u ocultar descriptores de referencias cruzadas en el output
SET SHOW_REFERENCES = ON;

-- Paginación interactiva
SET PAGE_SIZE = 25;

-- Consulta con paginación
SELECT _id, amount, currency, _ref_vector 
FROM transactions 
WHERE amount > 1000.00 
ORDER BY amount DESC 
LIMIT 25 OFFSET 0;
```

---

## 7. Comandos Nativos de Backup y Restore

### 7.1 Copia de Seguridad Instantánea (`BACKUP DATABASE`)
Congela las MemTables y genera una copia atómica de los archivos `.jettra` en el directorio de destino configurado o especificado:
```sql
BACKUP DATABASE financial_analytics TO '/backup/financial_analytics_snapshot.jettra_bak';
```
*Salida de confirmación:*
```text
[BACKUP] Initiating atomic flush of MemTable and WAL...
[BACKUP] Copying SSTables and Bloom Filters (.jettra) off-heap...
[SUCCESS] Backup completed in 68ms. Target: /backup/financial_analytics_snapshot.jettra_bak (2.4 GB).
```

### 7.2 Restauración de Base de Datos (`RESTORE DATABASE`)
Restaura una base de datos validando los checksums criptográficos `CRC64` y reconstruyendo los índices dispersos:
```sql
RESTORE DATABASE financial_analytics FROM '/backup/financial_analytics_snapshot.jettra_bak';
```
*Salida de confirmación:*
```text
[RESTORE] Validating .jettra binary blocks... OK
[RESTORE] Checking Raft consensus log synchronization... OK
[RESTORE] Rebuilding sparse secondary index structures... OK
[SUCCESS] Database 'financial_analytics' restored successfully and mounted in cluster.
```

---

## 8. Comando de Ayuda (`help` / `?`)

Al escribir `help` o `?`, el shell despliega la lista completa de sintaxis clasificada:

```text
admin@jettra-cluster:primary> help

============================= JETTRASTORE SHELL HELP ============================
SINTAXIS GENERAL:
  help, ?                         Muestra esta guía de comandos.
  menu                            Abre el menú interactivo guiado.
  exit, quit                      Cierra la sesión actual de forma segura.

ADMINISTRACIÓN DE SEGURIDAD (JettraJWT):
  CREATE USER <name> IDENTIFIED BY '<pass>' ROLE '<role>'
  ALTER USER <name> IDENTIFIED BY '<pass>'
  DROP USER <name>
  SHOW USERS

BASES DE DATOS Y ENGINES:
  CREATE DATABASE <dbname>
  DROP DATABASE <dbname>
  USE <dbname>
  SHOW DATABASES
  CREATE ENGINE <name> TYPE <DOCUMENT|VECTOR|GRAPH|TIMESERIES|COLUMNAR|KEYVALUE|GEO|RECORD>
  SHOW ENGINES

CONSULTAS Y MUTACIONES (JettraSQL / LQL):
  SELECT <cols> FROM <engine> [WHERE ...] [ORDER BY ...] [LIMIT n OFFSET m]
  INSERT INTO <engine> (<cols>) VALUES (<vals>)
  UPDATE <engine> SET <col> = <val> [WHERE ...]
  DELETE FROM <engine> [WHERE ...]

DIRECTIVAS DE RENDIMIENTO:
  SET LAZY_LOAD = TRUE | FALSE    Activa o desactiva la resolución diferida de punteros.
  SET SHOW_REFERENCES = ON | OFF  Muestra/oculta referencias cruzadas inter-engine.
  SET PAGE_SIZE = <num>           Establece el tamaño de página para visualización.

RESPALDO Y RECUPERACIÓN (BACKUP & RESTORE):
  BACKUP DATABASE <dbname> TO '<path>'
  RESTORE DATABASE <dbname> FROM '<path>'

CLÚSTER Y TELEMETRÍA:
  SHOW NODES                      Muestra los nodos del anillo y el estado de cluster.multinode.active.
  SHOW CLUSTER                    Muestra los 3 nodos, líder Raft y estado del anillo.
  SHOW MEMORY                     Muestra uso de Heap JVM y memoria nativa Panama.
  MULTINODE                       Consulta si cluster.multinode.active está en ON u OFF.
  MULTINODE ON / MULTINODE OFF    Activa o desactiva el comportamiento de distribución por consenso.
================================================================================
```

---

## 10. Sistema de Paginación Interactiva de Consultas en Consola

Para consultar colecciones con alta densidad de registros (como los 200,000 clientes o 1,000,000 facturas de `example_factura_db`), `JettraStoreShell` incorpora una barra de paginación interactiva y un conjunto completo de comandos de desplazamiento:

### 10.1 Comandos de Navegación Paginada
| Comando | Atajo | Función |
| :--- | :---: | :--- |
| `PAGE_SIZE <n>` | `SIZE <n>` | Configura el tamaño del lote por página (ej. `PAGE_SIZE 25`, `PAGE_SIZE 50`). |
| `FIRST` / `PRIMERO` | `P` / `|<<` | Salta inmediatamente a la primera página de la consulta activa. |
| `PREV` / `ANTERIOR` | `A` / `<` | Retrocede a la página anterior de resultados. |
| `NEXT` / `SIGUIENTE` | `S` / `>` | Avanza a la siguiente página de resultados. |
| `LAST` / `ULTIMO` | `U` / `>>|` | Salta a la última página de la consulta. |
| `PAGE <n>` / `PAGINA <n>` | - | Salta directamente al número de página indicado. |

### 10.2 Ejemplo Visual de Paginación en Consola
```text
jettra-shell [admin@127.0.0.1:9091/example_factura_db]> select * from clientes
=== JETTRASQL RESULTADO (0 ms) ===
🛡️  [JETTRAPOLICE SENTINEL: INTERVENCIÓN PREVENTIVA DE MEMORIA HEAP]
   Estrategia: Streaming perezoso (Lazy Load) con distribución por lotes seguros.
   Diagnóstico: [JettraPolice SENTINEL: Paginación Lazy Anti-OOM Activada] 25 fila(s) retornada(s)
+--------------+----------------+----------------------------------+------------------+-----------------+
| _id          | limite_credito | razon_social                     | ciudad           | rfc_tax_id      |
+--------------+----------------+----------------------------------+------------------+-----------------+
| cli_1        | 100000.0       | Corporación Comercial 1 S.A.     | Ciudad de Panamá | RFC-PAN-1000001 |
| cli_2        | 85000.0        | Industrias del Pacífico 2 S.A.   | David, Chiriquí  | RFC-PAN-1000002 |
...
+------------------------------------------------------------------------------------------------------+
|  PÁGINA [ 1 / 8000 ]  •  Mostrando filas 1 - 25 de 200,000 total  •  Tamaño de página: 25            |
|  Navegación: [P]RIMERO (|<<)  •  [A]NTERIOR (<)  •  [S]IGUIENTE (>)  •  [U]LTIMO (>>|)                 |
|  Comandos: 'SIGUIENTE' | 'ANTERIOR' | 'PRIMERO' | 'ULTIMO' | 'PAGE <n>' | 'PAGE_SIZE <n>'           |
+------------------------------------------------------------------------------------------------------+

jettra-shell [admin@127.0.0.1:9091/example_factura_db]> S
=== JETTRASQL RESULTADO (0 ms) ===
... (Mostrando filas 26 a 50 de forma instantánea sin impacto en Heap)
```
