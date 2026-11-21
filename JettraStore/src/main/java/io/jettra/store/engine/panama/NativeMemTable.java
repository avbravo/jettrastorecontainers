package io.jettra.store.engine.panama;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

public final class NativeMemTable implements AutoCloseable {
    public static final byte MAGIC_BYTE_0 = (byte) 0x4A; // 'J'
    public static final byte MAGIC_BYTE_1 = (byte) 0x53; // 'S'

    private volatile Arena arena;
    private volatile MemorySegment segment;
    private final long capacityBytes;
    private final AtomicLong writeOffset = new AtomicLong(0);

    public NativeMemTable(long capacityBytes) {
        this.capacityBytes = Math.max(1024L, capacityBytes);
    }

    private MemorySegment ensureAllocated() {
        MemorySegment seg = this.segment;
        if (seg != null) {
            return seg;
        }
        synchronized (this) {
            if (this.segment == null) {
                this.arena = Arena.ofShared();
                this.segment = this.arena.allocate(capacityBytes, 8); // 8-byte aligned for 64-bit CPU
            }
            return this.segment;
        }
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

        MemorySegment seg = ensureAllocated();
        long pos = current;
        seg.set(ValueLayout.JAVA_BYTE, pos++, engineId);
        seg.set(ValueLayout.JAVA_INT_UNALIGNED, pos, key.length);
        pos += 4;
        MemorySegment.copy(MemorySegment.ofArray(key), 0, seg, pos, key.length);
        pos += key.length;

        seg.set(ValueLayout.JAVA_INT_UNALIGNED, pos, payload.length);
        pos += 4;
        MemorySegment.copy(MemorySegment.ofArray(payload), 0, seg, pos, payload.length);
        return true;
    }

    public void flushToJettraFile(Path destinationPath) throws IOException {
        long bytesToWrite = writeOffset.get();
        try (FileChannel channel = FileChannel.open(destinationPath, 
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            // Escribir cabecera mágica .jettra sin asignar memoria adicional en el Arena nativo
            ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
            header.put(MAGIC_BYTE_0);
            header.put(MAGIC_BYTE_1);
            header.putShort((short) 1); // Version 1.0
            header.putInt((int) bytesToWrite);
            header.flip();
            channel.write(header);

            // Escribir payload off-heap directamente a disco mediante canal NIO
            MemorySegment seg = this.segment;
            if (bytesToWrite > 0 && seg != null) {
                MemorySegment slice = seg.asSlice(0, bytesToWrite);
                channel.write(slice.asByteBuffer());
            }
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
    public synchronized void close() {
        if (arena != null) {
            arena.close();
            arena = null;
            segment = null;
        }
    }
}
