package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

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

        // Then — VarBinArray.ConstantMode, not an eagerly materialized OffsetMode
        assertThat(result).isInstanceOf(VarBinArray.ConstantMode.class);
        VarBinArray strings = (VarBinArray) result;
        assertThat(strings.getString(0)).isEqualTo("hi");
        assertThat(strings.getString(2)).isEqualTo("hi");
    }

    /// Row count no longer bounds the work `decodeString` does: it used to eagerly allocate
    /// and copy `n` string repetitions into a real buffer (`n * strLen`), which for a large
    /// `n` was both wasted work and an integer-overflow/OOM risk (#329). `ConstantMode` is
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
