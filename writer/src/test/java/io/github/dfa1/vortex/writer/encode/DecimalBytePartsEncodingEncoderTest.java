package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.GenericArray;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.proto.DecimalBytePartsMetadata;
import io.github.dfa1.vortex.reader.decode.DecimalBytePartsEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DecimalBytePartsEncodingEncoderTest {

    @Test
    void roundTrip_longArray_preservesMspValues() {
        // Given
        long[] values = {1L, -2L, 3L};
        DType dtype = new DType.Decimal((byte) 18, (byte) 0, false);
        var encoder = new DecimalBytePartsEncodingEncoder();
        var decoder = new DecimalBytePartsEncodingDecoder();
        ReadRegistry registry = TestRegistry.ofDecoders(decoder, new PrimitiveEncodingDecoder());

        // When
        EncodeResult encoded = encoder.encode(dtype, values, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, values.length, dtype, registry);
        GenericArray result = (GenericArray) decoder.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(values.length);
        Array msp = result.child(0);
        assertThat(msp.length()).isEqualTo(values.length);
        for (int i = 0; i < values.length; i++) {
            assertThat(ArraySegments.of(msp).get(PTypeIO.LE_LONG, (long) i * 8)).isEqualTo(values[i]);
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
        byte[] metaBytes = new byte[result.rootNode().metadata().remaining()];
        result.rootNode().metadata().duplicate().get(metaBytes);
        DecimalBytePartsMetadata meta =
                DecimalBytePartsMetadata.decode(java.lang.foreign.MemorySegment.ofArray(metaBytes), 0, metaBytes.length);
        assertThat(meta.zeroth_child_ptype().value()).isEqualTo(7); // I64 ordinal
        assertThat(meta.lower_part_count()).isEqualTo(0);
    }
}
