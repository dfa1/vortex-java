package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.reader.RowFilter;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.impl.AbstractTable;

import java.util.List;

/// A [VortexTable] already sorted: what `ORDER BY` with no `LIMIT` over a big table is rewritten to
/// scan, so the rows come back lazily instead of through Calcite's sort, which buffers every row of
/// the table before it returns the first (a 100M-row table ran an IDE's JDBC process out of memory).
///
/// The first rows come from a bounded top-N pass, [VortexTable#topN]. When the client reads past
/// them, the pass runs again for twice as many rows and the rows already returned are skipped: a
/// client that stops after a grid page costs one pass and holds only that page, one that reads
/// everything costs about two full passes and holds what Calcite's own sort would. Ties rank by scan
/// position, so a longer pass always starts with the rows of the shorter one.
final class VortexOrderedTable extends AbstractTable implements ScannableTable {

    /// Rows the first pass keeps: a few IDE grid pages.
    private static final int FIRST_BATCH = 1024;

    private final VortexTable table;
    private final int[] cols;
    private final RowFilter filter;
    private final List<VortexTable.SortKey> keys;
    private final RelDataType rowType;
    private final double estimatedRows;
    private final RelCollation collation;

    VortexOrderedTable(VortexTable table, int[] cols, RowFilter filter, List<VortexTable.SortKey> keys,
                       RelDataType rowType, double estimatedRows, RelCollation collation) {
        this.table = table;
        this.cols = cols;
        this.filter = filter;
        this.keys = keys;
        this.rowType = rowType;
        this.estimatedRows = estimatedRows;
        this.collation = collation;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        return rowType;
    }

    @Override
    public Statistic getStatistic() {
        // the planner's estimate for the rows the sort would have received (a filter shrinks it), so this
        // scan is costed against the plain sort over the same filtered scan on equal terms; the
        // enumerator below never uses it as a bound
        // declaring the order is what lets the planner drop the sort the query asked for: without it
        // it adds one back on top and the cheaper plain scan wins
        return Statistics.of(estimatedRows, List.of(), List.of(collation));
    }

    @Override
    public Enumerable<Object[]> scan(DataContext root) {
        return new AbstractEnumerable<>() {
            @Override
            public Enumerator<Object[]> enumerator() {
                return new Batches();
            }
        };
    }

    /// Returns the sorted rows, running a longer top-N pass whenever the current one is used up.
    private final class Batches implements Enumerator<Object[]> {

        private List<Object[]> batch = List.of();
        private int requested;
        private long returned;
        private int index = -1;
        private boolean exhausted;

        @Override
        public boolean moveNext() {
            index++;
            if (index < batch.size()) {
                return true;
            }
            if (exhausted) {
                return false;
            }
            // the first pass keeps FIRST_BATCH rows, every later one twice the last; a pass cannot
            // keep more rows than the table has
            long tableRows = (long) table.getStatistic().getRowCount().doubleValue();
            long want = requested == 0 ? FIRST_BATCH : 2L * requested;
            requested = (int) Math.min(want, Math.min(Integer.MAX_VALUE - 8L, Math.max(1, tableRows)));
            batch = table.topN(cols, filter, keys, requested);
            exhausted = batch.size() < requested || requested >= tableRows;
            // skip the rows an earlier, shorter pass already returned
            index = (int) returned;
            if (index >= batch.size()) {
                return false;
            }
            return true;
        }

        @Override
        public Object[] current() {
            returned = index + 1L;
            return batch.get(index);
        }

        @Override
        public void reset() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            batch = List.of();
        }
    }
}
