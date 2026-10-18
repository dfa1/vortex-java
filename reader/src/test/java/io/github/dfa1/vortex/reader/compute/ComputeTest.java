package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinSlicedArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.util.List;
import java.util.function.LongPredicate;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.mockito.BDDMockito.given;
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
                .isThrownBy(() -> Compute.filteredAggregate(null, RowFilter.gt(ColumnName.of("f"), 0L), ColumnName.of("v")));
        assertThatNullPointerException()
                .isThrownBy(() -> Compute.filteredAggregate(chunk, null, ColumnName.of("v")));
    }

    /// [Compute#matcher(Chunk, RowFilter)] lowers a filter once per chunk; over a string dictionary
    /// it evaluates the predicate per dictionary entry instead of per row (#552). The per-row
    /// [Compute#matches(Chunk, RowFilter, long)] is the oracle: both must select the same rows.
    @Nested
    class Matcher {

        private static final ColumnName SYM = ColumnName.of("sym");

        /// An unsorted pool with a null row (row 3) whose code points at "café", so a lowering that
        /// ignored validity would select row 3 for `eq` and miss it for `is null`.
        private static Array nullableDict() {
            Array dict = ComputeArrays.utf8DictArray(ARENA, new String[]{"café", "AAPL", "MSFT"},
                    new int[]{0, 1, 2, 0, 1, 0});
            return new MaskedArray(dict, ComputeArrays.boolArray(ARENA, true, true, true, false, true, true));
        }

        static Stream<Arguments> predicates() {
            return Stream.of(
                    Arguments.of(named("eq", new Predicate.Eq("café")), List.of(0L, 5L)),
                    Arguments.of(named("neq", new Predicate.Neq("café")), List.of(1L, 2L, 4L)),
                    Arguments.of(named("lt in UTF-8 order", new Predicate.Lt("café")), List.of(1L, 2L, 4L)),
                    Arguments.of(named("between", new Predicate.Between("AAPL", "MSFT")), List.of(1L, 2L, 4L)),
                    Arguments.of(named("no entry matches", new Predicate.Eq("TSLA")), List.of()),
                    Arguments.of(named("is null", new Predicate.IsNull()), List.of(3L)),
                    Arguments.of(named("is null or eq", new Predicate.Or(new Predicate.IsNull(),
                            new Predicate.Eq("AAPL"))), List.of(1L, 3L, 4L)));
        }

        @ParameterizedTest
        @MethodSource("predicates")
        void selectsTheRowsMatchesSelects(Predicate predicate, List<Long> expected) {
            // Given
            Chunk chunk = mock(Chunk.class);
            given(chunk.<Array>column(SYM)).willReturn(nullableDict());
            RowFilter filter = new RowFilter.Column(SYM, predicate);

            // When
            LongPredicate sut = Compute.matcher(chunk, filter);
            List<Long> result = LongStream.range(0, 6).filter(sut).boxed().toList();

            // Then
            assertThat(result).isEqualTo(expected)
                    .isEqualTo(LongStream.range(0, 6).filter(i -> Compute.matches(chunk, filter, i)).boxed().toList());
        }

        /// A dictionary spanning many scan chunks reaches the filter as a slice (Rust's global dict
        /// layout, e.g. TPC-H `c_mktsegment`). The slice's rows must read the codes at its offset.
        @Test
        void slicedDictionary_testsCodesAtTheSliceOffset() {
            // Given — rows 2..4 of the dictionary column: "b", "a", "b"
            VarBinArray dict = ComputeArrays.utf8DictArray(ARENA, new String[]{"a", "b"}, new int[]{0, 0, 1, 0, 1});
            Chunk chunk = mock(Chunk.class);
            given(chunk.<Array>column(SYM)).willReturn(new VarBinSlicedArray(DType.UTF8, 3, dict, 2));
            RowFilter filter = RowFilter.eq(SYM, "b");

            // When
            LongPredicate sut = Compute.matcher(chunk, filter);
            List<Long> result = LongStream.range(0, 3).filter(sut).boxed().toList();

            // Then
            assertThat(result).containsExactly(0L, 2L)
                    .isEqualTo(LongStream.range(0, 3).filter(i -> Compute.matches(chunk, filter, i)).boxed().toList());
        }

        /// Rust's dict compare declines when the dictionary has more entries than there are rows;
        /// the matcher then tests rows directly and must still select the right ones.
        @Test
        void dictionaryLargerThanRows_stillSelectsMatchingRows() {
            // Given — 4 entries, 2 rows
            Chunk chunk = mock(Chunk.class);
            given(chunk.<Array>column(SYM)).willReturn(ComputeArrays.utf8DictArray(ARENA,
                    new String[]{"a", "b", "c", "d"}, new int[]{3, 1}));

            // When
            LongPredicate sut = Compute.matcher(chunk, RowFilter.eq(SYM, "b"));

            // Then
            assertThat(LongStream.range(0, 2).filter(sut).boxed().toList()).containsExactly(1L);
        }

        /// A code past the dictionary is malformed input: it must fail as [VortexException], never as
        /// an `ArrayIndexOutOfBoundsException` from the per-entry table (ADR 0003).
        @Test
        void codeOutsideDictionary_throwsVortexException() {
            // Given — 2 entries, row 1 carries code 7
            Chunk chunk = mock(Chunk.class);
            given(chunk.<Array>column(SYM)).willReturn(ComputeArrays.utf8DictArray(ARENA,
                    new String[]{"a", "b"}, new int[]{0, 7, 1}));
            LongPredicate sut = Compute.matcher(chunk, RowFilter.eq(SYM, "b"));

            // When / Then
            assertThatThrownBy(() -> sut.test(1)).isInstanceOf(VortexException.class);
        }

        @Test
        void rejectsNullArguments() {
            // Given
            Chunk chunk = mock(Chunk.class);

            // When / Then
            assertThatNullPointerException().isThrownBy(() -> Compute.matcher(null, RowFilter.eq(SYM, "b")));
            assertThatNullPointerException().isThrownBy(() -> Compute.matcher(chunk, null));
        }
    }
}
