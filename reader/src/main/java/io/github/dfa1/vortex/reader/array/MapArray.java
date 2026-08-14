package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Decoded map array: a thin logical wrapper over its single `entries` child, a list of
/// non-nullable `{key, value}` structs.
///
/// Validity is delegated to `entries`: a null map row is a null entries row, which is why the
/// map's own nullability is carried by the entries list dtype rather than by a validity buffer
/// here. The entries child stores that validity in the list-view's own validity slot, which
/// decodes to a [MaskedArray] wrapping the [ListViewArray]; a non-nullable map's entries are a
/// bare [ListViewArray]. The accessor stays typed as [Array] so callers unwrap whichever shape
/// the file used.
public final class MapArray implements Array {

    private final DType.Map dtype;
    private final long length;
    private final Array entries;

    /// Creates a new `MapArray`.
    ///
    /// @param dtype   logical map type
    /// @param length  number of map rows
    /// @param entries the `{key, value}` entries child
    public MapArray(DType.Map dtype, long length, Array entries) {
        this.dtype = dtype;
        this.length = length;
        this.entries = entries;
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    /// Returns the entries child holding one `{key, value}` struct list per row.
    ///
    /// @return the entries array
    public Array entries() {
        return entries;
    }

    @Override
    public Array limited(long rows) {
        return new MapArray(dtype, rows, Array.limited(entries, rows));
    }

    /// Always throws: a map array is its [#entries()] child, not a single primary segment.
    /// Materialize the entries' own children separately.
    ///
    /// @param arena unused
    /// @return never returns
    /// @throws VortexException always
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        throw new VortexException("MapArray has no primary segment");
    }
}
