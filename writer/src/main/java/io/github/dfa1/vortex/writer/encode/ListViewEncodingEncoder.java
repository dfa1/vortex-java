package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;

import io.github.dfa1.vortex.core.model.EncodingId;

import io.github.dfa1.vortex.core.io.VortexFormat;

import io.github.dfa1.vortex.core.proto.ProtoListViewMetadata;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.listview`.
public final class ListViewEncodingEncoder implements EncodingEncoder {

    // StructEncodingEncoder is here because a ListView of structs is a legitimate column shape
    // (and is exactly how a vortex.map column stores its {key, value} entries).
    private static final List<EncodingEncoder> FALLBACK = List.of(
            new PrimitiveEncodingEncoder(), new VarBinEncodingEncoder(), new BoolEncodingEncoder(),
            new NullEncodingEncoder(), new ByteBoolEncodingEncoder(), new StructEncodingEncoder());

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
        // A nullable list-view keeps its own validity in a fourth child slot instead of under a
        // vortex.masked wrapper, matching the Rust reference — and vortex.map depends on it,
        // because a map's entries child must be a bare list-view.
        NullableData outerNullable = data instanceof NullableData nd ? nd : null;
        Object payload = outerNullable != null ? outerNullable.values() : data;
        if (!(payload instanceof ListViewData lvd)) {
            throw new VortexException(EncodingId.VORTEX_LISTVIEW,
                    "expected ListViewData, got " + (payload == null ? "null" : payload.getClass().getName()));
        }
        DType elementType = listDtype.elementType();
        // A nullable element type carries a NullableData(values, validity) pair, which only the
        // masked encoder understands — same dispatch StructEncodingEncoder does per field.
        EncodingEncoder elemEncoding =
                (lvd.elements() instanceof NullableData && !(elementType instanceof DType.Extension))
                        ? new MaskedEncodingEncoder()
                        : findEncoding(elementType);
        EncodeResult elemResult = elemEncoding.encode(elementType, lvd.elements(), ctx);

        List<MemorySegment> allBuffers = new ArrayList<>(elemResult.buffers());
        int elemBufCount = allBuffers.size();
        EncodeNode elemNode = EncodeNode.remapBufferIndices(elemResult.rootNode(), 0);

        long n = lvd.outerLen();

        MemorySegment offsetsBuf = ctx.arena().allocate(n * Integer.BYTES, Integer.BYTES);
        for (int i = 0; i < n; i++) {
            offsetsBuf.setAtIndex(VortexFormat.LE_INT, i, lvd.offsets()[i]);
        }
        allBuffers.add(offsetsBuf);
        EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, elemBufCount);

        MemorySegment sizesBuf = ctx.arena().allocate(n * Integer.BYTES, Integer.BYTES);
        for (int i = 0; i < n; i++) {
            sizesBuf.setAtIndex(VortexFormat.LE_INT, i, lvd.sizes()[i]);
        }
        allBuffers.add(sizesBuf);
        EncodeNode sizesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, elemBufCount + 1);

        long elementsLen = elementCount(lvd.elements());
        byte[] metaBytes = new ProtoListViewMetadata(
                elementsLen,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I32.ordinal()),
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I32.ordinal())
        ).encode();

        EncodeNode[] children;
        if (outerNullable == null) {
            children = new EncodeNode[]{elemNode, offsetsNode, sizesNode};
        } else {
            EncodeResult validityResult = new BoolEncodingEncoder()
                    .encode(DType.BOOL, outerNullable.validity(), ctx);
            EncodeNode validityNode = EncodeNode.remapBufferIndices(validityResult.rootNode(), allBuffers.size());
            allBuffers.addAll(validityResult.buffers());
            children = new EncodeNode[]{elemNode, offsetsNode, sizesNode, validityNode};
        }

        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_LISTVIEW,
                MemorySegment.ofArray(metaBytes),
                children,
                new int[]{});
        return new EncodeResult(root, List.copyOf(allBuffers), null, null);
    }

    /// Returns the number of inner elements carried by a list-view's `elements` payload.
    /// Struct elements are a [StructData] rather than a Java array, so their row count is the
    /// length of the first field array (an empty struct carries no rows).
    private static long elementCount(Object elements) {
        if (elements instanceof StructData(var fieldArrays)) {
            return fieldArrays.isEmpty() ? 0L : elementCount(fieldArrays.getFirst());
        }
        if (elements instanceof NullableData(_, var validity)) {
            return validity.length;
        }
        return java.lang.reflect.Array.getLength(elements);
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
