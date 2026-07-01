package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.array.Array;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;

class ComputeTest {

    private static final Arena ARENA = Arena.ofAuto();

    @Test
    void filteredSumDelegatesToTheFusedKernel() {
        // Given a filter column and a positionally aligned aggregate column
        Array filter = ComputeArrays.longArray(ARENA, 10, 20, 30, 40, 50);
        Array agg = ComputeArrays.longArray(ARENA, 1, 2, 3, 4, 5);

        // When summing the aggregate over the rows where the filter is > 20 through the facade
        Number result = Compute.filteredSum(filter, new Predicate.Gt(20L), agg);

        // Then only the survivors (rows 2, 3, 4) are folded
        assertThat(result).isEqualTo(12L);
    }

    @Test
    void filteredSumRejectsNullArguments() {
        // Given valid columns
        Array filter = ComputeArrays.longArray(ARENA, 1);
        Array agg = ComputeArrays.longArray(ARENA, 1);

        // When any required argument is null
        // Then the facade fails fast
        assertThatNullPointerException().isThrownBy(() -> Compute.filteredSum(null, new Predicate.Eq(1L), agg));
        assertThatNullPointerException().isThrownBy(() -> Compute.filteredSum(filter, null, agg));
        assertThatNullPointerException().isThrownBy(() -> Compute.filteredSum(filter, new Predicate.Eq(1L), null));
    }

    @Test
    void filteredAggregateRejectsNullArguments() {
        // Given a chunk (a null aggColumn is allowed — it means COUNT(*))
        Chunk chunk = mock(Chunk.class);

        // When the chunk or the filter is null
        // Then the facade fails fast
        assertThatNullPointerException()
                .isThrownBy(() -> Compute.filteredAggregate(null, RowFilter.gt("f", 0L), "v"));
        assertThatNullPointerException()
                .isThrownBy(() -> Compute.filteredAggregate(chunk, null, "v"));
    }
}
