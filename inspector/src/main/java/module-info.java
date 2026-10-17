/// Dumps a Vortex file's footer, layout tree and per-column statistics.
// "dfa1" trips the module-name terminal-digit lint; the name has to match the packages (and the published group).
@SuppressWarnings("module")
module io.github.dfa1.vortex.inspect {
    requires transitive io.github.dfa1.vortex.reader;

    exports io.github.dfa1.vortex.inspect;
}
