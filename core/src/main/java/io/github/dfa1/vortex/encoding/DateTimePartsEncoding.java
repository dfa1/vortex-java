package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.GenericArray;
import io.github.dfa1.vortex.proto.DTypeProtos;
import io.github.dfa1.vortex.proto.EncodingProtos;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Encoder/decoder for {@code vortex.datetimeparts} — timestamp split into days/seconds/subseconds.
///
/// <p>Wire format (per Rust vtable):
/// <ul>
///   <li>Metadata: {@code DateTimePartsMetadata} — three PType fields (tag 1/2/3):
///       {@code days_ptype}, {@code seconds_ptype}, {@code subseconds_ptype}
///   <li>Buffers: 0
///   <li>Children: 3
///       <ul>
///         <li>Slot 0 — {@code days}: {@code Primitive(days_ptype, parentNullability)}
///         <li>Slot 1 — {@code seconds}: {@code Primitive(seconds_ptype, false)}
///         <li>Slot 2 — {@code subseconds}: {@code Primitive(subseconds_ptype, false)}
///       </ul>
/// </ul>
///
/// <p>Extension metadata for {@code vortex.timestamp} dtype (hand-rolled, not protobuf):
/// {@code byte[0]=TimeUnit tag, bytes[1-2]=tz_len (u16 LE), bytes[3+]=tz UTF-8}
public final class DateTimePartsEncoding implements Encoding {

