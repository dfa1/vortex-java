package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.util.Collection;

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

    /// Polymorphic encode: converts a collection of domain values into the
    /// raw storage-array shape the writer accepts ({@code int[]}, {@code long[]},
    /// {@code byte[]}, ...). Used by the writer's auto-routing path. Concrete
    /// impls cast {@code values} to their domain type and may consult
    /// {@code dtype.metadata()} (e.g. {@code TimeUnit}).
    ///
    /// @param dtype  declared extension dtype (carries unit/timezone metadata)
    /// @param values domain-typed values to encode
    /// @return packed storage array
    /// @throws VortexException by default; impls must override to support writes
    default Object encodeAll(DType.Extension dtype, Collection<?> values) {
        throw new VortexException("encode not supported for " + extensionId());
    }

    /// Resolves a {@link DType.Extension} to its spec-defined singleton, or
    /// {@code null} when the wire id isn't one of the four spec extensions.
    /// Closes over the closed-set spec impls; third-party extensions go
    /// through {@link io.github.dfa1.vortex.encoding.Registry#lookup(ExtensionId)}.
    ///
    /// @param dtype declared extension dtype
    /// @return matching spec extension singleton, or {@code null}
    static @org.jspecify.annotations.Nullable Extension findKnown(DType.Extension dtype) {
        ExtensionId id = ExtensionId.tryFrom(dtype.extensionId());
        if (id == null) {
            return null;
        }
        return switch (id) {
            case VORTEX_DATE -> DateExtension.INSTANCE;
            case VORTEX_TIME -> TimeExtension.INSTANCE;
            case VORTEX_TIMESTAMP -> TimestampExtension.INSTANCE;
            case VORTEX_UUID -> UuidExtension.INSTANCE;
        };
    }
}
