package io.github.dfa1.vortex.performance;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.WriteRegistry;
import io.github.dfa1.vortex.writer.encode.EncodeContext;
import io.github.dfa1.vortex.writer.encode.EncodeResult;
import io.github.dfa1.vortex.writer.encode.PcoEncodingEncoder;
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
import org.openjdk.jmh.annotations.Warmup;

import java.lang.foreign.Arena;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/// vortex-java's Pco encoder alone, one column of 1M values per call, over the shapes that take
/// its different paths: Classic without delta (`randomI64`, `priceF64`), Classic with
/// consecutive delta (`timestampI64`) and IntMult (`centsI64`, multiples of 100 plus noise-free
/// small offsets).
///
/// Run: `./bench PcoEncodeBenchmark.javaEncode`
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@State(Scope.Benchmark)
public class PcoEncodeBenchmark {

    private static final int N = 1 << 20;

    @Param({"randomI64", "timestampI64", "centsI64", "priceF64"})
    public String shape;

    private final PcoEncodingEncoder sut = new PcoEncodingEncoder();
    private final WriteRegistry registry = WriteRegistry.loadAll();
    private DType dtype;
    private Object data;

    @Setup(Level.Trial)
    public void setup() {
        SplittableRandom rng = new SplittableRandom(42);
        switch (shape) {
            case "randomI64" -> {
                dtype = new DType.Primitive(PType.I64, false);
                long[] v = new long[N];
                for (int i = 0; i < N; i++) {
                    v[i] = rng.nextLong(1_000_000_000L);
                }
                data = v;
            }
            case "timestampI64" -> {
                dtype = new DType.Primitive(PType.I64, false);
                long[] v = new long[N];
                long t = 1_700_000_000_000L;
                for (int i = 0; i < N; i++) {
                    t += rng.nextInt(1_000);
                    v[i] = t;
                }
                data = v;
            }
            case "centsI64" -> {
                dtype = new DType.Primitive(PType.I64, false);
                long[] v = new long[N];
                for (int i = 0; i < N; i++) {
                    v[i] = rng.nextLong(100_000) * 100 + (rng.nextInt(10) == 0 ? 99 : 0);
                }
                data = v;
            }
            case "priceF64" -> {
                dtype = new DType.Primitive(PType.F64, false);
                double[] v = new double[N];
                for (int i = 0; i < N; i++) {
                    v[i] = Math.round((50 + rng.nextDouble() * 100) * 100) / 100.0;
                }
                data = v;
            }
            default -> throw new IllegalArgumentException(shape);
        }
        System.out.printf("[PcoEncodeBenchmark] %s: %,d B%n", shape, javaEncode());
    }

    @Benchmark
    public long javaEncode() {
        try (Arena arena = Arena.ofConfined()) {
            EncodeResult result = sut.encode(dtype, data, EncodeContext.of(arena, registry));
            long bytes = 0;
            for (var buffer : result.buffers()) {
                bytes += buffer.byteSize();
            }
            return bytes;
        }
    }
}
