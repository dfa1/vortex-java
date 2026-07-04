package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LazyZigZagIntArrayTest {


    private static final DType I32 = DType.I32;

    private static LazyZigZagIntArray of(int... encoded) {
        MemorySegment seg = Arena.ofAuto().allocate((long) encoded.length * 4, 4);
        for (int i = 0; i < encoded.length; i++) {
            seg.setAtIndex(VortexFormat.LE_INT, i, encoded[i]);
        }
        return new LazyZigZagIntArray(I32, encoded.length, seg);
    }

    @Test
    void getIntAppliesZigzagDecode() {
        // Given — zigzag(0)=0, zigzag(-1)=1, zigzag(1)=2, zigzag(-2)=3, zigzag(2)=4
        LazyZigZagIntArray sut = of(0, 1, 2, 3, 4);

        // When + Then
        assertThat(sut.getInt(0)).isZero();
        assertThat(sut.getInt(1)).isEqualTo(-1);
        assertThat(sut.getInt(2)).isEqualTo(1);
        assertThat(sut.getInt(3)).isEqualTo(-2);
        assertThat(sut.getInt(4)).isEqualTo(2);
    }

    @Test
    void forEachIntVisitsAll() {
        // Given
        LazyZigZagIntArray sut = of(4, 5);
        List<Integer> got = new ArrayList<>();

        // When
        sut.forEachInt(got::add);

        // Then
        assertThat(got).containsExactly(2, -3);
    }

    @Test
    void foldSumApplies() {
        // Given — encoded [0,1,2,3,4] decodes [0,-1,1,-2,2] → sum 0
        LazyZigZagIntArray sut = of(0, 1, 2, 3, 4);

        // When
        int sum = sut.fold(0, Integer::sum);

        // Then
        assertThat(sum).isZero();
    }

    @Test
    void materializeDecodesAllRows() {
        // Given
        LazyZigZagIntArray sut = of(0, 1, 2, 3, 4);

        // When
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = sut.materialize(arena);

            // Then — materialized rows match the lazy getter
            for (int i = 0; i < 5; i++) {
                assertThat(seg.getAtIndex(VortexFormat.LE_INT, i)).as("row %d", i).isEqualTo(sut.getInt(i));
            }
        }
    }
}
