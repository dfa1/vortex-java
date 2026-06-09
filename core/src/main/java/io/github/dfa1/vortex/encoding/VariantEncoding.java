package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.VariantArray;
import io.github.dfa1.vortex.proto.VariantMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Decoder for {@code vortex.variant}.
///
/// <p>Wire format:
/// <ul>
///   <li>Metadata: proto {@code VariantMetadata {optional DType shredded_dtype = 1;}}</li>
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
                MemorySegment metaSeg = MemorySegment.ofBuffer(rawMeta.duplicate());
                VariantMetadata meta = VariantMetadata.decode(metaSeg, 0, metaSeg.byteSize());
                if (meta.shredded_dtype() == null) {
                    return null;
                }
                return dtypeFromProto(meta.shredded_dtype());
            } catch (IOException e) {
                throw new VortexException(EncodingId.VORTEX_VARIANT, "invalid metadata", e);
            }
        }

        static DType dtypeFromProto(io.github.dfa1.vortex.proto.DType proto) {
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
                for (io.github.dfa1.vortex.proto.DType child : s.dtypes()) {
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
                        ByteBuffer.wrap(proto.extension().metadata() != null ? proto.extension().metadata() : new byte[0]).asReadOnlyBuffer(),
                        false);
            }
            if (proto.variant() != null) {
                return new DType.Variant(proto.variant().nullable());
            }
            throw new VortexException("unsupported proto DType");
        }
    }
}
