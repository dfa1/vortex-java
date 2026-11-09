package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoZstdFrameMetadata;
import io.github.dfa1.vortex.core.proto.ProtoZstdMetadata;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// Write-only encoder for `vortex.zstd`.
///
/// By default the whole array compresses into a single zstd frame. Construct with a positive
/// `valuesPerFrame` to split the payload into independently compressed frames of that many values
/// each (the last frame holds the remainder), emitting one `ZstdFrameMetadata` per frame. Multiple
/// frames let a slice scan decompress only the frames overlapping its row range.
public final class ZstdEncodingEncoder implements EncodingEncoder {

    // The io.github.dfa1.zstd binding is an OPTIONAL dependency: probe once so a writer
    // touching vortex.zstd without it gets an actionable message instead of a raw
    // NoClassDefFoundError surfacing from the first binding call.
    private static final boolean ZSTD_BINDING_PRESENT =
            bindingPresent("io.github.dfa1.zstd.ZstdCompressContext");

    /// Reports whether the named binding class is loadable — package-private and
    /// class-name-parameterized so both branches are unit-testable without the binding
    /// having to actually be absent from the classpath.
    ///
    /// @param className fully qualified class name to probe
    /// @return `true` if the class resolves, `false` if it is absent
    static boolean bindingPresent(String className) {
        try {
            Class.forName(className, false, ZstdEncodingEncoder.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException _) {
            return false;
        }
    }

    /// Fails with an actionable [VortexException] when the optional zstd binding is absent.
    ///
    /// @param present whether the binding is on the classpath
    /// @throws VortexException naming the two artifacts to add, if `present` is `false`
    static void requireBinding(boolean present) {
        if (!present) {
            throw new VortexException(EncodingId.VORTEX_ZSTD, "vortex.zstd encoding requires the "
                    + "optional zstd binding — add io.github.dfa1.zstd:zstd and "
                    + "io.github.dfa1.zstd:zstd-platform (versions pinned by the vortex BOM)");
        }
    }

    /// Values per frame of the compact preset, as in Rust's `from_var_bin_view_without_dict(.., 3, 8192, ..)`.
    private static final long TEXT_VALUES_PER_FRAME = 8192;

    /// Largest uncompressed payload of one varbin frame: half the 2 GiB a reader takes per frame.
    private static final long MAX_FRAME_BYTES = 1L << 30;

    /// The varbin frame cap in use: [#MAX_FRAME_BYTES], smaller in tests.
    private final long maxFrameBytes;

    /// Values per zstd frame; `0` (or any non-positive value) means a single frame for the whole array.
    private final long valuesPerFrame;

    /// Whether only strings and binary are accepted, as in Rust's compact preset, which hands numbers to Pco.
    private final boolean textOnly;

    /// Creates an encoder that compresses each array into a single frame.
    public ZstdEncodingEncoder() {
        this(0);
    }

    /// Creates an encoder for strings and binary only, as Rust's compact preset uses Zstd, leaving
    /// numbers to Pco. It writes [#TEXT_VALUES_PER_FRAME] values per frame, as Rust's binary
    /// Zstd scheme does, so a row-range scan decompresses only the frames it overlaps.
    ///
    /// @return a text-only encoder
    public static ZstdEncodingEncoder forText() {
        return new ZstdEncodingEncoder(TEXT_VALUES_PER_FRAME, true);
    }

    /// Creates an encoder that splits the payload into frames of `valuesPerFrame` values each.
    ///
    /// @param valuesPerFrame the number of values per zstd frame; non-positive means a single frame
    ///                       for the whole array
    public ZstdEncodingEncoder(long valuesPerFrame) {
        this(valuesPerFrame, false);
    }

    private ZstdEncodingEncoder(long valuesPerFrame, boolean textOnly) {
        this(valuesPerFrame, textOnly, MAX_FRAME_BYTES);
    }

    /// Creates an encoder with a smaller varbin frame cap, so tests reach the split without 1 GiB.
    ///
    /// @param valuesPerFrame values per frame; non-positive means no count limit
    /// @param textOnly       whether only strings and binary are accepted
    /// @param maxFrameBytes  the largest uncompressed varbin frame payload
    ZstdEncodingEncoder(long valuesPerFrame, boolean textOnly, long maxFrameBytes) {
        this.valuesPerFrame = valuesPerFrame;
        this.textOnly = textOnly;
        this.maxFrameBytes = maxFrameBytes;
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ZSTD;
    }

    @Override
    public boolean accepts(DType dtype) {
        return (!textOnly && dtype instanceof DType.Primitive) || dtype instanceof DType.Utf8
                || dtype instanceof DType.Binary;
    }

    @Override
    public boolean acceptsNullable(DType dtype) {
        // Nullable primitive, utf8, and binary columns arrive as a NullableData carrier and are
        // encoded directly here (validity emitted as Bool child[0]) rather than masked-wrapped.
        return accepts(dtype);
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        requireBinding(ZSTD_BINDING_PRESENT);
        // Compression does not reorder anything, so the column's bounds are the input's bounds.
        if (data instanceof NullableData nd) {
            if (dtype instanceof DType.Primitive dt) {
                return encodeNullablePrimitive(dt, nd, ctx).withStats(ZoneMapStats.of(dtype, data));
            }
            if (dtype instanceof DType.Utf8 || dtype instanceof DType.Binary) {
                byte[][] encoded = VarBinBytes.toRawByteArrays(nd.values());
                return encodeNullableVarBin(encoded, nd.validity(), ctx).withStats(textStats(dtype, nd.values(), encoded));
            }
            throw new VortexException(EncodingId.VORTEX_ZSTD,
                    "NullableData is unsupported for dtype: " + dtype);
        }
        if (dtype instanceof DType.Primitive dt) {
            return encodePrimitive(dt, data, ctx.arena()).withStats(ZoneMapStats.of(dtype, data));
        }
        if (dtype instanceof DType.Utf8 || dtype instanceof DType.Binary) {
            byte[][] encoded = VarBinBytes.toRawByteArrays(data);
            if (containsNull(encoded)) {
                throw new VortexException(EncodingId.VORTEX_ZSTD,
                        "non-nullable " + dtype + " contains null");
            }
            return encodeVarBin(encoded, ctx.arena()).withStats(textStats(dtype, data, encoded));
        }
        throw new VortexException(EncodingId.VORTEX_ZSTD, "unsupported dtype: " + dtype);
    }

    private EncodeResult encodePrimitive(DType.Primitive dt, Object data, Arena arena) {
        int byteWidth = dt.ptype().byteSize();
        MemorySegment raw = PrimitiveArrays.toSegment(data, dt.ptype(), arena);
        long n = Array.getLength(data);
        return buildResult(raw, uniformLayout(n, byteWidth), arena);
    }

    private EncodeResult encodeVarBin(byte[][] encoded, Arena arena) {
        MemorySegment raw = buildLengthPrefixed(encoded, arena);
        return buildResult(raw, varBinLayout(raw, encoded.length), arena);
    }

    private EncodeResult buildResult(MemorySegment raw, FrameLayout layout, Arena arena) {
        // Zero-copy: each frame is an arena-native slice of raw, compressed straight into another
        // arena segment. A single-value-per-array config yields one frame (the prior behavior).
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
        MemorySegment full = PrimitiveArrays.toSegment(nd.values(), dt.ptype(), arena);
        MemorySegment packed = packValidBytes(full, validity, byteWidth, arena);
        return buildNullableResult(packed, uniformLayout(countValid(validity), byteWidth), validity, ctx);
    }

    private EncodeResult encodeNullableVarBin(byte[][] encoded, boolean[] validity, EncodeContext ctx) {
        // Strip null positions: only valid rows reach the compressed payload (mirrors the Rust
        // reference). The decoder scatters them back over the validity mask carried by child[0].
        // The validity decides, not the row: a caller may hold a null row as an empty placeholder.
        byte[][] valid = stripNulls(encoded, validity);
        MemorySegment packed = buildLengthPrefixed(valid, ctx.arena());
        return buildNullableResult(packed, varBinLayout(packed, valid.length), validity, ctx);
    }

    /// The zone-map bounds of a string column, ordered by the UTF-8 bytes already encoded for the
    /// payload rather than by comparing the `String`s again: the vectorized byte compare is several
    /// times faster on values sharing a prefix (timestamps, ids), where the `String` compare was the
    /// largest single cost of a compact CSV import. Binary has no useful ordering, as in [ZoneMapStats].
    private static MinMax textStats(DType dtype, Object values, byte[][] encoded) {
        if (values instanceof String[] strings) {
            return VarBinEncodingEncoder.minMaxStats(strings, encoded);
        }
        return VarBinEncodingEncoder.textMinMax(dtype, values);
    }

    private EncodeResult buildNullableResult(
            MemorySegment raw, FrameLayout layout, boolean[] validity, EncodeContext ctx) {
        Frames frames = compressFrames(raw, layout, ctx.arena());
        int frameCount = frames.compressed().size();

        EncodeResult validityResult = new BoolEncodingEncoder().encode(DTypes.BOOL, validity, ctx);
        // The frame payloads own buffer[0..frameCount-1]; the validity child's buffers follow, so
        // shift its buffer indices past them.
        EncodeNode validityNode = EncodeNode.remapBufferIndices(validityResult.rootNode(), frameCount);

        List<EncodedBuffer> buffers = new ArrayList<>(frameCount + validityResult.buffers().size());
        buffers.addAll(frames.compressed());
        buffers.addAll(validityResult.encodedBuffers());

        EncodeNode root = new EncodeNode(EncodingId.VORTEX_ZSTD, MemorySegment.ofArray(frames.metadata()),
                new EncodeNode[]{validityNode}, frameBufferIndices(frameCount, 0));
        return new EncodeResult(root, buffers, null, null);
    }

    /// Byte spans and value counts of each frame; spans sum to the payload size.
    private record FrameLayout(long[] byteLengths, long[] valueCounts) {
        @Override
        public boolean equals(Object o) {
            return o instanceof FrameLayout(long[] bl, long[] vc)
                    && Arrays.equals(byteLengths, bl) && Arrays.equals(valueCounts, vc);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(byteLengths) + Arrays.hashCode(valueCounts);
        }

        @Override
        public String toString() {
            return "FrameLayout[byteLengths=" + Arrays.toString(byteLengths)
                    + ", valueCounts=" + Arrays.toString(valueCounts) + "]";
        }
    }

    /// Compressed frame payloads paired with the encoded `ZstdMetadata` describing them.
    private record Frames(List<EncodedBuffer> compressed, byte[] metadata) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Frames(List<EncodedBuffer> c, byte[] m)
                    && Objects.equals(compressed, c) && Arrays.equals(metadata, m);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(compressed) + Arrays.hashCode(metadata);
        }

