package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.IoBounds;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoRLEMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.LazyConstantByteArray;
import io.github.dfa1.vortex.reader.array.LazyConstantDoubleArray;
import io.github.dfa1.vortex.reader.array.LazyConstantFloatArray;
import io.github.dfa1.vortex.reader.array.LazyConstantIntArray;
import io.github.dfa1.vortex.reader.array.LazyConstantLongArray;
import io.github.dfa1.vortex.reader.array.LazyConstantShortArray;
import io.github.dfa1.vortex.reader.array.LazyRleBoolArray;
import io.github.dfa1.vortex.reader.array.LazyRleByteArray;
import io.github.dfa1.vortex.reader.array.LazyRleDoubleArray;
import io.github.dfa1.vortex.reader.array.LazyRleFloatArray;
import io.github.dfa1.vortex.reader.array.LazyRleIntArray;
import io.github.dfa1.vortex.reader.array.LazyRleLongArray;
import io.github.dfa1.vortex.reader.array.LazyRleShortArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.OffsetBoolArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for `fastlanes.rle`.
public final class RleEncodingDecoder implements EncodingDecoder {

    private static final int FL_CHUNK_SIZE = 1024;

    @Override
    public EncodingId encodingId() {
        return EncodingId.FASTLANES_RLE;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment rawMeta = ctx.metadata();
        ProtoRLEMetadata meta;
        try {
            meta = ProtoRLEMetadata.decode(rawMeta, 0, rawMeta.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.FASTLANES_RLE, "invalid metadata", e);
        }

        long valuesLen = meta.values_len();
        long indicesLen = meta.indices_len();
        PType indicesPtype = PType.fromOrdinal(meta.indices_ptype().value());
        long offsetsLen = meta.values_idx_offsets_len();
        PType offsetsPtype = PType.fromOrdinal(meta.values_idx_offsets_ptype().value());
        int offset = (int) meta.offset();

        long rowCount = ctx.rowCount();
        if (rowCount == 0 || indicesLen == 0) {
            return emptyArray(ctx);
        }

        // Both lengths are attacker-controlled and size the segments the lazy carriers read
        // through, so they pass the ADR 0004 count guard before anything is decoded. A run-value
        // pool can never legitimately outgrow the per-row index table that selects from it.
        IoBounds.checkCount(valuesLen);
        IoBounds.checkCount(indicesLen);
        if (valuesLen > indicesLen) {
            throw new VortexException(EncodingId.FASTLANES_RLE,
                    "values_len " + valuesLen + " exceeds indices_len " + indicesLen);
        }
        int numChunks = (int) (indicesLen / FL_CHUNK_SIZE);

        DType indicesDtype = new DType.Primitive(indicesPtype, false);
        DType offsetsDtype = new DType.Primitive(offsetsPtype, false);

        Array indicesRaw = ctx.decodeChild(1, indicesDtype, indicesLen);

        BoolArray indicesValidity = null;
        Array indicesArr = indicesRaw;
        if (indicesRaw instanceof MaskedArray masked) {
            indicesArr = masked.inner();
            indicesValidity = masked.validity();
        }

        boolean wideIndices = switch (indicesPtype) {
            case U8 -> false;
            case U16 -> true;
            default ->
                    throw new VortexException(EncodingId.FASTLANES_RLE, "unsupported indices ptype: " + indicesPtype);
        };
        MemorySegment indices = fitElements(ctx.arena(), ctx.materialize(indicesArr),
                indicesLen, indicesPtype.byteSize());

        // Only one offset per chunk is ever read, so a bogus values_idx_offsets_len cannot size
        // the array beyond that — the chunk count is already bounded by the indices length above.
        int offsetsCount = IoBounds.checkCount(Math.min(offsetsLen, numChunks + 1L));
        long[] valuesIdxOffsets = readUnsignedLongs(
                fitElements(ctx.arena(), ctx.decodeChildSegment(2, offsetsDtype, offsetsLen),
                        offsetsCount, offsetsPtype.byteSize()),
                offsetsCount, offsetsPtype);
        long firstOffset = valuesLen > 0 && valuesIdxOffsets.length > 0 ? valuesIdxOffsets[0] : 0L;
        checkChunkOffsets(valuesIdxOffsets, firstOffset, valuesLen, numChunks);

        if (ctx.dtype() instanceof DType.Bool) {
            Array valuesArr = ctx.decodeChild(0, DType.BOOL, valuesLen);
            Array valuesData = valuesArr instanceof MaskedArray m ? m.inner() : valuesArr;
            Array boolResult = new LazyRleBoolArray(ctx.dtype(), rowCount, (BoolArray) valuesData,
                    indices, wideIndices, valuesIdxOffsets, firstOffset, valuesLen, numChunks, offset);
            if (indicesValidity == null) {
                return boolResult;
            }
            return new MaskedArray(boolResult, new OffsetBoolArray(DType.BOOL, rowCount, indicesValidity, offset));
        }

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.FASTLANES_RLE, "expected Primitive dtype, got " + ctx.dtype());
        }
        PType ptype = p.ptype();
        DType valuesDtype = new DType.Primitive(ptype, false);

        MemorySegment values = fitElements(ctx.arena(), ctx.decodeChildSegment(0, valuesDtype, valuesLen),
                valuesLen, ptype.byteSize());
        Array result = switch (ptype) {
            case I64, U64 -> new LazyRleLongArray(ctx.dtype(), rowCount, values,
                    indices, wideIndices, valuesIdxOffsets, firstOffset, valuesLen, numChunks, offset);
            case I32, U32 -> new LazyRleIntArray(ctx.dtype(), rowCount, values,
                    indices, wideIndices, valuesIdxOffsets, firstOffset, valuesLen, numChunks, offset);
            case I16, U16 -> new LazyRleShortArray(ctx.dtype(), rowCount, values,
                    indices, wideIndices, valuesIdxOffsets, firstOffset, valuesLen, numChunks, offset,
                    ptype == PType.U16);
            case I8, U8 -> new LazyRleByteArray(ctx.dtype(), rowCount, values,
                    indices, wideIndices, valuesIdxOffsets, firstOffset, valuesLen, numChunks, offset,
                    ptype == PType.U8);
            case F64 -> new LazyRleDoubleArray(ctx.dtype(), rowCount, values,
                    indices, wideIndices, valuesIdxOffsets, firstOffset, valuesLen, numChunks, offset);
            case F32 -> new LazyRleFloatArray(ctx.dtype(), rowCount, values,
                    indices, wideIndices, valuesIdxOffsets, firstOffset, valuesLen, numChunks, offset);
            default -> throw new VortexException(EncodingId.FASTLANES_RLE, "unsupported ptype " + ptype);
        };

        if (indicesValidity == null) {
            return result;
        }
        BoolArray outputValidity = new OffsetBoolArray(DType.BOOL, rowCount, indicesValidity, offset);
        return new MaskedArray(result, outputValidity);
    }

    private static Array emptyArray(DecodeContext ctx) {
        DType dt = ctx.dtype();
        PType ptype = ((DType.Primitive) dt).ptype();
        return switch (ptype) {
            case I64, U64 -> new LazyConstantLongArray(dt, 0L, 0L);
            case I32, U32 -> new LazyConstantIntArray(dt, 0L, 0);
            case I16, U16 -> new LazyConstantShortArray(dt, 0L, (short) 0);
            case I8, U8 -> new LazyConstantByteArray(dt, 0L, (byte) 0);
            case F64 -> new LazyConstantDoubleArray(dt, 0L, 0.0);
            case F32 -> new LazyConstantFloatArray(dt, 0L, 0.0f);
            default -> throw new VortexException(EncodingId.FASTLANES_RLE, "unsupported ptype " + ptype);
        };
    }

    /// Returns a view of `buf` holding exactly `count` elements, for the `LazyRleXxxArray`
    /// records to read through.
    ///
    /// Normally that is a zero-copy slice of the mmapped child segment. A `ConstantEncoding`
    /// child deliberately stores one element regardless of the length it declares (zip-bomb
    /// defense), and that lone element is broadcast once into a fresh arena segment here — so the
    /// per-row loops in the records need no `i % cap` at all (CLAUDE.md hot-loop rule).
    ///
    /// Any other short child is a shape mismatch — a malformed file declaring more elements than
    /// it stores — and is rejected rather than silently wrapped around. `count` is expected to
    /// have passed [IoBounds#checkCount(long)] already, so the broadcast allocation is bounded.
    ///
    /// Package-private (not private) so the broadcast and rejection branches are testable
    /// directly; both are reachable through `decode()` on malformed input.
    ///
    /// @param arena     allocator for the broadcast copy, when one is needed
    /// @param buf       decoded child segment
    /// @param count     number of elements the caller needs
    /// @param elemBytes element width in bytes
    /// @return a segment of exactly `count * elemBytes` bytes
    /// @throws VortexException if `count` is out of range, or `buf` holds neither `count`
    ///         elements nor the single element of a constant child
    static MemorySegment fitElements(SegmentAllocator arena, MemorySegment buf, long count, int elemBytes) {
        long bytes = (long) IoBounds.checkCount(count) * elemBytes;
        if (buf.byteSize() >= bytes) {
            return IoBounds.slice(buf, 0, bytes);
        }
        long capacity = SegmentBroadcast.capacity(buf, elemBytes);
        if (capacity != 1) {
            throw new VortexException(EncodingId.FASTLANES_RLE,
                    "child holds " + capacity + " element(s) for a declared length of " + count);
        }
        MemorySegment out = arena.allocate(bytes, elemBytes);
        SegmentBroadcast.broadcastCopy(buf, out, count, elemBytes);
        return out;
    }

    /// Verifies that every chunk's slice of the values pool lies inside it.
    ///
    /// The offsets come from file bytes, and the records index the values segment at
    /// `valuesIdxOffsets[chunk] - firstOffset + localIdx`. Checking here that the offsets are
    /// non-decreasing and stay within `[0, valuesLen]` makes every one of those reads in-bounds
    /// by construction, so a malformed offsets table fails as [VortexException] instead of
    /// escaping later as a raw `IndexOutOfBoundsException` (ADR 0003).
    ///
    /// @param valuesIdxOffsets per-chunk values-pool start offsets
    /// @param firstOffset      absolute origin of the values pool
    /// @param valuesLen        total values pool length
    /// @param numChunks        number of FastLanes chunks the indices table covers
    /// @throws VortexException if an offset is missing, out of order, or past the pool
    private static void checkChunkOffsets(long[] valuesIdxOffsets, long firstOffset, long valuesLen,
            int numChunks) {
        if (valuesIdxOffsets.length < numChunks) {
            throw new VortexException(EncodingId.FASTLANES_RLE, "values_idx_offsets holds "
                    + valuesIdxOffsets.length + " entries for " + numChunks + " chunk(s)");
        }
        long previous = 0L;
        for (int chunk = 0; chunk < numChunks; chunk++) {
            long start = valuesIdxOffsets[chunk] - firstOffset;
            if (start < previous || start > valuesLen) {
                throw new VortexException(EncodingId.FASTLANES_RLE, "chunk " + chunk
                        + " starts at " + start + " in a values pool of " + valuesLen);
            }
            previous = start;
        }
    }

    /// Widens `count` unsigned offsets out of an exact-length segment.
    ///
    /// The ptype test is hoisted out of the loop — one specialized body per width rather than a
    /// per-element switch (CLAUDE.md hot-loop rule).
    ///
    /// @param buf   offsets segment, already sized to `count` elements by [#fitElements]
    /// @param count number of offsets to read
    /// @param ptype unsigned physical type of the offsets
    /// @return the widened offsets
    /// @throws VortexException if `ptype` is not an unsigned integer type
    private static long[] readUnsignedLongs(MemorySegment buf, int count, PType ptype) {
        long[] out = new long[count];
        switch (ptype) {
            case U8 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, i));
                }
            }
            case U16 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = Short.toUnsignedLong(buf.getAtIndex(VortexFormat.LE_SHORT, i));
                }
            }
            case U32 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = Integer.toUnsignedLong(buf.getAtIndex(VortexFormat.LE_INT, i));
                }
            }
            case U64 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = buf.getAtIndex(VortexFormat.LE_LONG, i);
                }
            }
            default -> throw new VortexException(EncodingId.FASTLANES_RLE, "unsupported offsets ptype: " + ptype);
        }
        return out;
    }
}
