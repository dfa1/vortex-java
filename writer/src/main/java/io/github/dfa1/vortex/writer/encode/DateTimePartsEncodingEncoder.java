package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.proto.ProtoDateTimePartsMetadata;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.datetimeparts`.
public final class DateTimePartsEncodingEncoder implements EncodingEncoder {

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
        DateTimePartsData d = (DateTimePartsData) data;

        MemorySegment extMeta = ext.metadata();
        if (extMeta == null || extMeta.byteSize() < 3) {
            throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS,
                    "extension metadata missing or too short");
        }
        byte[] extBytes = extMeta.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
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

        DType daysDtype = d.nullable() ? DType.I64.asNullable() : DType.I64;

        EncodingEncoder primEnc = ctx.lookupEncoder(EncodingId.VORTEX_PRIMITIVE);
        EncodeResult daysResult = primEnc.encode(daysDtype, days, ctx);
        EncodeResult secondsResult = primEnc.encode(DType.I64, seconds, ctx);
        EncodeResult subsecondsResult = primEnc.encode(DType.I64, subseconds, ctx);

        List<MemorySegment> allBuffers = new ArrayList<>();
        allBuffers.addAll(daysResult.buffers());
        allBuffers.addAll(secondsResult.buffers());
        allBuffers.addAll(subsecondsResult.buffers());

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
        return new EncodeResult(root, List.copyOf(allBuffers), null, null);
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext encodeCtx) {
        if (!(data instanceof DateTimePartsData d)) {
            return CascadeStep.notApplicable();
        }
        DType.Extension ext = (DType.Extension) dtype;
        MemorySegment extMeta = ext.metadata();
        byte[] extBytes = extMeta.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
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

        byte[] metaBytes = new ProtoDateTimePartsMetadata(I64_PROTO, I64_PROTO, I64_PROTO).encode();

        EncodeNode partialRoot = new EncodeNode(
                EncodingId.VORTEX_DATETIMEPARTS,
                MemorySegment.ofArray(metaBytes),
                new EncodeNode[3],
                new int[0]);

        DType daysDtype = d.nullable() ? DType.I64.asNullable() : DType.I64;
        List<ChildSlot> children = List.of(
                new ChildSlot(daysDtype, days, 0),
                new ChildSlot(DType.I64, seconds, 1),
                new ChildSlot(DType.I64, subseconds, 2));

        return new CascadeStep(partialRoot, List.of(), children, null, null, true);
    }
}
