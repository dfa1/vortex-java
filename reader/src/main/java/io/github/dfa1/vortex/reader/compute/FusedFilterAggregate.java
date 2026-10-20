package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.VarBinDictArray;
import io.github.dfa1.vortex.reader.array.VarBinOffsetArray;
import io.github.dfa1.vortex.reader.array.VarBinSlicedArray;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.LongPredicate;

/// The fused, one-pass multi-column filter-and-aggregate kernel: it evaluates a whole [RowFilter]
/// (an n-ary `AND` of column-bound [Predicate] leaves, or a single leaf) and, over the rows it
/// selects, folds an optional aggregate column's `SUM` / `MIN` / `MAX` / non-null count — all in a
/// single scan of the chunk's rows, with no intermediate selection bitmap and no second pass.
///
/// This generalizes [FusedFilterSum] from one filter + one aggregate to many filters + one
/// aggregate, and adds the `MIN` / `MAX` / count folds the Calcite boundary aggregate push-down
/// (ADR 0013 §6 tier 2) needs. The kernel resolves every leaf's column and lowers every leaf's
/// [Predicate] once, then runs a single row loop: for each row it evaluates the leaves
/// (short-circuiting on the first that rejects the row) and, when the whole filter accepts the row and
/// the aggregate value is non-null, folds that value straight into the running totals. Nothing is
/// materialized between the filter and the reduce. The semantics are:
/// - the filter is an n-ary `AND` under three-valued logic — a row is selected only when every leaf
///   accepts it, and a null in any filter column rejects that leaf;
/// - the aggregate skips its own nulls;
/// - `SUM` covers numeric primitives only — a [Long] for an integer aggregate, a [Double] for a
///   floating one (an `f16` column without a specialized lane folds a boxing `doubleValue()` total
///   into a [Double]), the additive identity (`0`) over zero selected non-null rows — and is reported
///   as `null` for a non-numeric aggregate column (the caller treats that as an unanswerable sum,
///   mirroring where [NumericColumns#requireNumeric(DType)] would reject it);
/// - `MIN` / `MAX` use the column's dtype-aware order ([NumericColumns#widenLong(Array, boolean,
///   long)] with unsigned-aware [Long#compareUnsigned(long, long)], [Double#compare(double, double)]
///   for floats, [Compare#values(Object, Object, DType)] otherwise) and are `null` when no selected
///   row is non-null.
///
/// The aggregate fold is type-specialized into boxing-free accumulators (a long domain, a double
/// domain) chosen once outside the loop; only the box at the end allocates. An aggregate without a
/// specialized lane (a non-primitive column, or an `f16` numeric primitive) folds its `SUM` and
/// `MIN` / `MAX` through the boxing [Values#valueAt(Array, long)] / [Compare] path. Each filter leaf's
/// [Predicate] is lowered once (reusing [PrimitiveFilter#lowerLong(Predicate, long)] /
/// [PrimitiveFilter#lowerDouble(Predicate)]), with the typed accessor and validity hoisted out of the
/// row loop; a non-primitive or composite leaf falls back to the per-element predicate evaluator
/// ([PredicateEvaluator#evaluate(Array, long, Predicate)]). A filter whose `AND` holds a comparison
/// leaf over a dict-encoded column takes the [DictFilter] code-scan lane instead of the row loop:
/// that leaf's predicate is resolved against the value pool once, the raw `u8` codes are scanned
/// from their backing segments, and any remaining leaves are tested only on the code matches.
final class FusedFilterAggregate {

    private FusedFilterAggregate() {
    }

    /// Evaluates `filter` over `chunk` and folds `aggColumn` over the rows it selects, in one pass.
    ///
    /// @param chunk     the decoded chunk holding the filter and aggregate columns
    /// @param filter    the whole boundary-chunk predicate: an n-ary `AND` of column-bound leaves, or
    ///                  a single column-bound leaf
    /// @param aggColumn the column to reduce over the selected rows, or `null` to count selected rows
    ///                  only (`COUNT(*)`)
    /// @return the fold over the selected rows: the selected row count, the aggregate's non-null count
    ///         among them, and (when `aggColumn` is given) its `SUM` / `MIN` / `MAX`
    static FilteredAggregate aggregate(Chunk chunk, RowFilter filter, ColumnName aggColumn) {
        long n = chunk.rowCount();
        FilteredAggregate dictResult = tryDictLane(chunk, filter, aggColumn, n);
        if (dictResult != null) {
            return dictResult;
        }
        List<LongPredicate> leafList = new ArrayList<>();
        collectLeaves(chunk, filter, leafList, new DictMemo());
        LongPredicate[] leaves = leafList.toArray(LongPredicate[]::new);

        if (aggColumn == null) {
            long selected = 0L;
            for (long i = 0; i < n; i++) {
                if (allMatch(leaves, i)) {
                    selected++;
                }
            }
            return new FilteredAggregate(selected, 0L, null, null, null);
        }
        return foldAggregate(chunk.column(aggColumn), leaves, n);
    }

