package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ExtensionId;

import java.util.Collection;

/// Write-side contract for a Vortex extension type.
///
/// Implementations pair a spec identity ([ExtensionId]) with the matching
/// [DType.Extension] dtype and a polymorphic [#encodeAll] entry point used
/// by the writer's auto-routing path.
public interface ExtensionEncoder {

    /// @return the spec identity of this extension
    ExtensionId extensionId();

    /// @param nullable whether the column allows nulls
    /// @return matching [DType.Extension] (storage dtype + default metadata)
    DType.Extension dtype(boolean nullable);

    /// Polymorphic encode: converts a collection of domain values into the raw
    /// storage-array shape the writer accepts (e.g. `int[]`, `long[]`,
    /// `byte[]`). Concrete implementations cast `values` to their
    /// domain type and may consult `dtype.metadata()` (e.g. `TimeUnit`).
    ///
    /// @param dtype  declared extension dtype (carries unit/timezone metadata)
    /// @param values domain-typed values to encode
    /// @return packed storage array
    Object encodeAll(DType.Extension dtype, Collection<?> values);
}
