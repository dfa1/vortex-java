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

    private static final DType UTF8 = new DType.Utf8(false);

    @Nested
    class Construction {

        @Test
        void emptyChunkListRejected() {
            assertThatThrownBy(() -> VarBinArray.ChunkedMode.of(UTF8, 0, List.of()))
                    .isInstanceOf(VortexException.class);
        }

        @Test
        void rowMismatchRejected() {
            try (Arena arena = Arena.ofConfined()) {
                VarBinArray c0 = stringChunk(arena, "a", "b");
                assertThatThrownBy(() -> VarBinArray.ChunkedMode.of(UTF8, 99, List.of(c0)))
                        .isInstanceOf(VortexException.class);
            }
        }

        @Test
        void nonVarBinChunkRejected() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(8L);
                seg.setAtIndex(ValueLayout.JAVA_LONG, 0, 42L);
                LongArray notVarBin = new MaterializedLongArray(
                        new DType.Primitive(PType.I64, false), 1, seg.asReadOnly());
                assertThatThrownBy(() -> VarBinArray.ChunkedMode.of(UTF8, 1, List.of(notVarBin)))
                        .isInstanceOf(VortexException.class);
            }
        }

        @Test
        void nestedChunkedFlattens() {
            try (Arena arena = Arena.ofConfined()) {
                VarBinArray leaf0 = stringChunk(arena, "a");
                VarBinArray leaf1 = stringChunk(arena, "b");
                VarBinArray.ChunkedMode nested = VarBinArray.ChunkedMode.of(UTF8, 2, List.of(leaf0, leaf1));
                VarBinArray leaf2 = stringChunk(arena, "c");

                VarBinArray.ChunkedMode sut = VarBinArray.ChunkedMode.of(UTF8, 3, List.of(nested, leaf2));

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
                VarBinArray c0 = stringChunk(arena, "alpha", "beta");
                VarBinArray c1 = stringChunk(arena, "gamma");
                VarBinArray.ChunkedMode sut = VarBinArray.ChunkedMode.of(UTF8, 3, List.of(c0, c1));

                assertThat(sut.getString(0)).isEqualTo("alpha");
                assertThat(sut.getString(1)).isEqualTo("beta");
                assertThat(sut.getString(2)).isEqualTo("gamma");
            }
        }

        @Test
        void getBytesDispatchesAcrossChunks() {
            try (Arena arena = Arena.ofConfined()) {
                VarBinArray c0 = stringChunk(arena, "abc");
                VarBinArray c1 = stringChunk(arena, "xyz");
                VarBinArray.ChunkedMode sut = VarBinArray.ChunkedMode.of(UTF8, 2, List.of(c0, c1));

                assertThat(sut.getBytes(1)).containsExactly('x', 'y', 'z');
            }
        }

        @Test
        void getByteLengthCrossesBoundary() {
            try (Arena arena = Arena.ofConfined()) {
                VarBinArray c0 = stringChunk(arena, "hi");
                VarBinArray c1 = stringChunk(arena, "hello");
                VarBinArray.ChunkedMode sut = VarBinArray.ChunkedMode.of(UTF8, 2, List.of(c0, c1));

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
                VarBinArray c0 = stringChunk(arena, "a", "b", "c");
                VarBinArray c1 = stringChunk(arena, "d", "e");
                VarBinArray.ChunkedMode sut = VarBinArray.ChunkedMode.of(UTF8, 5, List.of(c0, c1));

                VarBinArray truncated = sut.truncate(4);

                assertThat(truncated.length()).isEqualTo(4);
                assertThat(truncated.getString(0)).isEqualTo("a");
                assertThat(truncated.getString(3)).isEqualTo("d");
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