    /// Attempts the [DictFilter] code-scan lane: when the filter's flattened `AND` holds at least
    /// one comparison leaf over a dict-encoded column, that leaf DRIVES the scan — raw `u8` codes
    /// are compared instead of decoding every row — and the remaining leaves are lowered once (the
    /// same [#leafTest(Array, Predicate, DictMemo)] lowering as the row loop) and tested only on the rows the
    /// code scan matches. With ≈ 1/pool-size selectivity, the residual leaves' accessor reads run
    /// on a small fraction of the rows instead of all of them. Returns `null` — leaving the generic
    /// row loop to run — when no leaf qualifies as a driver, or for an aggregate without a
    /// specialized long/double lane.
    ///
    /// @param chunk     the decoded chunk holding the filter and aggregate columns
    /// @param filter    the whole chunk predicate
    /// @param aggColumn the aggregate column name, or `null` to count selected rows only
    /// @param n         the chunk's row count
    /// @return the fold, or `null` when the shape does not qualify for the dict lane
    private static FilteredAggregate tryDictLane(Chunk chunk, RowFilter filter, ColumnName aggColumn, long n) {
        List<RowFilter.Column> leaves = new ArrayList<>();
        flattenLeaves(filter, leaves);
        int driver = -1;
        Array driverData = null;
        for (int i = 0; i < leaves.size(); i++) {
            RowFilter.Column leaf = leaves.get(i);
            if (isComparisonLeaf(leaf.predicate()) && PrimitiveFilter.predicateOk(leaf.predicate())) {
                Array data = NumericColumns.unwrap(chunk.column(leaf.column()));
                if (DictFilter.isScannable(data)) {
                    driver = i;
                    driverData = data;
                    break;
                }
            }
        }
        if (driver < 0) {
            return null;
        }
        RowFilter.Column driverLeaf = leaves.get(driver);
        BoolArray driverValidity = NumericColumns.validityOf(chunk.column(driverLeaf.column()));
        LongPredicate residual = residualOf(chunk, leaves, driver);
        if (aggColumn == null) {
            Long selected = DictFilter.tryFilteredCount(driverData, driverValidity,
                    driverLeaf.predicate(), residual, n);
            if (selected == null) {
                return null;
            }
            return new FilteredAggregate(selected.longValue(), 0L, null, null, null);
        }
        Array agg = chunk.column(aggColumn);
        return DictFilter.tryFilteredAggregate(driverData, driverValidity, driverLeaf.predicate(), residual,
                NumericColumns.unwrap(agg), NumericColumns.validityOf(agg), n);
    }

    /// Lowers every non-driver leaf into one hoisted residual test, evaluated only on the rows the
    /// driving code scan matches.
    ///
    /// @param chunk  the chunk supplying each leaf's column
    /// @param leaves the flattened column-bound leaves
    /// @param driver the index of the leaf driving the code scan
    /// @return the conjunction of the non-driver leaves, or `null` when the driver is the only leaf
    private static LongPredicate residualOf(Chunk chunk, List<RowFilter.Column> leaves, int driver) {
        List<LongPredicate> lowered = new ArrayList<>(leaves.size() - 1);
        for (int i = 0; i < leaves.size(); i++) {
            if (i != driver) {
                RowFilter.Column leaf = leaves.get(i);
                lowered.add(leafTest(chunk.column(leaf.column()), leaf.predicate(), new DictMemo()));
            }
        }
        if (lowered.isEmpty()) {
            return null;
        }
        if (lowered.size() == 1) {
            return lowered.getFirst();
        }
        LongPredicate[] tests = lowered.toArray(LongPredicate[]::new);
        return i -> allMatch(tests, i);
    }

    /// Flattens the filter tree into its column-bound leaves, in filter order.
    ///
    /// @param filter the (sub-)filter to walk
    /// @param out    the collected leaves
    private static void flattenLeaves(RowFilter filter, List<RowFilter.Column> out) {
        switch (filter) {
            case RowFilter.And(var parts) -> {
                for (RowFilter part : parts) {
                    flattenLeaves(part, out);
                }
            }
            case RowFilter.Column column -> out.add(column);
        }
    }

