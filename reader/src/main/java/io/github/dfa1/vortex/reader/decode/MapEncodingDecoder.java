package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MapArray;

/// Read-only decoder for `vortex.map`: one `entries` child, no buffers, no metadata.
///
/// The entries child must be a bare `vortex.listview` of the map's non-nullable `{key, value}`
/// entry struct — the same three checks (encoding, then dtype, with the length fixed by the
/// decode call) the Rust reference applies in its own `validate_entries`.
public final class MapEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_MAP;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        if (!(ctx.dtype() instanceof DType.Map mapDtype)) {
            throw new VortexException(EncodingId.VORTEX_MAP,
                    "expected DType.Map, got " + ctx.dtype());
        }

        int nchildren = ctx.node().children().length;
        if (nchildren != 1) {
            throw new VortexException(EncodingId.VORTEX_MAP,
                    "expected 1 child, got " + nchildren);
        }

        // The entries child must be a bare list-view, exactly as the Rust reference's
        // validate_entries demands ("MapArray entries must use vortex.listview encoding"). In
        // particular a vortex.masked wrapper around it is rejected: a nullable map delegates its
        // validity to the list-view's own fourth child slot, and accepting both shapes would
        // leave two wire encodings for one logical column that no writer — this one included —
        // ever emits.
        EncodingId entriesEncoding = ctx.node().children()[0].encodingId();
        if (!EncodingId.VORTEX_LISTVIEW.equals(entriesEncoding)) {
            throw new VortexException(EncodingId.VORTEX_MAP,
                    "entries child must use vortex.listview encoding, got " + entriesEncoding.id());
        }

        // The map's own nullability lives on the entries list: a null map row is a null
        // entries row (upstream delegates map validity to this child).
        DType entriesListDtype = new DType.List(mapDtype.entriesDtype(), mapDtype.nullable());
        Array entries = ctx.decodeChild(0, entriesListDtype, ctx.rowCount());
        if (!entries.dtype().equals(entriesListDtype)) {
            throw new VortexException(EncodingId.VORTEX_MAP,
                    "entries child decoded to " + entries.dtype() + ", expected " + entriesListDtype);
        }
        return new MapArray(mapDtype, ctx.rowCount(), entries);
    }
}
