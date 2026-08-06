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
import io.github.dfa1.vortex.reader.array.VarBinRunEndArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
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
            // The values child is untrusted: an all-null run column decodes to NullArray, and a
            // crafted file can name any encoding here. A raw ClassCastException would violate
            // ADR 0003, so reject a non-VarBin child explicitly.
            if (!(valuesData instanceof VarBinArray varBinValues)) {
                throw new VortexException(EncodingId.VORTEX_RUNEND,
                        "runend: expected a VarBin values child for " + ctx.dtype() + ", got "
                                + valuesData.getClass().getSimpleName());
            }
            Array result = new VarBinRunEndArray(ctx.dtype(), n, varBinValues, endsData, offset);
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

    private static long readUnsigned(MemorySegment seg, long i, PType ptype) {
        return switch (ptype) {
            case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, i));
            case U16 -> Short.toUnsignedLong(seg.get(VortexFormat.LE_SHORT, i * 2));
            case U32 -> Integer.toUnsignedLong(seg.get(VortexFormat.LE_INT, i * 4));
            case U64 -> seg.get(VortexFormat.LE_LONG, i * 8);
            default -> throw new VortexException(EncodingId.VORTEX_RUNEND, "non-unsigned ends ptype " + ptype);
        };
    }
}