    /// Dispatches the fold on the aggregate's value domain — a boxing-free long or double lane, else the
    /// generic boxing lane — so each specialized loop's inner body is a bare typed read plus a primitive
    /// accumulate, the hot-loop rule's branch-split idiom (hoist the domain choice once, gate one
    /// specialized loop per domain rather than branching per row).
    ///
    /// @param agg    the aggregate column (possibly a [io.github.dfa1.vortex.reader.array.MaskedArray])
    /// @param leaves the lowered filter leaves, all of which must accept a row for it to be selected
    /// @param n      the chunk's row count
    /// @return the fold over the selected non-null aggregate rows
    private static FilteredAggregate foldAggregate(Array agg, LongPredicate[] leaves, long n) {
        Array aggData = NumericColumns.unwrap(agg);
        BoolArray aggValidity = NumericColumns.validityOf(agg);
        if (NumericColumns.isLongDomain(aggData)) {
            return foldLongDomain(aggData, aggValidity, aggData.dtype().isUnsigned(), leaves, n);
        }
        if (aggData instanceof DoubleArray || aggData instanceof FloatArray) {
            return foldDoubleDomain(aggData, aggValidity, aggData instanceof FloatArray, leaves, n);
        }
        // A numeric primitive without a specialized long/double lane (an f16 column) still has an
        // answerable SUM: it folds a boxing doubleValue() total into a Double. A non-numeric column
        // (Utf8/Binary/Bool/…) has no sum and reports null.
        DType aggDtype = agg.dtype();
        return foldGeneric(agg, aggValidity, aggDtype, aggDtype instanceof DType.Primitive, leaves, n);
    }

    /// The long-domain lane: reads each selected non-null row through [NumericColumns#widenLong(Array,
    /// boolean, long)] and folds a wrapping [Long] `SUM` plus an unsigned-aware `MIN` / `MAX`.
    ///
    /// @param aggData  the unwrapped long-domain aggregate array
    /// @param validity the aggregate validity bitmap, or `null` when every row is valid
    /// @param unsigned whether the column is unsigned (its order and widening zero-extend)
    /// @param leaves   the lowered filter leaves, all of which must accept a row for it to be selected
    /// @param n        the chunk's row count
    /// @return the fold: selected count, non-null count, the [Long] sum, and the [Long] `MIN` / `MAX`
    ///         (`null` when no selected row is non-null)
    private static FilteredAggregate foldLongDomain(Array aggData, BoolArray validity, boolean unsigned,
                                                    LongPredicate[] leaves, long n) {
        long selected = 0L;
        long nonNull = 0L;
        long sum = 0L;
        boolean found = false;
        long min = 0L;
        long max = 0L;
        for (long i = 0; i < n; i++) {
            if (!allMatch(leaves, i)) {
                continue;
            }
            selected++;
            if (validity != null && !validity.getBoolean(i)) {
                continue;
            }
            nonNull++;
            long v = NumericColumns.widenLong(aggData, unsigned, i);
            sum += v;
            if (!found) {
                min = v;
                max = v;
                found = true;
            } else {
                if (lessLong(v, min, unsigned)) {
                    min = v;
                }
                if (lessLong(max, v, unsigned)) {
                    max = v;
                }
            }
        }
        return new FilteredAggregate(selected, nonNull, Long.valueOf(sum),
                found ? Long.valueOf(min) : null, found ? Long.valueOf(max) : null);
    }

    /// The double-domain lane: reads each selected non-null row through the hoisted `f32`/`f64` accessor
    /// and folds a [Double] `SUM` plus a [Double#compare(double, double)]-ordered `MIN` / `MAX`.
    ///
    /// @param aggData  the unwrapped double-domain aggregate array
    /// @param validity the aggregate validity bitmap, or `null` when every row is valid
    /// @param isFloat  whether the column is an `f32` ([FloatArray]) rather than an `f64`
    /// @param leaves   the lowered filter leaves, all of which must accept a row for it to be selected
    /// @param n        the chunk's row count
    /// @return the fold: selected count, non-null count, the [Double] sum, and the boxed `MIN` / `MAX`
    ///         ([Float] for an `f32` column, `null` when no selected row is non-null)
    private static FilteredAggregate foldDoubleDomain(Array aggData, BoolArray validity, boolean isFloat,
                                                      LongPredicate[] leaves, long n) {
        long selected = 0L;
        long nonNull = 0L;
        double sum = 0.0;
        boolean found = false;
        double min = 0.0;
        double max = 0.0;
        for (long i = 0; i < n; i++) {
            if (!allMatch(leaves, i)) {
                continue;
            }
            selected++;
            if (validity != null && !validity.getBoolean(i)) {
                continue;
            }
            nonNull++;
            double v = isFloat ? ((FloatArray) aggData).getFloat(i) : ((DoubleArray) aggData).getDouble(i);
            sum += v;
            if (!found) {
                min = v;
                max = v;
                found = true;
            } else {
                if (Double.compare(v, min) < 0) {
                    min = v;
                }
                if (Double.compare(v, max) > 0) {
                    max = v;
                }
            }
        }
        return new FilteredAggregate(selected, nonNull, Double.valueOf(sum),
                boxDouble(found, min, isFloat), boxDouble(found, max, isFloat));
    }

