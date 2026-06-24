package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.reader.array.LazyDecimalBytePartsArray;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.proto.ProtoDecimalBytePartsMetadata;
import io.github.dfa1.vortex.reader.decode.DecimalBytePartsEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void metadata_zerothChildPtype_isI64_lowerPartCountIsZero() throws Exception {
        // Given
        long[] values = {42L};
        DType dtype = new DType.Decimal((byte) 18, (byte) 0, false);
        var sut = new DecimalBytePartsEncodingEncoder();

        // When
        EncodeResult result = sut.encode(dtype, values, EncodeTestHelper.testCtx());

        // Then
        java.lang.foreign.MemorySegment metaSeg = result.rootNode().metadata();
        ProtoDecimalBytePartsMetadata meta =
                ProtoDecimalBytePartsMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        assertThat(meta.zeroth_child_ptype().value()).isEqualTo(7); // I64 ordinal
        assertThat(meta.lower_part_count()).isZero();
    }
}
