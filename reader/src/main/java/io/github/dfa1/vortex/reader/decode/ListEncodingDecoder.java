package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ListArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoListMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Read-only decoder for `vortex.list`.
public final class ListEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_LIST;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        if (!(ctx.dtype() instanceof DType.List listDtype)) {
            throw new VortexException(EncodingId.VORTEX_LIST,
                    "expected DType.List, got " + ctx.dtype());
        }

        int nchildren = ctx.node().children().length;
        if (nchildren < 2 || nchildren > 3) {
            throw new VortexException(EncodingId.VORTEX_LIST,
                    "expected 2 or 3 children, got " + nchildren);
        }

        ProtoListMetadata meta;
        try {
            MemorySegment metaSeg = ctx.metadata();
            meta = ProtoListMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_LIST, "invalid metadata", e);
        }

        long elementsLen = meta.elements_len();
        PType offsetPtype = PType.fromOrdinal(meta.offset_ptype().value());
        long outerLen = ctx.rowCount();

        DType elementDtype = listDtype.elementType();
        DType offsetsDtype = new DType.Primitive(offsetPtype, false);

        Array elements = ctx.decodeChild(0, elementDtype, elementsLen);
        Array offsets = ctx.decodeChild(1, offsetsDtype, outerLen + 1);

        // A nullable list carries its validity as a third child (Rust: `children.get(2, ..)`),
        // not under a vortex.masked wrapper. Ignoring it turned every null row into an empty list.
        // As for vortex.listview, the declared nullability is authoritative: a third child under
        // a non-nullable dtype is not consulted.
        if (nchildren == 2 || !listDtype.nullable()) {
            return new ListArray(listDtype, outerLen, elements, offsets);
        }
        Array validityArray = ctx.decodeChild(2, DType.BOOL, outerLen);
        BoolArray validity = MaskedArray.requireBoolArray(validityArray, EncodingId.VORTEX_LIST, "validity child");
        DType.List innerDtype = (DType.List) listDtype.withNullable(false);
        return new MaskedArray(new ListArray(innerDtype, outerLen, elements, offsets), validity);
    }
}
