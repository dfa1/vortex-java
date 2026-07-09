package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoALPRDMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazyAlpRdDoubleArray;
import io.github.dfa1.vortex.reader.array.LazyAlpRdFloatArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Read-only decoder for `vortex.alprd`.
public final class AlpRdEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ALPRD;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ProtoALPRDMetadata meta = parseMeta(ctx);

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_ALPRD,
                    "expected primitive dtype, got " + ctx.dtype());
        }

        PType leftPartsPtype = PType.fromOrdinal(meta.left_parts_ptype().value());
        if (leftPartsPtype != PType.U16) {
            throw new VortexException(EncodingId.VORTEX_ALPRD,
                    "unsupported left_parts_ptype " + leftPartsPtype + " (#249)");
        }

        int rightBitWidth = meta.right_bit_width();
        int dictLen = meta.dict_len();
        short[] dict = new short[dictLen];
        for (int i = 0; i < dictLen; i++) {
            dict[i] = (short) (meta.dict().get(i) & 0xFFFF);
        }

        long n = ctx.rowCount();
        PType ptype = p.ptype();

        // Validity mirrors the Rust reference: an ALP-RD array's validity IS its
        // left_parts child's validity. Decode the child as an Array so a nullable
        // left_parts surfaces its MaskedArray; capture the mask and re-wrap the decoded
        // result rather than flattening it (which silently dropped nulls — #234).
        Array leftRaw = ctx.decodeChild(0, DType.U16, n);
        BoolArray validity = null;
        Array leftInner = leftRaw;
        if (leftRaw instanceof MaskedArray masked) {
            leftInner = masked.inner();
            validity = masked.validity();
        }
        ShortArray leftArr = (ShortArray) leftInner;

        Patches patches = decodePatches(ctx, meta.patches());

        Array decoded = switch (ptype) {
            case F64 -> {
                Array rightRaw = ctx.decodeChild(1, DType.U64, n);
                LongArray rightArr = (LongArray) unwrap(rightRaw);
                yield new LazyAlpRdDoubleArray(ctx.dtype(), n, dict, rightBitWidth,
                        leftArr, rightArr, patches.indices, patches.leftValues, patches.offset);
            }
            case F32 -> {
                Array rightRaw = ctx.decodeChild(1, DType.U32, n);
                IntArray rightArr = (IntArray) unwrap(rightRaw);
                yield new LazyAlpRdFloatArray(ctx.dtype(), n, dict, rightBitWidth,
                        leftArr, rightArr, patches.indices, patches.leftValues, patches.offset);
            }
            default -> throw new VortexException(EncodingId.VORTEX_ALPRD, "unsupported dtype " + ptype);
        };
        return validity != null ? new MaskedArray(decoded, validity) : decoded;
    }

    private static Array unwrap(Array arr) {
        return arr instanceof MaskedArray m ? m.inner() : arr;
    }

    /// Decoded patches: sorted absolute indices (as a typed Array for in-place lookup)
    /// plus the actual left u16 values pulled into a short[].
    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record Patches(Array indices, short[] leftValues, long offset) {
        static final Patches EMPTY = new Patches(null, new short[0], 0L);
    }

    private static Patches decodePatches(DecodeContext ctx, ProtoPatchesMetadata pm) {
        if (pm == null || pm.len() == 0) {
            return Patches.EMPTY;
        }
        long numPatches = pm.len();
        long offset = pm.offset();
        PType idxPtype = PType.fromOrdinal(pm.indices_ptype().value());
        DType idxDtype = new DType.Primitive(idxPtype, false);

        Array idxArr = ctx.decodeChild(2, idxDtype, numPatches);
        Array idxData = idxArr instanceof MaskedArray m ? m.inner() : idxArr;

        // Pull the small left-values table into a short[] so lookups don't pay an
        // Array-dispatch per patch hit. Patches are typically <1% of rows.
        MemorySegment valSeg = ctx.decodeChildSegment(3, DType.U16, numPatches);
        long valCap = SegmentBroadcast.capacity(valSeg, 2);
        short[] leftValues = new short[(int) numPatches];
        for (int j = 0; j < numPatches; j++) {
            leftValues[j] = valSeg.getAtIndex(VortexFormat.LE_SHORT, j % valCap);
        }
        return new Patches(idxData, leftValues, offset);
    }

    private static ProtoALPRDMetadata parseMeta(DecodeContext ctx) {
        MemorySegment rawMeta = ctx.metadata();
        if (rawMeta == null || rawMeta.byteSize() == 0) {
            return new ProtoALPRDMetadata(0, 0, java.util.List.of(),
                    io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.U16.ordinal()), null);
        }
        try {
            return ProtoALPRDMetadata.decode(rawMeta, 0, rawMeta.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_ALPRD, "invalid metadata", e);
        }
    }
}
