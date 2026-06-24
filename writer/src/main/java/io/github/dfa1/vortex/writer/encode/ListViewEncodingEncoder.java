package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;



import io.github.dfa1.vortex.encoding.EncodingId;

import io.github.dfa1.vortex.encoding.PTypeIO;


import io.github.dfa1.vortex.proto.ProtoListViewMetadata;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.listview`.
public final class ListViewEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public ListViewEncodingEncoder() {
    }

    private static final List<EncodingEncoder> FALLBACK = List.of(
            new PrimitiveEncodingEncoder(), new VarBinEncodingEncoder(), new BoolEncodingEncoder(),
            new NullEncodingEncoder(), new ByteBoolEncodingEncoder());

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_LISTVIEW;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.List;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        DType.List listDtype = (DType.List) dtype;
        ListViewData lvd = (ListViewData) data;
        DType elementType = listDtype.elementType();
        EncodingEncoder elemEncoding = findEncoding(elementType);
        EncodeResult elemResult = elemEncoding.encode(elementType, lvd.elements(), ctx);

        List<MemorySegment> allBuffers = new ArrayList<>(elemResult.buffers());
        int elemBufCount = allBuffers.size();
        EncodeNode elemNode = EncodeNode.remapBufferIndices(elemResult.rootNode(), 0);

        long n = lvd.outerLen();

        MemorySegment offsetsBuf = ctx.arena().allocate(n * Integer.BYTES, Integer.BYTES);
        for (int i = 0; i < n; i++) {
            offsetsBuf.setAtIndex(PTypeIO.LE_INT, i, lvd.offsets()[i]);
        }
        allBuffers.add(offsetsBuf);
        EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, elemBufCount);

        MemorySegment sizesBuf = ctx.arena().allocate(n * Integer.BYTES, Integer.BYTES);
        for (int i = 0; i < n; i++) {
            sizesBuf.setAtIndex(PTypeIO.LE_INT, i, lvd.sizes()[i]);
        }
        allBuffers.add(sizesBuf);
        EncodeNode sizesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, elemBufCount + 1);

        long elementsLen = java.lang.reflect.Array.getLength(lvd.elements());
        byte[] metaBytes = new ProtoListViewMetadata(
                elementsLen,
                io.github.dfa1.vortex.proto.ProtoPType.fromValue(PType.I32.ordinal()),
                io.github.dfa1.vortex.proto.ProtoPType.fromValue(PType.I32.ordinal())
        ).encode();

        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_LISTVIEW,
                MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{elemNode, offsetsNode, sizesNode},
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
