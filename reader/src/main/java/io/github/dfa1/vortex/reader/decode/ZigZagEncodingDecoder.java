package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.LazyConstantByteArray;
import io.github.dfa1.vortex.reader.array.LazyConstantIntArray;
import io.github.dfa1.vortex.reader.array.LazyConstantLongArray;
import io.github.dfa1.vortex.reader.array.LazyConstantShortArray;
import io.github.dfa1.vortex.reader.array.LazyZigZagByteArray;
import io.github.dfa1.vortex.reader.array.LazyZigZagIntArray;
import io.github.dfa1.vortex.reader.array.LazyZigZagLongArray;
import io.github.dfa1.vortex.reader.array.LazyZigZagShortArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for `vortex.zigzag` — zigzag-decoded signed integers.
public final class ZigZagEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ZIGZAG;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_ZIGZAG, "expected primitive dtype, got " + ctx.dtype());
        }
        PType signed = p.ptype();
        PType unsigned = toUnsigned(signed);
        long n = ctx.rowCount();

        // Validity mirrors the Rust reference (`ValidityChild<ZigZag>`): a zigzag array's
        // validity IS its encoded child's, and the child dtype inherits the nullability.
        // Decode as an Array so a masked child is split rather than flattened (#210).
        Array encoded = ctx.decodeChild(0, new DType.Primitive(unsigned, p.nullable()), n);
        BoolArray validity = null;
        Array rawEncoded = encoded;
        if (encoded instanceof MaskedArray masked) {
            rawEncoded = masked.inner();
            validity = masked.validity();
        }
        MemorySegment src = ctx.materialize(rawEncoded);
        int elemBytes = signed.byteSize();
        long srcCap = SegmentBroadcast.capacity(src, elemBytes);

        Array result = decodeUnwrapped(ctx, signed, n, src, srcCap);
        return validity != null ? new MaskedArray(result, validity) : result;
    }

    private static Array decodeUnwrapped(DecodeContext ctx, PType signed, long n, MemorySegment src, long srcCap) {
        if (srcCap < n) {
            // Broadcast: single encoded value maps to all n rows — decode once, return constant
            return switch (signed) {
                case I8 -> {
                    int u = Byte.toUnsignedInt(src.get(ValueLayout.JAVA_BYTE, 0));
                    yield new LazyConstantByteArray(ctx.dtype(), n, (byte) ((u >>> 1) ^ -(u & 1)));
                }
                case I16 -> {
                    int u = Short.toUnsignedInt(src.get(VortexFormat.LE_SHORT, 0));
                    yield new LazyConstantShortArray(ctx.dtype(), n, (short) ((u >>> 1) ^ -(u & 1)));
                }
                case I32 -> {
                    int u = src.get(VortexFormat.LE_INT, 0);
                    yield new LazyConstantIntArray(ctx.dtype(), n, (u >>> 1) ^ -(u & 1));
                }
                case I64 -> {
                    long u = src.get(VortexFormat.LE_LONG, 0);
                    yield new LazyConstantLongArray(ctx.dtype(), n, (u >>> 1) ^ -(u & 1L));
                }
                default -> throw new VortexException(EncodingId.VORTEX_ZIGZAG, "unreachable");
            };
        }

        return switch (signed) {
            case I8 -> new LazyZigZagByteArray(ctx.dtype(), n, src);
            case I16 -> new LazyZigZagShortArray(ctx.dtype(), n, src);
            case I32 -> new LazyZigZagIntArray(ctx.dtype(), n, src);
            case I64 -> new LazyZigZagLongArray(ctx.dtype(), n, src);
            default -> throw new VortexException(EncodingId.VORTEX_ZIGZAG, "unreachable");
        };
    }

    private static PType toUnsigned(PType signed) {
        return switch (signed) {
            case I8 -> PType.U8;
            case I16 -> PType.U16;
            case I32 -> PType.U32;
            case I64 -> PType.U64;
            default -> throw new VortexException(EncodingId.VORTEX_ZIGZAG, "not a signed integer: " + signed);
        };
    }
}