    /// The generic boxing lane for an aggregate with no specialized long/double accessor: an `f16`
    /// numeric primitive (whose `SUM` folds through a boxing `doubleValue()`) or a non-numeric column
    /// (whose `SUM` is unanswerable and reported `null`). `MIN` / `MAX` order through
    /// [Compare#values(Object, Object, DType)].
    ///
    /// @param agg              the aggregate column, read through the boxing [Values#valueAt(Array, long)]
    /// @param validity         the aggregate validity bitmap, or `null` when every row is valid
    /// @param aggDtype         the aggregate column's dtype, driving the `MIN` / `MAX` order
    /// @param numericPrimitive whether the column is a numeric primitive with an answerable `SUM`
    /// @param leaves           the lowered filter leaves, all of which must accept a row for selection
    /// @param n                the chunk's row count
    /// @return the fold: selected count, non-null count, the [Double] sum (or `null` when non-numeric),
    ///         and the boxed `MIN` / `MAX` (`null` when no selected row is non-null)
    private static FilteredAggregate foldGeneric(Array agg, BoolArray validity, DType aggDtype,
                                                 boolean numericPrimitive, LongPredicate[] leaves, long n) {
        long selected = 0L;
        long nonNull = 0L;
        double sum = 0.0;
        Object min = null;
        Object max = null;
        for (long i = 0; i < n; i++) {
            if (!allMatch(leaves, i)) {
                continue;
            }
            selected++;
            if (validity != null && !validity.getBoolean(i)) {
                continue;
            }
            nonNull++;
            Object v = Values.valueAt(agg, i);
            if (numericPrimitive) {
                sum += ((Number) v).doubleValue();
            }
            if (min == null) {
                min = v;
                max = v;
            } else {
                if (Compare.values(v, min, aggDtype) < 0) {
                    min = v;
                }
                if (Compare.values(v, max, aggDtype) > 0) {
                    max = v;
                }
            }
        }
        // The additive identity (0.0) over zero non-null rows for a numeric primitive; null sum otherwise.
        return new FilteredAggregate(selected, nonNull, numericPrimitive ? Double.valueOf(sum) : null, min, max);
    }

    /// Reports whether `a` orders before `b` in the long domain, unsigned-aware so a `u64` value past
    /// the high bit compares as the larger magnitude.
    ///
    /// @param a        the left value (raw bits for the unsigned case)
    /// @param b        the right value
    /// @param unsigned whether the column is unsigned
    /// @return `true` if `a` is strictly less than `b` under the column's order
    static boolean lessLong(long a, long b, boolean unsigned) {
        return unsigned ? Long.compareUnsigned(a, b) < 0 : a < b;
    }

    /// Boxes a double-domain extreme to the column's own accessor type — a [Float] for an `f32`
    /// column, a [Double] for `f64` — matching the column's element box, or `null` when no selected
    /// row was non-null.
    ///
    /// @param found    whether any selected row contributed a value
    /// @param value    the accumulated extreme
    /// @param isFloat  whether the column is an `f32` ([FloatArray])
    /// @return the boxed extreme, or `null` when `found` is `false`
    static Object boxDouble(boolean found, double value, boolean isFloat) {
        if (!found) {
            return null;
        }
        // Kept as separate returns, not a `?:`: a Float/Double conditional triggers binary numeric
        // promotion (JLS 15.25), unboxing both branches to double and boxing an f32 column's extreme
        // back to Double — silently violating the Float contract above.
        if (isFloat) {
            // (float) round-trips a value that already came from a float accessor without loss.
            return Float.valueOf((float) value);
        }
        return Double.valueOf(value);
    }

    /// Reports whether every leaf accepts row `i` (the n-ary `AND`), short-circuiting on the first
    /// leaf that rejects it.
    ///
    /// @param leaves the lowered filter leaves
    /// @param i      the zero-based row
    /// @return `true` if every leaf accepts the row
    private static boolean allMatch(LongPredicate[] leaves, long i) {
        for (LongPredicate leaf : leaves) {
            if (!leaf.test(i)) {
                return false;
            }
        }
        return true;
    }

