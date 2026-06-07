package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VarBinArrayTest {

    private static final DType UTF8 = new DType.Utf8(false);

    private static VarBinArray of(String... values) {
        byte[] allBytes = String.join("", values).getBytes(StandardCharsets.UTF_8);
        MemorySegment bytes = MemorySegment.ofArray(allBytes);

        int[] offs = new int[values.length + 1];
        for (int i = 0; i < values.length; i++) {
            offs[i + 1] = offs[i] + values[i].getBytes(StandardCharsets.UTF_8).length;
        }
        ByteBuffer bb = ByteBuffer.allocate(offs.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int o : offs) {
            bb.putInt(o);
        }
        MemorySegment offsetsSeg = MemorySegment.ofArray(bb.array());
        return new VarBinArray(UTF8, values.length, bytes, offsetsSeg, PType.I32);
    }

    @Nested
    class Regular {

        @Test
        void length_returnsElementCount() {
            // Given
            VarBinArray sut = of("a", "bb", "ccc");

            // When / Then
            assertThat(sut.length()).isEqualTo(3L);
        }

        @Test
        void dtype_returnsConstructedDtype() {
            // Given
            VarBinArray sut = of("x");

            // When / Then
            assertThat(sut.dtype()).isEqualTo(UTF8);
        }

        @Test
        void getBytes_returnsCorrectBytes() {
            // Given
            VarBinArray sut = of("hello", "world");

            // When / Then
            assertThat(sut.getString(0)).isEqualTo("hello");
            assertThat(sut.getString(1)).isEqualTo("world");
        }

        @Test
        void getByteLength_returnsCorrectLengths() {
            // Given
            VarBinArray sut = of("hi", "there", "!");

            // When / Then
            assertThat(sut.getByteLength(0)).isEqualTo(2);
            assertThat(sut.getByteLength(1)).isEqualTo(5);
            assertThat(sut.getByteLength(2)).isEqualTo(1);
        }

        @Test
        void forEachByteLength_visitsAllLengths() {
            // Given
            VarBinArray sut = of("ab", "c", "def");
            List<Integer> lengths = new ArrayList<>();

            // When
            sut.forEachByteLength(lengths::add);

            // Then
            assertThat(lengths).containsExactly(2, 1, 3);
        }

        @Test
        void getBytes_emptyString_returnsEmptyArray() {
            // Given
            VarBinArray sut = of("", "x", "");

            // When / Then
            assertThat(sut.getBytes(0)).isEmpty();
            assertThat(sut.getByteLength(0)).isZero();
            assertThat(sut.getByteLength(2)).isZero();
        }

        @Test
        void empty_zeroElements() {
            // Given
            VarBinArray sut = of();

            // When / Then
            assertThat(sut.length()).isZero();
        }

        @Test
        void segment_returnsBytesSegment() {
            // Given
            VarBinArray sut = of("a");

            // When / Then
            assertThat(sut.segment()).isSameAs(sut.bytesSegment());
        }

        @Test
        void offsetsPtype_returnsOffsetType() {
            // Given
            VarBinArray sut = of("a");

            // When / Then
            assertThat(sut.offsetsPtype()).isNotNull();
        }
    }

    @Nested
    class Dict {

        private static VarBinArray ofDict(String[] dictValues, int[] codes) {
            byte[] allBytes = String.join("", dictValues).getBytes(StandardCharsets.UTF_8);
            MemorySegment dictValBytes = MemorySegment.ofArray(allBytes);

            int[] offs = new int[dictValues.length + 1];
            for (int i = 0; i < dictValues.length; i++) {
                offs[i + 1] = offs[i] + dictValues[i].getBytes(StandardCharsets.UTF_8).length;
            }
            MemorySegment dictValOffsets = leInts(offs);

            MemorySegment dictCodes = leInts(codes);

            return VarBinArray.ofDict(UTF8, codes.length,
                    dictValBytes, dictValOffsets, PType.I32,
                    dictCodes, PType.I32);
        }

        private static MemorySegment leInts(int[] values) {
            ByteBuffer bb = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : values) {
                bb.putInt(v);
            }
            return MemorySegment.ofArray(bb.array());
        }

        @Test
        void getBytes_resolvesViaDict() {
            // Given — dict=["foo","bar"], codes=[1,0,1]
            VarBinArray sut = ofDict(new String[]{"foo", "bar"}, new int[]{1, 0, 1});

            // When / Then
            assertThat(sut.getString(0)).isEqualTo("bar");
            assertThat(sut.getString(1)).isEqualTo("foo");
            assertThat(sut.getString(2)).isEqualTo("bar");
        }

        @Test
        void getByteLength_resolvesViaDict() {
            // Given — dict=["hi","there"], codes=[0,1,0]
            VarBinArray sut = ofDict(new String[]{"hi", "there"}, new int[]{0, 1, 0});

            // When / Then
            assertThat(sut.getByteLength(0)).isEqualTo(2);
            assertThat(sut.getByteLength(1)).isEqualTo(5);
            assertThat(sut.getByteLength(2)).isEqualTo(2);
        }

        @Test
        void forEachByteLength_resolvesViaDict() {
            // Given
            VarBinArray sut = ofDict(new String[]{"a", "bbb"}, new int[]{1, 0, 1});
            List<Integer> lengths = new ArrayList<>();

            // When
            sut.forEachByteLength(lengths::add);

            // Then
            assertThat(lengths).containsExactly(3, 1, 3);
        }

        @Test
        void offsetsSegment_inDictMode_throws() {
            // Given
            VarBinArray sut = ofDict(new String[]{"x"}, new int[]{0});

            // When / Then
            assertThatThrownBy(() -> sut.offsetsSegment())
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
