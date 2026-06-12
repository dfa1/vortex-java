package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.SegmentBroadcast;
import io.github.dfa1.vortex.proto.VarBinMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/// Read-only decoder for {@code vortex.varbin}.
public final class VarBinEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public VarBinEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_VARBIN;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Utf8 || dtype instanceof DType.Binary;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        if (rawMeta == null) {
            throw new VortexException(EncodingId.VORTEX_VARBIN, "missing metadata");
        }
        VarBinMetadata meta;
        try {
            MemorySegment metaSeg = MemorySegment.ofBuffer(rawMeta.duplicate());
            meta = VarBinMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_VARBIN, "invalid metadata", e);
        }

        PType offsetsPtype = PType.fromOrdinal(meta.offsets_ptype().value());
        DType offsetsDtype = new DType.Primitive(offsetsPtype, false);
        long n = ctx.rowCount();

        MemorySegment offsets = ctx.decodeChildSegment(0, offsetsDtype, n + 1);

        int offBytes = offsetsPtype.byteSize();
        long offCap = SegmentBroadcast.capacity(offsets, offBytes);
        if (offCap < n + 1) {
            MemorySegment materialized = ctx.arena().allocate((n + 1) * (long) offBytes, offBytes);
            SegmentBroadcast.broadcastCopy(offsets, materialized, n + 1, offBytes);
            offsets = materialized;
        }

        MemorySegment bytes = ctx.buffer(0);

        return new VarBinArray(ctx.dtype(), n, bytes, offsets, offsetsPtype);
    }
}
