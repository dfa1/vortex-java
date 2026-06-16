package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.ZstdMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloat16Array;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

import io.airlift.compress.v3.zstd.ZstdDecompressor;
import io.airlift.compress.v3.zstd.ZstdJavaDecompressor;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/// Read-only decoder for `vortex.zstd`.
public final class ZstdEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public ZstdEncodingDecoder() {
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
    public Array decode(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        if (rawMeta == null) {
            throw new VortexException(EncodingId.VORTEX_ZSTD, "missing metadata");
        }
        ZstdMetadata meta;
        try {
            MemorySegment metaSeg = MemorySegment.ofBuffer(rawMeta.duplicate());
            meta = ZstdMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_ZSTD, "invalid metadata", e);
        }
        if (meta.dictionary_size() != 0) {
            throw new VortexException(EncodingId.VORTEX_ZSTD,
                    "dictionary-compressed Zstd segments are not supported (pure-Java decoder)");
        }

        BoolArray validity = null;
        if (ctx.node().children().length > 0) {
            Array validityArray = ctx.decodeChild(0, new DType.Bool(false), ctx.rowCount());
            if (!(validityArray instanceof BoolArray ba)) {
                throw new VortexException(EncodingId.VORTEX_ZSTD,
                        "validity child decoded to unexpected type: " + validityArray.getClass().getSimpleName());
            }
            validity = ba;
        }

        int frameCount = meta.frames().size();
        long totalUncompressed = 0;
        for (int i = 0; i < frameCount; i++) {
            totalUncompressed += meta.frames().get(i).uncompressed_size();
        }

        MemorySegment decompressed = decompressFrames(ctx, meta, frameCount, totalUncompressed);

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

        return new VarBinArray.OffsetMode(dtype.withNullable(false), rowCount, values, offsets, PType.I32);
    }

    private static MemorySegment decompressFrames(
            DecodeContext ctx,
            ZstdMetadata meta,
            int frameCount,
            long totalUncompressed
    ) {
        MemorySegment out = ctx.arena().allocate(totalUncompressed);
        ZstdDecompressor decompressor = new ZstdJavaDecompressor();
        long outOffset = 0;
        for (int i = 0; i < frameCount; i++) {
            MemorySegment frameSeg = ctx.buffer(i);
            byte[] compressed = frameSeg.toArray(ValueLayout.JAVA_BYTE);
            int uncompSize = (int) meta.frames().get(i).uncompressed_size();
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
            case I64, U64 -> new MaterializedLongArray(dt, n, decompressed);
            case I32, U32 -> new MaterializedIntArray(dt, n, decompressed);
            case F64 -> new MaterializedDoubleArray(dt, n, decompressed);
            case F32 -> new MaterializedFloatArray(dt, n, decompressed);
            case I16, U16 -> new MaterializedShortArray(dt, n, decompressed);
            case I8, U8 -> new MaterializedByteArray(dt, n, decompressed);
            case F16 -> new MaterializedFloat16Array(dt, n, decompressed);
        };
    }

    private static VarBinArray buildVarBin(DType dtype, long n, MemorySegment decompressed, DecodeContext ctx) {
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

        return new VarBinArray.OffsetMode(dtype, n, values, offsets, PType.I32);
    }
}
