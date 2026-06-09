package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.proto.EncodingProtos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AlpRdEncodingTest {

    @Nested
    class Encode {

        @Test
        void encode_f64_roundTrip() {
            // Given
            double[] values = {0.1, 0.2, 0.3, 0.4, 0.5};
            AlpRdEncoding sut = new AlpRdEncoding();
            Registry registry = TestRegistry.of(sut, new BitpackedEncoding(), new PrimitiveEncoding());

            // When
            EncodeResult encoded = sut.encode(DTypes.F64, values, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, values.length, DTypes.F64, registry);
            var result = sut.decode(ctx);

            // Then
            var layout = PTypeIO.LE_DOUBLE;
            for (int i = 0; i < values.length; i++) {
                assertThat(ArraySegments.of(result).get(layout, (long) i * 8))
                        .as("index %d", i).isCloseTo(values[i], within(1e-9));
            }
        }

        @Test
        void encode_f64_metadata_rightBitWidth_isNonZero() throws Exception {
            // Given — ALPRD splits F64 into left+right parts; right_bit_width>0 means real encoding happened
            // if tag drifts, right_bit_width reads as 0 (proto3 default) and right parts are all zero
            double[] values = {0.1, 0.2, 0.3, 0.4, 0.5};
            AlpRdEncoding sut = new AlpRdEncoding();

            // When
            EncodeResult result = sut.encode(DTypes.F64, values, EncodeTestHelper.testCtx());
            EncodingProtos.ALPRDMetadata meta =
                    EncodingProtos.ALPRDMetadata.parseFrom(result.rootNode().metadata().duplicate());

            // Then
            assertThat(meta.getRightBitWidth()).isGreaterThan(0);
        }
    }
}
