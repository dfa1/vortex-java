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
///
/// By default the whole array compresses into a single zstd frame. Construct with a positive
/// `valuesPerFrame` to split the payload into independently compressed frames of that many values
/// each (the last frame holds the remainder), emitting one `ZstdFrameMetadata` per frame. Multiple
/// frames let a slice scan decompress only the frames overlapping its row range.
public final class ZstdEncodingEncoder implements EncodingEncoder {

    /// Values per zstd frame; `0` (or any non-positive value) means a single frame for the whole array.
    private final long valuesPerFrame;

    /// Public no-arg constructor required by [java.util.ServiceLoader]; compresses each array into
    /// a single frame.
    public ZstdEncodingEncoder() {
        this(0);
    }

    /// Creates an encoder that splits the payload into frames of `valuesPerFrame` values each.
    ///
    /// @param valuesPerFrame the number of values per zstd frame; non-positive means a single frame
    ///                       for the whole array
    public ZstdEncodingEncoder(long valuesPerFrame) {
        this.valuesPerFrame = valuesPerFrame;
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
    public boolean acceptsNullable(DType dtype) {
        // Nullable primitive, utf8 and binary columns all arrive as a NullableData carrier and are
        // encoded directly here (validity emitted as Bool child[0]) rather than masked-wrapped.
        return dtype instanceof DType.Primitive || dtype instanceof DType.Utf8 || dtype instanceof DType.Binary;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (data instanceof NullableData nd) {
            if (dtype instanceof DType.Primitive dt) {
                return encodeNullablePrimitive(dt, nd, ctx);
            }
            if (dtype instanceof DType.Utf8 || dtype instanceof DType.Binary) {
                return encodeNullableVarBin(nd, ctx);
            }
            throw new VortexException(EncodingId.VORTEX_ZSTD,
                    "NullableData is unsupported for dtype: " + dtype);
        }
        if (dtype instanceof DType.Primitive dt) {
            return encodePrimitive(dt, data, ctx.arena());
        }
        if (dtype instanceof DType.Utf8 || dtype instanceof DType.Binary) {
            String[] strings = (String[]) data;
            if (containsNull(strings)) {
                throw new VortexException(EncodingId.VORTEX_ZSTD,
                        "non-nullable " + dtype + " contains null");
            }
            return encodeVarBin(strings, ctx.arena());
        }
        throw new VortexException(EncodingId.VORTEX_ZSTD, "unsupported dtype: " + dtype);
    }

    private EncodeResult encodePrimitive(DType.Primitive dt, Object data, Arena arena) {
        int byteWidth = dt.ptype().byteSize();
        MemorySegment raw = primitiveToLeBytes(dt.ptype(), data, arena);
        long n = primitiveLength(dt.ptype(), data);
        return buildResult(raw, uniformLayout(n, byteWidth), arena);
    }

    private EncodeResult encodeVarBin(String[] strings, Arena arena) {
        MemorySegment raw = buildLengthPrefixed(strings, arena);
        return buildResult(raw, varBinLayout(raw, strings.length), arena);
    }

    private EncodeResult buildResult(MemorySegment raw, FrameLayout layout, Arena arena) {
        // Zero-copy: each frame is an arena-native slice of raw, compressed straight into another
        // arena segment. A single-value-per-array config yields one frame (the prior behaviour).
        Frames frames = compressFrames(raw, layout, arena);
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_ZSTD, MemorySegment.ofArray(frames.metadata()),
                new EncodeNode[0], frameBufferIndices(frames.compressed().size(), 0));
        return new EncodeResult(root, List.copyOf(frames.compressed()), null, null);
    }

    private EncodeResult encodeNullablePrimitive(DType.Primitive dt, NullableData nd, EncodeContext ctx) {
        Arena arena = ctx.arena();
        int byteWidth = dt.ptype().byteSize();
        boolean[] validity = nd.validity();
        // Strip null positions: only valid values reach the compressed payload (mirrors the Rust
        // reference). The decoder scatters them back over the validity mask carried by child[0].
        MemorySegment full = primitiveToLeBytes(dt.ptype(), nd.values(), arena);
        MemorySegment packed = packValidBytes(full, validity, byteWidth, arena);
        return buildNullableResult(packed, uniformLayout(countValid(validity), byteWidth), validity, ctx);
    }

    private EncodeResult encodeNullableVarBin(NullableData nd, EncodeContext ctx) {
        // Strip null positions: only valid strings reach the compressed payload (mirrors the Rust
        // reference). The decoder scatters them back over the validity mask carried by child[0].
        String[] valid = stripNulls((String[]) nd.values());
        MemorySegment packed = buildLengthPrefixed(valid, ctx.arena());
        return buildNullableResult(packed, varBinLayout(packed, valid.length), nd.validity(), ctx);
    }

