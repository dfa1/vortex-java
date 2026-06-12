package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.util.Collection;

/// Write-side surface of an extension. Exposes the spec identity, the matching
/// {@link DType.Extension}, and the polymorphic {@link #encodeAll} entry point used
/// by the writer's auto-routing path.
///
/// <p>ADR 0001 Phase 5: {@link Extension} extends this interface so existing
/// implementations satisfy it unchanged. Read-only consumers (Inspector, the scan
/// path's extension wrapping in {@code Chunk.as(...)}) can type-narrow to
/// {@link ExtensionEncoder}'s parent interface {@link Extension} without depending
/// on encoder logic; once each spec extension is lifted into a standalone
/// encoder living in {@code writer}, this interface becomes the canonical
/// write-side type and the bifunctional {@link Extension} interface goes away.
public interface ExtensionEncoder {

    /// @return the spec identity of this extension
    ExtensionId extensionId();

    /// @param nullable whether the column allows nulls
    /// @return matching {@link DType.Extension} (storage dtype + default metadata)
    DType.Extension dtype(boolean nullable);

    /// Polymorphic encode: converts a collection of domain values into the raw
    /// storage-array shape the writer accepts (e.g. {@code int[]}, {@code long[]},
    /// {@code byte[]}). Concrete implementations cast {@code values} to their
    /// domain type and may consult {@code dtype.metadata()} (e.g. {@code TimeUnit}).
    ///
    /// @param dtype  declared extension dtype (carries unit/timezone metadata)
    /// @param values domain-typed values to encode
    /// @return packed storage array
    /// @throws VortexException by default; implementations must override to support writes
    default Object encodeAll(DType.Extension dtype, Collection<?> values) {
        throw new VortexException("encode not supported for " + extensionId());
    }
}
