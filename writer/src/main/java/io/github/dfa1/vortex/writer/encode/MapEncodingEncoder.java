package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;

/// Write-only encoder for `vortex.map`: one `entries` child, no buffers, no metadata.
///
/// A map column's data has exactly the shape of a `ListView<Struct{key, value}>` column — a
/// [ListViewData] whose `elements` is a [StructData] of the key and value arrays — because the
/// entries child literally is a list-view of entry structs. A nullable map column arrives as a
/// [NullableData] wrapping that [ListViewData]: map validity is delegated to the entries child,
/// which carries it in the list-view's own validity slot rather than under a `vortex.masked`
/// wrapper — the Rust reference rejects any entries child that is not a bare `vortex.listview`.
public final class MapEncodingEncoder implements EncodingEncoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_MAP;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Map;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        DType.Map mapDtype = (DType.Map) dtype;
        DType entriesListDtype = new DType.List(mapDtype.entriesDtype(), mapDtype.nullable());
        EncodeResult entriesResult = new ListViewEncodingEncoder().encode(entriesListDtype, data, ctx);
        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_MAP,
                null,
                new EncodeNode[]{entriesResult.rootNode()},
                new int[0]);
        return new EncodeResult(root, entriesResult.buffers(), null, null);
    }
}
