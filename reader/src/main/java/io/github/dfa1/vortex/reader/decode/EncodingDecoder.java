package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.core.model.EncodingId;

/// Read-side decoding interface. Implementations live in the `reader` module.
///
/// Register via [io.github.dfa1.vortex.reader.ReadRegistry.Builder#registerDefaults()]
/// or [io.github.dfa1.vortex.reader.ReadRegistry.Builder#register(EncodingDecoder)].
public interface EncodingDecoder {

    /// Returns the wire identifier of this decoder.
    ///
    /// @return the wire identifier
    EncodingId encodingId();

    /// Decodes an array node from the file using the provided context.
    ///
    /// @param ctx decoding context containing buffers, dtype, row count, and child registry
    /// @return decoded array
    Array decode(DecodeContext ctx);
}
