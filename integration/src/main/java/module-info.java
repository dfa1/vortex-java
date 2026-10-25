/// Integration tests: Rust interop, vortex-jni comparisons and the cross-format importers/exporters.
///
/// The module has no production code. This descriptor exists so the tests are patched into a named module and failsafe
/// runs on the module path like the library modules it exercises. Dependencies without a module descriptor
/// (vortex-jni, hardwood, Arrow's netty bits, calcite) deliberately stay on the classpath.
// "dfa1" trips the module-name terminal-digit lint; the name has to match the packages (and the published group).
@SuppressWarnings("module")
module io.github.dfa1.vortex.integration {
    requires io.github.dfa1.vortex.core;
    requires io.github.dfa1.vortex.reader;
    requires io.github.dfa1.vortex.writer;
    requires io.github.dfa1.vortex.csv;
    requires io.github.dfa1.vortex.jdbc;
    requires io.github.dfa1.vortex.inspect;
    requires de.siegmar.fastcsv;
    requires java.sql;
    requires java.compiler;
}
