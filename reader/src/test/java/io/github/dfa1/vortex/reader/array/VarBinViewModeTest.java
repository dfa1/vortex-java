package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.io.VortexFormat;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for [VarBinArray.ViewMode]. Covers inline (≤ 12 byte) views,
/// referenced views into shared data buffers, length-only reads, and truncate.
class VarBinViewModeTest {

    private static final DType UTF8 = DType.UTF8;
    private static final int VIEW_SIZE = 16;

    @Nested
    class Inline {

        @Test
        void getStringFromInlineView() {
            try (Arena arena = Arena.ofConfined()) {
                // Given two short strings stored inline in 16-byte views.
                MemorySegment views = writeViews(arena, new String[]{"hi", "world!"});
                var sut = new VarBinArray.ViewMode(UTF8, 2, views, new MemorySegment[0]);

                // When/Then
                assertThat(sut.getString(0)).isEqualTo("hi");
                assertThat(sut.getString(1)).isEqualTo("world!");
                assertThat(sut.getByteLength(0)).isEqualTo(2);
                assertThat(sut.getByteLength(1)).isEqualTo(6);
            }
        }

        @Test
        void forEachByteLengthEmitsAllRows() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment views = writeViews(arena, new String[]{"a", "bb", "ccc"});
                var sut = new VarBinArray.ViewMode(UTF8, 3, views, new MemorySegment[0]);

                var seen = new ArrayList<Integer>();
                sut.forEachByteLength(seen::add);

                assertThat(seen).containsExactly(1, 2, 3);
            }
        }
    }

    @Nested
    class Referenced {

        @Test
        void getStringFromReferencedDataBuffer() {
            try (Arena arena = Arena.ofConfined()) {
                // Given a 13-byte string (exceeds 12-byte inline limit) stored in a shared data buffer.
                String longStr = "thirteen-byte";
                assertThat(longStr).hasSize(13);
                MemorySegment dataBuf = arena.allocate(longStr.length());
                MemorySegment.copy(MemorySegment.ofArray(longStr.getBytes(StandardCharsets.UTF_8)),
                        0, dataBuf, 0, longStr.length());

                // One view referencing the data buffer at offset 0.
                MemorySegment views = arena.allocate(VIEW_SIZE);
                views.set(VortexFormat.LE_INT, 0, longStr.length());      // size
                views.set(VortexFormat.LE_INT, 4, 0);                     // prefix (ignored on read)
                views.set(VortexFormat.LE_INT, 8, 0);                     // buffer index
                views.set(VortexFormat.LE_INT, 12, 0);                    // offset within buffer
                var sut = new VarBinArray.ViewMode(UTF8, 1, views, new MemorySegment[]{dataBuf});

                // When/Then
                assertThat(sut.getString(0)).isEqualTo(longStr);
                assertThat(sut.getByteLength(0)).isEqualTo(13);
            }
        }

        @Test
        void mixedInlineAndReferenced() {
            try (Arena arena = Arena.ofConfined()) {
                // Row 0: inline "short". Row 1: referenced "this is more than twelve bytes".
                String longStr = "this is more than twelve bytes";
                MemorySegment dataBuf = arena.allocate(longStr.length());
                MemorySegment.copy(MemorySegment.ofArray(longStr.getBytes(StandardCharsets.UTF_8)),
                        0, dataBuf, 0, longStr.length());

                MemorySegment views = arena.allocate(2L * VIEW_SIZE);
                writeInlineView(views, 0, "short");
                // Row 1: referenced.
                views.set(VortexFormat.LE_INT, VIEW_SIZE, longStr.length());
                views.set(VortexFormat.LE_INT, VIEW_SIZE + 8, 0);
                views.set(VortexFormat.LE_INT, VIEW_SIZE + 12, 0);

                var sut = new VarBinArray.ViewMode(UTF8, 2, views, new MemorySegment[]{dataBuf});

                assertThat(sut.getString(0)).isEqualTo("short");
                assertThat(sut.getString(1)).isEqualTo(longStr);
            }
        }
    }

    @Nested
    class Truncate {

        @Test
        void keepsPrefixSharingDataBuffers() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment views = writeViews(arena, new String[]{"a", "b", "c", "d"});
                var sut = new VarBinArray.ViewMode(UTF8, 4, views, new MemorySegment[0]);

                VarBinArray truncated = sut.limited(2);

                assertThat(truncated.length()).isEqualTo(2);
                assertThat(truncated.getString(0)).isEqualTo("a");
                assertThat(truncated.getString(1)).isEqualTo("b");
            }
        }

        @Test
        void truncateBeyondLengthReturnsSelf() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment views = writeViews(arena, new String[]{"a"});
                var sut = new VarBinArray.ViewMode(UTF8, 1, views, new MemorySegment[0]);

                assertThat(sut.limited(10)).isSameAs(sut);
            }
        }
    }

    private static MemorySegment writeViews(Arena arena, String[] rows) {
        MemorySegment views = arena.allocate((long) rows.length * VIEW_SIZE);
        for (int i = 0; i < rows.length; i++) {
            writeInlineView(views, (long) i * VIEW_SIZE, rows[i]);
        }
        return views;
    }

    private static void writeInlineView(MemorySegment views, long viewOff, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 12) {
            throw new IllegalArgumentException("test helper only handles inline ≤ 12 byte values");
        }
        views.set(VortexFormat.LE_INT, viewOff, bytes.length);
        for (int j = 0; j < bytes.length; j++) {
            views.set(ValueLayout.JAVA_BYTE, viewOff + 4 + j, bytes[j]);
        }
    }
}
