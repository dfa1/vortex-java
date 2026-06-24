package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ExtensionId;

/// Read-side contract for a Vortex extension type.
///
/// Implementations pair a spec identity ([ExtensionId]) with the matching
/// [DType.Extension] dtype. Typed decode methods live on each concrete
/// implementation — they are not on this interface, so read callers get typed return
/// values without casting through `Object`.
public interface ExtensionDecoder {

    /// @return the spec identity of this extension
    ExtensionId extensionId();

    /// @param nullable whether the column allows nulls
    /// @return matching [DType.Extension] (storage dtype + default metadata)
    DType.Extension dtype(boolean nullable);
}
