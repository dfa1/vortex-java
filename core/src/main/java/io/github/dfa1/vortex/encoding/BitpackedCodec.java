package io.github.dfa1.vortex.encoding;

import dev.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/// Codec for `fastlanes.bitpacked` — bit-packing for integer columns.
///
/// Metadata (9 bytes): bit_width (u8) | frame_of_reference (i64 LE).
/// Values are shifted by the column minimum (frame-of-reference), then packed
/// bit_width bits each, LSB-first, contiguous. bit_width=0 means all values
/// equal the frame-of-reference.
public final class BitpackedCodec implements Codec {

    @Override
    public String encodingId() {
        return "fastlanes.bitpacked";
    }

    @Override
    public boolean accepts(DType dtype) {
        if (!(dtype instanceof DType.Primitive p)) {
            return false;
        }
        return switch (p.ptype()) {
            case I8, I16, I32, I64, U8, U16, U32, U64 -> true;
            default -> false;
        };
    }

    // ── Encode ────────────────────────────────────────────────────────────────

    @Override
    public EncodeResult encode(DType dtype, Object data) {
        PType  ptype    = ((DType.Primitive) dtype).ptype();
        long[] longs    = toLongs(data, ptype);
        int    n        = longs.length;
        boolean unsign  = isUnsigned(ptype);

        long frameOfRef = 0L;
        long maxVal     = 0L;
        int  bitWidth   = 0;

        if (n > 0) {
            long min = longs[0];
            long max = longs[0];
            for (int i = 1; i < n; i++) {
                long v = longs[i];
                if (unsign ? Long.compareUnsigned(v, min) < 0 : v < min) {
                    min = v;
                }
                if (unsign ? Long.compareUnsigned(v, max) > 0 : v > max) {
                    max = v;
                }
            }
            frameOfRef = min;
            maxVal     = max;
            long range = max - min;
            bitWidth   = (range == 0L) ? 0 : (64 - Long.numberOfLeadingZeros(range));
        }

        ByteBuffer packed = pack(longs, frameOfRef, bitWidth);

        ByteBuffer meta = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
        meta.put((byte) bitWidth);
        meta.putLong(frameOfRef);
        meta.flip();

        byte[] statsMin = n > 0 ? statsBytes(ptype, frameOfRef) : null;
        byte[] statsMax = n > 0 ? statsBytes(ptype, maxVal)     : null;

        EncodeNode root = new EncodeNode(encodingId(), meta, new EncodeNode[0], new int[]{0});
        return new EncodeResult(root, List.of(packed), statsMin, statsMax);
    }

    // ── Decode ────────────────────────────────────────────────────────────────

    @Override
    public Array decode(DecodeContext ctx)  {
        ByteBuffer rawMeta = ctx.metadata();
        if (rawMeta == null || rawMeta.capacity() < 2) {
            throw new IllegalStateException("fastlanes.bitpacked: missing or truncated metadata");
        }
        if (rawMeta.capacity() == 9) {
            return decodeJava(ctx, rawMeta);
        }
        // 2-byte JNI format: [constant:u8, bit_width:u8] — FastLanes block packing
        return decodeJni(ctx, rawMeta);
    }

    private Array decodeJava(DecodeContext ctx, ByteBuffer rawMeta)  {
        ByteBuffer meta = rawMeta.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int  bitWidth   = Byte.toUnsignedInt(meta.get(0));
        long frameOfRef = meta.getLong(1);

        PType ptype    = ((DType.Primitive) ctx.dtype()).ptype();
        long  rowCount = ctx.rowCount();

        MemorySegment packed      = ctx.buffer(0);
        byte[]        packedBytes = new byte[(int) packed.byteSize()];
        MemorySegment.copy(packed, ValueLayout.JAVA_BYTE, 0, packedBytes, 0, packedBytes.length);

        long[] longs = unpack(packedBytes, (int) rowCount, bitWidth, frameOfRef);

        return new Array(ctx.dtype(), rowCount,
            new MemorySegment[]{fromLongs(longs, ptype)}, new Array[0], ArrayStats.empty());
    }

