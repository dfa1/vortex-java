package io.github.dfa1.vortex.encoding;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/// Fluent builder for {@link DecodeContext} used in encoding tests.
/// Defaults: rowCount=0, segments=[], registry=empty, arena=Arena.global().
final class TestDecodeContexts {

    private final ArrayNode node;
    private final io.github.dfa1.vortex.core.DType dtype;
    private long rowCount = 0;
    private MemorySegment[] segments = new MemorySegment[0];
    private Registry registry = Registry.empty();
    private Arena arena = Arena.global();

    private TestDecodeContexts(ArrayNode node, io.github.dfa1.vortex.core.DType dtype) {
        this.node = node;
        this.dtype = dtype;
    }

    static TestDecodeContexts of(ArrayNode node, io.github.dfa1.vortex.core.DType dtype) {
        return new TestDecodeContexts(node, dtype);
    }

    TestDecodeContexts rowCount(long n) {
        this.rowCount = n;
        return this;
    }

    TestDecodeContexts segments(MemorySegment... segs) {
        this.segments = segs;
        return this;
    }

    TestDecodeContexts registry(Registry reg) {
        this.registry = reg;
        return this;
    }

    TestDecodeContexts arena(Arena a) {
        this.arena = a;
        return this;
    }

    DecodeContext build() {
        return new DecodeContext(node, dtype, rowCount, segments, registry, arena);
    }
}
