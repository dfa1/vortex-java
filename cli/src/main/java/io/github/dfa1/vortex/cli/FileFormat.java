package io.github.dfa1.vortex.cli;

import java.util.Optional;

/// The file formats `import`/`export` recognize by extension. The single source of truth for
/// what an extension means — no other file in this module spells out `.csv`/`.parquet`/`.vortex`
/// or their lengths as string literals.
enum FileFormat {

    CSV(".csv"),
    PARQUET(".parquet"),
    VORTEX(".vortex");

    private final String extension;

    FileFormat(String extension) {
        this.extension = extension;
    }

    String extension() {
        return extension;
    }

    boolean matches(String fileName) {
        return fileName.endsWith(extension);
    }

    /// Resolves `fileName`'s format from its extension.
    ///
    /// @param fileName a file name or URL path, e.g. `"data.parquet"`
    /// @return the matching format, or empty if none of `.csv`/`.parquet`/`.vortex` matches
    static Optional<FileFormat> of(String fileName) {
        for (FileFormat format : values()) {
            if (format.matches(fileName)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
