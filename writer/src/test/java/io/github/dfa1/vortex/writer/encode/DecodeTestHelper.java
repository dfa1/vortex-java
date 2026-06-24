package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

/// Utilities for wrapping encode output into a [DecodeContext] for round-trip tests.
///
/// Public so writer/ test trees can reuse via the reader test-jar.
public final class DecodeTestHelper {

    private DecodeTestHelper() {
    }

    /// Wraps a writer's [EncodeResult] into a [DecodeContext] for round-trip assertions.
    ///
    /// @param result   writer output
    /// @param rowCount logical row count
    /// @param dtype    decoded dtype
    /// @param registry registry used for nested decode dispatch
    /// @return decode context ready for [EncodingDecoder#decode]
    public static DecodeContext toDecodeContext(
            EncodeResult result, long rowCount, DType dtype, ReadRegistry registry
    ) {
        List<MemorySegment> buffers = result.buffers();
        MemorySegment[] segments = buffers.toArray(new MemorySegment[0]);
        ArrayNode root = toArrayNode(result.rootNode());
        return new DecodeContext(root, dtype, rowCount, segments, registry, Arena.ofAuto());
    }

    private static ArrayNode toArrayNode(EncodeNode enc) {
        ArrayNode[] children = new ArrayNode[enc.children().length];
        for (int i = 0; i < children.length; i++) {
            children[i] = toArrayNode(enc.children()[i]);
        }
        return ArrayNode.of(enc.encodingId(), enc.metadata(), children, enc.bufferIndices());
    }
}
