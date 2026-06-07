package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ListArray;
import io.github.dfa1.vortex.proto.EncodingProtos;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Encoder/decoder for {@code vortex.list}.
///
/// <p>Wire format (per Rust vtable):
/// <ul>
///   <li>Buffers: 0
///   <li>Metadata: protobuf {@code ListMetadata} — {@code elements_len} (u64) + {@code offset_ptype} (PType).
///   <li>Children: 2 or 3.
///       {@code children[0]} = elements array (len = elements_len, dtype = elementType).
///       {@code children[1]} = offsets array (len = outerLen + 1, dtype = offset_ptype, non-nullable).
///       {@code children[2]} = validity (optional, Bool, len = outerLen).
/// </ul>
public final class ListEncoding implements Encoding {

    /// Creates a new {@code ListEncoding} instance; use via {@link EncodingRegistry}.
    public ListEncoding() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_LIST;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.List;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        return Encoder.encode((DType.List) dtype, (ListData) data, ctx);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Encoder {

        private static final List<Encoding> FALLBACK = List.of(
                new PrimitiveEncoding(), new VarBinEncoding(), new BoolEncoding(),
                new NullEncoding(), new ByteBoolEncoding());

        static EncodeResult encode(DType.List dtype, ListData data, EncodeContext ctx) {
            DType elementType = dtype.elementType();
            Encoding elemEncoding = findEncoding(elementType);
            EncodeResult elemResult = elemEncoding.encode(elementType, data.elements(), ctx);

            List<MemorySegment> allBuffers = new ArrayList<>(elemResult.buffers());
            int elemBufCount = allBuffers.size();
            EncodeNode elemNode = EncodeNode.remapBufferIndices(elemResult.rootNode(), 0);

            long nOffsets = data.outerLen() + 1;
            MemorySegment offsetsBuf = ctx.arena().allocate(nOffsets * Long.BYTES, Long.BYTES);
            for (int i = 0; i < nOffsets; i++) {
                offsetsBuf.setAtIndex(PTypeIO.LE_LONG, i, data.offsets()[i]);
            }
            allBuffers.add(offsetsBuf);
            EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, elemBufCount);

            long elementsLen = data.offsets()[(int) data.outerLen()];
            byte[] metaBytes = EncodingProtos.ListMetadata.newBuilder()
                                       .setElementsLen(elementsLen)
                                       .setOffsetPtype(io.github.dfa1.vortex.proto.DTypeProtos.PType.forNumber(PType.I64.ordinal()))
                                       .build()
                                       .toByteArray();

            EncodeNode root = new EncodeNode(
                    EncodingId.VORTEX_LIST,
                    ByteBuffer.wrap(metaBytes),
                    new EncodeNode[]{elemNode, offsetsNode},
                    new int[]{});
            return new EncodeResult(root, List.copyOf(allBuffers), null, null);
        }

        private static Encoding findEncoding(DType dtype) {
            for (Encoding enc : FALLBACK) {
                if (enc.accepts(dtype)) {
                    return enc;
                }
            }
            throw new UnsupportedOperationException("no fallback encoding for dtype: " + dtype);
        }
    }

    private static final class Decoder {

        static Array decode(DecodeContext ctx) {
            if (!(ctx.dtype() instanceof DType.List listDtype)) {
                throw new VortexException(EncodingId.VORTEX_LIST,
                        "expected DType.List, got " + ctx.dtype());
            }

            int nchildren = ctx.node().children().length;
            if (nchildren < 2 || nchildren > 3) {
                throw new VortexException(EncodingId.VORTEX_LIST,
                        "expected 2 or 3 children, got " + nchildren);
            }

            EncodingProtos.ListMetadata meta;
            try {
                meta = EncodingProtos.ListMetadata.parseFrom(ctx.metadata().duplicate());
            } catch (InvalidProtocolBufferException e) {
                throw new VortexException(EncodingId.VORTEX_LIST, "invalid metadata", e);
            }

            long elementsLen = meta.getElementsLen();
            PType offsetPtype = PType.fromOrdinal(meta.getOffsetPtype().getNumber());
            long outerLen = ctx.rowCount();

            DType elementDtype = listDtype.elementType();
            DType offsetsDtype = new DType.Primitive(offsetPtype, false);

            Array elements = ctx.decodeChild(0, elementDtype, elementsLen);
            Array offsets = ctx.decodeChild(1, offsetsDtype, outerLen + 1);

            return new ListArray(listDtype, outerLen, elements, offsets);
        }
    }
}