    /// Walks the filter tree, lowering each [RowFilter.Column] leaf into a hoisted [LongPredicate] over
    /// its decoded column and flattening the conjuncts of an n-ary `AND` into `out`.
    ///
    /// @param chunk  the chunk supplying each leaf's column
    /// @param filter the (sub-)filter to walk
    /// @param out    the collected per-leaf row predicates
    private static void collectLeaves(Chunk chunk, RowFilter filter, List<LongPredicate> out, DictMemo memo) {
        switch (filter) {
            case RowFilter.And(var parts) -> {
                for (RowFilter part : parts) {
                    collectLeaves(chunk, part, out, memo);
                }
            }
            case RowFilter.Column(var column, var predicate) ->
                    out.add(leafTest(chunk.column(column), predicate, memo));
        }
    }

    /// Lowers one column-bound [Predicate] into a hoisted [LongPredicate]: a null test reads validity
    /// directly, a numeric comparison leaf lowers once to the [PrimitiveFilter] range / operator, and
    /// any other shape falls back to the per-element predicate evaluator
    /// ([PredicateEvaluator#evaluate(Array, long, Predicate)]).
    ///
    /// @param column    the decoded filter column (possibly masked)
    /// @param predicate the column-bound predicate
    /// @return a row predicate that tests `predicate` against `column` at a given row
    private static LongPredicate leafTest(Array column, Predicate predicate, DictMemo memo) {
        Array data = NumericColumns.unwrap(column);
        BoolArray validity = NumericColumns.validityOf(column);
        if (predicate instanceof Predicate.IsNull) {
            return validity == null ? i -> false : i -> !validity.getBoolean(i);
        }
        if (predicate instanceof Predicate.IsNotNull) {
            return validity == null ? i -> true : validity::getBoolean;
        }
        if (isComparisonLeaf(predicate) && PrimitiveFilter.predicateOk(predicate)
                && data.dtype() instanceof DType.Primitive) {
            if (data instanceof DoubleArray || data instanceof FloatArray) {
                return doubleLeaf(data, validity, predicate);
            }
            if (NumericColumns.isLongDomain(data)) {
                return longLeaf(data, validity, predicate);
            }
        }
        LongPredicate generic = i -> PredicateEvaluator.evaluate(column, i, predicate);
        if (data instanceof VarBinDictArray dict) {
            return dictLeaf(dict, 0, dict.length(), validity, predicate, generic, memo);
        }
        // A dictionary spanning many scan chunks (Rust's global dict layout) is decoded once and
        // sliced per chunk, so real files mostly reach here as a slice.
        if (data instanceof VarBinSlicedArray(var _, long rows, VarBinDictArray dict, long offset)) {
            return dictLeaf(dict, offset, rows, validity, predicate, generic, memo);
        }
        return generic;
    }

    /// Builds a string dictionary leaf the way Rust's dict compare kernel does: the predicate is
    /// evaluated once per dictionary entry (#552), and the rows' codes are then mapped through those
    /// results in one pass over the codes segment, Rust's `values.take(codes)`, into a bitmap the row
    /// test reads. A dictionary the chunks share is evaluated once per `memo`, as Rust's dict reader
    /// caches its values evaluation.
    ///
    /// A null row still goes through `generic`, so a null-aware predicate (`IS NULL` under an `OR`)
    /// answers exactly as before, and so does a code outside the dictionary, which `generic` reports
    /// as malformed input. Like Rust, the lowering declines when the dictionary has more entries
    /// than there are rows to test, where testing every entry costs more than testing the rows.
    ///
    /// @param dict      the dictionary-encoded array the rows read from
    /// @param offset    the dictionary row of the first tested row (non-zero for a slice)
    /// @param rows      how many rows will be tested
    /// @param validity  the filter validity bitmap, or `null` when every row is valid
    /// @param predicate the column-bound predicate
    /// @param generic   the per-row evaluator over the original (possibly masked) column
    /// @param memo      the entry results already computed in this scan
    /// @return the lowered row predicate
    private static LongPredicate dictLeaf(VarBinDictArray dict, long offset, long rows, BoolArray validity,
                                          Predicate predicate, LongPredicate generic, DictMemo memo) {
        long size = dict.values().length();
        if (size > rows || size > Integer.MAX_VALUE) {
            return generic;
        }
        DictMemo.Entry entry = memo.entry(dict, predicate);
        boolean[] entryMatches = entry.matches();
        Optional<long[]> matched = matchBits(dict, offset, rows, entry);
        if (matched.isEmpty()) {
            return i -> {
                if (validity != null && !validity.getBoolean(i)) {
                    return generic.test(i);
                }
                long code = dict.code(i + offset);
                return code >= 0 && code < size ? entryMatches[(int) code] : generic.test(i);
            };
        }
        long[] bits = matched.get();
        if (validity == null) {
            return new BitmapLeaf(bits);
        }
        // ponytail: a nullable column tests its null rows through `generic`, so its rows are walked
        // one by one; folding the validity into the bitmap would need the predicate's null answer
        return i -> validity.getBoolean(i) ? (bits[(int) (i >>> 6)] & 1L << i) != 0 : generic.test(i);
    }

