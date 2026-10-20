package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.reader.extension.ExtensionDecoder;

import java.lang.foreign.Arena;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/// One decoded row group returned by [ScanIterator#next()].
///
/// A `Chunk` owns a confined [Arena] holding the decoded columnar buffers. The
/// [Array] views returned by [#column(ColumnName)] and [#columns()] are zero-copy
/// references into that arena (or into the underlying mmap region), valid only
/// while this `Chunk` is open.
///
/// Columns are keyed by [ColumnName] — the validated name domain the file parser
/// certifies at the read boundary, so every key here is policy-valid and unique.
///
/// A column is decoded the first time it is read, by [#column(ColumnName)], [#as(ColumnName, Class)]
/// or [#columns()], so a caller that reads only some of the projected columns of a chunk never pays
/// for the others. A malformed column therefore fails when it is read, not when the chunk is returned.
///
/// **Lifecycle.** `Chunk` is [AutoCloseable]. Always wrap consumption in
/// try-with-resources:
///
/// ```java
/// try (Chunk chunk = iter.next()) {
///     DoubleArray price = chunk.column(ColumnName.of("price"));
///     ...
/// }
/// ```
///
/// After [#close()], touching any previously-returned [Array] is undefined; FFM
/// raises an [IllegalStateException] from its scope check. Calling
/// [ScanIterator#next()] while a prior `Chunk` is still open is rejected with
/// [IllegalStateException] — the previous chunk must be closed first.
public final class Chunk implements AutoCloseable {

    private final long rowCount;
    private final List<ColumnName> names;
    private final Map<ColumnName, Integer> positions;
    private final IntFunction<Column> decoder;
    /// Per column, its decoded value once read, else `null`.
    private final Column[] decoded;
    private SequencedMap<ColumnName, Column> columns;
    /// The arena this chunk releases on close, or `null` for a slice, which shares its parent's.
    private final Arena arena;
    private final ReadRegistry registry;
    private final Consumer<Chunk> onClose;
    private boolean closed;

    Chunk(long rowCount, SequencedMap<ColumnName, Column> columns,
            Arena arena, ReadRegistry registry, Consumer<Chunk> onClose) {
        this(rowCount, List.copyOf(columns.keySet()), positions(List.copyOf(columns.keySet())),
                List.copyOf(columns.values())::get, arena, registry, onClose);
    }

    /// A chunk whose column `i`, of `names`, is decoded by `decoder` the first time it is read.
    ///
    /// @param positions each of `names` to its index, shared by the chunks of one scan
    /// @param arena     the arena to release on close, or `null` for a slice of another chunk
    Chunk(long rowCount, List<ColumnName> names, Map<ColumnName, Integer> positions, IntFunction<Column> decoder,
          Arena arena, ReadRegistry registry, Consumer<Chunk> onClose) {
        this.rowCount = rowCount;
        this.names = names;
        this.positions = positions;
        this.decoder = Objects.requireNonNull(decoder);
        this.decoded = new Column[names.size()];
        this.arena = arena;
        this.registry = Objects.requireNonNull(registry);
        this.onClose = Objects.requireNonNull(onClose);
    }

    static Map<ColumnName, Integer> positions(List<ColumnName> names) {
        Map<ColumnName, Integer> positions = HashMap.newHashMap(names.size());
        for (int i = 0; i < names.size(); i++) {
            positions.put(names.get(i), i);
        }
        return positions;
    }

    /// A view of rows `[offset, offset + length)` of every column, sharing this chunk's memory
    /// with no copy. The view is valid while this chunk is open; closing the view does nothing.
    ///
    /// @param offset first row of the view
    /// @param length number of rows in the view
    /// @return the row-range view
    /// @throws IndexOutOfBoundsException if the range is not within `[0, rowCount())`
    /// @throws VortexException if a column's array type cannot be sliced
    public Chunk slice(long offset, long length) {
        Objects.checkFromIndexSize(offset, length, rowCount);
        IntFunction<Column> sliced = i -> {
            Column column = column(i);
            return new Column(ScanIterator.sliceArray(column.array(), offset, length, column.dtype()), column.dtype());
        };
        return new Chunk(length, names, positions, sliced, null, registry, _ -> { });
    }

