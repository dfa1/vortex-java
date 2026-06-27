package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.compute.ZoneReducer;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

import org.apache.calcite.DataContext;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.schema.ProjectableFilterableTable;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/// A single Vortex file exposed to Calcite as a flat SQL table with column projection and
/// zone-map filter push-down.
///
/// Projection (`projects`) is honoured exactly — only the requested columns are decoded and
/// returned. Filters (`filters`) that translate to a [RowFilter] are pushed into the scan for
/// *chunk skipping* via zone-map statistics, but are **left in Calcite's list** rather than
/// consumed: zone-map pruning is approximate (it drops whole chunks that cannot match, not
/// individual rows), so Calcite must still apply the predicate row-by-row for exactness. The
/// win is decoding far fewer chunks when the filter is selective on a clustered column.
public final class VortexTable extends AbstractTable implements ProjectableFilterableTable, TranslatableTable {

    /// Used only to expand `SEARCH`/`Sarg` predicates (e.g. `BETWEEN`, `IN`) back into ordinary
    /// comparison trees the [RowFilter] translation understands.
    private static final RexBuilder REX_BUILDER = new RexBuilder(new JavaTypeFactoryImpl());

    private final Path file;
    private final AtomicLong chunksScannedLastQuery = new AtomicLong();

    /// Creates a table backed by the Vortex file at `file`.
    ///
    /// @param file path to the `.vortex` file
    public VortexTable(Path file) {
        this.file = file;
    }

    /// Number of chunks actually decoded by the most recent [#scan] — the rest were pruned by
    /// zone-map statistics. Used by the demo to show push-down skipping work.
    ///
    /// @return chunks decoded in the last query
    public long chunksScannedLastQuery() {
        return chunksScannedLastQuery.get();
    }

    /// A column's footer zone-map statistics paired with the file's total row count — the two facts
    /// the aggregate push-down rule reads together to answer `MIN`/`MAX`/`COUNT(col)` (a `COUNT`
    /// needs `rows − nulls`; a `MIN`/`MAX` with no stat needs the row count to prove the column is
    /// empty). Read in one reader pass so the rule opens the file once per aggregate.
    ///
    /// @param stats     the column's aggregated statistics (global min/max, null count), or
    ///                  [ArrayStats#empty()] when the column carries no zone map
    /// @param totalRows the total row count across all chunks
    public record ColumnStats(ArrayStats stats, long totalRows) {
    }

    /// Per-column zone-map statistics (global min/max and null count) together with the total row
    /// count, read from the footer and chunk metadata without decoding data, in a single pass over
    /// one open reader. Used by the aggregate push-down rule to answer `MIN`/`MAX`/`COUNT`.
    ///
    /// @param column the column name
    /// @return the column's aggregated statistics paired with the file's total row count
    public ColumnStats statsAndRows(String column) {
        try (VortexReader reader = VortexReader.open(file)) {
            ArrayStats stats = reader.columnStats().getOrDefault(column, ArrayStats.empty());
            return new ColumnStats(stats, countRows(reader));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read stats of " + file, e);
        }
    }

    /// The folded `SUM` of a column together with the two facts needed to interpret a zero fold:
    /// SQL `SUM` over zero non-null rows is `NULL`, but an all-null zone records a sum-neutral `0`,
    /// so `sum == 0` alone cannot tell an all-null column from a genuine zero. The `nullCount` and
    /// `totalRows` resolve it.
    ///
    /// @param sum       the folded column sum ([Long] for integer columns, [Double] for floating),
    ///                  or `null` when no zone carries a usable sum (no zone map, or an overflowed
    ///                  zone)
    /// @param nullCount the column's total null count from the zone map, or `null` if not recorded
    /// @param totalRows the total row count across all chunks
    public record ZoneSum(Number sum, Long nullCount, long totalRows) {
    }

