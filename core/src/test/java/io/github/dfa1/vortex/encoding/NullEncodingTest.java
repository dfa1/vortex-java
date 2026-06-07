package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.NullArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;

class NullEncodingTest {

    @Nested
    class Encode {

        @Test
        void encode_producesEmptyNode() {
            // Given
            var sut = new NullEncoding();

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
            var sut = new NullEncoding();

            // When
            EncodeResult encoded = sut.encode(DTypes.NULL, null, EncodeTestHelper.testCtx());
            ArrayNode node = ArrayNode.of(encoded.rootNode().encodingId(), null, new ArrayNode[0], new int[0], null);
            DecodeContext ctx = new DecodeContext(node, DTypes.NULL, rowCount, new MemorySegment[0],
                    EncodingRegistry.empty(), Arena.ofAuto());

            // Then
            var decoded = sut.decode(ctx);
            assertThat(decoded).isInstanceOf(NullArray.class);
            assertThat(decoded.length()).isEqualTo(rowCount);
        }
    }

    @Nested
    class Decode {

        private static DecodeContext buildNullCtx(long rowCount) {
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_NULL, null, new ArrayNode[0], new int[0], null);
            EncodingRegistry registry = EncodingRegistry.builder().register(new NullEncoding()).build();
            return new DecodeContext(node, DTypes.NULL, rowCount, new MemorySegment[0], registry, Arena.ofAuto());
        }

        @Test
        void decode_nullArray_returnsNullArrayWithCorrectLength() {
            // Given
            long rowCount = 42L;
            DecodeContext ctx = buildNullCtx(rowCount);
            var sut = new NullEncoding();

            // When
            var result = sut.decode(ctx);

            // Then
            assertThat(result).isInstanceOf(NullArray.class);
            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result.dtype()).isEqualTo(DTypes.NULL);
        }
    }
}
