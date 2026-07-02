package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.ChunkedByteArray;
import io.github.dfa1.vortex.reader.array.DictDoubleArray;
import io.github.dfa1.vortex.reader.array.DictFloatArray;
import io.github.dfa1.vortex.reader.array.DictIntArray;
import io.github.dfa1.vortex.reader.array.DictLongArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.OffsetDoubleArray;
import io.github.dfa1.vortex.reader.array.OffsetFloatArray;
import io.github.dfa1.vortex.reader.array.OffsetIntArray;
import io.github.dfa1.vortex.reader.array.OffsetLongArray;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/// The dict code-scan fast lane of the fused filter kernels: a comparison predicate over a
/// dictionary-encoded filter column is resolved against the (tiny) value pool ONCE, then the scan
/// tests raw `u8` codes instead of decoded values — no per-row dictionary gather and, crucially, no
/// per-row accessor dispatch.
///
/// Why this lane exists (measured on the 100M-row `ComputeKernelBenchmark`): reading dict codes
/// through the generic accessor chain costs ≈ 5 ns/element — the codes of a shared dictionary are a
/// [ChunkedByteArray], so every `getByte(i)` pays a `findChunk` binary search plus the child's
/// broadcast guard (`length == elementCount ? i : i % elementCount`, whose modulo C2 evaluates via
/// CMove and which blocks superword vectorization). Scanning the same codes directly from the
/// backing [MemorySegment] runs at plain-scan bandwidth, ≈ 20× faster. Encoded-domain value
/// comparison alone does NOT help (the `forLoopDictEncoded` negative result) — the win is the
/// monomorphic segment loop, which this lane provides by hoisting the chunk walk and the code
/// resolution out of the per-row path.
///
/// The lane lowers the predicate to a 256-entry code match table via the SAME single source of
/// truth as the primitive lanes ([PrimitiveFilter]), so selection can never drift from the generic
/// path. Two loop shapes, chosen once per run (the hot-loop rule's branch-split idiom):
/// - exactly one matching code (the common `=` push-down) — a bare `code == match` byte compare;
/// - several matching codes — a `table[code]` lookup.
///
/// Codes are walked as contiguous runs: each [ChunkedByteArray] child (or the single flat codes
/// array) contributes one run scanned over its backing segment; a run whose child exposes no
/// segment (or a broadcast-shortened one) falls back to the child's accessor for that run only.
/// Filter validity and the aggregate's own validity are only read on matching rows, so the
/// 100%-of-rows cost stays a single byte compare per element.
final class DictFilter {

    /// Codes wider than one byte (a value pool past 256 entries) fall back to the generic lanes.
    private static final int MAX_POOL = 256;

    private DictFilter() {
    }

    /// Attempts the dict code-scan lane for [Compute#filteredSum(Array, Predicate, Array)]:
    /// filters a dict-encoded column by a comparison predicate and sums `agg` over the selected
    /// non-null rows in one pass over the raw codes.
    ///
    /// The caller has already verified the predicate is a numeric comparison leaf and the aggregate
    /// is a specialized numeric array. This method additionally requires the filter to unwrap to a
    /// `DictXxxArray` (optionally behind one `OffsetXxxArray` slice) with `u8` codes covering the
    /// scanned range; any other shape declines the lane.
    ///
    /// @param filterData the unwrapped filter array (possibly an offset slice over a dict array)
    /// @param fVal       the filter validity bitmap, or `null` when every filter row is valid
    /// @param predicate  the comparison-leaf predicate over numeric constants
    /// @param aggData    the unwrapped aggregate array ([LongArray], [DoubleArray] or [FloatArray])
    /// @param aVal       the aggregate validity bitmap, or `null` when every aggregate row is valid
    /// @param aggDouble  whether the aggregate folds into a double ([DoubleArray] / [FloatArray])
    /// @param n          the row count
    /// @return the sum boxed as a [Long] (integer aggregate) or [Double] (floating aggregate), or
    ///         `null` when the filter does not qualify for this lane
    static Number tryFilteredSum(Array filterData, BoolArray fVal, Predicate predicate,
                                 Array aggData, BoolArray aVal, boolean aggDouble, long n) {
        DictShape shape = shapeOf(filterData);
        if (shape == null || shape.codes().length() < shape.codeStart() + n) {
            return null;
        }
        CodeTable table = matchTable(shape, predicate);
        if (table == null) {
            return null;
        }
        if (table.matches() == 0) {
            // No pool value satisfies the predicate: nothing is selected, the sum is the identity.
            // Explicit branches, not a ternary: a `Double ? : Long` conditional would binary-numeric
            // promote both arms to double and re-box to Double, losing the long aggregate's result type.
            if (aggDouble) {
                return Double.valueOf(0.0);
            }
            return Long.valueOf(0L);
        }
        List<Run> runs = runsOf(shape.codes(), shape.codeStart(), n);
        if (aggDouble) {
            return Double.valueOf(sumDouble(runs, table, fVal, aggData, aVal));
        }
        return Long.valueOf(sumLong(runs, table, fVal, (LongArray) aggData, aVal));
    }

