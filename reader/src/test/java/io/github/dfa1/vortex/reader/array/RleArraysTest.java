package io.github.dfa1.vortex.reader.array;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RleArraysTest {

    @Test
    void chunkValueCount_middleChunk_usesNextOffset() {
        // Given — two chunks, pool [0,3,5); firstOffset 0
        long[] offsets = {0, 3};

        // When
        int count = RleArrays.chunkValueCount(0, 2, offsets, 0L, 5L);

        // Then — offsets[1] - offsets[0] = 3
        assertThat(count).isEqualTo(3);
    }

    @Test
    void chunkValueCount_lastChunk_usesValuesLen() {
        // Given — last chunk falls back to the total pool length
        long[] offsets = {0, 3};

        // When
        int count = RleArrays.chunkValueCount(1, 2, offsets, 0L, 5L);

        // Then — valuesLen(5) - offsets[1](3) = 2
        assertThat(count).isEqualTo(2);
    }

    @Test
    void chunkValueCount_subtractsFirstOffset() {
        // Given — pool origin shifted by firstOffset 10
        long[] offsets = {10, 13};

        // When / Then — both branches still measure the local span
        assertThat(RleArrays.chunkValueCount(0, 2, offsets, 10L, 5L)).isEqualTo(3);
        assertThat(RleArrays.chunkValueCount(1, 2, offsets, 10L, 5L)).isEqualTo(2);
    }
}
