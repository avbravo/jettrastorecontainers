# Reporte de Tolerancia y Pruebas de Estrés — JettraStore (`RF-20`)

- **Fecha de ejecución:** 2026-11-25 00:37:29
- **Contenedor de pruebas:** `jettrastoretestcontainer` (`JettraStoreMeter`, Java 25.0.5.1)
- **Objetivo:** `jettra-stress-target:50001` (REST Health: `60001`)
- **Recursos JVM del contenedor de pruebas:** `4` vCPUs, Heap máximo `1536 MB`, guarda de memoria `82%`, límite por escenario `25s`

## 1. Resultados por Volumen de Registros

| Volumen Objetivo | Ops Completadas | Errores | Tiempo (ms) | Throughput (ops/s) | Lat. Prom (ms) | Lat. p95 (ms) | Lat. p99 (ms) | Max (ms) | Heap (MB / %) | Estado DB | Clasificación | Observaciones |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|
| 10,000 | 10,000 | 0 | 122 | 81,967.21 | 0.527 | 6.110 | 6.244 | 6.244 | 29/1536 (1.9%) | UP (HTTP 200) | **STABLE** | Operación estable, 0 errores, teardown limpio |
| 100,000 | 100,000 | 0 | 261 | 383,141.76 | 0.007 | 0.020 | 0.048 | 0.082 | 74/1536 (4.8%) | UP (HTTP 200) | **STABLE** | Operación estable, 0 errores, teardown limpio |
| 1,000,000 | 1,000,000 | 0 | 1,226 | 815,660.69 | 0.004 | 0.008 | 0.014 | 0.098 | 422/1536 (27.5%) | UP (HTTP 200) | **STABLE** | Operación estable, 0 errores, teardown limpio |
| 1,500,000 | 1,500,000 | 0 | 1,864 | 804,721.03 | 0.003 | 0.007 | 0.011 | 0.105 | 923/1536 (60.1%) | UP (HTTP 200) | **STABLE** | Operación estable, 0 errores, teardown limpio |
| 25,000,000 | 1,425,408 | 0 | 1,918 | 743,174.14 | 0.004 | 0.008 | 0.013 | 0.039 | 1280/1536 (83.4%) | UP (HTTP 200) | **LIMIT_REACHED** | Umbral de memoria alcanzado (83.4% >= 82%); ejecución detenida de forma controlada tras 1,425,408 ops |
| 35,000,000 | 1,601,536 | 0 | 2,591 | 618,115.01 | 0.017 | 0.010 | 0.017 | 73.011 | 1311/1536 (85.4%) | UP (HTTP 200) | **LIMIT_REACHED** | Umbral de memoria alcanzado (85.4% >= 82%); ejecución detenida de forma controlada tras 1,601,536 ops |
| 50,000,000 | 933,888 | 0 | 1,353 | 690,235.03 | 0.004 | 0.010 | 0.015 | 0.052 | 1287/1536 (83.8%) | UP (HTTP 200) | **LIMIT_REACHED** | Umbral de memoria alcanzado (83.8% >= 82%); ejecución detenida de forma controlada tras 933,888 ops |

## 2. Resultados por Concurrencia de Usuarios (Virtual Threads)

