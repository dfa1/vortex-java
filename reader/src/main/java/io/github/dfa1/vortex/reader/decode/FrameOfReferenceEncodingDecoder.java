package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.proto.ScalarValue;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.LazyForByteArray;
import io.github.dfa1.vortex.reader.array.LazyForIntArray;
import io.github.dfa1.vortex.reader.array.LazyForLongArray;
import io.github.dfa1.vortex.reader.array.LazyForShortArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/// Read-only decoder for `fastlanes.for` (Frame of Reference).
public final class FrameOfReferenceEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public FrameOfReferenceEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.FASTLANES_FOR;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive p && !p.ptype().isFloating();
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        if (rawMeta == null || !rawMeta.hasRemaining()) {
            throw new VortexException(EncodingId.FASTLANES_FOR, "missing metadata");
        }
        ScalarValue scalar;
        try {
            MemorySegment metaSeg = MemorySegment.ofBuffer(rawMeta.duplicate());
            scalar = ScalarValue.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.FASTLANES_FOR, "invalid metadata", e);
        }

        Array encoded = ctx.decodeChild(0);

        BoolArray validity = null;
        Array rawEncoded = encoded;
        if (encoded instanceof MaskedArray masked) {
            rawEncoded = masked.inner();
            validity = masked.validity();
        }

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.FASTLANES_FOR, "expected primitive dtype, got " + ctx.dtype());
        }

        long ref = referenceValue(scalar);
        if (ref == 0L) {
            return validity != null ? new MaskedArray(rawEncoded, validity) : rawEncoded;
        }

        MemorySegment src = ArraySegments.of(rawEncoded, ctx.arena());
        long n = ctx.rowCount();
        Array result = switch (p.ptype()) {
            case I64, U64 -> new LazyForLongArray(ctx.dtype(), n, src, ref);
            case I32, U32 -> new LazyForIntArray(ctx.dtype(), n, src, (int) ref);
            case I16, U16 -> new LazyForShortArray(ctx.dtype(), n, src, (short) ref);
            case I8, U8 -> new LazyForByteArray(ctx.dtype(), n, src, (byte) ref);
            default -> throw new VortexException(EncodingId.FASTLANES_FOR, "unsupported ptype " + p.ptype());
        };
        return validity != null ? new MaskedArray(result, validity) : result;
    }

    private static long referenceValue(ScalarValue scalar) {
        if (scalar.int64_value() != null) {
            return scalar.int64_value();
        }
        if (scalar.uint64_value() != null) {
            return scalar.uint64_value();
        }
        return 0L;
    }
}