    /// Folds the per-zone `SUM` statistics for `column` without decoding any data segment, reading
    /// the fold, null count, and row count in a single pass over the open reader so the caller can
    /// answer `SUM` null-correctly. Used by the aggregate push-down rule (ADR 0013 §6).
    ///
    /// Integer columns fold into a [Long] (exact); floating columns into a [Double]. The
    /// [ZoneSum#sum()] is `null` when the zone-map table cannot answer the reduction — a column
    /// with no zone map, or a zone whose sum was not retained (e.g. an overflowed zone).
    ///
    /// @param column the numeric column name
    /// @return the folded sum with the null and row counts needed to interpret a zero
    public ZoneSum zoneSum(String column) {
        try (VortexReader reader = VortexReader.open(file)) {
            Number sum = new ZoneReducer(reader).sum(column);
            Long nullCount = reader.columnStats()
                    .getOrDefault(column, ArrayStats.empty()).nullCount();
            return new ZoneSum(sum, nullCount, countRows(reader));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read zone sum of " + file, e);
        }
    }

    /// Total row count across all chunks, read from chunk metadata without decoding data.
    ///
    /// @return the number of rows in the file
    public long totalRows() {
        try (VortexReader reader = VortexReader.open(file)) {
            return countRows(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot count rows of " + file, e);
        }
    }

    /// Sums the chunk row counts of `reader` from chunk metadata, without decoding data — shared by
    /// [#totalRows()] and [#zoneSum(String)] so both read the count from a single open reader.
    private static long countRows(VortexReader reader) throws IOException {
        long total = 0;
        try (ScanIterator scan = reader.scan(ScanOptions.all())) {
            for (long c : scan.chunkRowCounts()) {
                total += c;
            }
        }
        return total;
    }

    /// A fold of the zones a pushed `WHERE` filter selects, returned by
    /// [#filteredFold(RowFilter, String)] only when the filter classifies every zone all-or-nothing
    /// — no boundary zone it partially selects. The aggregate rule reads the field its aggregate
    /// needs; the values cover exactly the rows the filter matches, so the fold answers
    /// `SUM`/`COUNT`/`MIN`/`MAX` over the filtered table with no data segment decoded.
    ///
    /// @param sum       folded sum of the kept zones ([Long] for integer columns, [Double] for
    ///                  floating), or `null` when a kept zone carries no usable sum
    /// @param min       minimum over the kept zones, or `null` if no zone is kept or a kept zone
    ///                  omits it
    /// @param max       maximum over the kept zones, or `null` if no zone is kept or a kept zone
    ///                  omits it
    /// @param nullCount total null count over the kept zones, or `null` if a kept zone omits it
    /// @param rows      total row count of the kept zones
    public record FilteredFold(Number sum, Object min, Object max, Long nullCount, long rows) {
    }

    /// Folds the zone-map statistics of the zones a pushed `WHERE` `filter` fully selects, reading
    /// only footer metadata — answering an aggregate over the filtered table without decoding a data
    /// segment. Returns empty unless the filter partitions **every** zone cleanly into kept (all
    /// rows match) or dropped (no row matches): the moment one zone is a boundary the filter only
    /// partially selects, the fold is abandoned so the caller scans, because that zone's matching
    /// rows can be known only by decoding it.
    ///
    /// `aggColumn` is the column whose stats are folded over the kept zones (`null` for `COUNT(*)`,
    /// which needs only the kept row count). The result is also empty when a zone cannot be
    /// classified — a missing min/max or null count leaves the all-or-nothing decision undecidable
    /// — or when the zone-map zone count does not align 1:1 with the chunks (a file from a writer
    /// that uses a fixed zone length).
    ///
    /// @param filter    the pushed predicate, already translated to the reader's [RowFilter]
    /// @param aggColumn the column to fold over the kept zones, or `null` for a row-count-only fold
    /// @return the kept-zone fold, or empty when the filter does not partition the zones cleanly
    public Optional<FilteredFold> filteredFold(RowFilter filter, String aggColumn) {
        java.util.LinkedHashSet<String> columns = new java.util.LinkedHashSet<>();
        collectColumns(filter, columns);
        if (aggColumn != null) {
            columns.add(aggColumn);
        }
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator scan = reader.scan(ScanOptions.columns(columns.toArray(String[]::new)))) {
            long[] rowCounts = scan.chunkRowCounts();
            int zones = rowCounts.length;
            java.util.Map<String, List<ArrayStats>> zoneStats = new java.util.HashMap<>();
            for (String column : columns) {
                List<ArrayStats> perZone = scan.columnZoneStats(column);
                if (perZone.size() != zones) {
                    return Optional.empty(); // zone-map zones don't align 1:1 with chunks — cannot classify
                }
                zoneStats.put(column, perZone);
            }

            long longSum = 0L;
            double doubleSum = 0.0;
            boolean floatingSum = false;
            boolean sumUsable = true;
            Object min = null;
            Object max = null;
            long nullCount = 0;
            boolean nullCountKnown = true;
            long keptRows = 0;
            for (int zone = 0; zone < zones; zone++) {
                Match match = classify(filter, zone, zoneStats, rowCounts[zone]);
                if (match == Match.BOUNDARY) {
                    return Optional.empty(); // a partially-selected zone — only a decode can fold it
                }
                if (match == Match.OUT) {
                    continue;
                }
                keptRows += rowCounts[zone];
                if (aggColumn == null) {
                    continue;
                }
                ArrayStats stats = zoneStats.get(aggColumn).get(zone);
                if (sumUsable) {
                    Object zoneSum = stats.sum();
                    if (zoneSum == null) {
                        sumUsable = false;
                    } else if (zoneSum instanceof Double d) {
                        floatingSum = true;
                        doubleSum += d;
                    } else {
                        longSum += ((Number) zoneSum).longValue();
                    }
                }
                min = pickExtreme(min, stats.min(), true);
                max = pickExtreme(max, stats.max(), false);
                if (nullCountKnown) {
                    Long zoneNulls = stats.nullCount();
                    if (zoneNulls == null) {
                        nullCountKnown = false;
                    } else {
                        nullCount += zoneNulls;
                    }
                }
            }
            Number foldedSum = !sumUsable ? null : floatingSum ? Double.valueOf(doubleSum) : Long.valueOf(longSum);
            Long foldedNulls = nullCountKnown ? nullCount : null;
            return Optional.of(new FilteredFold(foldedSum, min, max, foldedNulls, keptRows));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot fold filtered zones of " + file, e);
        }
    }

