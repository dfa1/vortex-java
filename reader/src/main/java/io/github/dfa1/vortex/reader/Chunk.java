package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.extension.DateExtension;
import io.github.dfa1.vortex.extension.ExtensionId;
import io.github.dfa1.vortex.extension.TimeExtension;
import io.github.dfa1.vortex.extension.TimestampExtension;
import io.github.dfa1.vortex.extension.UuidExtension;

import java.lang.foreign.Arena;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/// One decoded row group returned by [ScanIterator#next()].
///
/// A `Chunk` owns a confined [Arena] holding the decoded columnar buffers. The
/// [Array] views returned by [#column(String)] and [#columns()] are zero-copy
/// references into that arena (or into the underlying mmap region), valid only
/// while this `Chunk` is open.
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
    private final Map<String, Array> columns;
    private final Map<String, DType> columnDtypes;
    private final Arena arena;
    private final Consumer<Chunk> onClose;
    private boolean closed;

    Chunk(long rowCount, Map<String, Array> columns, Map<String, DType> columnDtypes,
            Arena arena, Consumer<Chunk> onClose) {
        this.rowCount = rowCount;
        this.columns = Objects.requireNonNull(columns);
        this.columnDtypes = Objects.requireNonNull(columnDtypes);
        this.arena = Objects.requireNonNull(arena);
        this.onClose = Objects.requireNonNull(onClose);
    }

    /// Number of logical rows in this chunk (after any limit truncation).
    ///
    /// @return row count of this chunk
    public long rowCount() {
        return rowCount;
    }

    /// Returns the decoded columns by name. The map is unmodifiable; values are
    /// valid only while this `Chunk` is open.
    ///
    /// @return projected columns keyed by name
    public Map<String, Array> columns() {
        return columns;
    }

    /// Looks up a column by name with a checked cast to the caller's expected
    /// [Array] subtype.
    ///
    /// @param name column name as declared in the file's [io.github.dfa1.vortex.core.DType] schema
    /// @param <T>  expected concrete [Array] subtype
    /// @return the column array
    /// @throws VortexException if no column with the given name is present in this chunk
    @SuppressWarnings("unchecked")
    public <T extends Array> T column(String name) {
        Array arr = columns.get(name);
        if (arr == null) {
            throw new VortexException("unknown column: " + name);
        }
        return (T) arr;
    }

    /// Decodes an extension column into a typed `List<T>` of domain values.
    ///
    /// Hides the {@link Extension#decodeAll} boilerplate for the four spec extensions:
    ///
    /// ```java
    /// List<LocalDate> dates = chunk.as("birthdays", LocalDate.class);
    /// List<Instant>   ts    = chunk.as("events", Instant.class);
    /// ```
    ///
    /// Third-party extensions go through {@link io.github.dfa1.vortex.encoding.Registry#lookup}
    /// + the impl's own typed methods — this accessor is closed over the spec set.
    ///
    /// @param name       column name as declared in the file's schema
    /// @param domainType the spec extension's Java domain type (e.g. {@link LocalDate} for
    ///                   {@code vortex.date}, {@link Instant} for {@code vortex.timestamp})
    /// @param <T>        domain element type
    /// @return decoded values in row order
    /// @throws VortexException if {@code name} isn't present, isn't an extension column,
    ///         or the requested {@code domainType} doesn't match the column's extension id
    @SuppressWarnings("unchecked")
    public <T> List<T> as(String name, Class<T> domainType) {
        DType colDtype = columnDtypes.get(name);
        if (colDtype == null) {
            throw new VortexException("unknown column: " + name);
        }
        if (!(colDtype instanceof DType.Extension ext)) {
            throw new VortexException("not an extension column: " + name);
        }
        ExtensionId id = ExtensionId.parse(ext.extensionId())
                .orElseThrow(() -> new VortexException("not a spec extension id: " + ext.extensionId()));
        Array storage = column(name);
        Object result = switch (id) {
            case VORTEX_DATE -> {
                requireDomainType(name, domainType, LocalDate.class);
                yield DateExtension.INSTANCE.decodeAll(storage);
            }
            case VORTEX_TIME -> {
                requireDomainType(name, domainType, LocalTime.class);
                yield TimeExtension.INSTANCE.decodeAll(ext, storage);
            }
            case VORTEX_TIMESTAMP -> {
                requireDomainType(name, domainType, Instant.class);
                yield TimestampExtension.INSTANCE.decodeAll(ext, storage);
            }
            case VORTEX_UUID -> {
                requireDomainType(name, domainType, UUID.class);
                yield UuidExtension.INSTANCE.decodeAll(storage);
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
    /// @return {@code true} if [#close()] has run
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
