package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.compute.Compare;
import io.github.dfa1.vortex.reader.compute.Compute;
import io.github.dfa1.vortex.reader.compute.FilteredAggregate;
import io.github.dfa1.vortex.reader.compute.Predicate;
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
/// Projection (`projects`) is honored exactly — only the requested columns are decoded and
/// returned. Filters (`filters`) that translate to a [RowFilter] are pushed into the scan for
/// *chunk skipping* via zone-map statistics, but are **left in Calcite's list** rather than
/// consumed: zone-map pruning is approximate (it drops whole chunks that cannot match, not
/// individual rows), so Calcite must still apply the predicate row-by-row for exactness. The
/// win is decoding far fewer chunks when the filter is selective on a clustered column.
public final class VortexTable extends AbstractTable implements ProjectableFilterableTable, TranslatableTable {

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
            ArrayStats stats = reader.columnStats().getOrDefault(ColumnName.of(column), ArrayStats.empty());
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
                    .getOrDefault(ColumnName.of(column), ArrayStats.empty()).nullCount();
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

    /// A fold of the rows a pushed `WHERE` filter selects, returned by
    /// [#filteredFold(RowFilter, String)]. A zone the filter selects entirely is folded from its
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
    /// [Compute#filteredAggregate(Chunk, RowFilter, String)], with no intermediate selection bitmap.
    /// The two tiers combine into one fold, so the caller answers the filtered aggregate while decoding
    /// only the boundary zones.
    ///
    /// `aggColumn` is the column whose values are reduced over the selected rows (`null` for
    /// `COUNT(*)`, which needs only the selected row count). The result is empty — abandoning to a
    /// full scan, which is always correctness-safe — when the fold cannot be trusted: an unsigned
    /// column involved (whose signed stat order [#isUnsigned] cannot classify), a zone-map zone count
    /// that does not align 1:1 with the chunks, or a boundary zone whose mask cannot be built.
    ///
    /// The fold also abandons for performance, not correctness, when there is at least one boundary
    /// zone but no fully-selected (IN) zone: the only win the fold has over a scan is folding IN zones
    /// from statistics without decoding them, so with zero IN zones it would decode exactly the chunks
    /// a scan decodes and only add the stats-table classify and mask-building overhead. The
    /// zone-map-pruned scan is the cheaper answer. An all-OUT selection (no IN, no boundary) is not
    /// this case — it folds to a correct empty aggregate with no decode and is kept.
    ///
    /// @param filter    the pushed predicate, already translated to the reader's [RowFilter]
    /// @param aggColumn the column to reduce over the selected rows, or `null` for a row-count-only
    ///                  fold
    /// @return the fold over the selected rows, or empty when it cannot be answered soundly
    public Optional<FilteredFold> filteredFold(RowFilter filter, String aggColumn) {
        java.util.LinkedHashSet<String> filterColumns = new java.util.LinkedHashSet<>();
        RexFilterTranslator.collectColumns(filter, filterColumns);
        java.util.LinkedHashSet<String> columns = new java.util.LinkedHashSet<>(filterColumns);
        if (aggColumn != null) {
            columns.add(aggColumn);
        }
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator scan = reader.scan(ScanOptions.columns(columns.toArray(String[]::new)))) {
            // Unsigned columns store stats whose boxed (signed) order disagrees with the unsigned
            // value order past the high bit, so a signed compare here could fold the wrong zones —
            // a wrong answer. Abandon the fold for any unsigned column involved and let the scan
            // (whose comparator is unsigned-aware) compute it. The boundary tier inherits this guard:
            // it never decodes an unsigned column, so its compute compare stays correct too.
            if (!(reader.dtype() instanceof DType.Struct struct)) {
                return Optional.empty();
            }
            for (String column : columns) {
                if (isUnsigned(struct, column)) {
                    return Optional.empty();
                }
            }
            // A boundary zone's row mask evaluates the filter through the compute kernel, whose
            // Compare.values uses Double.compare — where NaN sorts as the maximum. So a predicate
            // like `f >= v` (lowered to Eq OR Gt) would SELECT a NaN row, but SQL (and Calcite's own
            // row filter) treats `NaN >= v` as false. Since the boundary fold REPLACES the scan (it
            // is the final answer, with no per-row recheck), a NaN in a partially-selected float
            // filter column would be a silent wrong answer. Conservatively abandon the push-down for
            // any floating-point FILTER column and let the scan (NaN-correct) compute it. Scoped to
            // the mask columns: a floating-point AGG column is fine (NaN data sums to NaN, exactly as
            // a scan would). The proper follow-up is a SQL-NaN-correct compare in the kernel, but
            // Compare.values is shared with the tier-1 classify and copied from ScanIterator — out
            // of scope here.
            for (String column : filterColumns) {
                if (isFloating(struct, column)) {
                    return Optional.empty();
                }
            }
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

            // Classify every zone first, so the IN count is known before any boundary decode. The
            // fold's only win over a scan is folding IN zones from statistics with no decode; a
            // BOUNDARY zone is decoded exactly as a scan would decode it. So when there is at least
            // one BOUNDARY zone but no IN zone, the fold decodes the same chunks a scan does and adds
            // the stats-table classify + mask-building overhead on top — a net loss. Abandon to the
            // (zone-map-pruned) scan in that case. The all-OUT case (no IN, no BOUNDARY) is NOT this
            // case: it folds to a correct empty/zero aggregate with no decode at all, so it is kept.
            Match[] matches = new Match[zones];
            int inZones = 0;
            int boundaryZones = 0;
            for (int zone = 0; zone < zones; zone++) {
                Match match = classify(filter, zone, zoneStats, rowCounts[zone]);
                matches[zone] = match;
                if (match == Match.IN) {
                    inZones++;
                } else if (match == Match.BOUNDARY) {
                    boundaryZones++;
                }
            }
            if (boundaryZones >= 1 && inZones == 0) {
                return Optional.empty(); // every match is a boundary decode — no fold win over a scan
            }

            List<String> decodeColumns = new ArrayList<>(columns);
            Fold fold = new Fold();
            for (int zone = 0; zone < zones; zone++) {
                Match match = matches[zone];
                if (match == Match.OUT) {
                    continue;
                }
                if (match == Match.BOUNDARY) {
                    // Tier 2: a partially-selected zone. Decode this chunk (and the filter columns),
                    // build a row mask from the filter, and reduce the masked rows. The chunk index
                    // equals the zone index — the perZone.size() == zones guard above proved the
                    // zone-map rows align 1:1 with the chunks.
                    foldBoundaryZone(reader, zone, decodeColumns, filter, aggColumn, fold);
                    continue;
                }
                // Tier 1 (Match.IN): every row matches — fold the whole zone from its statistics.
                fold.keptRows += rowCounts[zone];
                if (aggColumn != null) {
                    ArrayStats stats = zoneStats.get(aggColumn).get(zone);
                    fold.addSum(stats.sum());
                    fold.addMin(stats.min());
                    fold.addMax(stats.max());
                    fold.addNulls(stats.nullCount());
                }
            }
            return Optional.of(fold.result());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot fold filtered zones of " + file, e);
        }
    }

    /// Decodes one boundary chunk and folds the aggregate over the rows the filter selects in it,
    /// contributing to the running [Fold]. The chunk is released per zone via try-with-resources, so
    /// nothing outlives the reduction.
    ///
    /// The whole filter — an n-ary `AND` of column-bound [Predicate] leaves — and the aggregate fold
    /// run in one fused pass over the chunk's rows via
    /// [Compute#filteredAggregate(Chunk, RowFilter, String)], with no intermediate selection bitmap.
    /// `SUM` is guarded: the fused kernel reports a `null` sum for a non-numeric aggregate column,
    /// which disables the sum (mirroring a zone that records no usable sum) rather than failing the
    /// whole fold — a `MIN`/`MAX` over the same column still folds. The aggregate column's null count
    /// among the selected rows is the selected count minus the non-null count.
    private static void foldBoundaryZone(VortexReader reader, int zone, List<String> columns,
                                         RowFilter filter, String aggColumn, Fold fold) {
        try (Chunk chunk = reader.decodeChunk(zone, columns)) {
            FilteredAggregate aggregate = Compute.filteredAggregate(chunk, filter, aggColumn);
            fold.keptRows += aggregate.selectedRows();
            if (aggColumn == null) {
                return;
            }
            // A null sum marks a non-numeric aggregate column — unanswerable, like a missing zone sum;
            // addSum(null) disables only the sum, leaving MIN/MAX to fold.
            fold.addSum(aggregate.sum());
            fold.addMin(aggregate.min());
            fold.addMax(aggregate.max());
            fold.addNulls(aggregate.selectedRows() - aggregate.aggNonNullCount());
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
        private boolean sumUsable = true;
        private Object min;
        private Object max;
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
            } else {
                longSum += ((Number) contribution).longValue();
            }
        }

        private void addMin(Object candidate) {
            min = pickExtreme(min, candidate, true);
        }

        private void addMax(Object candidate) {
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
            } else {
                foldedSum = longSum;
            }
            Long foldedNulls = nullCountKnown ? nullCount : null;
            return new FilteredFold(foldedSum, min, max, foldedNulls, keptRows);
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
        List<String> names = struct.fieldNames().stream().map(ColumnName::value).toList();
        List<DType> types = struct.fieldTypes();
        return RexFilterTranslator.translateStrict(filters, names, types);
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
            case RowFilter.Column(var col, var predicate) ->
                    classifyColumn(predicate, zoneStats.get(col.value()).get(zone), rowCount);
        };
    }

    /// Classifies one zone against a column-bound [Predicate] from the zone's statistics `s`. The
    /// comparison ops carry the same three-valued-logic semantics as before the [RowFilter] /
    /// [Predicate] unification: an unrecognized stat shape or a partially-overlapping zone is
    /// [Match#BOUNDARY], a zone provably outside the predicate is [Match#OUT], and a zone every row
    /// of which matches (which, for a value comparison, also requires the zone to carry no nulls) is
    /// [Match#IN]. The composite and range predicates ([Predicate.Between] / [Predicate.And] /
    /// [Predicate.Or]) cannot arise from the Calcite translation — `SEARCH` / `BETWEEN` expand to an
    /// `AND` of single-leaf columns — so they fall through to [Match#BOUNDARY], which decodes and
    /// applies the kernel mask and is therefore always correct.
    ///
    /// @param predicate the column-bound value-test
    /// @param s         the zone's statistics for that column
    /// @param rowCount  the zone's row count
    /// @return how the zone relates to the predicate
    private static Match classifyColumn(Predicate predicate, ArrayStats s, long rowCount) {
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
            case Predicate.And _ -> Match.BOUNDARY;
            case Predicate.Or _ -> Match.BOUNDARY;
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

    /// Whether `column` is an unsigned primitive, whose signed stat ordering [#compareStat] cannot
    /// safely classify (the fold abandons such columns to the scan).
    private static boolean isUnsigned(DType.Struct struct, String column) {
        int idx = struct.fieldNames().indexOf(ColumnName.of(column));
        return idx >= 0 && struct.fieldTypes().get(idx) instanceof DType.Primitive p && p.ptype().isUnsigned();
    }

    /// Whether `column` is a floating-point primitive, whose compute-kernel compare
    /// ([Compare#values(Object, Object, DType)]) sorts NaN as the maximum and so cannot soundly fold a
    /// boundary partition (the fold abandons such a filter column to the NaN-correct scan).
    private static boolean isFloating(DType.Struct struct, String column) {
        int idx = struct.fieldNames().indexOf(ColumnName.of(column));
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
    /// the zone-map stats box at the column's own width ([Integer] for an `I32` column, [Short] for
    /// `I16`, …) while a filter literal arrives as [Long]/[Double], so a same-type comparison would
    /// reject narrower columns. Signed-integer widening is exact; unsigned columns are excluded
    /// upstream ([#isUnsigned]). Strings compare lexicographically.
    ///
    /// Delegates to the reader's [Compare#values(Object, Object, DType)] — the single scalar
    /// comparator shared with zone-map pruning and the compute kernels — passing a `null` column so it
    /// takes the width-agnostic, operand-keyed branch that is byte-for-byte this method's former body.
    /// That branch is signed-only, which is exactly right here: every unsigned column is rejected
    /// upstream ([#isUnsigned], [#filteredFold]) before any [#classify] / [#pickExtreme] call reaches
    /// this helper, so no unsigned value is ever ordered through it.
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
        List<String> allNames = struct.fieldNames().stream().map(ColumnName::value).toList();

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
        Optional<RowFilter> pushed = RexFilterTranslator.toRowFilter(filters, allNames, struct.fieldTypes());

        // The scan must include any column the filter prunes on, even when it is not projected —
        // chunk pruning reads that column's zone-map stats. Output still emits only outNames.
        java.util.LinkedHashSet<String> scanColumns = new java.util.LinkedHashSet<>(List.of(outNames));
        ScanOptions options;
        if (pushed.isPresent()) {
            RexFilterTranslator.collectColumns(pushed.get(), scanColumns);
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
                return new VortexEnumerator(file, chunksScannedLastQuery, scanOptions, outNames, outTypes);
            }
        };
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

    /// Maps a Vortex dtype to a Calcite SQL type. Unsigned integers map to the next wider signed
    /// SQL type so the declared type can hold their full range (`U8`->`SMALLINT`, `U16`->`INTEGER`,
    /// `U32`->`BIGINT`); `U64` is the exception — no wider SQL integer exists, so it maps to
    /// `BIGINT` and [VortexEnumerator] guards the high-half values that cannot fit.
    ///
    /// @param factory the Calcite type factory
    /// @param type    the Vortex column dtype
    /// @return the nullable-adjusted SQL type
    private static RelDataType toSqlType(RelDataTypeFactory factory, DType type) {
        RelDataType sql = switch (type) {
            case DType.Primitive p -> switch (p.ptype()) {
                case F64 -> factory.createSqlType(SqlTypeName.DOUBLE);
                case F32 -> factory.createSqlType(SqlTypeName.REAL);
                case I64, U64, U32 -> factory.createSqlType(SqlTypeName.BIGINT);
                case I32, U16 -> factory.createSqlType(SqlTypeName.INTEGER);
                case I16, U8 -> factory.createSqlType(SqlTypeName.SMALLINT);
                case I8 -> factory.createSqlType(SqlTypeName.TINYINT);
                default -> throw new IllegalStateException("unsupported ptype: " + p.ptype());
            };
            case DType.Utf8 _ -> factory.createSqlType(SqlTypeName.VARCHAR);
            case DType.Bool _ -> factory.createSqlType(SqlTypeName.BOOLEAN);
            default -> throw new IllegalStateException("unsupported column dtype: " + type);
        };
        return factory.createTypeWithNullability(sql, type.nullable());
    }
}
