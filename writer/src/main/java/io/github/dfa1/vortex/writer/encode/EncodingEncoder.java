package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.EncodingId;

import io.github.dfa1.vortex.core.model.DType;

/// Write-side surface of an encoding. Exposes only the metadata required to pick an
/// encoder for a dtype and the [#encode(DType, Object, EncodeContext)] entry
/// point itself.
///
/// Encoder implementations live in the `writer` module. Register via
/// [io.github.dfa1.vortex.writer.WriteRegistry.Builder#registerDefaults()]
/// or [io.github.dfa1.vortex.writer.WriteRegistry.Builder#register(EncodingEncoder)].
public interface EncodingEncoder {

    /// @return the wire identifier of this encoding
    EncodingId encodingId();

    /// @param dtype the dtype to test
    /// @return `true` if this encoding can encode arrays of `dtype`
    boolean accepts(DType dtype);

    /// Whether this encoder consumes a [NullableData] carrier directly for `dtype`, embedding
    /// the validity bitmap itself (as a Bool child) rather than relying on an enclosing
    /// `vortex.masked` wrapper.
    ///
    /// The writer uses this to route nullable columns: an encoder returning `true` receives the
    /// [NullableData] straight, otherwise the column is masked-wrapped over a non-nullable child.
    /// Default `false` — most encoders only handle dense data.
    ///
    /// @param dtype the dtype to test
    /// @return `true` if this encoding can encode nullable `dtype` from a [NullableData] carrier
    default boolean acceptsNullable(DType dtype) {
        return false;
    }

    /// Encodes `data` to bytes using the provided arena for output buffer allocation.
    ///
    /// @param dtype logical type of the data
    /// @param data  the data to encode (type depends on encoding; typically a primitive array)
    /// @param ctx   encoding context supplying the arena for output buffer allocation
    /// @return encode result containing the root node, buffers, and optional stats
    EncodeResult encode(DType dtype, Object data, EncodeContext ctx);

    /// Cascade-aware encode: returns a partial step with open child slots.
    /// Default wraps the terminal [#encode] result; override to expose children.
    ///
    /// @param dtype the logical type of the data
    /// @param data  the data to encode
    /// @param ctx   encoding context supplying the arena, registry, and cascade parameters
    /// @return cascade step with optional open child slots
    default CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        return CascadeStep.terminal(encode(dtype, data, ctx));
    }

    /// Stats this encoder needs from the cascade compressor's single-pass scan to evaluate
    /// [#expectedRatio]. Returned options are merged across all eligible encoders so
    /// one scan satisfies every consumer.
    ///
    /// @return stats requested for cascade selection; default is no stats
    default StatsOptions statsOptions() {
        return StatsOptions.NONE;
    }

    /// Estimate compression effectiveness on `data` given pre-computed [ArrayStats].
    /// Returning a verdict lets the cascade skip the expensive sample-encode probe.
    ///
    /// @param dtype the logical type of the data
    /// @param data  the input data
    /// @param stats pre-computed stats reflecting the merged [StatsOptions]
    /// @return [Estimate#SKIP] / [Estimate#ALWAYS_USE], or [Estimate#COMPLETE] to
    ///         defer to the sample-encoded selection path
    default Estimate expectedRatio(DType dtype, Object data, ArrayStats stats) {
        return Estimate.COMPLETE;
    }
}