    private EncodeResult buildNullableResult(
            MemorySegment raw, FrameLayout layout, boolean[] validity, EncodeContext ctx) {
        Frames frames = compressFrames(raw, layout, ctx.arena());
        int frameCount = frames.compressed().size();

        EncodeResult validityResult = new BoolEncodingEncoder().encode(DType.BOOL, validity, ctx);
        // The frame payloads own buffer[0..frameCount-1]; the validity child's buffers follow, so
        // shift its buffer indices past them.
        EncodeNode validityNode = EncodeNode.remapBufferIndices(validityResult.rootNode(), frameCount);

        List<MemorySegment> buffers = new ArrayList<>(frameCount + validityResult.buffers().size());
        buffers.addAll(frames.compressed());
        buffers.addAll(validityResult.buffers());

        EncodeNode root = new EncodeNode(EncodingId.VORTEX_ZSTD, MemorySegment.ofArray(frames.metadata()),
                new EncodeNode[]{validityNode}, frameBufferIndices(frameCount, 0));
        return new EncodeResult(root, buffers, null, null);
    }

    /// Byte spans and value counts of each frame; spans sum to the payload size.
    private record FrameLayout(long[] byteLengths, long[] valueCounts) {
    }

    /// Compressed frame payloads paired with the encoded `ZstdMetadata` describing them.
    private record Frames(List<MemorySegment> compressed, byte[] metadata) {
    }

    private static int[] frameBufferIndices(int frameCount, int base) {
        int[] indices = new int[frameCount];
        for (int i = 0; i < frameCount; i++) {
            indices[i] = base + i;
        }
        return indices;
    }

    /// Frame layout for `n` fixed-width values (`byteWidth` bytes each): `valuesPerFrame` values
    /// per frame, the last frame holding the remainder. One frame when framing is disabled.
    private FrameLayout uniformLayout(long n, int byteWidth) {
        if (valuesPerFrame <= 0 || n <= valuesPerFrame) {
            return new FrameLayout(new long[]{n * byteWidth}, new long[]{n});
        }
        int frameCount = (int) ((n + valuesPerFrame - 1) / valuesPerFrame);
        long[] byteLengths = new long[frameCount];
        long[] valueCounts = new long[frameCount];
        long remaining = n;
        for (int f = 0; f < frameCount; f++) {
            long count = Math.min(valuesPerFrame, remaining);
            valueCounts[f] = count;
            byteLengths[f] = count * byteWidth;
            remaining -= count;
        }
        return new FrameLayout(byteLengths, valueCounts);
    }

    /// Frame layout for a length-prefixed varbin payload: `valuesPerFrame` values per frame, with
    /// each frame's byte span found by walking the 4-byte length prefixes to a value boundary.
    private FrameLayout varBinLayout(MemorySegment raw, long nValues) {
        if (valuesPerFrame <= 0 || nValues <= valuesPerFrame) {
            return new FrameLayout(new long[]{raw.byteSize()}, new long[]{nValues});
        }
        int frameCount = (int) ((nValues + valuesPerFrame - 1) / valuesPerFrame);
        long[] byteLengths = new long[frameCount];
        long[] valueCounts = new long[frameCount];
        long pos = 0;
        long valueIdx = 0;
        for (int f = 0; f < frameCount; f++) {
            long count = Math.min(valuesPerFrame, nValues - valueIdx);
            long start = pos;
            for (long k = 0; k < count; k++) {
                int len = raw.get(PTypeIO.LE_INT, pos);
                pos += 4L + len;
            }
            byteLengths[f] = pos - start;
            valueCounts[f] = count;
            valueIdx += count;
        }
        return new FrameLayout(byteLengths, valueCounts);
    }

    private static Frames compressFrames(MemorySegment raw, FrameLayout layout, Arena arena) {
        int frameCount = layout.byteLengths().length;
        List<MemorySegment> compressed = new ArrayList<>(frameCount);
        List<ProtoZstdFrameMetadata> metas = new ArrayList<>(frameCount);
        long offset = 0;
        try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
            for (int f = 0; f < frameCount; f++) {
                long len = layout.byteLengths()[f];
                compressed.add(cctx.compress(arena, raw.asSlice(offset, len)));
                metas.add(new ProtoZstdFrameMetadata(len, layout.valueCounts()[f]));
                offset += len;
            }
        }
        byte[] metadata = new ProtoZstdMetadata(0, List.copyOf(metas)).encode();
        return new Frames(compressed, metadata);
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
