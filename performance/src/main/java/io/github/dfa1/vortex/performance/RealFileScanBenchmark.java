package io.github.dfa1.vortex.performance;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/// Full scan of a real-world `.vortex` file: every column of every chunk decoded through
/// [Array#materialize(java.lang.foreign.SegmentAllocator)], the profiling target for finding which
/// decode loops are hot on real data rather than on synthetic columns. Pass the file with
/// `-p file=/path/to/data.vortex` (several comma-separated paths run as separate params), e.g. a
/// Raincloud corpus file (`scripts/hydrate-raincloud-corpus.sh`).
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 1, jvmArgsAppend = {
        "--enable-native-access=ALL-UNNAMED"
})
public class RealFileScanBenchmark {

    /// The file to scan; required, set with `-p file=...`.
    @Param("")
    public String file;

    /// Decodes every column of every chunk.
    ///
    /// @return the total materialized bytes (returned so JMH keeps the work)
    /// @throws IOException if the file cannot be read
    @Benchmark
    public long scanAll() throws IOException {
        long bytes = 0;
        try (VortexReader reader = VortexReader.open(Path.of(file));
             var chunks = reader.scan(ScanOptions.all())) {
            while (chunks.hasNext()) {
                try (Chunk chunk = chunks.next(); Arena arena = Arena.ofConfined()) {
                    for (Chunk.Column column : chunk.columns().values()) {
                        bytes += materialize(column.array(), arena);
                    }
                }
            }
        }
        return bytes;
    }

    // Struct/list/variant columns have no primary segment: counted by length, not decoded further.
    private static long materialize(Array array, Arena arena) {
        try {
            return array.materialize(arena).byteSize();
        } catch (VortexException e) {
            return array.length();
        }
    }
}