    /// One decoded column: its zero-copy [Array] view and the [DType] it was declared with in the
    /// file's schema. The dtype travels with the array so extension decoding (see [#as(ColumnName, Class)])
    /// and dtype-aware tooling need no second lookup.
    ///
    /// @param array the decoded column values, valid only while the owning [Chunk] is open
    /// @param dtype the column's declared type from the file schema
    public record Column(Array array, DType dtype) {

        /// Binds a decoded array to its declared type.
        ///
        /// @param array the decoded column values
        /// @param dtype the column's declared type
        public Column {
            Objects.requireNonNull(array, "array");
            Objects.requireNonNull(dtype, "dtype");
        }
    }

    /// Number of logical rows in this chunk (after any limit truncation).
    ///
    /// @return row count of this chunk
    public long rowCount() {
        return rowCount;
    }

    /// Returns the decoded columns in schema (projection) order. The map is unmodifiable and
    /// preserves encounter order; each [Column] value is valid only while this `Chunk` is open.
    ///
    /// @return projected columns keyed by [ColumnName], in schema order
    public SequencedMap<ColumnName, Column> columns() {
        if (columns == null) {
            var all = LinkedHashMap.<ColumnName, Column>newLinkedHashMap(names.size());
            for (int i = 0; i < names.size(); i++) {
                all.put(names.get(i), column(i));
            }
            columns = Collections.unmodifiableSequencedMap(all);
        }
        return columns;
    }

    /// Looks up a column by its validated [ColumnName] with a checked cast to the caller's
    /// expected [Array] subtype.
    ///
    /// @param name validated column name as declared in the file's schema
    /// @param <T>  expected concrete [Array] subtype
    /// @return the column array
    /// @throws VortexException if no column with the given name is present in this chunk
    @SuppressWarnings("unchecked")
    public <T extends Array> T column(ColumnName name) {
        return (T) column(position(name)).array();
    }

    private int position(ColumnName name) {
        Integer position = positions.get(name);
        if (position == null) {
            throw new VortexException("unknown column: " + name);
        }
        return position;
    }

    private Column column(int i) {
        Column column = decoded[i];
        if (column == null) {
            if (closed) {
                throw new IllegalStateException("chunk is closed");
            }
            column = decoder.apply(i);
            decoded[i] = column;
        }
        return column;
    }

    /// Decodes an extension column into a typed `List<T>` of domain values, through the
    /// [io.github.dfa1.vortex.reader.extension.ExtensionDecoder] registered for the column's
    /// extension id on the [ReadRegistry] the file was opened with:
    ///
    /// ```java
    /// List<LocalDate> dates = chunk.as(ColumnName.of("birthdays"), LocalDate.class);
    /// List<Instant>   ts    = chunk.as(ColumnName.of("events"), Instant.class);
    /// ```
    ///
    /// @param name       column name as declared in the file's schema
    /// @param domainType the decoder's domain type (e.g. [java.time.LocalDate] for `vortex.date`)
    /// @param <T>        domain element type
    /// @return decoded values in row order
    /// @throws VortexException if `name` isn't present, isn't an extension column, has no
    ///         registered decoder, or the decoder's domain type isn't `domainType`
    @SuppressWarnings("unchecked")
    public <T> List<T> as(ColumnName name, Class<T> domainType) {
        Column col = column(position(name));
        if (!(col.dtype() instanceof DType.Extension ext)) {
            throw new VortexException("not an extension column: " + name);
        }
        ExtensionDecoder<?> decoder = registry.lookup(ExtensionId.parse(ext.extensionId()));
        if (decoder == null) {
            throw new VortexException("no extension decoder registered for " + ext.extensionId()
                    + " (column '" + name + "')");
        }
        if (!domainType.equals(decoder.domainType())) {
            throw new VortexException("column '" + name + "' decodes to " + decoder.domainType().getSimpleName()
                    + ", not " + domainType.getSimpleName());
        }
        return (List<T>) decoder.decodeAll(ext, col.array());
    }

    /// Returns whether this chunk has already been closed.
    ///
    /// @return `true` if [#close()] has run
    public boolean isClosed() {
        return closed;
    }

    /// Releases the chunk's [Arena]. After return all [Array] views obtained
    /// from this chunk become invalid. Idempotent: subsequent calls are no-ops.
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (arena == null) {
            return;
        }
        try {
            onClose.accept(this);
        } finally {
            arena.close();
        }
    }
}
