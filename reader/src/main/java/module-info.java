/// Reads Vortex files: memory-maps the file, decodes the layout tree and encodings into arrays.
// "dfa1" trips the module-name terminal-digit lint; the name has to match the packages (and the published group).
@SuppressWarnings("module")
module io.github.dfa1.vortex.reader {
    requires transitive io.github.dfa1.vortex.core;
    // LazyFsstVarBinArray exposes the fsst Decompressor.
    requires transitive io.github.dfa1.vortex.fsst;
    // Optional: only the zstd encoding needs it, and it is resolved when present.
    requires static io.github.dfa1.zstd;
    // VortexHttpReader exposes HttpClient.
    requires transitive java.net.http;

    exports io.github.dfa1.vortex.reader;
    exports io.github.dfa1.vortex.reader.array;
    // RowFilter exposes Predicate.
    exports io.github.dfa1.vortex.reader.compute;
    exports io.github.dfa1.vortex.reader.decode;
    exports io.github.dfa1.vortex.reader.extension;
    exports io.github.dfa1.vortex.reader.layout;
}
