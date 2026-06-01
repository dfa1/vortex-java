package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/// Decoder for {@code vortex.zstd} — Zstandard-compressed columnar array.
///
/// <p>Wire format:
/// <ul>
///   <li>Metadata: {@code ZstdMetadata} — {@code dictionary_size} (0 = no dict) + repeated {@code ZstdFrameMetadata}</li>
///   <li>Buffers: one compressed frame per metadata entry (no dictionary buffer when {@code dictionary_size == 0})</li>
///   <li>Child 0: validity bitmap (optional; this decoder rejects nullable arrays)</li>
/// </ul>
///
/// <p>Primitive dtype: decompressed bytes are raw LE values → returned as typed primitive array.
/// <p>Utf8/Binary dtype: decompressed bytes interleave {@code [u32-LE length][data]} per valid string.
///
/// <p>Scope: decode only, no dictionary, non-nullable.
public final class ZstdEncoding implements Encoding {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ZSTD;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data) {
        throw new UnsupportedOperationException("encode not supported by " + encodingId());
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
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
