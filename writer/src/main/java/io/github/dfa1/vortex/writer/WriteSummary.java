package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ArrayStats;
import io.github.dfa1.vortex.core.model.ColumnName;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/// What a finished write produced, returned by [VortexWriter#finish()]: Rust's `WriteSummary`, so a
/// caller learns the file's size and statistics without opening it again.
///
/// @param fileSize the size of the file in bytes, trailer included
/// @param rowCount the number of rows written
/// @param columns  one entry per top-level column, in schema order
public record WriteSummary(long fileSize, long rowCount, Map<ColumnName, Column> columns) {

    /// Copies `columns`, keeping its order.
    public WriteSummary {
        columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    /// One top-level column of the written file.
    ///
    /// `stats` are the file statistics written into the footer: Rust's min, max, sum, null count
    /// and NaN count, each folded over the `writeChunk` batches. A UTF-8 or binary bound is cut to
    /// 64 bytes and then inexact (`minExact`/`maxExact` say so); a binary bound is a `byte[]`. A
    /// statistic Rust does not keep for the column's type is `null`, as is every one of a column
    /// without rows.
    ///
    /// @param compressedSize the bytes of every segment of the column: data, zone map and
    ///                       dictionary, as Rust's `compressed_column_sizes` counts them
    /// @param stats          the column's file statistics
    public record Column(long compressedSize, ArrayStats stats) {
    }
}