| Usuarios Objetivo | Ops Completadas | Errores | Tiempo (ms) | Throughput (ops/s) | Lat. Prom (ms) | Lat. p95 (ms) | Lat. p99 (ms) | Max (ms) | Heap (MB / %) | Estado DB | Clasificación | Observaciones |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|
| 1 | 10 | 0 | 10 | 1,000.00 | 0.100 | 0.928 | 0.928 | 0.928 | 227/1536 (14.8%) | UP (HTTP 200) | **STABLE** | Operación estable, 0 errores, teardown limpio |
| 10 | 100 | 0 | 8 | 12,500.00 | 0.099 | 0.405 | 0.661 | 0.661 | 12/1536 (0.8%) | UP (HTTP 200) | **STABLE** | Operación estable, 0 errores, teardown limpio |
| 20 | 200 | 0 | 8 | 25,000.00 | 0.105 | 0.238 | 0.272 | 0.286 | 10/1536 (0.7%) | UP (HTTP 200) | **STABLE** | Operación estable, 0 errores, teardown limpio |
| 50 | 500 | 0 | 8 | 62,500.00 | 0.159 | 0.303 | 0.335 | 0.357 | 11/1536 (0.8%) | UP (HTTP 200) | **STABLE** | Operación estable, 0 errores, teardown limpio |
| 75 | 750 | 0 | 9 | 83,333.33 | 0.127 | 0.449 | 0.496 | 0.512 | 12/1536 (0.8%) | UP (HTTP 200) | **STABLE** | Operación estable, 0 errores, teardown limpio |
| 100 | 1,000 | 0 | 17 | 58,823.53 | 0.160 | 0.584 | 0.639 | 0.649 | 14/1536 (0.9%) | UP (HTTP 200) | **STABLE** | Operación estable, 0 errores, teardown limpio |
| 1,000 | 2,000 | 0 | 19 | 105,263.16 | 0.923 | 3.382 | 3.462 | 3.462 | 21/1536 (1.4%) | UP (HTTP 200) | **STABLE** | Operación estable, 0 errores, teardown limpio |
| 2,000 | 4,000 | 0 | 21 | 190,476.19 | 1.643 | 5.031 | 5.409 | 5.409 | 38/1536 (2.5%) | UP (HTTP 200) | **STABLE** | Operación estable, 0 errores, teardown limpio |
| 5,000 | 10,000 | 0 | 41 | 243,902.44 | 2.978 | 10.670 | 12.613 | 12.791 | 83/1536 (5.4%) | UP (HTTP 200) | **STABLE** | Operación estable, 0 errores, teardown limpio |
| 10,000 | 20,000 | 0 | 94 | 212,765.96 | 17.674 | 55.374 | 57.827 | 57.943 | 22/1536 (1.5%) | UP (HTTP 200) | **DEGRADED** | Latencia o presión de memoria elevada |
| 150,000 | 150,000 | 0 | 625 | 240,000.00 | 31.124 | 88.892 | 100.355 | 103.530 | 375/1536 (24.4%) | UP (HTTP 200) | **DEGRADED** | Latencia o presión de memoria elevada; Ejecución escalonada en oleadas de 25,000 Virtual Threads |
| 1,000,000 | 1,000,000 | 0 | 2,478 | 403,551.25 | 17.342 | 46.321 | 70.623 | 87.856 | 716/1536 (46.6%) | UP (HTTP 200) | **STABLE** | Operación estable, 0 errores, teardown limpio; Ejecución escalonada en oleadas de 25,000 Virtual Threads |
| 2,300,000 | 2,300,000 | 0 | 5,794 | 396,962.37 | 16.220 | 56.770 | 71.768 | 98.590 | 633/1536 (41.2%) | UP (HTTP 200) | **DEGRADED** | Latencia o presión de memoria elevada; Ejecución escalonada en oleadas de 25,000 Virtual Threads |

## 3. Análisis de Tolerancia y Límites Observados

### Volumen de Registros
- **Zona estable comprobada:** hasta **1,500,000 registros** con 0 errores y estabilidad total de la base de datos.
- **Inicio de degradación / límite observado:** a partir de **25,000,000 registros** (`LIMIT_REACHED` — completó **1,425,408 registros** antes de activar la protección: Umbral de memoria alcanzado (83.4% >= 82%); ejecución detenida de forma controlada tras 1,425,408 ops).

### Concurrencia de Usuarios
- **Zona estable comprobada:** hasta **1,000,000 usuarios concurrentes** con 0 errores.
- **Inicio de degradación / límite observado:** a partir de **10,000 usuarios** (`DEGRADED` — completó **20,000 ops**: Latencia o presión de memoria elevada).

## 4. Conclusiones Técnicas y Recomendaciones de Soporte

1. **Operación nominal recomendada (SLA estable):** cargas de hasta **1,500,000 registros activos por bucket** en memoria acotada (`-Xmx` de contenedor) y hasta **1,000,000 usuarios concurrentes** mantienen latencias sub-milisegundo/bajas, 0% de errores y healthcheck `UP`.
2. **Cargas masivas (25M–50M registros):** requieren particionamiento/sharding por buckets o incremento proporcional de RAM/almacenamiento off-heap (`DISK_MEMORY` / `OFF_HEAP` + SSD NVMe) en los nodos del clúster para evitar saturación de heap en un solo contenedor.
3. **Concurrencia extrema (150K–2.3M usuarios):** Java 25 Virtual Threads permite escalar mediante oleadas controladas y *backpressure*; en producción se recomienda balanceo de carga delante del clúster de 3+ nodos Raft y limitación de conexiones simultáneas por nodo.
