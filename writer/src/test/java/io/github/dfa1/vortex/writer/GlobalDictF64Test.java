package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static io.github.dfa1.vortex.writer.VortexReads.readAllDoubles;
import static org.assertj.core.api.Assertions.assertThat;

/// Global dictionary encoding for low-cardinality F64 columns: ports Rust's
/// {@code vortex-compressor::builtins::dict::float::FloatDictScheme}. Required for the
/// NYC taxi {@code mta_tax} / {@code Airport_fee} / {@code extra} columns where ALP +
/// per-chunk dict was producing ~10% larger files than Rust's global dict + sparse codes.
class GlobalDictF64Test {

    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of("rate"),
            List.of(new DType.Primitive(PType.F64, false)),
            false);

    @Test
    void lowCardinality_f64_acrossChunks_usesGlobalDict(@TempDir Path tmp) throws IOException {
        // Given — mta_tax-shaped data: one dominant value (0.5) for ~95% of rows + a handful
        // of others, repeated across multiple chunks. The frequency-ordered dict assigns
        // the dominant value to code 0 so SparseEncodingEncoder (fill=0) can compress the
        // codes child.
        Path file = tmp.resolve("rate.vortex");
        double[] dict = {0.5, 0.0, -0.5, 1.0, 2.0, 3.0, 4.0, 0.75};
        int rowsPerChunk = 1_000;
        int chunkCount = 5;

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.cascading(3))) {
            // When
            for (int c = 0; c < chunkCount; c++) {
                double[] data = new double[rowsPerChunk];
                for (int i = 0; i < rowsPerChunk; i++) {
                    // 95% dominant (0.5), 5% scattered across the other 7 values
                    data[i] = (i % 20 == 0) ? dict[1 + ((c + i) % 7)] : dict[0];
                }
                sut.writeChunk(Map.of("rate", data));
            }
        }

        // Then — file is small. 5 chunks × 1000 rows of F64 (40 KB raw) compressed to
        // dict (8 × 8 = 64 B values) + ~5 KB of mostly-zero U8 codes per chunk. Allowing
        // for layout overhead, well under 12 KB total.
        long size = Files.size(file);
        assertThat(size).as("global F64 dict file size").isLessThan(12_000L);

        // And — values round-trip exactly.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            double[] got = readAllDoubles(vf, "rate");
            assertThat(got).hasSize(rowsPerChunk * chunkCount);
            int idx = 0;
            for (int c = 0; c < chunkCount; c++) {
                for (int i = 0; i < rowsPerChunk; i++) {
                    double expected = (i % 20 == 0) ? dict[1 + ((c + i) % 7)] : dict[0];
                    assertThat(got[idx++]).isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void highCardinality_f64_fallsBackToPerChunk(@TempDir Path tmp) throws IOException {
        // Given — every value distinct, well past the dict cardinality cap.
        Path file = tmp.resolve("ids.vortex");
        int rows = 5_000;
        double[] data = new double[rows];
        for (int i = 0; i < rows; i++) {
            data[i] = i + 0.5;
        }

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.cascading(3))) {
            // When
            sut.writeChunk(Map.of("rate", data));
        }

        // Then — correctness only; the dict path must give up cleanly when cardinality > cap.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            double[] got = readAllDoubles(vf, "rate");
            assertThat(got).containsExactly(data);
        }
    }
}
