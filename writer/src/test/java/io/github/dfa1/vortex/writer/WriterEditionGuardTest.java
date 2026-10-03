package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
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
/// The only escape hatch is turning the guard off entirely, as Rust's `disable_editions()` does:
/// an encoding in no edition is not reachable any other way (see
/// [#withoutEditions_allowsTheForcedEncoder]).
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
        // Given — fastlanes.delta is in no edition, so outside the default core2026.08.3 guard;
        // the explicit single-encoder list forces findEncoder's first-match dispatch straight to
        // it, bypassing CascadingCompressor's exclusion-aware competition entirely
        Path file = tmp.resolve("delta_guarded.vtx");
        long[] data = {100L, 105L, 110L, 115L, 120L};
        Map<ColumnName, Object> chunk = Map.of(ColumnName.of("ts"), data);

        // When / Then
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults(),
                     List.of(new DeltaEncodingEncoder()))) {
            assertThatThrownBy(() -> sut.writeChunk(chunk))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("fastlanes.delta")
                    .hasMessageContaining("core2026.08.3")
                    .hasMessageContaining("not part of any edition")
                    .hasMessageContaining("withoutEditions");
        }
    }

    @Test
    void withoutEditions_allowsTheForcedEncoder(@TempDir Path tmp) throws IOException {
        // Given — the guard turned off, as Rust's disable_editions(): the only way to emit delta
        Path file = tmp.resolve("delta_no_editions.vtx");
        long[] data = {100L, 105L, 110L, 115L, 120L};
        WriteOptions options = WriteOptions.defaults().withoutEditions();

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

    /// Delta joins the cascade like Rust's `DeltaScheme`: registered, but in no edition, so only
    /// offered when editions are disabled (issue #410). Default writes must never emit it, even on
    /// data it would win.
    @Test
    void cascading_defaultEditions_neverEmitDelta(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("delta_default.vtx");

        // When
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.cascading(3))) {
            sut.writeChunk(Map.of(ColumnName.of("ts"), jitteredTimestamps()));
        }

        // Then
        try (var vf = VortexReader.open(file)) {
            assertThat(vf.footer().arraySpecs()).isNotEmpty().doesNotContain(io.github.dfa1.vortex.core.model.EncodingId.FASTLANES_DELTA);
        }
    }

    @Test
    void cascading_withoutEditions_picksDeltaOnJitteredTimestamps(@TempDir Path tmp) throws IOException {
        // Given — ~1s ticks with sub-second jitter: FoR needs the whole span (~23 bits), the
        // transposed deltas only the jitter around the lane stride
        Path file = tmp.resolve("delta_no_editions_cascade.vtx");
        long[] data = jitteredTimestamps();
        WriteOptions options = WriteOptions.cascading(3).withoutEditions();

        // When
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I64_SCHEMA, options)) {
            sut.writeChunk(Map.of(ColumnName.of("ts"), data));
        }

        // Then
        try (var vf = VortexReader.open(file)) {
            assertThat(vf.footer().arraySpecs()).contains(io.github.dfa1.vortex.core.model.EncodingId.FASTLANES_DELTA);
            assertThat(readAllLongs(vf, "ts")).containsExactly(data);
        }
    }

    private static long[] jitteredTimestamps() {
        java.util.Random random = new java.util.Random(7);
        long[] data = new long[8_192];
        for (int i = 0; i < data.length; i++) {
            data[i] = 1_700_000_000_000L + i * 1_000L + random.nextInt(1_000);
        }
        return data;
    }

    /// The edition policy must reach candidates an encoder keeps privately: Sparse compresses a
    /// validity bitmap's patch indices over its own list, which offers `fastlanes.delta`. Before,
    /// only the writer's own encoder lists were excluded, so once cascading delta could win there
    /// a default-edition write failed with "fastlanes.delta: outside the configured edition(s)".
    @Test
    void cascading_defaultEditions_privateCandidateListsHonorTheEdition(@TempDir Path tmp) throws IOException {
        // Given — a nullable column with a periodic null pattern: its validity goes sparse, and
        // the regular patch indices are exactly what delta compresses best
        DType.Struct schema = new DType.Struct(List.of(ColumnName.of("s")), List.of(new DType.Utf8(true)), false);
        String[] categories = {"alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta"};
        java.util.Random random = new java.util.Random(42);
        String[] data = new String[50_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = i % 10 == 0 ? null : categories[random.nextInt(categories.length)];
        }
        Path file = tmp.resolve("sparse_validity.vtx");

        // When
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.cascading(3).withGlobalDict(false))) {
            sut.writeChunk(Map.of(ColumnName.of("s"), data));
        }

        // Then
        try (var vf = VortexReader.open(file)) {
            assertThat(vf.footer().arraySpecs()).isNotEmpty().doesNotContain(io.github.dfa1.vortex.core.model.EncodingId.FASTLANES_DELTA);
        }
    }
}
