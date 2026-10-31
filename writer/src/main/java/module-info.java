/// Writes Vortex files: encoders, the cascading compressor and the file writer.
// "dfa1" trips the module-name terminal-digit lint; the name has to match the packages (and the published group).
@SuppressWarnings("module")
module io.github.dfa1.vortex.writer {
    requires transitive io.github.dfa1.vortex.core;
    requires io.github.dfa1.vortex.fsst;
    // Optional: only the zstd encoding needs it, and it is resolved when present.
    requires static io.github.dfa1.zstd;

    exports io.github.dfa1.vortex.writer;
    exports io.github.dfa1.vortex.writer.encode;
}
