package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.PTypeIO;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlpDoubleArrayTest {

    private static final DType F64 = new DType.Primitive(PType.F64, false);

    private static AlpDoubleArray of(double scale, long... encoded) {
        MemorySegment seg = Arena.ofAuto().allocate((long) encoded.length * 8, 8);
        for (int i = 0; i < encoded.length; i++) {
            seg.setAtIndex(PTypeIO.LE_LONG, i, encoded[i]);
        }
        return new AlpDoubleArray(F64, encoded.length, seg, scale);
    }

    @Nested
    class GetDouble {

        @Test
        void appliesScaleOnAccess() {
            // Given: ALP scale 0.01, encoded values 100, 250, -75
            AlpDoubleArray sut = of(0.01, 100L, 250L, -75L);

            // When + Then
            assertThat(sut.getDouble(0)).isEqualTo(1.0);
            assertThat(sut.getDouble(1)).isEqualTo(2.5);
            assertThat(sut.getDouble(2)).isEqualTo(-0.75);
        }
    }

    @Nested
    class ForEachDouble {

        @Test
        void visitsAllInOrder() {
            // Given
            AlpDoubleArray sut = of(0.1, 10L, 20L, 30L, 40L);
            List<Double> collected = new ArrayList<>();

            // When
            sut.forEachDouble(collected::add);

            // Then
            assertThat(collected).containsExactly(1.0, 2.0, 3.0, 4.0);
        }
    }

    @Nested
    class Fold {

        @Test
        void sumMatchesScaledValues() {
            // Given
            AlpDoubleArray sut = of(0.5, 2L, 4L, 6L);

            // When
            double sum = sut.fold(0.0, Double::sum);

            // Then: (2 + 4 + 6) * 0.5 = 6.0
            assertThat(sum).isEqualTo(6.0);
        }
    }

    @Nested
    class SumWhereGt {

        @Test
        void includesOnlyValuesStrictlyGreater() {
            // Given: scale 0.1, encoded 10, 20, 30, 40 → values 1.0, 2.0, 3.0, 4.0
            AlpDoubleArray sut = of(0.1, 10L, 20L, 30L, 40L);

            // When + Then
            assertThat(sut.sumWhereGt(2.5)).isEqualTo(7.0);   // 3.0 + 4.0
            assertThat(sut.sumWhereGt(2.0)).isEqualTo(7.0);   // 3.0 + 4.0 (2.0 not strictly >)
            assertThat(sut.sumWhereGt(0.0)).isEqualTo(10.0);  // all
            assertThat(sut.sumWhereGt(4.0)).isEqualTo(0.0);   // none
        }

        @Test
        void worksWithNegativeValues() {
            // Given: scale 1.0, values -3, -1, 0, 1, 3
            AlpDoubleArray sut = of(1.0, -3L, -1L, 0L, 1L, 3L);

            // When + Then
            assertThat(sut.sumWhereGt(-2.0)).isEqualTo(3.0);  // -1 + 0 + 1 + 3
            assertThat(sut.sumWhereGt(0.0)).isEqualTo(4.0);   // 1 + 3
            assertThat(sut.sumWhereGt(3.0)).isEqualTo(0.0);   // none
        }

        @Test
        void emptyArrayReturnsZero() {
            // Given
            AlpDoubleArray sut = of(0.1);

            // When + Then
            assertThat(sut.sumWhereGt(0.0)).isEqualTo(0.0);
        }

        @Test
        void resultMatchesFoldWithExplicitPredicate() {
            // Given: same data + predicate via fold
            AlpDoubleArray sut = of(0.01, 100L, 250L, 175L, 350L, 425L);
            double threshold = 2.0;

            // When
            double viaPushdown = sut.sumWhereGt(threshold);
            double viaFold = sut.fold(0.0, (acc, v) -> v > threshold ? acc + v : acc);

            // Then
            assertThat(viaPushdown).isEqualTo(viaFold);
        }
    }
}
