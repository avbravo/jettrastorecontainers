# JVM 3D Matrix — Monitor y Orquestador 3D de Aplicaciones Java en Golang

**JVM 3D Matrix** es una aplicación desarrollada en el lenguaje **Golang** con una interfaz futurista **estilo 3D (WebGL / Three.js)** para visualizar en tiempo real todas las máquinas virtuales Java (JVM) en ejecución en el sistema, permitiendo:

1. **Visualizar en 3D** cada proceso Java como un nodo holográfico interactivo con métricas en tiempo real (PID, uso de CPU %, consumo de memoria RAM, hilos, flags de JVM y directorio de trabajo).
2. **Detener aplicaciones (Stop / Kill)**: envía señales controladas (`SIGTERM` para apagado elegante o `SIGKILL` forzado). Al detenerse, el nodo 3D pasa a modo inactivo ("Detenida") preservando sus parámetros y rutas.
3. **Iniciar / Reanudar aplicaciones (Start / Restart)**: permite reanudar o reiniciar en 1 clic cualquier proceso Java previamente detenido, ejecutándolo de nuevo con exactamente los mismos argumentos, variables y directorio de trabajo original.
4. **Lanzar nuevas aplicaciones Java**: soporte para iniciar cualquier aplicación JAR o clase personalizada desde la interfaz.
5. **Generador de Aplicación Demo**: permite compilar y ejecutar con un solo clic un microservicio Java de prueba para experimentar con el ciclo de vida sin alterar aplicaciones en producción.

---

## 🚀 Arquitectura y Tecnologías

- **Backend (Golang 1.23+)**:
  - `internal/process/detector.go`: Motor de escaneo ultrarrápido basado en el kernel Linux (`/proc`), extrayendo línea de comandos (`cmdline`), memoria RSS/VMS (`status`), cálculo diferencial de CPU (`stat`), directorio de trabajo (`cwd`) e identificación de clases/JAR.
  - `internal/process/manager.go`: Gestor concurrente con seguridad de hilos (`sync.RWMutex`), persistencia en memoria de aplicaciones activas y detenidas, y control de señales POSIX para inicio/parada.
  - `internal/demo/demo.go`: Generador y compilador dinámico de microservicios Java de prueba.
  - `main.go`: Servidor HTTP con endpoints REST y Streaming en tiempo real (**Server-Sent Events - SSE**) con empaquetado autocontenido de recursos (`//go:embed web/*`).
- **Frontend 3D (WebGL / Three.js)**:
  - Escena espacial Cyberpunk con suelo de cuadrícula holográfica reactiva, anillos de energía concentricos y partículas de datos flotantes.
  - Nodos 3D tipo *Server Blade*: base hexagonal metálica, cristal flotante con animación senoidal de levitación y anillos orbitales cuya velocidad de giro representa el consumo de CPU.
  - Balizas de luz vertical y etiquetas 2D/3D (sprites) con renderizado nítido en canvas.
  - Cámara orbital interactiva con modos predefinidos: Isométrica, Libre y Cenital, más auto-rotación 360°.
  - Panel lateral *Holo-Inspector* con medidores animados, chips de argumentos JVM (`-Xmx`, `-XX:`, etc.) y botones de control.

---

## 📦 Estructura del Proyecto

```
RQ-1-20261009T154635Z-1-001/
├── go.mod                     # Módulo Go
├── main.go                    # Servidor HTTP, SSE y API REST
├── start.sh                   # Script de compilación y arranque rápido
├── internal/
│   ├── process/
│   │   ├── types.go           # Estructuras de datos (JavaApp, SystemStats, etc.)
│   │   ├── detector.go        # Escáner de /proc y clasificador de apps Java
│   │   └── manager.go         # Gestor de ciclo de vida (Start, Stop, Restart)
│   └── demo/
│       └── demo.go            # Compilador y ejecutor de apps Java de prueba
└── web/
    ├── index.html             # Interfaz web y HUD 3D
    ├── styles.css             # Estilos Cyberpunk / Glassmorphism
    ├── app.js                 # Lógica Three.js, raycasting y orquestación
    └── lib/
        ├── three.min.js       # Three.js (incrustado localmente, sin internet)
        ├── OrbitControls.js   # Control de cámara orbital
        └── tween.umd.js       # Motor de interpolación para transiciones de cámara
```

---

## 🛠️ Cómo Compilar y Ejecutar

### Opción 1: Usando el script `start.sh` (Recomendado)
```bash
chmod +x start.sh
./start.sh
```

### Opción 2: Compilación y Ejecución Manual con Go
```bash
# Compilar el binario ejecutable
go build -o javaview3d .

# Ejecutar (abrirá automáticamente el navegador en http://localhost:8989)
./javaview3d

# Opcional: especificar otro puerto o modo sin navegador automático
./javaview3d -port 9090 -no-browser
```

Una vez iniciado, abre tu navegador en:
👉 **[http://localhost:8989](http://localhost:8989)**

---

## 🎮 Controles de la Interfaz 3D

| Acción | Control |
|---|---|
| **Rotar cámara** | Clic izquierdo y arrastrar el ratón |
| **Zoom (acercar/alejar)** | Rueda de desplazamiento del ratón (Scroll) |
| **Panorámica (desplazar)** | Clic derecho y arrastrar |
| **Inspeccionar Aplicación** | Clic sobre cualquier nodo o pedestal 3D |
| **Cerrar Inspector** | Tecla `ESC` o botón `✕` |
| **Detener Aplicación** | Botón rojo **🛑 Detener** en el inspector |
| **Iniciar Aplicación** | Botón verde **▶️ Iniciar** en el inspector |
| **Reiniciar Aplicación** | Botón amarillo **🔄 Reiniciar** en el inspector |
| **Modos de Cámara** | Botones flotantes inferiores: *Isométrica*, *Orbital*, *Superior*, *Auto-Giro* |
| **Filtrar Procesos** | Barra superior de búsqueda (filtra por nombre o PID en tiempo real) |

