package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.compute.LongIntMap;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.Zone;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinDictArray;
import io.github.dfa1.vortex.reader.array.VarBinOffsetArray;
import io.github.dfa1.vortex.reader.array.VarBinSlicedArray;
import io.github.dfa1.vortex.reader.compute.Compare;
import io.github.dfa1.vortex.reader.compute.Compute;
import io.github.dfa1.vortex.reader.compute.FilteredAggregate;
import io.github.dfa1.vortex.reader.compute.Predicate;
import io.github.dfa1.vortex.reader.compute.RowSelection;
import io.github.dfa1.vortex.reader.compute.ZoneReducer;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.ProjectableFilterableTable;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/// A single Vortex file exposed to Calcite as a flat SQL table with column projection and
/// zone-map filter push-down.
///
/// Projection (`projects`) is honored exactly — only the requested columns are decoded and
/// returned. Every filter (`filters`) that translates to a [RowFilter] is pushed into the scan for
/// *chunk skipping* via zone-map statistics (approximate — it drops whole chunks that cannot
/// match, not individual rows). A filter captured *in full* by the translation is additionally
/// enforced row-by-row in the returned enumerator (via the `NaN`-correct
/// [Compute#matches(io.github.dfa1.vortex.reader.Chunk, RowFilter, long)]) and removed from
/// Calcite's own `filters` list, so Calcite does not also re-check it; a filter the translation
/// only partially captures is left in `filters` for Calcite's own row check. The win is decoding
/// far fewer chunks when the filter is selective on a clustered column, and — for a fully captured
/// filter — not re-evaluating it a second time over the chunks that are decoded.
///
/// The schema and row count are read from the footer on first use and cached:
/// a Vortex file is written once, so they cannot change under the table.
public final class VortexTable extends AbstractTable implements ProjectableFilterableTable, TranslatableTable {

    private final Path file;
    private final AtomicLong chunksScannedLastQuery = new AtomicLong();
    // Read lazily so a missing file fails at query time, as before; a racing first read only
    // reads the footer twice, so no lock.
    private volatile Footer footer;

