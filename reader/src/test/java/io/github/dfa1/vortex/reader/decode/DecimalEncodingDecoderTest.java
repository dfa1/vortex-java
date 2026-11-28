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

    private static Array decodeBytes(int valuesType, int rowCount, byte[] bytes) {
        MemorySegment meta = MemorySegment.ofArray(new byte[]{0x08, (byte) valuesType});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_DECIMAL, meta, new ArrayNode[0], new int[]{0});
        DecodeContext ctx = new DecodeContext(node, DECIMAL, rowCount,
                new MemorySegment[]{MemorySegment.ofArray(bytes)}, ReadRegistry.empty(), Arena.ofAuto());
        return SUT.decode(ctx);
    }

    /// One row of zeros whose buffer declares `alignment` bytes, as a file's array FlatBuffer does.
    private static Array decodeAligned(int valuesType, int bufferBytes, int alignment) {
        MemorySegment meta = MemorySegment.ofArray(new byte[]{0x08, (byte) valuesType});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_DECIMAL, meta, new ArrayNode[0], new int[]{0});
        DecodeContext ctx = new DecodeContext(node, DECIMAL, 1,
                new MemorySegment[]{MemorySegment.ofArray(new byte[bufferBytes])}, ReadRegistry.empty(),
                Arena.ofAuto(), new int[]{alignment});
        return SUT.decode(ctx);
    }

    private static void longToLe(long value, byte[] out, int offset) {
        for (int b = 0; b < 8; b++) {
            out[offset + b] = (byte) (value >>> (8 * b));
        }
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

        /// Rust 0.95 requires exactly one value per row: a longer buffer, which a legacy read would
        /// take its row count from, fails as a shorter one does.
        @ParameterizedTest(name = "{0} bytes for 2 rows of 8")
        @CsvSource({"8", "15", "17", "24"})
        void bufferNotExactlyOneValuePerRow_throws(int bufferBytes) {
            // When / Then
            assertThatThrownBy(() -> decode(3, 2, bufferBytes))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("buffer must hold exactly 2 values of 8 bytes");
        }

        @Test
        void hugeRowCount_throwsWithoutOverflow() {
            // Given — rows × 32 bytes would overflow a long
            MemorySegment meta = MemorySegment.ofArray(new byte[]{0x08, 5});
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_DECIMAL, meta, new ArrayNode[0], new int[]{0});
            DecodeContext ctx = new DecodeContext(node, DECIMAL, Long.MAX_VALUE / 4,
                    new MemorySegment[]{MemorySegment.ofArray(new byte[32])}, ReadRegistry.empty(), Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx)).isInstanceOf(VortexException.class);
        }

        /// `DecimalData::validate`: Rust checks the alignment the buffer declares, not its address.
        @ParameterizedTest(name = "values_type {0} declared at {1}")
        @CsvSource({"3, 4", "3, 1", "4, 8", "5, 8"})
        void underAlignedBuffer_throws(int valuesType, int declared) {
            // When / Then
            assertThatThrownBy(() -> decodeAligned(valuesType, 1 << valuesType, declared))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("alignment");
        }

        @ParameterizedTest(name = "values_type {0} declared at {1}")
        @CsvSource({"3, 8", "3, 64", "4, 16", "5, 16"})
        void alignedBuffer_decodes(int valuesType, int declared) {
            // When
            Array result = decodeAligned(valuesType, 1 << valuesType, declared);

            // Then — i256 needs only 16 bytes, as Rust's is two 128-bit halves
            assertThat(result.length()).isEqualTo(1);
        }

        @Test
        void legacyWidth_validValueOutsidePrecision_throws() {
            // Given — precision 10 is canonically i64; stored as i128 it is scanned, and 10^10 needs 11 digits
            byte[] bytes = new byte[16];
            longToLe(10_000_000_000L, bytes, 0);

            // When / Then
            assertThatThrownBy(() -> decodeBytes(4, 1, bytes))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("does not fit precision 10");
        }

        @Test
        void legacyWidth_negativeValueOutsidePrecision_throws() {
            // Given — -10^10 at the canonical i64 width of precision 10 would be trusted; at i128 it is scanned
            byte[] bytes = new byte[16];
            longToLe(-10_000_000_000L, bytes, 0);
            java.util.Arrays.fill(bytes, 8, 16, (byte) -1);

            // When / Then
            assertThatThrownBy(() -> decodeBytes(4, 1, bytes))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("does not fit precision 10");
        }

        @Test
        void canonicalWidth_valueOutsidePrecision_isTrusted() {
            // Given — the canonical i64 width of precision 10 is not scanned, as in Rust
            byte[] bytes = new byte[8];
            longToLe(10_000_000_000L, bytes, 0);

            // When
            Array result = decodeBytes(3, 1, bytes);

            // Then
            assertThat(((DecimalArray) result).getDecimal(0)).isEqualByComparingTo("100000000.00");
        }

        @Test
        void legacyWidth_valuesWithinPrecision_decode() {
            // Given — precision 10 stored as i16, narrower than its canonical i64
            byte[] bytes = {(byte) 0xff, 0x7f, 0x00, (byte) 0x80};

            // When
            Array result = decodeBytes(1, 2, bytes);

            // Then
            assertThat(((DecimalArray) result).getDecimal(0)).isEqualByComparingTo("327.67");
            assertThat(((DecimalArray) result).getDecimal(1)).isEqualByComparingTo("-327.68");
        }

        /// Proto3 omits a default field, so Rust writes values_type 0 (i8, a precision <= 2 column)
        /// as empty metadata. Rejecting it as "missing" failed every such Rust- or Java-written file.
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.dfa1.vortex.reader.decode.DecimalEncodingDecoderTest#absentMetadata")
        void absentOrEmptyMetadata_decodesAsI8(String name, MemorySegment meta) {
            // Given — 3 rows over 3 bytes: only a 1-byte width holds exactly 3 values

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

    /// `fixed_width::deserialize_validity`: Java used to ignore the child, reading the column as
    /// non-nullable where Rust rejects the node.
    @Test
    void decode_validityChildOnNonNullable_throws() {
        // Given
        MemorySegment meta = MemorySegment.ofArray(new byte[]{0x08, 0});
        ArrayNode validityNode = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{1});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_DECIMAL, meta, new ArrayNode[]{validityNode}, new int[]{0});
        DecodeContext ctx = new DecodeContext(node, new DType.Decimal((byte) 2, (byte) 0, false), 1,
                new MemorySegment[]{MemorySegment.ofArray(new byte[]{1}), MemorySegment.ofArray(new byte[]{1})},
                TestRegistry.ofDecoders(SUT, new BoolEncodingDecoder()), Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> SUT.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("a validity child requires a nullable dtype");
    }

    /// Only valid values are scanned: a null row of a legacy node may hold anything.
    @Test
    void decode_legacyWidth_nullRowOutsidePrecision_decodes() {
        // Given — precision 2 (canonically i8) stored as i16: row 0 is null and holds 1000
        MemorySegment meta = MemorySegment.ofArray(new byte[]{0x08, 1});
        ArrayNode validityNode = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{1});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_DECIMAL, meta, new ArrayNode[]{validityNode}, new int[]{0});
        DecodeContext ctx = new DecodeContext(node, new DType.Decimal((byte) 2, (byte) 0, true), 2,
                new MemorySegment[]{MemorySegment.ofArray(new byte[]{(byte) 0xe8, 0x03, 42, 0}),
                        MemorySegment.ofArray(new byte[]{0b10})},
                TestRegistry.ofDecoders(SUT, new BoolEncodingDecoder()), Arena.ofAuto());

        // When
        Array result = SUT.decode(ctx);

        // Then
        MaskedArray masked = (MaskedArray) result;
        assertThat(masked.isValid(0)).isFalse();
        assertThat(((DecimalArray) masked.inner()).getDecimal(1)).isEqualByComparingTo("42");
    }
}
