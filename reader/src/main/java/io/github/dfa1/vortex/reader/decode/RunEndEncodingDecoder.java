package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoRunEndMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndBoolArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndByteArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndIntArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndLongArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndShortArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for `vortex.runend`.
public final class RunEndEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_RUNEND;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment rawMeta = ctx.metadata();
        if (rawMeta == null) {
            throw new VortexException(EncodingId.VORTEX_RUNEND, "missing metadata");
        }

        ProtoRunEndMetadata meta;
        try {
            meta = ProtoRunEndMetadata.decode(rawMeta, 0, rawMeta.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_RUNEND, "invalid metadata", e);
        }

        PType endsPtype = PType.fromOrdinal(meta.ends_ptype().value());
        long numRuns = meta.num_runs();
        long offset = meta.offset();

        long n = ctx.rowCount();
        if (numRuns < 0) {
            throw new VortexException(EncodingId.VORTEX_RUNEND, "runend: negative num_runs " + numRuns);
        }
        if (numRuns == 0 && n > 0) {
            // Zero runs cover no rows — a crafted file pairing that with a non-empty row
            // count previously decoded "successfully" into a LazyRunEndXxxArray backed by
            // an empty ends/values child, then threw a raw IndexOutOfBoundsException (or
            // ArithmeticException via the % elementCount broadcast path) on first read
            // instead of failing here as a VortexException.
            throw new VortexException(EncodingId.VORTEX_RUNEND,
                    "runend: zero runs cannot cover " + n + " row(s)");
        }
        DType endsDtype = new DType.Primitive(endsPtype, false);
        Array endsArr = ctx.decodeChild(0, endsDtype, numRuns);
        Array endsData = endsArr instanceof MaskedArray m ? m.inner() : endsArr;
        MemorySegment endsSeg = ctx.materialize(endsData);
        validateEnds(endsSeg, endsPtype, numRuns, offset, n);

        // Values-side validity mirrors the Rust reference `ValidityVTable<RunEnd>`: a
        // RunEnd array's validity IS a RunEnd over the same ends whose per-run value is
        // the run-value's validity bit. Row i's validity is thus the validity of the run
        // it falls in — expressed lazily as `LazyRunEndBoolArray`, O(numRuns) not O(n)
        // (n can be huge, e.g. uci-online-retail 499712 rows). A nullable values child
        // surfaces as a MaskedArray; dropping its mask expanded null runs to a filler
        // value (e.g. u16? null rows emitting the FoR base) — #225.
        Array valuesArr = ctx.decodeChild(1, ctx.dtype(), numRuns);
        BoolArray valuesValidity = null;
        Array valuesData = valuesArr;
        if (valuesArr instanceof MaskedArray m) {
            valuesData = m.inner();
            valuesValidity = m.validity();
        }

        if (ctx.dtype() instanceof DType.Utf8 || ctx.dtype() instanceof DType.Binary) {
            Array result = expandStrings(endsSeg, VarBinArray.toOffsetMode((VarBinArray) valuesData, ctx.arena()),
                    endsPtype, numRuns, offset, n, ctx.dtype(), ctx.arena());
            return withRunValidity(result, valuesValidity, endsData, n, offset);
        }

        if (ctx.dtype() instanceof DType.Bool) {
            Array result = new LazyRunEndBoolArray(ctx.dtype(), n, (BoolArray) valuesData, endsData, offset);
            return withRunValidity(result, valuesValidity, endsData, n, offset);
        }

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_RUNEND, "expected primitive dtype, got " + ctx.dtype());
        }
        PType valuePtype = p.ptype();

        // Lazy path: wrap values + ends without expanding into an n-sized buffer.
        // VarBin keeps the eager path above — offset rebasing doesn't trivially
        // express as binary-search-on-read.
        Array result = switch (valuePtype) {
            case I64, U64 -> new LazyRunEndLongArray(ctx.dtype(), n, (LongArray) valuesData, endsData, offset);
            case I32, U32 -> new LazyRunEndIntArray(ctx.dtype(), n, (IntArray) valuesData, endsData, offset);
            case I16, U16 -> new LazyRunEndShortArray(ctx.dtype(), n, (ShortArray) valuesData, endsData, offset);
            case I8, U8 -> new LazyRunEndByteArray(ctx.dtype(), n, (ByteArray) valuesData, endsData, offset);
            default -> throw new VortexException(EncodingId.VORTEX_RUNEND, "unsupported ptype " + valuePtype);
        };
        return withRunValidity(result, valuesValidity, endsData, n, offset);
    }

    /// Wraps `result` in a [MaskedArray] whose per-row validity is a run-end bool array
    /// over the same run-ends: row `i` is valid iff the run it falls in has a valid value.
    /// Returns `result` unchanged when the values child carried no validity (all valid).
    ///
    /// @param result         the decoded run-end value array
    /// @param valuesValidity per-run validity bits from the values child, or `null`
    /// @param endsData       the run-ends array (raw, mask-unwrapped)
    /// @param n              logical row count
    /// @param offset         starting absolute position
    /// @return `result`, or a [MaskedArray] carrying the lazy per-row validity
    private static Array withRunValidity(Array result, BoolArray valuesValidity, Array endsData, long n, long offset) {
        if (valuesValidity == null) {
            return result;
        }
        BoolArray rowValidity = new LazyRunEndBoolArray(DType.BOOL, n, valuesValidity, endsData, offset);
        return new MaskedArray(result, rowValidity);
    }

    /// Validates `ends` against the format's write-side contract that the reference reader does
    /// not itself enforce — the spec's note on this encoding is explicit: "a conformant reader
    /// SHOULD validate \[strict-increase and the two-children shape\] itself rather than assume
    /// them" (`encoding-format/dict-runend-sparse.md` §RunEnd). One O(numRuns) pass checks:
    /// `ends` strictly increasing; `ends[0] >= offset` when sliced; and `ends[numRuns-1] >=
    /// offset + n` — the runs must cover the full requested window (trailing runs beyond it are
    /// legal per the offset-aware slicing model and simply go unused, so this is `>=`, not `==`).
    /// Every violation here previously decoded without error and either silently repeated the
    /// last run's value past where the data actually ends, or (for `ends[0] < offset`) resolved a
    /// negative index.
    private static void validateEnds(MemorySegment endsSeg, PType endsPtype, long numRuns, long offset, long n) {
        long endsCap = SegmentBroadcast.capacity(endsSeg, endsPtype.byteSize());
        if (endsCap <= 0) {
            throw new VortexException(EncodingId.VORTEX_RUNEND,
                    "runend: empty ends buffer for " + numRuns + " run(s)");
        }
        long prev = readUnsigned(endsSeg, 0, endsPtype);
        if (offset != 0 && prev < offset) {
            throw new VortexException(EncodingId.VORTEX_RUNEND,
                    "runend: ends[0]=" + prev + " < offset " + offset);
        }
        for (long i = 1; i < numRuns; i++) {
            long end = readUnsigned(endsSeg, i % endsCap, endsPtype);
            if (end <= prev) {
                throw new VortexException(EncodingId.VORTEX_RUNEND,
                        "runend: ends not strictly increasing at run " + i + " (" + end + " <= " + prev + ")");
            }
            prev = end;
        }
        if (prev < offset + n) {
            throw new VortexException(EncodingId.VORTEX_RUNEND,
                    "runend: last end " + prev + " does not cover offset+n=" + (offset + n));
        }
    }

    private static Array expandStrings(
            MemorySegment endsSeg, VarBinArray.OffsetMode valuesArr,
            PType endsPtype, long numRuns, long offset, long n,
            DType dtype, SegmentAllocator arena
    ) {
        long endsCap = SegmentBroadcast.capacity(endsSeg, endsPtype.byteSize());
        MemorySegment valBytes = valuesArr.bytesSegment();
        MemorySegment valOffsets = valuesArr.offsetsSegment();
        PType valOffPtype = valuesArr.offsetsPtype();

        long totalBytes = 0;
        long logicalPos = 0;
        for (long run = 0; run < numRuns; run++) {
            long runEnd = readUnsigned(endsSeg, run % endsCap, endsPtype);
            long lo = Math.max(logicalPos, offset);
            long hi = Math.min(runEnd, offset + n);
            long count = Math.max(0, hi - lo);
            long strLen = readVarBinOffset(valOffsets, run + 1, valOffPtype)
                                  - readVarBinOffset(valOffsets, run, valOffPtype);
            totalBytes += count * strLen;
            logicalPos = runEnd;
        }

        MemorySegment outBytes = arena.allocate(totalBytes > 0 ? totalBytes : 1);
        MemorySegment outOffsets = arena.allocate((n + 1) * 4L, 4);
        outOffsets.setAtIndex(VortexFormat.LE_INT, 0, 0);

        long bytePos = 0;
        long outIdx = 0;
        logicalPos = 0;
        for (long run = 0; run < numRuns && outIdx < n; run++) {
            long runEnd = readUnsigned(endsSeg, run % endsCap, endsPtype);
            long lo = Math.max(logicalPos, offset);
            long hi = Math.min(runEnd, offset + n);
            if (hi > lo) {
                long strStart = readVarBinOffset(valOffsets, run, valOffPtype);
                long strEnd = readVarBinOffset(valOffsets, run + 1, valOffPtype);
                long strLen = strEnd - strStart;
                for (long lp = lo; lp < hi; lp++, outIdx++) {
                    if (strLen > 0) {
                        MemorySegment.copy(valBytes, strStart, outBytes, bytePos, strLen);
                        bytePos += strLen;
                    }
                    outOffsets.setAtIndex(VortexFormat.LE_INT, outIdx + 1, (int) bytePos);
                }
            }
            logicalPos = runEnd;
        }

        return new VarBinArray.OffsetMode(dtype, n, outBytes.asReadOnly(), outOffsets.asReadOnly(), PType.I32);
    }

    private static long readUnsigned(MemorySegment seg, long i, PType ptype) {
        return switch (ptype) {
            case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, i));
            case U16 -> Short.toUnsignedLong(seg.get(VortexFormat.LE_SHORT, i * 2));
            case U32 -> Integer.toUnsignedLong(seg.get(VortexFormat.LE_INT, i * 4));
            case U64 -> seg.get(VortexFormat.LE_LONG, i * 8);
            default -> throw new VortexException(EncodingId.VORTEX_RUNEND, "non-unsigned ends ptype " + ptype);
        };
    }

    private static long readVarBinOffset(MemorySegment seg, long i, PType ptype) {
        return switch (ptype) {
            case I32, U32 -> Integer.toUnsignedLong(seg.getAtIndex(VortexFormat.LE_INT, i));
            case I64, U64 -> seg.getAtIndex(VortexFormat.LE_LONG, i);
            default -> throw new VortexException(EncodingId.VORTEX_RUNEND, "unsupported offset ptype " + ptype);
        };
    }
}