    /// A leaf whose selected rows are a bitmap, bit `i` for row `i`, which [#rowTest(Chunk, RowFilter,
    /// DictMemo)] walks a word at a time.
    ///
    /// @param bits the bitmap
    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record BitmapLeaf(long[] bits) implements LongPredicate {
        @Override
        public boolean test(long row) {
            return (bits[(int) (row >>> 6)] & 1L << row) != 0;
        }
    }

    /// Marks rows `[offset, offset + rows)` of `dict` whose entry matches, reading its `u8` or `u16`
    /// codes straight from their segment, in the cheapest of three shapes chosen once:
    /// - no entry matches (`= 'x'` with `x` absent): nothing to mark, only the codes' range is
    ///   checked, by the vectorized maximum;
    /// - one entry matches (`= 'x'`): a compare against its code, taken on the few matching rows;
    /// - several match: a lookup through [DictMemo.Entry#codeTable()], free of branches.
    ///
    /// @return the bitmap, bit `i` for row `offset + i`, or empty for wider codes, a codes buffer too
    ///         short or a code outside the dictionary, which the per-row path then reports
    private static Optional<long[]> matchBits(VarBinDictArray dict, long offset, long rows, DictMemo.Entry entry) {
        PType ptype = dict.dictCodesPType();
        MemorySegment codes = dict.dictCodesSegs();
        if (entry.codeTable().length == 0 || rows > Integer.MAX_VALUE
                || codes.byteSize() / ptype.byteSize() < offset + rows) {
            return Optional.empty();
        }
        int n = (int) rows;
        long[] bits = new long[(n + 63) >>> 6];
        if (n == 0) {
            return Optional.of(bits);
        }
        int size = entry.matches().length;
        int only = entry.onlyMatch();
        boolean inDictionary;
        if (only == DictMemo.NO_MATCH) {
            long width = ptype.byteSize();
            inDictionary = SimdOperationsSupport.preferred()
                    .maxUnsigned(codes.asSlice(offset * width, n * width), n, ptype) < size;
        } else if (only >= 0) {
            inDictionary = markCode(codes, ptype, offset, n, only, bits) < size;
        } else {
            inDictionary = (markTable(codes, ptype, offset, n, entry.codeTable(), bits) & 2) == 0;
        }
        return inDictionary ? Optional.of(bits) : Optional.empty();
    }

    /// Sets the bit of every row among `n` from `offset` whose code's slot in `table` is `1`.
    ///
    /// @return every slot read, ORed: bit `2` is set when a code is past the dictionary
    private static int markTable(MemorySegment codes, PType ptype, long offset, int n, byte[] table, long[] bits) {
        int seen = 0;
        if (ptype == PType.U8) {
            for (int i = 0; i < n; i++) {
                int slot = table[Byte.toUnsignedInt(codes.get(ValueLayout.JAVA_BYTE, offset + i))];
                bits[i >>> 6] |= (long) (slot & 1) << i;
                seen |= slot;
            }
        } else {
            for (int i = 0; i < n; i++) {
                int slot = table[Short.toUnsignedInt(codes.getAtIndex(VortexFormat.LE_SHORT, offset + i))];
                bits[i >>> 6] |= (long) (slot & 1) << i;
                seen |= slot;
            }
        }
        return seen;
    }

    /// Sets the bit of every row among `n` from `offset` whose code is `only`.
    ///
    /// @return the largest code read, for the caller's range check
    private static int markCode(MemorySegment codes, PType ptype, long offset, int n, int only, long[] bits) {
        int max = 0;
        // the compare is taken on the matching rows only, as rare as the entry is, so it predicts well
        if (ptype == PType.U8) {
            for (int i = 0; i < n; i++) {
                int code = Byte.toUnsignedInt(codes.get(ValueLayout.JAVA_BYTE, offset + i));
                max = Math.max(max, code);
                if (code == only) {
                    bits[i >>> 6] |= 1L << i;
                }
            }
        } else {
            for (int i = 0; i < n; i++) {
                int code = Short.toUnsignedInt(codes.getAtIndex(VortexFormat.LE_SHORT, offset + i));
                max = Math.max(max, code);
                if (code == only) {
                    bits[i >>> 6] |= 1L << i;
                }
            }
        }
        return max;
    }

