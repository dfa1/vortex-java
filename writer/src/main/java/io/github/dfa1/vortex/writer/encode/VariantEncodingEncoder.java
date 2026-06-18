package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.proto.ScalarValue;
import io.github.dfa1.vortex.proto.VariantMetadata;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;

/// Write-only encoder for `vortex.variant`.
///
/// Layer A: encodes a constant variant column — every row carries the same value.
/// The container emits a single `vortex.constant` child (`core_storage`) whose scalar
/// is the variant value; there is no shredded child and the container owns no buffers.
/// The `VariantMetadata` proto records no `shredded_dtype`.
public final class VariantEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public VariantEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_VARIANT;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Variant;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (!(dtype instanceof DType.Variant)) {
            throw new VortexException(EncodingId.VORTEX_VARIANT, "encode requires Variant dtype, got " + dtype);
        }
        if (!(data instanceof VariantData variantData)) {
            throw new VortexException(EncodingId.VORTEX_VARIANT,
                    "encode requires VariantData, got " + (data == null ? "null" : data.getClass().getName()));
        }

        // core_storage: a single vortex.constant child holding the variant scalar for every row.
        // The constant encoding stores its scalar in buffer 0 (not metadata), so the child is a
        // buffer-backed leaf and the container threads that single buffer up to the segment.
        ScalarValue coreScalar = ScalarValue.ofVariantValue(variantData.constantValue());
        MemorySegment coreBuffer = MemorySegment.ofArray(coreScalar.encode());
        EncodeNode coreChild = EncodeNode.leaf(EncodingId.VORTEX_CONSTANT, 0);

        // Container metadata: no shredding in Layer A, so shredded_dtype is absent.
        ByteBuffer containerMeta = ByteBuffer.wrap(new VariantMetadata(null).encode());
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_VARIANT, containerMeta,
                new EncodeNode[]{coreChild}, new int[0]);

        return new EncodeResult(root, List.of(coreBuffer), null, null);
    }
}
