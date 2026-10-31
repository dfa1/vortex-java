package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;

import io.github.dfa1.vortex.core.model.EncodingId;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.fixed_size_list`.
public final class FixedSizeListEncodingEncoder implements EncodingEncoder {

    private static final List<EncodingEncoder> FALLBACK = List.of(
            new PrimitiveEncodingEncoder(), new VarBinEncodingEncoder(), new BoolEncodingEncoder(),
            new NullEncodingEncoder(), new ByteBoolEncodingEncoder(), new StructEncodingEncoder(),
            new FixedSizeListEncodingEncoder(), new ListEncodingEncoder());

    /// Creates the encoder.
    public FixedSizeListEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_FIXED_SIZE_LIST;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.FixedSizeList;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        DType.FixedSizeList fsl = (DType.FixedSizeList) dtype;
        FixedSizeListData fsd = (FixedSizeListData) data;
        DType elementType = fsl.elementType();
        // Nullable elements carry a NullableData(values, validity) pair, which the element type's
        // own fallback encoder (dense values only) rejects: route it through the masked encoder, as
        // ListEncodingEncoder and StructEncodingEncoder do.
        EncodingEncoder inner = fsd.elements() instanceof NullableData && !(elementType instanceof DType.Extension)
                ? new MaskedEncodingEncoder()
                : FallbackEncodings.require(FALLBACK, elementType);

        EncodeResult elemResult = inner.encode(elementType, fsd.elements(), ctx);

        List<EncodedBuffer> allBuffers = new ArrayList<>(elemResult.encodedBuffers());
        EncodeNode elemNode = EncodeNode.remapBufferIndices(elemResult.rootNode(), 0);

        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_FIXED_SIZE_LIST,
                MemorySegment.ofArray(new byte[0]),
                new EncodeNode[]{elemNode},
                new int[]{});
        return new EncodeResult(root, List.copyOf(allBuffers), null, null);
    }
}
