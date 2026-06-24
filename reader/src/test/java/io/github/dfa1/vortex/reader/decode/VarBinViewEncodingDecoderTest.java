package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VarBinViewEncodingDecoderTest {

    private static final VarBinViewEncodingDecoder SUT = new VarBinViewEncodingDecoder();

    @Test
    void encodingId_isVortexVarBinView() {
        // Given / When / Then
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.VORTEX_VARBINVIEW);
    }

    @Test
    void decode_binaryDtype_inlineViews() {
        // Given two short (inline) values under a Binary dtype — exercises the
        // Binary branch the UTF8-only encoder tests never reach
        Arena arena = Arena.ofAuto();
        byte[] a = "hi".getBytes(StandardCharsets.UTF_8);
        byte[] b = "bye".getBytes(StandardCharsets.UTF_8);
        MemorySegment views = arena.allocate(2 * 16);
        writeInlineView(views, 0, a);
        writeInlineView(views, 1, b);

        ArrayNode node = ArrayNode.of(EncodingId.VORTEX_VARBINVIEW, null, new ArrayNode[0], new int[]{0});
        DecodeContext ctx = new DecodeContext(node, DType.BINARY, 2,
                new MemorySegment[]{views}, ReadRegistry.empty(), arena);

        // When
        Array result = SUT.decode(ctx);

        // Then
        VarBinArray arr = (VarBinArray) result;
        assertThat(arr.length()).isEqualTo(2);
        assertThat(arr.getBytes(0)).containsExactly(a);
        assertThat(arr.getBytes(1)).containsExactly(b);
    }

    @Test
    void decode_wrongDtype_throws() {
        // Given a primitive dtype
        ArrayNode node = ArrayNode.of(EncodingId.VORTEX_VARBINVIEW, null, new ArrayNode[0], new int[]{0});
        DecodeContext ctx = new DecodeContext(node, new DType.Primitive(io.github.dfa1.vortex.core.model.PType.I32, false),
                0, new MemorySegment[]{Arena.ofAuto().allocate(16)}, ReadRegistry.empty(), Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> SUT.decode(ctx))
                .hasMessageContaining("expected Utf8/Binary dtype");
    }

    @Test
    void decode_noBuffers_throws() {
        // Given a node with zero buffer indices
        ArrayNode node = ArrayNode.of(EncodingId.VORTEX_VARBINVIEW, null, new ArrayNode[0], new int[0]);
        DecodeContext ctx = new DecodeContext(node, DType.UTF8, 0,
                new MemorySegment[0], ReadRegistry.empty(), Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> SUT.decode(ctx))
                .hasMessageContaining("at least 1 buffer");
    }

    /// Writes a ≤12-byte inline view: length prefix then the bytes packed in-place.
    private static void writeInlineView(MemorySegment views, int row, byte[] bytes) {
        long off = (long) row * 16;
        views.set(PTypeIO.LE_INT, off, bytes.length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, views, off + 4, bytes.length);
    }
}
