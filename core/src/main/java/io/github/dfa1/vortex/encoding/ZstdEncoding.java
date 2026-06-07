package io.github.dfa1.vortex.encoding;

import com.github.luben.zstd.ZstdDecompressCtx;
import com.google.protobuf.InvalidProtocolBufferException;
import io.airlift.compress.v3.zstd.ZstdCompressor;
import io.airlift.compress.v3.zstd.ZstdDecompressor;
import io.airlift.compress.v3.zstd.ZstdJavaCompressor;
import io.airlift.compress.v3.zstd.ZstdJavaDecompressor;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.Float16Array;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.proto.EncodingProtos;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/// Encoder/decoder for {@code vortex.zstd} — Zstandard-compressed columnar array.
///
/// <p><b>When to use in the cascade:</b> good for low-entropy strings and binary columns.
/// Do NOT add to the numeric cascade alongside ALP/bitpack — on the NYC Taxi dataset,
/// Zstd wins compression for F64 columns (50 MB → 43 MB) but decode throughput collapses
/// 6× (240 → 40 ops/s single-column), because ZSTD decompression is much slower than
/// ALP reconstruction or bitpack unpack. Use it only for {@link io.github.dfa1.vortex.core.DType.Utf8}
/// and {@link io.github.dfa1.vortex.core.DType.Binary} where there is no faster structural alternative.
///
/// <p>Wire format:
/// <ul>
///   <li>Metadata: {@code ZstdMetadata} — {@code dictionary_size} (0 = no dict) + repeated {@code ZstdFrameMetadata}</li>
///   <li>Buffers: one compressed frame per metadata entry (no dictionary buffer when {@code dictionary_size == 0})</li>
///   <li>Child 0: validity bitmap (optional; this decoder rejects nullable arrays)</li>
/// </ul>
///
/// <p>Primitive dtype: raw LE values compressed into a single frame.
/// <p>Utf8/Binary dtype: {@code [u32-LE length][data]} per string, compressed into a single frame.
///
/// <p>Dictionary support: when {@code dictionary_size > 0} the first buffer is the dictionary and
/// frames start at buffer index 1. Decompression uses {@link ZstdDecompressCtx} from {@code zstd-jni}.
/// Nullable arrays (validity child present) are decoded by scattering valid values back into a
/// full-length array wrapped in {@link MaskedArray}.
public final class ZstdEncoding implements Encoding {

