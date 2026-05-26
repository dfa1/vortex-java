package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.Array;
import io.github.dfa1.vortex.encoding.ArrayStats;
import io.github.dfa1.vortex.encoding.DecodeContext;
import io.github.dfa1.vortex.encoding.Decoder;
import io.github.dfa1.vortex.encoding.DecoderRegistry;
import io.github.dfa1.vortex.io.VortexFile;
import io.github.dfa1.vortex.scan.RowFilter;
import io.github.dfa1.vortex.scan.ScanOptions;
import io.github.dfa1.vortex.scan.ScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Round-trip tests verifying zone-map chunk pruning via RowFilter.
class ZoneMapPruningTest {

    private static final DType.Struct SCHEMA = new DType.Struct(
        List.of("id"),
        List.of(new DType.Primitive(PType.I64, false)),
        false);

    // Three chunks: id in [1..50], [51..100], [101..150]
    private static Path writeThreeChunks(Path tmp) throws IOException {
        Path file = tmp.resolve("three_chunks.vtx");
        try (var ch  = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("id", range(1L, 50L)));
            sut.writeChunk(Map.of("id", range(51L, 100L)));
            sut.writeChunk(Map.of("id", range(101L, 150L)));
        }
        return file;
    }

    @Test
    void gte_prunesChunksBelowThreshold(@TempDir Path tmp) throws IOException {
        // Given — chunk 1 max=50, threshold=75 → chunk 1 pruned
        Path file = writeThreeChunks(tmp);

        // When
        List<ScanResult> results = scanWith(file, RowFilter.gte("id", 75L));

        // Then
        assertThat(results).hasSize(2);
        assertThat(results.get(0).rowCount()).isEqualTo(50L); // chunk 2
        assertThat(results.get(1).rowCount()).isEqualTo(50L); // chunk 3
    }

    @Test
    void lte_prunesChunksAboveThreshold(@TempDir Path tmp) throws IOException {
        // Given — chunk 3 min=101, threshold=75 → chunk 3 pruned
        Path file = writeThreeChunks(tmp);

        // When
        List<ScanResult> results = scanWith(file, RowFilter.lte("id", 75L));

        // Then
        assertThat(results).hasSize(2);
        assertThat(results.get(0).rowCount()).isEqualTo(50L); // chunk 1
        assertThat(results.get(1).rowCount()).isEqualTo(50L); // chunk 2
    }

    @Test
    void eq_prunesChunksExcludingValue(@TempDir Path tmp) throws IOException {
        // Given — id=75 only falls in chunk 2 [51..100]; chunks 1 and 3 pruned
        Path file = writeThreeChunks(tmp);

        // When
        List<ScanResult> results = scanWith(file, RowFilter.eq("id", 75L));

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).rowCount()).isEqualTo(50L); // chunk 2
    }

    @Test
    void and_prunesChunksExcludedByAnySubFilter(@TempDir Path tmp) throws IOException {
        // Given — AND(id>=51, id<=100): chunk 1 pruned by gte, chunk 3 pruned by lte
        Path file = writeThreeChunks(tmp);

        // When
        List<ScanResult> results = scanWith(file, RowFilter.and(
            RowFilter.gte("id", 51L),
            RowFilter.lte("id", 100L)));

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).rowCount()).isEqualTo(50L); // chunk 2
    }

    @Test
    void noFilter_returnsAllChunks(@TempDir Path tmp) throws IOException {
        // Given
        Path file = writeThreeChunks(tmp);

        // When
        List<ScanResult> results = scanWith(file, null);

        // Then
        assertThat(results).hasSize(3);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static List<ScanResult> scanWith(Path file, RowFilter filter) throws IOException {
        var opts     = new ScanOptions(List.of(), filter, ScanOptions.NO_LIMIT);
        var registry = primitiveRegistry();
        var results  = new ArrayList<ScanResult>();
        try (var vf   = VortexFile.open(file, registry);
             var iter = vf.scan(opts)) {
            while (iter.hasNext()) {
                results.add(iter.next());
            }
        }
        return results;
    }

    private static long[] range(long from, long to) {
        long[] arr = new long[(int) (to - from + 1)];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = from + i;
        }
        return arr;
    }

    private static DecoderRegistry primitiveRegistry() {
        var registry = DecoderRegistry.empty();
        registry.register(new Decoder() {
            @Override public String encodingId() { return "vortex.primitive"; }
            @Override public Array decode(DecodeContext ctx) {
                MemorySegment buf = ctx.buffer(0);
                return new Array(ctx.dtype(), ctx.rowCount(),
                    new MemorySegment[]{buf}, new Array[0], ArrayStats.empty());
            }
        });
        return registry;
    }
}