        @Override
        public String toString() {
            return "Frames[compressed=" + compressed + ", metadata=" + Arrays.toString(metadata) + "]";
        }
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
    ///
    /// A frame is also closed before its payload passes [#MAX_FRAME_BYTES] (#633): the reader
    /// caps a frame's declared size at 2 GiB until ADR 0004's resource limits exist, so a frame this
    /// writer emits stays readable. A payload under the cap keeps the plain layout. A single value
    /// larger than the cap is a frame of its own; past 2 GiB it would not read back, but no Java
    /// array holds such a row.
    private FrameLayout varBinLayout(MemorySegment raw, long nValues) {
        boolean fitsOneFrame = valuesPerFrame <= 0 || nValues <= valuesPerFrame;
        if (fitsOneFrame && raw.byteSize() <= maxFrameBytes) {
            return new FrameLayout(new long[]{raw.byteSize()}, new long[]{nValues});
        }
        List<Long> byteLengths = new ArrayList<>();
        List<Long> valueCounts = new ArrayList<>();
        long pos = 0;
        long frameStart = 0;
        long count = 0;
        for (long v = 0; v < nValues; v++) {
            long valueBytes = 4L + raw.get(VortexFormat.LE_INT, pos);
            boolean full = valuesPerFrame > 0 && count == valuesPerFrame;
            if (count > 0 && (full || pos - frameStart + valueBytes > maxFrameBytes)) {
                byteLengths.add(pos - frameStart);
                valueCounts.add(count);
                frameStart = pos;
                count = 0;
            }
            pos += valueBytes;
            count++;
        }
        byteLengths.add(pos - frameStart);
        valueCounts.add(count);
        return new FrameLayout(byteLengths.stream().mapToLong(Long::longValue).toArray(),
                valueCounts.stream().mapToLong(Long::longValue).toArray());
    }

