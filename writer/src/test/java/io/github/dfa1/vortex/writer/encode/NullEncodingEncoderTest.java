package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.NullEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;

class NullEncodingEncoderTest {

    @Nested
    class Encode {

        @Test
        void encode_producesEmptyNode() {
            // Given
            var sut = new NullEncodingEncoder();

            // When
            EncodeResult result = sut.encode(DTypes.NULL, null, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_NULL);
            assertThat(result.rootNode().children()).isEmpty();
            assertThat(result.buffers()).isEmpty();
        }

        @Test
        void encode_thenDecode_roundTrips() {
            // Given
            long rowCount = 10L;
            var encoder = new NullEncodingEncoder();
            var decoder = new NullEncodingDecoder();

            // When
            EncodeResult encoded = encoder.encode(DTypes.NULL, null, EncodeTestHelper.testCtx());
            ArrayNode node = ArrayNode.of(encoded.rootNode().encodingId(), null, new ArrayNode[0], new int[0], null);
            DecodeContext ctx = new DecodeContext(node, DTypes.NULL, rowCount, new MemorySegment[0],
                    ReadRegistry.empty(), Arena.ofAuto());

            // Then
            var decoded = decoder.decode(ctx);
            assertThat(decoded).isInstanceOf(NullArray.class);
            assertThat(decoded.length()).isEqualTo(rowCount);
        }
    }
}
