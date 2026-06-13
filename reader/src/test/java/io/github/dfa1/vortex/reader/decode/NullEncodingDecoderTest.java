package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.reader.ReadRegistry;

import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.encoding.EncodingId;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;

class NullEncodingDecoderTest {

    @Test
    void decode_nullArray_returnsNullArrayWithCorrectLength() {
        // Given
        long rowCount = 42L;
        ArrayNode node = ArrayNode.of(EncodingId.VORTEX_NULL, null, new ArrayNode[0], new int[0]);
        DecodeContext ctx = new DecodeContext(node, DTypes.NULL, rowCount, new MemorySegment[0],
                ReadRegistry.empty(), Arena.ofAuto());
        var sut = new NullEncodingDecoder();

        // When
        var result = sut.decode(ctx);

        // Then
        assertThat(result).isInstanceOf(NullArray.class);
        assertThat(result.length()).isEqualTo(rowCount);
        assertThat(result.dtype()).isEqualTo(DTypes.NULL);
    }
}