    /// Creates a new {@code ZstdEncoding} instance; use via {@link EncodingRegistry}.
    public ZstdEncoding() {
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
        return Encoder.encode(dtype, data);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Encoder {

        private static EncodeResult encode(DType dtype, Object data) {
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
            byte[] meta = EncodingProtos.ZstdMetadata.newBuilder()
                                  .setDictionarySize(0)
                                  .addFrames(EncodingProtos.ZstdFrameMetadata.newBuilder()
                                                     .setUncompressedSize(raw.length)
                                                     .setNValues(n))
                                  .build().toByteArray();
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

    private static final class Decoder {

        private static Array decode(DecodeContext ctx) {
            ByteBuffer rawMeta = ctx.metadata();
            if (rawMeta == null) {
                throw new VortexException(EncodingId.VORTEX_ZSTD, "missing metadata");
            }
            EncodingProtos.ZstdMetadata meta;
            try {
                meta = EncodingProtos.ZstdMetadata.parseFrom(rawMeta.duplicate());
            } catch (InvalidProtocolBufferException e) {
                throw new VortexException(EncodingId.VORTEX_ZSTD, "invalid metadata", e);
            }
            boolean hasDictionary = meta.getDictionarySize() != 0;

            BoolArray validity = null;
            if (ctx.node().children().length > 0) {
                ArrayNode childNode = ctx.node().children()[0];
                DecodeContext childCtx = new DecodeContext(
                        childNode, new DType.Bool(false), ctx.rowCount(),
                        ctx.segmentBuffers(), ctx.registry(), ctx.arena());
                Array validityArray = ctx.registry().decode(childCtx);
                if (!(validityArray instanceof BoolArray ba)) {
                    throw new VortexException(EncodingId.VORTEX_ZSTD,
                            "validity child decoded to unexpected type: " + validityArray.getClass().getSimpleName());
                }
                validity = ba;
            }

            int frameCount = meta.getFramesCount();
            long totalUncompressed = 0;
            for (int i = 0; i < frameCount; i++) {
                totalUncompressed += meta.getFrames(i).getUncompressedSize();
            }

            MemorySegment decompressed = hasDictionary
                                                 ? decompressFramesWithDict(ctx, meta, frameCount, totalUncompressed)
                                                 : decompressFrames(ctx, meta, frameCount, totalUncompressed);

            if (validity == null) {
                return buildArray(ctx.dtype(), ctx.rowCount(), decompressed, ctx);
            } else {
                return buildNullableArray(ctx.dtype(), ctx.rowCount(), decompressed, validity, ctx);
            }
        }

        private static Array buildNullableArray(
                DType dtype, long rowCount, MemorySegment validValues, BoolArray validity, DecodeContext ctx
        ) {
            Array child;
            if (dtype instanceof DType.Primitive dt) {
                child = buildScatteredPrimitive(dt, rowCount, validValues, validity, ctx);
            } else if (dtype instanceof DType.Utf8 || dtype instanceof DType.Binary) {
                child = buildScatteredVarBin(dtype, rowCount, validValues, validity, ctx);
            } else {
                throw new VortexException(EncodingId.VORTEX_ZSTD, "unsupported nullable dtype: " + dtype);
            }
            return new MaskedArray(child, validity);
        }

        private static Array buildScatteredPrimitive(
                DType.Primitive dt, long rowCount, MemorySegment validValues, BoolArray validity, DecodeContext ctx
        ) {
            int byteSize = dt.ptype().byteSize();
            MemorySegment out = ctx.arena().allocate(rowCount * byteSize);
            long readPos = 0;
            for (long i = 0; i < rowCount; i++) {
                if (validity.getBoolean(i)) {
                    MemorySegment.copy(validValues, readPos, out, i * byteSize, byteSize);
                    readPos += byteSize;
                }
            }
            DType.Primitive nonNull = new DType.Primitive(dt.ptype(), false);
            return buildPrimitive(nonNull, rowCount, out);
        }

        private static VarBinArray buildScatteredVarBin(
                DType dtype, long rowCount, MemorySegment validValues, BoolArray validity, DecodeContext ctx
        ) {
            // First pass: total data bytes across valid positions only
            long totalDataBytes = 0;
            long scanPos = 0;
            for (long i = 0; i < rowCount; i++) {
                if (validity.getBoolean(i)) {
                    int len = validValues.get(PTypeIO.LE_INT, scanPos);
                    scanPos += 4L + len;
                    totalDataBytes += len;
                }
            }

            MemorySegment values = ctx.arena().allocate(totalDataBytes > 0 ? totalDataBytes : 1);
            MemorySegment offsets = ctx.arena().allocate((rowCount + 1) * 4L, 4);
            offsets.setAtIndex(PTypeIO.LE_INT, 0, 0);

            long readPos = 0;
            long dataPos = 0;
            for (long i = 0; i < rowCount; i++) {
                if (validity.getBoolean(i)) {
                    int len = validValues.get(PTypeIO.LE_INT, readPos);
                    readPos += 4;
                    MemorySegment.copy(validValues, readPos, values, dataPos, len);
                    readPos += len;
                    dataPos += len;
                }
                offsets.setAtIndex(PTypeIO.LE_INT, i + 1, (int) dataPos);
            }

            DType i32 = new DType.Primitive(PType.I32, false);
            IntArray offsetsArr = new IntArray(i32, rowCount + 1, offsets);
            return new VarBinArray(dtype.withNullable(false), rowCount, values, offsetsArr, PType.I32);
        }

        private static MemorySegment decompressFramesWithDict(
                DecodeContext ctx,
                EncodingProtos.ZstdMetadata meta,
                int frameCount,
                long totalUncompressed
        ) {
            MemorySegment out = ctx.arena().allocate(totalUncompressed);
            byte[] dictBytes = ctx.buffer(0).toArray(ValueLayout.JAVA_BYTE);
            try (ZstdDecompressCtx zctx = new ZstdDecompressCtx()) {
                zctx.loadDict(dictBytes);
                long outOffset = 0;
                for (int i = 0; i < frameCount; i++) {
                    byte[] compressed = ctx.buffer(i + 1).toArray(ValueLayout.JAVA_BYTE);
                    int uncompSize = (int) meta.getFrames(i).getUncompressedSize();
                    byte[] temp = new byte[uncompSize];
                    int written = zctx.decompressByteArray(temp, 0, uncompSize, compressed, 0, compressed.length);
                    if (written != uncompSize) {
                        throw new VortexException(EncodingId.VORTEX_ZSTD,
                                "frame " + i + ": expected " + uncompSize + " bytes, got " + written);
                    }
                    MemorySegment.copy(MemorySegment.ofArray(temp), 0, out, outOffset, uncompSize);
                    outOffset += uncompSize;
                }
            } catch (VortexException e) {
                throw e;
            } catch (Exception e) {
                throw new VortexException(EncodingId.VORTEX_ZSTD, "dict decompression failed", e);
            }
            return out;
        }

        private static MemorySegment decompressFrames(
                DecodeContext ctx,
                EncodingProtos.ZstdMetadata meta,
                int frameCount,
                long totalUncompressed
        ) {
            MemorySegment out = ctx.arena().allocate(totalUncompressed);
            ZstdDecompressor decompressor = new ZstdJavaDecompressor();
            long outOffset = 0;
            for (int i = 0; i < frameCount; i++) {
                MemorySegment frameSeg = ctx.buffer(i);
                byte[] compressed = frameSeg.toArray(ValueLayout.JAVA_BYTE);
                int uncompSize = (int) meta.getFrames(i).getUncompressedSize();
                byte[] temp = new byte[uncompSize];
                int written = decompressor.decompress(compressed, 0, compressed.length, temp, 0, uncompSize);
                if (written != uncompSize) {
                    throw new VortexException(EncodingId.VORTEX_ZSTD,
                            "frame " + i + ": expected " + uncompSize + " bytes, got " + written);
                }
                MemorySegment.copy(MemorySegment.ofArray(temp), 0, out, outOffset, uncompSize);
                outOffset += uncompSize;
            }
            return out;
        }

        private static Array buildArray(DType dtype, long n, MemorySegment decompressed, DecodeContext ctx) {
            if (dtype instanceof DType.Primitive dt) {
                return buildPrimitive(dt, n, decompressed);
            }
            if (dtype instanceof DType.Utf8 || dtype instanceof DType.Binary) {
                return buildVarBin(dtype, n, decompressed, ctx);
            }
            throw new VortexException(EncodingId.VORTEX_ZSTD, "unsupported dtype: " + dtype);
        }

        private static Array buildPrimitive(DType.Primitive dt, long n, MemorySegment decompressed) {
            PType ptype = dt.ptype();
            return switch (ptype) {
                case I64, U64 -> new LongArray(dt, n, decompressed);
                case I32, U32 -> new IntArray(dt, n, decompressed);
                case F64 -> new DoubleArray(dt, n, decompressed);
                case F32 -> new FloatArray(dt, n, decompressed);
                case I16, U16 -> new ShortArray(dt, n, decompressed);
                case I8, U8 -> new ByteArray(dt, n, decompressed);
                case F16 -> new Float16Array(dt, n, decompressed);
            };
        }

        private static VarBinArray buildVarBin(DType dtype, long n, MemorySegment decompressed, DecodeContext ctx) {
            // scan [u32-LE length][data] pairs to compute total data bytes
            long totalDataBytes = 0;
            long pos = 0;
            for (long i = 0; i < n; i++) {
                int len = decompressed.get(PTypeIO.LE_INT, pos);
                pos += 4 + len;
                totalDataBytes += len;
            }

            MemorySegment values = ctx.arena().allocate(totalDataBytes);
            MemorySegment offsets = ctx.arena().allocate((n + 1) * 4L, 4);
            offsets.setAtIndex(PTypeIO.LE_INT, 0, 0);

            pos = 0;
            long dataPos = 0;
            for (long i = 0; i < n; i++) {
                int len = decompressed.get(PTypeIO.LE_INT, pos);
                pos += 4;
                MemorySegment.copy(decompressed, pos, values, dataPos, len);
                pos += len;
                dataPos += len;
                offsets.setAtIndex(PTypeIO.LE_INT, i + 1, (int) dataPos);
            }

            DType i32 = new DType.Primitive(PType.I32, false);
            IntArray offsetsArr = new IntArray(i32, n + 1, offsets);
            return new VarBinArray(dtype, n, values, offsetsArr, PType.I32);
        }
    }
}
