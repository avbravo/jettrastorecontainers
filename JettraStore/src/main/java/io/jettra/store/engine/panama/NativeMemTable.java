package io.jettra.store.engine.panama;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

public final class NativeMemTable implements AutoCloseable {
    public static final byte MAGIC_BYTE_0 = (byte) 0x4A; // 'J'
    public static final byte MAGIC_BYTE_1 = (byte) 0x53; // 'S'

    private final Arena arena;
    private final MemorySegment segment;
    private final long capacityBytes;
    private final AtomicLong writeOffset = new AtomicLong(0);

    public NativeMemTable(long capacityBytes) {
        this.capacityBytes = capacityBytes;
        this.arena = Arena.ofShared();
        this.segment = arena.allocate(capacityBytes, 8); // 8-byte aligned for 64-bit CPU
    }

    public boolean append(byte engineId, byte[] key, byte[] payload) {
        long required = 1L + 4L + key.length + 4L + payload.length;
        long current;
        long next;
        do {
            current = writeOffset.get();
            next = current + required;
            if (next > capacityBytes) {
                return false; // MemTable saturated
            }
        } while (!writeOffset.compareAndSet(current, next));

        long pos = current;
        segment.set(ValueLayout.JAVA_BYTE, pos++, engineId);
        segment.set(ValueLayout.JAVA_INT_UNALIGNED, pos, key.length);
        pos += 4;
        MemorySegment.copy(MemorySegment.ofArray(key), 0, segment, pos, key.length);
        pos += key.length;

        segment.set(ValueLayout.JAVA_INT_UNALIGNED, pos, payload.length);
        pos += 4;
        MemorySegment.copy(MemorySegment.ofArray(payload), 0, segment, pos, payload.length);
        return true;
    }

    public void flushToJettraFile(Path destinationPath) throws IOException {
        long bytesToWrite = writeOffset.get();
        try (FileChannel channel = FileChannel.open(destinationPath, 
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            // Escribir cabecera mágica .jettra
            MemorySegment header = arena.allocate(8);
            header.set(ValueLayout.JAVA_BYTE, 0, MAGIC_BYTE_0);
            header.set(ValueLayout.JAVA_BYTE, 1, MAGIC_BYTE_1);
            header.set(ValueLayout.JAVA_SHORT_UNALIGNED, 2, (short) 1); // Version 1.0
            header.set(ValueLayout.JAVA_INT_UNALIGNED, 4, (int) bytesToWrite);
            channel.write(header.asByteBuffer());

            // Escribir payload off-heap directamente a disco mediante canal NIO
            MemorySegment slice = segment.asSlice(0, bytesToWrite);
            channel.write(slice.asByteBuffer());
        }
    }

    public long getUsedBytes() {
        return writeOffset.get();
    }

    public long getCapacityBytes() {
        return capacityBytes;
    }

    public double getUtilization() {
        return (double) writeOffset.get() / (double) capacityBytes;
    }

    @Override
    public void close() {
        arena.close();
    }
}
