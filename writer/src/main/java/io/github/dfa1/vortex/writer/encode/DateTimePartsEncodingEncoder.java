package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.proto.ProtoDateTimePartsMetadata;

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
    private static final io.github.dfa1.vortex.core.proto.ProtoPType I64_PROTO =
            io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I64.ordinal());

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
        DateTimePartsData d = asParts(dtype, data);
        if (d == null) {
            throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS,
                    "expected timestamp storage, got " + (data == null ? "null" : data.getClass().getSimpleName()));
        }
        MemorySegment extMeta = ext.metadata();
        if (extMeta == null || extMeta.byteSize() < 3) {
            throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS,
                    "extension metadata missing or too short");
        }
        Parts parts = split(d.timestamps(), unitOf(ext));
        long[] days = parts.days();
        long[] seconds = parts.seconds();
        long[] subseconds = parts.subseconds();

        DType daysDtype = d.nullable() ? DType.I64.asNullable() : DType.I64;

        EncodingEncoder primEnc = ctx.lookupEncoder(EncodingId.VORTEX_PRIMITIVE);
        EncodeResult daysResult = primEnc.encode(daysDtype, days, ctx);
        EncodeResult secondsResult = primEnc.encode(DType.I64, seconds, ctx);
        EncodeResult subsecondsResult = primEnc.encode(DType.I64, subseconds, ctx);

        List<EncodedBuffer> allBuffers = new ArrayList<>();
        allBuffers.addAll(daysResult.encodedBuffers());
        allBuffers.addAll(secondsResult.encodedBuffers());
        allBuffers.addAll(subsecondsResult.encodedBuffers());

        int off1 = daysResult.buffers().size();
        int off2 = off1 + secondsResult.buffers().size();

        EncodeNode daysNode = EncodeNode.remapBufferIndices(daysResult.rootNode(), 0);
        EncodeNode secondsNode = EncodeNode.remapBufferIndices(secondsResult.rootNode(), off1);
        EncodeNode subsecondsNode = EncodeNode.remapBufferIndices(subsecondsResult.rootNode(), off2);

        byte[] metaBytes = new ProtoDateTimePartsMetadata(I64_PROTO, I64_PROTO, I64_PROTO).encode();

        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_DATETIMEPARTS,
                MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{daysNode, secondsNode, subsecondsNode},
                new int[]{});
        // Bounds come from the undivided timestamps, in storage space: the split children are
        // days/seconds/subseconds and none of them orders the column. Without this, choosing
        // datetimeparts over vortex.ext would trade the column's zone map for its size.
        byte[][] stats = PrimitiveEncodingEncoder.minMaxStats(PType.I64, d.timestamps());
        return new EncodeResult(root, List.copyOf(allBuffers),
                PrimitiveEncodingEncoder.minOf(stats), PrimitiveEncodingEncoder.maxOf(stats));
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext encodeCtx) {
        DateTimePartsData d = asParts(dtype, data);
        if (d == null) {
            return CascadeStep.notApplicable();
        }
        DType.Extension ext = (DType.Extension) dtype;
        Parts parts = split(d.timestamps(), unitOf(ext));
        long[] days = parts.days();
        long[] seconds = parts.seconds();
        long[] subseconds = parts.subseconds();

        byte[] metaBytes = new ProtoDateTimePartsMetadata(I64_PROTO, I64_PROTO, I64_PROTO).encode();

        EncodeNode partialRoot = new EncodeNode(
                EncodingId.VORTEX_DATETIMEPARTS,
                MemorySegment.ofArray(metaBytes),
                new EncodeNode[3],
                new int[0]);

        DType daysDtype = d.nullable() ? DType.I64.asNullable() : DType.I64;
        List<ChildSlot> children = List.of(
                new ChildSlot(daysDtype, days, 0, SELF),
                new ChildSlot(DType.I64, seconds, 1, SELF),
                new ChildSlot(DType.I64, subseconds, 2, SELF));

        byte[][] stats = PrimitiveEncodingEncoder.minMaxStats(PType.I64, d.timestamps());
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
    /// A nullable column arrives as [NullableData] and is declined here, leaving it on the
    /// existing `vortex.ext` path rather than silently dropping its validity.
    ///
    /// @param dtype the column dtype
    /// @param data  the values being encoded
    /// @return the timestamps to split, or `null` when this encoder does not apply
    private static DateTimePartsData asParts(DType dtype, Object data) {
        if (data instanceof DateTimePartsData parts) {
            return parts;
        }
        if (!(data instanceof long[] storage) || !(dtype instanceof DType.Extension ext)
                || !ExtensionId.VORTEX_TIMESTAMP.id().equals(ext.extensionId())
                || ext.metadata() == null || ext.metadata().byteSize() < 3
                || TimeUnit.fromTag(ext.metadata().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)[0])
                        == TimeUnit.Days) {
            return null;
        }
        return new DateTimePartsData(storage, false);
    }

    private static TimeUnit unitOf(DType.Extension ext) {
        return TimeUnit.fromTag(ext.metadata().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)[0]);
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

    /// One timestamp column split into its three `vortex.datetimeparts` children.
    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record Parts(long[] days, long[] seconds, long[] subseconds) {
    }
}