    /// Attempts the dict code-scan lane for a count-only fold — the `COUNT(*)` shape of
    /// [Compute#filteredAggregate(Chunk, RowFilter, String)] with no aggregate column.
    ///
    /// @param filterData the unwrapped filter array (possibly an offset slice over a dict array)
    /// @param fVal       the filter validity bitmap, or `null` when every filter row is valid
    /// @param predicate  the comparison-leaf predicate over numeric constants
    /// @param n          the row count
    /// @return the selected row count, or `null` when the filter does not qualify for this lane
    static Long tryFilteredCount(Array filterData, BoolArray fVal, Predicate predicate, long n) {
        DictShape shape = shapeOf(filterData);
        if (shape == null || shape.codes().length() < shape.codeStart() + n) {
            return null;
        }
        CodeTable table = matchTable(shape, predicate);
        if (table == null) {
            return null;
        }
        if (table.matches() == 0) {
            return Long.valueOf(0L);
        }
        return Long.valueOf(count(runsOf(shape.codes(), shape.codeStart(), n), table, fVal));
    }

    /// Attempts the dict code-scan lane for the full filtered-aggregate fold of
    /// [Compute#filteredAggregate(Chunk, RowFilter, String)]: the selected row count, the
    /// aggregate's non-null count among them, and its `SUM` / `MIN` / `MAX`, folded per matching
    /// row with the same domain semantics as the kernel's own lanes (wrapping [Long] sum and
    /// unsigned-aware order in the long domain, [Double#compare(double, double)] order and the
    /// column's element box in the double domain).
    ///
    /// @param filterData the unwrapped filter array (possibly an offset slice over a dict array)
    /// @param fVal       the filter validity bitmap, or `null` when every filter row is valid
    /// @param predicate  the comparison-leaf predicate over numeric constants
    /// @param aggData    the unwrapped aggregate array
    /// @param aVal       the aggregate validity bitmap, or `null` when every aggregate row is valid
    /// @param n          the row count
    /// @return the fold, or `null` when the filter is not a `u8`-coded dict view or the aggregate
    ///         has no specialized long/double lane (an `f16` or non-numeric column)
    static FilteredAggregate tryFilteredAggregate(Array filterData, BoolArray fVal, Predicate predicate,
                                                  Array aggData, BoolArray aVal, long n) {
        boolean aggLong = NumericColumns.isLongDomain(aggData);
        boolean aggDouble = aggData instanceof DoubleArray || aggData instanceof FloatArray;
        if (!aggLong && !aggDouble) {
            return null;
        }
        DictShape shape = shapeOf(filterData);
        if (shape == null || shape.codes().length() < shape.codeStart() + n) {
            return null;
        }
        CodeTable table = matchTable(shape, predicate);
        if (table == null) {
            return null;
        }
        if (table.matches() == 0) {
            // Nothing selected: the sum is the domain identity, the extremes are null — the same
            // fold the kernel's own lanes produce over zero selected rows. Explicit branches, not a
            // ternary: a `Long ? : Double` conditional binary-numeric promotes both arms to double
            // and re-boxes to Double, losing the long aggregate's result type.
            if (aggLong) {
                return new FilteredAggregate(0L, 0L, Long.valueOf(0L), null, null);
            }
            return new FilteredAggregate(0L, 0L, Double.valueOf(0.0), null, null);
        }
        List<Run> runs = runsOf(shape.codes(), shape.codeStart(), n);
        if (aggLong) {
            LongAggFold fold = new LongAggFold(aggData, aggData.dtype().isUnsigned(), fVal, aVal);
            scanRunsLong(runs, table, fold);
            return fold.box();
        }
        DoubleAggFold fold = new DoubleAggFold(aggData, aggData instanceof FloatArray, fVal, aVal);
        scanRunsDouble(runs, table, fold);
        return fold.box();
    }

