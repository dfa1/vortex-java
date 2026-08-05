package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Nested;
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

        ArrayNode node = new ArrayNode(EncodingId.VORTEX_VARBINVIEW, null, new ArrayNode[0], new int[]{0});
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
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_VARBINVIEW, null, new ArrayNode[0], new int[]{0});
        DecodeContext ctx = new DecodeContext(node, new DType.Primitive(io.github.dfa1.vortex.core.model.PType.I32, false),
                0, new MemorySegment[]{Arena.ofAuto().allocate(16)}, ReadRegistry.empty(), Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> SUT.decode(ctx))
                .hasMessageContaining("expected Utf8/Binary dtype");
    }

    @Test
    void decode_noBuffers_throws() {
        // Given a node with zero buffer indices
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_VARBINVIEW, null, new ArrayNode[0], new int[0]);
        DecodeContext ctx = new DecodeContext(node, DType.UTF8, 0,
                new MemorySegment[0], ReadRegistry.empty(), Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> SUT.decode(ctx))
                .hasMessageContaining("at least 1 buffer");
    }

    /// Adversarial views from an untrusted file (TODO.md §Security, per-encoding adversarial
    /// tests). The view headers are decoded lazily and were read with no validation at all:
    /// a negative size reached `new byte[size]` as a `NegativeArraySizeException`, a bogus
    /// buffer index indexed `dataBufs` as a raw `ArrayIndexOutOfBoundsException`, and a row
    /// past the views segment (or a data offset past its buffer) escaped as a raw
    /// `IndexOutOfBoundsException`.
    @Nested
    class AdversarialViews {

        @Test
        void negativeViewSize_getBytes_throws() {
            // Given a view whose size field is -1
            VarBinArray array = decodeViews(1, views -> views.set(VortexFormat.LE_INT, 0, -1));

            // When / Then
            assertThatThrownBy(() -> array.getBytes(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("negative varbin view size");
        }

        @Test
        void negativeViewSize_getByteLength_throws() {
            // Given the same header read through the length accessor
            VarBinArray array = decodeViews(1, views -> views.set(VortexFormat.LE_INT, 0, -1));

            // When / Then
            assertThatThrownBy(() -> array.getByteLength(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("negative varbin view size");
        }

        @Test
        void rowPastViewsSegment_throws() {
            // Given 3 declared rows but only one 16-byte view on the wire
            VarBinArray array = decodeViews(3, views -> views.set(VortexFormat.LE_INT, 0, 2));

            // When / Then
            assertThatThrownBy(() -> array.getBytes(2))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for a views segment");
        }

        @Test
        void bufferIndexPastDataBuffers_throws() {
            // Given a long (non-inlined) view pointing at data buffer 7 when the decoder
            // handed the array a single buffer
            VarBinArray array = decodeViews(1, views -> {
                views.set(VortexFormat.LE_INT, 0, 20);      // size > 12 -> long view
                views.set(VortexFormat.LE_INT, 8, 7);       // buffer index
                views.set(VortexFormat.LE_INT, 12, 0);      // offset within buffer
            });

            // When / Then
            assertThatThrownBy(() -> array.getBytes(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("references data buffer 7");
        }

        @Test
        void dataOffsetPastBuffer_throws() {
            // Given a long view whose [offset, offset + size) window runs past its buffer
            VarBinArray array = decodeViews(1, views -> {
                views.set(VortexFormat.LE_INT, 0, 20);
                views.set(VortexFormat.LE_INT, 8, 0);
                views.set(VortexFormat.LE_INT, 12, 900);
            }, MemorySegment.ofArray(new byte[32]));

            // When / Then
            assertThatThrownBy(() -> array.getBytes(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for data buffer 0");
        }

        @Test
        void truncatedViewsSegment_forEachByteLength_throws() {
            // Given 3 declared rows over a single view: the bulk length walk sizes the
            // segment once up front instead of running off it
            VarBinArray array = decodeViews(3, views -> views.set(VortexFormat.LE_INT, 0, 2));

            // When / Then
            assertThatThrownBy(() -> array.forEachByteLength(len -> {
            }))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("holds fewer than 3 views");
        }

        @Test
        void truncatedViewsSegment_limited_throws() {
            // Given the same shape truncated by a scan limit
            VarBinArray array = decodeViews(3, views -> views.set(VortexFormat.LE_INT, 0, 2));

            // When / Then
            assertThatThrownBy(() -> array.limited(2))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("holds fewer than 2 views");
        }

        private VarBinArray decodeViews(long rowCount, java.util.function.Consumer<MemorySegment> writer,
                MemorySegment... dataBufs) {
            Arena arena = Arena.ofAuto();
            MemorySegment views = arena.allocate(16);
            writer.accept(views);
            // The decoder takes the views from the LAST buffer; data buffers come first.
            MemorySegment[] segs = new MemorySegment[dataBufs.length + 1];
            System.arraycopy(dataBufs, 0, segs, 0, dataBufs.length);
            segs[dataBufs.length] = views;
            int[] bufferIndices = new int[segs.length];
            for (int i = 0; i < segs.length; i++) {
                bufferIndices[i] = i;
            }
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_VARBINVIEW, null, new ArrayNode[0], bufferIndices);
            DecodeContext ctx = new DecodeContext(node, DType.UTF8, rowCount, segs, ReadRegistry.empty(), arena);
            return (VarBinArray) SUT.decode(ctx);
        }
    }

    /// Writes a ≤12-byte inline view: length prefix then the bytes packed in-place.
    private static void writeInlineView(MemorySegment views, int row, byte[] bytes) {
        long off = (long) row * 16;
        views.set(VortexFormat.LE_INT, off, bytes.length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, views, off + 4, bytes.length);
    }
}
