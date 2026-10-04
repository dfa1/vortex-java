package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoZstdBuffersMetadata;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.zstd.ZstdCompressContext;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The interop test reads a Rust-written file; these pin the metadata/children contract of Rust's
/// `ZstdBuffers::deserialize` and every malformed shape, which a real file never exercises.
class ZstdBuffersEncodingDecoderTest {

    private static final ZstdBuffersEncodingDecoder SUT = new ZstdBuffersEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(SUT, new PrimitiveEncodingDecoder());
    private static final byte[] VALUES = TestSegments.leInts(7, -1, 42).toArray(ValueLayout.JAVA_BYTE);

    @Test
    void wrappedPrimitive_decodesToTheInnerArray() {
        // Given
        ArrayNode node = zstdBuffers(meta("vortex.primitive", VALUES.length, 4), 0);

        // When
        Array result = SUT.decode(ctx(node, compress(VALUES)));

        // Then
        IntArray ints = (IntArray) result;
        assertThat(ints.getInt(0)).isEqualTo(7);
        assertThat(ints.getInt(1)).isEqualTo(-1);
        assertThat(ints.getInt(2)).isEqualTo(42);
    }

    @Test
    void nestedZstdBuffers_unwrapsEveryLevel() {
        // Given — the inner array is itself zstd_buffers over a primitive: the outer buffer
        // decompresses to the inner level's compressed buffer, nested through metadata alone
        byte[] innerFrame = compress(VALUES);
        ProtoZstdBuffersMetadata innerMeta = new ProtoZstdBuffersMetadata("vortex.primitive", new byte[0],
                List.of((long) VALUES.length), List.of(4), List.of(), List.of());
        ProtoZstdBuffersMetadata outerMeta = new ProtoZstdBuffersMetadata("vortex.zstd_buffers", innerMeta.encode(),
                List.of((long) innerFrame.length), List.of(1), List.of(), List.of());
        ArrayNode node = zstdBuffers(MemorySegment.ofArray(outerMeta.encode()), 0);

        // When
        Array result = SUT.decode(ctx(node, compress(innerFrame)));

        // Then
        assertThat(((IntArray) result).getInt(2)).isEqualTo(42);
    }

    @Test
    void decompressedBuffer_honorsTheDeclaredAlignment() {
        // Given — Rust holds a buffer to its declared alignment; 64 is above anything the arena
        // would give by default
        ArrayNode node = zstdBuffers(meta("vortex.primitive", VALUES.length, 64), 0);

        // When
        Array result = SUT.decode(ctx(node, compress(VALUES)));

        // Then
        assertThat(result.segmentIfPresent()).hasValueSatisfying(seg -> assertThat(seg.address() % 64).isZero());
    }

    @Nested
    class Malformed {

        @Test
        void missingMetadata_throws() {
            // Given
            ArrayNode node = zstdBuffers(null, 0);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx(node, compress(VALUES))))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing metadata");
        }

        @Test
        void sizeCountDisagreesWithBuffers_throws() {
            // Given — one compressed buffer, no declared sizes
            ProtoZstdBuffersMetadata meta = new ProtoZstdBuffersMetadata("vortex.primitive", new byte[0],
                    List.of(), List.of(), List.of(), List.of());
            ArrayNode node = zstdBuffers(MemorySegment.ofArray(meta.encode()), 0);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx(node, compress(VALUES))))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("1 compressed buffers but 0 sizes");
        }

        @Test
        void childCountDisagreesWithMetadata_throws() {
            // Given — a child the metadata does not describe
            ArrayNode child = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_ZSTD_BUFFERS, meta("vortex.primitive", VALUES.length, 4),
                    new ArrayNode[]{child}, new int[]{0});

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx(node, compress(VALUES))))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("1 children but 0 child dtypes");
        }

        @Test
        void blankInnerEncodingId_throws() {
            // Given
            ArrayNode node = zstdBuffers(meta(" ", VALUES.length, 4), 0);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx(node, compress(VALUES))))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing inner encoding id");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 3, 1 << 17, -1})
        void invalidAlignment_throws(int alignment) {
            // Given — zero, not a power of two, above the cap, and a u32 that reads as negative
            ArrayNode node = zstdBuffers(meta("vortex.primitive", VALUES.length, alignment), 0);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx(node, compress(VALUES))))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("buffer alignment must be a power of two");
        }

        @Test
        void corruptFrame_throwsVortexException() {
            // Given — not a Zstd frame: the binding's own ZstdException must not escape
            ArrayNode node = zstdBuffers(meta("vortex.primitive", VALUES.length, 4), 0);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx(node, new byte[]{1, 2, 3, 4, 5, 6, 7, 8})))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("frame 0");
        }

        @Test
        void declaredSizeLargerThanFrame_throws() {
            // Given — metadata promises more bytes than the frame decompresses to
            ArrayNode node = zstdBuffers(meta("vortex.primitive", VALUES.length + 4, 4), 0);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx(node, compress(VALUES))))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("frame 0");
        }
    }

    private static DecodeContext ctx(ArrayNode node, byte[] frame) {
        return new DecodeContext(node, DType.I32, 3, new MemorySegment[]{MemorySegment.ofArray(frame)},
                REGISTRY, Arena.ofAuto());
    }

    private static MemorySegment meta(String innerId, long uncompressedSize, int alignment) {
        return MemorySegment.ofArray(new ProtoZstdBuffersMetadata(innerId, new byte[0],
                List.of(uncompressedSize), List.of(alignment), List.of(), List.of()).encode());
    }

    private static ArrayNode zstdBuffers(MemorySegment meta, int bufferSegment) {
        return new ArrayNode(EncodingId.VORTEX_ZSTD_BUFFERS, meta, new ArrayNode[0], new int[]{bufferSegment});
    }

    private static byte[] compress(byte[] raw) {
        try (ZstdCompressContext cctx = new ZstdCompressContext()) {
            return cctx.compress(raw);
        }
    }
}
