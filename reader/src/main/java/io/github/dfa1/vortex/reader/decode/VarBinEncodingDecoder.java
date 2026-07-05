package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoVarBinMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Read-only decoder for `vortex.varbin`.
public final class VarBinEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_VARBIN;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment rawMeta = ctx.metadata();
        if (rawMeta == null) {
            throw new VortexException(EncodingId.VORTEX_VARBIN, "missing metadata");
        }
        ProtoVarBinMetadata meta;
        try {
            MemorySegment metaSeg = rawMeta;
            meta = ProtoVarBinMetadata.decode(metaSeg, 0, metaSeg.byteSize());
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
            MemorySegment materialized = ctx.arena().allocate((n + 1) * offBytes, offBytes);
            SegmentBroadcast.broadcastCopy(offsets, materialized, n + 1, offBytes);
            offsets = materialized;
        }

        MemorySegment bytes = ctx.buffer(0);

        return new VarBinArray.OffsetMode(ctx.dtype(), n, bytes, offsets, offsetsPtype);
    }
}
