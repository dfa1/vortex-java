package io.github.dfa1.vortex.scan;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;

import java.lang.foreign.Arena;
import java.util.Map;
import java.util.Objects;
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
    private final Arena arena;
    private final Consumer<Chunk> onClose;
    private boolean closed;

    Chunk(long rowCount, Map<String, Array> columns, Arena arena, Consumer<Chunk> onClose) {
        this.rowCount = rowCount;
        this.columns = Objects.requireNonNull(columns);
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
