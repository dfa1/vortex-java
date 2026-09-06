package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.fbs.FbsArray;
import io.github.dfa1.vortex.core.fbs.FbsArrayNode;
import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Adversarial tests for the encoded-array-tree recursion in
/// [SerializedArrayDecoder]'s `convertArrayNode`.
///
/// The decoder walks the array node tree recursively (validity, patches, run-ends, dictionary
/// codes/values, …). Without the [SerializedArrayDecoder#MAX_ARRAY_TREE_DEPTH] cap a crafted segment
/// with thousands of nested children produces a [StackOverflowError] — an `Error` that escapes the
/// "malformed input must surface as [VortexException]" contract (ADR 0003).
///
/// The two cases bracket the exact `depth > MAX_ARRAY_TREE_DEPTH` boundary: a tree whose deepest
/// node sits at exactly the limit must clear the guard (and then fail later for an unrelated reason
/// — no decoder), while one node deeper must be rejected by the depth guard itself.
class ArrayNodeDepthBombSecurityTest {

    private static final DType DTYPE = DType.I32;

    private final SerializedArrayDecoder sut = new SerializedArrayDecoder(ReadRegistry.empty());

    @Test
    void arrayTreeAtDepthLimit_clearsGuard() {
        try (Arena arena = Arena.ofConfined()) {
            // Given — deepest node at exactly MAX_ARRAY_TREE_DEPTH: convertArrayNode is entered with
            // depth == limit there, and `limit > limit` is false. The walk completes and only then
            // fails because the empty registry has no decoder. Kills `depth >` relaxed to `>=`,
            // which would wrongly reject this legal max-depth tree with the depth message.
            byte[] fb = deeplyNestedArrayFlatBuffer(SerializedArrayDecoder.MAX_ARRAY_TREE_DEPTH);
            MemorySegment seg = wrapAsSegment(fb, arena);
            List<EncodingId> encodings = List.of(EncodingId.parse("vortex.flat"));

            // When / Then
            assertThatThrownBy(() -> sut.decode(seg, encodings, DTYPE, 1, arena))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("no decoder");
        }
    }

    @Test
    void arrayTreeOneOverDepthLimit_throwsVortexException() {
        try (Arena arena = Arena.ofConfined()) {
            // Given — one level deeper: the deepest node reaches limit + 1, tripping the guard
            // before any StackOverflowError can escape.
            byte[] fb = deeplyNestedArrayFlatBuffer(SerializedArrayDecoder.MAX_ARRAY_TREE_DEPTH + 1);
            MemorySegment seg = wrapAsSegment(fb, arena);
            List<EncodingId> encodings = List.of(EncodingId.parse("vortex.flat"));

            // When / Then — must surface as VortexException, not StackOverflowError
            assertThatThrownBy(() -> sut.decode(seg, encodings, DTYPE, 1, arena))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("depth");
        }
    }

    private static MemorySegment wrapAsSegment(byte[] fb, Arena arena) {
        MemorySegment seg = arena.allocate(fb.length + 4L);
        MemorySegment.copy(MemorySegment.ofArray(fb), 0, seg, 0, fb.length);
        seg.set(VortexFormat.LE_INT, fb.length, fb.length);
        return seg;
    }

    /// Builds a minimal `FbsArray` whose root node has `depth` levels of single-child nesting,
    /// each level a buffer-less node referencing encoding index 0.
    private static byte[] deeplyNestedArrayFlatBuffer(int depth) {
        FbsBuilder b = new FbsBuilder(depth * 32);
        // Leaf first; FlatBuffer requires children be finished before parents.
        int emptyChildren = FbsArrayNode.createChildrenVector(b, new int[0]);
        int emptyBuffers = FbsArrayNode.createBuffersVector(b, new int[0]);
        int current = FbsArrayNode.createFbsArrayNode(b, 0, 0, emptyChildren, emptyBuffers, 0);
        for (int i = 0; i < depth; i++) {
            int childV = FbsArrayNode.createChildrenVector(b, new int[]{current});
            int bufV = FbsArrayNode.createBuffersVector(b, new int[0]);
            current = FbsArrayNode.createFbsArrayNode(b, 0, 0, childV, bufV, 0);
        }
        FbsArray.startBuffersVector(b, 0);
        int buffers = b.endVector();
        int array = FbsArray.createFbsArray(b, current, buffers);
        FbsArray.finishFbsArrayBuffer(b, array);
        return b.sizedByteArray();
    }
}
