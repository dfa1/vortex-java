package io.github.dfa1.vortex.cli;

import java.nio.file.Path;

/// A file or URL path's last segment, typed around its [FileFormat]. Centralizes the
/// extension parsing/swapping `import`/`export` both need — replacing a scattered set of
/// `endsWith(".xxx")` checks and hand-counted `substring(0, name.length() - N)` suffix strips.
///
/// @param value the raw name, e.g. `"data.parquet"` or a URL's last `/`-segment
record FileName(String value) {

    /// The name of `path`'s final component, e.g. `FileName.of(Path.of("a/data.csv"))` is
    /// `FileName("data.csv")`.
    static FileName of(Path path) {
        return new FileName(path.getFileName().toString());
    }

    /// Whether this name ends in `format`'s extension.
    boolean is(FileFormat format) {
        return format.matches(value);
    }

    /// Swaps this name's extension for `target`'s, stripping any *different* known extension
    /// first — `"data.csv".withFormat(VORTEX)` and `"data.parquet".withFormat(VORTEX)` both give
    /// `"data.vortex"`. A name with no known extension, or one already in `target`'s format, is
    /// never stripped — only appended to — so the result always differs from `value`: a caller
    /// deriving a default output name from an input name can never get back the input's own name.
    String withFormat(FileFormat target) {
        String stem = FileFormat.of(value)
                              .filter(current -> current != target)
                              .map(current -> value.substring(0, value.length() - current.extension().length()))
                              .orElse(value);
        return stem + target.extension();
    }
}
