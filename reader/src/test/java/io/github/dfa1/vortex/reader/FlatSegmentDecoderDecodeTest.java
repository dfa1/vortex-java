package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.fbs.FbsArrayNode;
import io.github.dfa1.vortex.core.fbs.FbsBuffer;
import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.UnknownArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import static io.github.dfa1.vortex.core.io.PTypeIO.LE_INT;
import static org.assertj.core.api.Assertions.assertThat;

/// Successful flat-segment decode path — complements [FlatSegmentBoundsSecurityTest] (which only
/// drives the rejection paths). A buffer descriptor with non-zero padding exercises the offset
/// walk, and an unknown encoding id exercises the `UnknownArrayNode` fallback through an
/// allow-unknown registry. Together these pin two otherwise-untested spots:
/// - the `dataOffset += padding` accumulation: with padding > 0, flipping `+=` to `-=` slices at a
///   negative offset and fails, so a clean decode proves the addition.
/// - the `orElseGet(() -> new UnknownArrayNode(...))` fallback: returning `null` there yields a
///   null node and the decode would not produce an `UnknownArray`.
class FlatSegmentDecoderDecodeTest {

    @Test
    void decode_unknownEncodingWithBufferPadding_returnsUnknownArray() {
        ReadRegistry registry = ReadRegistry.builder().allowUnknown().build();
        FlatSegmentDecoder sut = new FlatSegmentDecoder(registry);

        try (Arena arena = Arena.ofConfined()) {
            // Given — a flat segment whose single buffer carries 8 bytes of leading padding and
            // an unrecognised encoding id. The 8 pad bytes are the entire buffer-data region
            // (buffer length 0), so the decoder must advance dataOffset by +8 to slice it; a
            // subtraction would slice at offset -8.
            int padding = 8;
            byte[] fb = arrayFlatBufferOneBuffer(padding, 0L);
            MemorySegment seg = arena.allocate((long) padding + fb.length + 4);
            MemorySegment.copy(MemorySegment.ofArray(fb), 0, seg, padding, fb.length);
            seg.set(LE_INT, padding + fb.length, fb.length);

            // When — encoding index 0 maps to an id no decoder handles
            Array result = sut.decode(seg, List.of("vortex.nonexistent"),
                    DType.I32, 0, arena);

            // Then — the allow-unknown path produced an UnknownArray (proves both the +padding
            // walk and the UnknownArrayNode fallback ran)
            assertThat(result).isInstanceOf(UnknownArray.class);
        }
    }

    /// Builds an `Array` FlatBuffer with a single buffer descriptor of the given padding/length.
    private static byte[] arrayFlatBufferOneBuffer(int padding, long length) {
        FbsBuilder b = new FbsBuilder();

        int rootChildren = FbsArrayNode.createChildrenVector(b, new int[0]);
        int rootBuffers = FbsArrayNode.createBuffersVector(b, new int[]{0});
        int root = FbsArrayNode.createFbsArrayNode(b, 0, 0, rootChildren, rootBuffers, 0);

        io.github.dfa1.vortex.core.fbs.FbsArray.startBuffersVector(b, 1);
        FbsBuffer.createFbsBuffer(b, padding, 0, 0, length);
        int buffers = b.endVector();

        int array = io.github.dfa1.vortex.core.fbs.FbsArray.createFbsArray(b, root, buffers);
        io.github.dfa1.vortex.core.fbs.FbsArray.finishFbsArrayBuffer(b, array);
        return b.sizedByteArray();
    }
}
