package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.extension.ExtensionId;

/// Read-side contract for a Vortex extension type.
///
/// Implementations pair a spec identity ({@link ExtensionId}) with the matching
/// {@link DType.Extension} dtype. Typed decode methods live on each concrete
/// implementation — they are not on this interface, so read callers get typed return
/// values without casting through `Object`.
public interface ExtensionDecoder {

    /// @return the spec identity of this extension
    ExtensionId extensionId();

    /// @param nullable whether the column allows nulls
    /// @return matching {@link DType.Extension} (storage dtype + default metadata)
    DType.Extension dtype(boolean nullable);
}
