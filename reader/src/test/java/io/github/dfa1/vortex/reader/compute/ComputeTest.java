package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings("removal") // transitional — exercises the deprecated Mask / Compute.filter until the fused multi-column filteredReduce lands
class ComputeTest {

    private static final Arena ARENA = Arena.ofAuto();

    @Test
    void filterDelegatesToTheStreamingKernel() {
        // Given a plain long array
        Array array = ComputeArrays.longArray(ARENA, 10, 20, 30, 40, 50);

        // When filtering through the public facade
        Mask result = Compute.filter(array, new Predicate.Gt(25L), ARENA);

        // Then the facade returns the same selection the kernel would
        assertThat(result.trueCount()).isEqualTo(3L);
        assertThat(result.get(2)).isTrue();
        assertThat(result.get(0)).isFalse();
    }

    @Test
    void sumDelegatesToTheReduction() {
        // Given a plain long array
        Array array = ComputeArrays.longArray(ARENA, 1, 2, 3, 4);

        // When summing through the facade with an all-true mask
        Number result = Compute.sum(array, Mask.allTrue(array.length()));

        // Then the total is returned
        assertThat(result).isEqualTo(10L);
    }

    @Test
    void countMinMaxDelegateToTheReductions() {
        // Given a plain long array
        Array array = ComputeArrays.longArray(ARENA, 5, 1, 9, 3);
        Mask all = Mask.allTrue(array.length());

        // When reducing through the facade
        long count = Compute.count(array, all);
        Object min = Compute.min(array, all);
        Object max = Compute.max(array, all);

        // Then each facade method returns its reduction's result
        assertThat(count).isEqualTo(4L);
        assertThat(min).isEqualTo(1L);
        assertThat(max).isEqualTo(9L);
    }

    @Test
    void filterComposesWithReduceAsAPipeline() {
        // Given a long array — the ADR's filter-then-reduce pipeline over one column
        Array array = ComputeArrays.longArray(ARENA, 10, 20, 30, 40, 50);

        // When filtering > 20 and summing the survivors
        Mask mask = Compute.filter(array, new Predicate.Gt(20L), ARENA);
        Number result = Compute.sum(array, mask);

        // Then only the selected rows are folded
        assertThat(result).isEqualTo(120L);
    }

    @Test
    void filterRejectsNullArguments() {
        // Given a valid array
        Array array = ComputeArrays.longArray(ARENA, 1);

        // When any required argument is null
        // Then the facade fails fast
        assertThatNullPointerException().isThrownBy(() -> Compute.filter(null, new Predicate.Eq(1L), ARENA));
        assertThatNullPointerException().isThrownBy(() -> Compute.filter(array, null, ARENA));
        assertThatNullPointerException().isThrownBy(() -> Compute.filter(array, new Predicate.Eq(1L), null));
    }

    @Test
    void filterEqSelectsAStringColumn() {
        // Given a Utf8 column — exercises the VarBinArray accessor + the Compare Comparable branch
        Array array = ComputeArrays.utf8Array(ARENA, "apple", "banana", "cherry", "banana");

        // When filtering for an exact string match
        Mask result = Compute.filter(array, new Predicate.Eq("banana"), ARENA);

        // Then only the two "banana" rows are selected
        assertThat(result.trueCount()).isEqualTo(2L);
        assertThat(result.get(1)).isTrue();
        assertThat(result.get(3)).isTrue();
        assertThat(result.get(0)).isFalse();
    }

    @Test
    void filterLtOrdersAStringColumnLexicographically() {
        // Given a Utf8 column whose rows straddle the bound; "apple" < "mango" but "zebra" > "mango"
        // lexicographically, the exact pair that catches a kernel routing strings through a numeric
        // (non-Comparable) compare path
        Array array = ComputeArrays.utf8Array(ARENA, "apple", "mango", "zebra");

        // When filtering for values strictly less than "mango"
        Mask result = Compute.filter(array, new Predicate.Lt("mango"), ARENA);

        // Then only the lexicographically smaller "apple" survives
        assertThat(result.trueCount()).isEqualTo(1L);
        assertThat(result.get(0)).isTrue();
        assertThat(result.get(1)).isFalse();
        assertThat(result.get(2)).isFalse();
    }

    @Test
    void minMaxReduceAStringColumn() {
        // Given a Utf8 column out of lexicographic order, so a kernel that returned the first/last
        // row instead of the ordered extreme would pass on a sorted input but fail here
        Array array = ComputeArrays.utf8Array(ARENA, "mango", "apple", "zebra", "banana");
        Mask all = Mask.allTrue(array.length());

        // When reducing for the extremes
        Object min = Compute.min(array, all);
        Object max = Compute.max(array, all);

        // Then the lexicographic min and max are returned, not the boundary rows
        assertThat(min).isEqualTo("apple");
        assertThat(max).isEqualTo("zebra");
    }

    @Test
    void sumRejectsANonNumericStringColumn() {
        // Given a Utf8 column — summing a string is meaningless and previously leaked a raw
        // ClassCastException out of the public facade; it must surface as the typed domain exception
        Array array = ComputeArrays.utf8Array(ARENA, "apple", "banana");

        // When summing it
        // Then a VortexException naming the unsupported dtype is thrown, not a ClassCastException
        assertThatThrownBy(() -> Compute.sum(array, Mask.allTrue(array.length())))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("SUM")
                .hasMessageContaining("Utf8");
    }

    @Test
    void reduceRejectsNullArguments() {
        // Given a valid array
        Array array = ComputeArrays.longArray(ARENA, 1);

        // When the mask is null
        // Then the facade fails fast
        assertThatNullPointerException().isThrownBy(() -> Compute.sum(array, null));
    }
}
