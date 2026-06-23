package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VarBinChunkedModeTest {

    private static final DType UTF8 = DType.UTF8;

    @Nested
    class Construction {

        @Test
        void emptyChunkListRejected() {
            // Given / When / Then
            assertThatThrownBy(() -> VarBinArray.ChunkedMode.of(UTF8, 0, List.of()))
                    .isInstanceOf(VortexException.class);
        }

        @Test
        void rowMismatchRejected() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                VarBinArray c0 = stringChunk(arena, "a", "b");

                // When / Then
                assertThatThrownBy(() -> VarBinArray.ChunkedMode.of(UTF8, 99, List.of(c0)))
                        .isInstanceOf(VortexException.class);
            }
        }

        @Test
        void nonVarBinChunkRejected() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                MemorySegment seg = arena.allocate(8L);
                seg.setAtIndex(ValueLayout.JAVA_LONG, 0, 42L);
                LongArray notVarBin = new MaterializedLongArray(
                        DType.I64, 1, seg.asReadOnly());

                // When / Then
                assertThatThrownBy(() -> VarBinArray.ChunkedMode.of(UTF8, 1, List.of(notVarBin)))
                        .isInstanceOf(VortexException.class);
            }
        }

        @Test
        void nestedChunkedFlattens() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                VarBinArray leaf0 = stringChunk(arena, "a");
                VarBinArray leaf1 = stringChunk(arena, "b");
                VarBinArray.ChunkedMode nested = VarBinArray.ChunkedMode.of(UTF8, 2, List.of(leaf0, leaf1));
                VarBinArray leaf2 = stringChunk(arena, "c");

                // When
                VarBinArray.ChunkedMode sut = VarBinArray.ChunkedMode.of(UTF8, 3, List.of(nested, leaf2));

                // Then
                assertThat(sut.children()).hasSize(3);
                assertThat(sut.getString(2)).isEqualTo("c");
            }
        }
    }

    @Nested
    class Access {

        @Test
        void getStringDispatchesAcrossChunks() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                VarBinArray c0 = stringChunk(arena, "alpha", "beta");
                VarBinArray c1 = stringChunk(arena, "gamma");
                VarBinArray.ChunkedMode sut = VarBinArray.ChunkedMode.of(UTF8, 3, List.of(c0, c1));

                // When / Then
                assertThat(sut.getString(0)).isEqualTo("alpha");
                assertThat(sut.getString(1)).isEqualTo("beta");
                assertThat(sut.getString(2)).isEqualTo("gamma");
            }
        }

        @Test
        void getBytesDispatchesAcrossChunks() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                VarBinArray c0 = stringChunk(arena, "abc");
                VarBinArray c1 = stringChunk(arena, "xyz");
                VarBinArray.ChunkedMode sut = VarBinArray.ChunkedMode.of(UTF8, 2, List.of(c0, c1));

                // When / Then
                assertThat(sut.getBytes(1)).containsExactly('x', 'y', 'z');
            }
        }

        @Test
        void getByteLengthCrossesBoundary() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                VarBinArray c0 = stringChunk(arena, "hi");
                VarBinArray c1 = stringChunk(arena, "hello");
                VarBinArray.ChunkedMode sut = VarBinArray.ChunkedMode.of(UTF8, 2, List.of(c0, c1));

                // When / Then
                assertThat(sut.getByteLength(0)).isEqualTo(2);
                assertThat(sut.getByteLength(1)).isEqualTo(5);
            }
        }
    }

    @Nested
    class Truncate {

        @Test
        void keepsPrefix() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                VarBinArray c0 = stringChunk(arena, "a", "b", "c");
                VarBinArray c1 = stringChunk(arena, "d", "e");
                VarBinArray.ChunkedMode sut = VarBinArray.ChunkedMode.of(UTF8, 5, List.of(c0, c1));

                // When
                VarBinArray result = sut.limited(4);

                // Then
                assertThat(result.length()).isEqualTo(4);
                assertThat(result.getString(0)).isEqualTo("a");
                assertThat(result.getString(3)).isEqualTo("d");
            }
        }
    }

    @Nested
    class SegmentProbe {

        @Test
        void chunkedHasNoContiguousSegment() {
            try (Arena arena = Arena.ofConfined()) {
                // Given a chunked array — bytes are spread across child segments
                VarBinArray c0 = stringChunk(arena, "a", "b");
                VarBinArray c1 = stringChunk(arena, "c");
                VarBinArray.ChunkedMode sut = VarBinArray.ChunkedMode.of(UTF8, 3, List.of(c0, c1));

                // When / Then the probe must not surface the NULL bytesSegment() sentinel
                assertThat(sut.segmentIfPresent()).isEmpty();
            }
        }

        @Test
        void slicedDelegatesToInnerProbe() {
            try (Arena arena = Arena.ofConfined()) {
                // Given a slice over a chunked inner — still no single segment
                VarBinArray c0 = stringChunk(arena, "a", "b");
                VarBinArray c1 = stringChunk(arena, "c");
                VarBinArray.ChunkedMode chunked = VarBinArray.ChunkedMode.of(UTF8, 3, List.of(c0, c1));
                VarBinArray.SlicedMode sut = new VarBinArray.SlicedMode(UTF8, 2, chunked, 1);

                // When / Then the probe follows the inner array, not the NULL bytesSegment()
                assertThat(sut.segmentIfPresent()).isEmpty();
            }
        }

        @Test
        void offsetBackedSurfacesItsSegment() {
            try (Arena arena = Arena.ofConfined()) {
                // Given a single-segment offset-backed array
                VarBinArray sut = stringChunk(arena, "a", "b");

                // When / Then the probe returns the real backing bytes segment
                assertThat(sut.segmentIfPresent()).containsSame(sut.bytesSegment());
            }
        }
    }

    private static VarBinArray stringChunk(Arena arena, String... values) {
        int totalBytes = 0;
        for (String s : values) {
            totalBytes += s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        MemorySegment bytes = arena.allocate(Math.max(totalBytes, 1));
        MemorySegment offsets = arena.allocate((values.length + 1) * 4L, 4);
        offsets.setAtIndex(ValueLayout.JAVA_INT, 0, 0);
        int pos = 0;
        for (int i = 0; i < values.length; i++) {
            byte[] b = values[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
            MemorySegment.copy(MemorySegment.ofArray(b), 0, bytes, pos, b.length);
            pos += b.length;
            offsets.setAtIndex(ValueLayout.JAVA_INT, i + 1, pos);
        }
        return new VarBinArray.OffsetMode(UTF8, values.length, bytes.asReadOnly(),
                offsets.asReadOnly(), PType.I32);
    }
}