    /// Translates the predicates a `WHERE` pushed onto the scan into one [RowFilter], **strictly**:
    /// returns empty if any predicate (or any conjunct of one) is not a column-vs-literal
    /// comparison or `AND` of them. The strictness is the correctness guard for aggregate
    /// push-down — answering from stats is only sound when the [RowFilter] captures the *whole*
    /// predicate, so a single untranslatable conjunct must abandon the rewrite rather than silently
    /// drop a filter (as the best-effort [#toRowFilter] does for the scan path, where Calcite still
    /// re-checks every row).
    ///
    /// @param filters the predicates Calcite pushed onto the scan
    /// @return the conjoined filter, or empty when any predicate is not fully translatable
    public Optional<RowFilter> translatePushedFilters(List<RexNode> filters) {
        DType.Struct struct = struct();
        List<String> names = struct.fieldNames();
        List<DType> types = struct.fieldTypes();
        List<RowFilter> translated = new ArrayList<>();
        for (RexNode node : filters) {
            RexNode expanded = RexUtil.expandSearch(REX_BUILDER, null, node);
            Optional<RowFilter> one = strictComparison(expanded, names, types);
            if (one.isEmpty()) {
                return Optional.empty();
            }
            translated.add(one.get());
        }
        if (translated.isEmpty()) {
            return Optional.empty();
        }
        if (translated.size() == 1) {
            return Optional.of(translated.getFirst());
        }
        return Optional.of(RowFilter.and(translated.toArray(RowFilter[]::new)));
    }

