/// CSV import and export for Vortex files.
// "dfa1" trips the module-name terminal-digit lint; the name has to match the packages (and the published group).
@SuppressWarnings("module")
module io.github.dfa1.vortex.csv {
    requires transitive io.github.dfa1.vortex.reader;
    requires transitive io.github.dfa1.vortex.writer;
    requires de.siegmar.fastcsv;

    exports io.github.dfa1.vortex.csv;
}
