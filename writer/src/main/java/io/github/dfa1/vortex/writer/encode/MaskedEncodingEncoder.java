package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;

import io.github.dfa1.vortex.core.model.EncodingId;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.masked`. Wraps the payload encode in a values + validity
/// pair driven by a [NullableData] carrier.
public final class MaskedEncodingEncoder implements EncodingEncoder {

    private static final List<EncodingEncoder> INNER_FALLBACK = List.of(
            new PrimitiveEncodingEncoder(),
            new VarBinEncodingEncoder(),
            new FixedSizeListEncodingEncoder());

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_MASKED;
    }

    @Override
    public boolean accepts(DType dtype) {
        return false;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (!(data instanceof NullableData nd)) {
            throw new VortexException(EncodingId.VORTEX_MASKED,
                    "expected NullableData, got " + (data == null ? "null" : data.getClass().getName()));
        }
        DType nonNullable = dtype.withNullable(false);
        EncodingEncoder inner = pickInner(nonNullable);
        EncodeResult valuesResult = inner.encode(nonNullable, nd.values(), ctx);
        EncodeResult validityResult = new BoolEncodingEncoder().encode(DType.BOOL, nd.validity(), ctx);

        int valuesBufCount = valuesResult.buffers().size();
        EncodeNode validityNode = EncodeNode.remapBufferIndices(validityResult.rootNode(), valuesBufCount);

        List<MemorySegment> buffers = new ArrayList<>(valuesBufCount + validityResult.buffers().size());
        buffers.addAll(valuesResult.buffers());
        buffers.addAll(validityResult.buffers());

        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_MASKED,
                null,
                new EncodeNode[]{valuesResult.rootNode(), validityNode},
                new int[0]);
        return new EncodeResult(root, buffers, valuesResult.statsMin(), valuesResult.statsMax());
    }

    private static EncodingEncoder pickInner(DType nonNullable) {
        for (EncodingEncoder e : INNER_FALLBACK) {
            if (e.accepts(nonNullable)) {
                return e;
            }
        }
        throw new VortexException(EncodingId.VORTEX_MASKED,
                "no inner encoding for " + nonNullable);
    }
}