    // -------------------------------------------------------------------------------------------------
    // Shape detection and predicate lowering (once per call, outside every loop).
    // -------------------------------------------------------------------------------------------------

    /// Unwraps the filter into its scannable dict parts: one optional `OffsetXxxArray` slice layer,
    /// then a `DictXxxArray` whose codes are a [ByteArray].
    ///
    /// @param filterData the unwrapped filter array
    /// @return the dict shape, or `null` when the filter is not a `u8`-coded dict view
    private static DictShape shapeOf(Array filterData) {
        Array base = filterData;
        long codeStart = 0L;
        if (base instanceof OffsetLongArray off) {
            codeStart = off.offset();
            base = off.inner();
        } else if (base instanceof OffsetIntArray off) {
            codeStart = off.offset();
            base = off.inner();
        } else if (base instanceof OffsetDoubleArray off) {
            codeStart = off.offset();
            base = off.inner();
        } else if (base instanceof OffsetFloatArray off) {
            codeStart = off.offset();
            base = off.inner();
        }
        return switch (base) {
            case DictLongArray dict when dict.codes() instanceof ByteArray codes ->
                    new DictShape(dict.values(), codes, codeStart, false, dict.dtype().isUnsigned());
            case DictIntArray dict when dict.codes() instanceof ByteArray codes ->
                    new DictShape(dict.values(), codes, codeStart, false, dict.dtype().isUnsigned());
            case DictDoubleArray dict when dict.codes() instanceof ByteArray codes ->
                    new DictShape(dict.values(), codes, codeStart, true, false);
            case DictFloatArray dict when dict.codes() instanceof ByteArray codes ->
                    new DictShape(dict.values(), codes, codeStart, true, false);
            default -> null;
        };
    }

    /// Lowers the predicate to a per-code match table by evaluating it over every pool value,
    /// through the same [PrimitiveFilter] lowering the primitive lanes use.
    ///
    /// @param shape     the dict shape
    /// @param predicate the comparison-leaf predicate
    /// @return the 256-entry match table with its match count, or `null` when the pool is empty
    ///         (a malformed dict left to the generic path) or larger than a `u8` code can index
    private static CodeTable matchTable(DictShape shape, Predicate predicate) {
        long pool = shape.values().length();
        if (pool == 0 || pool > MAX_POOL) {
            return null;
        }
        boolean[] match = new boolean[MAX_POOL];
        int matches = 0;
        int only = -1;
        if (shape.doubleDomain()) {
            PrimitiveFilter.DoubleBound bound = PrimitiveFilter.lowerDouble(predicate);
            boolean isFloat = shape.values() instanceof FloatArray;
            for (int c = 0; c < pool; c++) {
                double v = isFloat
                        ? ((FloatArray) shape.values()).getFloat(c)
                        : ((DoubleArray) shape.values()).getDouble(c);
                if (PrimitiveFilter.matchDouble(v, bound.op(), bound.lo(), bound.hi())) {
                    match[c] = true;
                    matches++;
                    only = c;
                }
            }
        } else {
            long flip = shape.unsigned() ? Long.MIN_VALUE : 0L;
            PrimitiveFilter.LongRange range = PrimitiveFilter.lowerLong(predicate, flip);
            for (int c = 0; c < pool; c++) {
                long v = NumericColumns.widenLong(shape.values(), shape.unsigned(), c) ^ flip;
                if ((range.lo() <= v && v <= range.hi()) != range.negate()) {
                    match[c] = true;
                    matches++;
                    only = c;
                }
            }
        }
        return new CodeTable(match, matches, only);
    }

