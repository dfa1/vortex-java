package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;

import java.lang.foreign.MemorySegment;

/// Read-only decoder for `vortex.bool` (bit-packed boolean arrays, LSB first).
///
/// When the encoding node has one child, that child is the validity bitmask:
/// a [BoolArray] where `false` marks null rows. The values array is wrapped in
/// a [MaskedArray] so callers see nulls as invalid rows.
public final class BoolEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_BOOL;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        long n = ctx.rowCount();
        MemorySegment bits = ctx.buffer(0);
        // The bitmap comes straight from the file and needs one byte per 8 rows. A shorter one
        // is malformed, and [MaterializedBoolArray#getBoolean] indexes its buffer unchecked —
        // deliberately, since every other construction site allocates the bitmap itself at
        // exactly this size — so without this the read of whichever row runs off the end is a
        // raw IndexOutOfBoundsException (ADR 0003). O(1), and it also covers `materialize`,
        // which hands the same short buffer straight to the caller.
        long needed = (n + 7) >>> 3;
        if (bits.byteSize() < needed) {
            throw new VortexException(EncodingId.VORTEX_BOOL,
                    "bool bitmap of " + bits.byteSize() + " byte(s) is shorter than the "
                            + needed + " byte(s) needed for " + n + " row(s)");
        }
        Array values = new MaterializedBoolArray(ctx.dtype(), n, bits);
        if (ctx.node().children().length == 1) {
            Array va = ctx.decodeChild(0, DType.BOOL, n);
            if (!(va instanceof BoolArray validity)) {
                throw new VortexException(EncodingId.VORTEX_BOOL,
                        "validity child decoded to unexpected type: " + va.getClass().getSimpleName());
            }
            return new MaskedArray(values, validity);
        }
        return values;
    }
}
