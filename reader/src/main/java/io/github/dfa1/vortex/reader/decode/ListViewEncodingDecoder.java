package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ListViewArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
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

        if (nchildren == 3) {
            return new ListViewArray(listDtype, outerLen, elements, offsets, sizes);
        }

        // A list-view carries its own validity in a fourth child slot rather than under a
        // vortex.masked wrapper — that is how the Rust reference stores a nullable list, and
        // vortex.map relies on it because it requires its entries child to be a bare list-view.
        //
        // A validity slot under a dtype the file itself declares non-nullable is a
        // contradiction, and one that cannot be resolved silently: the decoded array would
        // report nullable=true while the column's declared dtype (what Chunk.Column hands
        // downstream) says nullable=false, so a consumer trusting the declared dtype would read
        // the null rows' placeholder slots as real values. Fail loudly instead — a crafted file
        // must never produce a wrong answer.
        if (!listDtype.nullable()) {
            throw new VortexException(EncodingId.VORTEX_LISTVIEW,
                    "validity child present but the declared dtype is non-nullable: " + listDtype);
        }
        Array validityArray = ctx.decodeChild(3, DType.BOOL, outerLen);
        if (!(validityArray instanceof BoolArray validity)) {
            throw new VortexException(EncodingId.VORTEX_LISTVIEW,
                    "validity child decoded to unexpected type: " + validityArray.getClass().getSimpleName());
        }
        DType.List innerDtype = (DType.List) listDtype.withNullable(false);
        return new MaskedArray(new ListViewArray(innerDtype, outerLen, elements, offsets, sizes), validity);
    }
}
