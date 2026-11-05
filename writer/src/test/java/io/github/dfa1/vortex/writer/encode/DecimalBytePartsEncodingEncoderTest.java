package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.array.LazyDecimalBytePartsArray;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.proto.ProtoDecimalBytePartsMetadata;
import io.github.dfa1.vortex.reader.decode.DecimalBytePartsEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.ParameterizedTest;
import io.github.dfa1.vortex.core.model.EncodingId;

import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecimalBytePartsEncodingEncoderTest {

    @Test
    void roundTrip_longArray_preservesMspValues() {
        // Given — scale=0 means the reassembled BigDecimal equals the raw mantissa,
        // so round-tripping the input values verifies both the writer's child
        // payload and the lazy reader's reassembly without needing a scale factor.
        long[] values = {1L, -2L, 3L};
        DType dtype = new DType.Decimal((byte) 18, (byte) 0, false);
        var encoder = new DecimalBytePartsEncodingEncoder();
        var decoder = new DecimalBytePartsEncodingDecoder();
        ReadRegistry registry = TestRegistry.ofDecoders(decoder, new PrimitiveEncodingDecoder());

        // When
        EncodeResult encoded = encoder.encode(dtype, values, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, values.length, dtype, registry);
        LazyDecimalBytePartsArray result = (LazyDecimalBytePartsArray) decoder.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(values.length);
        for (int i = 0; i < values.length; i++) {
            assertThat(result.getDecimal(i)).isEqualByComparingTo(BigDecimal.valueOf(values[i]));
        }
    }

    @Test
    void encodeNode_hasNoBuffers_andOneMspChild() {
        // Given
        long[] values = {10L, 20L};
        DType dtype = new DType.Decimal((byte) 18, (byte) 0, false);
        var sut = new DecimalBytePartsEncodingEncoder();

        // When
        EncodeResult result = sut.encode(dtype, values, EncodeTestHelper.testCtx());

        // Then
        assertThat(result.rootNode().bufferIndices()).isEmpty();
        assertThat(result.rootNode().children()).hasSize(1);
        assertThat(result.buffers()).hasSize(1);
    }

    @ParameterizedTest(name = "[{0}, {1}] -> {2}")
    @CsvSource({
            "0, 127, I8",
            "-128, 0, I8",
            "0, 128, I16",
            "-129, 0, I16",
            "-32768, 32767, I16",
            "0, 32768, I32",
            "-2147483648, 2147483647, I32",
            "0, 2147483648, I64",
            "-9223372036854775808, 0, I64",
    })
    void metadata_zerothChildPtype_isTheNarrowestSignedType(long min, long max, PType expected) throws Exception {
        // Given — Rust's narrowed_decimal picks among i8/i16/i32/i64, never an unsigned type (#644)
        long[] values = {min, max};
        DType dtype = new DType.Decimal((byte) 18, (byte) 0, false);
        var sut = new DecimalBytePartsEncodingEncoder();

        // When
        EncodeResult result = sut.encode(dtype, values, EncodeTestHelper.testCtx());

        // Then
        MemorySegment metaSeg = result.rootNode().metadata();
        ProtoDecimalBytePartsMetadata meta =
                ProtoDecimalBytePartsMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        assertThat(meta.zeroth_child_ptype().value()).isEqualTo(expected.ordinal());
        assertThat(meta.lower_part_count()).isZero();
    }

    @ParameterizedTest(name = "{0} values")
    @ValueSource(ints = {0, 1})
    void encodeCascade_underTwoValues_isNotApplicable(int n) {
        // Given — Rust's try_downcast leaves such an array un-narrowed, a canonical vortex.decimal
        long[] values = new long[n];
        DType dtype = DType.Decimal.of(15, 2, false);
        var sut = new DecimalBytePartsEncodingEncoder();

        // When
        CascadeStep result = sut.encodeCascade(dtype, values, EncodeTestHelper.testCtx());

        // Then
        assertThat(result.applicable()).isFalse();
    }

    @Test
    void encodeCascade_opensTheNarrowedValueChildForTheCascade() {
        // Given — l_quantity-like values: 1.00..50.00 at scale 2, an i16 child
        BigDecimal[] values = {new BigDecimal("1.00"), new BigDecimal("50.00"), new BigDecimal("17.00")};
        DType dtype = DType.Decimal.of(15, 2, false);
        var sut = new DecimalBytePartsEncodingEncoder();

        // When
        CascadeStep result = sut.encodeCascade(dtype, values, EncodeTestHelper.testCtx());

        // Then — one open child for the cascade to compress, not a terminal primitive
        assertThat(result.applicable()).isTrue();
        assertThat(result.openChildren()).singleElement().satisfies(child -> {
            assertThat(child.childDtype()).isEqualTo(new DType.Primitive(PType.I16, false));
            assertThat((short[]) child.childData()).containsExactly((short) 100, (short) 5000, (short) 1700);
            assertThat(child.excluded()).contains(EncodingId.VORTEX_DECIMAL_BYTE_PARTS);
        });
        assertThat(result.statsMin()).isEqualTo(ZoneMapStats.ofDecimal(new long[]{100L, 5000L, 1700L}).min());
    }

    /// The byte-parts layout stores the whole unscaled value in one i64 mantissa (no low parts), so
    /// a decimal wider than 18 digits is split only when its values fit a long, as Rust's
    /// `narrowed_decimal` finds from the data: the cascade steps aside for a value that does not.
    @ParameterizedTest(name = "precision {0}, unscaled {1}: applicable={2}")
    @CsvSource({"18, 5, true", "19, 5, true", "38, 5, true", "38, 9223372036854775807, true",
            "38, 9223372036854775808, false", "38, -9223372036854775809, false",
            "38, 12345678901234567890123, false"})
    void encodeCascade_splitsAWideDecimal_onlyWhenItsValuesFitAnI64(int precision, String unscaled,
            boolean expected) {
        // Given
        var sut = new DecimalBytePartsEncodingEncoder();
        DType dtype = new DType.Decimal((byte) precision, (byte) 0, false);
        BigDecimal[] values = {new BigDecimal(unscaled), new BigDecimal("7")};

        // When
        CascadeStep result = sut.encodeCascade(dtype, values, EncodeTestHelper.testCtx());

        // Then
        assertThat(result.applicable()).isEqualTo(expected);
    }

    @Test
    void encode_aValueThatDoesNotFitAnI64_throws() {
        // Given
        var sut = new DecimalBytePartsEncodingEncoder();
        DType dtype = new DType.Decimal((byte) 38, (byte) 0, false);
        BigDecimal[] values = {new BigDecimal("9223372036854775808")};

        // When / Then
        assertThatThrownBy(() -> sut.encode(dtype, values, EncodeTestHelper.testCtx()))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("does not fit");
    }
}
