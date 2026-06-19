package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.TimeUnit;
import io.github.dfa1.vortex.proto.DateTimePartsMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LazyDateTimePartsLongArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/// Read-only decoder for `vortex.datetimeparts`.
///
/// Reassembles the three children (days, seconds, subseconds) into a
/// [LazyDateTimePartsLongArray] of epoch counts in the extension's
/// {@link TimeUnit}. No per-row materialisation happens at decode time —
/// the downstream extension decoder reads the reassembled long via the
/// lazy `getLong` accessor.
public final class DateTimePartsEncodingDecoder implements EncodingDecoder {

    private static final long SECONDS_PER_DAY = 86_400L;

    /// Public no-arg constructor required by [java.util.ServiceLoader].
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

        if (!(ctx.dtype() instanceof DType.Extension ext)) {
            throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS,
                    "expected Extension dtype, got " + ctx.dtype());
        }
        long unitsPerSecond = readUnitsPerSecond(ext);
        long unitsPerDay = SECONDS_PER_DAY * unitsPerSecond;

        return new LazyDateTimePartsLongArray(ctx.dtype(), ctx.rowCount(),
                days, seconds, subseconds, unitsPerDay, unitsPerSecond);
    }

    /// Returns `TimeUnit.divisor()` for the extension's declared time unit, or
    /// `1` when the unit is [TimeUnit#Days] (days carry no sub-second
    /// component; seconds and subseconds children are expected to be zero).
    private static long readUnitsPerSecond(DType.Extension ext) {
        ByteBuffer extMeta = ext.metadata();
        if (extMeta == null || !extMeta.hasRemaining()) {
            throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS,
                    "extension " + ext.extensionId() + " missing TimeUnit metadata byte");
        }
        TimeUnit unit = TimeUnit.fromTag(extMeta.get(extMeta.position()));
        return unit == TimeUnit.Days ? 1L : unit.divisor();
    }
}
