package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.LazyForByteArray;
import io.github.dfa1.vortex.reader.array.LazyForIntArray;
import io.github.dfa1.vortex.reader.array.LazyForLongArray;
import io.github.dfa1.vortex.reader.array.LazyForShortArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for `fastlanes.for` (Frame of Reference).
public final class FrameOfReferenceEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.FASTLANES_FOR;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment rawMeta = ctx.metadata();
        if (rawMeta == null || rawMeta.byteSize() == 0) {
            throw new VortexException(EncodingId.FASTLANES_FOR, "missing metadata");
        }
        ProtoScalarValue scalar;
        try {
            scalar = ProtoScalarValue.decode(rawMeta, 0, rawMeta.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.FASTLANES_FOR, "invalid metadata", e);
        }

        Array encoded = ctx.decodeChild(0);
        MaskedArray.Unwrapped unwrapped = MaskedArray.unwrap(encoded);
        Array rawEncoded = unwrapped.inner();
        BoolArray validity = unwrapped.validity();

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.FASTLANES_FOR, "expected primitive dtype, got " + ctx.dtype());
        }

        long ref = referenceValue(scalar);
        if (ref == 0L) {
            return MaskedArray.wrapIfPresent(rawEncoded, validity);
        }

        long n = ctx.rowCount();
        if (unpackedByThisDecode(ctx) && rawEncoded.segmentIfPresent().isPresent()) {
            MemorySegment owned = rawEncoded.segmentIfPresent().get();
            if (owned.byteSize() >= n * p.ptype().byteSize()) {
                addInPlace(owned, n, p.ptype(), ref);
                return MaskedArray.wrapIfPresent(MaterializedArrays.of(ctx.dtype(), p.ptype(), n, owned), validity);
            }
        }

        MemorySegment src = ctx.materialize(rawEncoded);
        Array result = switch (p.ptype()) {
            case I64, U64 -> new LazyForLongArray(ctx.dtype(), n, src, ref);
            case I32, U32 -> new LazyForIntArray(ctx.dtype(), n, src, (int) ref);
            case I16, U16 -> new LazyForShortArray(ctx.dtype(), n, src, (short) ref);
            case I8, U8 -> new LazyForByteArray(ctx.dtype(), n, src, (byte) ref);
            default -> throw new VortexException(EncodingId.FASTLANES_FOR, "unsupported ptype " + p.ptype());
        };
        return MaskedArray.wrapIfPresent(result, validity);
    }

    // Rust's FoR decompress fuses the unpack of a BitPacked child with the reference add, and adds
    // in place to any buffer it owns uniquely. A bitpacked child unpacks into a buffer allocated by
    // this very decode and referenced nowhere else, so adding there is that same in-place map: one
    // buffer for the column instead of the unpacked one plus a second on materialize.
    private static boolean unpackedByThisDecode(DecodeContext ctx) {
        ArrayNode[] children = ctx.node().children();
        return children.length > 0 && EncodingId.FASTLANES_BITPACKED.equals(children[0].encodingId());
    }

    // Wrapping add at the element width, one uniform loop per width so C2 vectorizes it.
    private static void addInPlace(MemorySegment seg, long n, PType ptype, long ref) {
        switch (ptype) {
            case I64, U64 -> {
                for (long i = 0; i < n; i++) {
                    seg.setAtIndex(VortexFormat.LE_LONG, i, seg.getAtIndex(VortexFormat.LE_LONG, i) + ref);
                }
            }
            case I32, U32 -> {
                int r = (int) ref;
                for (long i = 0; i < n; i++) {
                    seg.setAtIndex(VortexFormat.LE_INT, i, seg.getAtIndex(VortexFormat.LE_INT, i) + r);
                }
            }
            case I16, U16 -> {
                short r = (short) ref;
                for (long i = 0; i < n; i++) {
                    seg.setAtIndex(VortexFormat.LE_SHORT, i, (short) (seg.getAtIndex(VortexFormat.LE_SHORT, i) + r));
                }
            }
            case I8, U8 -> {
                byte r = (byte) ref;
                for (long i = 0; i < n; i++) {
                    seg.set(ValueLayout.JAVA_BYTE, i, (byte) (seg.get(ValueLayout.JAVA_BYTE, i) + r));
                }
            }
            default -> throw new VortexException(EncodingId.FASTLANES_FOR, "unsupported ptype " + ptype);
        }
    }

    private static long referenceValue(ProtoScalarValue scalar) {
        if (scalar.int64_value() != null) {
            return scalar.int64_value();
        }
        if (scalar.uint64_value() != null) {
            return scalar.uint64_value();
        }
        return 0L;
    }
}
