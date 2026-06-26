package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.proto.ProtoZstdMetadata;
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

import io.github.dfa1.zstd.ZstdDecompressCtx;
import io.github.dfa1.zstd.ZstdDecompressDict;
import io.github.dfa1.zstd.ZstdDictionary;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for `vortex.zstd`.
public final class ZstdEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public ZstdEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ZSTD;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment rawMeta = ctx.metadata();
        if (rawMeta == null) {
            throw new VortexException(EncodingId.VORTEX_ZSTD, "missing metadata");
        }
        ProtoZstdMetadata meta;
        try {
            MemorySegment metaSeg = rawMeta;
            meta = ProtoZstdMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_ZSTD, "invalid metadata", e);
        }
        BoolArray validity = null;
        if (ctx.node().children().length > 0) {
            Array validityArray = ctx.decodeChild(0, DType.BOOL, ctx.rowCount());
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
            ProtoZstdMetadata meta,
            int frameCount,
            long totalUncompressed
    ) {
        // Zero-copy: decompress each native frame straight into its slice of the arena output,
        // no heap byte[] bounce. The mmap'd file buffers are already native; the scratch arena
        // only services the heap segments unit tests hand in.
        //
        // Buffer layout mirrors the Rust reference: with a shared dictionary, buffer[0] is the
        // dictionary and the frames follow at buffer[1..]; without one, the frames start at
        // buffer[0]. The frames count is metadata-driven either way.
        boolean hasDictionary = meta.dictionary_size() != 0;
        int frameBufferBase = hasDictionary ? 1 : 0;
        MemorySegment out = ctx.arena().allocate(totalUncompressed);
        try (ZstdDecompressCtx dctx = new ZstdDecompressCtx();
             Arena scratch = Arena.ofConfined()) {
            ZstdDecompressDict dictionary = hasDictionary ? digestDictionary(ctx.buffer(0)) : null;
            try {
                long outOffset = 0;
                for (int i = 0; i < frameCount; i++) {
                    MemorySegment src = asNative(ctx.buffer(frameBufferBase + i), scratch);
                    int uncompSize = (int) meta.frames().get(i).uncompressed_size();
                    MemorySegment dst = out.asSlice(outOffset, uncompSize);
                    long written = dictionary == null
                            ? dctx.decompress(dst, src)
                            : dctx.decompress(dst, src, dictionary);
                    if (written != uncompSize) {
                        throw new VortexException(EncodingId.VORTEX_ZSTD,
                                "frame " + i + ": expected " + uncompSize + " bytes, got " + written);
                    }
                    outOffset += uncompSize;
                }
            } finally {
                if (dictionary != null) {
                    dictionary.close();
                }
            }
        }
        return out;
    }

    /// Digests the raw dictionary bytes carried in `dictBuffer` into a reusable native
    /// decompression dictionary shared by every frame in this segment.
    ///
    /// The one heap copy here is off the hot path: the dictionary is digested once per segment
    /// (not per frame or per row) over a small buffer, and `ZSTD_createDDict` re-copies into its
    /// own native allocation regardless. Switch to a `MemorySegment` overload once the zstd
    /// bindings expose one.
    private static ZstdDecompressDict digestDictionary(MemorySegment dictBuffer) {
        byte[] raw = dictBuffer.toArray(ValueLayout.JAVA_BYTE);
        return new ZstdDecompressDict(ZstdDictionary.of(raw));
    }

    /// Returns `seg` unchanged when it is already native (the production mmap path); otherwise
    /// copies it into `scratch` so the zero-copy native API can read it.
    private static MemorySegment asNative(MemorySegment seg, Arena scratch) {
        if (seg.isNative()) {
            return seg;
        }
        MemorySegment copy = scratch.allocate(Math.max(seg.byteSize(), 1));
        MemorySegment.copy(seg, 0, copy, 0, seg.byteSize());
        return copy.asSlice(0, seg.byteSize());
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
