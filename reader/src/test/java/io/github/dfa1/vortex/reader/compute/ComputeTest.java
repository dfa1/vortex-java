package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinDictArray;
import io.github.dfa1.vortex.reader.array.VarBinSlicedArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.util.List;
import java.util.Map;
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

    /// [Compute#groupedAggregate(Chunk, ColumnName, RowFilter, ColumnName)] folds per dictionary
    /// code; every expectation below is the plain per-row SQL answer, worked out by hand.
    @Nested
    class Grouped {

        private static final ColumnName G = ColumnName.of("g");
        private static final ColumnName V = ColumnName.of("v");

        /// Groups a, b, a, (null), b, a: row 3's group is null although its code points at "a".
        private static Array nullableGroups() {
            Array dict = ComputeArrays.utf8DictArray(ARENA, new String[]{"a", "b"}, new int[]{0, 1, 0, 0, 1, 0});
            return new MaskedArray(dict, ComputeArrays.boolArray(ARENA, true, true, true, false, true, true));
        }

        private static Chunk chunk(Array group, Array agg) {
            Chunk chunk = mock(Chunk.class);
            given(chunk.rowCount()).willReturn(group.length());
            given(chunk.<Array>column(G)).willReturn(group);
            given(chunk.<Array>column(V)).willReturn(agg);
            return chunk;
        }

        @Test
        void countOnly_countsRowsPerGroupWithNullAsItsOwnGroup() {
            // Given
            Chunk chunk = chunk(nullableGroups(), null);

            // When
            Map<Object, FilteredAggregate> result = Compute.groupedAggregate(chunk, G, null, null).orElseThrow();

            // Then
            assertThat(result).containsOnlyKeys("a", "b", null);
            assertThat(result.get("a").selectedRows()).isEqualTo(3);
            assertThat(result.get("b").selectedRows()).isEqualTo(2);
            assertThat(result.get(null).selectedRows()).isEqualTo(1);
        }

        @Test
        void longColumn_foldsSumMinMaxSkippingNullValues() {
            // Given — v = 5, 7, (null), 2, -1, 9
            Array v = ComputeArrays.maskedLongArray(ARENA, new long[]{5, 7, 0, 2, -1, 9},
                    new boolean[]{true, true, false, true, true, true});

            // When
            Map<Object, FilteredAggregate> result = Compute.groupedAggregate(chunk(nullableGroups(), v), G, null, V)
                    .orElseThrow();

            // Then — a: rows 0, 2, 5 with row 2 null; b: rows 1, 4; null group: row 3
            assertThat(result.get("a")).isEqualTo(new FilteredAggregate(3, 2, 14L, 5L, 9L));
            assertThat(result.get("b")).isEqualTo(new FilteredAggregate(2, 2, 6L, -1L, 7L));
            assertThat(result.get(null)).isEqualTo(new FilteredAggregate(1, 1, 2L, 2L, 2L));
        }

        @Test
        void doubleColumn_ordersMinMaxLikeRust() {
            // Given — group a holds -0.0, NaN and 0.0: Rust's order puts -0.0 first and NaN last
            Array group = ComputeArrays.utf8DictArray(ARENA, new String[]{"a"}, new int[]{0, 0, 0});
            Array v = ComputeArrays.doubleArray(ARENA, -0.0, Double.NaN, 0.0);

            // When
            FilteredAggregate result = Compute.groupedAggregate(chunk(group, v), G, null, V).orElseThrow().get("a");

            // Then
            assertThat(result.min()).isEqualTo(-0.0);
            assertThat(result.max()).isEqualTo(Double.NaN);
            assertThat(result.aggNonNullCount()).isEqualTo(3);
        }

        @Test
        void filter_foldsOnlyTheSelectedRows() {
            // Given — keep v > 4: rows 0 (a, 5), 1 (b, 7), 5 (a, 9)
            Array v = ComputeArrays.longArray(ARENA, 5, 7, 1, 2, -1, 9);
            Chunk chunk = chunk(nullableGroups(), v);

            // When
            Map<Object, FilteredAggregate> result = Compute.groupedAggregate(chunk, G, RowFilter.gt(V, 4L), V)
                    .orElseThrow();

            // Then — groups with no selected row do not appear
            assertThat(result).containsOnlyKeys("a", "b");
            assertThat(result.get("a")).isEqualTo(new FilteredAggregate(2, 2, 14L, 5L, 9L));
            assertThat(result.get("b")).isEqualTo(new FilteredAggregate(1, 1, 7L, 7L, 7L));
        }

        /// A dictionary spanning many chunks reaches the kernel as a slice; codes are read at its offset.
        @Test
        void slicedDictionary_readsCodesAtTheSliceOffset() {
            // Given — rows 2..4 of a, a, b, b, a
            VarBinArray dict = ComputeArrays.utf8DictArray(ARENA, new String[]{"a", "b"}, new int[]{0, 0, 1, 1, 0});
            Chunk chunk = chunk(new VarBinSlicedArray(DType.UTF8, 3, dict, 2), null);

            // When
            Map<Object, FilteredAggregate> result = Compute.groupedAggregate(chunk, G, null, null).orElseThrow();

            // Then
            assertThat(result.get("b").selectedRows()).isEqualTo(2);
            assertThat(result.get("a").selectedRows()).isEqualTo(1);
        }

        /// A group is a value, not a code: two entries holding the same string fold into one group.
        @Test
        void duplicateDictionaryValues_foldIntoOneGroup() {
            // Given — entries 0 and 2 are both "x"
            Array group = ComputeArrays.utf8DictArray(ARENA, new String[]{"x", "y", "x"}, new int[]{0, 2, 1, 2});
            Array v = ComputeArrays.longArray(ARENA, 1, 10, 100, 1000);

            // When
            Map<Object, FilteredAggregate> result = Compute.groupedAggregate(chunk(group, v), G, null, V).orElseThrow();

            // Then
            assertThat(result.get("x")).isEqualTo(new FilteredAggregate(3, 3, 1011L, 1L, 1000L));
        }

        @Test
        void codeOutsideDictionary_throwsVortexException() {
            // Given — 2 entries, row 1 carries code 9
            Array group = ComputeArrays.utf8DictArray(ARENA, new String[]{"a", "b"}, new int[]{0, 9});
            Chunk chunk = chunk(group, null);

            // When / Then
            assertThatThrownBy(() -> Compute.groupedAggregate(chunk, G, null, null))
                    .isInstanceOf(VortexException.class);
        }

        /// The codes buffer is untrusted: one shorter than the rows it claims must fail as malformed
        /// input, not as an IndexOutOfBoundsException.
        @Test
        void codesBufferShorterThanRows_throwsVortexException() {
            // Given — a dictionary array claiming 3 rows over a 1-code buffer
            VarBinDictArray one = (VarBinDictArray) ComputeArrays.utf8DictArray(ARENA, new String[]{"a"}, new int[]{0});
            Array group = new VarBinDictArray(DType.UTF8, 3, one.bytesSegment(), one.dictValOffsets(),
                    one.dictValOffPType(), one.dictCodesSegs(), one.dictCodesPType());
            Chunk chunk = chunk(group, null);

            // When / Then
            assertThatThrownBy(() -> Compute.groupedAggregate(chunk, G, null, null))
                    .isInstanceOf(VortexException.class);
        }

        @Test
        void booleanGroupColumn_foldsFalseTrueAndNull() {
            // Given — g = true, false, true, (null); v = 1, 2, 3, 4
            Array group = new MaskedArray(ComputeArrays.boolArray(ARENA, true, false, true, true),
                    ComputeArrays.boolArray(ARENA, true, true, true, false));
            Array v = ComputeArrays.longArray(ARENA, 1, 2, 3, 4);

            // When
            Map<Object, FilteredAggregate> result = Compute.groupedAggregate(chunk(group, v), G, null, V).orElseThrow();

            // Then
            assertThat(result).containsOnlyKeys(true, false, null);
            assertThat(result.get(true)).isEqualTo(new FilteredAggregate(2, 2, 4L, 1L, 3L));
            assertThat(result.get(false)).isEqualTo(new FilteredAggregate(1, 1, 2L, 2L, 2L));
            assertThat(result.get(null)).isEqualTo(new FilteredAggregate(1, 1, 4L, 4L, 4L));
        }

        @Test
        void integerGroupColumn_foldsEachDistinctValueWithAFilter() {
            // Given — g = 7, -3, 7, 7, -3; keep v > 1
            Array group = ComputeArrays.longArray(ARENA, 7, -3, 7, 7, -3);
            Array v = ComputeArrays.longArray(ARENA, 1, 2, 3, 4, 5);

            // When
            Map<Object, FilteredAggregate> result = Compute.groupedAggregate(chunk(group, v), G, RowFilter.gt(V, 1L), V)
                    .orElseThrow();

            // Then
            assertThat(result).containsOnlyKeys(7L, -3L);
            assertThat(result.get(7L)).isEqualTo(new FilteredAggregate(2, 2, 7L, 3L, 4L));
            assertThat(result.get(-3L)).isEqualTo(new FilteredAggregate(2, 2, 7L, 2L, 5L));
        }

        /// A u64 key past 2^63 has no signed-long home, so the kernel declines rather than group it
        /// under a negative key.
        @Test
        void u64GroupColumn_isEmpty() {
            // Given
            Chunk chunk = chunk(ComputeArrays.unsignedLongArray(ARENA, 1, -1), null);

            // When / Then
            assertThat(Compute.groupedAggregate(chunk, G, null, null)).isEmpty();
        }

        /// An integer column may hold a value per row; past 65,536 groups in a chunk the per-group
        /// arrays would grow with the rows, so the kernel declines.
        @Test
        void integerGroupColumnWithTooManyValues_isEmpty() {
            // Given
            long[] values = new long[70_000];
            for (int i = 0; i < values.length; i++) {
                values[i] = i;
            }
            Chunk chunk = chunk(ComputeArrays.longArray(ARENA, values), null);

            // When / Then
            assertThat(Compute.groupedAggregate(chunk, G, null, null)).isEmpty();
        }

        /// The caller groups the rows itself when the kernel cannot: it must say so, not guess.
        @Test
        void groupColumnNotDictionaryEncoded_isEmpty() {
            // Given
            Chunk chunk = chunk(ComputeArrays.utf8Array(ARENA, "a", "b"), null);

            // When / Then
            assertThat(Compute.groupedAggregate(chunk, G, null, null)).isEmpty();
        }

        /// u64 values past 2^63 have no signed-long home, and a string has no sum.
        @Test
        void aggregateColumnWithoutALane_isEmpty() {
            // Given
            Array group = ComputeArrays.utf8DictArray(ARENA, new String[]{"a"}, new int[]{0, 0});

            // When / Then
            assertThat(Compute.groupedAggregate(chunk(group, ComputeArrays.unsignedLongArray(ARENA, 1, 2)), G, null, V))
                    .isEmpty();
            assertThat(Compute.groupedAggregate(chunk(group, ComputeArrays.utf8Array(ARENA, "p", "q")), G, null, V))
                    .isEmpty();
        }
    }
}
