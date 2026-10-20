package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.VariantArray;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoVariantMetadata;
import io.github.dfa1.vortex.reader.ReadRegistry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/// Read-only decoder for `vortex.variant`.
public final class VariantEncodingDecoder implements EncodingDecoder {

    /// Creates the decoder.
    public VariantEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_VARIANT;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        DType shreddedDtype = parseShreddedDtype(ctx.metadata(), ctx.registry());

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

    private static DType parseShreddedDtype(MemorySegment rawMeta, ReadRegistry registry) {
        if (rawMeta == null || rawMeta.byteSize() == 0) {
            return null;
        }
        try {
            MemorySegment metaSeg = rawMeta;
            ProtoVariantMetadata meta = ProtoVariantMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            if (meta.shredded_dtype() == null) {
                return null;
            }
            return dtypeFromProto(meta.shredded_dtype(), registry);
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_VARIANT, "invalid metadata", e);
        }
    }

    /// Converts a dtype embedded in array metadata and checks its extension dtypes against
    /// `registry`, as Rust's `DType::from_proto` resolves each extension id on the session.
    ///
    /// @param proto    the embedded dtype
    /// @param registry the registry of the file being read
    /// @return the converted dtype
    /// @throws VortexException if an extension id is unregistered (without allow-unknown) or its
    ///         decoder rejects the dtype
    static DType dtypeFromProto(io.github.dfa1.vortex.core.proto.ProtoDType proto, ReadRegistry registry) {
        DType dtype = dtypeFromProto(proto);
        registry.validateExtensions(dtype);
        return dtype;
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
                    PType.fromOrdinal(proto.primitive().type().value()),
                    proto.primitive().nullable());
        }
        if (proto.decimal() != null) {
            return DType.Decimal.of(
                    proto.decimal().precision(),
                    proto.decimal().scale(),
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
            var names = new ArrayList<io.github.dfa1.vortex.core.model.ColumnName>(s.names().size());
            var types = new ArrayList<DType>(s.dtypes().size());
            for (String name : s.names()) {
                names.add(io.github.dfa1.vortex.core.model.ColumnName.of(name));
            }
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
            // an extension's nullability is its storage's, as the file-schema parser has it
            DType storage = dtypeFromProto(proto.extension().storage_dtype());
            return new DType.Extension(
                    proto.extension().id(),
                    storage,
                    MemorySegment.ofArray(proto.extension().metadata() != null ? proto.extension().metadata() : new byte[0]).asReadOnly(),
                    storage.nullable());
        }
        if (proto.variant() != null) {
            return new DType.Variant(proto.variant().nullable());
        }
        if (proto.union() != null) {
            var u = proto.union();
            var names = new ArrayList<io.github.dfa1.vortex.core.model.ColumnName>(u.names().size());
            for (String name : u.names()) {
                names.add(io.github.dfa1.vortex.core.model.ColumnName.of(name));
            }
            var types = new ArrayList<DType>(u.dtypes().size());
            for (io.github.dfa1.vortex.core.proto.ProtoDType child : u.dtypes()) {
                types.add(dtypeFromProto(child));
            }
            return new DType.Union(names, types, u.type_ids(), u.nullable());
        }
        throw new VortexException("unsupported proto DType");
    }
}
