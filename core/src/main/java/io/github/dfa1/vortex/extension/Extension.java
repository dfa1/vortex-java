package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;

/// Contract for a Vortex extension type — pairs the wire-format identity
/// (an [ExtensionId]) with a factory for the matching [DType.Extension]
/// dtype. Behaviour-specific encode/decode methods live on each concrete
/// implementation, not on this interface, so callers get typed return
/// values without casting through {@code Object}.
public interface Extension {

    /// Returns the spec identity of this extension.
    ///
    /// @return canonical extension id
    ExtensionId extensionId();

    /// Returns the DType describing a column of this extension type.
    ///
    /// @param nullable whether the column allows nulls
    /// @return matching [DType.Extension] (storage dtype + default metadata)
    DType.Extension dtype(boolean nullable);
}