    /// Splits the scanned code range into contiguous runs — one per overlapping
    /// [ChunkedByteArray] child, or a single run over flat codes — resolving each run's backing
    /// segment once.
    ///
    /// @param codes the `u8` codes array
    /// @param start the code index of the view's first row
    /// @param n     the row count
    /// @return the runs covering `[start, start + n)` in row order
    private static List<Run> runsOf(ByteArray codes, long start, long n) {
        List<Run> runs = new ArrayList<>();
        if (codes instanceof ChunkedByteArray chunked) {
            ByteArray[] children = chunked.children();
            long[] offsets = chunked.offsets();
            long end = start + n;
            for (int c = 0; c < children.length; c++) {
                long lo = Math.max(start, offsets[c]);
                long hi = Math.min(end, offsets[c + 1]);
                if (lo < hi) {
                    runs.add(runOf(children[c], lo - offsets[c], lo - start, hi - lo));
                }
            }
        } else {
            runs.add(runOf(codes, start, 0L, n));
        }
        return runs;
    }

    /// Builds one run, capturing the child's backing segment when it is present and physically
    /// covers the run — a broadcast-shortened buffer (fewer stored elements than logical rows)
    /// keeps `seg` `null` so the run scans through the accessor, which applies the wrap.
    ///
    /// @param child     the codes child owning the run
    /// @param childBase the run's first index within the child
    /// @param rowBase   the run's first row in the scanned view
    /// @param len       the run length
    /// @return the run
    private static Run runOf(ByteArray child, long childBase, long rowBase, long len) {
        MemorySegment seg = child.segmentIfPresent()
                .filter(s -> s.byteSize() >= childBase + len)
                .orElse(null);
        return new Run(child, seg, childBase, rowBase, len);
    }

    // -------------------------------------------------------------------------------------------------
    // Specialized fused loops. Per run, the loop shape is chosen once: a single-code segment
    // compare, a table-lookup segment scan, or the accessor fallback. Validity and the aggregate
    // are only read on matching rows.
    // -------------------------------------------------------------------------------------------------

    /// Folds the integer aggregate over the selected rows of every run.
    ///
    /// @param runs  the code runs in row order
    /// @param table the lowered match table
    /// @param fVal  the filter validity bitmap, or `null`
    /// @param agg   the aggregate array
    /// @param aVal  the aggregate validity bitmap, or `null`
    /// @return the wrapping integer sum over the selected non-null rows
    private static long sumLong(List<Run> runs, CodeTable table, BoolArray fVal,
                                LongArray agg, BoolArray aVal) {
        boolean[] match = table.match();
        boolean single = table.matches() == 1;
        byte only = (byte) table.only();
        long acc = 0L;
        for (Run run : runs) {
            MemorySegment seg = run.seg();
            long base = run.childBase();
            long rowBase = run.rowBase();
            long len = run.len();
            if (seg != null && single) {
                for (long j = 0; j < len; j++) {
                    if (seg.get(ValueLayout.JAVA_BYTE, base + j) == only) {
                        long row = rowBase + j;
                        if (rowSelected(row, fVal, aVal)) {
                            acc += agg.getLong(row);
                        }
                    }
                }
            } else if (seg != null) {
                for (long j = 0; j < len; j++) {
                    if (match[seg.get(ValueLayout.JAVA_BYTE, base + j) & 0xFF]) {
                        long row = rowBase + j;
                        if (rowSelected(row, fVal, aVal)) {
                            acc += agg.getLong(row);
                        }
                    }
                }
            } else {
                ByteArray child = run.child();
                for (long j = 0; j < len; j++) {
                    if (match[child.getByte(base + j) & 0xFF]) {
                        long row = rowBase + j;
                        if (rowSelected(row, fVal, aVal)) {
                            acc += agg.getLong(row);
                        }
                    }
                }
            }
        }
        return acc;
    }

