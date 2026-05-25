package io.github.dfa1.vortex.performance;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/// Write-speed benchmark: Java `VortexWriter` vs JNI vortex writer.
///
/// Run: `java -jar performance/target/benchmarks.jar WriteBenchmark`
///
/// JNI baseline is commented out until bindings are available (TODO.md #10).
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class WriteBenchmark {

    private static final DType.Struct SCHEMA = new DType.Struct(
        List.of("id", "value"),
        List.of(new DType.Primitive(PType.I64, false),
                new DType.Primitive(PType.F64, false)),
        false);

    @Param({"10000", "100000", "1000000"})
    public int rowCount;

    @Param({"1", "10"})
    public int chunkCount;

    private long[]   ids;
    private double[] values;
    private Path     outputFile;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        int chunkSize = rowCount / chunkCount;
        ids    = new long[chunkSize];
        values = new double[chunkSize];
        for (int i = 0; i < chunkSize; i++) {
            ids[i]    = i;
            values[i] = i * 1.5;
        }
        outputFile = Files.createTempFile("vortex-bench-write", ".vtx");
    }

    @TearDown(Level.Invocation)
    public void cleanup() throws IOException {
        Files.deleteIfExists(outputFile);
    }

    @Benchmark
    public void javaWriter() throws IOException {
        try (var ch  = FileChannel.open(outputFile,
                           StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                           StandardOpenOption.TRUNCATE_EXISTING);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            for (int i = 0; i < chunkCount; i++) {
                sut.writeChunk(Map.of("id", ids, "value", values));
            }
        }
    }

    // TODO: enable once JNI bindings are available (see TODO.md #10)
    // @Benchmark
    // public void jniWriter() throws IOException {
    //     VortexJni.write(outputFile, SCHEMA, buildChunks());
    // }
}
