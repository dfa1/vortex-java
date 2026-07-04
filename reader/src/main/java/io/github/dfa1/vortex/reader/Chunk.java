package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.reader.extension.DateExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.TimeExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.TimestampExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.UuidExtensionDecoder;

import java.lang.foreign.Arena;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.UUID;
import java.util.function.Consumer;

/// One decoded row group returned by [ScanIterator#next()].
///
/// A `Chunk` owns a confined [Arena] holding the decoded columnar buffers. The
/// [Array] views returned by [#column(String)] and [#columns()] are zero-copy
/// references into that arena (or into the underlying mmap region), valid only
/// while this `Chunk` is open.
///
/// Columns are keyed by [ColumnName] — the validated name domain the file parser
/// certifies at the read boundary, so every key here is policy-valid and unique.
/// [#column(String)] keeps a string-sugar overload for callers that hold a raw
/// name; it wraps the string in a [ColumnName] before looking it up.
///
/// **Lifecycle.** `Chunk` is [AutoCloseable]. Always wrap consumption in
/// try-with-resources:
///
/// ```java
/// try (Chunk chunk = iter.next()) {
///     DoubleArray price = chunk.column("price");
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
    private final SequencedMap<ColumnName, Column> columns;
    private final Arena arena;
    private final Consumer<Chunk> onClose;
    private boolean closed;

    Chunk(long rowCount, SequencedMap<ColumnName, Column> columns,
            Arena arena, Consumer<Chunk> onClose) {
        this.rowCount = rowCount;
        this.columns = Objects.requireNonNull(columns);
        this.arena = Objects.requireNonNull(arena);
        this.onClose = Objects.requireNonNull(onClose);
    }

    /// One decoded column: its zero-copy [Array] view and the [DType] it was declared with in the
    /// file's schema. The dtype travels with the array so extension decoding (see [#as(String, Class)])
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
        return columns;
    }

    /// Looks up a column by its raw string name with a checked cast to the caller's expected
    /// [Array] subtype. The name is validated through [ColumnName#of(String)] first, so a
    /// policy-invalid query name fails fast with the policy's [IllegalArgumentException] — it
    /// could never match a certified column anyway.
    ///
    /// @param name column name as declared in the file's [io.github.dfa1.vortex.core.model.DType] schema
    /// @param <T>  expected concrete [Array] subtype
    /// @return the column array
    /// @throws IllegalArgumentException if `name` violates the column-name policy
    /// @throws VortexException          if no column with the given name is present in this chunk
    public <T extends Array> T column(String name) {
        return column(ColumnName.of(name));
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
        Column col = columns.get(name);
        if (col == null) {
            throw new VortexException("unknown column: " + name);
        }
        return (T) col.array();
    }

    /// Decodes an extension column into a typed `List<T>` of domain values.
    ///
    /// Hides extension-decode boilerplate for the four spec extensions:
    ///
    /// ```java
    /// List<LocalDate> dates = chunk.as("birthdays", LocalDate.class);
    /// List<Instant>   ts    = chunk.as("events", Instant.class);
    /// ```
    ///
    /// Third-party extensions call the extension's own typed methods directly —
    /// this accessor is closed over the spec set.
    ///
    /// @param name       column name as declared in the file's schema
    /// @param domainType the spec extension's Java domain type (e.g. [LocalDate] for
    ///                   `vortex.date`, [Instant] for `vortex.timestamp`)
    /// @param <T>        domain element type
    /// @return decoded values in row order
    /// @throws VortexException if `name` isn't present, isn't an extension column,
    ///         or the requested `domainType` doesn't match the column's extension id
    @SuppressWarnings("unchecked")
    public <T> List<T> as(String name, Class<T> domainType) {
        Column col = columns.get(ColumnName.of(name));
        if (col == null) {
            throw new VortexException("unknown column: " + name);
        }
        if (!(col.dtype() instanceof DType.Extension ext)) {
            throw new VortexException("not an extension column: " + name);
        }
        ExtensionId id = ExtensionId.parse(ext.extensionId())
                .orElseThrow(() -> new VortexException("not a spec extension id: " + ext.extensionId()));
        Array storage = col.array();
        Object result = switch (id) {
            case VORTEX_DATE -> {
                requireDomainType(name, domainType, LocalDate.class);
                yield DateExtensionDecoder.INSTANCE.decodeAll(storage);
            }
            case VORTEX_TIME -> {
                requireDomainType(name, domainType, LocalTime.class);
                yield TimeExtensionDecoder.INSTANCE.decodeAll(ext, storage);
            }
            case VORTEX_TIMESTAMP -> {
                requireDomainType(name, domainType, Instant.class);
                yield TimestampExtensionDecoder.INSTANCE.decodeAll(ext, storage);
            }
            case VORTEX_UUID -> {
                requireDomainType(name, domainType, UUID.class);
                yield UuidExtensionDecoder.INSTANCE.decodeAll(storage);
            }
        };
        return (List<T>) result;
    }

    private static void requireDomainType(String name, Class<?> requested, Class<?> expected) {
        if (!requested.equals(expected)) {
            throw new VortexException("column '" + name + "' decodes to " + expected.getSimpleName()
                    + ", not " + requested.getSimpleName());
        }
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
        try {
            onClose.accept(this);
        } finally {
            arena.close();
        }
    }
}