    /// Folds the floating aggregate over the selected rows of every run.
    ///
    /// @param runs    the code runs in row order
    /// @param table   the lowered match table
    /// @param fVal    the filter validity bitmap, or `null`
    /// @param aggData the unwrapped double-domain aggregate array
    /// @param aVal    the aggregate validity bitmap, or `null`
    /// @return the floating sum over the selected non-null rows
    private static double sumDouble(List<Run> runs, CodeTable table, BoolArray fVal,
                                    Array aggData, BoolArray aVal) {
        boolean[] match = table.match();
        boolean single = table.matches() == 1;
        byte only = (byte) table.only();
        boolean aggIsFloat = aggData instanceof FloatArray;
        double acc = 0.0;
        for (Run run : runs) {
            MemorySegment seg = run.seg();
            long base = run.childBase();
            long rowBase = run.rowBase();
            long len = run.len();
            if (seg != null && single) {
                for (long j = 0; j < len; j++) {
                    if (seg.get(ValueLayout.JAVA_BYTE, base + j) == only) {
                        long row = rowBase + j;
                        if (rowSelected(row, fVal, aVal)) {
                            acc += readDouble(aggData, aggIsFloat, row);
                        }
                    }
                }
            } else if (seg != null) {
                for (long j = 0; j < len; j++) {
                    if (match[seg.get(ValueLayout.JAVA_BYTE, base + j) & 0xFF]) {
                        long row = rowBase + j;
                        if (rowSelected(row, fVal, aVal)) {
                            acc += readDouble(aggData, aggIsFloat, row);
                        }
                    }
                }
            } else {
                ByteArray child = run.child();
                for (long j = 0; j < len; j++) {
                    if (match[child.getByte(base + j) & 0xFF]) {
                        long row = rowBase + j;
                        if (rowSelected(row, fVal, aVal)) {
                            acc += readDouble(aggData, aggIsFloat, row);
                        }
                    }
                }
            }
        }
        return acc;
    }

    /// Counts the selected rows of every run: a code match that is not a null filter row.
    ///
    /// @param runs  the code runs in row order
    /// @param table the lowered match table
    /// @param fVal  the filter validity bitmap, or `null`
    /// @return the selected row count
    private static long count(List<Run> runs, CodeTable table, BoolArray fVal) {
        boolean[] match = table.match();
        boolean single = table.matches() == 1;
        byte only = (byte) table.only();
        boolean noFilterNull = fVal == null;
        long selected = 0L;
        for (Run run : runs) {
            MemorySegment seg = run.seg();
            long base = run.childBase();
            long rowBase = run.rowBase();
            long len = run.len();
            if (seg != null && single) {
                for (long j = 0; j < len; j++) {
                    if (seg.get(ValueLayout.JAVA_BYTE, base + j) == only
                            && (noFilterNull || fVal.getBoolean(rowBase + j))) {
                        selected++;
                    }
                }
            } else if (seg != null) {
                for (long j = 0; j < len; j++) {
                    if (match[seg.get(ValueLayout.JAVA_BYTE, base + j) & 0xFF]
                            && (noFilterNull || fVal.getBoolean(rowBase + j))) {
                        selected++;
                    }
                }
            } else {
                ByteArray child = run.child();
                for (long j = 0; j < len; j++) {
                    if (match[child.getByte(base + j) & 0xFF]
                            && (noFilterNull || fVal.getBoolean(rowBase + j))) {
                        selected++;
                    }
                }
            }
        }
        return selected;
    }

    /// Folds the long-domain aggregate accumulator over the code-matching rows of every run.
    ///
    /// @param runs  the code runs in row order
    /// @param table the lowered match table
    /// @param fold  the accumulator receiving each matching row
    private static void scanRunsLong(List<Run> runs, CodeTable table, LongAggFold fold) {
        boolean[] match = table.match();
        boolean single = table.matches() == 1;
        byte only = (byte) table.only();
        for (Run run : runs) {
            MemorySegment seg = run.seg();
            long base = run.childBase();
            long rowBase = run.rowBase();
            long len = run.len();
            if (seg != null && single) {
                for (long j = 0; j < len; j++) {
                    if (seg.get(ValueLayout.JAVA_BYTE, base + j) == only) {
                        fold.accept(rowBase + j);
                    }
                }
            } else if (seg != null) {
                for (long j = 0; j < len; j++) {
                    if (match[seg.get(ValueLayout.JAVA_BYTE, base + j) & 0xFF]) {
                        fold.accept(rowBase + j);
                    }
                }
            } else {
                ByteArray child = run.child();
                for (long j = 0; j < len; j++) {
                    if (match[child.getByte(base + j) & 0xFF]) {
                        fold.accept(rowBase + j);
                    }
                }
            }
        }
    }

