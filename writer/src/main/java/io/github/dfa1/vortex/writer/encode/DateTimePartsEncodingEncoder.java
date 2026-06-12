package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.TimeUnit;
import io.github.dfa1.vortex.proto.DateTimePartsMetadata;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for {@code vortex.datetimeparts}.
public final class DateTimePartsEncodingEncoder implements EncodingEncoder {

    private static final long SECONDS_PER_DAY = 86_400L;
    private static final DType I64 = new DType.Primitive(PType.I64, false);
    private static final DType I64_NULLABLE = new DType.Primitive(PType.I64, true);
    private static final io.github.dfa1.vortex.proto.PType I64_PROTO =
            io.github.dfa1.vortex.proto.PType.fromValue(PType.I64.ordinal());

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
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
        DateTimePartsData d = (DateTimePartsData) data;

        ByteBuffer extMeta = ext.metadata();
        if (extMeta == null || extMeta.remaining() < 3) {
            throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS,
                    "extension metadata missing or too short");
        }
        byte[] extBytes = new byte[extMeta.remaining()];
        extMeta.duplicate().get(extBytes);
        TimeUnit unit = TimeUnit.fromTag(extBytes[0]);

        long divisor = unit.divisor();
        long ticksPerDay = SECONDS_PER_DAY * divisor;
        int n = d.timestamps().length;

        long[] days = new long[n];
        long[] seconds = new long[n];
        long[] subseconds = new long[n];

        for (int i = 0; i < n; i++) {
            long ts = d.timestamps()[i];
            long dval = ts / ticksPerDay;
            long rem = ts % ticksPerDay;
            if (rem < 0) {
                rem += ticksPerDay;
                dval--;
            }
            days[i] = dval;
            seconds[i] = rem / divisor;
            subseconds[i] = rem % divisor;
        }

        DType daysDtype = d.nullable() ? I64_NULLABLE : I64;

        EncodingEncoder primEnc = ctx.lookupEncoder(EncodingId.VORTEX_PRIMITIVE);
        EncodeResult daysResult = primEnc.encode(daysDtype, days, ctx);
        EncodeResult secondsResult = primEnc.encode(I64, seconds, ctx);
        EncodeResult subsecondsResult = primEnc.encode(I64, subseconds, ctx);

        List<MemorySegment> allBuffers = new ArrayList<>();
        allBuffers.addAll(daysResult.buffers());
        allBuffers.addAll(secondsResult.buffers());
        allBuffers.addAll(subsecondsResult.buffers());

        int off1 = daysResult.buffers().size();
        int off2 = off1 + secondsResult.buffers().size();

        EncodeNode daysNode = EncodeNode.remapBufferIndices(daysResult.rootNode(), 0);
        EncodeNode secondsNode = EncodeNode.remapBufferIndices(secondsResult.rootNode(), off1);
        EncodeNode subsecondsNode = EncodeNode.remapBufferIndices(subsecondsResult.rootNode(), off2);

        byte[] metaBytes = new DateTimePartsMetadata(I64_PROTO, I64_PROTO, I64_PROTO).encode();

        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_DATETIMEPARTS,
                ByteBuffer.wrap(metaBytes),
                new EncodeNode[]{daysNode, secondsNode, subsecondsNode},
                new int[]{});
        return new EncodeResult(root, List.copyOf(allBuffers), null, null);
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext encodeCtx) {
        if (!(data instanceof DateTimePartsData d)) {
            return CascadeStep.notApplicable();
        }
        DType.Extension ext = (DType.Extension) dtype;
        ByteBuffer extMeta = ext.metadata();
        byte[] extBytes = new byte[extMeta.remaining()];
        extMeta.duplicate().get(extBytes);
        TimeUnit unit = TimeUnit.fromTag(extBytes[0]);

        long divisor = unit.divisor();
        long ticksPerDay = SECONDS_PER_DAY * divisor;
        int n = d.timestamps().length;

        long[] days = new long[n];
        long[] seconds = new long[n];
        long[] subseconds = new long[n];

        for (int i = 0; i < n; i++) {
            long ts = d.timestamps()[i];
            long dval = ts / ticksPerDay;
            long rem = ts % ticksPerDay;
            if (rem < 0) {
                rem += ticksPerDay;
                dval--;
            }
            days[i] = dval;
            seconds[i] = rem / divisor;
            subseconds[i] = rem % divisor;
        }

        byte[] metaBytes = new DateTimePartsMetadata(I64_PROTO, I64_PROTO, I64_PROTO).encode();

        EncodeNode partialRoot = new EncodeNode(
                EncodingId.VORTEX_DATETIMEPARTS,
                ByteBuffer.wrap(metaBytes),
                new EncodeNode[3],
                new int[0]);

        DType daysDtype = d.nullable() ? I64_NULLABLE : I64;
        List<ChildSlot> children = List.of(
                new ChildSlot(daysDtype, days, 0),
                new ChildSlot(I64, seconds, 1),
                new ChildSlot(I64, subseconds, 2));

        return new CascadeStep(partialRoot, List.of(), children, null, null, true);
    }
}
