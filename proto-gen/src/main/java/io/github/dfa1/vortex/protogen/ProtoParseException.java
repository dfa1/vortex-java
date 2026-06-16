package io.github.dfa1.vortex.protogen;

/// Thrown when `.proto` source is malformed or uses unsupported syntax.
public final class ProtoParseException extends RuntimeException {

    /// @param message diagnostic
    public ProtoParseException(String message) {
        super(message);
    }
}
