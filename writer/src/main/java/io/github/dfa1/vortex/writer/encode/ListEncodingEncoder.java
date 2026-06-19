package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;



import io.github.dfa1.vortex.encoding.EncodingId;

import io.github.dfa1.vortex.encoding.PTypeIO;


import io.github.dfa1.vortex.proto.ListMetadata;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.list`.
public final class ListEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public ListEncodingEncoder() {
    }

    private static final List<EncodingEncoder> FALLBACK = List.of(
            new PrimitiveEncodingEncoder(), new VarBinEncodingEncoder(), new BoolEncodingEncoder(),
            new NullEncodingEncoder(), new ByteBoolEncodingEncoder());

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
        for (int i = 0; i < nOffsets; i++) {
            offsetsBuf.setAtIndex(PTypeIO.LE_LONG, i, ld.offsets()[i]);
        }
        allBuffers.add(offsetsBuf);
        EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, elemBufCount);

        long elementsLen = ld.offsets()[(int) ld.outerLen()];
        byte[] metaBytes = new ListMetadata(
                elementsLen,
                io.github.dfa1.vortex.proto.PType.fromValue(PType.I64.ordinal())
        ).encode();

        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_LIST,
                ByteBuffer.wrap(metaBytes),
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
