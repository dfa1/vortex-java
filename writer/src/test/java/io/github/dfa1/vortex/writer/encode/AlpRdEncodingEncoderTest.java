package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.proto.ALPRDMetadata;
import io.github.dfa1.vortex.reader.decode.AlpRdEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.BitpackedEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AlpRdEncodingEncoderTest {

    @Test
    void encode_f64_roundTrip() {
        // Given
        double[] values = {0.1, 0.2, 0.3, 0.4, 0.5};
        var encoder = new AlpRdEncodingEncoder();
        var decoder = new AlpRdEncodingDecoder();
        ReadRegistry registry = TestRegistry.ofDecoders(decoder, new BitpackedEncodingDecoder(), new PrimitiveEncodingDecoder());

        // When
        EncodeResult encoded = encoder.encode(DTypes.F64, values, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, values.length, DTypes.F64, registry);
        var result = decoder.decode(ctx);

        // Then
        for (int i = 0; i < values.length; i++) {
            assertThat(((io.github.dfa1.vortex.reader.array.DoubleArray) result).getDouble(i))
                    .as("index %d", i).isCloseTo(values[i], within(1e-9));
        }
    }

    @Test
    void encode_f64_metadata_rightBitWidth_isNonZero() throws Exception {
        // Given — ALPRD splits F64 into left+right parts; right_bit_width>0 means real encoding happened
        // if tag drifts, right_bit_width reads as 0 (proto3 default) and right parts are all zero
        double[] values = {0.1, 0.2, 0.3, 0.4, 0.5};
        var sut = new AlpRdEncodingEncoder();

        // When
        EncodeResult result = sut.encode(DTypes.F64, values, EncodeTestHelper.testCtx());
        var metaSeg = java.lang.foreign.MemorySegment.ofBuffer(result.rootNode().metadata().duplicate());
        ALPRDMetadata meta = ALPRDMetadata.decode(metaSeg, 0, metaSeg.byteSize());

        // Then
        assertThat(meta.right_bit_width()).isGreaterThan(0);
    }
}
