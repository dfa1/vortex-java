package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ListViewArray;
import io.github.dfa1.vortex.proto.ListViewMetadata;

import java.io.IOException;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Encoder/decoder for {@code vortex.listview}.
///
/// <p>Wire format (per Rust vtable):
/// <ul>
///   <li>Buffers: 0
///   <li>Metadata: protobuf {@code ListViewMetadata} — {@code elements_len} (u64),
///       {@code offset_ptype} (PType), {@code size_ptype} (PType).
///   <li>Children: 3 or 4.
///       {@code children[0]} = elements (len = elements_len, dtype = elementType).
///       {@code children[1]} = offsets (len = outerLen, dtype = offset_ptype, non-nullable).
///       {@code children[2]} = sizes (len = outerLen, dtype = size_ptype, non-nullable).
///       {@code children[3]} = validity (optional, Bool, len = outerLen).
/// </ul>
///
/// <p>Unlike {@code vortex.list}, offsets and sizes have length {@code outerLen} (not outerLen+1);
/// list {@code i} covers {@code elements[offsets[i]..offsets[i]+sizes[i]]}.
public final class ListViewEncoding implements Encoding {

    /// Creates a new {@code ListViewEncoding} instance; use via {@link Registry}.
    public ListViewEncoding() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_LISTVIEW;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.List;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        return Encoder.encode((DType.List) dtype, (ListViewData) data, ctx);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Encoder {

        private static final List<Encoding> FALLBACK = List.of(
                new PrimitiveEncoding(), new VarBinEncoding(), new BoolEncoding(),
                new NullEncoding(), new ByteBoolEncoding());

        static EncodeResult encode(DType.List dtype, ListViewData data, EncodeContext ctx) {
            DType elementType = dtype.elementType();
            Encoding elemEncoding = findEncoding(elementType);
            EncodeResult elemResult = elemEncoding.encode(elementType, data.elements(), ctx);

            List<MemorySegment> allBuffers = new ArrayList<>(elemResult.buffers());
            int elemBufCount = allBuffers.size();
            EncodeNode elemNode = EncodeNode.remapBufferIndices(elemResult.rootNode(), 0);

            long n = data.outerLen();

            MemorySegment offsetsBuf = ctx.arena().allocate(n * Integer.BYTES, Integer.BYTES);
            for (int i = 0; i < n; i++) {
                offsetsBuf.setAtIndex(PTypeIO.LE_INT, i, data.offsets()[i]);
            }
            allBuffers.add(offsetsBuf);
            EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, elemBufCount);

            MemorySegment sizesBuf = ctx.arena().allocate(n * Integer.BYTES, Integer.BYTES);
            for (int i = 0; i < n; i++) {
                sizesBuf.setAtIndex(PTypeIO.LE_INT, i, data.sizes()[i]);
            }
            allBuffers.add(sizesBuf);
            EncodeNode sizesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, elemBufCount + 1);

            long elementsLen = java.lang.reflect.Array.getLength(data.elements());
            byte[] metaBytes = new ListViewMetadata(
                    elementsLen,
                    io.github.dfa1.vortex.proto.PType.fromValue(PType.I32.ordinal()),
                    io.github.dfa1.vortex.proto.PType.fromValue(PType.I32.ordinal())
            ).encode();

            EncodeNode root = new EncodeNode(
                    EncodingId.VORTEX_LISTVIEW,
                    ByteBuffer.wrap(metaBytes),
                    new EncodeNode[]{elemNode, offsetsNode, sizesNode},
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
                throw new VortexException(EncodingId.VORTEX_LISTVIEW,
                        "expected DType.List, got " + ctx.dtype());
            }

            int nchildren = ctx.node().children().length;
            if (nchildren < 3 || nchildren > 4) {
                throw new VortexException(EncodingId.VORTEX_LISTVIEW,
                        "expected 3 or 4 children, got " + nchildren);
            }

            ListViewMetadata meta;
            try {
                MemorySegment metaSeg = MemorySegment.ofBuffer(ctx.metadata().duplicate());
                meta = ListViewMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            } catch (IOException e) {
                throw new VortexException(EncodingId.VORTEX_LISTVIEW, "invalid metadata", e);
            }

            long elementsLen = meta.elements_len();
            PType offsetPtype = PType.fromOrdinal(meta.offset_ptype().value());
            PType sizePtype = PType.fromOrdinal(meta.size_ptype().value());
            long outerLen = ctx.rowCount();

            DType elementDtype = listDtype.elementType();
            DType offsetsDtype = new DType.Primitive(offsetPtype, false);
            DType sizesDtype = new DType.Primitive(sizePtype, false);

            Array elements = ctx.decodeChild(0, elementDtype, elementsLen);
            Array offsets = ctx.decodeChild(1, offsetsDtype, outerLen);
            Array sizes = ctx.decodeChild(2, sizesDtype, outerLen);

            return new ListViewArray(listDtype, outerLen, elements, offsets, sizes);
        }
    }
}
