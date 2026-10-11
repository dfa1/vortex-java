package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LazyDecimalArray;
import org.junit.jupiter.api.Nested;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecimalEncodingDecoderTest {

    private static final DecimalEncodingDecoder SUT = new DecimalEncodingDecoder();
    private static final DType DECIMAL = new DType.Decimal((byte) 10, (byte) 2, false);

    private static Array decode(int valuesType, int rowCount, int bufferBytes) {
        // Encode values_type explicitly (field 1, varint) so even the proto3 default (0) is
        // present on the wire — DecimalMetadata(0).encode() would omit it and read as empty.
        MemorySegment meta = MemorySegment.ofArray(new byte[]{0x08, (byte) valuesType});
        return decode(meta, rowCount, bufferBytes);
    }

    private static Array decode(MemorySegment meta, int rowCount, int bufferBytes) {
        MemorySegment buf = MemorySegment.ofArray(new byte[bufferBytes]);
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_DECIMAL, meta, new ArrayNode[0], new int[]{0});
        DecodeContext ctx = new DecodeContext(node, DECIMAL, rowCount,
                new MemorySegment[]{buf}, ReadRegistry.empty(), Arena.ofAuto());
        return SUT.decode(ctx);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> absentMetadata() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("null", null),
                org.junit.jupiter.params.provider.Arguments.of("empty", MemorySegment.ofArray(new byte[0])));
    }

    @Test
    void encodingId() {
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.VORTEX_DECIMAL);
    }

    @Nested
    class Decode {

        @ParameterizedTest(name = "valuesType={0} -> {1} bytes")
        @CsvSource({"0,1", "1,2", "2,4", "3,8", "4,16", "5,32"})
        void everyDecimalTypeWidth(int valuesType, int byteWidth) {
            // Given — buffer sized exactly for the declared width × rows
            int rows = 2;

            // When
            Array result = decode(valuesType, rows, rows * byteWidth);

            // Then
            assertThat(result).isInstanceOf(LazyDecimalArray.class);
            assertThat(result.length()).isEqualTo(rows);
        }

        @Test
        void unknownValuesType_throws() {
            // When / Then
            assertThatThrownBy(() -> decode(6, 1, 64))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unknown DecimalType");
        }

        @Test
        void bufferTooSmall_throws() {
            // Given — values_type 3 = 8 bytes/row × 2 rows = 16 needed, only 8 provided
            // When / Then
            assertThatThrownBy(() -> decode(3, 2, 8))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("buffer too small");
        }

        /// Proto3 omits a default field, so Rust writes values_type 0 (i8, a precision <= 2 column)
        /// as empty metadata. Rejecting it as "missing" failed every such Rust- or Java-written file.
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.dfa1.vortex.reader.decode.DecimalEncodingDecoderTest#absentMetadata")
        void absentOrEmptyMetadata_decodesAsI8(String name, MemorySegment meta) {
            // Given — 3 rows over 3 bytes: only a 1-byte width fits, wider would be "buffer too small"

            // When
            Array result = decode(meta, 3, 3);

            // Then
            assertThat(result.length()).isEqualTo(3);
        }

        @Test
        void invalidMetadata_throws() {
            // Given — a truncated varint that proto decode rejects
            MemorySegment bad = MemorySegment.ofArray(new byte[]{0x08, (byte) 0x80});

            // When / Then
            assertThatThrownBy(() -> decode(bad, 1, 8))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("invalid metadata");
        }
    }

    /// Rust's Decimal takes `[validity?]` as its only child. The decoder used to ignore it, so every
    /// null row of a Rust-written nullable column (i8 and i128 storage in the interop sweep) read
    /// back as zero.
    @Test
    void decode_validityChild_masksNullRows() {
        // Given 0.01, null, 0.03 in i8 storage: validity bits 0b101
        MemorySegment meta = MemorySegment.ofArray(new byte[]{0x08, 0});
        ArrayNode validityNode = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{1});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_DECIMAL, meta, new ArrayNode[]{validityNode}, new int[]{0});
        DecodeContext ctx = new DecodeContext(node, new DType.Decimal((byte) 10, (byte) 2, true), 3,
                new MemorySegment[]{MemorySegment.ofArray(new byte[]{1, 2, 3}), MemorySegment.ofArray(new byte[]{0b101})},
                TestRegistry.ofDecoders(SUT, new BoolEncodingDecoder()), Arena.ofAuto());

        // When
        Array result = SUT.decode(ctx);

        // Then
        MaskedArray masked = (MaskedArray) result;
        assertThat(masked.isValid(0)).isTrue();
        assertThat(masked.isValid(1)).isFalse();
        assertThat(((DecimalArray) masked.inner()).getDecimal(2)).isEqualByComparingTo("0.03");
    }

    @Test
    void decode_twoChildren_throws() {
        // Given Rust accepts only [] or [validity]
        MemorySegment meta = MemorySegment.ofArray(new byte[]{0x08, 0});
        ArrayNode validityNode = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{1});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_DECIMAL, meta,
                new ArrayNode[]{validityNode, validityNode}, new int[]{0});
        DecodeContext ctx = new DecodeContext(node, new DType.Decimal((byte) 10, (byte) 2, true), 1,
                new MemorySegment[]{MemorySegment.ofArray(new byte[]{1}), MemorySegment.ofArray(new byte[]{1})},
                TestRegistry.ofDecoders(SUT, new BoolEncodingDecoder()), Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> SUT.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("expected 0 or 1 children, got 2");
    }
}
