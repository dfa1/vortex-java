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

    /// Resolves a {@link DType.Extension} to its spec-defined singleton.
    ///
    /// @param dtype declared extension dtype
    /// @return matching spec extension singleton, or empty when the wire id
    ///         isn't one of the four spec extensions
    static java.util.Optional<Extension> findKnown(DType.Extension dtype) {
        return ExtensionId.parse(dtype.extensionId()).map(id -> switch (id) {
            case VORTEX_DATE -> DateExtension.INSTANCE;
            case VORTEX_TIME -> TimeExtension.INSTANCE;
            case VORTEX_TIMESTAMP -> TimestampExtension.INSTANCE;
            case VORTEX_UUID -> UuidExtension.INSTANCE;
        });
    }
}
