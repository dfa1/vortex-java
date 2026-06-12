package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

/// Write-only encoder for {@code vortex.zigzag} — signed integers as zigzag-encoded unsigned values.
public final class ZigZagEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public ZigZagEncodingEncoder() {
    }

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
        MemorySegment seg = switch (signed) {
            case I8 -> {
                byte[] arr = (byte[]) data;
                MemorySegment s = ctx.arena().allocate(arr.length);
                for (int i = 0; i < arr.length; i++) {
                    byte v = arr[i];
                    s.set(ValueLayout.JAVA_BYTE, i, (byte) ((v << 1) ^ (v >> 7)));
                }
                yield s;
            }
            case I16 -> {
                short[] arr = (short[]) data;
                MemorySegment s = ctx.arena().allocate((long) arr.length * 2, 2);
                for (int i = 0; i < arr.length; i++) {
                    short v = arr[i];
                    s.setAtIndex(PTypeIO.LE_SHORT, i, (short) ((v << 1) ^ (v >> 15)));
                }
                yield s;
            }
            case I32 -> {
                int[] arr = (int[]) data;
                MemorySegment s = ctx.arena().allocate((long) arr.length * 4, 4);
                for (int i = 0; i < arr.length; i++) {
                    int v = arr[i];
                    s.setAtIndex(PTypeIO.LE_INT, i, (v << 1) ^ (v >> 31));
                }
                yield s;
            }
            case I64 -> {
                long[] arr = (long[]) data;
                MemorySegment s = ctx.arena().allocate((long) arr.length * 8, 8);
                for (int i = 0; i < arr.length; i++) {
                    long v = arr[i];
                    s.setAtIndex(PTypeIO.LE_LONG, i, (v << 1) ^ (v >> 63));
                }
                yield s;
            }
            default -> throw new VortexException(EncodingId.VORTEX_ZIGZAG, "unsupported ptype: " + signed);
        };
        EncodeNode child = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_ZIGZAG, null, new EncodeNode[]{child}, new int[0]);
        return new EncodeResult(root, List.of(seg), null, null);
    }
}
