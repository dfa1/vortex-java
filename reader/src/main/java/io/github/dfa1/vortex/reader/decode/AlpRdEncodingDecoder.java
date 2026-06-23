package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.ALPRDMetadata;
import io.github.dfa1.vortex.proto.PatchesMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazyAlpRdDoubleArray;
import io.github.dfa1.vortex.reader.array.LazyAlpRdFloatArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/// Read-only decoder for `vortex.alprd`.
public final class AlpRdEncodingDecoder implements EncodingDecoder {
    private static final DType U16_DTYPE = new DType.Primitive(PType.U16, false);
    private static final DType U32_DTYPE = new DType.Primitive(PType.U32, false);
    private static final DType U64_DTYPE = new DType.Primitive(PType.U64, false);

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public AlpRdEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ALPRD;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ALPRDMetadata meta = parseMeta(ctx);

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_ALPRD,
                    "expected primitive dtype, got " + ctx.dtype());
        }

        int rightBitWidth = meta.right_bit_width();
        int dictLen = meta.dict_len();
        short[] dict = new short[dictLen];
        for (int i = 0; i < dictLen; i++) {
            dict[i] = (short) (meta.dict().get(i) & 0xFFFF);
        }

        long n = ctx.rowCount();
        PType ptype = p.ptype();

        // Lazy path: keep left/right as typed Arrays + patches as a small short[] +
        // a lazy indices Array. No n-sized output buffer allocated.
        Array leftRaw = ctx.decodeChild(0, U16_DTYPE, n);
        ShortArray leftArr = (ShortArray) unwrap(leftRaw);

        Patches patches = decodePatches(ctx, meta.patches());

        return switch (ptype) {
            case F64 -> {
                Array rightRaw = ctx.decodeChild(1, U64_DTYPE, n);
                LongArray rightArr = (LongArray) unwrap(rightRaw);
                yield new LazyAlpRdDoubleArray(ctx.dtype(), n, dict, rightBitWidth,
                        leftArr, rightArr, patches.indices, patches.leftValues, patches.offset);
            }
            case F32 -> {
                Array rightRaw = ctx.decodeChild(1, U32_DTYPE, n);
                IntArray rightArr = (IntArray) unwrap(rightRaw);
                yield new LazyAlpRdFloatArray(ctx.dtype(), n, dict, rightBitWidth,
                        leftArr, rightArr, patches.indices, patches.leftValues, patches.offset);
            }
            default -> throw new VortexException(EncodingId.VORTEX_ALPRD, "unsupported dtype " + ptype);
        };
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

    private static Patches decodePatches(DecodeContext ctx, PatchesMetadata pm) {
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
        MemorySegment valSeg = ctx.decodeChildSegment(3, U16_DTYPE, numPatches);
        long valCap = SegmentBroadcast.capacity(valSeg, 2);
        short[] leftValues = new short[(int) numPatches];
        for (int j = 0; j < numPatches; j++) {
            leftValues[j] = valSeg.getAtIndex(PTypeIO.LE_SHORT, j % valCap);
        }
        return new Patches(idxData, leftValues, offset);
    }

    private static ALPRDMetadata parseMeta(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        if (rawMeta == null || !rawMeta.hasRemaining()) {
            return new ALPRDMetadata(0, 0, java.util.List.of(),
                    io.github.dfa1.vortex.proto.PType.fromValue(PType.U16.ordinal()), null);
        }
        try {
            MemorySegment metaSeg = MemorySegment.ofBuffer(rawMeta.duplicate());
            return ALPRDMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_ALPRD, "invalid metadata", e);
        }
    }
}
