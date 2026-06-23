package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.core.DType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LazyForIntArrayTest {


    private static final DType I32 = DType.I32;

    private static LazyForIntArray of(int ref, int... encoded) {
        MemorySegment seg = Arena.ofAuto().allocate((long) encoded.length * 4, 4);
        for (int i = 0; i < encoded.length; i++) {
            seg.setAtIndex(PTypeIO.LE_INT, i, encoded[i]);
        }
        return new LazyForIntArray(I32, encoded.length, seg, ref);
    }

    @Test
    void getIntAddsReference() {
        // Given
        LazyForIntArray sut = of(1000, 1, 2, 3);

        // When + Then
        assertThat(sut.getInt(0)).isEqualTo(1001);
        assertThat(sut.getInt(1)).isEqualTo(1002);
        assertThat(sut.getInt(2)).isEqualTo(1003);
    }

    @Test
    void forEachIntVisitsAll() {
        // Given
        LazyForIntArray sut = of(-10, 100, 200);
        List<Integer> got = new ArrayList<>();

        // When
        sut.forEachInt(got::add);

        // Then
        assertThat(got).containsExactly(90, 190);
    }

    @Test
    void foldSumApplies() {
        // Given — [1,2,3] + ref 10 = [11,12,13] → sum 36
        LazyForIntArray sut = of(10, 1, 2, 3);

        // When
        int sum = sut.fold(0, Integer::sum);

        // Then
        assertThat(sum).isEqualTo(36);
    }

    @Test
    void materializeDecodesAllRows() {
        // Given
        LazyForIntArray sut = of(1000, 1, 2, 3);

        // When
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = sut.materialize(arena);

            // Then — materialized rows match the lazy getter
            for (int i = 0; i < 3; i++) {
                assertThat(seg.getAtIndex(PTypeIO.LE_INT, i)).as("row %d", i).isEqualTo(sut.getInt(i));
            }
        }
    }
}
