package io.github.dfa1.vortex.writer.encode;

import io.airlift.compress.v3.zstd.ZstdCompressor;
import io.airlift.compress.v3.zstd.ZstdJavaCompressor;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodeContext;
import io.github.dfa1.vortex.encoding.EncodeNode;
import io.github.dfa1.vortex.encoding.EncodeResult;
import io.github.dfa1.vortex.encoding.EncodingEncoder;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.ZstdFrameMetadata;
import io.github.dfa1.vortex.proto.ZstdMetadata;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/// Write-only encoder for {@code vortex.zstd}.
public final class ZstdEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public ZstdEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ZSTD;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive || dtype instanceof DType.Utf8 || dtype instanceof DType.Binary;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (dtype instanceof DType.Primitive dt) {
            return encodePrimitive(dt, data);
        }
        if (dtype instanceof DType.Utf8 || dtype instanceof DType.Binary) {
            return encodeVarBin((String[]) data);
        }
        throw new VortexException(EncodingId.VORTEX_ZSTD, "unsupported dtype: " + dtype);
    }

    private static EncodeResult encodePrimitive(DType.Primitive dt, Object data) {
        MemorySegment raw = primitiveToLeBytes(dt.ptype(), data, Arena.ofAuto());
        long n = primitiveLength(dt.ptype(), data);
        byte[] rawBytes = raw.toArray(ValueLayout.JAVA_BYTE);
        return buildResult(rawBytes, n);
    }

    private static EncodeResult encodeVarBin(String[] strings) {
        byte[] raw = buildLengthPrefixed(strings);
        return buildResult(raw, strings.length);
    }

    private static EncodeResult buildResult(byte[] raw, long n) {
        byte[] compressed = compress(raw);
        byte[] meta = new ZstdMetadata(
                0,
                java.util.List.of(new ZstdFrameMetadata(raw.length, n))
        ).encode();
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_ZSTD, ByteBuffer.wrap(meta),
                new EncodeNode[0], new int[]{0});
        return new EncodeResult(root, List.of(MemorySegment.ofArray(compressed)), null, null);
    }

    private static byte[] compress(byte[] input) {
        ZstdCompressor compressor = new ZstdJavaCompressor();
        byte[] out = new byte[compressor.maxCompressedLength(input.length)];
        int len = compressor.compress(input, 0, input.length, out, 0, out.length);
        return Arrays.copyOf(out, len);
    }

    private static MemorySegment primitiveToLeBytes(PType ptype, Object data, Arena arena) {
        return switch (ptype) {
            case I8, U8 -> MemorySegment.ofArray((byte[]) data);
            case I16, U16, F16 -> {
                short[] arr = (short[]) data;
                MemorySegment seg = arena.allocate((long) arr.length * 2, 2);
                for (int i = 0; i < arr.length; i++) {
                    seg.setAtIndex(PTypeIO.LE_SHORT, i, arr[i]);
                }
                yield seg;
            }
            case I32, U32 -> {
                int[] arr = (int[]) data;
                MemorySegment seg = arena.allocate((long) arr.length * 4, 4);
                for (int i = 0; i < arr.length; i++) {
                    seg.setAtIndex(PTypeIO.LE_INT, i, arr[i]);
                }
                yield seg;
            }
            case I64, U64 -> {
                long[] arr = (long[]) data;
                MemorySegment seg = arena.allocate((long) arr.length * 8, 8);
                for (int i = 0; i < arr.length; i++) {
                    seg.setAtIndex(PTypeIO.LE_LONG, i, arr[i]);
                }
                yield seg;
            }
            case F32 -> {
                float[] arr = (float[]) data;
                MemorySegment seg = arena.allocate((long) arr.length * 4, 4);
                for (int i = 0; i < arr.length; i++) {
                    seg.setAtIndex(PTypeIO.LE_FLOAT, i, arr[i]);
                }
                yield seg;
            }
            case F64 -> {
                double[] arr = (double[]) data;
                MemorySegment seg = arena.allocate((long) arr.length * 8, 8);
                for (int i = 0; i < arr.length; i++) {
                    seg.setAtIndex(PTypeIO.LE_DOUBLE, i, arr[i]);
                }
                yield seg;
            }
        };
    }

    private static long primitiveLength(PType ptype, Object data) {
        return switch (ptype) {
            case I8, U8 -> ((byte[]) data).length;
            case I16, U16, F16 -> ((short[]) data).length;
            case I32, U32 -> ((int[]) data).length;
            case F32 -> ((float[]) data).length;
            case I64, U64 -> ((long[]) data).length;
            case F64 -> ((double[]) data).length;
        };
    }

    private static byte[] buildLengthPrefixed(String[] strings) {
        int total = 0;
        byte[][] encoded = new byte[strings.length][];
        for (int i = 0; i < strings.length; i++) {
            encoded[i] = strings[i].getBytes(StandardCharsets.UTF_8);
            total += 4 + encoded[i].length;
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment seg = scratch.allocate(total > 0 ? total : 1);
            long pos = 0;
            for (byte[] bytes : encoded) {
                seg.set(PTypeIO.LE_INT, pos, bytes.length);
                pos += 4;
                MemorySegment.copy(MemorySegment.ofArray(bytes), 0, seg, pos, bytes.length);
                pos += bytes.length;
            }
            return seg.asSlice(0, total).toArray(ValueLayout.JAVA_BYTE);
        }
    }
}
