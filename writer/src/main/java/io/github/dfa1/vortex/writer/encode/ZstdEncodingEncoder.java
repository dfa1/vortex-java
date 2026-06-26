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
import java.util.ArrayList;
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
        if (data instanceof NullableData nd) {
            if (!(dtype instanceof DType.Primitive dt)) {
                throw new VortexException(EncodingId.VORTEX_ZSTD,
                        "NullableData is only supported for primitive dtypes, got " + dtype);
            }
            return encodeNullablePrimitive(dt, nd, ctx);
        }
        if (dtype instanceof DType.Primitive dt) {
            return encodePrimitive(dt, data, ctx.arena());
        }
        if (dtype instanceof DType.Utf8 || dtype instanceof DType.Binary) {
            String[] strings = (String[]) data;
            if (containsNull(strings)) {
                return encodeNullableVarBin(strings, ctx);
            }
            return encodeVarBin(strings, ctx.arena());
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

    private static EncodeResult encodeNullablePrimitive(DType.Primitive dt, NullableData nd, EncodeContext ctx) {
        Arena arena = ctx.arena();
        int byteWidth = dt.ptype().byteSize();
        boolean[] validity = nd.validity();
        // Strip null positions: only valid values reach the compressed payload (mirrors the Rust
        // reference). The decoder scatters them back over the validity mask carried by child[0].
        MemorySegment full = primitiveToLeBytes(dt.ptype(), nd.values(), arena);
        MemorySegment packed = packValidBytes(full, validity, byteWidth, arena);
        return buildNullableResult(packed, countValid(validity), validity, ctx);
    }

    private static EncodeResult encodeNullableVarBin(String[] strings, EncodeContext ctx) {
        boolean[] validity = validityOf(strings);
        String[] valid = stripNulls(strings);
        MemorySegment packed = buildLengthPrefixed(valid, ctx.arena());
        return buildNullableResult(packed, valid.length, validity, ctx);
    }

    private static EncodeResult buildNullableResult(
            MemorySegment raw, long nValues, boolean[] validity, EncodeContext ctx) {
        // Zero-copy: compress the arena-native packed segment into another arena segment.
        MemorySegment compressed;
        try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
            compressed = cctx.compress(ctx.arena(), raw);
        }
        byte[] meta = new ProtoZstdMetadata(
                0,
                List.of(new ProtoZstdFrameMetadata(raw.byteSize(), nValues))
        ).encode();

        EncodeResult validityResult = new BoolEncodingEncoder().encode(DType.BOOL, validity, ctx);
        // The frame payload owns buffer[0]; the validity child's buffers follow, so shift its
        // buffer indices by one.
        EncodeNode validityNode = EncodeNode.remapBufferIndices(validityResult.rootNode(), 1);

        List<MemorySegment> buffers = new ArrayList<>(1 + validityResult.buffers().size());
        buffers.add(compressed);
        buffers.addAll(validityResult.buffers());

        EncodeNode root = new EncodeNode(EncodingId.VORTEX_ZSTD, MemorySegment.ofArray(meta),
                new EncodeNode[]{validityNode}, new int[]{0});
        return new EncodeResult(root, buffers, null, null);
    }

    private static MemorySegment packValidBytes(
            MemorySegment full, boolean[] validity, int byteWidth, Arena arena) {
        long validBytes = (long) countValid(validity) * byteWidth;
        MemorySegment packed = arena.allocate(Math.max(validBytes, 1), byteWidth);
        long pos = 0;
        for (int i = 0; i < validity.length; i++) {
            if (validity[i]) {
                MemorySegment.copy(full, (long) i * byteWidth, packed, pos, byteWidth);
                pos += byteWidth;
            }
        }
        return packed.asSlice(0, validBytes);
    }

    private static int countValid(boolean[] validity) {
        int count = 0;
        for (boolean valid : validity) {
            if (valid) {
                count++;
            }
        }
        return count;
    }

    private static boolean containsNull(String[] strings) {
        for (String s : strings) {
            if (s == null) {
                return true;
            }
        }
        return false;
    }

    private static boolean[] validityOf(String[] strings) {
        boolean[] validity = new boolean[strings.length];
        for (int i = 0; i < strings.length; i++) {
            validity[i] = strings[i] != null;
        }
        return validity;
    }

    private static String[] stripNulls(String[] strings) {
        String[] valid = new String[countNonNull(strings)];
        int j = 0;
        for (String s : strings) {
            if (s != null) {
                valid[j++] = s;
            }
        }
        return valid;
    }

    private static int countNonNull(String[] strings) {
        int count = 0;
        for (String s : strings) {
            if (s != null) {
                count++;
            }
        }
        return count;
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
