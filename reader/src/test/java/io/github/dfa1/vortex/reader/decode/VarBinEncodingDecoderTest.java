package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.core.proto.ProtoVarBinMetadata;
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

class VarBinEncodingDecoderTest {

    private static final VarBinEncodingDecoder SUT = new VarBinEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(SUT, new PrimitiveEncodingDecoder());

    private static MemorySegment i32OffsetsMeta() {
        return MemorySegment.ofArray(new ProtoVarBinMetadata(io.github.dfa1.vortex.core.proto.ProtoPType.I32).encode());
    }

    private static DecodeContext ctx(MemorySegment meta, MemorySegment bytes, MemorySegment offsets, long n) {
        // children[0] = offsets (primitive, segment index 1); bufferIndices[0] -> bytes (index 0)
        ArrayNode offsetsNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
        ArrayNode varbinNode = new ArrayNode(EncodingId.VORTEX_VARBIN, meta, new ArrayNode[]{offsetsNode}, new int[]{0});
        return new DecodeContext(varbinNode, DType.UTF8, n,
                new MemorySegment[]{bytes, offsets}, REGISTRY, Arena.ofAuto());
    }

    @Test
    void decode_i32Offsets_happyPath() {
        // Given "a","b","c" with I32 offsets (the encoder defaults to I64, so this
        // exercises the I32 offsets-ptype branch directly)
        MemorySegment data = MemorySegment.ofArray("abc".getBytes(StandardCharsets.UTF_8));
        MemorySegment offsets = TestSegments.leInts(0, 1, 2, 3);

        // When
        Array result = SUT.decode(ctx(i32OffsetsMeta(), data, offsets, 3));

        // Then
        VarBinArray arr = (VarBinArray) result;
        assertThat(arr.length()).isEqualTo(3);
        assertThat(arr.getBytes(0)).containsExactly('a');
        assertThat(arr.getBytes(1)).containsExactly('b');
        assertThat(arr.getBytes(2)).containsExactly('c');
    }

    /// Adversarial offsets from an untrusted file (TODO.md §Security, per-encoding
    /// adversarial tests). `decode()` deliberately never scans the offsets — VarBin decode
    /// is zero-copy and lazy — so every malformed offset has to be caught by the accessors,
    /// and always as a [VortexException]: a non-monotonic pair used to reach
    /// `new byte[end - start]` as a `NegativeArraySizeException`, and an offset past the
    /// data buffer used to reach `MemorySegment.copy` as a raw `IndexOutOfBoundsException`
    /// (or, in `getByteLength`, to be reported silently as a bogus length).
    @Nested
    class AdversarialOffsets {

        @Test
        void nonMonotonicOffsets_getBytes_throws() {
            // Given offsets [0, 5, 2] over "abcde": row 1 spans [5, 2), a negative length
            MemorySegment data = MemorySegment.ofArray("abcde".getBytes(StandardCharsets.UTF_8));
            VarBinArray array = (VarBinArray) SUT.decode(ctx(i32OffsetsMeta(), data, TestSegments.leInts(0, 5, 2), 2));

            // When / Then
            assertThatThrownBy(() -> array.getBytes(1))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for a data buffer");
        }

        @Test
        void nonMonotonicOffsets_getByteLength_throws() {
            // Given the same descending pair — the length itself must be rejected, not
            // handed back as a negative int
            MemorySegment data = MemorySegment.ofArray("abcde".getBytes(StandardCharsets.UTF_8));
            VarBinArray array = (VarBinArray) SUT.decode(ctx(i32OffsetsMeta(), data, TestSegments.leInts(0, 5, 2), 2));

            // When / Then
            assertThatThrownBy(() -> array.getByteLength(1))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for a data buffer");
        }

        @Test
        void negativeOffset_getBytes_throws() {
            // Given a negative I32 offset (0xFFFFFFFF widens to -1), which would copy from
            // before the start of the data buffer
            MemorySegment data = MemorySegment.ofArray("abc".getBytes(StandardCharsets.UTF_8));
            VarBinArray array = (VarBinArray) SUT.decode(ctx(i32OffsetsMeta(), data, TestSegments.leInts(-1, 2), 1));

            // When / Then
            assertThatThrownBy(() -> array.getBytes(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for a data buffer");
        }

        @Test
        void offsetPastDataBuffer_getBytes_throws() {
            // Given an end offset of 100 over a 3-byte data buffer
            MemorySegment data = MemorySegment.ofArray("abc".getBytes(StandardCharsets.UTF_8));
            VarBinArray array = (VarBinArray) SUT.decode(ctx(i32OffsetsMeta(), data, TestSegments.leInts(0, 100), 1));

            // When / Then
            assertThatThrownBy(() -> array.getBytes(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for a data buffer");
        }

        @Test
        void offsetPastDataBuffer_getByteLength_throws() {
            // Given the same overrun — getByteLength used to return 100 silently, letting a
            // caller size a copy from data it never owned
            MemorySegment data = MemorySegment.ofArray("abc".getBytes(StandardCharsets.UTF_8));
            VarBinArray array = (VarBinArray) SUT.decode(ctx(i32OffsetsMeta(), data, TestSegments.leInts(0, 100), 1));

            // When / Then
            assertThatThrownBy(() -> array.getByteLength(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for a data buffer");
        }

        @Test
        void offsetPastDataBuffer_limited_throws() {
            // Given a truncating slice whose cut point (row 1 -> offset 100) lies past the
            // 3-byte data buffer: asSlice would have thrown IndexOutOfBoundsException
            MemorySegment data = MemorySegment.ofArray("abc".getBytes(StandardCharsets.UTF_8));
            VarBinArray array = (VarBinArray) SUT.decode(ctx(i32OffsetsMeta(), data, TestSegments.leInts(0, 100, 200), 2));

            // When / Then
            assertThatThrownBy(() -> array.limited(1))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for a data buffer");
        }

        @Test
        void truncatedOffsetsSegment_getBytes_throws() {
            // Given an offsets child with n+1 declared but only n values present. The
            // decoder broadcast-materializes it (offCap < n + 1), so reading past the end is
            // only possible on a directly built array — this exercises the readOffset guard
            // directly against a short offsets segment.
            MemorySegment data = MemorySegment.ofArray("abc".getBytes(StandardCharsets.UTF_8));
            VarBinArray array = new VarBinArray.OffsetMode(DType.UTF8, 4, data,
                    TestSegments.leInts(0, 1, 2), PType.I32);

            // When / Then
            assertThatThrownBy(() -> array.getBytes(3))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for an offsets segment");
        }
    }

    @Test
    void decode_broadcastOffsets_singleOffsetExpandsToAllRows() {
        // Given an offsets child holding a single value (as ConstantEncoding emits):
        // capacity 1 < n+1, so the decoder must broadcast-copy it. A constant offset
        // means every row spans an empty slice.
        MemorySegment data = Arena.ofAuto().allocate(1);
        MemorySegment offsets = TestSegments.leInts(0); // one element only

        // When
        Array result = SUT.decode(ctx(i32OffsetsMeta(), data, offsets, 3));

        // Then
        VarBinArray arr = (VarBinArray) result;
        assertThat(arr.length()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(arr.getBytes(i)).as("index %d", i).isEmpty();
        }
    }
}
