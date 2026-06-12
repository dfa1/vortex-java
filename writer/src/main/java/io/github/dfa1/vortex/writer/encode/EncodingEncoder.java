package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.encoding.EncodingId;

import io.github.dfa1.vortex.core.DType;

/// Write-side surface of an encoding. Exposes only the metadata required to pick an
/// encoder for a dtype and the {@link #encode(DType, Object, EncodeContext)} entry
/// point itself.
///
/// <p>Encoder implementations live in the {@code writer} module and are registered via
/// {@link java.util.ServiceLoader}.
public interface EncodingEncoder {

    /// @return the wire identifier of this encoding
    EncodingId encodingId();

    /// @param dtype the dtype to test
    /// @return {@code true} if this encoding can encode arrays of {@code dtype}
    boolean accepts(DType dtype);

    /// Encodes {@code data} to bytes using the provided arena for output buffer allocation.
    ///
    /// @param dtype logical type of the data
    /// @param data  the data to encode (type depends on encoding; typically a primitive array)
    /// @param ctx   encoding context supplying the arena for output buffer allocation
    /// @return encode result containing the root node, buffers, and optional stats
    EncodeResult encode(DType dtype, Object data, EncodeContext ctx);

    /// Cascade-aware encode: returns a partial step with open child slots.
    /// Default wraps the terminal {@link #encode} result; override to expose children.
    ///
    /// @param dtype the logical type of the data
    /// @param data  the data to encode
    /// @param ctx   encoding context supplying the arena, registry, and cascade parameters
    /// @return cascade step with optional open child slots
    default CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        return CascadeStep.terminal(encode(dtype, data, ctx));
    }
}
