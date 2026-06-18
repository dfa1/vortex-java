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

class LazyZigZagByteArrayTest {

    private static final DType I8 = new DType.Primitive(PType.I8, false);

    private static LazyZigZagByteArray of(byte... encoded) {
        MemorySegment seg = Arena.ofAuto().allocate(encoded.length);
        for (int i = 0; i < encoded.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, encoded[i]);
        }
        return new LazyZigZagByteArray(I8, encoded.length, seg);
    }

    @Test
    void getByteAppliesZigzagDecode() {
        // Given — zigzag(0)=0, zigzag(-1)=1, zigzag(1)=2, zigzag(-2)=3, zigzag(2)=4
        LazyZigZagByteArray sut = of((byte) 0, (byte) 1, (byte) 2, (byte) 3, (byte) 4);

        // When + Then
        assertThat(sut.getByte(0)).isEqualTo((byte) 0);
        assertThat(sut.getByte(1)).isEqualTo((byte) -1);
        assertThat(sut.getByte(2)).isEqualTo((byte) 1);
        assertThat(sut.getByte(3)).isEqualTo((byte) -2);
        assertThat(sut.getByte(4)).isEqualTo((byte) 2);
    }

    @Test
    void getIntWidensSignedByte() {
        // Given — zigzag(1) = 2
        LazyZigZagByteArray sut = of((byte) 2);

        // When + Then — I8 returns signed int, not zero-extended
        assertThat(sut.getInt(0)).isEqualTo(1);
    }

    @Test
    void forEachByteVisitsAll() {
        // Given
        LazyZigZagByteArray sut = of((byte) 4, (byte) 5);
        List<Byte> got = new ArrayList<>();

        // When
        sut.forEachByte(got::add);

        // Then — zigzag(4)=2, zigzag(5)=-3
        assertThat(got).containsExactly((byte) 2, (byte) -3);
    }

    @Test
    void foldSumApplies() {
        // Given — encoded [0,1,2,3,4] decodes [0,-1,1,-2,2] → sum 0
        LazyZigZagByteArray sut = of((byte) 0, (byte) 1, (byte) 2, (byte) 3, (byte) 4);

        // When
        long sum = sut.fold(0L, Long::sum);

        // Then
        assertThat(sum).isEqualTo(0L);
    }
}
