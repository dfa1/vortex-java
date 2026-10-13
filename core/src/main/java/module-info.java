/// Shared model, wire codecs and SIMD kernels used by the reader and the writer.
// "dfa1" trips the module-name terminal-digit lint; the name has to match the packages (and the published group).
@SuppressWarnings("module")
module io.github.dfa1.vortex.core {
    // Opt-in at run time (--add-modules jdk.incubator.vector); the auto-vectorized kernels are the default.
    requires static jdk.incubator.vector;
    // @Generated on the fbs/proto codecs; compile-time only.
    requires static java.compiler;

    exports io.github.dfa1.vortex.core.model;
    exports io.github.dfa1.vortex.core.error;
    exports io.github.dfa1.vortex.core.io;
    exports io.github.dfa1.vortex.core.compute;
    exports io.github.dfa1.vortex.core.simd;
    // Generated wire codecs: qualified so they stay out of the public API (and out of the exports lint).
    exports io.github.dfa1.vortex.core.fbs to io.github.dfa1.vortex.reader, io.github.dfa1.vortex.writer, io.github.dfa1.vortex.inspect;
    exports io.github.dfa1.vortex.core.proto to io.github.dfa1.vortex.reader, io.github.dfa1.vortex.writer, io.github.dfa1.vortex.integration;
}