    /// Folds the double-domain aggregate accumulator over the code-matching rows of every run.
    ///
    /// @param runs  the code runs in row order
    /// @param table the lowered match table
    /// @param fold  the accumulator receiving each matching row
    private static void scanRunsDouble(List<Run> runs, CodeTable table, DoubleAggFold fold) {
        boolean[] match = table.match();
        boolean single = table.matches() == 1;
        byte only = (byte) table.only();
        for (Run run : runs) {
            MemorySegment seg = run.seg();
            long base = run.childBase();
            long rowBase = run.rowBase();
            long len = run.len();
            if (seg != null && single) {
                for (long j = 0; j < len; j++) {
                    if (seg.get(ValueLayout.JAVA_BYTE, base + j) == only) {
                        fold.accept(rowBase + j);
                    }
                }
            } else if (seg != null) {
                for (long j = 0; j < len; j++) {
                    if (match[seg.get(ValueLayout.JAVA_BYTE, base + j) & 0xFF]) {
                        fold.accept(rowBase + j);
                    }
                }
            } else {
                ByteArray child = run.child();
                for (long j = 0; j < len; j++) {
                    if (match[child.getByte(base + j) & 0xFF]) {
                        fold.accept(rowBase + j);
                    }
                }
            }
        }
    }

    /// The long-domain filtered-aggregate accumulator: mirrors the kernel's own long lane — a
    /// wrapping sum and an unsigned-aware `MIN` / `MAX` over the selected non-null rows, reading
    /// each value through [NumericColumns#widenLong(Array, boolean, long)]. Only invoked on code
    /// matches, so validity is never read on the 100%-of-rows path.
    private static final class LongAggFold {

        private final Array aggData;
        private final boolean unsigned;
        private final BoolArray fVal;
        private final BoolArray aVal;
        private long selected;
        private long nonNull;
        private long sum;
        private boolean found;
        private long min;
        private long max;

        LongAggFold(Array aggData, boolean unsigned, BoolArray fVal, BoolArray aVal) {
            this.aggData = aggData;
            this.unsigned = unsigned;
            this.fVal = fVal;
            this.aVal = aVal;
        }

        /// Folds one code-matching row: skipped when the filter row is null (three-valued logic);
        /// counted as selected, then folded when the aggregate row is non-null.
        ///
        /// @param row the matching row
        void accept(long row) {
            if (fVal != null && !fVal.getBoolean(row)) {
                return;
            }
            selected++;
            if (aVal != null && !aVal.getBoolean(row)) {
                return;
            }
            nonNull++;
            long v = NumericColumns.widenLong(aggData, unsigned, row);
            sum += v;
            if (!found) {
                min = v;
                max = v;
                found = true;
            } else {
                if (FusedFilterAggregate.lessLong(v, min, unsigned)) {
                    min = v;
                }
                if (FusedFilterAggregate.lessLong(max, v, unsigned)) {
                    max = v;
                }
            }
        }

        /// Boxes the fold exactly as the kernel's long lane does.
        ///
        /// @return the fold: selected count, non-null count, the [Long] sum, and the [Long]
        ///         `MIN` / `MAX` (`null` when no selected row is non-null)
        FilteredAggregate box() {
            return new FilteredAggregate(selected, nonNull, Long.valueOf(sum),
                    found ? Long.valueOf(min) : null, found ? Long.valueOf(max) : null);
        }
    }

    /// The double-domain filtered-aggregate accumulator: mirrors the kernel's own double lane — a
    /// [Double] sum and a [Double#compare(double, double)]-ordered `MIN` / `MAX` over the selected
    /// non-null rows. Only invoked on code matches, so validity is never read on the 100%-of-rows
    /// path.
    private static final class DoubleAggFold {