    /// How a zone relates to a filter: every row matches ([Match#IN]), no row matches
    /// ([Match#OUT]), or the zone straddles the predicate ([Match#BOUNDARY]) and can be folded only
    /// by decoding it.
    private enum Match {
        IN, OUT, BOUNDARY
    }

    /// Classifies one zone against `filter` from the zone's footer statistics, applying SQL
    /// three-valued logic: a row that is `NULL` in a compared column does not match a comparison,
    /// so a zone is [Match#IN] for a comparison only when it also provably carries no nulls.
    private static Match classify(RowFilter filter, int zone,
                                  java.util.Map<String, List<ArrayStats>> zoneStats, long rowCount) {
        return switch (filter) {
            case RowFilter.And(var parts) -> {
                boolean allIn = true;
                for (RowFilter part : parts) {
                    Match m = classify(part, zone, zoneStats, rowCount);
                    if (m == Match.OUT) {
                        yield Match.OUT; // one conjunct excludes the whole zone
                    }
                    if (m == Match.BOUNDARY) {
                        allIn = false;
                    }
                }
                yield allIn ? Match.IN : Match.BOUNDARY;
            }
            case RowFilter.Eq(var col, var value) -> {
                ArrayStats s = zoneStats.get(col).get(zone);
                if (uncomparable(s, value)) {
                    yield Match.BOUNDARY;
                }
                if (compareStat(value, s.min()) < 0 || compareStat(value, s.max()) > 0) {
                    yield Match.OUT; // the value lies outside the zone's range
                }
                if (compareStat(s.min(), s.max()) == 0 && compareStat(s.min(), value) == 0 && noNulls(s)) {
                    yield Match.IN; // every row equals the value
                }
                yield Match.BOUNDARY;
            }
            case RowFilter.Neq(var col, var value) -> {
                ArrayStats s = zoneStats.get(col).get(zone);
                if (uncomparable(s, value)) {
                    yield Match.BOUNDARY;
                }
                if (compareStat(s.min(), s.max()) == 0 && compareStat(s.min(), value) == 0) {
                    yield Match.OUT; // every row equals the value, so none differ
                }
                if ((compareStat(value, s.min()) < 0 || compareStat(value, s.max()) > 0) && noNulls(s)) {
                    yield Match.IN; // the value is outside the range, so every non-null row differs
                }
                yield Match.BOUNDARY;
            }
            case RowFilter.Gt(var col, var value) -> compare(zoneStats.get(col).get(zone), value,
                    (st, v) -> compareStat(st.max(), v) <= 0, (st, v) -> compareStat(st.min(), v) > 0);
            case RowFilter.Gte(var col, var value) -> compare(zoneStats.get(col).get(zone), value,
                    (st, v) -> compareStat(st.max(), v) < 0, (st, v) -> compareStat(st.min(), v) >= 0);
            case RowFilter.Lt(var col, var value) -> compare(zoneStats.get(col).get(zone), value,
                    (st, v) -> compareStat(st.min(), v) >= 0, (st, v) -> compareStat(st.max(), v) < 0);
            case RowFilter.Lte(var col, var value) -> compare(zoneStats.get(col).get(zone), value,
                    (st, v) -> compareStat(st.min(), v) > 0, (st, v) -> compareStat(st.max(), v) <= 0);
            case RowFilter.IsNull(var col) -> {
                Long nulls = zoneStats.get(col).get(zone).nullCount();
                if (nulls == null) {
                    yield Match.BOUNDARY;
                }
                if (nulls == rowCount) {
                    yield Match.IN;
                }
                yield nulls == 0 ? Match.OUT : Match.BOUNDARY;
            }
            case RowFilter.IsNotNull(var col) -> {
                Long nulls = zoneStats.get(col).get(zone).nullCount();
                if (nulls == null) {
                    yield Match.BOUNDARY;
                }
                if (nulls == 0) {
                    yield Match.IN;
                }
                yield nulls == rowCount ? Match.OUT : Match.BOUNDARY;
            }
        };
    }

