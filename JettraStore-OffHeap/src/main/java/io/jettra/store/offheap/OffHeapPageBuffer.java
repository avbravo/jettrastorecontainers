package io.jettra.store.offheap;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Buffer de páginas ranuradas (Slotted-Page Architecture) de alto rendimiento en memoria nativa off-heap.
 * Opera directamente sobre memoria virtual contigua de 64 KB o 256 KB sin pausas del Garbage Collector.
 */
public final class OffHeapPageBuffer {

    public static final int DEFAULT_PAGE_SIZE = 64 * 1024;    // 64 KB
    public static final int LARGE_PAGE_SIZE = 256 * 1024;     // 256 KB
    public static final long PAGE_MAGIC = 0x4A45545452415047L; // "JETTRAPG" en ASCII hexadecimal

    // Offsets fijos de la cabecera de página (32 bytes alineados a 8 bytes)
    public static final long OFFSET_MAGIC = 0;           // int64 (8 bytes)
    public static final long OFFSET_PAGE_ID = 8;         // int64 (8 bytes)
    public static final long OFFSET_TX_ID = 16;          // int64 (8 bytes)
    public static final long OFFSET_SLOT_COUNT = 24;     // int32 (4 bytes)
    public static final long OFFSET_FREE_POINTER = 28;    // int32 (4 bytes)
    public static final long HEADER_SIZE = 32;

    // Tamaño de cada entrada en el directorio de slots: [Offset (4B), Length (4B)] = 8B
    public static final int SLOT_SIZE = 8;

    private final MemorySegment segment;
    private final int pageSize;

    public OffHeapPageBuffer(Arena arena, long pageId, int pageSize) {
        this.pageSize = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        this.segment = Objects.requireNonNull(arena, "arena requerida").allocate(this.pageSize, 8);

        // Inicializar cabecera nativa
        segment.set(ValueLayout.JAVA_LONG, OFFSET_MAGIC, PAGE_MAGIC);
        segment.set(ValueLayout.JAVA_LONG, OFFSET_PAGE_ID, pageId);
        segment.set(ValueLayout.JAVA_LONG, OFFSET_TX_ID, 0L);
        segment.set(ValueLayout.JAVA_INT, OFFSET_SLOT_COUNT, 0);
        segment.set(ValueLayout.JAVA_INT, OFFSET_FREE_POINTER, this.pageSize); // Crece de atrás hacia adelante
    }

    public OffHeapPageBuffer(MemorySegment existingSegment) {
        this.segment = Objects.requireNonNull(existingSegment, "segmento requerido");
        this.pageSize = (int) existingSegment.byteSize();
        long magic = segment.get(ValueLayout.JAVA_LONG, OFFSET_MAGIC);
        if (magic != PAGE_MAGIC) {
            throw new IllegalArgumentException("Segmento inválido: Magic inválido 0x" + Long.toHexString(magic));
        }
    }

    public long getMagic() {
        return segment.get(ValueLayout.JAVA_LONG, OFFSET_MAGIC);
    }

    public long getPageId() {
        return segment.get(ValueLayout.JAVA_LONG, OFFSET_PAGE_ID);
    }

    public void setPageId(long pageId) {
        segment.set(ValueLayout.JAVA_LONG, OFFSET_PAGE_ID, pageId);
    }

    public long getTransactionId() {
        return segment.get(ValueLayout.JAVA_LONG, OFFSET_TX_ID);
    }

    public void setTransactionId(long txId) {
        segment.set(ValueLayout.JAVA_LONG, OFFSET_TX_ID, txId);
    }

    public int getSlotCount() {
        return segment.get(ValueLayout.JAVA_INT, OFFSET_SLOT_COUNT);
    }

    public int getFreeSpacePointer() {
        return segment.get(ValueLayout.JAVA_INT, OFFSET_FREE_POINTER);
    }

    public int getPageSize() {
        return pageSize;
    }

    /**
     * Calcula los bytes libres contiguos disponibles entre el fin del directorio de slots
     * y el inicio de los registros.
     */
    public synchronized int freeBytes() {
        int slotCount = segment.get(ValueLayout.JAVA_INT, OFFSET_SLOT_COUNT);
        int freePointer = segment.get(ValueLayout.JAVA_INT, OFFSET_FREE_POINTER);
        long slotDirEnd = HEADER_SIZE + ((long) (slotCount + 1) * SLOT_SIZE);
        return (int) Math.max(0, freePointer - slotDirEnd);
    }

    /**
     * Inserta un registro binario copiando desde un segmento de memoria fuente.
     * @return El índice del slot asignado, o -1 si no hay espacio suficiente.
     */
    public synchronized int insertRecord(MemorySegment source, long length) {
        if (length <= 0) {
            throw new IllegalArgumentException("La longitud del registro debe ser mayor a 0: " + length);
        }
        int slotCount = segment.get(ValueLayout.JAVA_INT, OFFSET_SLOT_COUNT);
        int freePointer = segment.get(ValueLayout.JAVA_INT, OFFSET_FREE_POINTER);

        long slotDirEnd = HEADER_SIZE + ((long) (slotCount + 1) * SLOT_SIZE);
        long newFreePointer = freePointer - length;

        if (slotDirEnd > newFreePointer) {
            return -1; // Página llena
        }

        // 1. Copia directa cero-heap al final de la página (crece hacia arriba)
        MemorySegment.copy(source, 0, segment, newFreePointer, length);

        // 2. Escribir entrada en el directorio de slots
        long slotOffset = HEADER_SIZE + ((long) slotCount * SLOT_SIZE);
        segment.set(ValueLayout.JAVA_INT, slotOffset, (int) newFreePointer);
        segment.set(ValueLayout.JAVA_INT, slotOffset + 4, (int) length);

        // 3. Actualizar cabecera
        segment.set(ValueLayout.JAVA_INT, OFFSET_SLOT_COUNT, slotCount + 1);
        segment.set(ValueLayout.JAVA_INT, OFFSET_FREE_POINTER, (int) newFreePointer);

        return slotCount;
    }

