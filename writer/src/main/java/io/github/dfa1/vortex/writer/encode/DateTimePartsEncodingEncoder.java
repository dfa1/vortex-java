package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimestampMetadata;
import io.github.dfa1.vortex.core.proto.ProtoDateTimePartsMetadata;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;

import java.lang.foreign.MemorySegment;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.datetimeparts`.
public final class DateTimePartsEncodingEncoder implements EncodingEncoder {

    /// This encoding, barred from its own children: a part array is days/seconds/subseconds, never
    /// another timestamp to split again.
    private static final Set<EncodingId> SELF = Set.of(EncodingId.VORTEX_DATETIMEPARTS);

    private static final long SECONDS_PER_DAY = 86_400L;

    /// Creates the encoder.
    public DateTimePartsEncodingEncoder() {
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
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        DType.Extension ext = (DType.Extension) dtype;
        Input in = asParts(dtype, data);
        if (in == null) {
            throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS,
                    "expected non-Days vortex.timestamp storage with a valid row, got "
                            + (data == null ? "null" : data.getClass().getSimpleName()));
        }
        Parts parts = split(in.timestamps(), unitOf(ext));
        Part days = Part.narrowed(parts.days(), in.validity());
        Part seconds = Part.narrowed(parts.seconds(), null);
        Part subseconds = Part.narrowed(parts.subseconds(), null);

        EncodingEncoder primEnc = ctx.lookupEncoder(EncodingId.VORTEX_PRIMITIVE);
        EncodeResult daysResult = in.validity() == null
                ? primEnc.encode(days.dtype(in.nullable()), days.values(), ctx)
                : new MaskedEncodingEncoder().encode(days.dtype(true), in.days(days.values()), ctx);
        EncodeResult secondsResult = primEnc.encode(seconds.dtype(false), seconds.values(), ctx);
        EncodeResult subsecondsResult = primEnc.encode(subseconds.dtype(false), subseconds.values(), ctx);

        List<EncodedBuffer> allBuffers = new ArrayList<>();
        allBuffers.addAll(daysResult.encodedBuffers());
        allBuffers.addAll(secondsResult.encodedBuffers());
        allBuffers.addAll(subsecondsResult.encodedBuffers());

        int off1 = daysResult.buffers().size();
        int off2 = off1 + secondsResult.buffers().size();

        EncodeNode daysNode = EncodeNode.remapBufferIndices(daysResult.rootNode(), 0);
        EncodeNode secondsNode = EncodeNode.remapBufferIndices(secondsResult.rootNode(), off1);
        EncodeNode subsecondsNode = EncodeNode.remapBufferIndices(subsecondsResult.rootNode(), off2);

        byte[] metaBytes = metadata(days, seconds, subseconds);

        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_DATETIMEPARTS,
                MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{daysNode, secondsNode, subsecondsNode},
                new int[]{});
        // Bounds come from the undivided timestamps, in storage space: the split children are
        // days/seconds/subseconds and none of them orders the column. Without this, choosing
        // datetimeparts over vortex.ext would trade the column's zone map for its size.
        MinMax stats = in.stats();
        return new EncodeResult(root, List.copyOf(allBuffers),
                PrimitiveEncodingEncoder.minOf(stats), PrimitiveEncodingEncoder.maxOf(stats));
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext encodeCtx) {
        Input in = asParts(dtype, data);
        if (in == null) {
            return CascadeStep.notApplicable();
        }
        DType.Extension ext = (DType.Extension) dtype;
        Parts parts = split(in.timestamps(), unitOf(ext));
        Part days = Part.narrowed(parts.days(), in.validity());
        Part seconds = Part.narrowed(parts.seconds(), null);
        Part subseconds = Part.narrowed(parts.subseconds(), null);

        EncodeNode partialRoot = new EncodeNode(
                EncodingId.VORTEX_DATETIMEPARTS,
                MemorySegment.ofArray(metadata(days, seconds, subseconds)),
                new EncodeNode[3],
                new int[0]);

        List<ChildSlot> children = List.of(
                new ChildSlot(days.dtype(in.nullable()), in.days(days.values()), 0, SELF),
                new ChildSlot(seconds.dtype(false), seconds.values(), 1, SELF),
                new ChildSlot(subseconds.dtype(false), subseconds.values(), 2, SELF));

        MinMax stats = in.stats();
        return new CascadeStep(partialRoot, List.of(), children,
                PrimitiveEncodingEncoder.minOf(stats), PrimitiveEncodingEncoder.maxOf(stats), true);
    }

    /// The timestamps this encoder should split, or `null` when it does not apply.
    ///
    /// Accepts two shapes. Pre-decomposed [DateTimePartsData] comes from the Parquet importer.
    /// Raw `long[]` storage is what every other write path produces - `TimestampExtensionEncoder`
    /// packs a `Collection&lt;Instant&gt;` into one - and before this was accepted the encoder
    /// declined every such column, so a timestamp written through the normal API always fell
    /// through to `vortex.ext` over a bitpacked i64 and never competed as datetimeparts at all.
    ///
    /// Restricted to `vortex.timestamp`: a date is already whole days, a time-of-day has no day
    /// component worth splitting out, and [TimeUnit#Days] cannot be subdivided.
    ///
    /// A nullable column with nulls arrives as [NullableData] over `long[]` storage. Its validity
    /// goes on the days child, as Rust's `split_temporal` keeps it there (seconds and subseconds
    /// stay non-nullable); an all-valid validity is dropped. An all-null chunk is declined: Rust
    /// checks for all-null before the extension's schemes, so the cascade writes it as the null
    /// constant through `vortex.ext`. The days child takes the column's nullability, as Rust's
    /// does, whichever shape the storage arrives in.
    ///
    /// @param dtype the column dtype
    /// @param data  the values being encoded
    /// @return the timestamps to split, or `null` when this encoder does not apply
    private static Input asParts(DType dtype, Object data) {
        if (data instanceof DateTimePartsData parts) {
            return new Input(parts.timestamps(), dtype.nullable(), null);
        }
        if (!(dtype instanceof DType.Extension ext)
                || ext.extensionId() != ExtensionId.VORTEX_TIMESTAMP
                || !(ext.metadata() instanceof TimestampMetadata(TimeUnit unit, var _))
                || unit == TimeUnit.Days) {
            return null;
        }
        return switch (data) {
            case long[] storage -> new Input(storage, ext.nullable(), null);
            case NullableData(long[] storage, boolean[] validity) -> nullableInput(storage, validity);
            default -> null;
        };
    }

    /// An all-valid validity is dropped, as Rust's `AllValid` writes no bitmap; an all-null one
    /// declines the chunk.
    private static Input nullableInput(long[] storage, boolean[] validity) {
        int valid = 0;
        for (boolean v : validity) {
            if (v) {
                valid++;
            }
        }
        if (valid == 0) {
            return null;
        }
        return new Input(storage, true, valid == validity.length ? null : validity);
    }

    private static TimeUnit unitOf(DType.Extension ext) {
        if (!(ext.metadata() instanceof TimestampMetadata(TimeUnit unit, var _))) {
            throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS,
                    "extension metadata is not timestamp metadata: " + ext.metadata());
        }
        return unit;
    }

    /// Splits epoch ticks into whole days, seconds within the day, and sub-second ticks, the three
    /// arrays the `vortex.datetimeparts` wire format stores. Each part compresses far better on its
    /// own than the interleaved timestamp does: days is a short dense run, seconds spans a fixed
    /// 0..86399, and subseconds is frequently constant.
    ///
    /// @param timestamps epoch ticks in `unit`
    /// @param unit       the resolution `timestamps` is expressed in
    /// @return the three part arrays
    private static Parts split(long[] timestamps, TimeUnit unit) {
        long divisor = unit.divisor();
        long ticksPerDay = SECONDS_PER_DAY * divisor;
        int n = timestamps.length;
        long[] days = new long[n];
        long[] seconds = new long[n];
        long[] subseconds = new long[n];
        for (int i = 0; i < n; i++) {
            long ts = timestamps[i];
            long dval = ts / ticksPerDay;
            long rem = ts % ticksPerDay;
            // Java truncates division toward zero, so a pre-epoch timestamp lands one day late
            // with a positive remainder; carry it back so days stays monotonic with the value.
            if (rem < 0) {
                rem += ticksPerDay;
                dval--;
            }
            days[i] = dval;
            seconds[i] = rem / divisor;
            subseconds[i] = rem % divisor;
        }
        return new Parts(days, seconds, subseconds);
    }

    /// The timestamps to split, whether the column is nullable, and the validity when the chunk
    /// holds nulls (`null` when every row is valid).
    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record Input(long[] timestamps, boolean nullable, boolean[] validity) {

        /// The days child: the plain part, or the part under this chunk's validity.
        Object days(Object days) {
            return validity == null ? days : new NullableData(days, validity);
        }

        /// Bounds over the valid rows only: an invalid slot's placeholder is not a value.
        MinMax stats() {
            Object values = validity == null ? timestamps : PrimitiveArrays.compact(PType.I64, timestamps, validity);
            return PrimitiveEncodingEncoder.minMaxStats(PType.I64, values);
        }
    }

    /// One timestamp column split into its three `vortex.datetimeparts` children.
    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record Parts(long[] days, long[] seconds, long[] subseconds) {
    }

    private static byte[] metadata(Part days, Part seconds, Part subseconds) {
        return new ProtoDateTimePartsMetadata(days.protoPtype(), seconds.protoPtype(), subseconds.protoPtype())
                .encode();
    }

    /// One part narrowed as Rust's `compress_temporal` narrows each part before compressing it
    /// (`PrimitiveArray::narrow`). The width decides the cascade: 64-bit days scored FoR at 64/6
    /// bits and beat run-length encoding that wins on 16-bit days (#634).
    ///
    /// @param ptype  the narrowed type
    /// @param values the part in `ptype`'s carrier array
    private record Part(PType ptype, Object values) {

        static Part narrowed(long[] values, boolean[] validity) {
            PType ptype = narrowest(values, validity);
            return new Part(ptype, PrimitiveArrays.fromLongsArray(values, ptype, EncodingId.VORTEX_DATETIMEPARTS));
        }

        DType dtype(boolean nullable) {
            return new DType.Primitive(ptype, nullable);
        }

        io.github.dfa1.vortex.core.proto.ProtoPType protoPtype() {
            return io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(ptype.ordinal());
        }
    }

    /// Rust's `PrimitiveArray::narrow` over the valid rows; an empty part is `U8`, as Rust zeroes it.
    private static PType narrowest(long[] values, boolean[] validity) {
        if (values.length == 0) {
            return PType.U8;
        }
        Object valid = validity == null ? values : PrimitiveArrays.compact(PType.I64, values, validity);
        long[] minMax = SimdOperationsSupport.preferred().minMax(valid, PType.I64);
        return PType.narrowest(minMax[0], minMax[1]);
    }
}
