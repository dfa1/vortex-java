package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingFilterKernelTest {

    private static final Arena ARENA = Arena.ofAuto();

    private final StreamingFilterKernel sut = new StreamingFilterKernel();

    @Test
    void eqSelectsMatchingValue() {
        // Given a plain long array
        Array array = ComputeArrays.longArray(ARENA, 10, 20, 30, 40, 50);

        // When filtering for == 30
        Mask result = filter(array, new Predicate.Eq(30L));

        // Then only the matching position is selected
        assertThat(selected(result)).containsExactly(2L);
    }

    @Test
    void ltSelectsValuesBelowBound() {
        // Given a plain long array
        Array array = ComputeArrays.longArray(ARENA, 10, 20, 30, 40, 50);

        // When filtering for < 30
        Mask result = filter(array, new Predicate.Lt(30L));

        // Then the positions strictly below the bound are selected
        assertThat(selected(result)).containsExactly(0L, 1L);
    }

    @Test
    void gtSelectsValuesAboveBound() {
        // Given a plain long array
        Array array = ComputeArrays.longArray(ARENA, 10, 20, 30, 40, 50);

        // When filtering for > 30
        Mask result = filter(array, new Predicate.Gt(30L));

        // Then the positions strictly above the bound are selected
        assertThat(selected(result)).containsExactly(3L, 4L);
    }

    @Test
    void betweenSelectsInclusiveRange() {
        // Given a plain long array
        Array array = ComputeArrays.longArray(ARENA, 10, 20, 30, 40, 50);

        // When filtering for [20, 40]
        Mask result = filter(array, new Predicate.Between(20L, 40L));

        // Then both bounds are inclusive
        assertThat(selected(result)).containsExactly(1L, 2L, 3L);
    }

    @Test
    void isNullSelectsNullPositions() {
        // Given a masked array with nulls at indices 1 and 3
        MaskedArray array = ComputeArrays.maskedLongArray(ARENA,
                new long[]{1, 2, 3, 4}, new boolean[]{true, false, true, false});

        // When testing IS NULL
        Mask result = filter(array, new Predicate.IsNull());

        // Then exactly the null positions are selected
        assertThat(selected(result)).containsExactly(1L, 3L);
    }

    @Test
    void isNotNullSelectsValidPositions() {
        // Given a masked array with nulls at indices 1 and 3
        MaskedArray array = ComputeArrays.maskedLongArray(ARENA,
                new long[]{1, 2, 3, 4}, new boolean[]{true, false, true, false});

        // When testing IS NOT NULL
        Mask result = filter(array, new Predicate.IsNotNull());

        // Then exactly the valid positions are selected
        assertThat(selected(result)).containsExactly(0L, 2L);
    }

    @Test
    void andIntersectsBothPredicates() {
        // Given a plain long array
        Array array = ComputeArrays.longArray(ARENA, 10, 20, 30, 40, 50);

        // When filtering for > 10 AND < 50
        Mask result = filter(array, new Predicate.And(new Predicate.Gt(10L), new Predicate.Lt(50L)));

        // Then only positions satisfying both survive
        assertThat(selected(result)).containsExactly(1L, 2L, 3L);
    }

    @Test
    void orUnionsBothPredicates() {
        // Given a plain long array
        Array array = ComputeArrays.longArray(ARENA, 10, 20, 30, 40, 50);

        // When filtering for < 20 OR > 40
        Mask result = filter(array, new Predicate.Or(new Predicate.Lt(20L), new Predicate.Gt(40L)));

        // Then positions satisfying either survive
        assertThat(selected(result)).containsExactly(0L, 4L);
    }

    @Test
    void nullPositionExcludedFromValuePredicate() {
        // Given a masked array where the null at index 1 holds a value that *would* match
        MaskedArray array = ComputeArrays.maskedLongArray(ARENA,
                new long[]{10, 20, 30}, new boolean[]{true, false, true});

        // When filtering for > 5 — the null's underlying 20 satisfies the value test
        Mask result = filter(array, new Predicate.Gt(5L));

        // Then the null is still excluded (three-valued logic: null compare is not true)
        assertThat(selected(result)).containsExactly(0L, 2L);
    }

    @Test
    void incomingAllTrueMaskKeepsEveryMatch() {
        // Given a plain long array and an explicit all-true incoming mask
        Array array = ComputeArrays.longArray(ARENA, 10, 20, 30, 40, 50);

        // When filtering > 30 under the all-true mask
        Mask result = sut.apply(array, Mask.allTrue(array.length()), new Predicate.Gt(30L), ARENA);

        // Then every match survives
        assertThat(selected(result)).containsExactly(3L, 4L);
    }

    @Test
    void incomingAllFalseMaskShortCircuits() {
        // Given a plain long array and an all-false incoming mask
        Array array = ComputeArrays.longArray(ARENA, 10, 20, 30, 40, 50);

        // When filtering under the all-false mask
        Mask result = sut.apply(array, Mask.allFalse(array.length()), new Predicate.Gt(0L), ARENA);

        // Then nothing is selected and no bitmap is allocated (short-circuit to AllFalse)
        assertThat(result).isInstanceOf(Mask.AllFalse.class);
        assertThat(result.trueCount()).isZero();
    }

    @Test
    void incomingBitmapMaskIsIntersected() {
        // Given a first filter narrowing the array to > 10
        Array array = ComputeArrays.longArray(ARENA, 10, 20, 30, 40, 50);
        Mask first = filter(array, new Predicate.Gt(10L));

        // When a second filter < 50 runs under that mask
        Mask result = sut.apply(array, first, new Predicate.Lt(50L), ARENA);

        // Then the result is the intersection, not the second predicate alone
        assertThat(selected(result)).containsExactly(1L, 2L, 3L);
    }

    @Test
    void emptyArrayProducesEmptyMask() {
        // Given an empty array
        Array array = ComputeArrays.longArray(ARENA);

        // When filtered
        Mask result = filter(array, new Predicate.Eq(1L));

        // Then the mask covers zero positions
        assertThat(result.length()).isZero();
        assertThat(result.trueCount()).isZero();
    }

    @Test
    void allPositionsSelected() {
        // Given an array of identical values
        Array array = ComputeArrays.longArray(ARENA, 7, 7, 7);

        // When all match the predicate
        Mask result = filter(array, new Predicate.Eq(7L));

        // Then every position is selected
        assertThat(result.trueCount()).isEqualTo(3L);
    }

    @Test
    void noPositionsSelected() {
        // Given an array with no matching value
        Array array = ComputeArrays.longArray(ARENA, 1, 2, 3);

        // When nothing matches
        Mask result = filter(array, new Predicate.Eq(99L));

        // Then no position is selected
        assertThat(result.trueCount()).isZero();
    }

    @Test
    void dictEncodingAlignsWithMaterializedEquivalent() {
        // Given a dict array [10,20,30,10,20] and its materialized twin — the ADR risk-note check
        // that a mask stays positionally aligned under a cascaded encoding
        Array dict = ComputeArrays.dictLongArray(ARENA, new long[]{10, 20, 30}, new int[]{0, 1, 2, 0, 1});
        Array materialized = ComputeArrays.longArray(ARENA, 10, 20, 30, 10, 20);

        // When the same predicate runs over both representations
        Mask dictMask = filter(dict, new Predicate.Gt(15L));
        Mask materializedMask = filter(materialized, new Predicate.Gt(15L));

        // Then the masks are identical
        assertThat(dictMask).isEqualTo(materializedMask);
        assertThat(selected(dictMask)).containsExactly(1L, 2L, 4L);
    }

    @Test
    void chunkedEncodingAlignsWithMaterializedEquivalent() {
        // Given a chunked array [[1,2],[3,4,5]] and its materialized twin
        Array chunked = ComputeArrays.chunkedLongArray(ARENA, new long[]{1, 2}, new long[]{3, 4, 5});
        Array materialized = ComputeArrays.longArray(ARENA, 1, 2, 3, 4, 5);

        // When the same predicate runs over both representations
        Mask chunkedMask = filter(chunked, new Predicate.Gt(2L));
        Mask materializedMask = filter(materialized, new Predicate.Gt(2L));

        // Then the masks are identical across the chunk boundary
        assertThat(chunkedMask).isEqualTo(materializedMask);
        assertThat(selected(chunkedMask)).containsExactly(2L, 3L, 4L);
    }

    private Mask filter(Array array, Predicate predicate) {
        return sut.apply(array, Mask.allTrue(array.length()), predicate, ARENA);
    }

    private static List<Long> selected(Mask mask) {
        List<Long> out = new ArrayList<>();
        for (long i = 0; i < mask.length(); i++) {
            if (mask.get(i)) {
                out.add(i);
            }
        }
        return out;
    }
}