    /// The footer facts every planning call needs, read once instead of reopening the file for each
    /// `getRowType`, `scan` and statistic. Column statistics stay out: folding them walks every
    /// column's zones, which only the aggregate rule needs.
    private record Footer(DType.Struct struct, long totalRows) {
    }

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
    public ColumnStats statsAndRows(ColumnName column) {
        long totalRows = footer().totalRows();
        try (VortexReader reader = VortexReader.open(file)) {
            return new ColumnStats(reader.columnStats().getOrDefault(column, ArrayStats.empty()), totalRows);
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
    public ZoneSum zoneSum(ColumnName column) {
        long totalRows = footer().totalRows();
        try (VortexReader reader = VortexReader.open(file)) {
            Number sum = new ZoneReducer(reader).sum(column);
            Long nullCount = reader.columnStats()
                    .getOrDefault(column, ArrayStats.empty()).nullCount();
            return new ZoneSum(sum, nullCount, totalRows);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read zone sum of " + file, e);
        }
    }

    /// Total row count across all chunks, read from chunk metadata without decoding data.
    ///
    /// @return the number of rows in the file
    public long totalRows() {
        return footer().totalRows();
    }

    /// Gives the planner the file's row count, so join ordering and costing see the real table
    /// size instead of Calcite's default guess.
    ///
    /// @return a statistic carrying the row count, with no declared keys
    @Override
    public Statistic getStatistic() {
        return Statistics.of(footer().totalRows(), List.of());
    }

    private Footer footer() {
        Footer f = footer;
        if (f == null) {
            f = readFooter();
            footer = f;
        }
        return f;
    }

    private Footer readFooter() {
        try (VortexReader reader = VortexReader.open(file)) {
            if (!(reader.dtype() instanceof DType.Struct s)) {
                throw new IllegalStateException("top-level Vortex dtype is not a struct: " + reader.dtype());
            }
            return new Footer(s, countRows(reader));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the footer of " + file, e);
        }
    }

    /// Sums the chunk row counts of `reader` from chunk metadata, without decoding data.
    private static long countRows(VortexReader reader) throws IOException {
        long total = 0;
        try (ScanIterator scan = reader.scan(ScanOptions.all())) {
            for (long c : scan.chunkRowCounts()) {
                total += c;
            }
        }
        return total;
    }

    /// A fold of the rows a pushed `WHERE` filter selects, returned by
    /// [#filteredFold(RowFilter, ColumnName)]. A zone the filter selects entirely is folded from its
    /// statistics with no decode; a boundary zone the filter only partially selects is decoded and
    /// reduced under a row-level selection mask (ADR 0013 §6 tier-2). The aggregate rule reads the
    /// field its aggregate needs; the values cover exactly the rows the filter matches, so the fold
    /// answers `SUM`/`COUNT`/`MIN`/`MAX` over the filtered table — decoding only the boundary zones.
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

    /// Folds an aggregate over the rows a pushed `WHERE` `filter` selects, in two tiers (ADR 0013 §6).
    /// A zone the filter selects entirely (or excludes entirely) is folded from — or skipped via — its
    /// footer statistics with no decode; a boundary zone the filter only partially selects is decoded
    /// for the aggregate and filter columns and reduced in one fused pass — the whole filter and the
    /// aggregate fold evaluated together via
    /// [Compute#filteredAggregate(Chunk, RowFilter, ColumnName)], with no intermediate selection bitmap.
    /// The two tiers combine into one fold, so the caller answers the filtered aggregate while decoding
    /// only the boundary zones.
    ///
    /// `aggColumn` is the column whose values are reduced over the selected rows (`null` for
    /// `COUNT(*)`, which needs only the selected row count). The result is empty — abandoning to a
    /// full scan, which is always correctness-safe — when the fold cannot be trusted: a `U64`
    /// column involved (whose stat order [#isU64] cannot classify — see the guard below), columns
    /// whose zones lie on different rows, zones that do not tile the chunks' rows, or a boundary
    /// zone whose columns cannot be sliced.
    ///
    /// Zones need not line up with chunks: Rust's `vortex.zoned` writes fixed 8192-row zones while a
    /// chunk often spans many of them, so a boundary zone is folded from just its rows of the chunk
    /// (or two chunks) holding it.
    ///
    /// The fold also abandons for performance, not correctness, when there is at least one boundary
    /// zone but no fully-selected (IN) zone: the only win the fold has over a scan is folding IN zones
    /// from statistics without decoding them, so with zero IN zones it would decode exactly the chunks
    /// a scan decodes and only add the stats-table classify overhead. The
    /// zone-map-pruned scan is the cheaper answer. An all-OUT selection (no IN, no boundary) is not
    /// this case — it folds to a correct empty aggregate with no decode and is kept.
    ///
    /// @param filter    the pushed predicate, already translated to the reader's [RowFilter]
    /// @param aggColumn the column to reduce over the selected rows, or `null` for a row-count-only
    ///                  fold
    /// @return the fold over the selected rows, or empty when it cannot be answered soundly
    public Optional<FilteredFold> filteredFold(RowFilter filter, ColumnName aggColumn) {
        java.util.LinkedHashSet<ColumnName> filterColumns = new java.util.LinkedHashSet<>();
        RexFilterTranslator.collectColumns(filter, filterColumns);
        java.util.LinkedHashSet<ColumnName> columns = new java.util.LinkedHashSet<>(filterColumns);
        if (aggColumn != null) {
            columns.add(aggColumn);
        }
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator scan = reader.scan(ScanOptions.columns(columns.toArray(ColumnName[]::new)))) {
            // U8/U16/U32 zone-map stats zero-extend into a non-negative Long when read
            // (ScanIterator#boxedScalar), so compareStat's width-agnostic long compare already
            // orders them correctly against a filter literal (also a zero-extended Long) — no
            // guard needed. U64 has no wider box to zero-extend into: its stat is the raw 64-bit
            // pattern, and a value past 2^63 compares wrong under compareStat's plain (non
            // unsigned-aware) long compare. Abandon the fold for any U64 column involved and let
            // the scan (whose comparator is unsigned-aware over the real row data) compute it. The
            // boundary tier's own row-level compute already handles every unsigned width correctly
            // (FusedFilterAggregate's sign-flip range compare), independent of this guard.
            if (!(reader.dtype() instanceof DType.Struct struct)) {
                return Optional.empty();
            }
            for (ColumnName column : columns) {
                if (isU64(struct, column)) {
                    return Optional.empty();
                }
            }
            long[] chunkRows = scan.chunkRowCounts();
            // Every column must place its zones on the same rows, so one zone index classifies
            // the whole filter. Rust's vortex.zoned uses fixed zones independent of the chunks;
            // the legacy vortex.stats and the no-zone-map fallback have one zone per chunk.
            List<Zone> geometry = null;
            java.util.Map<ColumnName, List<ArrayStats>> zoneStats = new java.util.HashMap<>();
            for (ColumnName column : columns) {
                List<Zone> zones = zonesOf(scan, column, chunkRows);
                if (zones == null || geometry != null && !sameRows(geometry, zones)) {
                    return Optional.empty();
                }
                geometry = zones;
                zoneStats.put(column, zones.stream().map(Zone::stats).toList());
            }
            if (!tiles(geometry, chunkRows)) {
                return Optional.empty(); // zones and chunks disagree on the rows
            }

            // Classify every zone first, so the IN count is known before any boundary decode. The
            // fold's only win over a scan is folding IN zones from statistics with no decode, so
            // when there is at least one BOUNDARY zone but no IN zone, abandon to the
            // (zone-map-pruned) scan. The all-OUT case (no IN, no BOUNDARY) folds to a correct
            // empty/zero aggregate with no decode at all, so it is kept.
            Match[] matches = new Match[geometry.size()];
            int inZones = 0;
            int boundaryZones = 0;
            for (int z = 0; z < geometry.size(); z++) {
                Match match = classify(filter, z, zoneStats, geometry.get(z).rowCount(), struct);
                matches[z] = match;
                if (match == Match.IN) {
                    inZones++;
                } else if (match == Match.BOUNDARY) {
                    boundaryZones++;
                }
            }
            if (boundaryZones >= 1 && inZones == 0) {
                return Optional.empty(); // every match is a boundary decode — no fold win over a scan
            }

            List<ColumnName> decodeColumns = new ArrayList<>(columns);
            Fold fold = new Fold();
            try (BoundaryChunks chunks = new BoundaryChunks(reader, decodeColumns, chunkRows)) {
                for (int z = 0; z < geometry.size(); z++) {
                    Zone zone = geometry.get(z);
                    if (matches[z] == Match.OUT) {
                        continue;
                    }
                    if (matches[z] == Match.BOUNDARY) {
                        // Tier 2: fold the rows the filter selects, from the zone's rows of the
                        // chunks holding it — a zone may straddle a chunk edge.
                        if (!chunks.fold(zone, filter, aggColumn, fold)) {
                            return Optional.empty();
                        }
                        continue;
                    }
                    // Tier 1 (Match.IN): every row matches — fold the whole zone from its statistics.
                    fold.keptRows += zone.rowCount();
                    if (aggColumn != null) {
                        ArrayStats stats = zoneStats.get(aggColumn).get(z);
                        // a zone with values but no min/max stat (a string zone holds only bounds)
                        // leaves the extreme unknown, rather than silently ignored
                        boolean hasValues = stats.nullCount() == null || stats.nullCount() < zone.rowCount();
                        fold.addSum(stats.sum());
                        fold.addMin(stats.min(), hasValues);
                        fold.addMax(stats.max(), hasValues);
                        fold.addNulls(stats.nullCount());
                    }
                }
            }
            return Optional.of(fold.result());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot fold filtered zones of " + file, e);
        }
    }

    /// One group's fold, returned by [#groupedFold(ColumnName, RowFilter, Set, int)].
    ///
    /// @param rows    the rows of the group the filter selects
    /// @param columns per aggregate column, its fold over those rows
    public record GroupFold(long rows, Map<ColumnName, FilteredFold> columns) {
    }

    /// Folds aggregates per value of a dictionary-encoded string, boolean or integer column over the
    /// rows `filter` selects, without building a row per record: each chunk
    /// goes through [Compute#groupedAggregate(Chunk, ColumnName, RowFilter, ColumnName)] and the
    /// chunks' results merge per group value.
    ///
    /// The result is empty, leaving the query to the scan, when the kernel declines a chunk (a string
    /// column not dictionary-encoded there, too many groups in it, an aggregate column with no long
    /// or double fold) or there are more than `maxGroups` groups in all.
    ///
    /// @param group      the column to group by
    /// @param filter     the pushed predicate, or `null` for every row
    /// @param aggColumns the columns the aggregates reduce; empty when only rows are counted
    /// @param maxGroups  most groups to answer; the scan stops as soon as it sees more
    /// @return the fold per group value (`String`, `Boolean` or `Long`; `null` is the null group), or
    ///         empty
    public Optional<Map<Object, GroupFold>> groupedFold(ColumnName group, RowFilter filter, Set<ColumnName> aggColumns,
                                                        int maxGroups) {
        java.util.LinkedHashSet<ColumnName> columns = new java.util.LinkedHashSet<>();
        columns.add(group);
        if (filter != null) {
            RexFilterTranslator.collectColumns(filter, columns);
        }
        columns.addAll(aggColumns);
        ScanOptions options = ScanOptions.columns(columns.toArray(ColumnName[]::new));
        if (filter != null) {
            options = options.withFilter(filter);
        }
        // one pass per aggregate column (a null column counts rows only)
        List<ColumnName> passes = aggColumns.isEmpty() ? java.util.Collections.singletonList(null)
                : List.copyOf(aggColumns);
        Map<Object, long[]> rows = new java.util.HashMap<>();
        Map<Object, Map<ColumnName, Fold>> byGroup = new java.util.HashMap<>();
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator scan = reader.scan(options)) {
            while (scan.hasNext()) {
                try (Chunk chunk = scan.next()) {
                    for (int p = 0; p < passes.size(); p++) {
                        ColumnName aggColumn = passes.get(p);
                        Optional<Map<Object, FilteredAggregate>> part =
                                Compute.groupedAggregate(chunk, group, filter, aggColumn);
                        if (part.isEmpty()) {
                            return Optional.empty();
                        }
                        for (Map.Entry<Object, FilteredAggregate> entry : part.get().entrySet()) {
                            FilteredAggregate aggregate = entry.getValue();
                            if (p == 0) {
                                rows.computeIfAbsent(entry.getKey(), k -> new long[1])[0] += aggregate.selectedRows();
                            }
                            if (aggColumn != null) {
                                Fold fold = byGroup.computeIfAbsent(entry.getKey(), k -> new java.util.HashMap<>())
                                        .computeIfAbsent(aggColumn, k -> new Fold());
                                fold.keptRows += aggregate.selectedRows();
                                // the kernel's min/max are null only when no selected row has a value
                                fold.addSum(aggregate.sum());
                                fold.addMin(aggregate.min(), false);
                                fold.addMax(aggregate.max(), false);
                                fold.addNulls(aggregate.selectedRows() - aggregate.aggNonNullCount());
                            }
                        }
                    }
                    if (rows.size() > maxGroups) {
                        return Optional.empty();
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot fold groups of " + file, e);
        }
        Map<Object, GroupFold> result = new java.util.HashMap<>();
        rows.forEach((key, count) -> {
            Map<ColumnName, FilteredFold> columnFolds = new java.util.HashMap<>();
            byGroup.getOrDefault(key, Map.of()).forEach((column, fold) -> columnFolds.put(column, fold.result()));
            result.put(key, new GroupFold(count[0], Map.copyOf(columnFolds)));
        });
        return Optional.of(java.util.Collections.unmodifiableMap(result));
    }

    /// The dtype of `column`.
    DType columnType(ColumnName column) {
        DType.Struct struct = struct();
        return struct.fieldTypes().get(struct.fieldNames().indexOf(column));
    }

    /// `COUNT(DISTINCT column)` over the rows `filter` selects: the number of distinct non-null
    /// values, counted without a boxed value per row. Integer and temporal storage, and doubles,
    /// are counted by their raw bits in a primitive [LongIntMap] (for doubles the same equality as
    /// `Double.equals`, which Calcite's own `DISTINCT` uses); a dictionary-encoded string column
    /// marks the codes a chunk uses and decodes each once; other strings go through a set.
    ///
    /// @param column the column to count distinct values of
    /// @param filter the rows to count over, or `null` for every row
    /// @return the count, or empty for a column type this does not count (decimals, binary, nested)
    public Optional<Long> distinctCount(ColumnName column, RowFilter filter) {
        java.util.LinkedHashSet<ColumnName> columns = new java.util.LinkedHashSet<>();
        columns.add(column);
        ScanOptions options;
        if (filter == null) {
            options = ScanOptions.columns(column);
        } else {
            RexFilterTranslator.collectColumns(filter, columns);
            options = ScanOptions.columns(columns.toArray(ColumnName[]::new)).withFilter(filter);
        }
        LongIntMap bits = new LongIntMap(1024);
        java.util.Set<String> strings = new java.util.HashSet<>();
        java.util.function.Function<Chunk, RowSelection> matcher =
                filter == null ? null : Compute.matcher(filter);
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator scan = reader.scan(options)) {
            while (scan.hasNext()) {
                try (Chunk chunk = scan.next()) {
                    java.util.function.LongPredicate selected = matcher == null ? null : matcher.apply(chunk);
                    Array array = chunk.column(column);
                    BoolArray validity = array instanceof MaskedArray masked ? masked.validity() : null;
                    Array data = array instanceof MaskedArray masked ? masked.inner() : array;
                    long n = chunk.rowCount();
                    if (!countChunk(data, validity, selected, n, bits, strings)) {
                        return Optional.empty();
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot count distinct values of " + column + " in " + file, e);
        }
        return Optional.of((long) bits.size() + strings.size());
    }

    /// Adds one chunk's distinct values; `false` for a column type [#distinctCount] does not count.
    private static boolean countChunk(Array data, BoolArray validity, java.util.function.LongPredicate selected,
                                      long n, LongIntMap bits, java.util.Set<String> strings) {
        java.util.function.LongUnaryOperator bitsOf = switch (data) {
            case LongArray a -> a::getLong;
            case IntArray a -> a::getInt;
            case ShortArray a -> a::getShort;
            case ByteArray a -> a::getByte;
            case DoubleArray a -> i -> Double.doubleToLongBits(a.getDouble(i));
            case FloatArray a -> i -> Double.doubleToLongBits(a.getFloat(i));
            case BoolArray a -> i -> a.getBoolean(i) ? 1 : 0;
            default -> null;
        };
        if (bitsOf != null) {
            for (long i = 0; i < n; i++) {
                if ((selected == null || selected.test(i)) && (validity == null || validity.getBoolean(i))) {
                    bits.add(bitsOf.applyAsLong(i));
                }
            }
            return true;
        }
        return distinctStrings(data, validity, selected, n, strings::add);
    }

    /// Passes each distinct non-null value of the selected rows of a string chunk to `sink`. A
    /// dictionary-encoded chunk marks the codes its rows use and decodes each once, instead of a
    /// `String` per row; any other string chunk passes every row's value.
    ///
    /// @return `false` when `data` is not a string array
    private static boolean distinctStrings(Array data, BoolArray validity, java.util.function.LongPredicate selected,
                                           long n, java.util.function.Consumer<String> sink) {
        VarBinDictArray dict = data instanceof VarBinDictArray d ? d
                : data instanceof VarBinSlicedArray(var _, var _, VarBinDictArray d, var _) ? d : null;
        if (dict != null && data.dtype() instanceof DType.Utf8) {
            long offset = data instanceof VarBinSlicedArray sliced ? sliced.offset() : 0;
            VarBinOffsetArray values = dict.values();
            java.util.BitSet used = new java.util.BitSet();
            for (long i = 0; i < n; i++) {
                if ((selected == null || selected.test(i)) && (validity == null || validity.getBoolean(i))) {
                    long code = dict.code(i + offset);
                    if (code < 0 || code >= values.length()) {
                        throw new VortexException("dict code " + code + " out of range for "
                                + values.length() + " dictionary entries");
                    }
                    used.set((int) code);
                }
            }
            used.stream().forEach(code -> sink.accept(values.getString(code)));
            return true;
        }
        if (data instanceof VarBinArray varBin && data.dtype() instanceof DType.Utf8) {
            for (long i = 0; i < n; i++) {
                if ((selected == null || selected.test(i)) && (validity == null || validity.getBoolean(i))) {
                    sink.accept(varBin.getString(i));
                }
            }
            return true;
        }
        return false;
    }

    /// `SUM`/`MIN`/`MAX`/null count of a decimal column over the rows `filter` selects, totalled as
    /// unscaled longs ([DecimalArray#getUnscaledLong(long)]) instead of a `BigDecimal` per row. Zone
    /// statistics carry no decimal sum, so without this every decimal `SUM` was left to Calcite.
    ///
    /// @param column the decimal column
    /// @param filter the rows to fold, or `null` for every row
    /// @return the fold, or empty for a precision past 18 or a sum past a long, which Calcite totals
    public Optional<FilteredFold> decimalFold(ColumnName column, RowFilter filter) {
        if (!(columnType(column) instanceof DType.Decimal decimal) || decimal.precision() > 18) {
            return Optional.empty();
        }
        java.util.LinkedHashSet<ColumnName> columns = new java.util.LinkedHashSet<>();
        columns.add(column);
        ScanOptions options;
        if (filter == null) {
            options = ScanOptions.columns(column);
        } else {
            RexFilterTranslator.collectColumns(filter, columns);
            options = ScanOptions.columns(columns.toArray(ColumnName[]::new)).withFilter(filter);
        }
        long sum = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long rows = 0;
        long nonNull = 0;
        java.util.function.Function<Chunk, RowSelection> matcher =
                filter == null ? null : Compute.matcher(filter);
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator scan = reader.scan(options)) {
            while (scan.hasNext()) {
                try (Chunk chunk = scan.next()) {
                    java.util.function.LongPredicate selected = matcher == null ? null : matcher.apply(chunk);
                    Array array = chunk.column(column);
                    BoolArray validity = array instanceof MaskedArray masked ? masked.validity() : null;
                    DecimalArray data = (DecimalArray) (array instanceof MaskedArray masked ? masked.inner() : array);
                    long n = chunk.rowCount();
                    for (long i = 0; i < n; i++) {
                        if (selected != null && !selected.test(i)) {
                            continue;
                        }
                        rows++;
                        if (validity != null && !validity.getBoolean(i)) {
                            continue;
                        }
                        long v = data.getUnscaledLong(i);
                        sum = Math.addExact(sum, v);
                        min = Math.min(min, v);
                        max = Math.max(max, v);
                        nonNull++;
                    }
                }
            }
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot sum " + column + " in " + file, e);
        }
        int scale = decimal.scale();
        return Optional.of(new FilteredFold(java.math.BigDecimal.valueOf(sum, scale),
                nonNull == 0 ? null : java.math.BigDecimal.valueOf(min, scale),
                nonNull == 0 ? null : java.math.BigDecimal.valueOf(max, scale), rows - nonNull, rows));
    }

    /// One `ORDER BY` key of [#topN(int[], RowFilter, List, int)].
    ///
    /// @param field      the key's position in the projected row
    /// @param descending whether larger values come first
    /// @param nullsFirst whether nulls come before every value
    public record SortKey(int field, boolean descending, boolean nullsFirst) {
    }

    /// The first `count` rows of the projection `cols` over the rows `filter` selects, in the order of
    /// `keys`, as the scan would emit them (Calcite's internal value forms). Only the key columns are
    /// read per row, and each value is compared as itself: the sort Calcite runs otherwise converts
    /// every number to a `BigDecimal` per comparison. A row is read in full only if it is still among
    /// the first `count` when its chunk ends, so a column sorted against the query, where every row
    /// beats the last, costs `count` full rows per chunk instead of one per row.
    ///
    /// @param cols   the table columns to emit, by position
    /// @param filter the rows to keep, or `null` for every row
    /// @param keys   the `ORDER BY` keys, most significant first
    /// @param count  how many rows to return
    /// @return the first `count` rows in order (fewer if the table has fewer)
    public List<Object[]> topN(int[] cols, RowFilter filter, List<SortKey> keys, int count) {
        if (count <= 0) {
            return List.of();
        }
        java.util.Comparator<Object[]> order = rowOrder(keys);
        // equal keys rank by scan position: Calcite's sort is stable, so ties come out in scan order
        java.util.Comparator<Candidate> ranking = java.util.Comparator.<Candidate, Object[]>comparing(c -> c.row, order)
                .thenComparingLong(c -> c.position);
        // ponytail: rows are cut back to count whenever this slack fills, by a sort that TimSort makes
        // linear on the ordered runs a sorted column gives; capped so a huge count does not double memory
        int capacity = count + Math.max(1024, Math.min(count, 1 << 20));
        List<Candidate> kept = new ArrayList<>();
        List<Candidate> fromChunk = new ArrayList<>();
        // the worst row kept once there are count of them: a row equal to it or worse cannot enter, as a
        // later row never beats an equal key
        Object[] worst = null;
        Object[] probe = new Object[cols.length];
        long position = 0;
        try (VortexEnumerator rows = enumerator(cols, filter)) {
            while (rows.nextChunk()) {
                while (rows.advanceInChunk()) {
                    for (SortKey key : keys) {
                        probe[key.field()] = rows.column(key.field());
                    }
                    if (worst != null && order.compare(probe, worst) >= 0) {
                        position++;
                        continue;
                    }
                    Candidate candidate = new Candidate(probe.clone(), position++, rows.rowInChunk());
                    kept.add(candidate);
                    fromChunk.add(candidate);
                    if (kept.size() == capacity) {
                        worst = cut(kept, ranking, count);
                        fromChunk.removeIf(c -> c.rowInChunk < 0);
                    }
                }
                worst = cut(kept, ranking, count);
                // the survivors are read in full while their chunk is still open
                for (Candidate c : fromChunk) {
                    if (c.rowInChunk >= 0) {
                        for (int i = 0; i < cols.length; i++) {
                            c.row[i] = rows.column(i, c.rowInChunk);
                        }
                        c.rowInChunk = -1;
                    }
                }
                fromChunk.clear();
            }
        }
        return kept.stream().map(c -> c.row).toList();
    }

    /// Sorts `kept` and drops all but the first `count`, marking the dropped ones so they are never read.
    ///
    /// @return the worst row kept when there are `count` of them, else `null`
    private static Object[] cut(List<Candidate> kept, java.util.Comparator<Candidate> ranking, int count) {
        kept.sort(ranking);
        if (kept.size() > count) {
            List<Candidate> dropped = kept.subList(count, kept.size());
            dropped.forEach(c -> c.rowInChunk = -1);
            dropped.clear();
        }
        return kept.size() == count ? kept.getLast().row : null;
    }

    /// A row [#topN(int[], RowFilter, List, int)] keeps: its key columns until it is read in full.
    private static final class Candidate {
        private final Object[] row;
        private final long position;
        /// Its row in the open chunk while only its keys are read, `-1` once read in full or dropped.
        private long rowInChunk;

        Candidate(Object[] row, long position, long rowInChunk) {
            this.row = row;
            this.position = position;
            this.rowInChunk = rowInChunk;
        }
    }

    /// The first `count` distinct rows of the projection `cols` over the rows `filter` selects, in the
    /// order of `keys`: `SELECT DISTINCT ... ORDER BY ... LIMIT`, without hashing every row of the table.
    /// Rows equal on `keys` come out in the order of their other columns, one of the orders SQL allows.
    ///
    /// @param cols   the table columns to emit, by position
    /// @param filter the rows to keep, or `null` for every row
    /// @param keys   the `ORDER BY` keys, most significant first
    /// @param count  how many distinct rows to return
    /// @return the first `count` distinct rows in order (fewer if the table has fewer)
    public List<Object[]> distinctTopN(int[] cols, RowFilter filter, List<SortKey> keys, int count) {
        if (count <= 0) {
            return List.of();
        }
        // every column takes part, so two rows compare equal exactly when they are duplicates
        List<SortKey> all = new ArrayList<>(keys);
        for (int i = 0; i < cols.length; i++) {
            int field = i;
            if (keys.stream().noneMatch(k -> k.field() == field)) {
                all.add(new SortKey(i, false, false));
            }
        }
        java.util.Comparator<Object[]> order = rowOrder(all);
        if (cols.length == 1 && struct().fieldTypes().get(cols[0]) instanceof DType.Utf8) {
            List<Object[]> strings = distinctTopNStrings(cols[0], filter, order, count);
            if (strings != null) {
                return strings;
            }
        }
        java.util.TreeSet<Object[]> kept = new java.util.TreeSet<>(order);
        Object[] probe = new Object[cols.length];
        try (VortexEnumerator rows = enumerator(cols, filter)) {
            while (rows.advance()) {
                for (int i = 0; i < cols.length; i++) {
                    probe[i] = rows.column(i);
                }
                if (offer(kept, order, probe, count)) {
                    probe = new Object[cols.length];
                }
            }
        }
        return List.copyOf(kept);
    }

    /// [#distinctTopN(int[], RowFilter, List, int)] of one string column, comparing each chunk's
    /// distinct values ([#distinctStrings]) instead of every row: `SELECT DISTINCT isin ... LIMIT 1`
    /// over 100M rows otherwise built and compared 100M strings to find 5000 values.
    ///
    /// @return the rows, or `null` when a chunk of the column is not a string array
    private List<Object[]> distinctTopNStrings(int col, RowFilter filter, java.util.Comparator<Object[]> order,
                                               int count) {
        ColumnName column = struct().fieldNames().get(col);
        java.util.LinkedHashSet<ColumnName> columns = new java.util.LinkedHashSet<>();
        columns.add(column);
        ScanOptions options = ScanOptions.columns(column);
        if (filter != null) {
            RexFilterTranslator.collectColumns(filter, columns);
            options = ScanOptions.columns(columns.toArray(ColumnName[]::new)).withFilter(filter);
        }
        java.util.TreeSet<Object[]> kept = new java.util.TreeSet<>(order);
        java.util.function.Function<Chunk, RowSelection> matcher =
                filter == null ? null : Compute.matcher(filter);
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator scan = reader.scan(options)) {
            chunksScannedLastQuery.set(0);
            while (scan.hasNext()) {
                try (Chunk chunk = scan.next()) {
                    chunksScannedLastQuery.incrementAndGet();
                    java.util.function.LongPredicate selected = matcher == null ? null : matcher.apply(chunk);
                    Array array = chunk.column(column);
                    BoolArray validity = array instanceof MaskedArray masked ? masked.validity() : null;
                    Array data = array instanceof MaskedArray masked ? masked.inner() : array;
                    long n = chunk.rowCount();
                    if (!distinctStrings(data, validity, selected, n, value -> offer(kept, order, new Object[]{value}, count))) {
                        return null;
                    }
                    if (validity != null && hasSelectedNull(validity, selected, n)) {
                        offer(kept, order, new Object[]{null}, count);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot scan " + file, e);
        }
        return List.copyOf(kept);
    }

    private static boolean hasSelectedNull(BoolArray validity, java.util.function.LongPredicate selected, long n) {
        for (long i = 0; i < n; i++) {
            if (!validity.getBoolean(i) && (selected == null || selected.test(i))) {
                return true;
            }
        }
        return false;
    }

    /// Adds `row` to `kept` unless it is a duplicate or ranks past the first `count`, then trims
    /// `kept` back to `count`.
    ///
    /// @return whether `kept` now holds `row`, so the caller must not reuse it
    private static boolean offer(java.util.TreeSet<Object[]> kept, java.util.Comparator<Object[]> order,
                                 Object[] row, int count) {
        if (kept.size() == count && order.compare(row, kept.last()) >= 0) {
            return false;
        }
        if (!kept.add(row)) {
            return false;
        }
        if (kept.size() > count) {
            kept.pollLast();
        }
        return true;
    }

    /// A scan of the projection `cols` over the rows `filter` selects, also decoding the columns the
    /// filter reads.
    private VortexEnumerator enumerator(int[] cols, RowFilter filter) {
        DType.Struct struct = struct();
        ColumnName[] outNames = new ColumnName[cols.length];
        DType[] outTypes = new DType[cols.length];
        for (int i = 0; i < cols.length; i++) {
            outNames[i] = struct.fieldNames().get(cols[i]);
            outTypes[i] = struct.fieldTypes().get(cols[i]);
        }
        java.util.LinkedHashSet<ColumnName> scanColumns = new java.util.LinkedHashSet<>(List.of(outNames));
        ScanOptions options;
        if (filter == null) {
            options = ScanOptions.columns(scanColumns.toArray(ColumnName[]::new));
        } else {
            RexFilterTranslator.collectColumns(filter, scanColumns);
            options = ScanOptions.columns(scanColumns.toArray(ColumnName[]::new)).withFilter(filter);
        }
        return new VortexEnumerator(file, chunksScannedLastQuery, options, outNames, outTypes, filter);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static java.util.Comparator<Object[]> rowOrder(List<SortKey> keys) {
        return (a, b) -> {
            for (SortKey key : keys) {
                Comparable x = (Comparable) a[key.field()];
                Comparable y = (Comparable) b[key.field()];
                int cmp;
                if (x == null || y == null) {
                    cmp = x == y ? 0 : (x == null) == key.nullsFirst() ? -1 : 1;
                } else {
                    cmp = key.descending() ? y.compareTo(x) : x.compareTo(y);
                }
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        };
    }

    /// Decodes the chunks that hold boundary zones, each once, in row order, and folds a zone's rows
    /// through a [Chunk#slice(long, long)] view. Zones arrive in row order, so only the chunk under
    /// the current zone is kept open.
    private static final class BoundaryChunks implements AutoCloseable {
        private final VortexReader reader;
        private final List<ColumnName> columns;
        private final long[] chunkRows;
        private int index = -1;
        private long chunkStart;
        private Chunk chunk;

        BoundaryChunks(VortexReader reader, List<ColumnName> columns, long[] chunkRows) {
            this.reader = reader;
            this.columns = columns;
            this.chunkRows = chunkRows;
        }

        /// Folds the rows `filter` selects in `zone`, in one fused pass per chunk part via
        /// [Compute#filteredAggregate(Chunk, RowFilter, ColumnName)]. `SUM` is guarded: the kernel
        /// reports a `null` sum for a non-numeric aggregate column, which disables only the sum.
        ///
        /// @return `false` when a column cannot be sliced, so the caller abandons to the scan
        boolean fold(Zone zone, RowFilter filter, ColumnName aggColumn, Fold fold) {
            long row = zone.firstRow();
            long end = row + zone.rowCount();
            while (row < end) {
                moveTo(row);
                long offset = row - chunkStart;
                long length = Math.min(end, chunkStart + chunkRows[index]) - row;
                FilteredAggregate aggregate;
                try (Chunk part = chunk.slice(offset, length)) {
                    aggregate = Compute.filteredAggregate(part, filter, aggColumn);
                } catch (VortexException e) {
                    // ponytail: some array types (F16, variant) have no slice yet; the scan answers
                    return false;
                }
                fold.keptRows += aggregate.selectedRows();
                if (aggColumn != null) {
                    // the kernel's min/max are null only when no selected row has a value
                    fold.addSum(aggregate.sum());
                    fold.addMin(aggregate.min(), false);
                    fold.addMax(aggregate.max(), false);
                    fold.addNulls(aggregate.selectedRows() - aggregate.aggNonNullCount());
                }
                row += length;
            }
            return true;
        }

        /// Opens the chunk holding `row`; `tiles` proved every row lies in some chunk.
        private void moveTo(long row) {
            if (chunk != null && row < chunkStart + chunkRows[index]) {
                return;
            }
            if (chunk != null) {
                chunk.close();
                chunk = null;
            }
            if (index < 0) {
                index = 0;
            }
            while (row >= chunkStart + chunkRows[index]) {
                chunkStart += chunkRows[index];
                index++;
            }
            chunk = reader.decodeChunk(index, columns);
        }

        @Override
        public void close() {
            if (chunk != null) {
                chunk.close();
            }
        }
    }

    /// Mutable accumulator for a filtered fold, shared by the whole-zone (tier 1) and boundary-zone
    /// (tier 2) paths so both contribute to one running result. Integer sums fold into a [Long]
    /// (wrapping like SQL `SUM(BIGINT)`), floating sums into a [Double]; a missing or unanswerable
    /// component disables only that component, never the whole fold.
    private static final class Fold {
        private long longSum;
        private double doubleSum;
        private boolean floatingSum;
        private java.math.BigDecimal decimalSum;
        private boolean sumUsable = true;
        private Object min;
        private Object max;
        private boolean extremesKnown = true;
        private long nullCount;
        private boolean nullCountKnown = true;
        private long keptRows;

        /// Adds a zone's or chunk's sum contribution; a `null` contribution marks the sum
        /// unanswerable (a zone that retained no usable sum). Accepts [Object] because a zone's
        /// `ArrayStats.sum()` is boxed as one, while the boundary tier passes a [Number].
        private void addSum(Object contribution) {
            if (!sumUsable) {
                return;
            }
            if (contribution == null) {
                sumUsable = false;
            } else if (contribution instanceof Double d) {
                floatingSum = true;
                doubleSum += d;
            } else if (contribution instanceof java.math.BigDecimal d) {
                decimalSum = decimalSum == null ? d : decimalSum.add(d);
            } else {
                longSum += ((Number) contribution).longValue();
            }
        }

        /// Adds a min candidate; a `null` one from rows that do hold values makes the min unknown.
        private void addMin(Object candidate, boolean hasValues) {
            if (candidate == null && hasValues) {
                extremesKnown = false;
            }
            min = pickExtreme(min, candidate, true);
        }

        private void addMax(Object candidate, boolean hasValues) {
            if (candidate == null && hasValues) {
                extremesKnown = false;
            }
            max = pickExtreme(max, candidate, false);
        }

        /// Adds a null-count contribution; a `null` contribution marks the null count unknown.
        private void addNulls(Long contribution) {
            if (!nullCountKnown) {
                return;
            }
            if (contribution == null) {
                nullCountKnown = false;
            } else {
                nullCount += contribution;
            }
        }

        private FilteredFold result() {
            Number foldedSum;
            if (!sumUsable) {
                foldedSum = null;             // a contributing zone carried no usable sum
            } else if (floatingSum) {
                foldedSum = doubleSum;
            } else if (decimalSum != null) {
                foldedSum = decimalSum;
            } else {
                foldedSum = longSum;
            }
            Long foldedNulls = nullCountKnown ? nullCount : null;
            // an unknown extreme is reported as absent, which the rule cannot mistake for an empty
            // selection while rows were kept
            return new FilteredFold(foldedSum, extremesKnown ? min : null, extremesKnown ? max : null,
                    foldedNulls, keptRows);
        }
    }

    /// Translates the predicates a `WHERE` pushed onto the scan into one [RowFilter], **strictly**:
    /// returns empty if any predicate (or any conjunct of one) is not a column-vs-literal
    /// comparison or `AND` of them. The strictness is the correctness guard for aggregate
    /// push-down — answering from stats is only sound when the [RowFilter] captures the *whole*
    /// predicate, so a single untranslatable conjunct must abandon the rewrite rather than silently
    /// drop a filter (as the best-effort [RexFilterTranslator#toRowFilter] does for the scan path, where Calcite still
    /// re-checks every row).
    ///
    /// @param filters the predicates Calcite pushed onto the scan
    /// @return the conjoined filter, or empty when any predicate is not fully translatable
    public Optional<RowFilter> translatePushedFilters(List<RexNode> filters) {
        DType.Struct struct = struct();
        List<ColumnName> names = struct.fieldNames();
        List<DType> types = struct.fieldTypes();
        return RexFilterTranslator.translateStrict(filters, names, types);
    }

    /// A column's zones: its zone map when the zones can be placed on rows, else one zone per chunk
    /// from the chunks' own statistics (the legacy `vortex.stats` table with batches of mixed
    /// length, or no zone map at all). `null` when neither lines up with the chunks.
    private static List<Zone> zonesOf(ScanIterator scan, ColumnName column, long[] chunkRows) {
        List<Zone> zones = scan.columnZones(column);
        if (!zones.isEmpty()) {
            return zones;
        }
        List<ArrayStats> perChunk = scan.columnZoneStats(column);
        if (perChunk.size() != chunkRows.length) {
            return null;
        }
        List<Zone> out = new ArrayList<>(chunkRows.length);
        long first = 0;
        for (int c = 0; c < chunkRows.length; c++) {
            out.add(new Zone(first, chunkRows[c], perChunk.get(c)));
            first += chunkRows[c];
        }
        return out;
    }

    private static boolean sameRows(List<Zone> a, List<Zone> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).firstRow() != b.get(i).firstRow() || a.get(i).rowCount() != b.get(i).rowCount()) {
                return false;
            }
        }
        return true;
    }

    /// Whether `zones` cover the chunks' rows exactly, in order and with no gap or overlap.
    private static boolean tiles(List<Zone> zones, long[] chunkRows) {
        long next = 0;
        for (Zone zone : zones) {
            if (zone.firstRow() != next) {
                return false;
            }
            next += zone.rowCount();
        }
        long total = 0;
        for (long rows : chunkRows) {
            total += rows;
        }
        return next == total;
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
                                  java.util.Map<ColumnName, List<ArrayStats>> zoneStats, long rowCount,
                                  DType.Struct struct) {
        return switch (filter) {
            case RowFilter.And(var parts) -> {
                boolean allIn = true;
                for (RowFilter part : parts) {
                    Match m = classify(part, zone, zoneStats, rowCount, struct);
                    if (m == Match.OUT) {
                        yield Match.OUT; // one conjunct excludes the whole zone
                    }
                    if (m == Match.BOUNDARY) {
                        allIn = false;
                    }
                }
                yield allIn ? Match.IN : Match.BOUNDARY;
            }
            case RowFilter.Column(var col, var predicate) -> classifyColumn(predicate,
                    zoneStats.get(col).get(zone), rowCount, isFloating(struct, col));
        };
    }

    /// Classifies one zone against a column-bound [Predicate] from the zone's statistics `s`,
    /// downgrading an otherwise-[Match#IN] result to [Match#BOUNDARY] for a `floating` column: the
    /// zone-map stats (min/max/null count) cannot reveal a hidden `NaN` value — `NaN` is not `null`,
    /// so it never shows up in the null count — and a `NaN` row must never satisfy a value
    /// comparison (IEEE 754), so an "every row matches" verdict drawn from stats alone cannot be
    /// trusted for such a column. Downgrading to [Match#BOUNDARY] routes it through the boundary
    /// tier's row-level mask instead, which — via [PredicateEvaluator] / [PrimitiveFilter] — is
    /// `NaN`-correct. [Match#OUT] stays trustworthy either way: it already requires stats that
    /// disprove a match outright, which a hidden `NaN` cannot undermine (see [#classifyColumnRaw]).
    ///
    /// @param predicate the column-bound value-test
    /// @param s         the zone's statistics for that column
    /// @param rowCount  the zone's row count
    /// @param floating  whether the column is a floating-point primitive
    /// @return how the zone relates to the predicate
    private static Match classifyColumn(Predicate predicate, ArrayStats s, long rowCount, boolean floating) {
        Match match = classifyColumnRaw(predicate, s, rowCount);
        return floating && match == Match.IN ? Match.BOUNDARY : match;
    }

    /// The comparison ops carry the same three-valued-logic semantics as before the [RowFilter] /
    /// [Predicate] unification: an unrecognized stat shape or a partially-overlapping zone is
    /// [Match#BOUNDARY], a zone provably outside the predicate is [Match#OUT], and a zone every row
    /// of which matches (which, for a value comparison, also requires the zone to carry no nulls) is
    /// [Match#IN]. A single-column [Predicate.And] / [Predicate.Or] (an `IN` list, a range union)
    /// combines its sides' verdicts; [Predicate.Between] cannot arise from the Calcite translation
    /// and stays [Match#BOUNDARY], which decodes and is therefore always correct.
    ///
    /// @param predicate the column-bound value-test
    /// @param s         the zone's statistics for that column
    /// @param rowCount  the zone's row count
    /// @return how the zone relates to the predicate, before the floating-column IN downgrade
    private static Match classifyColumnRaw(Predicate predicate, ArrayStats s, long rowCount) {
        return switch (predicate) {
            case Predicate.Eq(var value) -> {
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
            case Predicate.Neq(var value) -> {
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
            case Predicate.Gt(var value) -> compare(s, value,
                    (st, v) -> compareStat(st.max(), v) <= 0, (st, v) -> compareStat(st.min(), v) > 0);
            case Predicate.Gte(var value) -> compare(s, value,
                    (st, v) -> compareStat(st.max(), v) < 0, (st, v) -> compareStat(st.min(), v) >= 0);
            case Predicate.Lt(var value) -> compare(s, value,
                    (st, v) -> compareStat(st.min(), v) >= 0, (st, v) -> compareStat(st.max(), v) < 0);
            case Predicate.Lte(var value) -> compare(s, value,
                    (st, v) -> compareStat(st.min(), v) > 0, (st, v) -> compareStat(st.max(), v) <= 0);
            case Predicate.IsNull _ -> {
                Long nulls = s.nullCount();
                if (nulls == null) {
                    yield Match.BOUNDARY;
                }
                if (nulls == rowCount) {
                    yield Match.IN;
                }
                yield nulls == 0 ? Match.OUT : Match.BOUNDARY;
            }
            case Predicate.IsNotNull _ -> {
                Long nulls = s.nullCount();
                if (nulls == null) {
                    yield Match.BOUNDARY;
                }
                if (nulls == 0) {
                    yield Match.IN;
                }
                yield nulls == rowCount ? Match.OUT : Match.BOUNDARY;
            }
            case Predicate.Between _ -> Match.BOUNDARY;
            case Predicate.And(var left, var right) -> {
                Match l = classifyColumnRaw(left, s, rowCount);
                Match r = classifyColumnRaw(right, s, rowCount);
                if (l == Match.OUT || r == Match.OUT) {
                    yield Match.OUT;
                }
                yield l == Match.IN && r == Match.IN ? Match.IN : Match.BOUNDARY;
            }
            case Predicate.Or(var left, var right) -> {
                Match l = classifyColumnRaw(left, s, rowCount);
                Match r = classifyColumnRaw(right, s, rowCount);
                if (l == Match.IN || r == Match.IN) {
                    yield Match.IN;
                }
                yield l == Match.OUT && r == Match.OUT ? Match.OUT : Match.BOUNDARY;
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

    /// Whether `column` is `U64` — the one unsigned width with no wider box to zero-extend into, so
    /// its zone-map stat stays the raw 64-bit pattern and [#compareStat] cannot safely classify it
    /// (the fold abandons such columns to the scan). `U8`/`U16`/`U32` zero-extend into a
    /// non-negative `Long` when read ([ScanIterator#columnZoneStats(ColumnName)]) and classify
    /// correctly, so they are not included here.
    private static boolean isU64(DType.Struct struct, ColumnName column) {
        int idx = struct.fieldNames().indexOf(column);
        return idx >= 0 && struct.fieldTypes().get(idx) instanceof DType.Primitive p && p.ptype() == PType.U64;
    }

    /// Whether `column` is a floating-point primitive — used by [#classifyColumn(Predicate,
    /// ArrayStats, long, boolean)] to downgrade an otherwise-[Match#IN] zone to [Match#BOUNDARY]:
    /// zone-map stats cannot reveal a hidden `NaN` (it is not `null`, so the null count misses it
    /// too), and a `NaN` row must never satisfy a value comparison.
    private static boolean isFloating(DType.Struct struct, ColumnName column) {
        int idx = struct.fieldNames().indexOf(column);
        return idx >= 0 && struct.fieldTypes().get(idx) instanceof DType.Primitive p && p.ptype().isFloating();
    }

    /// Whether a zone's min/max are missing, or are a different kind of value than `value` (one
    /// numeric, the other a string) — either way the zone cannot be classified against `value`, so
    /// the caller must treat it as a boundary.
    private static boolean uncomparable(ArrayStats s, Object value) {
        return s.min() == null || s.max() == null || value == null
                || value instanceof Number != s.min() instanceof Number;
    }

    /// Whether the zone provably carries no nulls (its null count is recorded and zero).
    private static boolean noNulls(ArrayStats s) {
        return s.nullCount() != null && s.nullCount() == 0;
    }

    /// Compares two scalar stat/filter values by their natural order, widening across boxed widths:
    /// the zone-map stats box at the column's own width ([Integer] for a signed `I32` column,
    /// [Short] for `I16`, a zero-extended [Long] for any `U8`/`U16`/`U32` column, …) while a filter
    /// literal arrives as [Long]/[Double], so a same-type comparison would reject narrower columns.
    /// Signed-integer widening is exact; `U8`/`U16`/`U32` are already zero-extended non-negative
    /// `Long`s, so a plain long compare against the (also zero-extended) filter literal is exact
    /// too. `U64` is excluded upstream ([#isU64]) since it has no wider box to zero-extend into.
    /// Strings compare lexicographically.
    ///
    /// Delegates to the reader's [Compare#values(Object, Object, DType)] — the single scalar
    /// comparator shared with zone-map pruning and the compute kernels — passing a `null` column so it
    /// takes the width-agnostic, operand-keyed branch that is byte-for-byte this method's former body.
    /// That branch is signed-only, which is exactly right here: `U64` is rejected upstream
    /// ([#isU64], [#filteredFold]) before any [#classify] / [#pickExtreme] call reaches this helper,
    /// and every other value already arrives in a representation a plain signed compare orders
    /// correctly.
    private static int compareStat(Object a, Object b) {
        return Compare.values(a, b, null);
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
            builder.add(struct.fieldNames().get(i).value(), toSqlType(typeFactory, struct.fieldTypes().get(i)));
        }
        return builder.build();
    }

    @Override
    public Enumerable<Object[]> scan(DataContext root, List<RexNode> filters, int[] projects) {
        DType.Struct struct = struct();
        List<ColumnName> allNames = struct.fieldNames();
        List<DType> allTypes = struct.fieldTypes();

        // Projection: the columns to decode and emit, in the order Calcite asked for. A null
        // projects array means "all columns".
        int[] cols = projects != null ? projects : allColumns(allNames.size());
        ColumnName[] outNames = new ColumnName[cols.length];
        DType[] outTypes = new DType[cols.length];
        for (int i = 0; i < cols.length; i++) {
            outNames[i] = allNames.get(cols[i]);
            outTypes[i] = allTypes.get(cols[i]);
        }

        // Filter push-down for chunk-skip: every predicate we understand, applied leniently (an
        // untranslatable conjunct is just dropped rather than abandoning the whole rewrite).
        Optional<RowFilter> pushed = RexFilterTranslator.toRowFilter(filters, allNames, allTypes);

        // Exact push-down: a top-level predicate captured *in full* is enforced row-by-row below
        // (via Compute#matcher, which lowers it per chunk through the NaN-correct PrimitiveFilter /
        // PredicateEvaluator) and removed from Calcite's own re-check list, so Calcite stops
        // redundantly re-evaluating rows this scan has already excluded — Calcite's
        // TableScanNode#createProjectableFilterable wraps a `.where()` residual check around
        // whatever is still left in `filters` after this call returns.
        List<RowFilter> exactParts = new ArrayList<>();
        java.util.Iterator<RexNode> it = filters.iterator();
        while (it.hasNext()) {
            RexNode node = it.next();
            Optional<RowFilter> exact = RexFilterTranslator.translateStrict(List.of(node), allNames, allTypes);
            if (exact.isPresent()) {
                exactParts.add(exact.get());
                it.remove();
            }
        }
        RowFilter exactFilter = exactParts.isEmpty() ? null
                : exactParts.size() == 1 ? exactParts.getFirst()
                : RowFilter.and(exactParts.toArray(RowFilter[]::new));

        // The scan must decode any column a filter needs — pruning or exact enforcement — even
        // when it is not projected. Output still emits only outNames.
        java.util.LinkedHashSet<ColumnName> scanColumns = new java.util.LinkedHashSet<>(List.of(outNames));
        pushed.ifPresent(f -> RexFilterTranslator.collectColumns(f, scanColumns));
        if (exactFilter != null) {
            RexFilterTranslator.collectColumns(exactFilter, scanColumns);
        }
        ScanOptions options = ScanOptions.columns(scanColumns.toArray(ColumnName[]::new));
        if (pushed.isPresent()) {
            options = options.withFilter(pushed.get());
        }

        // Stream rows lazily: decode one chunk at a time and yield a fresh row, so rows die young
        // (in G1's young gen) instead of piling a whole-result List<Object[]> into the old gen — the
        // dominant cost an async-profiler run showed for the full-scan path (~72% in GC).
        ScanOptions scanOptions = options;
        RowFilter enumeratorFilter = exactFilter;
        return new AbstractEnumerable<>() {
            @Override
            public Enumerator<Object[]> enumerator() {
                return new VortexEnumerator(file, chunksScannedLastQuery, scanOptions, outNames, outTypes,
                        enumeratorFilter);
            }
        };
    }

    private DType.Struct struct() {
        return footer().struct();
    }

    private static int[] allColumns(int n) {
        int[] all = new int[n];
        for (int i = 0; i < n; i++) {
            all[i] = i;
        }
        return all;
    }

    /// Maps a Vortex dtype to a Calcite SQL type. Unsigned integers map to the next wider signed
    /// SQL type so the declared type can hold their full range (`U8`->`SMALLINT`, `U16`->`INTEGER`,
    /// `U32`->`BIGINT`); `U64` is the exception — no wider SQL integer exists, so it maps to
    /// `BIGINT` and [VortexEnumerator] guards the high-half values that cannot fit.
    ///
    /// `vortex.timestamp` is a `TIMESTAMP` holding the stored value as a UTC wall clock, zone or
    /// not: Avatica shifts `TIMESTAMP WITH LOCAL TIME ZONE` by the JVM's zone in its JDBC getters,
    /// so a zoned instant would read back wrong. An extension with no SQL counterpart reads as its storage
    /// type. Nested types (list, struct, map, union, variant) map to `ANY` so the rest of the table
    /// stays queryable; selecting one fails in [VortexEnumerator].
    ///
    /// @param factory the Calcite type factory
    /// @param type    the Vortex column dtype
    /// @return the nullable-adjusted SQL type
    private static RelDataType toSqlType(RelDataTypeFactory factory, DType type) {
        RelDataType sql = switch (type) {
            case DType.Primitive p -> switch (p.ptype()) {
                case F64 -> factory.createSqlType(SqlTypeName.DOUBLE);
                // SQL has no half-precision type: an F16 widens exactly into REAL, like F32
                case F32, F16 -> factory.createSqlType(SqlTypeName.REAL);
                case I64, U64, U32 -> factory.createSqlType(SqlTypeName.BIGINT);
                case I32, U16 -> factory.createSqlType(SqlTypeName.INTEGER);
                case I16, U8 -> factory.createSqlType(SqlTypeName.SMALLINT);
                case I8 -> factory.createSqlType(SqlTypeName.TINYINT);
            };
            case DType.Utf8 _ -> factory.createSqlType(SqlTypeName.VARCHAR);
            case DType.Binary _ -> factory.createSqlType(SqlTypeName.VARBINARY);
            case DType.Bool _ -> factory.createSqlType(SqlTypeName.BOOLEAN);
            case DType.Null _ -> factory.createSqlType(SqlTypeName.NULL);
            case DType.Decimal d -> factory.createSqlType(SqlTypeName.DECIMAL, d.precision(), d.scale());
            case DType.Extension ext -> switch (ExtensionId.parse(ext.extensionId())) {
                case ExtensionId.WellKnown.VORTEX_DATE -> factory.createSqlType(SqlTypeName.DATE);
                // millisecond precision, the precision the scan delivers: at Calcite's default of 0 a
                // literal built for the column (a pushed-down MIN/MAX, a top-N row) lost its millis
                case ExtensionId.WellKnown.VORTEX_TIME -> factory.createSqlType(SqlTypeName.TIME, 3);
                case ExtensionId.WellKnown.VORTEX_TIMESTAMP -> factory.createSqlType(SqlTypeName.TIMESTAMP, 3);
                case ExtensionId.WellKnown.VORTEX_UUID -> factory.createSqlType(SqlTypeName.UUID);
                default -> toSqlType(factory, ext.storageDType());
            };
            case DType.List _, DType.FixedSizeList _, DType.Struct _, DType.Map _, DType.Union _,
                 DType.Variant _ -> factory.createSqlType(SqlTypeName.ANY);
        };
        return factory.createTypeWithNullability(sql, type.nullable());
    }
}
