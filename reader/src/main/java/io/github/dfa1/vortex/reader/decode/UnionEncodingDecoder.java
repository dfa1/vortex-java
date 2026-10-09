package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.UnionArray;

import java.util.ArrayList;
import java.util.List;

/// Decoder for `vortex.union`, Rust's canonical sparse union (`vortex-array/src/arrays/union`):
/// no buffers and no metadata; children `[type_ids, variant_0, …, variant_{N-1}]`. `type_ids` is
/// `U8`, nullable exactly when the union is, and every child is as long as the union.
///
/// `vortex.union` is in no edition, so the writer never emits it; this is the read side only.
public final class UnionEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_UNION;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        if (!(ctx.dtype() instanceof DType.Union union)) {
            throw new VortexException(EncodingId.VORTEX_UNION, "expected union dtype, got " + ctx.dtype());
        }
        if (ctx.node().bufferIndices().length != 0) {
            throw new VortexException(EncodingId.VORTEX_UNION, "expects no buffers, got " + ctx.node().bufferIndices().length);
        }
        if (ctx.metadata() != null && ctx.metadata().byteSize() != 0) {
            throw new VortexException(EncodingId.VORTEX_UNION, "expects no metadata, got " + ctx.metadata().byteSize() + " bytes");
        }
        int variantCount = union.variantTypes().size();
        int children = ctx.node().children().length;
        if (children != variantCount + 1) {
            throw new VortexException(EncodingId.VORTEX_UNION,
                    "expected " + (variantCount + 1) + " children (type_ids + variants), got " + children);
        }
        long rows = ctx.rowCount();
        Array typeIds = ctx.decodeChild(0, new DType.Primitive(PType.U8, union.nullable()), rows);
        List<Array> variants = new ArrayList<>(variantCount);
        for (int i = 0; i < variantCount; i++) {
            variants.add(ctx.decodeChild(i + 1, union.variantTypes().get(i), rows));
        }
        return new UnionArray(union, rows, typeIds, variants);
    }
}
