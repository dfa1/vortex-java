package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LazyForByteArrayTest {

    private static final DType I8 = new DType.Primitive(PType.I8, false);

    private static LazyForByteArray of(byte ref, byte... encoded) {
        MemorySegment seg = Arena.ofAuto().allocate(encoded.length);
        for (int i = 0; i < encoded.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, encoded[i]);
        }
        return new LazyForByteArray(I8, encoded.length, seg, ref);
    }

    @Test
    void getByteAddsRef() {
        // Given — residuals [1, 2, 3] with ref=10 → decoded [11, 12, 13]
        LazyForByteArray sut = of((byte) 10, (byte) 1, (byte) 2, (byte) 3);

        // When + Then
        assertThat(sut.getByte(0)).isEqualTo((byte) 11);
        assertThat(sut.getByte(1)).isEqualTo((byte) 12);
        assertThat(sut.getByte(2)).isEqualTo((byte) 13);
    }

    @Test
    void getByteWrapsOnOverflow() {
        // Given — wrapping addition: 120 + 20 = 140 overflows to -116 for signed byte
        LazyForByteArray sut = of((byte) 20, (byte) 120);

        // When + Then
        assertThat(sut.getByte(0)).isEqualTo((byte) 140);
    }

    @Test
    void getIntWidensSignedByte() {
        // Given — ref=0, residual=-1 → decoded=-1, getInt returns -1 for I8
        LazyForByteArray sut = of((byte) 0, (byte) -1);

        // When + Then
        assertThat(sut.getInt(0)).isEqualTo(-1);
    }

    @Test
    void forEachByteVisitsAll() {
        // Given
        LazyForByteArray sut = of((byte) 5, (byte) 1, (byte) 2);
        List<Byte> got = new ArrayList<>();

        // When
        sut.forEachByte(got::add);

        // Then
        assertThat(got).containsExactly((byte) 6, (byte) 7);
    }

    @Test
    void foldSumApplies() {
        // Given — residuals [1,2,3] ref=10 → decoded [11,12,13] → sum 36
        LazyForByteArray sut = of((byte) 10, (byte) 1, (byte) 2, (byte) 3);

        // When
        long sum = sut.fold(0L, Long::sum);

        // Then
        assertThat(sum).isEqualTo(36L);
    }
}
