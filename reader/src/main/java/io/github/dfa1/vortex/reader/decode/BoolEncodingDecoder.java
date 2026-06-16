package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;

/// Read-only decoder for `vortex.bool` (bit-packed boolean arrays, LSB first).
///
/// Read-only decoder for bit-packed boolean arrays.
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
        return new MaterializedBoolArray(ctx.dtype(), ctx.rowCount(), ctx.buffer(0));
    }
}
