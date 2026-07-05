package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.proto.ProtoDateTimePartsMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LazyDateTimePartsLongArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Read-only decoder for `vortex.datetimeparts`.
///
/// Reassembles the three children (days, seconds, subseconds) into a
/// [LazyDateTimePartsLongArray] of epoch counts in the extension's
/// [TimeUnit]. No per-row materialization happens at decode time —
/// the downstream extension decoder reads the reassembled long via the
/// lazy `getLong` accessor.
public final class DateTimePartsEncodingDecoder implements EncodingDecoder {

    private static final long SECONDS_PER_DAY = 86_400L;

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_DATETIMEPARTS;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment meta = ctx.metadata();
        if (meta == null || meta.byteSize() == 0) {
            throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS, "missing metadata");
        }
        ProtoDateTimePartsMetadata decoded;
        try {
            MemorySegment metaSeg = meta;
            decoded = ProtoDateTimePartsMetadata.decode(metaSeg, 0, metaSeg.byteSize());
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
        MemorySegment extMeta = ext.metadata();
        if (extMeta == null || extMeta.byteSize() == 0) {
            throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS,
                    "extension " + ext.extensionId() + " missing TimeUnit metadata byte");
        }
        TimeUnit unit = TimeUnit.fromTag(extMeta.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0));
        return unit == TimeUnit.Days ? 1L : unit.divisor();
    }
}
