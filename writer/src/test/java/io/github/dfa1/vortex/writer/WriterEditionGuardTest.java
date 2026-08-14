package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.Editions;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.writer.encode.DeltaEncodingEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static io.github.dfa1.vortex.writer.VortexReads.readAllLongs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// End-to-end tests for the [WriteOptions#editions()] guard (issue #301): [VortexWriter]'s
/// backstop check in `registerEncodingIds`, which fires for selection paths that don't consult
/// [io.github.dfa1.vortex.writer.encode.EncodeContext#excluded()] at all — e.g. a forced,
/// single-candidate explicit encoder list, as used here. The graceful-fallback filtering behavior
/// for cost-based competitions is covered separately by
/// `io.github.dfa1.vortex.writer.encode.MaskedValidityCascadeEditionExclusionTest`.
///
/// There is deliberately no "disable the guard" escape hatch — an `unstable`-family encoding
/// must be reached by enabling its edition explicitly (see
/// [#withEdition_enablingUnstable_allowsTheForcedEncoder]).
class WriterEditionGuardTest {

    private static final DType.Struct I64_SCHEMA = new DType.Struct(
            List.of(ColumnName.of("ts")),
            List.of(DType.I64),
            false);

    private static ReadRegistry deltaRegistry() {
        return ReadRegistry.builder()
                .register(new io.github.dfa1.vortex.reader.decode.DeltaEncodingDecoder())
                .register(new io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder())
                .build();
    }

    @Test
    void defaultGuard_forcedOutOfEditionEncoder_throwsNamingIdAndEdition(@TempDir Path tmp) throws IOException {
        // Given — fastlanes.delta is unstable-family, outside the default core2026.08.0 guard;
        // the explicit single-encoder list forces findEncoder's first-match dispatch straight to
        // it, bypassing CascadingCompressor's exclusion-aware competition entirely
        Path file = tmp.resolve("delta_guarded.vtx");
        long[] data = {100L, 105L, 110L, 115L, 120L};

        // When / Then
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults(),
                     List.of(new DeltaEncodingEncoder()))) {
            assertThatThrownBy(() -> sut.writeChunk(Map.of(ColumnName.of("ts"), data)))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("fastlanes.delta")
                    .hasMessageContaining("core2026.08.0")
                    .hasMessageContaining("unstable2025.05.0")
                    .hasMessageContaining("withEdition");
        }
    }

    @Test
    void withEdition_enablingUnstable_allowsTheForcedEncoder(@TempDir Path tmp) throws IOException {
        // Given — explicitly opt into the unstable edition that covers fastlanes.delta
        Path file = tmp.resolve("delta_unstable.vtx");
        long[] data = {100L, 105L, 110L, 115L, 120L};
        WriteOptions options = WriteOptions.defaults().withEdition(Editions.UNSTABLE_2025_05_0);

        // When
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I64_SCHEMA, options, List.of(new DeltaEncodingEncoder()))) {
            sut.writeChunk(Map.of(ColumnName.of("ts"), data));
        }

        // Then
        try (var vf = VortexReader.open(file, deltaRegistry())) {
            assertThat(readAllLongs(vf, "ts")).containsExactly(data);
        }
    }
}
