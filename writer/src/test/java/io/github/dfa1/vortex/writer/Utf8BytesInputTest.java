package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;

/// A Utf8 column given as its UTF-8 bytes must write exactly the file the same strings do: the
/// bytes path skips encoding each string, and every encoder, stat and dictionary has to treat the
/// two carriers alike for that to hold.
class Utf8BytesInputTest {

    private static final ColumnName S = ColumnName.of("s");
    private static final int CHUNKS = 3;
    private static final int ROWS = 20_000;

    @TempDir
    Path tmp;

    static Stream<Arguments> columns() {
        List<Named<IntFunction<String>>> shapes = List.of(
                // dict wins: a few values
                named("low cardinality", i -> "city-" + (i * 7919 % 8)),
                // FSST/Zstd compete: unique values sharing a prefix
                named("high cardinality", i -> "note-" + Integer.toHexString(i * 0x9E3779B1)),
                // UTF-16 and UTF-8 order disagree: a surrogate pair against U+E000.., plus 2/3-byte
                named("non-ASCII", i -> switch (i % 5) {
                    case 0 -> "café " + (i % 50);
                    case 1 -> "😀" + (i % 50);
                    case 2 -> "｡" + (i % 50);
                    case 3 -> "€" + i;
                    default -> "plain" + i;
                }),
                named("constant", _ -> "same"),
                named("nullable", i -> i % 11 == 0 ? null : "v" + (i % 40)));
        List<Named<WriteOptions>> options = List.of(
                named("defaults", WriteOptions.defaults()),
                named("compact", WriteOptions.defaults().withCompact(true)),
                named("no global dict", WriteOptions.defaults().withGlobalDict(false)));
        List<Arguments> cases = new ArrayList<>();
        for (Named<IntFunction<String>> shape : shapes) {
            for (Named<WriteOptions> option : options) {
                cases.add(Arguments.of(shape, option));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("columns")
    void utf8Bytes_writeTheSameFileAsStrings(IntFunction<String> value, WriteOptions options) throws IOException {
        // Given
        List<String[]> chunks = new ArrayList<>();
        Random random = new Random(42);
        for (int c = 0; c < CHUNKS; c++) {
            String[] rows = new String[ROWS];
            for (int i = 0; i < ROWS; i++) {
                rows[i] = value.apply(random.nextInt(1_000_000));
            }
            chunks.add(rows);
        }
        boolean nullable = chunks.stream().flatMap(Stream::of).anyMatch(s -> s == null);
        Path expected = write("strings.vortex", nullable, options, chunks.stream().map(r -> (Object) r).toList());

        // When
        Path result = write("bytes.vortex", nullable, options, chunks.stream().map(Utf8BytesInputTest::utf8).toList());

        // Then
        assertThat(Files.readAllBytes(result)).isEqualTo(Files.readAllBytes(expected));
    }

    @Test
    void malformedUtf8_isRejected() throws IOException {
        // Given: an overlong encoding of '/', which Rust's Utf8 validation rejects
        byte[][] rows = {"ok".getBytes(StandardCharsets.UTF_8), {(byte) 0xc0, (byte) 0xaf}};

        // When / Then
        assertThatThrownBy(() -> write("bad.vortex", false, WriteOptions.defaults(), List.of((Object) rows)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("row 1 is not well-formed UTF-8");
    }

    private Path write(String name, boolean nullable, WriteOptions options, List<Object> chunks) throws IOException {
        Path file = tmp.resolve(name);
        var schema = new DType.Struct(List.of(S), List.of(new DType.Utf8(nullable)), false);
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(channel, schema, options)) {
            for (Object chunk : chunks) {
                sut.writeChunk(Map.of(S, chunk));
            }
        }
        return file;
    }

    private static Object utf8(String[] rows) {
        byte[][] out = new byte[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            out[i] = rows[i] == null ? null : rows[i].getBytes(StandardCharsets.UTF_8);
        }
        return out;
    }
}
