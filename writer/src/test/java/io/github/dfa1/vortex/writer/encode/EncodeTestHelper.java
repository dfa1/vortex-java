package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.writer.WriteRegistry;

import java.lang.foreign.Arena;

/// Encode-side test utilities.
///
/// Public so writer/ test trees can reuse it.
public final class EncodeTestHelper {

    private EncodeTestHelper() {
    }

    /// Creates a non-cascading [EncodeContext] using a GC-managed arena and all
    /// service-loaded [EncodingEncoder]s.
    ///
    /// @return a test-suitable [EncodeContext]
    public static EncodeContext testCtx() {
        return EncodeContext.of(Arena.ofAuto(), WriteRegistry.loadAll());
    }

    /// Creates an [EncodeContext] at the default write's cascade depth (3), where the compressor
    /// picks encodings as a [io.github.dfa1.vortex.writer.WriteOptions#defaults()] write does.
    ///
    /// @return a cascading [EncodeContext]
    public static EncodeContext cascadingCtx() {
        return EncodeContext.ofDepth(3, Arena.ofAuto(), WriteRegistry.loadAll());
    }
}