    /// Classifies a one-sided comparison (`>`, `>=`, `<`, `<=`) from a zone's bounds: `outTest`
    /// decides whether the whole zone is excluded, `inTest` whether every non-null row matches —
    /// which, combined here with [#noNulls], makes the zone [Match#IN] only when it also carries no
    /// nulls (a null row does not match a comparison).
    private static Match compare(ArrayStats s, Object value,
                                 java.util.function.BiPredicate<ArrayStats, Object> outTest,
                                 java.util.function.BiPredicate<ArrayStats, Object> inTest) {
        if (uncomparable(s, value)) {
            return Match.BOUNDARY;
        }
        if (outTest.test(s, value)) {
            return Match.OUT;
        }
        if (inTest.test(s, value) && noNulls(s)) {
            return Match.IN;
        }
        return Match.BOUNDARY;
    }

    /// Whether a zone's min/max are missing or not the same boxed type as `value` — either way the
    /// zone cannot be classified against `value`, so the caller must treat it as a boundary.
    private static boolean uncomparable(ArrayStats s, Object value) {
        return s.min() == null || s.max() == null || value == null
                || s.min().getClass() != value.getClass();
    }

    /// Whether the zone provably carries no nulls (its null count is recorded and zero).
    private static boolean noNulls(ArrayStats s) {
        return s.nullCount() != null && s.nullCount() == 0;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareStat(Object a, Object b) {
        return ((Comparable) a).compareTo(b);
    }

    private static Object pickExtreme(Object current, Object candidate, boolean min) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        int c = compareStat(current, candidate);
        if (min) {
            return c <= 0 ? current : candidate;
        }
        return c >= 0 ? current : candidate;
    }

    /// Translates a reference to this table into a [VortexTableScan] — the seam that auto-registers
    /// the aggregate push-down rules on the planner (see [VortexTableScan#register]). The scan
    /// immediately expands to a stock `LogicalTableScan`, so projection and filter push-down run
    /// through Calcite's `Bindables` convention unchanged.
    ///
    /// @param context     the planning context supplying the cluster
    /// @param relOptTable the planner's handle to this table
    /// @return a `VortexTableScan` over this table
    @Override
    public RelNode toRel(RelOptTable.ToRelContext context, RelOptTable relOptTable) {
        return VortexTableScan.create(context.getCluster(), relOptTable);
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        DType.Struct struct = struct();
        RelDataTypeFactory.Builder builder = typeFactory.builder();
        for (int i = 0; i < struct.fieldNames().size(); i++) {
            builder.add(struct.fieldNames().get(i), toSqlType(typeFactory, struct.fieldTypes().get(i)));
        }
        return builder.build();
    }

    @Override
    public Enumerable<Object[]> scan(DataContext root, List<RexNode> filters, int[] projects) {
        DType.Struct struct = struct();
        List<String> allNames = struct.fieldNames();

        // Projection: the columns to decode and emit, in the order Calcite asked for. A null
        // projects array means "all columns".
        int[] cols = projects != null ? projects : allColumns(allNames.size());
        String[] outNames = new String[cols.length];
        DType[] outTypes = new DType[cols.length];
        for (int i = 0; i < cols.length; i++) {
            outNames[i] = allNames.get(cols[i]);
            outTypes[i] = struct.fieldTypes().get(cols[i]);
        }

        // Filter push-down: build a RowFilter from the predicates we understand (for chunk skip).
        // Do NOT remove anything from `filters` — pruning is approximate, Calcite re-checks rows.
        Optional<RowFilter> pushed = toRowFilter(filters, allNames, struct.fieldTypes());

        // The scan must include any column the filter prunes on, even when it is not projected —
        // chunk pruning reads that column's zone-map stats. Output still emits only outNames.
        java.util.LinkedHashSet<String> scanColumns = new java.util.LinkedHashSet<>(List.of(outNames));
        ScanOptions options;
        if (pushed.isPresent()) {
            collectColumns(pushed.get(), scanColumns);
            options = ScanOptions.columns(scanColumns.toArray(String[]::new)).withFilter(pushed.get());
        } else {
            options = ScanOptions.columns(outNames);
        }

        // Stream rows lazily: decode one chunk at a time and yield a fresh row, so rows die young
        // (in G1's young gen) instead of piling a whole-result List<Object[]> into the old gen — the
        // dominant cost an async-profiler run showed for the full-scan path (~72% in GC).
        ScanOptions scanOptions = options;
        return new AbstractEnumerable<>() {
            @Override
            public Enumerator<Object[]> enumerator() {
                return new VortexEnumerator(scanOptions, outNames, outTypes);
            }
        };
    }

