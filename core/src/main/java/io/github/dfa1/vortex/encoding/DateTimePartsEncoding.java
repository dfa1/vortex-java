package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.GenericArray;
import io.github.dfa1.vortex.proto.DateTimePartsMetadata;

import java.io.IOException;

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
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        return Encoder.encode((DType.Extension) dtype, (DateTimePartsData) data, ctx);
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext encodeCtx) {
        // accepts(dtype) is true for any Extension; only DateTimePartsData carries the
        // pre-decomposed days/seconds/subseconds shape this encoding consumes. Bail out
        // for raw primitive storage (e.g. JDBC long[] via TimestampExtension.encodeAll)
        // so the compressor can excludeAndRetry, picking ExtEncoding instead.
        if (!(data instanceof DateTimePartsData)) {
            return CascadeStep.notApplicable();
        }
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
        private static final io.github.dfa1.vortex.proto.PType I64_PROTO =
                io.github.dfa1.vortex.proto.PType.fromValue(PType.I64.ordinal());

        static EncodeResult encode(DType.Extension dtype, DateTimePartsData data, EncodeContext ctx) {
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

            DType daysDtype = data.nullable() ? I64_NULLABLE : I64;

            Encoding primEnc = ctx.lookupEncoding(EncodingId.VORTEX_PRIMITIVE);
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

            byte[] metaBytes = new DateTimePartsMetadata(I64_PROTO, I64_PROTO, I64_PROTO).encode();

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
}
