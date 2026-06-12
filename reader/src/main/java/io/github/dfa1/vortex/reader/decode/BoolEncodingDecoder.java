package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.encoding.EncodingId;

/// Read-only decoder for {@code vortex.bool} (bit-packed boolean arrays, LSB first).
///
/// <p>ADR 0001 Phase 2: first encoding lifted into a standalone {@link EncodingDecoder}
/// implementation in the {@code reader} module. The corresponding write-side encode
/// path continues to live on {@link io.github.dfa1.vortex.encoding.BoolEncoding} in
/// {@code core}; that file is peeled into a {@code BoolEncodingEncoder} in
/// {@code writer} during Phase 3.
public final class BoolEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public BoolEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_BOOL;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Bool;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return new BoolArray(ctx.dtype(), ctx.rowCount(), ctx.buffer(0));
    }
}
