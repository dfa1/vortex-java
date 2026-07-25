package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// High-precision F64 columns that plain ALP cannot fit (too many exceptions) must be able to pick
/// ALP-RD in the cascade competition, matching the Rust reference — before #304 ALP-RD was
/// registered but not a top-level cascade candidate, so such columns fell back to raw primitive.
class AlpRdCascadeSelectionIntegrationTest {

    private static final ColumnName COL = ColumnName.of("lat");
    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of(COL), List.of(new DType.Primitive(PType.F64, false)), false);

    @Test
    void highPrecisionF64_selectsAlpRd_andRoundTrips(@TempDir Path tmp) throws IOException {
        // Given — NYC-latitude-like doubles: a tight value range (shared high/exponent bits) with
        // full random mantissas (so plain ALP produces exceptions on nearly every row, but ALP-RD's
        // left-parts dictionary captures the shared bits). All distinct, so dict is skipped too.
        Path file = tmp.resolve("lat.vortex");
        var rng = new Random(42L);
        int rows = 60_000;
        double[] data = new double[rows];
        for (int i = 0; i < rows; i++) {
            data[i] = 40.5 + rng.nextDouble() * 0.4;
        }

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.cascading(3))) {
            // When
            sut.writeChunk(Map.of(COL, data));
        }

        // Then — the cascade picks ALP-RD.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(InspectorTree.build(vf).usedEncodings())
                    .as("high-precision F64 selects ALP-RD via the cascade")
                    .contains("vortex.alprd");
        }

        // And every value round-trips exactly.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            double[] got = new double[rows];
            int[] idx = {0};
            try (var iter = vf.scan(ScanOptions.columns("lat"))) {
                while (iter.hasNext()) {
                    try (var chunk = iter.next()) {
                        DoubleArray col = chunk.column("lat");
                        long n = col.length();
                        for (long i = 0; i < n; i++) {
                            got[idx[0]++] = col.getDouble(i);
                        }
                    }
                }
            }
            assertThat(got).containsExactly(data);
        }
    }
}
