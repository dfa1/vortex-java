package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ListArray;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoListMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Read-only decoder for `vortex.list`.
public final class ListEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public ListEncodingDecoder() {
    }

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

        return new ListArray(listDtype, outerLen, elements, offsets);
    }
}
