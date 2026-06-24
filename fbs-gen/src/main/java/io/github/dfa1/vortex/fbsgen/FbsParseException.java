package io.github.dfa1.vortex.fbsgen;

import java.io.Serial;

/// Thrown when a `.fbs` schema cannot be lexed or parsed.
public final class FbsParseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /// @param message human-readable diagnostic, ideally with a 1-based line number
    public FbsParseException(String message) {
        super(message);
    }
}
