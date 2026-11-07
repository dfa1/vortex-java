package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.writer.encode.CascadingCompressor;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A nullable text column without a null has no validity to store, as Rust's all-valid string array
/// has none (#650): with cascading on it is never written under a `vortex.masked`.
class NullableTextMaskTest {

    private static final ColumnName V = ColumnName.of("v");
    private static final int ROWS = 5_000;

    static Stream<Arguments> writes() {
        // no global dict: its layout has no mask either, and these columns are low cardinality
        WriteOptions defaults = WriteOptions.defaults().withGlobalDict(false);
        WriteOptions fsst = defaults
                .withColumnEncoding(V, ColumnEncoding.candidates(EncodingId.VORTEX_FSST, EncodingId.VORTEX_VARBIN));
        return Stream.of(
                Arguments.of("no null, cascading", DTypes.UTF8.asNullable(), 0, defaults, false),
                Arguments.of("binary, no null, cascading", DTypes.BINARY.asNullable(), 0, defaults, false),
                Arguments.of("one null, cascading", DTypes.UTF8.asNullable(), 1, defaults, true),
                Arguments.of("no null, depth 0", DTypes.UTF8.asNullable(), 0, WriteOptions.cascading(0).withGlobalDict(false), true),
                Arguments.of("no null, column override", DTypes.UTF8.asNullable(), 0, fsst, false),
                Arguments.of("one null, column override", DTypes.UTF8.asNullable(), 1, fsst, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writes")
    void write_masksOnlyWhenThereIsValidityToStore(String name, DType dtype, int nulls, WriteOptions options,
                                                   boolean masked, @TempDir Path tmp) throws IOException {
        // Given
        boolean binary = dtype instanceof DType.Binary;
        boolean[] validity = new boolean[ROWS];
        Object values;
        if (binary) {
            byte[][] bytes = new byte[ROWS][];
            for (int i = 0; i < ROWS; i++) {
                validity[i] = i >= nulls;
                bytes[i] = validity[i] ? ("row-" + i % 50).getBytes() : null;
            }
            values = bytes;
        } else {
            String[] strings = new String[ROWS];
            for (int i = 0; i < ROWS; i++) {
                validity[i] = i >= nulls;
                strings[i] = validity[i] ? "row-" + i % 50 : null;
            }
            values = strings;
        }
        Path file = tmp.resolve("text.vtx");

        // When
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(channel, new DType.Struct(List.of(V), List.of(dtype), false), options)) {
            sut.writeChunk(Map.of(V, new NullableData(values, validity)));
        }

        // Then
        try (var reader = VortexReader.open(file)) {
            assertThat(reader.footer().arraySpecs().contains(EncodingId.VORTEX_MASKED)).isEqualTo(masked);
        }
    }

    @Test
    void isUnmaskedText_isOnlyANullableTextCarrierWithEveryRowValid() {
        // Given
        boolean[] allValid = {true, true};
        boolean[] oneNull = {true, false};
        Object text = new String[]{"a", "b"};

        // When / Then
        assertThat(CascadingCompressor.isUnmaskedText(DTypes.UTF8.asNullable(), new NullableData(text, allValid))).isTrue();
        assertThat(CascadingCompressor.isUnmaskedText(DTypes.BINARY.asNullable(), new NullableData(text, allValid))).isTrue();
        assertThat(CascadingCompressor.isUnmaskedText(DTypes.UTF8.asNullable(), new NullableData(text, oneNull))).isFalse();
        assertThat(CascadingCompressor.isUnmaskedText(DTypes.UTF8.asNullable(), text)).isFalse();
        assertThat(CascadingCompressor.isUnmaskedText(DTypes.I64.asNullable(),
                new NullableData(new long[]{1, 2}, allValid))).isFalse();
    }
}