    /// Streaming [Enumerator] over a Vortex scan: advances chunk by chunk, decoding each requested
    /// column once per chunk and materialising one `Object[]` row per [#moveNext()]. Rows are not
    /// retained, so the working set stays at one chunk rather than the whole result.
    private final class VortexEnumerator implements Enumerator<Object[]> {

        private final String[] names;
        private final DType[] types;
        private final VortexReader reader;
        private final ScanIterator scan;
        private Chunk chunk;
        private Object[] columns;
        private long rowInChunk;
        private long chunkRows;
        private Object[] current;

        private VortexEnumerator(ScanOptions options, String[] names, DType[] types) {
            this.names = names;
            this.types = types;
            chunksScannedLastQuery.set(0);
            VortexReader openedReader = null;
            try {
                openedReader = VortexReader.open(file);
                this.reader = openedReader;
                this.scan = openedReader.scan(options);
            } catch (IOException e) {
                closeQuietly(openedReader);
                throw new UncheckedIOException("cannot scan " + file, e);
            } catch (RuntimeException e) {
                closeQuietly(openedReader);
                throw e;
            }
        }

        private void closeQuietly(VortexReader r) {
            if (r != null) {
                r.close();
            }
        }

        @Override
        public Object[] current() {
            return current;
        }

        @Override
        public boolean moveNext() {
            while (true) {
                if (chunk != null && rowInChunk < chunkRows) {
                    Object[] row = new Object[names.length];
                    for (int c = 0; c < names.length; c++) {
                        row[c] = value(columns[c], types[c], rowInChunk);
                    }
                    rowInChunk++;
                    current = row;
                    return true;
                }
                if (chunk != null) {
                    chunk.close();
                    chunk = null;
                }
                if (!scan.hasNext()) {
                    return false;
                }
                chunk = scan.next();
                chunksScannedLastQuery.incrementAndGet();
                chunkRows = chunk.rowCount();
                rowInChunk = 0;
                columns = new Object[names.length];
                for (int c = 0; c < names.length; c++) {
                    columns[c] = chunk.column(names[c]);
                }
            }
        }

        @Override
        public void reset() {
            throw new UnsupportedOperationException("VortexEnumerator does not support reset");
        }

        @Override
        public void close() {
            try {
                if (chunk != null) {
                    chunk.close();
                }
            } finally {
                scan.close();
                reader.close();
            }
        }
    }

    private DType.Struct struct() {
        try (VortexReader reader = VortexReader.open(file)) {
            if (!(reader.dtype() instanceof DType.Struct s)) {
                throw new IllegalStateException("top-level Vortex dtype is not a struct: " + reader.dtype());
            }
            return s;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read schema of " + file, e);
        }
    }

    private static int[] allColumns(int n) {
        int[] all = new int[n];
        for (int i = 0; i < n; i++) {
            all[i] = i;
        }
        return all;
    }

