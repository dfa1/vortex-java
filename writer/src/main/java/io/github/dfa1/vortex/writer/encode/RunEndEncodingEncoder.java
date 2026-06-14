package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.RunEndMetadata;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for {@code vortex.runend}.
public final class RunEndEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public RunEndEncodingEncoder() {
    }

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
            return null;
        }
        long n = stats.valueCount();
        long distinct = stats.distinctCount();
        if (n == 0) {
            return Estimate.skip();
        }
        // Skip rule: if every value is distinct, each row is its own run — pure overhead.
        // Defer to the sample-encoded path otherwise; RunEnd's actual compression depends
        // on run-length distribution which is not summarised by distinct count alone.
        if (distinct >= n) {
            return Estimate.skip();
        }
        return null;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (!(dtype instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_RUNEND, "encode only supports Primitive dtype, got " + dtype);
        }
        PType ptype = p.ptype();
        int n = arrayLength(data, ptype);

        List<Integer> ends = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        if (n > 0) {
            long runVal = readLong(data, ptype, 0);
            for (int i = 1; i < n; i++) {
                long cur = readLong(data, ptype, i);
                if (cur != runVal) {
                    ends.add(i);
                    values.add(runVal);
                    runVal = cur;
                }
            }
            ends.add(n);
            values.add(runVal);
        }

        int numRuns = ends.size();

        MemorySegment endsBuf = ctx.arena().allocate((long) numRuns * 4, 4);
        for (int i = 0; i < numRuns; i++) {
            endsBuf.setAtIndex(PTypeIO.LE_INT, i, ends.get(i));
        }

        int elemBytes = ptype.byteSize();
        MemorySegment valuesBuf = ctx.arena().allocate((long) numRuns * elemBytes, elemBytes);
        for (int i = 0; i < numRuns; i++) {
            PTypeIO.set(valuesBuf, (long) i * elemBytes, ptype, values.get(i));
        }

        byte[] metaBytes = new RunEndMetadata(
                io.github.dfa1.vortex.proto.PType.fromValue(PType.U32.ordinal()),
                numRuns,
                0L
        ).encode();

        EncodeNode endsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode valuesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_RUNEND, ByteBuffer.wrap(metaBytes),
                new EncodeNode[]{endsNode, valuesNode}, new int[0]);
        return new EncodeResult(root, List.of(endsBuf, valuesBuf), null, null);
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

    private static long readLong(Object data, PType ptype, int i) {
        return switch (ptype) {
            case I8 -> ((byte[]) data)[i];
            case U8 -> Byte.toUnsignedLong(((byte[]) data)[i]);
            case I16 -> ((short[]) data)[i];
            case U16 -> Short.toUnsignedLong(((short[]) data)[i]);
            case I32 -> ((int[]) data)[i];
            case U32 -> Integer.toUnsignedLong(((int[]) data)[i]);
            case I64, U64 -> ((long[]) data)[i];
            default -> throw new VortexException(EncodingId.VORTEX_RUNEND, "unsupported ptype: " + ptype);
        };
    }
}
