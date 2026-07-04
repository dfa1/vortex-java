package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.fbs.FbsArray;
import io.github.dfa1.vortex.core.fbs.FbsArrayNode;
import io.github.dfa1.vortex.core.fbs.FbsBuffer;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Adversarial tests for the flat-segment decode path's offset/length arithmetic.
///
/// A flat segment is `buffer_data... | FlatBuffer(FbsArray) | u32 LE = FlatBuffer byte length`.
/// Both the trailing length field and each buffer descriptor's offset/length come straight
/// from untrusted file bytes. After ADR 0003 Phase E, every malformed value must surface as
/// a [VortexException], never a raw `IndexOutOfBoundsException` from `MemorySegment.asSlice`.
class SerializedArrayBoundsSecurityTest {


    private static final DType DTYPE = DType.I32;

    private final SerializedArrayDecoder sut = new SerializedArrayDecoder(ReadRegistry.empty());

    @Test
    void segmentSmallerThanLengthField_throwsVortexException() {
        try (Arena arena = Arena.ofConfined()) {
            // Given a 2-byte segment — too small to even hold the trailing 4-byte u32 length
            MemorySegment seg = arena.allocate(2);

            // When / Then the length-field read is bounds-checked, not left to crash get()
            assertThatThrownBy(() -> sut.decode(seg, List.of("vortex.flat"), DTYPE, 1, arena))
                    .isInstanceOf(VortexException.class);
        }
    }

    @Test
    void declaredFbLenLargerThanSegment_throwsVortexException() {
        try (Arena arena = Arena.ofConfined()) {
            // Given a 16-byte segment whose trailing u32 claims a 1 000 000-byte FlatBuffer;
            // fbStart = segLen - 4 - fbLen would go deeply negative.
            MemorySegment seg = arena.allocate(16);
            seg.set(PTypeIO.LE_INT, 12, 1_000_000);

            // When / Then
            assertThatThrownBy(() -> sut.decode(seg, List.of("vortex.flat"), DTYPE, 1, arena))
                    .isInstanceOf(VortexException.class);
        }
    }

    @Test
    void negativeFbLen_throwsVortexException() {
        try (Arena arena = Arena.ofConfined()) {
            // Given a trailing u32 of 0xFFFFFFFF — reads back as a signed int of -1
            MemorySegment seg = arena.allocate(16);
            seg.set(PTypeIO.LE_INT, 12, -1);

            // When / Then the negative length is rejected (checkRange len < 0)
            assertThatThrownBy(() -> sut.decode(seg, List.of("vortex.flat"), DTYPE, 1, arena))
                    .isInstanceOf(VortexException.class);
        }
    }

    @Test
    void bufferDescriptorLengthPastSegment_throwsVortexException() {
        try (Arena arena = Arena.ofConfined()) {
            // Given a well-formed FbsArray FlatBuffer whose single buffer descriptor claims a
            // 1 000 000-byte payload that cannot fit the actual segment.
            byte[] fb = arrayFlatBufferWithOneBuffer(1_000_000L);
            MemorySegment seg = arena.allocate(fb.length + 4L);
            MemorySegment.copy(MemorySegment.ofArray(fb), 0, seg, 0, fb.length);
            seg.set(PTypeIO.LE_INT, fb.length, fb.length);

            // When / Then the buffer slice is bounds-checked before asSlice
            assertThatThrownBy(() -> sut.decode(seg, List.of("vortex.flat"), DTYPE, 1, arena))
                    .isInstanceOf(VortexException.class);
        }
    }

    /// Builds a minimal valid `FbsArray` FlatBuffer with one buffer descriptor of the given length.
    private static byte[] arrayFlatBufferWithOneBuffer(long bufferLength) {
        FbsBuilder b = new FbsBuilder();

        int rootChildren = FbsArrayNode.createChildrenVector(b, new int[0]);
        int rootBuffers = FbsArrayNode.createBuffersVector(b, new int[]{0});
        int root = FbsArrayNode.createFbsArrayNode(b, 0, 0, rootChildren, rootBuffers, 0);

        FbsArray.startBuffersVector(b, 1);
        FbsBuffer.createFbsBuffer(b, 0, 0, 0, bufferLength);
        int buffers = b.endVector();

        int array = FbsArray.createFbsArray(b, root, buffers);
        FbsArray.finishFbsArrayBuffer(b, array);
        return b.sizedByteArray();
    }
}
