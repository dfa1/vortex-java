package io.github.dfa1.vortex.core.compute;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [PcoAns#spreadStateSymbols(int[], int)] against pcodec's own `spec.rs` reference outputs.
class PcoAnsTest {

    @Test
    void matchesSpecRsReferenceOutput() {
        // Given — from pcodec spec.rs test: ans_spec_new
        // weights=[1,1,3,11], total=16=2^4 → size_log=4
        int[] weights = {1, 1, 3, 11};
        int tableSize = 16;

        // When
        int[] stateSymbols = PcoAns.spreadStateSymbols(weights, tableSize);

        // Then — exact output from pcodec spec.rs reference test
        assertThat(stateSymbols).containsExactly(0, 3, 2, 3, 2, 3, 3, 3, 3, 1, 3, 2, 3, 3, 3, 3);
    }

    @Test
    void trivialSingleSymbol_fillsTable() {
        // Given — from pcodec spec.rs test: ans_spec_new_trivial (weight=[1])
        int[] weights = {1};

        // When
        int[] stateSymbols = PcoAns.spreadStateSymbols(weights, 1);

        // Then
        assertThat(stateSymbols).containsExactly(0);
    }

    @Test
    void twoEqualWeights_twoElementTable() {
        // Given — weights=[2], total=2=2^1
        int[] weights = {2};

        // When
        int[] stateSymbols = PcoAns.spreadStateSymbols(weights, 2);

        // Then
        assertThat(stateSymbols).containsExactly(0, 0);
    }
}
