package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoBoolMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for `vortex.bool` (bit-packed boolean arrays, LSB first).
///
/// The bitmap starts `BoolMetadata.offset` bits (< 8) into its buffer: Rust writes a nonzero
/// offset for a bool array sliced off a byte boundary (e.g. the validity of a sparse array's
/// patch values). Such a bitmap is re-aligned to bit 0 once; offset 0 stays zero-copy.
///
/// When the encoding node has one child, that child is the validity bitmask:
/// a [BoolArray] where `false` marks null rows. The values array is wrapped in
/// a [MaskedArray] so callers see nulls as invalid rows.
public final class BoolEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_BOOL;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Decoder {

        static Array decode(DecodeContext ctx) {
            long n = ctx.rowCount();
            int offset = bitOffset(ctx.metadata());
            MemorySegment bits = ctx.buffer(0);
            // The bitmap comes straight from the file and needs one byte per 8 rows (after the
            // offset). A shorter one is malformed, and [MaterializedBoolArray#getBoolean] indexes
            // its buffer unchecked — deliberately, since every other construction site allocates
            // the bitmap itself at exactly this size — so without this the read of whichever row
            // runs off the end is a raw IndexOutOfBoundsException (ADR 0003). O(1), and it also
            // covers `materialize`, which hands the same short buffer straight to the caller.
            long needed = (n + offset + 7) >>> 3;
            if (bits.byteSize() < needed) {
                throw new VortexException(EncodingId.VORTEX_BOOL,
                        "bool bitmap of " + bits.byteSize() + " byte(s) is shorter than the "
                                + needed + " byte(s) needed for " + n + " row(s) at bit offset " + offset);
            }
            if (offset != 0) {
                bits = realign(bits, offset, n, ctx);
            }
            Array values = new MaterializedBoolArray(ctx.dtype(), n, bits);
            if (ctx.node().children().length == 1) {
                Array va = ctx.decodeChild(0, DType.BOOL, n);
                BoolArray validity = MaskedArray.requireBoolArray(va, EncodingId.VORTEX_BOOL, "validity child");
                return new MaskedArray(values, validity);
            }
            return values;
        }

        /// Absent/empty metadata is proto3's all-defaults message: offset 0.
        private static int bitOffset(MemorySegment meta) {
            if (meta == null || meta.byteSize() == 0) {
                return 0;
            }
            int offset;
            try {
                offset = ProtoBoolMetadata.decode(meta, 0, meta.byteSize()).offset();
            } catch (IOException e) {
                throw new VortexException(EncodingId.VORTEX_BOOL, "invalid metadata: " + e.getMessage());
            }
            // Rust asserts offset < 8 on write; anything else (incl. a negative u32 overflow) is malformed
            if (offset < 0 || offset > 7) {
                throw new VortexException(EncodingId.VORTEX_BOOL, "bit offset must be < 8, got " + Integer.toUnsignedString(offset));
            }
            return offset;
        }

        /// Shifts `n` bits starting at bit `offset` of `src` down to bit 0 of a fresh buffer.
        private static MemorySegment realign(MemorySegment src, int offset, long n, DecodeContext ctx) {
            long outBytes = (n + 7) >>> 3;
            long srcBytes = src.byteSize();
            MemorySegment out = ctx.arena().allocate(Math.max(outBytes, 1));
            int back = 8 - offset;
            for (long i = 0; i < outBytes; i++) {
                int lo = Byte.toUnsignedInt(src.get(ValueLayout.JAVA_BYTE, i)) >>> offset;
                // the last output byte may need no high bits: its source byte i+1 can be past the buffer
                int hi = i + 1 < srcBytes ? Byte.toUnsignedInt(src.get(ValueLayout.JAVA_BYTE, i + 1)) << back : 0;
                out.set(ValueLayout.JAVA_BYTE, i, (byte) (lo | hi));
            }
            return out;
        }
    }
}
