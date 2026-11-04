# Módulo `JettraStore-OffHeap`

Módulo de memoria nativa fuera del Heap de la JVM (Off-Heap) para **JettraStore**, diseñado para eliminar las pausas del Garbage Collector en las rutas críticas de almacenamiento, procesamiento transaccional y motores de índices mediante **Project Panama (Foreign Function & Memory API - FFM)** y cabeceras compactas de 8 bytes de **Project Lilliput** en Java 25+.

---

## 1. Arquitectura y Pilares Técnicos

```
┌────────────────────────────────────────────────────────────────────────┐
│                        JettraStore Architecture                        │
├────────────────────────────────────────────────────────────────────────┤
│ JVM Heap: Metadatos ligeros con Cabeceras de 8 Bytes (Project Lilliput)│
│           -XX:+UseCompactObjectHeaders (Ahorro del 20-40% en RAM)      │
├────────────────────────────────────────────────────────────────────────┤
│ OFF-HEAP NATIVE MEMORY (Project Panama / FFM API - java.lang.foreign)  │
│                                                                        │
│   ┌──────────────────────────┐    ┌─────────────────────────────────┐  │
│   │  NativeMemoryAllocator   │    │    JettraTransactionContext     │  │
│   │  (Arena.ofShared/Global) │    │    (Arena.ofConfined / RAII)    │  │
│   └─────────────┬────────────┘    └────────────────┬────────────────┘  │
│                 ▼                                  ▼                   │
│   ┌─────────────────────────────────────────────────────────────────┐  │
│   │                 OffHeapPageBuffer (64KB / 256KB)                │  │
│   │  [Header (32B)] -> [Slot Directory] ... [Tuplas / Binario]      │  │
│   │  Zero-Copy Deserialization mediante VarHandles y ValueLayout    │  │
│   └─────────────────────────────────────────────────────────────────┘  │
│                                  │                                     │
│                                  ▼                                     │
│   ┌─────────────────────────────────────────────────────────────────┐  │
│   │         OffHeapPageManager & NIO Direct FileChannel             │  │
│   └─────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

### 1.1. Cabeceras Compactas de 8 Bytes (Project Lilliput)
En arquitecturas tradicionales x86-64, cada objeto en el heap de la JVM requiere 16 bytes de cabecera (Mark Word + Klass Word). Con **Project Lilliput** (`-XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders`), las cabeceras se reducen a **8 bytes**, ahorrando entre un 20% y un 40% de espacio en memoria para los metadatos de tablas, transacciones y descriptores de página.

### 1.2. Gestión de Memoria Off-Heap mediante `Arena` y `MemorySegment`
Los bloques de datos masivos y páginas de almacenamiento no residen en el Heap de la JVM, sino en regiones de memoria virtual nativa gestionadas por `java.lang.foreign.Arena`:
* **`NativeMemoryAllocator`**: Distribuye memoria con alineación forzada (8 bytes) para optimizar el rendimiento del bus de CPU y SIMD.
* **`OffHeapPageBuffer`**: Implementación de páginas ranuradas (*Slotted-Page Architecture*) de 64 KB o 256 KB. Las inserciones se realizan de atrás hacia adelante mientras el directorio de slots crece hacia abajo.
* **Cero-Copia y Deserialización Selectiva**: Los datos se leen directamente mediante `asSlice()` y `ValueLayout` tipados (`JAVA_INT`, `JAVA_LONG`, `JAVA_DOUBLE`), evitando deserializar objetos enteros en el heap.

### 1.3. Eliminación Determinista sin Garbage Collector (RAII)
Mediante `JettraTransactionContext`, cada transacción o consulta asigna una `Arena.ofConfined()`. Al finalizar la transacción mediante `try-with-resources`, la arena se cierra inmediatamente, devolviendo la memoria nativa al sistema operativo en tiempo \(O(1)\) sin intervención del GC.

---

## 2. Componentes del Módulo

| Clase | Responsabilidad |
|---|---|
| [`NativeMemoryAllocator`](file:///home/avbravo/NetBeansProjects/jettrastack_local/jettrastorecontainers/JettraStore-OffHeap/src/main/java/io/jettra/store/offheap/NativeMemoryAllocator.java) | Asignación masiva de memoria nativa global, compartida y confinada. |
| [`OffHeapPageBuffer`](file:///home/avbravo/NetBeansProjects/jettrastack_local/jettrastorecontainers/JettraStore-OffHeap/src/main/java/io/jettra/store/offheap/OffHeapPageBuffer.java) | Estructura Slotted-Page de 64KB/256KB con cabecera de 32 bytes y directorio de slots. |
| [`JettraTransactionContext`](file:///home/avbravo/NetBeansProjects/jettrastack_local/jettrastorecontainers/JettraStore-OffHeap/src/main/java/io/jettra/store/offheap/JettraTransactionContext.java) | Ámbito transaccional RAII con liberación determinista instantánea. |
| [`OffHeapPageManager`](file:///home/avbravo/NetBeansProjects/jettrastack_local/jettrastorecontainers/JettraStore-OffHeap/src/main/java/io/jettra/store/offheap/OffHeapPageManager.java) | Buffer Pool y persistencia directa a disco mediante canales NIO sin heap. |
| [`OffHeapSlottedMap`](file:///home/avbravo/NetBeansProjects/jettrastack_local/jettrastorecontainers/JettraStore-OffHeap/src/main/java/io/jettra/store/offheap/OffHeapSlottedMap.java) | Colección Clave-Valor orientada a documentos/tuplas en memoria nativa. |

---

## 3. Ejemplo de Uso Transaccional con RAII

```java
// Toda la memoria de la transacción se libera deterministamente al cerrar el bloque
try (JettraTransactionContext tx = new JettraTransactionContext(1001L)) {
    OffHeapPageBuffer page = tx.createPage(1L, OffHeapPageBuffer.DEFAULT_PAGE_SIZE);

    byte[] invoicePayload = "{\"invoiceId\":\"INV-999\",\"amount\":4500.50}".getBytes(StandardCharsets.UTF_8);
    int slot = page.insertRecord(invoicePayload);

    // Lectura Cero-Copia sin alojar objetos en el heap
    MemorySegment slice = page.getRecordSlice(slot);
    System.out.println("Bytes en memoria nativa: " + slice.byteSize());
} // -> transactionArena.close() instantáneo sin pausas de GC
```

---

## 4. Configuración y Flags de Ejecución

Para ejecutar el motor o pruebas unitarias con todas las optimizaciones activas:

```bash
mvn clean test -pl JettraStore-OffHeap
```

Flags requeridos en tiempo de ejecución:
```bash
--enable-preview \
--enable-native-access=ALL-UNNAMED \
-XX:+UnlockExperimentalVMOptions \
-XX:+UseCompactObjectHeaders \
-XX:+UseZGC \
-XX:+GenerationalZGC
```