    /**
     * Inserta un registro binario a partir de un arreglo de bytes.
     */
    public synchronized int insertRecord(byte[] payload) {
        Objects.requireNonNull(payload, "payload no puede ser nulo");
        MemorySegment src = MemorySegment.ofArray(payload);
        return insertRecord(src, payload.length);
    }

    /**
     * Obtiene una vista directa (Slice cero-copia) del registro en el slot indicado.
     * No realiza copias ni genera objetos temporales en el heap.
     */
    public MemorySegment getRecordSlice(int slotIndex) {
        int slotCount = segment.get(ValueLayout.JAVA_INT, OFFSET_SLOT_COUNT);
        if (slotIndex < 0 || slotIndex >= slotCount) {
            throw new IndexOutOfBoundsException("Slot inválido: " + slotIndex + ", total slots: " + slotCount);
        }

        long slotOffset = HEADER_SIZE + ((long) slotIndex * SLOT_SIZE);
        int recordOffset = segment.get(ValueLayout.JAVA_INT, slotOffset);
        int recordLength = segment.get(ValueLayout.JAVA_INT, slotOffset + 4);

        if (recordLength < 0) {
            return null; // Slot marcado como eliminado (Tombstone)
        }

        return segment.asSlice(recordOffset, recordLength);
    }

    /**
     * Extrae los bytes del registro a un byte[] en caso de interoperabilidad.
     */
    public byte[] getRecordBytes(int slotIndex) {
        MemorySegment slice = getRecordSlice(slotIndex);
        if (slice == null) {
            return null;
        }
        return slice.toArray(ValueLayout.JAVA_BYTE);
    }

    /**
     * Marca un slot como eliminado lógicamente sin compactación inmediata.
     */
    public synchronized boolean markSlotDeleted(int slotIndex) {
        int slotCount = segment.get(ValueLayout.JAVA_INT, OFFSET_SLOT_COUNT);
        if (slotIndex < 0 || slotIndex >= slotCount) {
            return false;
        }
        long slotOffset = HEADER_SIZE + ((long) slotIndex * SLOT_SIZE);
        // Indicador de tombstone: length = -1
        segment.set(ValueLayout.JAVA_INT, slotOffset + 4, -1);
        return true;
    }

    /**
     * Lectura tipada selectiva (Zero-Copy Deserialization): lee un int32 en el offset relativo del slot.
     */
    public int readInt(int slotIndex, long fieldRelativeOffset) {
        MemorySegment slice = getRecordSlice(slotIndex);
        if (slice == null) {
            throw new IllegalStateException("Slot " + slotIndex + " eliminado o vacío.");
        }
        return slice.get(ValueLayout.JAVA_INT_UNALIGNED, fieldRelativeOffset);
    }

    /**
     * Lectura tipada selectiva: lee un int64 en el offset relativo del slot.
     */
    public long readLong(int slotIndex, long fieldRelativeOffset) {
        MemorySegment slice = getRecordSlice(slotIndex);
        if (slice == null) {
            throw new IllegalStateException("Slot " + slotIndex + " eliminado o vacío.");
        }
        return slice.get(ValueLayout.JAVA_LONG_UNALIGNED, fieldRelativeOffset);
    }

    /**
     * Lectura tipada selectiva: lee un float64 en el offset relativo del slot.
     */
    public double readDouble(int slotIndex, long fieldRelativeOffset) {
        MemorySegment slice = getRecordSlice(slotIndex);
        if (slice == null) {
            throw new IllegalStateException("Slot " + slotIndex + " eliminado o vacío.");
        }
        return slice.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, fieldRelativeOffset);
    }

    /**
     * Lectura tipada selectiva: lee una cadena UTF-8 en el offset relativo del slot.
     */
    public String readUtf8String(int slotIndex, long fieldRelativeOffset, int byteLength) {
        MemorySegment slice = getRecordSlice(slotIndex);
        if (slice == null) {
            throw new IllegalStateException("Slot " + slotIndex + " eliminado o vacío.");
        }
        byte[] bytes = slice.asSlice(fieldRelativeOffset, byteLength).toArray(ValueLayout.JAVA_BYTE);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Escribe la página completa directamente a disco mediante un FileChannel NIO de forma contigua.
     */
    public void flush(FileChannel channel, long filePosition) throws IOException {
        ByteBuffer bb = segment.asByteBuffer();
        channel.write(bb, filePosition);
    }

    /**
     * Carga el contenido de la página directamente desde un FileChannel NIO.
     */
    public void load(FileChannel channel, long filePosition) throws IOException {
        ByteBuffer bb = segment.asByteBuffer();
        bb.clear();
        channel.read(bb, filePosition);
    }

    public MemorySegment underlyingSegment() {
        return segment;
    }
}
