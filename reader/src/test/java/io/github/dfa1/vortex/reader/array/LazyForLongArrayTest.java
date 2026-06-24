package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.model.DType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LazyForLongArrayTest {


    private static final DType I64 = DType.I64;

    private static LazyForLongArray of(long ref, long... encoded) {
        MemorySegment seg = Arena.ofAuto().allocate((long) encoded.length * 8, 8);
        for (int i = 0; i < encoded.length; i++) {
            seg.setAtIndex(PTypeIO.LE_LONG, i, encoded[i]);
        }
        return new LazyForLongArray(I64, encoded.length, seg, ref);
    }

    @Nested
    class GetLong {

        @Test
        void addsReference() {
            // Given
            LazyForLongArray sut = of(100L, 5L, 10L, 15L);

            // When + Then
            assertThat(sut.getLong(0)).isEqualTo(105L);
            assertThat(sut.getLong(1)).isEqualTo(110L);
            assertThat(sut.getLong(2)).isEqualTo(115L);
        }

        @Test
        void negativeReference() {
            // Given
            LazyForLongArray sut = of(-50L, 100L);

            // When + Then
            assertThat(sut.getLong(0)).isEqualTo(50L);
        }
    }

    @Nested
    class ForEachLong {

        @Test
        void visitsAllInOrder() {
            // Given
            LazyForLongArray sut = of(1000L, 1L, 2L, 3L);
            List<Long> got = new ArrayList<>();

            // When
            sut.forEachLong(got::add);

            // Then
            assertThat(got).containsExactly(1001L, 1002L, 1003L);
        }
    }

    @Nested
    class Fold {

        @Test
        void sumApplies() {
            // Given — encoded [1,2,3,4] + ref 10 = [11,12,13,14] → sum 50
            LazyForLongArray sut = of(10L, 1L, 2L, 3L, 4L);

            // When
            long sum = sut.fold(0L, Long::sum);

            // Then
            assertThat(sum).isEqualTo(50L);
        }

        @Test
        void emptyReturnsIdentity() {
            // Given
            LazyForLongArray sut = of(1L);

            // When
            long sum = sut.fold(7L, Long::sum);

            // Then
            assertThat(sum).isEqualTo(7L);
        }
    }
}
