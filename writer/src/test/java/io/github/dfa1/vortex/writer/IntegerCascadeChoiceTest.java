package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.writer.encode.NullableData;
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

/// The integer cascade's choices that vortex-jni's trees confirmed (#651): the encodings a column
/// is written with, as Rust's integer schemes pick them. A scheme Rust drops on its stats never
/// shows up, a nullable column keeps its validity in the encoding that stores its values.
class IntegerCascadeChoiceTest {

    private static final ColumnName V = ColumnName.of("v");
    private static final int ROWS = 131_072;
    private static final long[] RANDOM = new Random(7).longs(ROWS).toArray();

    @TempDir
    Path tmp;

    @Test
    void runsOfThreeValuesAreNotRunEnd() throws IOException {
        // Given: runs average 3 rows, under Rust's RUN_END_THRESHOLD of 4, so its verdict is skip
        long[] values = new long[ROWS];
        for (int i = 0; i < ROWS; i++) {
            values[i] = RANDOM[i / 3] >>> 20;
        }

        // When
        List<EncodingId> encodings = encodingsOf(primitive(PType.I64, false), values);

        // Then
        assertThat(encodings).doesNotContain(EncodingId.VORTEX_RUNEND);
    }

    @Test
    void longRunsOfWideValuesAreRunEnd() throws IOException {
        // Given: runs of 48 wide values, which no bit width packs below the run-end's few runs
        long[] values = new long[ROWS];
        for (int i = 0; i < ROWS; i++) {
            values[i] = RANDOM[i / 48] >>> 20;
        }

        // When
        List<EncodingId> encodings = encodingsOf(primitive(PType.I64, false), values);

        // Then
        assertThat(encodings).contains(EncodingId.VORTEX_RUNEND);
    }

    @Test
    void aNullableColumnKeepsItsValidityInTheBitPackedArray() throws IOException {
        // Given: small values, every seventh row null
        int[] values = new int[ROWS];
        boolean[] validity = new boolean[ROWS];
        for (int i = 0; i < ROWS; i++) {
            validity[i] = i % 7 != 0;
            values[i] = validity[i] ? Math.floorMod(RANDOM[i], 16) : 0;
        }

        // When
        List<EncodingId> encodings = encodingsOf(primitive(PType.I32, true), new NullableData(values, validity));

        // Then: no vortex.masked wrapper, as Rust has none
        assertThat(encodings).contains(EncodingId.FASTLANES_BITPACKED, EncodingId.VORTEX_BOOL)
                .doesNotContain(EncodingId.VORTEX_MASKED);
    }

    @Test
    void aNullableColumnWithoutNullsStoresNoValidity() throws IOException {
        // Given: small values, no null: Rust imports an all-valid Arrow array without a bitmap
        int[] values = new int[ROWS];
        boolean[] validity = new boolean[ROWS];
        for (int i = 0; i < ROWS; i++) {
            validity[i] = true;
            values[i] = Math.floorMod(RANDOM[i], 16);
        }

        // When
        List<EncodingId> encodings = encodingsOf(primitive(PType.I32, true), new NullableData(values, validity));

        // Then
        assertThat(encodings).contains(EncodingId.FASTLANES_BITPACKED)
                .doesNotContain(EncodingId.VORTEX_BOOL, EncodingId.VORTEX_MASKED);
    }

    private static DType.Primitive primitive(PType ptype, boolean nullable) {
        return new DType.Primitive(ptype, nullable);
    }

    private List<EncodingId> encodingsOf(DType.Primitive dtype, Object data) throws IOException {
        Path file = tmp.resolve("v.vtx");
        var schema = new DType.Struct(List.of(V), List.of(dtype), false);
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(channel, schema, WriteOptions.defaults().withZoneMaps(false))) {
            sut.writeChunk(Map.of(V, data));
        }
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            return reader.footer().arraySpecs();
        }
    }
}
