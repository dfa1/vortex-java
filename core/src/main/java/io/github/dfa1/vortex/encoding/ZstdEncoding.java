package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import io.airlift.compress.zstd.ZstdCompressor;
import io.airlift.compress.zstd.ZstdDecompressor;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.Float16Array;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
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
/// <p>No dictionary, no nullable (validity child not supported).
public final class ZstdEncoding implements Encoding {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ZSTD;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive || dtype instanceof DType.Utf8 || dtype instanceof DType.Binary;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data) {
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
                return encodeVarBin(dtype, (String[]) data);
            }
            throw new VortexException(EncodingId.VORTEX_ZSTD, "unsupported dtype: " + dtype);
        }

        private static EncodeResult encodePrimitive(DType.Primitive dt, Object data) {
            MemorySegment raw = primitiveToLeBytes(dt.ptype(), data);
            long n = primitiveLength(dt.ptype(), data);
            byte[] rawBytes = raw.toArray(ValueLayout.JAVA_BYTE);
            return buildResult(rawBytes, n);
        }

        private static EncodeResult encodeVarBin(DType dtype, String[] strings) {
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
            ZstdCompressor compressor = new ZstdCompressor();
            byte[] out = new byte[compressor.maxCompressedLength(input.length)];
            int len = compressor.compress(input, 0, input.length, out, 0, out.length);
            return Arrays.copyOf(out, len);
        }

        private static MemorySegment primitiveToLeBytes(PType ptype, Object data) {
            return switch (ptype) {
                case I8, U8 -> MemorySegment.ofArray((byte[]) data);
                case I16, U16, F16 -> {
                    short[] arr = (short[]) data;
                    MemorySegment seg = Arena.ofAuto().allocate((long) arr.length * 2, 2);
                    for (int i = 0; i < arr.length; i++) {
                        seg.setAtIndex(PTypeIO.LE_SHORT, i, arr[i]);
                    }
                    yield seg;
                }
                case I32, U32 -> {
                    int[] arr = (int[]) data;
                    MemorySegment seg = Arena.ofAuto().allocate((long) arr.length * 4, 4);
                    for (int i = 0; i < arr.length; i++) {
                        seg.setAtIndex(PTypeIO.LE_INT, i, arr[i]);
                    }
                    yield seg;
                }
                case I64, U64 -> {
                    long[] arr = (long[]) data;
                    MemorySegment seg = Arena.ofAuto().allocate((long) arr.length * 8, 8);
                    for (int i = 0; i < arr.length; i++) {
                        seg.setAtIndex(PTypeIO.LE_LONG, i, arr[i]);
                    }
                    yield seg;
                }
                case F32 -> {
                    float[] arr = (float[]) data;
                    MemorySegment seg = Arena.ofAuto().allocate((long) arr.length * 4, 4);
                    for (int i = 0; i < arr.length; i++) {
                        seg.setAtIndex(PTypeIO.LE_FLOAT, i, arr[i]);
                    }
                    yield seg;
                }
                case F64 -> {
                    double[] arr = (double[]) data;
                    MemorySegment seg = Arena.ofAuto().allocate((long) arr.length * 8, 8);
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
            MemorySegment seg = Arena.ofAuto().allocate(total > 0 ? total : 1);
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
            if (meta.getDictionarySize() != 0) {
                throw new VortexException(EncodingId.VORTEX_ZSTD, "dictionary not supported");
            }
            if (ctx.node().children().length > 0) {
                throw new VortexException(EncodingId.VORTEX_ZSTD, "nullable arrays not supported");
            }

            int frameCount = meta.getFramesCount();
            long totalUncompressed = 0;
            for (int i = 0; i < frameCount; i++) {
                totalUncompressed += meta.getFrames(i).getUncompressedSize();
            }

            MemorySegment decompressed = decompressFrames(ctx, meta, frameCount, totalUncompressed);
            return buildArray(ctx.dtype(), ctx.rowCount(), decompressed, ctx);
        }

        private static MemorySegment decompressFrames(
                DecodeContext ctx,
                EncodingProtos.ZstdMetadata meta,
                int frameCount,
                long totalUncompressed
        ) {
            MemorySegment out = ctx.arena().allocate(totalUncompressed);
            ZstdDecompressor decompressor = new ZstdDecompressor();
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
                case I64, U64 -> new LongArray(dt, n, decompressed, ArrayStats.empty());
                case I32, U32 -> new IntArray(dt, n, decompressed, ArrayStats.empty());
                case F64 -> new DoubleArray(dt, n, decompressed, ArrayStats.empty());
                case F32 -> new FloatArray(dt, n, decompressed, ArrayStats.empty());
                case I16, U16 -> new ShortArray(dt, n, decompressed, ArrayStats.empty());
                case I8, U8 -> new ByteArray(dt, n, decompressed, ArrayStats.empty());
                case F16 -> new Float16Array(dt, n, decompressed, ArrayStats.empty());
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
            IntArray offsetsArr = new IntArray(i32, n + 1, offsets, ArrayStats.empty());
            return new VarBinArray(dtype, n, values, offsetsArr, PType.I32, ArrayStats.empty());
        }
    }
}
