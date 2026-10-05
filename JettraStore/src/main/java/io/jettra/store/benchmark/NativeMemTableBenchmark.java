package io.jettra.store.benchmark;

import io.jettra.store.engine.panama.NativeMemTable;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
public class NativeMemTableBenchmark {

    private NativeMemTable memTable;
    private byte[] sampleKey;
    private byte[] samplePayload;

    @Setup(Level.Trial)
    public void setup() {
        memTable = new NativeMemTable(64 * 1024 * 1024); // 64 MB
        sampleKey = "benchmark_key_1001".getBytes();
        samplePayload = "{\"event\":\"telemetry\",\"val\":99.8,\"status\":\"OK\"}".getBytes();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        memTable.close();
    }

    @Benchmark
    public boolean testOffHeapAppend() {
        return memTable.append((byte) 1, sampleKey, samplePayload);
    }

    public static void runBenchmark() throws Exception {
        Options opt = new OptionsBuilder()
                .include(NativeMemTableBenchmark.class.getSimpleName())
                .forks(1)
                .warmupIterations(1)
                .measurementIterations(2)
                .build();
        new Runner(opt).run();
    }
}
