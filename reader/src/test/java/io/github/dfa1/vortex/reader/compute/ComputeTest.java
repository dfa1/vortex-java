package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.TimestampDtype;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanFilter;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LazyDateTimePartsLongArray;
import io.github.dfa1.vortex.reader.array.LazyDecimalBytePartsArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndLongArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.Mask;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.SelectedArrays;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinDictArray;
import io.github.dfa1.vortex.reader.array.VarBinSlicedArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
    void filteredAggregateRejectsNullArguments() {
        // Given a chunk (a null aggColumn is allowed — it means COUNT(*))
        Chunk chunk = mock(Chunk.class);

        // When the chunk or the filter is null
        // Then the facade fails fast
        assertThatNullPointerException()
                .isThrownBy(() -> Compute.filteredAggregate(null, ScanFilter.gt(ColumnName.of("f"), 0L), ColumnName.of("v")));
        assertThatNullPointerException()
                .isThrownBy(() -> Compute.filteredAggregate(chunk, null, ColumnName.of("v")));
    }

    /// An all-null chunk of a type with no masked form (a uuid, a list) reads as a [NullArray]; the
    /// filter tested it as a non-nullable array, so IS NULL selected none of its null rows.
    @Nested
    class AllNullChunk {

        private static final ColumnName COL = ColumnName.of("c");
        private static final long ROWS = 5;

        static Stream<Arguments> predicates() {
            return Stream.of(
                    Arguments.of(named("is null", new Predicate.IsNull()), true),
                    Arguments.of(named("is not null", new Predicate.IsNotNull()), false),
                    Arguments.of(named("eq", new Predicate.Eq(1L)), false),
                    Arguments.of(named("neq", new Predicate.Neq(1L)), false),
                    Arguments.of(named("gt", new Predicate.Gt(1L)), false),
                    Arguments.of(named("lte", new Predicate.Lte(1L)), false));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("predicates")
        void onlyIsNullSelectsItsRows(Predicate predicate, boolean selected) {
            // Given
            Chunk chunk = mock(Chunk.class);
            given(chunk.<Array>column(COL)).willReturn(new NullArray(DType.I64.asNullable(), ROWS));
            ScanFilter filter = new ScanFilter.Column(COL, predicate);

            // When
            LongPredicate result = Compute.matcher(chunk, filter);

            // Then: the lowered matcher and the per-row oracle agree on every row
            for (long i = 0; i < ROWS; i++) {
                assertThat(result.test(i)).as("row %d", i).isEqualTo(selected);
                assertThat(Compute.matches(chunk, filter, i)).as("row %d", i).isEqualTo(selected);
            }
        }
    }

    /// A `vortex.datetimeparts` timestamp column is compared after reassembling its parts once per
    /// chunk, into a bitmap, rather than per row. The per-row [Compute#matches(Chunk, ScanFilter, long)]
    /// is the oracle: both must select the same rows.
    @Nested
    class DateTimeParts {

        private static final ColumnName TS = ColumnName.of("ts");
        private static final int ROWS = 200;
        private static final long UNITS_PER_SECOND = 1_000_000L;
        private static final long UNITS_PER_DAY = 86_400L * UNITS_PER_SECOND;

        /// 200 microsecond timestamps over four bitmap words: a run-end `days` part (three days of
        /// 70, 70 and 60 rows, the shape real files take), varying seconds and subseconds.
        private static LazyDateTimePartsLongArray timestamps() {
            long[] seconds = new long[ROWS];
            long[] subseconds = new long[ROWS];
            for (int r = 0; r < ROWS; r++) {
                seconds[r] = r * 397L % 86_400L;
                subseconds[r] = r * 7_919L % UNITS_PER_SECOND;
            }
            Array days = new LazyRunEndLongArray(DType.I64, ROWS,
                    ComputeArrays.longArray(ARENA, 19_737L, 19_738L, 19_739L),
                    ComputeArrays.longArray(ARENA, 70L, 140L, 200L), 0L);
            return new LazyDateTimePartsLongArray(TimestampDtype.of(false), ROWS, days,
                    ComputeArrays.longArray(ARENA, seconds), ComputeArrays.longArray(ARENA, subseconds),
                    UNITS_PER_DAY, UNITS_PER_SECOND);
        }

        /// Every seventh row null: a comparison never selects a null row.
        private static Array nullableTimestamps() {
            boolean[] valid = new boolean[ROWS];
            for (int r = 0; r < ROWS; r++) {
                valid[r] = r % 7 != 3;
            }
            return new MaskedArray(timestamps(), ComputeArrays.boolArray(ARENA, valid));
        }

        static Stream<Arguments> filters() {
            LazyDateTimePartsLongArray ts = timestamps();
            long at = ts.getLong(100);
            long from = ts.getLong(60);
            long to = ts.getLong(150);
            return Stream.of(
                    Arguments.of(named("eq", ScanFilter.eq(TS, at))),
                    Arguments.of(named("neq", ScanFilter.neq(TS, at))),
                    Arguments.of(named("lt", ScanFilter.lt(TS, at))),
                    Arguments.of(named("lte", ScanFilter.lte(TS, at))),
                    Arguments.of(named("gt", ScanFilter.gt(TS, at))),
                    Arguments.of(named("gte", ScanFilter.gte(TS, at))),
                    Arguments.of(named("between", new ScanFilter.Column(TS, new Predicate.Between(from, to)))),
                    Arguments.of(named("window", ScanFilter.and(ScanFilter.gte(TS, from), ScanFilter.lt(TS, to)))),
                    Arguments.of(named("nothing matches", ScanFilter.lt(TS, 0L))),
                    // compared as doubles, not truncated to a long: `< at + 0.5` keeps the row at `at`
                    Arguments.of(named("lt a fractional double", ScanFilter.lt(TS, at + 0.5))),
                    Arguments.of(named("eq a fractional double", ScanFilter.eq(TS, at + 0.5))),
                    Arguments.of(named("int operand", ScanFilter.gt(TS, Integer.MAX_VALUE))),
                    Arguments.of(named("is null", ScanFilter.isNull(TS))));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("filters")
        void selectsTheRowsMatchesSelects(ScanFilter filter) {
            // Given
            Chunk chunk = mock(Chunk.class);
            given(chunk.<Array>column(TS)).willReturn(timestamps());
            given(chunk.rowCount()).willReturn((long) ROWS);

            // When
            LongPredicate sut = Compute.matcher(chunk, filter);

            // Then
            assertThat(LongStream.range(0, ROWS).filter(sut).boxed().toList())
                    .isEqualTo(LongStream.range(0, ROWS).filter(i -> Compute.matches(chunk, filter, i)).boxed().toList());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("filters")
        void nullable_neverSelectsANullRowForAComparison(ScanFilter filter) {
            // Given
            Chunk chunk = mock(Chunk.class);
            given(chunk.<Array>column(TS)).willReturn(nullableTimestamps());
            given(chunk.rowCount()).willReturn((long) ROWS);

            // When
            LongPredicate sut = Compute.matcher(chunk, filter);

            // Then
            assertThat(LongStream.range(0, ROWS).filter(sut).boxed().toList())
                    .isEqualTo(LongStream.range(0, ROWS).filter(i -> Compute.matches(chunk, filter, i)).boxed().toList());
        }

        @Test
        void window_selectsExactlyTheRowsInside() {
            // Given a window from row 60's value (inclusive) to row 150's (exclusive); seconds wrap
            // within a day, so the selected rows are those whose reassembled value falls inside
            LazyDateTimePartsLongArray ts = timestamps();
            long from = ts.getLong(60);
            long to = ts.getLong(150);
            Chunk chunk = mock(Chunk.class);
            given(chunk.<Array>column(TS)).willReturn(ts);
            given(chunk.rowCount()).willReturn((long) ROWS);

            // When
            Mask result = Compute.matcher(chunk, ScanFilter.and(ScanFilter.gte(TS, from), ScanFilter.lt(TS, to)))
                    .toMask(ROWS);

            // Then
            long expected = LongStream.range(0, ROWS).filter(i -> ts.getLong(i) >= from && ts.getLong(i) < to).count();
            assertThat(result.trueCount()).isEqualTo(expected).isPositive();
        }
    }

    /// [Compute#matcher(Chunk, ScanFilter)] lowers a filter once per chunk; over a string dictionary
    /// it evaluates the predicate per dictionary entry instead of per row (#552). The per-row
    /// [Compute#matches(Chunk, ScanFilter, long)] is the oracle: both must select the same rows.
    @Nested
    class Matcher {

        private static final ColumnName SYM = ColumnName.of("sym");

        /// An unsorted pool with a null row (row 3) whose code points at "café", so a lowering that
        /// ignored validity would select row 3 for `eq` and miss it for `is null`.
        private static Array nullableDict(PType codesPType) {
            Array dict = ComputeArrays.utf8DictArray(ARENA, new String[]{"café", "AAPL", "MSFT"},
                    new int[]{0, 1, 2, 0, 1, 0}, codesPType);
            return new MaskedArray(dict, ComputeArrays.boolArray(ARENA, true, true, true, false, true, true));
        }

        // u8 and u16 codes are mapped to a row bitmap in one pass over the codes; i32 codes are read
        // per row: every predicate must select the same rows either way
        static Stream<Arguments> predicates() {
            return Stream.of(PType.U8, PType.U16, PType.I32).flatMap(codes -> Stream.of(
                    Arguments.of(named("eq", new Predicate.Eq("café")), codes, List.of(0L, 5L)),
                    Arguments.of(named("neq", new Predicate.Neq("café")), codes, List.of(1L, 2L, 4L)),
                    Arguments.of(named("lt in UTF-8 order", new Predicate.Lt("café")), codes, List.of(1L, 2L, 4L)),
                    Arguments.of(named("between", new Predicate.Between("AAPL", "MSFT")), codes, List.of(1L, 2L, 4L)),
                    Arguments.of(named("no entry matches", new Predicate.Eq("TSLA")), codes, List.of()),
                    Arguments.of(named("is null", new Predicate.IsNull()), codes, List.of(3L)),
                    Arguments.of(named("is null or eq", new Predicate.Or(new Predicate.IsNull(),
                            new Predicate.Eq("AAPL"))), codes, List.of(1L, 3L, 4L))));
        }

        /// u8 and u16 codes hand their bitmap to the mask with no walk; i32 codes and the null
        /// tests go through the default walk: both must keep the rows the row test selects.
        @ParameterizedTest(name = "{0} over {1} codes")
        @MethodSource("predicates")
        void toMask_keepsTheRowsTheMatcherSelects(Predicate predicate, PType codesPType, List<Long> expected) {
            // Given: the row numbers as a column, so the selected column reads back the kept rows
            Chunk chunk = mock(Chunk.class);
            given(chunk.<Array>column(SYM)).willReturn(nullableDict(codesPType));
            given(chunk.rowCount()).willReturn(6L);
            RowSelection sut = Compute.matcher(chunk, new ScanFilter.Column(SYM, predicate));
            MemorySegment rowNumbers = ARENA.allocate(6 * 8L, 8);
            for (int i = 0; i < 6; i++) {
                rowNumbers.setAtIndex(ValueLayout.JAVA_LONG, i, i);
            }
            Array column = new MaterializedLongArray(DType.I64, 6, rowNumbers);

            // When
            Mask result = sut.toMask(6);

            // Then
            LongArray kept = (LongArray) SelectedArrays.select(column, result, ARENA);
            assertThat(result.trueCount()).isEqualTo(expected.size());
            assertThat(LongStream.range(0, kept.length()).map(kept::getLong).boxed().toList()).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} over {1} codes")
        @MethodSource("predicates")
        void selectsTheRowsMatchesSelects(Predicate predicate, PType codesPType, List<Long> expected) {
            // Given
            Chunk chunk = mock(Chunk.class);
            given(chunk.<Array>column(SYM)).willReturn(nullableDict(codesPType));
            ScanFilter filter = new ScanFilter.Column(SYM, predicate);

            // When
            LongPredicate sut = Compute.matcher(chunk, filter);
            List<Long> result = LongStream.range(0, 6).filter(sut).boxed().toList();

            // Then
            assertThat(result).isEqualTo(expected)
                    .isEqualTo(LongStream.range(0, 6).filter(i -> Compute.matches(chunk, filter, i)).boxed().toList());
        }

        /// A dictionary spanning many scan chunks reaches the filter as a slice (Rust's global dict
        /// layout, e.g. TPC-H `c_mktsegment`). The slice's rows must read the codes at its offset.
        @ParameterizedTest
        @EnumSource(value = PType.class, names = {"U8", "U16", "I32"})
        void slicedDictionary_testsCodesAtTheSliceOffset(PType codesPType) {
            // Given — rows 2..4 of the dictionary column: "b", "a", "b"
            VarBinArray dict = ComputeArrays.utf8DictArray(ARENA, new String[]{"a", "b"}, new int[]{0, 0, 1, 0, 1},
                    codesPType);
            Chunk chunk = mock(Chunk.class);
            given(chunk.<Array>column(SYM)).willReturn(new VarBinSlicedArray(DType.UTF8, 3, dict, 2));
            ScanFilter filter = ScanFilter.eq(SYM, "b");

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
            LongPredicate sut = Compute.matcher(chunk, ScanFilter.eq(SYM, "b"));

            // Then
            assertThat(LongStream.range(0, 2).filter(sut).boxed().toList()).containsExactly(1L);
        }

        /// A code past the dictionary is malformed input: it must fail as [VortexException], never as
        /// an `ArrayIndexOutOfBoundsException` from the per-entry table (ADR 0003), and only on the
        /// row that carries it, as the bitmap of u8/u16 codes gives way to the per-row reads.
        @ParameterizedTest
        @EnumSource(value = PType.class, names = {"U8", "U16", "I32"})
        void codeOutsideDictionary_throwsVortexException(PType codesPType) {
            // Given — 2 entries, row 1 carries code 7
            Chunk chunk = mock(Chunk.class);
            given(chunk.<Array>column(SYM)).willReturn(ComputeArrays.utf8DictArray(ARENA,
                    new String[]{"a", "b"}, new int[]{0, 7, 1}, codesPType));
            LongPredicate sut = Compute.matcher(chunk, ScanFilter.eq(SYM, "b"));

            // When / Then
            assertThat(sut.test(2)).isTrue();
            assertThatThrownBy(() -> sut.test(1)).isInstanceOf(VortexException.class);
        }

        /// With no entry matching, the codes are only range-checked by their maximum, which must still
        /// hand a code past the dictionary to the per-row path that reports it.
        @ParameterizedTest
        @EnumSource(value = PType.class, names = {"U8", "U16"})
        void codeOutsideDictionary_noEntryMatching_throwsVortexException(PType codesPType) {
            // Given — 2 entries, row 1 carries code 7, and no entry is "z"
            Chunk chunk = mock(Chunk.class);
            given(chunk.<Array>column(SYM)).willReturn(ComputeArrays.utf8DictArray(ARENA,
                    new String[]{"a", "b"}, new int[]{0, 7, 1}, codesPType));
            LongPredicate sut = Compute.matcher(chunk, ScanFilter.eq(SYM, "z"));

            // When / Then
            assertThat(sut.test(2)).isFalse();
            assertThatThrownBy(() -> sut.test(1)).isInstanceOf(VortexException.class);
        }

        /// One matcher per scan evaluates a dictionary its chunks share once, and must notice when a
        /// later chunk brings a dictionary of its own.
        @Test
        void scanMatcher_reusesASharedDictionaryAndNoticesANewOne() {
            // Given — two slices of one dictionary, then a chunk with a different pool where code 0 is "b"
            VarBinArray shared = ComputeArrays.utf8DictArray(ARENA, new String[]{"a", "b"},
                    new int[]{1, 0, 0, 1}, PType.U16);
            Chunk first = mock(Chunk.class);
            given(first.<Array>column(SYM)).willReturn(new VarBinSlicedArray(DType.UTF8, 2, shared, 0));
            Chunk second = mock(Chunk.class);
            given(second.<Array>column(SYM)).willReturn(new VarBinSlicedArray(DType.UTF8, 2, shared, 2));
            Chunk third = mock(Chunk.class);
            given(third.<Array>column(SYM)).willReturn(ComputeArrays.utf8DictArray(ARENA, new String[]{"b", "a"},
                    new int[]{1, 0}, PType.U16));
            Function<Chunk, RowSelection> sut = Compute.matcher(ScanFilter.eq(SYM, "b"));

            // When
            List<List<Long>> result = Stream.of(first, second, third)
                    .map(chunk -> LongStream.range(0, 2).filter(sut.apply(chunk)).boxed().toList())
                    .toList();

            // Then
            assertThat(result).containsExactly(List.of(0L), List.of(1L), List.of(1L));
        }

        /// The memo hands back the very results it computed while the dictionary is the same, so a
        /// shared dictionary's values are evaluated once, and recomputes for another dictionary.
        @Test
        void dictMemo_evaluatesADictionaryOncePerPredicate() {
            // Given
            VarBinDictArray shared = (VarBinDictArray) ComputeArrays.utf8DictArray(ARENA, new String[]{"a", "b"},
                    new int[]{1, 0}, PType.U8);
            VarBinDictArray other = (VarBinDictArray) ComputeArrays.utf8DictArray(ARENA, new String[]{"b", "a"},
                    new int[]{1, 0}, PType.U8);
            Predicate predicate = new Predicate.Eq("b");
            FusedFilterAggregate.DictMemo sut = new FusedFilterAggregate.DictMemo();
            FusedFilterAggregate.DictMemo.Entry first = sut.entry(shared, predicate);

            // When
            FusedFilterAggregate.DictMemo.Entry again = sut.entry(shared, predicate);
            FusedFilterAggregate.DictMemo.Entry result = sut.entry(other, predicate);

            // Then
            assertThat(again).isSameAs(first);
            assertThat(result.matches()).containsExactly(true, false);
            assertThat(first.matches()).containsExactly(false, true);
        }

        /// A u8 code addresses 256 entries, so a longer dictionary has entries no code can reach; the
        /// code table must cover exactly the reachable ones and still select by code.
        @Test
        void u8CodesOverADictionaryLongerThan256_selectByCode() {
            // Given — 300 entries, 300 rows coding entry r % 256
            String[] pool = new String[300];
            int[] codes = new int[300];
            for (int i = 0; i < pool.length; i++) {
                pool[i] = "v" + i;
                codes[i] = i % 256;
            }
            Chunk chunk = mock(Chunk.class);
            given(chunk.<Array>column(SYM)).willReturn(ComputeArrays.utf8DictArray(ARENA, pool, codes, PType.U8));
            LongPredicate sut = Compute.matcher(chunk, ScanFilter.eq(SYM, "v7"));

            // When
            List<Long> result = LongStream.range(0, 300).filter(sut).boxed().toList();

            // Then — rows 7 and 263 code entry 7; entry 299, past what a u8 code reaches, matches no row
            assertThat(result).containsExactly(7L, 263L);
            assertThat(LongStream.range(0, 300).filter(Compute.matcher(chunk, ScanFilter.eq(SYM, "v299"))).count())
                    .isZero();
        }

        @Test
        void rejectsNullArguments() {
            // Given
            Chunk chunk = mock(Chunk.class);

            // When / Then
            assertThatNullPointerException().isThrownBy(() -> Compute.matcher(null, ScanFilter.eq(SYM, "b")));
            assertThatNullPointerException().isThrownBy(() -> Compute.matcher(chunk, null));
            assertThatNullPointerException().isThrownBy(() -> Compute.matcher((ScanFilter) null));
            assertThatNullPointerException().isThrownBy(() -> Compute.matcher(ScanFilter.eq(SYM, "b")).apply(null));
        }

        /// [RowSelection#nextSelected(long)] jumps between selected rows, a 64-row word at a time over
        /// a bitmap; walking it must visit exactly the rows the row test accepts, in order.
        @Nested
        class NextSelected {

            private static final ColumnName OTHER = ColumnName.of("other");
            private static final ColumnName N = ColumnName.of("n");
            private static final int ROWS = 200;

            /// 200 rows over three 64-row words and a partial fourth: "b" sits on rows 3, 70, 199 and
            /// on every row of 130..135, so the second word ends empty and the third starts empty;
            /// `other` is "x" on even rows, `n` is the row number.
            private static Chunk chunk(PType codesPType) {
                int[] sym = new int[ROWS];
                int[] other = new int[ROWS];
                long[] n = new long[ROWS];
                for (int r = 0; r < ROWS; r++) {
                    boolean b = r == 3 || r == 70 || r == 199 || r >= 130 && r <= 135;
                    sym[r] = b ? 1 : 0;
                    other[r] = r % 2;
                    n[r] = r;
                }
                Chunk chunk = mock(Chunk.class);
                given(chunk.rowCount()).willReturn((long) ROWS);
                given(chunk.<Array>column(SYM)).willReturn(ComputeArrays.utf8DictArray(ARENA, new String[]{"a", "b"},
                        sym, codesPType));
                given(chunk.<Array>column(OTHER)).willReturn(ComputeArrays.utf8DictArray(ARENA, new String[]{"x", "y"},
                        other, codesPType));
                given(chunk.<Array>column(N)).willReturn(ComputeArrays.longArray(ARENA, n));
                return chunk;
            }

            static Stream<Arguments> filters() {
                return Stream.of(PType.U8, PType.U16, PType.I32).flatMap(codes -> Stream.of(
                        Arguments.of(named("one bitmap leaf", ScanFilter.eq(SYM, "b")), codes),
                        Arguments.of(named("no row", ScanFilter.eq(SYM, "z")), codes),
                        // two bitmaps ANDed into one: "b" on even rows only
                        Arguments.of(named("two bitmap leaves", ScanFilter.and(ScanFilter.eq(SYM, "b"),
                                ScanFilter.eq(OTHER, "x"))), codes),
                        // a numeric leaf tested only on the bitmap's rows
                        Arguments.of(named("bitmap and residual", ScanFilter.and(ScanFilter.eq(SYM, "b"),
                                ScanFilter.gt(N, 100L))), codes),
                        Arguments.of(named("no bitmap", ScanFilter.gt(N, 190L)), codes)));
            }

            @ParameterizedTest(name = "{0} over {1} codes")
            @MethodSource("filters")
            void walk_visitsExactlyTheRowsTheTestAccepts(ScanFilter filter, PType codesPType) {
                // Given
                Chunk chunk = chunk(codesPType);
                RowSelection sut = Compute.matcher(chunk, filter);
                List<Long> expected = LongStream.range(0, ROWS).filter(i -> Compute.matches(chunk, filter, i))
                        .boxed().toList();

                // When
                List<Long> result = new java.util.ArrayList<>();
                for (long row = sut.nextSelected(0); row >= 0; row = sut.nextSelected(row + 1)) {
                    result.add(row);
                }

                // Then
                assertThat(result).isEqualTo(expected)
                        .isEqualTo(LongStream.range(0, ROWS).filter(sut).boxed().toList());
            }

            @Test
            void fromPastTheLastRow_selectsNothing() {
                // Given
                RowSelection sut = Compute.matcher(chunk(PType.U16), ScanFilter.eq(SYM, "b"));

                // When
                long result = sut.nextSelected(ROWS);

                // Then
                assertThat(result).isEqualTo(-1);
            }
        }
    }

    /// [Compute#groupedAggregate(Chunk, ColumnName, ScanFilter, ColumnName)] folds per dictionary
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
            Map<Object, FilteredAggregate> result = Compute.groupedAggregate(chunk, G, ScanFilter.gt(V, 4L), V)
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
            Map<Object, FilteredAggregate> result = Compute.groupedAggregate(chunk(group, v), G, ScanFilter.gt(V, 1L), V)
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

        /// A decimal of precision up to 18 folds as unscaled longs and comes back exact, at its scale.
        @Test
        void decimalAggregate_foldsExactlyAtTheColumnScale() {
            // Given — groups a, b, a; v = 1.25, -0.50, 2.00 as DECIMAL(10, 2)
            Array group = ComputeArrays.utf8DictArray(ARENA, new String[]{"a", "b"}, new int[]{0, 1, 0});
            Array v = new LazyDecimalBytePartsArray(new DType.Decimal((byte) 10, (byte) 2, false), 3,
                    ComputeArrays.longArray(ARENA, 125, -50, 200));

            // When
            Map<Object, FilteredAggregate> result = Compute.groupedAggregate(chunk(group, v), G, null, V).orElseThrow();

            // Then
            assertThat(result.get("a")).isEqualTo(new FilteredAggregate(2, 2, new BigDecimal("3.25"),
                    new BigDecimal("1.25"), new BigDecimal("2.00")));
            assertThat(result.get("b").sum()).isEqualTo(new BigDecimal("-0.50"));
        }

        /// A decimal sum must be exact: one past a long declines rather than wrap.
        @Test
        void decimalSumPastALong_isEmpty() {
            // Given
            Array group = ComputeArrays.utf8DictArray(ARENA, new String[]{"a"}, new int[]{0, 0});
            Array v = new LazyDecimalBytePartsArray(new DType.Decimal((byte) 18, (byte) 0, false), 2,
                    ComputeArrays.longArray(ARENA, Long.MAX_VALUE, 1));

            // When / Then
            assertThat(Compute.groupedAggregate(chunk(group, v), G, null, V)).isEmpty();
        }

        @Test
        void decimalPastPrecision18_isEmpty() {
            // Given
            Array group = ComputeArrays.utf8DictArray(ARENA, new String[]{"a"}, new int[]{0});
            Array v = new LazyDecimalBytePartsArray(new DType.Decimal((byte) 19, (byte) 0, false), 1,
                    ComputeArrays.longArray(ARENA, 1));

            // When / Then
            assertThat(Compute.groupedAggregate(chunk(group, v), G, null, V)).isEmpty();
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
