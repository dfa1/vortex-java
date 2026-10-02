package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoDecimalBytePartsMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DecimalBytePartsEncodingDecoderTest {

    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            new DecimalBytePartsEncodingDecoder(), new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());

    /// A nullable decimal keeps its validity on the mantissa child. Returning the bare
    /// LazyDecimalBytePartsArray hid the nulls from every consumer that checks validity first
    /// (MaskedArray), so CSV export called getDecimal on a null row and threw "null cell at
    /// index N" (Raincloud duckdb-tpcds-sf1-*: 10 datasets with nullable decimal columns).
    @Test
    void decode_nullable_exposesNullsAsMaskedArray() {
        // Given — decimal(7, 2) mantissas 12345, <null>, -1 on an i64 child with a validity child
        DecodeContext ctx = ctx(new long[]{12_345L, 0L, -1L}, new boolean[]{true, false, true});
        var sut = new DecimalBytePartsEncodingDecoder();

        // When
        var result = sut.decode(ctx);

        // Then
        assertThat(result).isInstanceOf(MaskedArray.class);
        MaskedArray masked = (MaskedArray) result;
        DecimalArray values = (DecimalArray) masked.inner();
        assertThat(masked.isValid(0)).isTrue();
        assertThat(values.getDecimal(0)).isEqualTo(new BigDecimal("123.45"));
        assertThat(masked.isValid(1)).isFalse();
        assertThat(masked.isValid(2)).isTrue();
        assertThat(values.getDecimal(2)).isEqualTo(new BigDecimal("-0.01"));
    }

    @Test
    void decode_nonNullable_returnsDecimalArray() {
        // Given — no validity child: nothing to hoist
        DecodeContext ctx = ctx(new long[]{12_345L, -1L}, null);
        var sut = new DecimalBytePartsEncodingDecoder();

        // When
        var result = sut.decode(ctx);

        // Then
        assertThat(result).isInstanceOf(DecimalArray.class);
        assertThat(((DecimalArray) result).getDecimal(1)).isEqualTo(new BigDecimal("-0.01"));
    }

    /// decimal_byte_parts node over an i64 `vortex.primitive` mantissa child, with a
    /// `vortex.bool` validity grandchild when `valid` is non-null.
    private static DecodeContext ctx(long[] mantissas, boolean[] valid) {
        boolean nullable = valid != null;
        MemorySegment values = MemorySegment.ofArray(new byte[mantissas.length * 8]);
        for (int i = 0; i < mantissas.length; i++) {
            values.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, i, mantissas[i]);
        }
        ArrayNode[] mspChildren = new ArrayNode[0];
        MemorySegment[] buffers = {values};
        if (nullable) {
            byte[] bits = new byte[(valid.length + 7) / 8];
            for (int i = 0; i < valid.length; i++) {
                if (valid[i]) {
                    bits[i >>> 3] |= (byte) (1 << (i & 7));
                }
            }
            mspChildren = new ArrayNode[]{new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{1})};
            buffers = new MemorySegment[]{values, MemorySegment.ofArray(bits)};
        }
        ArrayNode msp = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, mspChildren, new int[]{0});
        MemorySegment meta = MemorySegment.ofArray(new ProtoDecimalBytePartsMetadata(ProtoPType.I64, 0).encode());
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_DECIMAL_BYTE_PARTS, meta, new ArrayNode[]{msp}, new int[0]);
        return new DecodeContext(node, new DType.Decimal((byte) 7, (byte) 2, nullable), mantissas.length, buffers,
                REGISTRY, Arena.ofAuto());
    }
}
