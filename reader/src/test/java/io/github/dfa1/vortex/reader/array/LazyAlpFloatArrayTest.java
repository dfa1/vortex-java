package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class LazyAlpFloatArrayTest {


    private static final DType F32 = new DType.Primitive(PType.F32, false);

    private static LazyAlpFloatArray of(float scale, int... encoded) {
        MemorySegment seg = Arena.ofAuto().allocate((long) encoded.length * 4, 4);
        for (int i = 0; i < encoded.length; i++) {
            seg.setAtIndex(PTypeIO.LE_INT, i, encoded[i]);
        }
        return new LazyAlpFloatArray(F32, encoded.length, seg, scale, 1.0f);
    }

    @Test
    void getFloatMultipliesByScale() {
        // Given — value 1.23f encoded as int 123 with scale 0.01f
        LazyAlpFloatArray sut = of(0.01f, 123, 456, 789);

        // When + Then
        assertThat(sut.getFloat(0)).isCloseTo(1.23f, offset(1e-5f));
        assertThat(sut.getFloat(1)).isCloseTo(4.56f, offset(1e-5f));
        assertThat(sut.getFloat(2)).isCloseTo(7.89f, offset(1e-5f));
    }

    @Test
    void foldSumApplies() {
        // Given
        LazyAlpFloatArray sut = of(1.0f, 1, 2, 3, 4);

        // When
        double sum = sut.fold(0.0, Double::sum);

        // Then
        assertThat(sum).isCloseTo(10.0, offset(1e-9));
    }

    @Test
    void materializeDecodesAllRows() {
        // Given
        LazyAlpFloatArray sut = of(0.01f, 123, 456, 789);

        // When
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = sut.materialize(arena);

            // Then — each materialized row matches the lazy getter
            for (int i = 0; i < 3; i++) {
                assertThat(seg.getAtIndex(PTypeIO.LE_FLOAT, i)).as("row %d", i)
                        .isCloseTo(sut.getFloat(i), offset(1e-6f));
            }
        }
    }

    @Test
    void metadataExposed() {
        // Given
        LazyAlpFloatArray sut = of(0.5f, 1, 2, 3);

        // When + Then
        assertThat(sut.length()).isEqualTo(3);
        assertThat(sut.dtype()).isEqualTo(F32);
        assertThat(sut.factorF()).isEqualTo(0.5f);
        assertThat(sut.factorE()).isEqualTo(1.0f);
    }
}
