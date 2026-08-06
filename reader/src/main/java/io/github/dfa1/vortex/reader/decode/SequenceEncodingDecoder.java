package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoSequenceMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LazySequenceByteArray;
import io.github.dfa1.vortex.reader.array.LazySequenceDoubleArray;
import io.github.dfa1.vortex.reader.array.LazySequenceFloat16Array;
import io.github.dfa1.vortex.reader.array.LazySequenceFloatArray;
import io.github.dfa1.vortex.reader.array.LazySequenceIntArray;
import io.github.dfa1.vortex.reader.array.LazySequenceLongArray;
import io.github.dfa1.vortex.reader.array.LazySequenceShortArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Read-only decoder for `vortex.sequence` — `A[i] = base + i * multiplier`.
///
/// Metadata-only: the encoding has no buffers and no children, so decode is pure metadata
/// parsing and the result is a `LazySequenceXxxArray` that computes each row on access.
/// Nothing is allocated, whatever the row count.
public final class SequenceEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_SEQUENCE;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment metaBuf = ctx.metadata();
        if (metaBuf == null || metaBuf.byteSize() == 0) {
            throw new VortexException(EncodingId.VORTEX_SEQUENCE, "missing metadata");
        }
        ProtoSequenceMetadata meta;
        try {
            meta = ProtoSequenceMetadata.decode(metaBuf, 0, metaBuf.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_SEQUENCE, "invalid metadata", e);
        }

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_SEQUENCE, "expected primitive dtype, got " + ctx.dtype());
        }

        DType dtype = ctx.dtype();
        long n = ctx.rowCount();
        PType pt = p.ptype();
        return switch (pt) {
            case I64, U64 -> new LazySequenceLongArray(dtype, n, signedValue(meta.base()), signedValue(meta.multiplier()));
            case I32, U32 -> new LazySequenceIntArray(dtype, n, signedValue(meta.base()), signedValue(meta.multiplier()));
            case I16, U16 -> new LazySequenceShortArray(dtype, n, signedValue(meta.base()), signedValue(meta.multiplier()));
            case I8, U8 -> new LazySequenceByteArray(dtype, n, signedValue(meta.base()), signedValue(meta.multiplier()));
            case F64 -> new LazySequenceDoubleArray(dtype, n, meta.base().f64_value(), meta.multiplier().f64_value());
            case F32 -> new LazySequenceFloatArray(dtype, n, meta.base().f32_value(), meta.multiplier().f32_value());
            case F16 -> new LazySequenceFloat16Array(dtype, n, halfValue(meta.base()), halfValue(meta.multiplier()));
        };
    }

    private static long signedValue(ProtoScalarValue sv) {
        if (sv == null) {
            return 0L;
        }
        if (sv.int64_value() != null) {
            return sv.int64_value();
        }
        if (sv.uint64_value() != null) {
            return sv.uint64_value();
        }
        return 0L;
    }

    /// Widens a half-precision scalar to the `float` the lazy carrier steps in.
    ///
    /// @param sv the metadata scalar holding an f16 bit pattern
    /// @return the widened value
    private static float halfValue(ProtoScalarValue sv) {
        return Float.float16ToFloat((short) sv.f16_value().longValue());
    }
}
