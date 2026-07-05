package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ListViewArray;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoListViewMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Read-only decoder for `vortex.listview`.
public final class ListViewEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_LISTVIEW;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        if (!(ctx.dtype() instanceof DType.List listDtype)) {
            throw new VortexException(EncodingId.VORTEX_LISTVIEW,
                    "expected DType.List, got " + ctx.dtype());
        }

        int nchildren = ctx.node().children().length;
        if (nchildren < 3 || nchildren > 4) {
            throw new VortexException(EncodingId.VORTEX_LISTVIEW,
                    "expected 3 or 4 children, got " + nchildren);
        }

        ProtoListViewMetadata meta;
        try {
            MemorySegment metaSeg = ctx.metadata();
            meta = ProtoListViewMetadata.decode(metaSeg, 0, metaSeg.byteSize());
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