    private static Object value(Object array, DType type, long r) {
        return switch (type) {
            case DType.Primitive p -> switch (p.ptype()) {
                case F64 -> ((DoubleArray) array).getDouble(r);
                case F32 -> (double) ((FloatArray) array).getFloat(r);
                case I64, U64 -> ((LongArray) array).getLong(r);
                // Narrow ints decode to their own array width (Byte/Short/Int), not IntArray —
                // each exposes getInt(r) with the correct sign/zero extension.
                case I32, U32 -> ((IntArray) array).getInt(r);
                case I16, U16 -> ((ShortArray) array).getInt(r);
                case I8, U8 -> ((ByteArray) array).getInt(r);
                default -> throw new IllegalStateException("unsupported ptype: " + p.ptype());
            };
            case DType.Utf8 _ -> ((VarBinArray) array).getString(r);
            case DType.Bool _ -> ((BoolArray) array).getBoolean(r);
            default -> throw new IllegalStateException("unsupported column dtype: " + type);
        };
    }

    /// Translates the Calcite predicates into a zone-map [RowFilter], keeping only the comparisons
    /// we can prune on (`=`, `<>`, `<`, `<=`, `>`, `>=` between a column and a literal, plus `AND`).
    /// Predicates we don't understand are simply not pushed — Calcite still applies them.
    private static Optional<RowFilter> toRowFilter(List<RexNode> filters, List<String> names, List<DType> types) {
        List<RowFilter> pushed = new ArrayList<>();
        for (RexNode node : filters) {
            // Calcite encodes BETWEEN / IN / range unions as SEARCH(ref, Sarg); expand back to a
            // comparison tree (>=, <=, AND, OR) before translating.
            RexNode expanded = RexUtil.expandSearch(REX_BUILDER, null, node);
            toComparison(expanded, names, types).ifPresent(pushed::add);
        }
        if (pushed.isEmpty()) {
            return Optional.empty();
        }
        if (pushed.size() == 1) {
            return Optional.of(pushed.getFirst());
        }
        return Optional.of(RowFilter.and(pushed.toArray(RowFilter[]::new)));
    }

    /// Collects every column the filter references into `out`, so the scan can include them for
    /// zone-map pruning regardless of projection.
    private static void collectColumns(RowFilter filter, java.util.Set<String> out) {
        switch (filter) {
            case RowFilter.And(var parts) -> parts.forEach(f -> collectColumns(f, out));
            case RowFilter.Eq(var col, var ignored) -> out.add(col);
            case RowFilter.Neq(var col, var ignored) -> out.add(col);
            case RowFilter.Gt(var col, var ignored) -> out.add(col);
            case RowFilter.Gte(var col, var ignored) -> out.add(col);
            case RowFilter.Lt(var col, var ignored) -> out.add(col);
            case RowFilter.Lte(var col, var ignored) -> out.add(col);
            case RowFilter.IsNull(var col) -> out.add(col);
            case RowFilter.IsNotNull(var col) -> out.add(col);
        }
    }

    private static Optional<RowFilter> toComparison(RexNode node, List<String> names, List<DType> types) {
        if (!(node instanceof RexCall call)) {
            return Optional.empty();
        }
        return switch (call.getKind()) {
            case AND -> {
                List<RowFilter> parts = new ArrayList<>();
                for (RexNode operand : call.getOperands()) {
                    toComparison(operand, names, types).ifPresent(parts::add);
                }
                yield parts.isEmpty() ? Optional.empty()
                        : Optional.of(RowFilter.and(parts.toArray(RowFilter[]::new)));
            }
            case EQUALS, NOT_EQUALS, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL ->
                    binary(call, names, types);
            default -> Optional.empty();
        };
    }

