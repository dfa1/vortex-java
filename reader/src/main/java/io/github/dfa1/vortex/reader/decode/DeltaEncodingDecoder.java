package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoDeltaMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;

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
        long rowCount = ctx.rowCount();
        int typeBits = ptype.bits();
        int lanes = FastLanes.lanes(ptype);
        long mask = FastLanes.lowMask(ptype.bits());

        long deltasLen = meta.deltas_len();
        int offset = meta.offset();

        if (deltasLen == 0L || rowCount == 0L) {
            return array(ctx, ptype, 0L, ctx.arena().allocate(0));
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
            undeltaChunk(chunkDeltas, chunkBases, lanes, typeBits, mask, chunkUndelta);
            scatterChunk(out, ptype, chunkUndelta, chunk * FastLanes.CHUNK - offset, rowCount);
        }
        return array(ctx, ptype, rowCount, out.asReadOnly());
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

    /// Wraps a decoded segment in the `Materialized*Array` matching `ptype`.
    ///
    /// @param ctx    decode context, source of the logical dtype
    /// @param ptype  physical type of the values in `seg`
    /// @param length row count
    /// @param seg    the decoded values, little-endian at `ptype`'s width
    /// @return the typed array view over `seg`
    /// @throws VortexException if `ptype` is not an integer ptype
    private static Array array(DecodeContext ctx, PType ptype, long length, MemorySegment seg) {
        return switch (ptype) {
            case I64, U64 -> new MaterializedLongArray(ctx.dtype(), length, seg);
            case I32, U32 -> new MaterializedIntArray(ctx.dtype(), length, seg);
            case I16, U16 -> new MaterializedShortArray(ctx.dtype(), length, seg);
            case I8, U8 -> new MaterializedByteArray(ctx.dtype(), length, seg);
            default -> throw new VortexException(EncodingId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
        };
    }

    private static void undeltaChunk(long[] deltas, long[] bases, int lanes, int typeBits, long mask, long[] out) {
        for (int lane = 0; lane < lanes; lane++) {
            long prev = bases[lane] & mask;
            for (int row = 0; row < typeBits; row++) {
                int idx = FastLanes.iterateIndex(row, lane);
                long next = ((deltas[idx] & mask) + prev) & mask;
                out[idx] = next;
                prev = next;
            }
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
            readContiguous(buf, ptype, firstIdx, count, out);
            return;
        }
        if (cap == 0) {
            throw new VortexException(EncodingId.FASTLANES_DELTA,
                    "empty child segment for " + count + " element(s) of " + ptype);
        }
        readBroadcast(buf, ptype, cap, firstIdx, count, out);
    }

    private static void readContiguous(MemorySegment buf, PType ptype, long from, int count, long[] out) {
        switch (ptype) {
            case I8 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = buf.get(ValueLayout.JAVA_BYTE, from + i);
                }
            }
            case U8 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, from + i));
                }
            }
            case I16 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = buf.getAtIndex(VortexFormat.LE_SHORT, from + i);
                }
            }
            case U16 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = Short.toUnsignedLong(buf.getAtIndex(VortexFormat.LE_SHORT, from + i));
                }
            }
            case I32 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = buf.getAtIndex(VortexFormat.LE_INT, from + i);
                }
            }
            case U32 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = Integer.toUnsignedLong(buf.getAtIndex(VortexFormat.LE_INT, from + i));
                }
            }
            case I64, U64 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = buf.getAtIndex(VortexFormat.LE_LONG, from + i);
                }
            }
            default -> throw new VortexException(EncodingId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
        }
    }

    /// Cold path of [#readElements]: the child holds fewer elements than the range asks for,
    /// which only a `vortex.constant` child does, so this wraps around it one element at a time.
    private static void readBroadcast(MemorySegment buf, PType ptype, long cap, long firstIdx,
            int count, long[] out) {
        int elemBytes = ptype.byteSize();
        for (int i = 0; i < count; i++) {
            out[i] = readOne(buf, ptype, ((firstIdx + i) % cap) * elemBytes);
        }
    }

    private static long readOne(MemorySegment buf, PType ptype, long off) {
        return switch (ptype) {
            case I8 -> buf.get(ValueLayout.JAVA_BYTE, off);
            case U8 -> Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, off));
            case I16 -> buf.get(VortexFormat.LE_SHORT, off);
            case U16 -> Short.toUnsignedLong(buf.get(VortexFormat.LE_SHORT, off));
            case I32 -> buf.get(VortexFormat.LE_INT, off);
            case U32 -> Integer.toUnsignedLong(buf.get(VortexFormat.LE_INT, off));
            case I64, U64 -> buf.get(VortexFormat.LE_LONG, off);
            default -> throw new VortexException(EncodingId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
        };
    }


}