    // Decode FastLanes block-transposed format (JNI/Rust Vortex writer).
    // Layout: ceil(rowCount/1024) blocks, each block = bit_width * 16 * 8 bytes.
    // Word index = bitPos * 16 + lane; bit j = bit bitPos of value at index (lane + j*16) in block.
    private Array decodeJni(DecodeContext ctx, ByteBuffer rawMeta)  {
        int bitWidth = Byte.toUnsignedInt(rawMeta.get(1));

        PType ptype    = ((DType.Primitive) ctx.dtype()).ptype();
        long  rowCount = ctx.rowCount();

        MemorySegment packed = ctx.buffer(0);
        long[] output = new long[(int) rowCount];

        int blockCount = (int) ((rowCount + 1023) / 1024);
        for (int block = 0; block < blockCount; block++) {
            int  blockStart    = block * 1024;
            long blockByteOff  = (long) block * bitWidth * 16L * 8L;
            for (int bitPos = 0; bitPos < bitWidth; bitPos++) {
                for (int lane = 0; lane < 16; lane++) {
                    long wordByteOff = blockByteOff + ((long) bitPos * 16 + lane) * 8L;
                    long word = packed.get(
                        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),
                        wordByteOff);
                    for (int j = 0; j < 64; j++) {
                        int vi = blockStart + lane + j * 16;
                        if (vi >= rowCount) {
                            break;
                        }
                        output[vi] |= ((word >>> j) & 1L) << bitPos;
                    }
                }
            }
        }

        return new Array(ctx.dtype(), rowCount,
            new MemorySegment[]{fromLongs(output, ptype)}, new Array[0], ArrayStats.empty());
    }

    // ── Bit packing ───────────────────────────────────────────────────────────

    private static ByteBuffer pack(long[] values, long frameOfRef, int bitWidth) {
        int    n         = values.length;
        int    totalBits = n * bitWidth;
        byte[] buf       = new byte[(totalBits + 7) / 8];

        for (int i = 0; i < n; i++) {
            long shifted = values[i] - frameOfRef;
            int  bitPos  = i * bitWidth;
            for (int b = 0; b < bitWidth; b++) {
                if (((shifted >>> b) & 1L) == 1L) {
                    buf[(bitPos + b) >>> 3] |= (byte) (1 << ((bitPos + b) & 7));
                }
            }
        }
        return ByteBuffer.wrap(buf);
    }

    private static long[] unpack(byte[] packed, int rowCount, int bitWidth, long frameOfRef) {
        long[] out = new long[rowCount];
        for (int i = 0; i < rowCount; i++) {
            long val    = 0L;
            int  bitPos = i * bitWidth;
            for (int b = 0; b < bitWidth; b++) {
                int byteIdx = (bitPos + b) >>> 3;
                int bitIdx  = (bitPos + b) & 7;
                if (byteIdx < packed.length && ((packed[byteIdx] >>> bitIdx) & 1) == 1) {
                    val |= 1L << b;
                }
            }
            out[i] = val + frameOfRef;
        }
        return out;
    }

    // ── Type conversion ───────────────────────────────────────────────────────

    private static long[] toLongs(Object data, PType ptype) {
        return switch (ptype) {
            case I8 -> {
                byte[] arr = (byte[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) { r[i] = arr[i]; }
                yield r;
            }
            case U8 -> {
                byte[] arr = (byte[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) { r[i] = Byte.toUnsignedLong(arr[i]); }
                yield r;
            }
            case I16 -> {
                short[] arr = (short[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) { r[i] = arr[i]; }
                yield r;
            }
            case U16 -> {
                short[] arr = (short[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) { r[i] = Short.toUnsignedLong(arr[i]); }
                yield r;
            }
            case I32 -> {
                int[] arr = (int[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) { r[i] = arr[i]; }
                yield r;
            }
            case U32 -> {
                int[] arr = (int[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) { r[i] = Integer.toUnsignedLong(arr[i]); }
                yield r;
            }
            case I64, U64 -> (long[]) data;
            default -> throw new UnsupportedOperationException("unsupported ptype: " + ptype);
        };
    }

    private static MemorySegment fromLongs(long[] longs, PType ptype) {
        int    n        = longs.length;
        int    elemSize = ptype.byteSize();
        byte[] bytes    = new byte[n * elemSize];
        ByteBuffer bb   = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (long v : longs) {
            switch (ptype) {
                case I8,  U8  -> bb.put((byte)  v);
                case I16, U16 -> bb.putShort((short) v);
                case I32, U32 -> bb.putInt((int)   v);
                case I64, U64 -> bb.putLong(v);
                default -> throw new UnsupportedOperationException("unsupported ptype: " + ptype);
            }
        }
        return MemorySegment.ofArray(bytes);
    }

    // ── Stats helpers ─────────────────────────────────────────────────────────

    private static byte[] statsBytes(PType ptype, long value) {
        if (isUnsigned(ptype)) {
            return ScalarProtos.ScalarValue.newBuilder().setUint64Value(value).build().toByteArray();
        }
        return ScalarProtos.ScalarValue.newBuilder().setInt64Value(value).build().toByteArray();
    }

    private static boolean isUnsigned(PType ptype) {
        return switch (ptype) {
            case U8, U16, U32, U64 -> true;
            default -> false;
        };
    }
}
