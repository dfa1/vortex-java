package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;

import io.github.dfa1.vortex.core.model.EncodingId;

import io.github.dfa1.vortex.core.io.VortexFormat;

import io.github.dfa1.vortex.core.proto.ProtoListMetadata;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.list`.
public final class ListEncodingEncoder implements EncodingEncoder {

    // Includes container encoders so nested element types (List[Struct], List[List], List[FixedSizeList]) resolve.
    private static final List<EncodingEncoder> FALLBACK = List.of(
            new PrimitiveEncodingEncoder(), new VarBinEncodingEncoder(), new BoolEncodingEncoder(),
            new NullEncodingEncoder(), new ByteBoolEncodingEncoder(), new StructEncodingEncoder(),
            new FixedSizeListEncodingEncoder(), new ListEncodingEncoder());

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_LIST;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.List;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        DType.List listDtype = (DType.List) dtype;
        ListData ld = (ListData) data;
        DType elementType = listDtype.elementType();
        EncodingEncoder elemEncoding = findEncoding(elementType);
        EncodeResult elemResult = elemEncoding.encode(elementType, ld.elements(), ctx);

        List<MemorySegment> allBuffers = new ArrayList<>(elemResult.buffers());
        int elemBufCount = allBuffers.size();
        EncodeNode elemNode = EncodeNode.remapBufferIndices(elemResult.rootNode(), 0);

        long nOffsets = ld.outerLen() + 1;
        MemorySegment offsetsBuf = ctx.arena().allocate(nOffsets * Long.BYTES, Long.BYTES);
        for (long i = 0; i < nOffsets; i++) {
            offsetsBuf.setAtIndex(VortexFormat.LE_LONG, i, ld.offsets()[(int) i]);
        }
        allBuffers.add(offsetsBuf);
        EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, elemBufCount);

        long elementsLen = ld.offsets()[(int) ld.outerLen()];
        byte[] metaBytes = new ProtoListMetadata(
                elementsLen,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I64.ordinal())
        ).encode();

        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_LIST,
                MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{elemNode, offsetsNode},
                new int[]{});
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
