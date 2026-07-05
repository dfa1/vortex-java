package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.VariantArray;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoVariantMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/// Read-only decoder for `vortex.variant`.
public final class VariantEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_VARIANT;
    }

    @Override
    public Array decode(DecodeContext ctx) {
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

    private static DType parseShreddedDtype(MemorySegment rawMeta) {
        if (rawMeta == null || rawMeta.byteSize() == 0) {
            return null;
        }
        try {
            MemorySegment metaSeg = rawMeta;
            ProtoVariantMetadata meta = ProtoVariantMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            if (meta.shredded_dtype() == null) {
                return null;
            }
            return dtypeFromProto(meta.shredded_dtype());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_VARIANT, "invalid metadata", e);
        }
    }

    static DType dtypeFromProto(io.github.dfa1.vortex.core.proto.ProtoDType proto) {
        if (proto.null_() != null) {
            return new DType.Null(true);
        }
        if (proto.bool() != null) {
            return new DType.Bool(proto.bool().nullable());
        }
        if (proto.primitive() != null) {
            return new DType.Primitive(
                    PType.values()[proto.primitive().type().value()],
                    proto.primitive().nullable());
        }
        if (proto.decimal() != null) {
            return new DType.Decimal(
                    (byte) proto.decimal().precision(),
                    (byte) proto.decimal().scale(),
                    proto.decimal().nullable());
        }
        if (proto.utf8() != null) {
            return new DType.Utf8(proto.utf8().nullable());
        }
        if (proto.binary() != null) {
            return new DType.Binary(proto.binary().nullable());
        }
        if (proto.struct() != null) {
            var s = proto.struct();
            var names = new ArrayList<String>(s.names().size());
            var types = new ArrayList<DType>(s.dtypes().size());
            names.addAll(s.names());
            for (io.github.dfa1.vortex.core.proto.ProtoDType child : s.dtypes()) {
                types.add(dtypeFromProto(child));
            }
            return new DType.Struct(List.copyOf(names), List.copyOf(types), s.nullable());
        }
        if (proto.list() != null) {
            return new DType.List(
                    dtypeFromProto(proto.list().element_type()),
                    proto.list().nullable());
        }
        if (proto.fixed_size_list() != null) {
            return new DType.FixedSizeList(
                    dtypeFromProto(proto.fixed_size_list().element_type()),
                    proto.fixed_size_list().size(),
                    proto.fixed_size_list().nullable());
        }
        if (proto.extension() != null) {
            return new DType.Extension(
                    proto.extension().id(),
                    dtypeFromProto(proto.extension().storage_dtype()),
                    MemorySegment.ofArray(proto.extension().metadata() != null ? proto.extension().metadata() : new byte[0]).asReadOnly(),
                    false);
        }
        if (proto.variant() != null) {
            return new DType.Variant(proto.variant().nullable());
        }
        throw new VortexException("unsupported proto DType");
    }
}
