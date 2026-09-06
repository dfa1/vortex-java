package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LongArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkedEncodingDecoderTest {

    private static final ChunkedEncodingDecoder SUT = new ChunkedEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            SUT, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());

    @Test
    void encodingId_isVortexChunked() {
        // Given / When / Then
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.VORTEX_CHUNKED);
    }

    @Test
    void decode_twoChunks_viewsBothWithoutConcatenating() {
        // Given — chunk offsets 0/2/5 over a 2-row and a 3-row i64 chunk
        MemorySegment[] segs = {
                TestSegments.leLongs(0, 2, 5),
                TestSegments.leLongs(10, 11),
                TestSegments.leLongs(12, 13, 14)
        };

        // When
        Array result = decode(DType.I64, 5, segs, primitiveNode(0), primitiveNode(1), primitiveNode(2));

        // Then — a single logical i64 view spanning both chunks
        assertThat(result).isInstanceOf(LongArray.class);
        LongArray longs = (LongArray) result;
        assertThat(longs.length()).isEqualTo(5);
        assertThat(longs.getLong(0)).isEqualTo(10);
        assertThat(longs.getLong(2)).isEqualTo(12);
        assertThat(longs.getLong(4)).isEqualTo(14);
    }

    /// Malformed-input cases for `vortex.chunked` (TODO.md §Security, ADR 0003). The child count
    /// and the chunk-offsets buffer both come from untrusted file bytes; the class named in each
    /// comment is the raw JDK exception the reader leaked before the guards landed.
    ///
    /// The TODO's other Chunked item — a maliciously deep child tree — is guarded and tested one
    /// layer up, at the point the `ArrayNode` tree is built from the file's FlatBuffer: see
    /// [io.github.dfa1.vortex.reader.SerializedArrayDecoder] `MAX_ARRAY_TREE_DEPTH` and
    /// `ArrayNodeDepthBombSecurityTest`. FlatBuffers cannot encode a true cycle, so the depth cap
    /// is the whole defense and nothing here can reproduce it from a hand-built node.
    @Nested
    class AdversarialInput {

        @Test
        void noChildren_throws() {
            // Given — a chunked node with no children at all, not even the offsets child
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_CHUNKED, null, new ArrayNode[0], new int[0]);
            DecodeContext ctx = new DecodeContext(node, DType.I64, 3, new MemorySegment[0],
                    REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("at least one child");
        }

        /// The TODO's "zero children with non-zero row_count": a node carrying only the offsets
        /// child describes no data, yet claims rows. The primitive families surfaced this as the
        /// `Chunked*Array` "empty chunk list" error and a struct dtype swallowed it silently, so
        /// the decoder now names the real defect uniformly.
        @Test
        void zeroChunksWithNonZeroRowCount_throws() {
            // Given — only the offsets child, but 10 rows claimed
            MemorySegment[] segs = {TestSegments.leLongs(0)};
            ArrayNode offsetsChild = primitiveNode(0);

            // When / Then
            assertThatThrownBy(() -> decode(DType.I64, 10, segs, offsetsChild))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("no chunks for 10 row(s)");
        }

        /// `ArithmeticException: / by zero` — the offsets read broadcasts an undersized buffer
        /// with `i % capacity`, and an empty offsets child makes that capacity zero.
        @Test
        void emptyOffsetsChild_throws() {
            // Given — a zero-byte offsets segment in front of one real chunk
            MemorySegment[] segs = {MemorySegment.ofArray(new byte[0]), TestSegments.leLongs(1, 2, 3)};
            ArrayNode offsetsChild = primitiveNode(0);
            ArrayNode chunkChild = primitiveNode(1);

            // When / Then
            assertThatThrownBy(() -> decode(DType.I64, 3, segs, offsetsChild, chunkChild))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("decoded child segment is empty");
        }

        /// `IndexOutOfBoundsException` — a decreasing offsets pair yields a negative chunk length.
        /// The chunk lengths still summed to the declared row count here, so the `Chunked*Array`
        /// row-count check passed and left an unsorted `offsets` array behind, whose binary-search
        /// dispatch then indexed a chunk at a negative row.
        @Test
        void nonMonotonicOffsets_throws() {
            // Given — offsets 0/5/3: chunk 0 spans 5 rows, chunk 1 spans -2, summing to the
            // declared 3 rows so nothing downstream catches the inversion.
            MemorySegment[] segs = {
                    TestSegments.leLongs(0, 5, 3),
                    TestSegments.leLongs(1, 2, 3, 4, 5),
                    TestSegments.leLongs(9, 9)
            };
            ArrayNode offsetsChild = primitiveNode(0);
            ArrayNode firstChunkChild = primitiveNode(1);
            ArrayNode secondChunkChild = primitiveNode(2);

            // When / Then
            assertThatThrownBy(() -> decode(DType.I64, 3, segs,
                    offsetsChild, firstChunkChild, secondChunkChild))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("non-decreasing");
        }

        /// The same guard's lower bound: offsets are cumulative row counts, so the first one is
        /// never negative. A negative start would make every chunk length nonsense.
        @Test
        void negativeFirstOffset_throws() {
            // Given — a first offset of -1
            MemorySegment[] segs = {TestSegments.leLongs(-1, 2), TestSegments.leLongs(1, 2, 3)};
            ArrayNode offsetsChild = primitiveNode(0);
            ArrayNode chunkChild = primitiveNode(1);

            // When / Then
            assertThatThrownBy(() -> decode(DType.I64, 3, segs, offsetsChild, chunkChild))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("non-decreasing");
        }
    }

    private static Array decode(DType dtype, long rowCount, MemorySegment[] segs, ArrayNode... children) {
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_CHUNKED, null, children, new int[0]);
        return SUT.decode(new DecodeContext(node, dtype, rowCount, segs, REGISTRY, Arena.ofAuto()));
    }

    private static ArrayNode primitiveNode(int bufferIndex) {
        return new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{bufferIndex});
    }
}
