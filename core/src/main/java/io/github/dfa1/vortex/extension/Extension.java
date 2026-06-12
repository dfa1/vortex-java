package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;

/// Contract for a Vortex extension type — pairs the wire-format identity
/// (an [ExtensionId]) with a factory for the matching [DType.Extension]
/// dtype. Behaviour-specific decode methods live on each concrete
/// implementation, not on this interface, so read callers get typed
/// return values without casting through {@code Object}.
///
/// <p>Extends {@link ExtensionEncoder} so existing bifunctional implementations
/// satisfy both contracts unchanged; ADR 0001 Phase 5 progressively peels the
/// write-side {@code encodeAll} surface into standalone {@link ExtensionEncoder}
/// implementations living in the writer module.
public interface Extension extends ExtensionEncoder {

    /// Resolves a {@link DType.Extension} to its spec-defined singleton.
    /// Closes over the closed-set spec impls; third-party extensions go
    /// through {@link io.github.dfa1.vortex.encoding.Registry#lookup(ExtensionId)}.
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
