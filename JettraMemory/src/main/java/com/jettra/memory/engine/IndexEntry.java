package com.jettra.memory.engine;

import java.io.Serializable;

/**
 * Representa la entrada de metadatos de un registro en el índice físico de JettraMemory.
 * Mapea la clave lógica con la ubicación física exacta en el archivo .jettra sin
 * necesidad de cargar el payload en el Heap de la JVM.
 *
 * @param offset Posición física (en bytes) en el archivo .jettra
 * @param length Longitud total del registro en disco
 * @param payloadLength Longitud del payload de datos binarios
 * @param version Versión del registro
 * @param timestamp Marca de tiempo del registro en milisegundos
 * @param isTombstone Indicador si el registro ha sido marcado como eliminado (Tombstone)
 * @param checksum Checksum CRC32 para validación de integridad
 */
public record IndexEntry(
        long offset,
        int length,
        int payloadLength,
        int version,
        long timestamp,
        boolean isTombstone,
        long checksum
) implements Serializable {

    public static final int SERIALIZED_BYTE_SIZE = 8 + 4 + 4 + 4 + 8 + 1 + 8; // 37 bytes

    public IndexEntry withTombstone() {
        return new IndexEntry(offset, length, payloadLength, version + 1, System.currentTimeMillis(), true, checksum);
    }

    public IndexEntry withNewOffset(long newOffset) {
        return new IndexEntry(newOffset, length, payloadLength, version, timestamp, isTombstone, checksum);
    }
}
