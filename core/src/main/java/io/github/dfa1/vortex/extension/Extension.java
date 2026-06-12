package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;

/// Read-side contract for a Vortex extension type — pairs the wire-format
/// identity (an [ExtensionId]) with a factory for the matching [DType.Extension]
/// dtype. Behaviour-specific decode methods live on each concrete implementation,
/// not on this interface, so read callers get typed return values without casting
/// through {@code Object}.
///
/// <p>The write-side surface ({@code encodeAll}) lives on
/// {@link ExtensionEncoder} implementations in the {@code writer} module.
public interface Extension {

    /// @return the spec identity of this extension
    ExtensionId extensionId();

    /// @param nullable whether the column allows nulls
    /// @return matching {@link DType.Extension} (storage dtype + default metadata)
    DType.Extension dtype(boolean nullable);
}