    /// Strict counterpart of [#toComparison]: the same column-vs-literal / `AND` grammar, but a
    /// single unrecognised node (or one `AND` conjunct) collapses the whole result to empty rather
    /// than being dropped. Used by [#translatePushedFilters] so aggregate push-down answers from
    /// stats only when the [RowFilter] captures the predicate in full.
    private static Optional<RowFilter> strictComparison(RexNode node, List<String> names, List<DType> types) {
        if (!(node instanceof RexCall call)) {
            return Optional.empty();
        }
        return switch (call.getKind()) {
            case AND -> {
                List<RowFilter> parts = new ArrayList<>();
                for (RexNode operand : call.getOperands()) {
                    Optional<RowFilter> part = strictComparison(operand, names, types);
                    if (part.isEmpty()) {
                        yield Optional.empty(); // one untranslatable conjunct abandons the whole predicate
                    }
                    parts.add(part.get());
                }
                yield parts.isEmpty() ? Optional.empty()
                        : Optional.of(RowFilter.and(parts.toArray(RowFilter[]::new)));
            }
            case EQUALS, NOT_EQUALS, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL ->
                    binary(call, names, types);
            default -> Optional.empty();
        };
    }

    private static Optional<RowFilter> binary(RexCall call, List<String> names, List<DType> types) {
        List<RexNode> ops = call.getOperands();
        if (ops.size() != 2 || !(ops.get(0) instanceof RexInputRef ref) || !(ops.get(1) instanceof RexLiteral lit)) {
            return Optional.empty();
        }
        Object val = literalValue(lit, types.get(ref.getIndex()));
        if (val == null) {
            return Optional.empty();
        }
        String col = names.get(ref.getIndex());
        Comparable<?> cmp = (Comparable<?>) val;
        return Optional.of(switch (call.getKind()) {
            case EQUALS -> RowFilter.eq(col, val);
            case NOT_EQUALS -> RowFilter.neq(col, val);
            case LESS_THAN -> RowFilter.lt(col, cmp);
            case LESS_THAN_OR_EQUAL -> RowFilter.lte(col, cmp);
            case GREATER_THAN -> RowFilter.gt(col, cmp);
            case GREATER_THAN_OR_EQUAL -> RowFilter.gte(col, cmp);
            default -> throw new IllegalStateException("unreachable kind: " + call.getKind());
        });
    }

    /// Coerces a SQL literal to the Java type the column's zone-map statistics are stored as, so
    /// the comparison in [RowFilter] is type-compatible. Integer scalars are stored as `Long` and
    /// floats as `Double` in the stats, regardless of the column's physical width — a mismatched
    /// boxed type silently disables pruning (the comparator swallows the `ClassCastException`).
    /// Returns `null` for unsupported columns.
    private static Object literalValue(RexLiteral lit, DType type) {
        return switch (type) {
            case DType.Utf8 _ -> lit.getValueAs(String.class);
            case DType.Primitive p -> switch (p.ptype()) {
                case F64, F32 -> lit.getValueAs(Double.class);
                case I64, U64, I32, U32, I16, U16, I8, U8 -> lit.getValueAs(Long.class);
                default -> null;
            };
            default -> null;
        };
    }

    private static RelDataType toSqlType(RelDataTypeFactory factory, DType type) {
        RelDataType sql = switch (type) {
            case DType.Primitive p -> switch (p.ptype()) {
                case F64 -> factory.createSqlType(SqlTypeName.DOUBLE);
                case F32 -> factory.createSqlType(SqlTypeName.REAL);
                case I64, U64 -> factory.createSqlType(SqlTypeName.BIGINT);
                case I32, U32 -> factory.createSqlType(SqlTypeName.INTEGER);
                case I16, U16 -> factory.createSqlType(SqlTypeName.SMALLINT);
                case I8, U8 -> factory.createSqlType(SqlTypeName.TINYINT);
                default -> throw new IllegalStateException("unsupported ptype: " + p.ptype());
            };
            case DType.Utf8 _ -> factory.createSqlType(SqlTypeName.VARCHAR);
            case DType.Bool _ -> factory.createSqlType(SqlTypeName.BOOLEAN);
            default -> throw new IllegalStateException("unsupported column dtype: " + type);
        };
        return factory.createTypeWithNullability(sql, type.nullable());
    }
}
