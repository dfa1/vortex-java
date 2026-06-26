package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.zstd.ZstdCompressCtx;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.proto.ProtoZstdFrameMetadata;
import io.github.dfa1.vortex.core.proto.ProtoZstdMetadata;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;

/// Write-only encoder for `vortex.zstd`.
public final class ZstdEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
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
            return encodePrimitive(dt, data, ctx.arena());
        }
        if (dtype instanceof DType.Utf8 || dtype instanceof DType.Binary) {
            return encodeVarBin((String[]) data, ctx.arena());
        }
        throw new VortexException(EncodingId.VORTEX_ZSTD, "unsupported dtype: " + dtype);
    }

    private static EncodeResult encodePrimitive(DType.Primitive dt, Object data, Arena arena) {
        MemorySegment raw = primitiveToLeBytes(dt.ptype(), data, arena);
        long n = primitiveLength(dt.ptype(), data);
        return buildResult(raw, n, arena);
    }

    private static EncodeResult encodeVarBin(String[] strings, Arena arena) {
        MemorySegment raw = buildLengthPrefixed(strings, arena);
        return buildResult(raw, strings.length, arena);
    }

    private static EncodeResult buildResult(MemorySegment raw, long n, Arena arena) {
        // Zero-copy: compress the arena-native raw segment straight into another arena segment,
        // no heap byte[] bounce on either side. The compressed slice is owned by the caller arena.
        MemorySegment compressed;
        try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
            compressed = cctx.compress(arena, raw);
        }
        byte[] meta = new ProtoZstdMetadata(
                0,
                List.of(new ProtoZstdFrameMetadata(raw.byteSize(), n))
        ).encode();
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_ZSTD, MemorySegment.ofArray(meta),
                new EncodeNode[0], new int[]{0});
        return new EncodeResult(root, List.of(compressed), null, null);
    }

    private static MemorySegment primitiveToLeBytes(PType ptype, Object data, Arena arena) {
        return switch (ptype) {
            case I8, U8 -> {
                byte[] arr = (byte[]) data;
                MemorySegment seg = arena.allocate(arr.length);
                MemorySegment.copy(arr, 0, seg, ValueLayout.JAVA_BYTE, 0, arr.length);
                yield seg;
            }
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

    private static MemorySegment buildLengthPrefixed(String[] strings, Arena arena) {
        int total = 0;
        byte[][] encoded = new byte[strings.length][];
        for (int i = 0; i < strings.length; i++) {
            encoded[i] = strings[i].getBytes(StandardCharsets.UTF_8);
            total += 4 + encoded[i].length;
        }
        MemorySegment seg = arena.allocate(total > 0 ? total : 1);
        long pos = 0;
        for (byte[] bytes : encoded) {
            seg.set(PTypeIO.LE_INT, pos, bytes.length);
            pos += 4;
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, seg, pos, bytes.length);
            pos += bytes.length;
        }
        return seg.asSlice(0, total);
    }
}
