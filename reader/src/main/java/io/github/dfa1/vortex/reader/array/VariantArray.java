package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

/// Decoded {@code vortex.variant} array: semi-structured data with a logical variant dtype.
///
/// Wire format:
/// - Metadata: proto {@code VariantMetadataProto} with optional {@code shredded_dtype}.
/// - Buffers: none.
/// - Child 0: {@code core_storage} — logical variant array preserving the full value per row.
/// - Child 1 (optional): {@code shredded} — row-aligned typed array for selected paths.
public final class VariantArray implements Array {

    private final DType dtype;
    private final long length;
    private final Array coreStorage;
    private final Array shredded;

    /// Creates a new {@code VariantArray}.
    ///
    /// @param dtype       logical variant dtype
    /// @param length      number of rows
    /// @param coreStorage full variant storage, one entry per row
    /// @param shredded    optional typed shredded storage, or {@code null}
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
    /// @return core storage {@link Array}
    public Array coreStorage() {
        return coreStorage;
    }

    /// Returns the optional typed shredded array, or {@code null} if absent.
    ///
    /// @return shredded {@link Array}, or {@code null}
    public Array shredded() {
        return shredded;
    }
}
