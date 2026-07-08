package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.proto.ProtoDateTimePartsMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.LazyDateTimePartsLongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

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

        // A row is null when ANY component is null (mirrors the Rust reference —
        // every part must be present to reassemble the epoch count). PR #225 made
        // nullable RunEnd children surface real nulls as MaskedArray, so unwrap each
        // component to its raw values, intersect their validities, and re-wrap the
        // reassembled array with the combined mask instead of throwing (#235).
        BoolArray validity = null;
        if (days instanceof MaskedArray masked) {
            validity = intersect(ctx, validity, masked.validity());
            days = masked.inner();
        }
        if (seconds instanceof MaskedArray masked) {
            validity = intersect(ctx, validity, masked.validity());
            seconds = masked.inner();
        }
        if (subseconds instanceof MaskedArray masked) {
            validity = intersect(ctx, validity, masked.validity());
            subseconds = masked.inner();
        }

        Array reassembled = new LazyDateTimePartsLongArray(ctx.dtype(), ctx.rowCount(),
                days, seconds, subseconds, unitsPerDay, unitsPerSecond);
        return validity != null ? new MaskedArray(reassembled, validity) : reassembled;
    }

    /// Combines a running validity bitmap with an incoming one via logical AND,
    /// so that a row stays valid only when every component is valid.
    ///
    /// A `null` incoming bitmap means the component is entirely valid and
    /// leaves `current` unchanged; a `null` `current` adopts `incoming` directly.
    /// Two non-null bitmaps are AND-ed into a fresh off-heap bitmap allocated
    /// from the decode arena.
    ///
    /// @param ctx      decode context supplying the output arena
    /// @param current  running combined validity, or `null` if none yet
    /// @param incoming a component's validity bitmap, or `null` if all-valid
    /// @return the combined validity bitmap, or `null` when both inputs are all-valid
    private static BoolArray intersect(DecodeContext ctx, BoolArray current, BoolArray incoming) {
        if (incoming == null) {
            return current;
        }
        if (current == null) {
            return incoming;
        }
        long n = current.length();
        MemorySegment bits = ctx.arena().allocate((n + 7) / 8);
        for (long i = 0; i < n; i++) {
            if (current.getBoolean(i) && incoming.getBoolean(i)) {
                long byteIndex = i >>> 3;
                byte b = bits.get(ValueLayout.JAVA_BYTE, byteIndex);
                bits.set(ValueLayout.JAVA_BYTE, byteIndex, (byte) ((b & 0xff) | (1 << (i & 7))));
            }
        }
        return new MaterializedBoolArray(DType.BOOL, n, bits.asReadOnly());
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
