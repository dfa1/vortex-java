package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;
import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoDeltaMetadata;
import io.github.dfa1.vortex.reader.array.Array;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for `fastlanes.delta`.
///
/// Delta is one of the few encodings that genuinely has to reconstruct values — a row is a
/// prefix sum along its lane — so there is no lazy carrier here. What decode does avoid is
/// doing that reconstruction on the heap: the only row-scaled buffer is the output segment,
/// allocated from `ctx.arena()` at the ptype's real width. The per-chunk scratch is fixed-size
/// ([FastLanes#CHUNK] elements, cache-resident) and reused across chunks.
public final class DeltaEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.FASTLANES_DELTA;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment rawMeta = ctx.metadata();
        ProtoDeltaMetadata meta;
        if (rawMeta == null || rawMeta.byteSize() == 0) {
            meta = new ProtoDeltaMetadata(0L, 0);
        } else {
            try {
                meta = ProtoDeltaMetadata.decode(rawMeta, 0, rawMeta.byteSize());
            } catch (IOException e) {
                throw new VortexException(EncodingId.FASTLANES_DELTA, "invalid metadata", e);
            }
        }

        PType ptype = ((DType.Primitive) ctx.dtype()).ptype();
        if (ptype.isFloating()) {
            throw new VortexException(EncodingId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
        }
        long rowCount = ctx.rowCount();
        int typeBits = ptype.bits();
        int lanes = FastLanes.lanes(ptype);
        long mask = FastLanes.lowMask(ptype.bits());

        long deltasLen = meta.deltas_len();
        int offset = meta.offset();

        if (deltasLen == 0L || rowCount == 0L) {
            return MaterializedArrays.of(ctx.dtype(), ptype, 0L, ctx.arena().allocate(0));
        }

        // Rows come from the window `[offset, offset + rowCount)` of the `deltasLen` elements
        // the chunks reconstruct. Both bounds are untrusted metadata: a negative offset or a
        // window past the end used to surface as a raw ArrayIndexOutOfBoundsException from the
        // final arraycopy, and an absurd `deltasLen` sized a heap array before anything checked
        // it — NegativeArraySizeException or OutOfMemoryError, neither a VortexException
        // (ADR 0003). Checked before any child decode, so a bogus length never drives an
        // allocation.
        // `deltasLen < 0` is checked on its own rather than left to the subtraction: a
        // sufficiently negative one makes `deltasLen - offset` wrap positive, which passes a
        // window check it should fail, and the chunk loop then simply does nothing and hands
        // back a zero-filled array — a malformed file answered instead of rejected.
        if (offset < 0 || deltasLen < 0 || rowCount > deltasLen - offset) {
            throw new VortexException(EncodingId.FASTLANES_DELTA,
                    "row window [" + offset + ", " + (offset + rowCount) + ") outside the "
                            + deltasLen + " delta element(s)");
        }

        DType dtype = ctx.dtype();
        long basesLen = (deltasLen / FastLanes.CHUNK) * lanes;
        MemorySegment basesSeg = ctx.decodeChildSegment(0, dtype, basesLen);
        MemorySegment deltasSeg = ctx.decodeChildSegment(1, dtype, deltasLen);
        int elemBytes = ptype.byteSize();
        long basesCap = SegmentBroadcast.capacity(basesSeg, elemBytes);
        long deltasCap = SegmentBroadcast.capacity(deltasSeg, elemBytes);

        // The only row-scaled allocation: the output itself, off-heap and at the column's own
        // width. Everything below is fixed-size scratch — one chunk's worth, cache-resident,
        // reused across chunks.
        MemorySegment out = ctx.arena().allocate(rowCount * elemBytes);
        long[] chunkBases = new long[lanes];
        long[] chunkDeltas = new long[FastLanes.CHUNK];
        long[] chunkUndelta = new long[FastLanes.CHUNK];

        // Each chunk carries its own lane bases, so chunks are independent and only those
        // overlapping the requested window are reconstructed — reading the tail of a long
        // column no longer walks every chunk before it.
        long numChunks = deltasLen / FastLanes.CHUNK;
        long firstChunk = offset / FastLanes.CHUNK;
        long lastChunk = Math.min(numChunks - 1, (offset + rowCount - 1) / FastLanes.CHUNK);
        for (long chunk = firstChunk; chunk <= lastChunk; chunk++) {
            readElements(basesSeg, ptype, basesCap, chunk * lanes, lanes, chunkBases);
            readElements(deltasSeg, ptype, deltasCap, chunk * FastLanes.CHUNK, FastLanes.CHUNK, chunkDeltas);
            SimdOperationsSupport.preferred().undeltaChunk(chunkDeltas, chunkBases, lanes, typeBits, mask, chunkUndelta);
            scatterChunk(out, ptype, chunkUndelta, chunk * FastLanes.CHUNK - offset, rowCount);
        }
        return MaterializedArrays.of(ctx.dtype(), ptype, rowCount, out.asReadOnly());
    }

    /// Untransposes one chunk straight into the output window.
    ///
    /// The value at in-chunk position `i` belongs at logical index
    /// `base + FastLanes#transposeIndex(i)`, so untransposing and window-shifting happen in the
    /// same store — no second chunk-sized buffer, and no separate pass to slice it.
    ///
    /// `base` is negative for the leading chunk of an offset-sliced array, and the trailing
    /// chunk can run past `rowCount`. One unsigned comparison covers both: a negative index
    /// reads as a huge unsigned value and fails the same test as an overrun. The stores are a
    /// permutation scatter, so they never vectorize regardless, and the compare costs nothing
    /// the untranspose was not already paying. The ptype switch is hoisted out of the loop so
    /// each body stays uniform (CLAUDE.md hot-loop rule).
    ///
    /// @param out      output segment of `rowCount` elements
    /// @param ptype    output element type
    /// @param values   one chunk of reconstructed values, in transposed order
    /// @param base     output index the chunk's logical position 0 maps to; may be negative
    /// @param rowCount number of rows in the output window
    private static void scatterChunk(MemorySegment out, PType ptype, long[] values, long base, long rowCount) {
        switch (ptype) {
            case I8, U8 -> {
                for (int i = 0; i < FastLanes.CHUNK; i++) {
                    long at = base + FastLanes.transposeIndex(i);
                    if (Long.compareUnsigned(at, rowCount) < 0) {
                        out.set(ValueLayout.JAVA_BYTE, at, (byte) values[i]);
                    }
                }
            }
            case I16, U16 -> {
                for (int i = 0; i < FastLanes.CHUNK; i++) {
                    long at = base + FastLanes.transposeIndex(i);
                    if (Long.compareUnsigned(at, rowCount) < 0) {
                        out.setAtIndex(VortexFormat.LE_SHORT, at, (short) values[i]);
                    }
                }
            }
            case I32, U32 -> {
                for (int i = 0; i < FastLanes.CHUNK; i++) {
                    long at = base + FastLanes.transposeIndex(i);
                    if (Long.compareUnsigned(at, rowCount) < 0) {
                        out.setAtIndex(VortexFormat.LE_INT, at, (int) values[i]);
                    }
                }
            }
            case I64, U64 -> {
                for (int i = 0; i < FastLanes.CHUNK; i++) {
                    long at = base + FastLanes.transposeIndex(i);
                    if (Long.compareUnsigned(at, rowCount) < 0) {
                        out.setAtIndex(VortexFormat.LE_LONG, at, values[i]);
                    }
                }
            }
            default -> throw new VortexException(EncodingId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
        }
    }

    /// Reads `count` consecutive elements starting at logical index `firstIdx`, widened to
    /// `long`.
    ///
    /// Branch-split on whether the segment physically holds the range (CLAUDE.md hot-loop
    /// rule): the fast path is a uniform, modulo-free loop per ptype, and the wrap-around
    /// arithmetic stays on the cold path, where it is only ever reached by a
    /// `vortex.constant` child that stores one element for the whole array.
    ///
    /// @param buf      the child segment
    /// @param ptype    element type
    /// @param cap      elements physically present in `buf`
    /// @param firstIdx logical index of the first element to read
    /// @param count    number of elements to read
    /// @param out      destination scratch, at least `count` long
    /// @throws VortexException if `buf` holds no elements at all
    private static void readElements(MemorySegment buf, PType ptype, long cap, long firstIdx,
            int count, long[] out) {
        if (firstIdx + count <= cap) {
            PrimitiveArrays.toLongsInto(buf, firstIdx, count, ptype, EncodingId.FASTLANES_DELTA, out);
            return;
        }
        if (cap == 0) {
            throw new VortexException(EncodingId.FASTLANES_DELTA,
                    "empty child segment for " + count + " element(s) of " + ptype);
        }
        readBroadcast(buf, ptype, cap, firstIdx, count, out);
    }

    /// Cold path of [#readElements]: the child holds fewer elements than the range asks for,
    /// which only a `vortex.constant` child does, so this wraps around it one element at a time.
    private static void readBroadcast(MemorySegment buf, PType ptype, long cap, long firstIdx,
            int count, long[] out) {
        int elemBytes = ptype.byteSize();
        for (int i = 0; i < count; i++) {
            out[i] = PrimitiveArrays.readLong(buf, ((firstIdx + i) % cap) * elemBytes, ptype, EncodingId.FASTLANES_DELTA);
        }
    }
}
