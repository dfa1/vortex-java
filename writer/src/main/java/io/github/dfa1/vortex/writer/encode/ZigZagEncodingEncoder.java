package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

/// Write-only encoder for `vortex.zigzag` — signed integers as zigzag-encoded unsigned values.
public final class ZigZagEncodingEncoder implements EncodingEncoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ZIGZAG;
    }

    @Override
    public boolean accepts(DType dtype) {
        if (!(dtype instanceof DType.Primitive p)) {
            return false;
        }
        PType pt = p.ptype();
        return pt == PType.I8 || pt == PType.I16 || pt == PType.I32 || pt == PType.I64;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        PType signed = ((DType.Primitive) dtype).ptype();
        // Zigzag's bit-interleaving is not order-preserving (e.g. -1 maps to 1, 1 maps to 2), so
        // stats must be tracked over the original signed values in the same pass, not read back
        // from the transformed unsigned output afterward.
        long[] minMax = new long[2];
        int n = arrayLength(data, signed);
        MemorySegment seg = switch (signed) {
            case I8 -> {
                byte[] arr = (byte[]) data;
                MemorySegment s = ctx.arena().allocate(arr.length);
                if (n > 0) {
                    minMax[0] = arr[0];
                    minMax[1] = arr[0];
                }
                for (int i = 0; i < arr.length; i++) {
                    byte v = arr[i];
                    if (v < minMax[0]) {
                        minMax[0] = v;
                    }
                    if (v > minMax[1]) {
                        minMax[1] = v;
                    }
                    s.set(ValueLayout.JAVA_BYTE, i, (byte) ((v << 1) ^ (v >> 7)));
                }
                yield s;
            }
            case I16 -> {
                short[] arr = (short[]) data;
                MemorySegment s = ctx.arena().allocate((long) arr.length * 2, 2);
                if (n > 0) {
                    minMax[0] = arr[0];
                    minMax[1] = arr[0];
                }
                for (int i = 0; i < arr.length; i++) {
                    short v = arr[i];
                    if (v < minMax[0]) {
                        minMax[0] = v;
                    }
                    if (v > minMax[1]) {
                        minMax[1] = v;
                    }
                    s.setAtIndex(VortexFormat.LE_SHORT, i, (short) ((v << 1) ^ (v >> 15)));
                }
                yield s;
            }
            case I32 -> {
                int[] arr = (int[]) data;
                MemorySegment s = ctx.arena().allocate((long) arr.length * 4, 4);
                if (n > 0) {
                    minMax[0] = arr[0];
                    minMax[1] = arr[0];
                }
                for (int i = 0; i < arr.length; i++) {
                    int v = arr[i];
                    if (v < minMax[0]) {
                        minMax[0] = v;
                    }
                    if (v > minMax[1]) {
                        minMax[1] = v;
                    }
                    s.setAtIndex(VortexFormat.LE_INT, i, (v << 1) ^ (v >> 31));
                }
                yield s;
            }
            case I64 -> {
                long[] arr = (long[]) data;
                MemorySegment s = ctx.arena().allocate((long) arr.length * 8, 8);
                if (n > 0) {
                    minMax[0] = arr[0];
                    minMax[1] = arr[0];
                }
                for (int i = 0; i < arr.length; i++) {
                    long v = arr[i];
                    if (v < minMax[0]) {
                        minMax[0] = v;
                    }
                    if (v > minMax[1]) {
                        minMax[1] = v;
                    }
                    s.setAtIndex(VortexFormat.LE_LONG, i, (v << 1) ^ (v >> 63));
                }
                yield s;
            }
            default -> throw new VortexException(EncodingId.VORTEX_ZIGZAG, "unsupported ptype: " + signed);
        };
        EncodeNode child = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_ZIGZAG, null, new EncodeNode[]{child}, new int[0]);
        byte[] statsMin = n > 0 ? ProtoScalarValue.ofInt64Value(minMax[0]).encode() : null;
        byte[] statsMax = n > 0 ? ProtoScalarValue.ofInt64Value(minMax[1]).encode() : null;
        return new EncodeResult(root, List.of(seg), statsMin, statsMax);
    }

    private static int arrayLength(Object data, PType ptype) {
        return switch (ptype) {
            case I8 -> ((byte[]) data).length;
            case I16 -> ((short[]) data).length;
            case I32 -> ((int[]) data).length;
            case I64 -> ((long[]) data).length;
            default -> throw new VortexException(EncodingId.VORTEX_ZIGZAG, "unsupported ptype: " + ptype);
        };
    }
}
