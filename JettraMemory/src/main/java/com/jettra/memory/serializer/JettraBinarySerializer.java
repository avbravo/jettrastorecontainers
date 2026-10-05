package com.jettra.memory.serializer;

import io.jettra.ee.serialization.CompactBinaryHeader;
import io.jettra.ee.serialization.JettraSerialization;
import io.jettra.ee.serialization.JettraSerializedRecord;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Utilidades de serialización binaria ultrarrápida para JettraMemory.
 * Garantiza total compatibilidad con las especificaciones de formatos binarios
 * de JettraStore (NativeMemTable) y JettraEE (CompactBinaryHeader & JettraSerialization).
 */
public final class JettraBinarySerializer {

    private JettraBinarySerializer() {}

    /**
     * Serializa un registro en el formato binario exacto de NativeMemTable de JettraStore:
     * [engineId (1B)][keyLength (4B)][keyBytes (NB)][payloadLength (4B)][payloadBytes (MB)]
     */
    public static byte[] serializeJettraStore(byte engineId, String key, byte[] payload) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int payloadLen = payload != null ? payload.length : 0;
        int totalLen = 1 + 4 + keyBytes.length + 4 + payloadLen;

        byte[] output = new byte[totalLen];
        ByteBuffer buf = ByteBuffer.wrap(output);
        buf.put(engineId);
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(payloadLen);
        if (payloadLen > 0) {
            buf.put(payload);
        }
        return output;
    }

    /**
     * Deserializa un registro binario en formato JettraStore.
     */
    public static JettraStoreBinaryRecord deserializeJettraStore(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        byte engineId = buf.get();
        int keyLen = buf.getInt();
        byte[] keyBytes = new byte[keyLen];
        buf.get(keyBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);

        int payloadLen = buf.getInt();
        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) {
            buf.get(payload);
        }

        return new JettraStoreBinaryRecord(engineId, key, payload, System.currentTimeMillis(), 1);
    }

    /**
     * Serializa un registro compatible con JettraEE y Helidon/Jakarta EE.
     */
    public static byte[] serializeJettraEE(String recordId, int version, long timestamp, byte[] payload) {
        return JettraSerialization.serializeRecord(recordId, version, timestamp, payload);
    }

    /**
     * Deserializa un registro binario de JettraEE.
     */
    public static JettraSerializedRecord deserializeJettraEE(String recordId, byte[] rawBytes) {
        return JettraSerialization.deserializeRecord(recordId, rawBytes);
    }

    /**
     * Serializa un registro a un MemorySegment off-heap de Panama FFM.
     */
    public static MemorySegment serializeToSegment(Arena arena, byte engineId, String key, byte[] payload) {
        byte[] serialized = serializeJettraStore(engineId, key, payload);
        MemorySegment seg = arena.allocate(serialized.length);
        MemorySegment.copy(MemorySegment.ofArray(serialized), 0, seg, 0, serialized.length);
        return seg;
    }
}