    /// Creates a new {@code DateTimePartsEncoding} instance.
    public DateTimePartsEncoding() {
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
    public EncodeResult encode(DType dtype, Object data) {
        return Encoder.encode((DType.Extension) dtype, (DateTimePartsData) data);
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, CompressorContext ctx) {
        return Encoder.encodeCascade((DType.Extension) dtype, (DateTimePartsData) data);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Encoder {

        private static final long SECONDS_PER_DAY = 86_400L;
        private static final DType I64 = new DType.Primitive(PType.I64, false);
        private static final DType I64_NULLABLE = new DType.Primitive(PType.I64, true);
        private static final DTypeProtos.PType I64_PROTO =
                DTypeProtos.PType.forNumber(PType.I64.ordinal());

        static EncodeResult encode(DType.Extension dtype, DateTimePartsData data) {
            ByteBuffer extMeta = dtype.metadata();
            if (extMeta == null || extMeta.remaining() < 3) {
                throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS,
                        "extension metadata missing or too short");
            }
            byte[] extBytes = new byte[extMeta.remaining()];
            extMeta.duplicate().get(extBytes);
            TimeUnit unit = TimeUnit.fromTag(extBytes[0]);

            long divisor = unit.divisor();
            long ticksPerDay = SECONDS_PER_DAY * divisor;
            int n = data.timestamps().length;

            long[] days = new long[n];
            long[] seconds = new long[n];
            long[] subseconds = new long[n];

            for (int i = 0; i < n; i++) {
                long ts = data.timestamps()[i];
                long d = ts / ticksPerDay;
                long rem = ts % ticksPerDay;
                if (rem < 0) {
                    rem += ticksPerDay;
                    d--;
                }
                days[i] = d;
                seconds[i] = rem / divisor;
                subseconds[i] = rem % divisor;
            }

            PrimitiveEncoding primEnc = new PrimitiveEncoding();
            DType daysDtype = data.nullable() ? I64_NULLABLE : I64;

            EncodeResult daysResult = primEnc.encode(daysDtype, days);
            EncodeResult secondsResult = primEnc.encode(I64, seconds);
            EncodeResult subsecondsResult = primEnc.encode(I64, subseconds);

            List<MemorySegment> allBuffers = new ArrayList<>();
            allBuffers.addAll(daysResult.buffers());
            allBuffers.addAll(secondsResult.buffers());
            allBuffers.addAll(subsecondsResult.buffers());

            int off1 = daysResult.buffers().size();
            int off2 = off1 + secondsResult.buffers().size();

            EncodeNode daysNode = EncodeNode.remapBufferIndices(daysResult.rootNode(), 0);
            EncodeNode secondsNode = EncodeNode.remapBufferIndices(secondsResult.rootNode(), off1);
            EncodeNode subsecondsNode = EncodeNode.remapBufferIndices(subsecondsResult.rootNode(), off2);

            byte[] metaBytes = EncodingProtos.DateTimePartsMetadata.newBuilder()
                    .setDaysPtype(I64_PROTO)
                    .setSecondsPtype(I64_PROTO)
                    .setSubsecondsPtype(I64_PROTO)
                    .build()
                    .toByteArray();

            EncodeNode root = new EncodeNode(
                    EncodingId.VORTEX_DATETIMEPARTS,
                    ByteBuffer.wrap(metaBytes),
                    new EncodeNode[]{daysNode, secondsNode, subsecondsNode},
                    new int[]{});
            return new EncodeResult(root, List.copyOf(allBuffers), null, null);
        }

        static CascadeStep encodeCascade(DType.Extension dtype, DateTimePartsData data) {
            ByteBuffer extMeta = dtype.metadata();
            byte[] extBytes = new byte[extMeta.remaining()];
            extMeta.duplicate().get(extBytes);
            TimeUnit unit = TimeUnit.fromTag(extBytes[0]);

            long divisor = unit.divisor();
            long ticksPerDay = SECONDS_PER_DAY * divisor;
            int n = data.timestamps().length;

            long[] days = new long[n];
            long[] seconds = new long[n];
            long[] subseconds = new long[n];

            for (int i = 0; i < n; i++) {
                long ts = data.timestamps()[i];
                long d = ts / ticksPerDay;
                long rem = ts % ticksPerDay;
                if (rem < 0) {
                    rem += ticksPerDay;
                    d--;
                }
                days[i] = d;
                seconds[i] = rem / divisor;
                subseconds[i] = rem % divisor;
            }

            byte[] metaBytes = EncodingProtos.DateTimePartsMetadata.newBuilder()
                    .setDaysPtype(I64_PROTO).setSecondsPtype(I64_PROTO).setSubsecondsPtype(I64_PROTO)
                    .build().toByteArray();

            // 3 null slots filled by the cascading compressor (days, seconds, subseconds)
            EncodeNode partialRoot = new EncodeNode(
                    EncodingId.VORTEX_DATETIMEPARTS,
                    ByteBuffer.wrap(metaBytes),
                    new EncodeNode[3],
                    new int[0]);

            DType daysDtype = data.nullable() ? I64_NULLABLE : I64;
            List<ChildSlot> children = List.of(
                    new ChildSlot(daysDtype, days, 0),
                    new ChildSlot(I64, seconds, 1),
                    new ChildSlot(I64, subseconds, 2));

            return new CascadeStep(partialRoot, List.of(), children, null, null, true);
        }
    }

    private static final class Decoder {

        private static Array decode(DecodeContext ctx) {
            ByteBuffer meta = ctx.metadata();
            if (meta == null || meta.remaining() == 0) {
                throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS, "missing metadata");
            }
            EncodingProtos.DateTimePartsMetadata decoded;
            try {
                byte[] bytes = new byte[meta.remaining()];
                meta.duplicate().get(bytes);
                decoded = EncodingProtos.DateTimePartsMetadata.parseFrom(bytes);
            } catch (InvalidProtocolBufferException e) {
                throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS, "invalid metadata: " + e.getMessage());
            }

            PType daysPtype = PType.values()[decoded.getDaysPtypeValue()];
            PType secondsPtype = PType.values()[decoded.getSecondsPtypeValue()];
            PType subsecondsPtype = PType.values()[decoded.getSubsecondsPtypeValue()];
            boolean nullable = ctx.dtype().nullable();

            Array days = decodeChild(ctx, 0, new DType.Primitive(daysPtype, nullable));
            Array seconds = decodeChild(ctx, 1, new DType.Primitive(secondsPtype, false));
            Array subseconds = decodeChild(ctx, 2, new DType.Primitive(subsecondsPtype, false));

            return new GenericArray(ctx.dtype(), ctx.rowCount(), new MemorySegment[0],
                    new Array[]{days, seconds, subseconds});
        }

        private static Array decodeChild(DecodeContext ctx, int idx, DType childDtype) {
            ArrayNode childNode = ctx.node().children()[idx];
            DecodeContext childCtx = new DecodeContext(
                    childNode, childDtype, ctx.rowCount(),
                    ctx.segmentBuffers(), ctx.registry(), ctx.arena());
            return ctx.registry().decode(childCtx);
        }
    }
}
