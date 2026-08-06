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
/// Delta genuinely has to reconstruct values — each depends on its predecessor — so unlike the
/// dict/runend/sequence encodings there is no lazy carrier to return. What it must not do is
/// stage that reconstruction on the heap: the values are written straight into one arena
/// segment at the column's own width, with only fixed-size per-chunk scratch in between.
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

        if (deltasLen == 0L) {
            return typedArray(ctx.dtype(), ptype, 0L, ctx.arena().allocate(0));
        }

        long basesLen = (deltasLen / FastLanes.CHUNK) * lanes;
        DType dtype = ctx.dtype();

        MemorySegment basesSeg = ctx.decodeChildSegment(0, dtype, basesLen);
        MemorySegment deltasSeg = ctx.decodeChildSegment(1, dtype, deltasLen);
        long basesCap = SegmentBroadcast.capacity(basesSeg, ptype.byteSize());
        long deltasCap = SegmentBroadcast.capacity(deltasSeg, ptype.byteSize());

        // The only row-scaled allocation: the output itself, off-heap and at the column's own
        // width. Everything below is fixed-size scratch — one chunk's worth, cache-resident,
        // reused across chunks.
        MemorySegment out = ctx.arena().allocate(rowCount * ptype.byteSize());
        long[] chunkBases = new long[lanes];
        long[] chunkDeltas = new long[FastLanes.CHUNK];
        long[] chunkUndelta = new long[FastLanes.CHUNK];

        int numChunks = (int) (deltasLen / FastLanes.CHUNK);
        for (int chunk = 0; chunk < numChunks; chunk++) {
            long basesOff = (long) chunk * lanes;
            long deltaOff = (long) chunk * FastLanes.CHUNK;

            readInto(basesSeg, basesOff, lanes, ptype, basesCap, chunkBases);
            readInto(deltasSeg, deltaOff, FastLanes.CHUNK, ptype, deltasCap, chunkDeltas);

            undeltaChunk(chunkDeltas, chunkBases, lanes, typeBits, mask, chunkUndelta);

            // Untranspose and window-shift in one step: the value at in-chunk position `i`
            // belongs at logical index `deltaOff + transposeIndex(i)`, and the window drops the
            // leading `offset` of those. Writing it straight out removes both the full-length
            // `decoded` staging array and the `arraycopy` that used to slice it.
            scatterChunk(out, chunkUndelta, deltaOff - offset, rowCount, ptype);
        }

        return typedArray(dtype, ptype, rowCount, out.asReadOnly());
    }

    /// Writes one untransposed chunk into the output window.
    ///
    /// `base` is the output index the chunk's logical position 0 maps to; it is negative for
    /// the leading chunk whenever the array is offset-sliced, and the trailing chunk can run
    /// past `rowCount`. Both are folded into a single unsigned comparison per element — the
    /// stores are a permutation scatter (`transposeIndex`), so they never vectorize regardless
    /// and the extra compare costs nothing the untranspose was not already paying. The ptype
    /// switch is hoisted out of the loop so each body stays uniform (CLAUDE.md hot-loop rule).
    ///
    /// @param out      output segment of `rowCount` elements
    /// @param values   one chunk of reconstructed values, in transposed order
    /// @param base     output index of the chunk's logical position 0 (may be negative)
    /// @param rowCount number of rows in the output window
    /// @param ptype    output element type
    private static void scatterChunk(MemorySegment out, long[] values, long base, long rowCount, PType ptype) {
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

    /// Reads `count` elements starting at element `from` into fixed-size scratch, widening to
    /// `long`.
    ///
    /// Branch-split on whether the segment actually covers the range: the common path indexes
    /// directly, and only an undersized child (the `ConstantEncoding` fan-out) pays the
    /// broadcast modulo. Both variants hoist the ptype switch out of the loop. The previous
    /// version did neither — one `i % cap` and one `switch (ptype)` per element, in a loop that
    /// ran over every value in the column.
    ///
    /// @param buf   source segment
    /// @param from  first element index to read
    /// @param count number of elements to read
    /// @param ptype element type
    /// @param cap   physical element count of `buf`
    /// @param out   scratch array of at least `count` entries
    private static void readInto(MemorySegment buf, long from, int count, PType ptype, long cap, long[] out) {
        if (cap == 0) {
            throw new VortexException(EncodingId.FASTLANES_DELTA, "empty delta child segment");
        }
        if (from + count <= cap) {
            readDirect(buf, from, count, ptype, out);
        } else {
            readBroadcast(buf, from, count, ptype, cap, out);
        }
    }

    private static void readDirect(MemorySegment buf, long from, int count, PType ptype, long[] out) {
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

    /// Broadcast variant, for an undersized child only.
    ///
    /// Strength-reduced to a rolling index: exactly one `%` runs, before the loop, and the
    /// wrap becomes a compare-and-reset that is correctly predicted every iteration but the
    /// `cap`-th. A per-element `idx % cap` would be a 20–40 cycle divide on Apple silicon and
    /// blocks C2 superword outright — the repeated cause of 5–10x regressions in this codebase
    /// (CLAUDE.md hot-loop rule; `ed658b7` -> `051a794` -> `442021f`). Reading the cycle into a
    /// scratch array instead would reintroduce a `cap`-sized heap allocation, which is the
    /// thing this rewrite exists to remove.
    ///
    /// @param buf   source segment
    /// @param from  first logical element index to read
    /// @param count number of elements to read
    /// @param ptype element type
    /// @param cap   physical element count of `buf` (≥ 1)
    /// @param out   scratch array of at least `count` entries
    private static void readBroadcast(MemorySegment buf, long from, int count, PType ptype, long cap, long[] out) {
        long start = from % cap;
        switch (ptype) {
            case I8 -> {
                for (int i = 0, at = (int) start; i < count; i++, at = at + 1 == cap ? 0 : at + 1) {
                    out[i] = buf.get(ValueLayout.JAVA_BYTE, at);
                }
            }
            case U8 -> {
                for (int i = 0, at = (int) start; i < count; i++, at = at + 1 == cap ? 0 : at + 1) {
                    out[i] = Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, at));
                }
            }
            case I16 -> {
                for (int i = 0, at = (int) start; i < count; i++, at = at + 1 == cap ? 0 : at + 1) {
                    out[i] = buf.getAtIndex(VortexFormat.LE_SHORT, at);
                }
            }
            case U16 -> {
                for (int i = 0, at = (int) start; i < count; i++, at = at + 1 == cap ? 0 : at + 1) {
                    out[i] = Short.toUnsignedLong(buf.getAtIndex(VortexFormat.LE_SHORT, at));
                }
            }
            case I32 -> {
                for (int i = 0, at = (int) start; i < count; i++, at = at + 1 == cap ? 0 : at + 1) {
                    out[i] = buf.getAtIndex(VortexFormat.LE_INT, at);
                }
            }
            case U32 -> {
                for (int i = 0, at = (int) start; i < count; i++, at = at + 1 == cap ? 0 : at + 1) {
                    out[i] = Integer.toUnsignedLong(buf.getAtIndex(VortexFormat.LE_INT, at));
                }
            }
            case I64, U64 -> {
                for (int i = 0, at = (int) start; i < count; i++, at = at + 1 == cap ? 0 : at + 1) {
                    out[i] = buf.getAtIndex(VortexFormat.LE_LONG, at);
                }
            }
            default -> throw new VortexException(EncodingId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
        }
    }

    private static Array typedArray(DType dtype, PType ptype, long n, MemorySegment seg) {
        return switch (ptype) {
            case I64, U64 -> new MaterializedLongArray(dtype, n, seg);
            case I32, U32 -> new MaterializedIntArray(dtype, n, seg);
            case I16, U16 -> new MaterializedShortArray(dtype, n, seg);
            case I8, U8 -> new MaterializedByteArray(dtype, n, seg);
            default -> throw new VortexException(EncodingId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
        };
    }
}
