package io.github.dfa1.vortex.performance;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.CodecRegistry;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanOptions;
import io.github.dfa1.vortex.scan.ScanResult;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/// Read-speed benchmark: Java `VortexReader` + `ScanIterator` vs JNI vortex reader.
///
/// Run: `java -jar performance/target/benchmarks.jar ReadBenchmark`
///
/// JNI baseline is commented out until bindings are available (TODO.md #11).
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class ReadBenchmark {

    private static final DType.Struct SCHEMA = new DType.Struct(
        List.of("id", "value"),
        List.of(new DType.Primitive(PType.I64, false),
                new DType.Primitive(PType.F64, false)),
        false);

    @Param({"10000", "100000", "1000000"})
    public int rowCount;

    @Param({"1", "10"})
    public int chunkCount;

    @Param({"all", "id"})
    public String projection;

    private Path            inputFile;
    private CodecRegistry registry;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        inputFile = Files.createTempFile("vortex-bench-read", ".vtx");
        registry  = CodecRegistry.loadAll();

        int      chunkSize = rowCount / chunkCount;
        long[]   ids       = new long[chunkSize];
        double[] values    = new double[chunkSize];
        for (int i = 0; i < chunkSize; i++) {
            ids[i]    = i;
            values[i] = i * 1.5;
        }

        try (var ch  = FileChannel.open(inputFile,
                           StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                           StandardOpenOption.TRUNCATE_EXISTING);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            for (int i = 0; i < chunkCount; i++) {
                sut.writeChunk(Map.of("id", ids, "value", values));
            }
        }
    }

    @TearDown(Level.Trial)
    public void cleanup() throws IOException {
        Files.deleteIfExists(inputFile);
    }

    @Benchmark
    public long javaReader() throws IOException {
        ScanOptions opts = "all".equals(projection)
            ? ScanOptions.all()
            : ScanOptions.columns("id");

        long rows = 0;
        try (var vf   = VortexReader.open(inputFile, registry);
             var iter = vf.scan(opts)) {
            while (iter.hasNext()) {
                ScanResult r = iter.next();
                rows += r.rowCount();
            }
        }
        return rows;
    }

    // TODO: enable once JNI bindings are available (see TODO.md #11)
    // @Benchmark
    // public long jniReader() throws IOException {
    //     return VortexJni.countRows(inputFile);
    // }
}