    private static Frames compressFrames(MemorySegment raw, FrameLayout layout, Arena arena) {
        int frameCount = layout.byteLengths().length;
        List<EncodedBuffer> compressed = new ArrayList<>(frameCount);
        List<ProtoZstdFrameMetadata> metas = new ArrayList<>(frameCount);
        long offset = 0;
        try (ZstdCompressContext cctx = new ZstdCompressContext()) {
            for (int f = 0; f < frameCount; f++) {
                long len = layout.byteLengths()[f];
                compressed.add(EncodedBuffer.bytes(cctx.compress(arena, raw.asSlice(offset, len))));
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

    private static boolean containsNull(byte[][] rows) {
        for (byte[] b : rows) {
            if (b == null) {
                return true;
            }
        }
        return false;
    }

    private static byte[][] stripNulls(byte[][] rows, boolean[] validity) {
        byte[][] valid = new byte[countValid(validity)][];
        int j = 0;
        for (int i = 0; i < rows.length; i++) {
            if (validity[i]) {
                valid[j++] = rows[i];
            }
        }
        return valid;
    }

    private static MemorySegment buildLengthPrefixed(byte[][] encoded, Arena arena) {
        // long: a chunk's rows may pass 2 GiB, which an int total wraps negative (#633)
        long total = 0;
        for (byte[] bytes : encoded) {
            total += 4 + bytes.length;
        }
        MemorySegment seg = arena.allocate(total > 0 ? total : 1);
        long pos = 0;
        for (byte[] bytes : encoded) {
            seg.set(VortexFormat.LE_INT, pos, bytes.length);
            pos += 4;
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, seg, pos, bytes.length);
            pos += bytes.length;
        }
        return seg.asSlice(0, total);
    }
}
