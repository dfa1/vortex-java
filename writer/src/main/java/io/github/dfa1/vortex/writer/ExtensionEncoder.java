package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ExtensionId;

import java.util.Collection;

/// Write-side contract for a Vortex extension type, the Java side of Rust's `ExtVTable`.
///
/// Implementations pair an identity ([ExtensionId]) with the matching
/// [DType.Extension] dtype and a polymorphic [#encodeAll] entry point used
/// by the writer's auto-routing path. Register one on
/// [WriteRegistry.Builder#register(ExtensionEncoder)] and pass the registry to
/// [VortexWriter#create(java.nio.channels.WritableByteChannel, DType.Struct, WriteOptions, WriteRegistry)].
/// An extension id with no registered encoder can still be written from its storage array.
public interface ExtensionEncoder {

    /// Returns the identity of this extension.
    ///
    /// @return the spec identity of this extension
    ExtensionId extensionId();

    /// Returns the extension dtype this encoder writes, with the given nullability.
    ///
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

    /// Rejects a dtype this extension cannot represent — Rust's `validate_dtype`. Called for every
    /// extension dtype with this id in the schema when the writer is created.
    ///
    /// The default accepts every dtype.
    ///
    /// @param dtype the extension dtype declared by the schema
    /// @throws io.github.dfa1.vortex.core.error.VortexException if the storage dtype or metadata is
    ///         invalid for this extension
    default void validate(DType.Extension dtype) {
    }
}
