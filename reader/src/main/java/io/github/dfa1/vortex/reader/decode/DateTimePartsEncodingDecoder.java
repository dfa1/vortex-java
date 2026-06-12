package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.GenericArray;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.proto.DateTimePartsMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/// Read-only decoder for {@code vortex.datetimeparts}.
public final class DateTimePartsEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public DateTimePartsEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_DATETIMEPARTS;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Extension;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer meta = ctx.metadata();
        if (meta == null || meta.remaining() == 0) {
            throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS, "missing metadata");
        }
        DateTimePartsMetadata decoded;
        try {
            MemorySegment metaSeg = MemorySegment.ofBuffer(meta.duplicate());
            decoded = DateTimePartsMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS, "invalid metadata: " + e.getMessage());
        }

        PType daysPtype = PType.fromOrdinal(decoded.days_ptype().value());
        PType secondsPtype = PType.fromOrdinal(decoded.seconds_ptype().value());
        PType subsecondsPtype = PType.fromOrdinal(decoded.subseconds_ptype().value());
        boolean nullable = ctx.dtype().nullable();

        Array days = ctx.decodeChild(0, new DType.Primitive(daysPtype, nullable), ctx.rowCount());
        Array seconds = ctx.decodeChild(1, new DType.Primitive(secondsPtype, false), ctx.rowCount());
        Array subseconds = ctx.decodeChild(2, new DType.Primitive(subsecondsPtype, false), ctx.rowCount());

        return new GenericArray(ctx.dtype(), ctx.rowCount(), new MemorySegment[0],
                new Array[]{days, seconds, subseconds});
    }
}
