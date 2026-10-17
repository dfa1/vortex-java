package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.reader.array.Array;

import java.util.List;

/// Read-side contract for a Vortex extension type, the Java side of Rust's `ExtVTable`.
///
/// Implementations pair an identity ([ExtensionId]) with the matching [DType.Extension] dtype and
/// decode a column's storage array into domain values. Register one on
/// [io.github.dfa1.vortex.reader.ReadRegistry.Builder#register(ExtensionDecoder)] and
/// [io.github.dfa1.vortex.reader.Chunk#as(io.github.dfa1.vortex.core.model.ColumnName, Class)]
/// dispatches to it. An extension id with no registered decoder still reads, as its storage array.
///
/// @param <T> the domain type one row decodes to (e.g. [java.time.LocalDate] for `vortex.date`)
public interface ExtensionDecoder<T> {

    /// @return the identity of this extension
    ExtensionId extensionId();

    /// @param nullable whether the column allows nulls
    /// @return matching [DType.Extension] (storage dtype + default metadata)
    DType.Extension dtype(boolean nullable);

    /// @return the domain type one row decodes to
    Class<T> domainType();

    /// Decodes every row of `storage` into domain values.
    ///
    /// @param dtype   declared extension dtype (carries metadata such as a time unit)
    /// @param storage the column's storage array; nullable columns arrive as a
    ///                [io.github.dfa1.vortex.reader.array.MaskedArray]
    /// @return decoded values in row order, `null` at null rows
    List<T> decodeAll(DType.Extension dtype, Array storage);

    /// Rejects a dtype this extension cannot represent — Rust's `validate_dtype`. Called for every
    /// extension dtype with this id in a file's schema when the file is opened.
    ///
    /// The default accepts every dtype.
    ///
    /// @param dtype the extension dtype declared by the file
    /// @throws io.github.dfa1.vortex.core.error.VortexException if the storage dtype or metadata is
    ///         invalid for this extension
    default void validate(DType.Extension dtype) {
    }
}
