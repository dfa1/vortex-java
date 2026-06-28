package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Decoded `vortex.variant` array: semi-structured data with a logical variant dtype.
///
/// Wire format:
/// - Metadata: proto `VariantMetadataProto` with optional `shredded_dtype`.
/// - Buffers: none.
/// - Child 0: `core_storage` — logical variant array preserving the full value per row.
/// - Child 1 (optional): `shredded` — row-aligned typed array for selected paths.
public final class VariantArray implements Array {

    private final DType dtype;
    private final long length;
    private final Array coreStorage;
    private final Array shredded;

    /// Creates a new `VariantArray`.
    ///
    /// @param dtype       logical variant dtype
    /// @param length      number of rows
    /// @param coreStorage full variant storage, one entry per row
    /// @param shredded    optional typed shredded storage, or `null`
    public VariantArray(DType dtype, long length, Array coreStorage, Array shredded) {
        this.dtype = dtype;
        this.length = length;
        this.coreStorage = coreStorage;
        this.shredded = shredded;
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    @Override
    public long length() {
        return length;
    }

    /// Returns the core storage array holding the full variant value for every row.
    ///
    /// @return core storage [Array]
    public Array coreStorage() {
        return coreStorage;
    }

    /// Returns the optional typed shredded array, or `null` if absent.
    ///
    /// @return shredded [Array], or `null`
    public Array shredded() {
        return shredded;
    }

    @Override
    public Array limited(long rows) {
        return new VariantArray(dtype, rows, Array.limited(coreStorage, rows),
                shredded != null ? Array.limited(shredded, rows) : null);
    }

    /// Always throws: a variant array is core-storage plus optional shredded children,
    /// not a single primary segment. Materialize [#coreStorage()] / [#shredded()]
    /// separately.
    ///
    /// @param arena unused
    /// @return never returns
    /// @throws VortexException always
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        throw new VortexException("VariantArray has no primary segment");
    }
}
