package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoNullValue;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.Float16Array;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazyConstantDecimalArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinConstantArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConstantEncodingDecoderTest {

    private static final ConstantEncodingDecoder SUT = new ConstantEncodingDecoder();

    @Test
    void encodingId_isVortexConstant() {
        // Given / When / Then
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.VORTEX_CONSTANT);
    }

    @Test
    void primitiveScalar_missingAllValueFields_decodesAsZero() {
        // Given — a scalar with every oneof field null (no tag matched the declared I64
        // dtype); scalarToRawBits() has an explicit fallback for this, so it must not crash.
        ProtoScalarValue scalar = new ProtoScalarValue(null, null, null, null, null, null, null, null, null, null, null);

        // When
        Array result = decode(scalar, DType.I64, 3);

        // Then
        LongArray longs = (LongArray) result;
        assertThat(longs.getLong(0)).isZero();
        assertThat(longs.getLong(2)).isZero();
    }

    /// A null constant, which Rust and our writer emit for an all-null chunk.
    @Nested
    class NullScalar {

        private static final ProtoScalarValue NULL = ProtoScalarValue.ofNullValue(ProtoNullValue.NULL_VALUE);
        private static final long ROWS = 1_000;

        @ParameterizedTest
        @MethodSource("maskableTypes")
        void decodesAsAMaskedArrayWithEveryRowInvalid(DType dtype, Class<?> payload) {
            // Given: a type with a placeholder value; it read as a NullArray, which every consumer
            // that unwraps a MaskedArray and casts its payload failed on

            // When
            Array result = decode(NULL, dtype, ROWS);

            // Then: the column's own type, masked like any nullable chunk, with no valid row
            assertThat(result).isInstanceOf(MaskedArray.class);
            assertThat(result.dtype()).isEqualTo(dtype);
            assertThat(result.length()).isEqualTo(ROWS);
            MaskedArray masked = (MaskedArray) result;
            assertThat(masked.inner()).isInstanceOf(payload);
            assertThat(masked.inner().dtype()).isEqualTo(dtype.withNullable(false));
            assertThat(masked.inner().length()).isEqualTo(ROWS);
            for (long i = 0; i < ROWS; i++) {
                assertThat(masked.isValid(i)).isFalse();
            }
        }

        static Stream<Arguments> maskableTypes() {
            List<Arguments> cases = new ArrayList<>();
            for (PType ptype : PType.values()) {
                cases.add(Arguments.of(new DType.Primitive(ptype, true), payloadOf(ptype)));
            }
            cases.add(Arguments.of(DType.BOOL.asNullable(), BoolArray.class));
            cases.add(Arguments.of(DType.UTF8.asNullable(), VarBinArray.class));
            cases.add(Arguments.of(DType.BINARY.asNullable(), VarBinArray.class));
            cases.add(Arguments.of(new DType.Decimal((byte) 10, (byte) 2, true), DecimalArray.class));
            cases.add(Arguments.of(new DType.Extension("vortex.date", DType.I32, MemorySegment.ofArray(new byte[]{0}), true),
                    IntArray.class));
            cases.add(Arguments.of(new DType.Extension("vortex.time", DType.I32, MemorySegment.ofArray(new byte[]{1}), true),
                    IntArray.class));
            cases.add(Arguments.of(new DType.Extension("vortex.timestamp", DType.I64,
                    MemorySegment.ofArray(new byte[]{1, 0, 0}), true), LongArray.class));
            cases.add(Arguments.of(new DType.Extension("acme.custom", DType.U16, null, true), ShortArray.class));
            return cases.stream();
        }

        @ParameterizedTest
        @CsvSource({"2, 1", "18, 0", "38, 10", "76, 38"})
        void aNullDecimalPlaceholderHasTheColumnsWidthAndScale(int precision, int scale) {
            // Given: chunks of one column are combined by width, so the placeholder must match
            // the width a valued chunk of the same column has
            DType.Decimal dtype = new DType.Decimal((byte) precision, (byte) scale, true);

            // When
            Array result = decode(NULL, dtype, ROWS);

            // Then
            LazyConstantDecimalArray placeholder = (LazyConstantDecimalArray) ((MaskedArray) result).inner();
            assertThat(placeholder.byteWidth()).isEqualTo(dtype.byteWidth());
            assertThat(placeholder.value().scale()).isEqualTo(scale);
        }

        @ParameterizedTest
        @MethodSource("unmaskableTypes")
        void staysANullArrayForATypeWithoutAPlaceholder(DType dtype) {
            // Given: no scalar can stand under the mask here; it must still decode, not throw

            // When
            Array result = decode(NULL, dtype, ROWS);

            // Then
            assertThat(result).isInstanceOf(NullArray.class);
            assertThat(result.length()).isEqualTo(ROWS);
        }

        static Stream<DType> unmaskableTypes() {
            return Stream.of(
                    new DType.Null(true),
                    new DType.List(DType.I64, true),
                    new DType.FixedSizeList(DType.I32, 3, true),
                    new DType.Struct(List.of(ColumnName.of("a")), List.of(DType.I64), true),
                    new DType.Map(DType.I64, DType.I64, false, true),
                    new DType.Variant(true),
                    new DType.Extension("vortex.uuid", new DType.FixedSizeList(DType.U8, 16, false), null, true));
        }

        @Test
        void aZeroRowNullConstantIsAnEmptyMaskedArray() {
            // When
            Array result = decode(NULL, DType.I64.asNullable(), 0);

            // Then
            assertThat(result).isInstanceOf(MaskedArray.class);
            assertThat(result.length()).isZero();
        }

        @Test
        void aNullScalarWinsOverANonNullableDtype() {
            // Given: a crafted file declaring a non-nullable column but storing a null constant;
            // every row is still null, as the scalar says
            // When
            Array result = decode(NULL, DType.I64, ROWS);

            // Then
            assertThat(result).isInstanceOf(MaskedArray.class);
            assertThat(((MaskedArray) result).isValid(0)).isFalse();
        }

        private static Class<?> payloadOf(PType ptype) {
            return switch (ptype) {
                case I8, U8 -> ByteArray.class;
                case I16, U16 -> ShortArray.class;
                case F16 -> Float16Array.class;
                case I32, U32 -> IntArray.class;
                case I64, U64 -> LongArray.class;
                case F32 -> FloatArray.class;
                case F64 -> DoubleArray.class;
            };
        }
    }

    /// A scalar whose oneof tag doesn't match the declared Decimal dtype (e.g. only
    /// int64_value set, `bytes_value` absent) previously threw a raw NullPointerException
    /// reading `bytes_value().length` instead of a [VortexException] (ADR 0003).
    @Test
    void decimalScalar_missingBytesValue_throwsVortexException() {
        // Given — int64_value set, bytes_value absent, for a Decimal-typed constant
        ProtoScalarValue scalar = new ProtoScalarValue(null, null, 42L, null, null, null, null, null, null, null, null);
        DType decimalDtype = new DType.Decimal((byte) 10, (byte) 2, false);

        // When / Then
        assertThatThrownBy(() -> decode(scalar, decimalDtype, 1))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("bytes_value");
    }

    @Test
    void decimalScalar_withBytesValue_decodes() {
        // Given — a 4-byte little-endian two's-complement decimal, scale 2 → 12345 / 100
        ProtoScalarValue scalar = new ProtoScalarValue(
                null, null, null, null, null, null, null,
                new byte[]{(byte) 0x39, (byte) 0x30, (byte) 0x00, (byte) 0x00}, null, null, null);
        DType decimalDtype = new DType.Decimal((byte) 9, (byte) 2, false);

        // When
        Array result = decode(scalar, decimalDtype, 2);

        // Then
        assertThat(result.length()).isEqualTo(2);
    }

    @Test
    void stringScalar_decodesToLazyConstantVarBin() {
        // Given
        ProtoScalarValue scalar = new ProtoScalarValue(
                null, null, null, null, null, null, "hi", null, null, null, null);

        // When
        Array result = decode(scalar, DType.UTF8, 3);

        // Then — VarBinConstantArray, not an eagerly materialized VarBinOffsetArray
        assertThat(result).isInstanceOf(VarBinConstantArray.class);
        VarBinArray strings = (VarBinArray) result;
        assertThat(strings.getString(0)).isEqualTo("hi");
        assertThat(strings.getString(2)).isEqualTo("hi");
    }

    /// Row count no longer bounds the work `decodeString` does: it used to eagerly allocate
    /// and copy `n` string repetitions into a real buffer (`n * strLen`), which for a large
    /// `n` was both wasted work and an integer-overflow/OOM risk (#329). `VarBinConstantArray` is
    /// O(1) regardless of `n`, so a row count too large to ever materialize still decodes
    /// instantly.
    @Test
    void stringScalar_hugeRowCount_decodesWithoutAllocating() {
        // Given — a row count that would demand petabytes if eagerly materialized
        ProtoScalarValue scalar = new ProtoScalarValue(
                null, null, null, null, null, null, "x", null, null, null, null);
        long hugeRowCount = Long.MAX_VALUE / 2;

        // When
        Array result = decode(scalar, DType.UTF8, hugeRowCount);

        // Then
        assertThat(result.length()).isEqualTo(hugeRowCount);
        assertThat(((VarBinArray) result).getString(0)).isEqualTo("x");
    }

    private static Array decode(ProtoScalarValue scalar, DType dtype, long n) {
        MemorySegment scalarBuf = MemorySegment.ofArray(scalar.encode());
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_CONSTANT, null, new ArrayNode[0], new int[]{0});
        DecodeContext ctx = new DecodeContext(node, dtype, n, new MemorySegment[]{scalarBuf},
                ReadRegistry.empty(), Arena.ofAuto());
        return SUT.decode(ctx);
    }
}
