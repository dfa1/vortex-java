package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.MemorySize;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies IS NULL / IS NOT NULL chunk pruning via per-chunk null_count in the ArrayNode stats.
class NullCountPruningTest {

    @TempDir
    Path tmp;

    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of(ColumnName.of("v")), List.of(new DType.Primitive(PType.I64, true)), false);

    // chunkSize large so each writeChunk is exactly one chunk (one zone). Three chunks of distinct
    // sizes and null patterns: 3 rows / 0 nulls, 2 rows / 1 null, 4 rows / all null.
    private Path write() throws IOException {
        Path file = tmp.resolve("nulls.vtx");
        WriteOptions opts = new WriteOptions(1024, true, 0.90, 0, false, false, MemorySize.ofMiB(256), Map.of());
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, opts)) {
            sut.writeChunk(Map.of(ColumnName.of("v"), new NullableData(
                    new long[]{1, 2, 3}, new boolean[]{true, true, true})));
            sut.writeChunk(Map.of(ColumnName.of("v"), new NullableData(
                    new long[]{4, 0}, new boolean[]{true, false})));
            sut.writeChunk(Map.of(ColumnName.of("v"), new NullableData(
                    new long[]{0, 0, 0, 0}, new boolean[]{false, false, false, false})));
        }
        return file;
    }

    private List<Long> scanRowCounts(Path file, RowFilter filter) throws IOException {
        var opts = new ScanOptions(List.of(), filter, ScanOptions.NO_LIMIT);
        var counts = new ArrayList<Long>();
        try (VortexReader reader = VortexReader.open(file);
             var iter = reader.scan(opts)) {
            iter.forEachRemaining(c -> counts.add(c.rowCount()));
        }
        return counts;
    }

    @Test
    void isNull_prunesZeroNullChunks() throws IOException {
        // Given the 3-row chunk has zero nulls → pruned; the 1-null and all-null chunks survive
        Path file = write();

        // When / Then surviving chunk sizes are 2 (1-null) and 4 (all-null)
        assertThat(scanRowCounts(file, RowFilter.isNull("v"))).containsExactly(2L, 4L);
    }

    @Test
    void isNotNull_prunesAllNullChunks() throws IOException {
        // Given the 4-row chunk is all null → pruned; the 0-null and 1-null chunks survive
        Path file = write();

        // When / Then surviving chunk sizes are 3 (0-null) and 2 (1-null)
        assertThat(scanRowCounts(file, RowFilter.isNotNull("v"))).containsExactly(3L, 2L);
    }

    @Test
    void noFilter_keepsAllChunks() throws IOException {
        // Given / When / Then all three chunks survive
        assertThat(scanRowCounts(write(), null)).containsExactly(3L, 2L, 4L);
    }

    @Test
    void columnStats_sumsNullCountAcrossChunks() throws IOException {
        // Given chunks carry 0, 1 and 4 nulls; every chunk records a null count so the
        // column total is known (0 + 1 + 4 = 5) rather than dropped to unknown
        Path file = write();

        // When
        Long result;
        try (VortexReader reader = VortexReader.open(file)) {
            result = reader.columnStats().get(ColumnName.of("v")).nullCount();
        }

        // Then
        assertThat(result).isEqualTo(5L);
    }
}
