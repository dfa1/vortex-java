package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloat16Array;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;

import java.lang.foreign.MemorySegment;

/// Read-only decoder for `vortex.primitive` — raw little-endian primitive arrays.
public final class PrimitiveEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public PrimitiveEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_PRIMITIVE;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment buf = ctx.buffer(0);
        long n = ctx.rowCount();
        DType dt = ctx.dtype();
        PType ptype = ((DType.Primitive) dt).ptype();
        Array values = switch (ptype) {
            case I64, U64 -> new MaterializedLongArray(dt, n, buf);
            case I32, U32 -> new MaterializedIntArray(dt, n, buf);
            case F64 -> new MaterializedDoubleArray(dt, n, buf);
            case F32 -> new MaterializedFloatArray(dt, n, buf);
            case I16, U16 -> new MaterializedShortArray(dt, n, buf);
            case I8, U8 -> new MaterializedByteArray(dt, n, buf);
            case F16 -> new MaterializedFloat16Array(dt, n, buf);
        };
        if (ctx.node().children().length == 1) {
            Array va = ctx.decodeChild(0, DType.BOOL, n);
            if (!(va instanceof BoolArray validity)) {
                throw new VortexException(EncodingId.VORTEX_PRIMITIVE,
                        "validity child decoded to unexpected type: " + va.getClass().getSimpleName());
            }
            return new MaskedArray(values, validity);
        }
        return values;
    }
}