    /// The per-entry results of each dictionary leaf, kept across the chunks of one scan: chunks
    /// sliced from one shared dictionary then evaluate its entries once. Not for concurrent use.
    static final class DictMemo {
        /// [Entry#onlyMatch()] when no entry matches.
        static final int NO_MATCH = -1;
        /// [Entry#onlyMatch()] when several entries match.
        static final int SEVERAL = -2;

        private final IdentityHashMap<Predicate, Entry> byLeaf = new IdentityHashMap<>();

        /// One leaf's results over one dictionary.
        ///
        /// @param dict      the dictionary they were computed for
        /// @param matches   whether each entry satisfies the leaf's predicate
        /// @param onlyMatch the one matching entry, or [#NO_MATCH] or [#SEVERAL]
        /// @param codeTable for `u8` or `u16` codes, one slot per possible code: `1` for a matching
        ///                  entry, `0` for another, `2` for a code past the dictionary; else empty
        @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
        record Entry(VarBinDictArray dict, boolean[] matches, int onlyMatch, byte[] codeTable) {
        }

        /// The results of `predicate` over `dict`, computed on the first call for this dictionary.
        Entry entry(VarBinDictArray dict, Predicate predicate) {
            Entry entry = byLeaf.get(predicate);
            if (entry != null && entry.dict() == dict) {
                return entry;
            }
            VarBinOffsetArray values = dict.values();
            boolean[] matches = new boolean[(int) values.length()];
            int only = NO_MATCH;
            for (int c = 0; c < matches.length; c++) {
                matches[c] = PredicateEvaluator.evaluate(values, c, predicate);
                if (matches[c]) {
                    only = only == NO_MATCH ? c : SEVERAL;
                }
            }
            entry = new Entry(dict, matches, only, codeTable(dict.dictCodesPType(), matches));
            byLeaf.put(predicate, entry);
            return entry;
        }

        private static byte[] codeTable(PType codes, boolean[] matches) {
            if (codes != PType.U8 && codes != PType.U16) {
                return new byte[0];
            }
            byte[] table = new byte[codes == PType.U8 ? 1 << 8 : 1 << 16];
            // a dictionary longer than the codes can address has entries no code reaches
            int reachable = Math.min(matches.length, table.length);
            java.util.Arrays.fill(table, reachable, table.length, (byte) 2);
            for (int c = 0; c < reachable; c++) {
                table[c] = matches[c] ? (byte) 1 : 0;
            }
            return table;
        }
    }

    /// Lowers a whole [RowFilter] over `chunk` into one row predicate: each leaf is lowered once by
    /// [#leafTest(Array, Predicate, DictMemo)], and a row matches when every leaf accepts it.
    ///
    /// @param chunk  the decoded chunk holding every column `filter` references
    /// @param filter the whole predicate
    /// @param memo   the dictionary entry results of earlier chunks of the same scan
    /// @return a row predicate valid for `chunk`'s rows
    static RowSelection rowTest(Chunk chunk, RowFilter filter, DictMemo memo) {
        List<LongPredicate> leafList = new ArrayList<>();
        collectLeaves(chunk, filter, leafList, memo);
        // the bitmap leaves are ANDed into one, which then drives the walk; the other leaves are
        // tested only on the rows it selects
        long[] bits = null;
        List<LongPredicate> rest = new ArrayList<>();
        for (LongPredicate leaf : leafList) {
            if (leaf instanceof BitmapLeaf(long[] leafBits)) {
                if (bits == null) {
                    bits = leafBits;
                } else {
                    for (int w = 0; w < bits.length; w++) {
                        bits[w] &= leafBits[w];
                    }
                }
            } else {
                rest.add(leaf);
            }
        }
        LongPredicate residual = switch (rest.size()) {
            case 0 -> null;
            case 1 -> rest.getFirst();
            default -> {
                LongPredicate[] leaves = rest.toArray(LongPredicate[]::new);
                yield i -> allMatch(leaves, i);
            }
        };
        long rows = chunk.rowCount();
        return bits == null ? new TestedRows(rows, residual) : new BitmapRows(bits, rows, residual);
    }

    /// A selection that tests every row.
    ///
    /// @param rows the chunk's row count
    /// @param test the row test
    private record TestedRows(long rows, LongPredicate test) implements RowSelection {
        @Override
        public boolean test(long row) {
            return test.test(row);
        }

