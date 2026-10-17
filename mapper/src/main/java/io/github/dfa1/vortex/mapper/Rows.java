package io.github.dfa1.vortex.mapper;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/// Reads a Vortex file as a stream of objects, one per row, built by a mapper over [Row].
///
/// Projection is the reading model: list the columns you need in
/// [ScanOptions#columns(ColumnName...)] and only those are decoded, however wide the file. The
/// mapper addresses them by position in that list:
///
/// ```java
/// record PriceOnly(double price) {}
///
/// try (var reader = VortexReader.open(path);
///      Stream<PriceOnly> prices = Rows.stream(reader,
///              ScanOptions.columns(ColumnName.of("price")),
///              row -> new PriceOnly(row.getDouble(0)))) {
///     ...
/// }
/// ```
public final class Rows {

    private Rows() {
    }

    /// Streams the rows `options` selects, mapped through `mapper`. The scan runs lazily as the
    /// stream is consumed, one chunk at a time; close the stream to release the scan (the reader
    /// itself stays open). The limit and filter in `options` apply as in [VortexReader#scan].
    ///
    /// @param reader  the file to read
    /// @param options the projection, and optionally a limit and a filter; with no projection the
    ///                row holds every column in file order
    /// @param mapper  builds one object from a [Row]; must not keep the row
    /// @param <T>     the element type
    /// @return the mapped rows, in file order
    /// @throws IllegalArgumentException if a projected column is not in the file
    public static <T> Stream<T> stream(VortexReader reader, ScanOptions options,
            Function<? super Row, ? extends T> mapper) {
        List<ColumnName> projection = options.columns();
        // the scan skips a projected name the file lacks; fail instead of shifting every index after it
        if (reader.dtype() instanceof DType.Struct schema) {
            for (ColumnName column : projection) {
                if (!schema.fieldNames().contains(column)) {
                    throw new IllegalArgumentException("no column '" + column + "' in file, which has "
                            + schema.fieldNames());
                }
            }
        }
        ScanIterator scan = reader.scan(options);
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(scan, Spliterator.ORDERED), false)
                .flatMap(chunk -> Rows.<T>map(chunk, projection, mapper).stream())
                .onClose(scan::close);
    }

    // Maps the whole chunk before closing it, so the scan can move on and no row outlives its arena.
    private static <T> List<T> map(Chunk chunk, List<ColumnName> projection,
            Function<? super Row, ? extends T> mapper) {
        try (chunk) {
            List<ColumnName> columns = projection.isEmpty() ? List.copyOf(chunk.columns().keySet()) : projection;
            Row row = new Row(chunk, columns);
            long n = chunk.rowCount();
            List<T> out = new ArrayList<>(Math.toIntExact(n));
            for (long i = 0; i < n; i++) {
                row.moveTo(i);
                out.add(mapper.apply(row));
            }
            return out;
        }
    }
}
