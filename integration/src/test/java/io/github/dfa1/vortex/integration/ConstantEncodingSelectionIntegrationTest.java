package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
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

import static org.assertj.core.api.Assertions.assertThat;

/// Regression test: cascading compressor must select vortex.constant for columns
/// whose every value is 0. Zero is the identity value for many integer operations,
/// which historically made it invisible to distinct-count checks.
class ConstantEncodingSelectionIntegrationTest {

    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of("v"),
            List.of(DType.I64),
            false);

    @Test
    void cascading_allZeroColumn_usesConstantEncoding(@TempDir Path tmp) throws IOException {
        // Given — 10 000 rows all equal to 0
        Path file = tmp.resolve("all_zeros.vtx");
        long[] zeros = new long[10_000];
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.cascading(3))) {
            sut.writeChunk(Map.of("v", zeros));
        }

        // When
        String result;
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = VortexInspector.inspect(vf);
        }

        // Then — constant must be the root encoding, not a sub-encoding inside a dict.
        // Before the fix: isDictCandidate returned true for single-distinct-value columns,
        // routing all-zero data through writeGlobalDictColumn which produced a dict layout
        // with vortex.constant-coded codes — constant appeared but as a dict child, wasting
        // the wrapping dict overhead.
        assertThat(result)
                .as("all-zero column must use vortex.constant, not vortex.dict\n%s", result)
                .contains("vortex.constant")
                .doesNotContain("vortex.dict");
    }
}
