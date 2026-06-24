package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.proto.ProtoDictMetadata;
import io.github.dfa1.vortex.reader.decode.DictEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.VarBinEncodingDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DictEncodingEncoderTest {

    private static final DictEncodingEncoder ENCODER = new DictEncodingEncoder();
    private static final DictEncodingDecoder DECODER = new DictEncodingDecoder();
    private static final ReadRegistry PRIM_REG = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());
    private static final ReadRegistry UTF8_REG = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder(), new VarBinEncodingDecoder());

    static Stream<int[]> i32Arrays() {
        return Stream.of(
                new int[]{0},
                new int[]{1, 2, 3},
                new int[]{0, 1, 2, 0, 1, 2, 0, 1, 2},
                new int[]{42, 42, 42, 42, 42},
                new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, 0, Integer.MIN_VALUE, Integer.MAX_VALUE}
        );
    }

    static Stream<long[]> i64Arrays() {
        return Stream.of(
                new long[]{0L},
                new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 0L, Long.MIN_VALUE},
                new long[]{1L, 2L, 1L, 2L, 1L, 2L, 1L, 2L}
        );
    }

    static Stream<Arguments> repetitiveI32Arrays() {
        return Stream.of(
                Arguments.of("binary-100", repeat(new int[]{0, 1}, 50)),
                Arguments.of("single-value-50", repeat(new int[]{42}, 50)),
                Arguments.of("three-values-60", repeat(new int[]{10, 20, 30}, 20))
        );
    }

    static Stream<Arguments> utf8Arrays() {
        return Stream.of(
                Arguments.of("single", new String[]{"hello"}),
                Arguments.of("all-unique", new String[]{"a", "b", "c"}),
                Arguments.of("repeated", new String[]{"AAPL", "GOOG", "AAPL", "MSFT", "GOOG", "AAPL"}),
                Arguments.of("unicode", new String[]{"café", "naïve", "café", "résumé", "naïve"}),
                Arguments.of("empty-string", new String[]{"", "x", "", "y", ""})
        );
    }

    private static int[] repeat(int[] pattern, int times) {
        int[] result = new int[pattern.length * times];
        for (int i = 0; i < times; i++) {
            System.arraycopy(pattern, 0, result, i * pattern.length, pattern.length);
        }
        return result;
    }

    @SuppressWarnings("SameParameterValue")
    private static String[] repeat(String[] pattern, int times) {
        String[] result = new String[pattern.length * times];
        for (int i = 0; i < times; i++) {
            System.arraycopy(pattern, 0, result, i * pattern.length, pattern.length);
        }
        return result;
    }

    @ParameterizedTest
    @MethodSource("i32Arrays")
    void encodeDecode_i32_isLossless(int[] data) {
        // Given
        EncodeResult encoded = ENCODER.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I32, PRIM_REG);

        // When
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(result.materialize(Arena.ofAuto()).get(PTypeIO.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest
    @MethodSource("i64Arrays")
    void encodeDecode_i64_isLossless(long[] data) {
        // Given
        EncodeResult encoded = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I64, PRIM_REG);

        // When
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(result.materialize(Arena.ofAuto()).get(PTypeIO.LE_LONG, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repetitiveI32Arrays")
    void encodedSize_lowCardinality_compressesWellVsRaw(String name, int[] data) {
        // Given
        // When
        EncodeResult result = ENCODER.encode(DTypes.I32, data, EncodeTestHelper.testCtx());

        // Then
        long encodedBytes = result.buffers().stream().mapToLong(MemorySegment::byteSize).sum();
        long rawBytes = (long) data.length * 4;
        assertThat(encodedBytes).isLessThan(rawBytes);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("utf8Arrays")
    void encodeDecode_utf8_isLossless(String name, String[] data) {
        // Given
        EncodeResult encoded = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.UTF8, UTF8_REG);

        // When
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result).isInstanceOf(VarBinArray.class);
        VarBinArray arr = (VarBinArray) result;
        assertThat(arr.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            String actual = arr.getString(i);
            assertThat(actual).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @Test
    void encodedSize_lowCardinalityUtf8_compressesWellVsRaw() {
        // Given
        String[] symbols = {"AAPL", "GOOG", "MSFT"};
        String[] data = repeat(symbols, 1000);

        // When
        EncodeResult result = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());

        // Then
        long encodedBytes = result.buffers().stream().mapToLong(MemorySegment::byteSize).sum();
        long rawBytes = 3000L * 5;
        assertThat(encodedBytes).isLessThan(rawBytes);
    }

    @Test
    void encode_utf8_metadata_valuesLen_matchesUniqueCount() throws Exception {
        // Given
        String[] data = {"apple", "banana", "apple", "banana", "apple"};

        // When
        EncodeResult result = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
        var metaSeg = result.rootNode().metadata();
        ProtoDictMetadata meta = ProtoDictMetadata.decode(metaSeg, 0, metaSeg.byteSize());

        // Then
        assertThat(meta.values_len()).isEqualTo(2);
    }
}
