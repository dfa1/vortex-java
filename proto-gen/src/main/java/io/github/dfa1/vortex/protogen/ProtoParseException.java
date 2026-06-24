package io.github.dfa1.vortex.protogen;

import java.io.Serial;

/// Thrown when `.proto` source is malformed or uses unsupported syntax.
public final class ProtoParseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /// @param message diagnostic
    public ProtoParseException(String message) {
        super(message);
    }
}
