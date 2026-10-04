package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/// Reads `fixtures/zstd_buffers.vortex`, written by Rust's own `ZstdBuffers::compress` under the
/// opt-in `zstd2026.02.0` edition (`scripts/fixtures/zstd-buffers`); vortex-jni cannot emit
/// `vortex.zstd_buffers`, so a checked-in fixture stands in for a jni-written file (#444).
///
/// Both columns wrap an inner array that has children as well as buffers (a primitive's validity,
/// a varbin's offsets and validity), so the decoder must hand the inner encoding its decompressed
/// buffers and its untouched children together.
class ZstdBuffersInteropIntegrationTest {

    private static final int ROWS = 1_000;

    @Test
    void rustWritesZstdBuffers_javaReadsEveryRow() throws IOException, URISyntaxException {
        // Given
        Path file = Path.of(Objects.requireNonNull(
                getClass().getResource("/fixtures/zstd_buffers.vortex")).toURI());

        // When
        List<String> result = new ArrayList<>(ROWS);
        try (var reader = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = reader.scan(ScanOptions.columns("ints", "strs"))) {
            while (iter.hasNext()) {
                Chunk chunk = iter.next();
                Array ints = chunk.column("ints");
                Array strs = chunk.column("strs");
                for (long i = 0; i < chunk.rowCount(); i++) {
                    result.add(render(ints, i, true) + "|" + render(strs, i, false));
                }
            }
        }

        // Then — the fixture's generator documents these values
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(InspectorTree.build(vf).usedEncodings()).contains("vortex.zstd_buffers");
        }
        assertThat(result).hasSize(ROWS);
        for (int i = 0; i < ROWS; i++) {
            String ints = i % 7 == 0 ? "null" : String.valueOf(i * 3 - 500);
            String strs = i % 5 == 0 ? "null" : "s" + i % 13;
            assertThat(result.get(i)).as("row %d", i).isEqualTo(ints + "|" + strs);
        }
    }

    private static String render(Array column, long i, boolean isInt) {
        Array values = column;
        if (column instanceof MaskedArray masked) {
            if (!masked.isValid(i)) {
                return "null";
            }
            values = masked.inner();
        }
        return isInt ? String.valueOf(((IntArray) values).getInt(i)) : ((VarBinArray) values).getString(i);
    }
}
