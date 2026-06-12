package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.Float16Array;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.encoding.EncodingId;

import java.lang.foreign.MemorySegment;

/// Read-only decoder for {@code vortex.primitive} — raw little-endian primitive arrays.
public final class PrimitiveEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public PrimitiveEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_PRIMITIVE;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment buf = ctx.buffer(0);
        long n = ctx.rowCount();
        DType dt = ctx.dtype();
        PType ptype = ((DType.Primitive) dt).ptype();
        Array values = switch (ptype) {
            case I64, U64 -> new LongArray(dt, n, buf);
            case I32, U32 -> new IntArray(dt, n, buf);
            case F64 -> new DoubleArray(dt, n, buf);
            case F32 -> new FloatArray(dt, n, buf);
            case I16, U16 -> new ShortArray(dt, n, buf);
            case I8, U8 -> new ByteArray(dt, n, buf);
            case F16 -> new Float16Array(dt, n, buf);
        };
        if (ctx.node().children().length == 1) {
            Array va = ctx.decodeChild(0, new DType.Bool(false), n);
            if (!(va instanceof BoolArray validity)) {
                throw new VortexException(EncodingId.VORTEX_PRIMITIVE,
                        "validity child decoded to unexpected type: " + va.getClass().getSimpleName());
            }
            return new MaskedArray(values, validity);
        }
        return values;
    }
}
