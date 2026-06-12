package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.encoding.EncodingId;

/// Read-side decoding interface. Implementations live in the {@code reader} module and
/// are discovered via {@link java.util.ServiceLoader}.
///
/// <p>Register via {@link io.github.dfa1.vortex.reader.ReadRegistry} — implementations
/// are discoverable via {@link java.util.ServiceLoader}.
public interface EncodingDecoder {

    /// Returns the wire identifier of this decoder.
    ///
    /// @return the wire identifier
    EncodingId encodingId();

    /// Returns whether this decoder handles the given dtype.
    ///
    /// @param dtype the dtype to test
    /// @return {@code true} if this decoder can handle arrays of {@code dtype}
    boolean accepts(DType dtype);

    /// Decodes an array node from the file using the provided context.
    ///
    /// @param ctx decoding context containing buffers, dtype, row count, and child registry
    /// @return decoded array
    Array decode(DecodeContext ctx);
}
