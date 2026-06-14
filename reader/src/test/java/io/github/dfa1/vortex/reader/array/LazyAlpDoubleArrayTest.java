package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class LazyAlpDoubleArrayTest {

    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static final DType F64 = new DType.Primitive(PType.F64, false);

    private static LazyAlpDoubleArray of(double scale, long... encoded) {
        MemorySegment seg = Arena.ofAuto().allocate((long) encoded.length * 8, 8);
        for (int i = 0; i < encoded.length; i++) {
            seg.setAtIndex(LE_LONG, i, encoded[i]);
        }
        return new LazyAlpDoubleArray(F64, encoded.length, seg, scale);
    }

    @Nested
    class GetDouble {

        @Test
        void multipliesEncodedByScale() {
            // Given — value 1.23 encoded as int 123 with scale 0.01
            LazyAlpDoubleArray sut = of(0.01, 123L, 456L, 789L);

            // When + Then
            assertThat(sut.getDouble(0)).isCloseTo(1.23, offset(1e-12));
            assertThat(sut.getDouble(1)).isCloseTo(4.56, offset(1e-12));
            assertThat(sut.getDouble(2)).isCloseTo(7.89, offset(1e-12));
        }

        @Test
        void negativeEncoded() {
            // Given
            LazyAlpDoubleArray sut = of(0.1, -42L);

            // When + Then
            assertThat(sut.getDouble(0)).isCloseTo(-4.2, offset(1e-12));
        }
    }

    @Nested
    class ForEachDouble {

        @Test
        void visitsAllInOrder() {
            // Given
            LazyAlpDoubleArray sut = of(0.001, 1L, 2L, 3L);
            List<Double> got = new ArrayList<>();

            // When
            sut.forEachDouble(got::add);

            // Then
            assertThat(got).containsExactly(0.001, 0.002, 0.003);
        }

        @Test
        void emptyIsNoop() {
            // Given
            LazyAlpDoubleArray sut = of(1.0);
            List<Double> got = new ArrayList<>();

            // When
            sut.forEachDouble(got::add);

            // Then
            assertThat(got).isEmpty();
        }
    }

    @Nested
    class Fold {

        @Test
        void sumApplies() {
            // Given
            LazyAlpDoubleArray sut = of(1.0, 1L, 2L, 3L, 4L);

            // When
            double sum = sut.fold(0.0, Double::sum);

            // Then
            assertThat(sum).isCloseTo(10.0, offset(1e-12));
        }

        @Test
        void emptyReturnsIdentity() {
            // Given
            LazyAlpDoubleArray sut = of(2.5);

            // When
            double sum = sut.fold(99.0, Double::sum);

            // Then
            assertThat(sum).isEqualTo(99.0);
        }
    }

    @Nested
    class Metadata {

        @Test
        void lengthAndDtypeFromConstructor() {
            // Given
            LazyAlpDoubleArray sut = of(0.5, 1L, 2L, 3L);

            // When + Then
            assertThat(sut.length()).isEqualTo(3);
            assertThat(sut.dtype()).isEqualTo(F64);
            assertThat(sut.scale()).isEqualTo(0.5);
        }
    }
}
