package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.writer.encode.ConstantEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.RowCount;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// Rust's `ConstantStrategy` (`vortex-layout/src/layouts/constant.rs`): a column whose rows all
/// hold one value is written as a single `vortex.constant` chunk, without the repartition, zone
/// map and dictionary steps the other columns go through.
///
/// A column's batches are held back while each holds one value and it is the run's value. The
/// first batch that differs releases the run and then itself to the column's normal pipeline,
/// and from then on its batches pass straight through. Only the run's first batch and its row
/// count are kept, as Rust keeps only the run's scalar and length: every row is the same, so the
/// first batch rebuilds the run at any length.
final class ConstantRunDetector {

    /// Rows per batch of a released run, so a long run is never rebuilt as one array.
    private static final int RELEASE_ROWS = 16 * Repartitioner.BLOCK_ROWS;

    // columns still held, in the order they first arrived
    private final Map<ColumnName, Run> runs;
    private final Set<ColumnName> released;

    ConstantRunDetector() {
        this.runs = new LinkedHashMap<>();
        this.released = new HashSet<>();
    }

    /// A held column: its first non-empty batch (an empty one until a row arrives), its rows so
    /// far and their one value, `null` until a row arrives.
    record Run(Object first, long rows, ProtoScalarValue scalar) {
    }

    /// The batches of `key` its normal pipeline takes now, in order: none while every row so far
    /// holds one value; the run then this batch when it holds another; this batch once released.
    ///
    /// @param key   the leaf column
    /// @param dtype its dtype
    /// @param data  its next batch
    /// @return the batches to write now
    List<Object> add(ColumnName key, DType dtype, Object data) {
        if (released.contains(key)) {
            return List.of(data);
        }
        Run run = runs.get(key);
        int rows = RowCount.of(data);
        if (rows == 0) {
            // Rust drops an empty chunk; the first stays, for a column that never gets a row
            runs.putIfAbsent(key, new Run(data, 0, null));
            return List.of();
        }
        Optional<ProtoScalarValue> scalar = ConstantEncodingEncoder.runScalar(dtype, data);
        if (scalar.isPresent() && (run == null || run.scalar() == null)) {
            runs.put(key, new Run(data, rows, scalar.get()));
            return List.of();
        }
        if (scalar.isPresent() && Arrays.equals(scalar.get().encode(), run.scalar().encode())) {
            runs.put(key, new Run(run.first(), run.rows() + rows, run.scalar()));
            return List.of();
        }
        released.add(key);
        runs.remove(key);
        List<Object> batches = release(run);
        batches.add(data);
        return batches;
    }

    /// Whether `key`'s batches now pass straight through.
    boolean isReleased(ColumnName key) {
        return released.contains(key);
    }

    /// The columns still held at the end of the file, in the order they first arrived: each is a
    /// constant column, or one that never got a row.
    Map<ColumnName, Run> held() {
        return runs;
    }

    /// A run's rows as batches of at most [#RELEASE_ROWS], or none for a run with no rows.
    private static List<Object> release(Run run) {
        List<Object> batches = new ArrayList<>();
        if (run == null || run.rows() == 0) {
            return batches;
        }
        Object full = Repartitioner.repeat(run.first(), (int) Math.min(run.rows(), RELEASE_ROWS));
        int fullRows = RowCount.of(full);
        long left = run.rows();
        while (left >= fullRows) {
            batches.add(full);
            left -= fullRows;
        }
        if (left > 0) {
            batches.add(Repartitioner.head(full, (int) left));
        }
        return batches;
    }
}
