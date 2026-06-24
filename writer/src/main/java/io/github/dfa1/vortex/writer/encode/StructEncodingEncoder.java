package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;



import io.github.dfa1.vortex.core.model.EncodingId;




import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.struct`.
public final class StructEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public StructEncodingEncoder() {
    }

    private static final List<EncodingEncoder> FALLBACK = List.of(
            new PrimitiveEncodingEncoder(), new VarBinEncodingEncoder(), new BoolEncodingEncoder(),
            new NullEncodingEncoder(), new ByteBoolEncodingEncoder());

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_STRUCT;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Struct;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        DType.Struct sdtype = (DType.Struct) dtype;
        StructData sd = (StructData) data;
        List<Object> fields = sd.fieldArrays();
        List<DType> fieldTypes = sdtype.fieldTypes();
        if (fields.size() != fieldTypes.size()) {
            throw new VortexException(EncodingId.VORTEX_STRUCT,
                    "fieldArrays length %d != fieldTypes length %d".formatted(fields.size(), fieldTypes.size()));
        }
        List<MemorySegment> allBuffers = new ArrayList<>();
        EncodeNode[] children = new EncodeNode[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            DType fieldDtype = fieldTypes.get(i);
            Object fieldData = fields.get(i);
            // Nullable fields carry a NullableData(values, validity) pair; route them through the
            // masked encoder (values + validity bitmap), mirroring VortexWriter.writeSegment. The
            // plain fallbacks only handle dense arrays.
            EncodingEncoder fieldEncoder =
                    (fieldData instanceof NullableData && !(fieldDtype instanceof DType.Extension))
                            ? new MaskedEncodingEncoder()
                            : findEncoding(fieldDtype);
            EncodeResult fieldResult = fieldEncoder.encode(fieldDtype, fieldData, ctx);
            int bufOffset = allBuffers.size();
            children[i] = EncodeNode.remapBufferIndices(fieldResult.rootNode(), bufOffset);
            allBuffers.addAll(fieldResult.buffers());
        }
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_STRUCT, null, children, new int[0]);
        return new EncodeResult(root, List.copyOf(allBuffers), null, null);
    }

    private static EncodingEncoder findEncoding(DType dtype) {
        for (EncodingEncoder enc : FALLBACK) {
            if (enc.accepts(dtype)) {
                return enc;
            }
        }
        throw new UnsupportedOperationException("no fallback encoding for dtype: " + dtype);
    }
}
