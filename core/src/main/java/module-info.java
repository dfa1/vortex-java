/// Vortex core module: shared type model, error contract, low-level I/O, compute kernels, and the
/// generated FlatBuffers/Protobuf wire codecs.
///
/// The model, error, and I/O packages form the public surface shared with downstream tooling. The
/// compute kernels and the generated `fbs`/`proto` codecs are internal plumbing, exported only to
/// the reader and writer modules.
// "module" lint suppressed for two unavoidable warnings under the zero-warning policy:
// the group-derived name component `dfa1` has a terminal digit, and the qualified-export targets
// (reader/writer) are not visible when this module is compiled standalone in the reactor.
@SuppressWarnings("module")
module io.github.dfa1.vortex.core {
    // Compile-only: generated proto records carry @javax.annotation.processing.Generated
    // (SOURCE retention, so not needed at runtime).
    requires static java.compiler;

    // Public API.
    exports io.github.dfa1.vortex.core.model;
    exports io.github.dfa1.vortex.core.error;
    exports io.github.dfa1.vortex.core.io;

    // Internal compute kernels and generated wire codecs: consumed only by reader/writer (modular
    // path). Downstream non-modular tooling like the inspector reads them via the classpath, where
    // these directives are ignored.
    exports io.github.dfa1.vortex.core.compute to io.github.dfa1.vortex.reader, io.github.dfa1.vortex.writer;
    exports io.github.dfa1.vortex.core.fbs to io.github.dfa1.vortex.reader, io.github.dfa1.vortex.writer;
    exports io.github.dfa1.vortex.core.proto to io.github.dfa1.vortex.reader, io.github.dfa1.vortex.writer;
}
