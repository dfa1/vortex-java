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

        // A list-view carries its own validity in a fourth child slot rather than under a
        // vortex.masked wrapper — that is how the Rust reference stores a nullable list, and
        // vortex.map relies on it because it requires its entries child to be a bare list-view.
        //
        // The declared dtype's nullability is authoritative, not the fourth child's mere
        // presence: the Rust reference's own `ValidityVTable` contract is that "non-nullable
        // arrays bypass this hook" entirely, and its explicit-normalization writer (0.86+) may
        // emit a structurally uniform four-child node even for a non-nullable list-view. So a
        // fourth child under a non-nullable dtype is not a contradiction to reject — it is simply
        // not consulted.
        if (nchildren == 3 || !listDtype.nullable()) {
            return new ListViewArray(listDtype, outerLen, elements, offsets, sizes);
        }
        Array validityArray = ctx.decodeChild(3, DType.BOOL, outerLen);
        BoolArray validity = MaskedArray.requireBoolArray(validityArray, EncodingId.VORTEX_LISTVIEW, "validity child");
        DType.List innerDtype = (DType.List) listDtype.withNullable(false);
        return new MaskedArray(new ListViewArray(innerDtype, outerLen, elements, offsets, sizes), validity);
    }
}
