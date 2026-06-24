package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ReadRegistry;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/// Fluent builder for [DecodeContext] used in encoding tests.
///
/// Defaults: rowCount=0, segments=[], registry=empty, arena=Arena.global().
///
/// Public so writer/ test trees can reuse via the reader test-jar.
public final class TestDecodeContexts {

    private final ArrayNode node;
    private final DType dtype;
    private long rowCount = 0;
    private MemorySegment[] segments = new MemorySegment[0];
    private ReadRegistry registry = ReadRegistry.empty();
    private Arena arena = Arena.global();

    private TestDecodeContexts(ArrayNode node, DType dtype) {
        this.node = node;
        this.dtype = dtype;
    }

    /// Creates a new builder for the given node and dtype.
    ///
    /// @param node  the array node
    /// @param dtype the logical type
    /// @return a new builder
    public static TestDecodeContexts of(ArrayNode node, DType dtype) {
        return new TestDecodeContexts(node, dtype);
    }

    /// Sets the row count.
    ///
    /// @param n row count
    /// @return this builder
    public TestDecodeContexts rowCount(long n) {
        this.rowCount = n;
        return this;
    }

    /// Sets the segment buffers.
    ///
    /// @param segs segment buffers
    /// @return this builder
    public TestDecodeContexts segments(MemorySegment... segs) {
        this.segments = segs;
        return this;
    }

    /// Sets the registry.
    ///
    /// @param reg registry
    /// @return this builder
    public TestDecodeContexts registry(ReadRegistry reg) {
        this.registry = reg;
        return this;
    }

    /// Sets the arena.
    ///
    /// @param a arena
    /// @return this builder
    public TestDecodeContexts arena(Arena a) {
        this.arena = a;
        return this;
    }

    /// Builds the [DecodeContext].
    ///
    /// @return a new decode context
    public DecodeContext build() {
        return new DecodeContext(node, dtype, rowCount, segments, registry, arena);
    }
}