        @Override
        public long nextSelected(long from) {
            for (long row = from; row < rows; row++) {
                if (test.test(row)) {
                    return row;
                }
            }
            return -1;
        }
    }

    /// A selection held as a bitmap, walked a word of 64 rows at a time, with the leaves the bitmap
    /// does not cover tested on its rows only.
    ///
    /// @param bits     the bitmap, bit `i` for row `i`
    /// @param rows     the chunk's row count
    /// @param residual the other leaves' test, or `null` when the bitmap is the whole filter
    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record BitmapRows(long[] bits, long rows, LongPredicate residual) implements RowSelection {
        @Override
        public boolean test(long row) {
            return (bits[(int) (row >>> 6)] & 1L << row) != 0 && (residual == null || residual.test(row));
        }

        @Override
        public long nextSelected(long from) {
            long row = from;
            while (row < rows) {
                int w = (int) (row >>> 6);
                long word = bits[w] & -1L << row;
                while (word == 0) {
                    if (++w == bits.length) {
                        return -1;
                    }
                    word = bits[w];
                }
                row = ((long) w << 6) + Long.numberOfTrailingZeros(word);
                if (residual == null || residual.test(row)) {
                    return row;
                }
                row++;
            }
            return -1;
        }
    }

    /// Builds a hoisted long-domain comparison leaf: the predicate lowers once to an inclusive range
    /// on the order-preserving flipped longs, matching [PrimitiveFilter] exactly.
    ///
    /// @param data      the unwrapped long-domain filter array
    /// @param validity  the filter validity bitmap, or `null` when every row is valid
    /// @param predicate the comparison leaf
    /// @return the lowered row predicate
    private static LongPredicate longLeaf(Array data, BoolArray validity, Predicate predicate) {
        boolean unsigned = data.dtype().isUnsigned();
        long flip = unsigned ? Long.MIN_VALUE : 0L;
        PrimitiveFilter.LongRange range = PrimitiveFilter.lowerLong(predicate, flip);
        long lo = range.lo();
        long hi = range.hi();
        boolean negate = range.negate();
        if (validity == null) {
            return i -> {
                long v = NumericColumns.widenLong(data, unsigned, i) ^ flip;
                return (lo <= v && v <= hi) != negate;
            };
        }
        return i -> {
            if (!validity.getBoolean(i)) {
                return false;
            }
            long v = NumericColumns.widenLong(data, unsigned, i) ^ flip;
            return (lo <= v && v <= hi) != negate;
        };
    }

    /// Builds a hoisted double-domain comparison leaf: the predicate lowers once to its operator and
    /// bounds, compared with [Double#compare(double, double)] like [PrimitiveFilter].
    ///
    /// @param data      the unwrapped double-domain filter array
    /// @param validity  the filter validity bitmap, or `null` when every row is valid
    /// @param predicate the comparison leaf
    /// @return the lowered row predicate
    private static LongPredicate doubleLeaf(Array data, BoolArray validity, Predicate predicate) {
        PrimitiveFilter.DoubleBound bound = PrimitiveFilter.lowerDouble(predicate);
        PrimitiveFilter.DoubleOp op = bound.op();
        double lo = bound.lo();
        double hi = bound.hi();
        boolean isFloat = data instanceof FloatArray;
        if (validity == null) {
            return i -> PrimitiveFilter.matchDouble(readDouble(data, isFloat, i), op, lo, hi);
        }
        return i -> validity.getBoolean(i)
                && PrimitiveFilter.matchDouble(readDouble(data, isFloat, i), op, lo, hi);
    }

    /// Reads filter position `i` as a double through the hoisted typed accessor.
    ///
    /// @param data    the unwrapped double-domain filter array
    /// @param isFloat whether the column is an `f32` ([FloatArray]) rather than `f64`
    /// @param i       the zero-based position
    /// @return the value widened to a double
    private static double readDouble(Array data, boolean isFloat, long i) {
        return isFloat ? ((FloatArray) data).getFloat(i) : ((DoubleArray) data).getDouble(i);
    }

    /// Reports whether the predicate is a single value-comparison leaf the hoisted lane lowers — not a
    /// null test or a boolean composite, which the per-element evaluator handles.
    ///
    /// @param predicate the predicate to classify
    /// @return `true` for Eq / Neq / Lt / Gt / Lte / Gte / Between
    private static boolean isComparisonLeaf(Predicate predicate) {
        return predicate instanceof Predicate.Eq || predicate instanceof Predicate.Neq
                || predicate instanceof Predicate.Lt || predicate instanceof Predicate.Gt
                || predicate instanceof Predicate.Lte || predicate instanceof Predicate.Gte
                || predicate instanceof Predicate.Between;
    }

}
