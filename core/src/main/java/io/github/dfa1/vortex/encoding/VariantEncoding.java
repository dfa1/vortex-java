package io.github.dfa1.vortex.encoding;

import com.google.protobuf.CodedInputStream;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.VariantArray;
import io.github.dfa1.vortex.proto.DTypeProtos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Decoder for {@code vortex.variant}.
///
/// <p>Wire format:
/// <ul>
///   <li>Metadata: proto {@code VariantMetadataProto {optional DType shredded_dtype = 1;}}</li>
///   <li>Buffers: none.</li>
///   <li>Child 0: {@code core_storage} with the outer variant dtype.</li>
///   <li>Child 1 (optional): {@code shredded} with the decoded {@code shredded_dtype}.</li>
/// </ul>
public final class VariantEncoding implements Encoding {

    /// Creates a new {@code VariantEncoding} instance; use via {@link Registry}.
    public VariantEncoding() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_VARIANT;
    }

    @Override
    public boolean accepts(DType dtype) {
        return false;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        throw new VortexException(EncodingId.VORTEX_VARIANT, "encode not yet implemented");
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Decoder {

        static Array decode(DecodeContext ctx) {
            DType shreddedDtype = parseShreddedDtype(ctx.metadata());

            int numChildren = ctx.node().children().length;
            if (numChildren < 1 || numChildren > 2) {
                throw new VortexException(EncodingId.VORTEX_VARIANT,
                        "expected 1 or 2 children, got " + numChildren);
            }

            Array coreStorage = ctx.decodeChild(0, ctx.dtype(), ctx.rowCount());

            Array shredded = null;
            if (shreddedDtype != null && numChildren >= 2) {
                shredded = ctx.decodeChild(1, shreddedDtype, ctx.rowCount());
            }

            return new VariantArray(ctx.dtype(), ctx.rowCount(), coreStorage, shredded);
        }

        private static DType parseShreddedDtype(ByteBuffer rawMeta) {
            if (rawMeta == null || !rawMeta.hasRemaining()) {
                return null;
            }
            try {
                CodedInputStream cin = CodedInputStream.newInstance(rawMeta.duplicate());
                while (!cin.isAtEnd()) {
                    int tag = cin.readTag();
                    int fieldNumber = tag >>> 3;
                    if (fieldNumber == 1) {
                        byte[] dtypeBytes = cin.readByteArray();
                        DTypeProtos.DType proto = DTypeProtos.DType.parseFrom(dtypeBytes);
                        return dtypeFromProto(proto);
                    } else {
                        cin.skipField(tag);
                    }
                }
            } catch (IOException e) {
                throw new VortexException(EncodingId.VORTEX_VARIANT, "invalid metadata", e);
            }
            return null;
        }

        static DType dtypeFromProto(DTypeProtos.DType proto) {
            return switch (proto.getDtypeTypeCase()) {
                case NULL -> new DType.Null(true);
                case BOOL -> new DType.Bool(proto.getBool().getNullable());
                case PRIMITIVE -> new DType.Primitive(
                        PType.values()[proto.getPrimitive().getType().getNumber()],
                        proto.getPrimitive().getNullable());
                case DECIMAL -> new DType.Decimal(
                        (byte) proto.getDecimal().getPrecision(),
                        (byte) proto.getDecimal().getScale(),
                        proto.getDecimal().getNullable());
                case UTF8 -> new DType.Utf8(proto.getUtf8().getNullable());
                case BINARY -> new DType.Binary(proto.getBinary().getNullable());
                case STRUCT -> {
                    var s = proto.getStruct();
                    var names = new ArrayList<String>(s.getNamesCount());
                    var types = new ArrayList<DType>(s.getDtypesCount());
                    for (int i = 0; i < s.getNamesCount(); i++) {
                        names.add(s.getNames(i));
                    }
                    for (DTypeProtos.DType child : s.getDtypesList()) {
                        types.add(dtypeFromProto(child));
                    }
                    yield new DType.Struct(List.copyOf(names), List.copyOf(types), s.getNullable());
                }
                case LIST -> new DType.List(
                        dtypeFromProto(proto.getList().getElementType()),
                        proto.getList().getNullable());
                case FIXED_SIZE_LIST -> new DType.FixedSizeList(
                        dtypeFromProto(proto.getFixedSizeList().getElementType()),
                        proto.getFixedSizeList().getSize(),
                        proto.getFixedSizeList().getNullable());
                case EXTENSION -> new DType.Extension(
                        proto.getExtension().getId(),
                        dtypeFromProto(proto.getExtension().getStorageDtype()),
                        proto.getExtension().getMetadata().asReadOnlyByteBuffer(),
                        false);
                case VARIANT -> new DType.Variant(proto.getVariant().getNullable());
                default -> throw new VortexException("unsupported proto DType: " + proto.getDtypeTypeCase());
            };
        }
    }
}