        private final Array aggData;
        private final boolean aggIsFloat;
        private final BoolArray fVal;
        private final BoolArray aVal;
        private long selected;
        private long nonNull;
        private double sum;
        private boolean found;
        private double min;
        private double max;

        DoubleAggFold(Array aggData, boolean aggIsFloat, BoolArray fVal, BoolArray aVal) {
            this.aggData = aggData;
            this.aggIsFloat = aggIsFloat;
            this.fVal = fVal;
            this.aVal = aVal;
        }

        /// Folds one code-matching row: skipped when the filter row is null (three-valued logic);
        /// counted as selected, then folded when the aggregate row is non-null.
        ///
        /// @param row the matching row
        void accept(long row) {
            if (fVal != null && !fVal.getBoolean(row)) {
                return;
            }
            selected++;
            if (aVal != null && !aVal.getBoolean(row)) {
                return;
            }
            nonNull++;
            double v = readDouble(aggData, aggIsFloat, row);
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

        /// Boxes the fold exactly as the kernel's double lane does — the extremes carry the
        /// column's element box ([Float] for an `f32` aggregate).
        ///
        /// @return the fold: selected count, non-null count, the [Double] sum, and the boxed
        ///         `MIN` / `MAX` (`null` when no selected row is non-null)
        FilteredAggregate box() {
            return new FilteredAggregate(selected, nonNull, Double.valueOf(sum),
                    FusedFilterAggregate.boxDouble(found, min, aggIsFloat),
                    FusedFilterAggregate.boxDouble(found, max, aggIsFloat));
        }
    }

    /// Reports whether a code-matching row is actually selected and countable: not a null filter
    /// row (three-valued logic) and not a null aggregate row. Only called on code matches, so the
    /// validity bitmaps are never read on the 100%-of-rows path.
    ///
    /// @param row  the matching row
    /// @param fVal the filter validity bitmap, or `null` when every filter row is valid
    /// @param aVal the aggregate validity bitmap, or `null` when every aggregate row is valid
    /// @return `true` if the row contributes to the sum
    private static boolean rowSelected(long row, BoolArray fVal, BoolArray aVal) {
        return (fVal == null || fVal.getBoolean(row)) && (aVal == null || aVal.getBoolean(row));
    }

    /// Reads aggregate position `row` as a double through the hoisted typed accessor.
    ///
    /// @param aggData    the unwrapped double-domain aggregate array
    /// @param aggIsFloat whether the aggregate is an `f32` ([FloatArray]) rather than `f64`
    /// @param row        the zero-based position
    /// @return the aggregate value widened to a double
    private static double readDouble(Array aggData, boolean aggIsFloat, long row) {
        return aggIsFloat ? ((FloatArray) aggData).getFloat(row) : ((DoubleArray) aggData).getDouble(row);
    }

    /// A dict filter column reduced to its scannable parts.
    ///
    /// @param values       the dictionary value pool
    /// @param codes        the per-row `u8` codes
    /// @param codeStart    the code index of the view's first row (an offset slice's offset)
    /// @param doubleDomain whether the pool is floating ([DoubleArray] / [FloatArray])
    /// @param unsigned     whether a long-domain pool is unsigned
    private record DictShape(Array values, ByteArray codes, long codeStart,
                             boolean doubleDomain, boolean unsigned) {
    }

    /// The predicate lowered to per-code matches.
    ///
    /// @param match   whether each `u8` code selects its row (codes past the pool never match)
    /// @param matches the number of matching codes
    /// @param only    the single matching code when `matches == 1`, else meaningless
    private record CodeTable(boolean[] match, int matches, int only) {
    }

    /// One contiguous run of codes: a child (or the flat codes array) intersected with the scanned
    /// range, with its backing segment resolved once.
    ///
    /// @param child     the codes child owning the run
    /// @param seg       the child's backing segment, or `null` to scan through the accessor
    /// @param childBase the run's first index within the child
    /// @param rowBase   the run's first row in the scanned view
    /// @param len       the run length
    private record Run(ByteArray child, MemorySegment seg, long childBase, long rowBase, long len) {
    }
}
