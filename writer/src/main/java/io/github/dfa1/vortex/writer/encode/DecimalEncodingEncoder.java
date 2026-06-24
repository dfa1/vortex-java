package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoDecimalMetadata;

import java.lang.foreign.MemorySegment;
import java.util.List;

/// Write-only encoder for `vortex.decimal`.
public final class DecimalEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public DecimalEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_DECIMAL;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Decimal;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        DType.Decimal d = (DType.Decimal) dtype;
        MemorySegment seg = (MemorySegment) data;
        int valuesType = valuesType(d.precision());
        int bw = byteWidth(valuesType);
        if (seg.byteSize() % bw != 0) {
            throw new VortexException(EncodingId.VORTEX_DECIMAL,
                    "buffer size %d not multiple of byteWidth %d".formatted(seg.byteSize(), bw));
        }
        MemorySegment metaBuf = MemorySegment.ofArray(new ProtoDecimalMetadata(valuesType).encode());
        EncodeNode node = new EncodeNode(EncodingId.VORTEX_DECIMAL, metaBuf, new EncodeNode[0], new int[]{0});
        return new EncodeResult(node, List.of(seg), null, null);
    }

    private static int valuesType(byte precision) {
        if (precision <= 2) {
            return 0;
        }
        if (precision <= 4) {
            return 1;
        }
        if (precision <= 9) {
            return 2;
        }
        if (precision <= 18) {
            return 3;
        }
        if (precision <= 38) {
            return 4;
        }
        return 5;
    }

    private static int byteWidth(int valuesType) {
        return switch (valuesType) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 8;
            case 4 -> 16;
            case 5 -> 32;
            default -> throw new VortexException(EncodingId.VORTEX_DECIMAL,
                    "unknown valuesType: " + valuesType);
        };
    }
}
