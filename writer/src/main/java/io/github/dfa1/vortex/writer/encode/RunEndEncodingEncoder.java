package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.proto.ProtoRunEndMetadata;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.runend`.
public final class RunEndEncodingEncoder implements EncodingEncoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_RUNEND;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive p && !p.ptype().isFloating();
    }

    @Override
    public StatsOptions statsOptions() {
        return new StatsOptions(true, true);
    }

    @Override
    public Estimate expectedRatio(DType dtype, Object data, ArrayStats stats) {
        if (!(dtype instanceof DType.Primitive) || !stats.hasDistinctCount()) {
            return Estimate.COMPLETE;
        }
        long n = stats.valueCount();
        long distinct = stats.distinctCount();
        if (n == 0) {
            return Estimate.SKIP;
        }
        // The only consumer the capped scan does not settle: `distinct >= n` needs the exact
        // count, and a capped scan only proves distinct > n/2 + 1. Defer to the sample instead
        // of guessing — a 1024-row sample encode is far cheaper than the probes the cap saved.
        if (stats.distinctCapped()) {
            return Estimate.COMPLETE;
        }
        // Skip rule: if every value is distinct, each row is its own run — pure overhead.
        // Defer to the sample-encoded path otherwise; RunEnd's actual compression depends
        // on run-length distribution which is not summarized by distinct count alone.
        if (distinct >= n) {
            return Estimate.SKIP;
        }
        return Estimate.COMPLETE;
    }

    /// Encodes a boolean array as `vortex.runend`: consecutive equal values collapse into one run.
    /// Unlike [SparseEncodingEncoder#encodeBool] (good for a dominant value with scattered rare
    /// flips, at the cost of a patch per flip) this wins on CLUSTERED validity — long consecutive
    /// stretches of valid or invalid rows — where every flip costs a run regardless of which value
    /// is more frequent. Always builds the result; callers compare against alternatives (raw
    /// bitmap, sparse) and keep whichever is smallest.
    ///
    /// @param validity per-row boolean array; must contain at least one `true` and one `false`
    ///                 (an all-same array is cheaper as `vortex.constant`)
    /// @param ctx      encode context
    /// @return the encoded `vortex.runend` result
    static EncodeResult encodeBool(boolean[] validity, EncodeContext ctx) {
        int n = validity.length;
        List<Integer> ends = new ArrayList<>();
        List<Boolean> values = new ArrayList<>();
        boolean runVal = validity[0];
        for (int i = 1; i < n; i++) {
            if (validity[i] != runVal) {
                ends.add(i);
                values.add(runVal);
                runVal = validity[i];
            }
        }
        ends.add(n);
        values.add(runVal);

        int numRuns = ends.size();
        MemorySegment endsBuf = ctx.arena().allocate((long) numRuns * 4, 4);
        for (int i = 0; i < numRuns; i++) {
            endsBuf.setAtIndex(VortexFormat.LE_INT, i, ends.get(i));
        }
        boolean[] valuesArr = new boolean[numRuns];
        for (int i = 0; i < numRuns; i++) {
            valuesArr[i] = values.get(i);
        }
        EncodeResult valuesResult = new BoolEncodingEncoder().encode(DType.BOOL, valuesArr, ctx);

        byte[] metaBytes = new ProtoRunEndMetadata(
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.U32.ordinal()),
                numRuns, 0L).encode();

        EncodeNode endsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode valuesNode = EncodeNode.remapBufferIndices(valuesResult.rootNode(), 1);

        List<MemorySegment> buffers = new ArrayList<>();
        buffers.add(endsBuf);
        buffers.addAll(valuesResult.buffers());

        EncodeNode root = new EncodeNode(EncodingId.VORTEX_RUNEND, MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{endsNode, valuesNode}, new int[0]);
        return new EncodeResult(root, List.copyOf(buffers), null, null);
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (!(dtype instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_RUNEND, "encode only supports Primitive dtype, got " + dtype);
        }
        PType ptype = p.ptype();
        int n = arrayLength(data, ptype);
        boolean unsign = ptype.isUnsigned();

        int elemBytes = ptype.byteSize();
        long minVal = 0L;
        long maxVal = 0L;
        int numRuns = 0;
        MemorySegment endsBuf;
        MemorySegment valuesBuf;

        if (n == 0) {
            endsBuf = ctx.arena().allocate(0, 4);
            valuesBuf = ctx.arena().allocate(0, elemBytes);
        } else {
            // A per-element ptype switch and the signedness ternary used to sit inside the scan,
            // making the body non-uniform for every row (CLAUDE.md hot-loop rule). Widen once,
            // then run uniform loops over `long`s.
            //
            // Two passes — count the runs, then fill — rather than one pass appending to
            // `List<Integer>`/`List<Long>`. Those lists boxed an Integer and a Long per RUN, and
            // on data with no runs to find (random values) that is a boxed pair per row. This
            // encoder is a cascade candidate, encoded on every competition and discarded when it
            // loses, so that cost fell on columns run-end never wins: it was the hottest frame
            // (11.6%) while profiling the BITPACKED benchmark. Counting first also sizes both
            // segments exactly and writes straight into them, so run data never touches the heap.
            long[] widened = widen(data, ptype, n);
            long runVal = widened[0];
            minVal = runVal;
            maxVal = runVal;
            numRuns = 1;
            if (unsign) {
                for (int i = 1; i < n; i++) {
                    long cur = widened[i];
                    if (Long.compareUnsigned(cur, minVal) < 0) {
                        minVal = cur;
                    }
                    if (Long.compareUnsigned(cur, maxVal) > 0) {
                        maxVal = cur;
                    }
                    if (cur != runVal) {
                        numRuns++;
                        runVal = cur;
                    }
                }
            } else {
                for (int i = 1; i < n; i++) {
                    long cur = widened[i];
                    if (cur < minVal) {
                        minVal = cur;
                    }
                    if (cur > maxVal) {
                        maxVal = cur;
                    }
                    if (cur != runVal) {
                        numRuns++;
                        runVal = cur;
                    }
                }
            }

            endsBuf = ctx.arena().allocate((long) numRuns * 4, 4);
            valuesBuf = ctx.arena().allocate((long) numRuns * elemBytes, elemBytes);
            int k = 0;
            runVal = widened[0];
            for (int i = 1; i < n; i++) {
                long cur = widened[i];
                if (cur != runVal) {
                    endsBuf.setAtIndex(VortexFormat.LE_INT, k, i);
                    PTypeIO.set(valuesBuf, (long) k * elemBytes, ptype, runVal);
                    k++;
                    runVal = cur;
                }
            }
            endsBuf.setAtIndex(VortexFormat.LE_INT, k, n);
            PTypeIO.set(valuesBuf, (long) k * elemBytes, ptype, runVal);
        }

        byte[] metaBytes = new ProtoRunEndMetadata(
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.U32.ordinal()),
                numRuns,
                0L
        ).encode();

        EncodeNode endsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode valuesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_RUNEND, MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{endsNode, valuesNode}, new int[0]);
        byte[] statsMin = n > 0 ? statsBytes(ptype, minVal) : null;
        byte[] statsMax = n > 0 ? statsBytes(ptype, maxVal) : null;
        return new EncodeResult(root, List.of(endsBuf, valuesBuf), statsMin, statsMax);
    }

    private static byte[] statsBytes(PType ptype, long value) {
        if (ptype.isUnsigned()) {
            return ProtoScalarValue.ofUint64Value(value).encode();
        }
        return ProtoScalarValue.ofInt64Value(value).encode();
    }

    private static int arrayLength(Object data, PType ptype) {
        return switch (ptype) {
            case I8, U8 -> ((byte[]) data).length;
            case I16, U16 -> ((short[]) data).length;
            case I32, U32 -> ((int[]) data).length;
            case I64, U64 -> ((long[]) data).length;
            default -> throw new VortexException(EncodingId.VORTEX_RUNEND, "unsupported ptype: " + ptype);
        };
    }

    /// Widens a chunk to `long`s with one switch for the whole array rather than one per element.
    /// I64/U64 input is returned in place; only the narrower carriers pay for a copy.
    ///
    /// @param data  the chunk's typed primitive array
    /// @param ptype the column's primitive type
    /// @param n     element count
    /// @return the values as `long`s, sign- or zero-extended to match `ptype`
    private static long[] widen(Object data, PType ptype, int n) {
        switch (ptype) {
            case I64, U64 -> {
                return (long[]) data;
            }
            case I8 -> {
                byte[] a = (byte[]) data;
                long[] out = new long[n];
                for (int i = 0; i < n; i++) {
                    out[i] = a[i];
                }
                return out;
            }
            case U8 -> {
                byte[] a = (byte[]) data;
                long[] out = new long[n];
                for (int i = 0; i < n; i++) {
                    out[i] = Byte.toUnsignedLong(a[i]);
                }
                return out;
            }
            case I16 -> {
                short[] a = (short[]) data;
                long[] out = new long[n];
                for (int i = 0; i < n; i++) {
                    out[i] = a[i];
                }
                return out;
            }
            case U16 -> {
                short[] a = (short[]) data;
                long[] out = new long[n];
                for (int i = 0; i < n; i++) {
                    out[i] = Short.toUnsignedLong(a[i]);
                }
                return out;
            }
            case I32 -> {
                int[] a = (int[]) data;
                long[] out = new long[n];
                for (int i = 0; i < n; i++) {
                    out[i] = a[i];
                }
                return out;
            }
            case U32 -> {
                int[] a = (int[]) data;
                long[] out = new long[n];
                for (int i = 0; i < n; i++) {
                    out[i] = Integer.toUnsignedLong(a[i]);
                }
                return out;
            }
            default -> throw new VortexException(EncodingId.VORTEX_RUNEND, "unsupported ptype: " + ptype);
        }
    }

}
