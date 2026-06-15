package io.github.dfa1.vortex.core;

import io.github.dfa1.vortex.encoding.EncodingId;

import java.util.Optional;

/// Unrecoverable Vortex error: malformed file, unsupported feature, or encoding failure.
///
/// **Non-recoverable contract:** once thrown, the underlying file or stream is
/// in an indeterminate state. Callers must propagate this exception — do not catch-and-swallow,
/// do not retry on the same input. The correct response is to abort the read, surface the error,
/// and close the {@code VortexFile}.
///
/// Carries an optional {@link EncodingId} for diagnostic attribution; it is not intended
/// for recovery logic.
public final class VortexException extends RuntimeException {

    /// The encoding that raised this exception, or {@code null} if not attributed to a specific encoding.
    private final EncodingId encodingId;

    /// Creates a {@code VortexException} with the given message and no associated encoding.
    ///
    /// @param message human-readable error description
    public VortexException(String message) {
        super(message);
        this.encodingId = null;
    }

    /// Creates a {@code VortexException} with the given message and cause, and no associated encoding.
    ///
    /// @param message human-readable error description
    /// @param cause   the underlying cause
    public VortexException(String message, Throwable cause) {
        super(message, cause);
        this.encodingId = null;
    }

    /// Creates a {@code VortexException} attributed to the given encoding.
    ///
    /// @param encodingId encoding responsible for this error
    /// @param message    human-readable error description (prefixed with the encoding id)
    public VortexException(EncodingId encodingId, String message) {
        super(prefix(encodingId) + message);
        this.encodingId = encodingId;
    }

    /// Creates a {@code VortexException} attributed to the given encoding, with a cause.
    ///
    /// @param encodingId encoding responsible for this error
    /// @param message    human-readable error description (prefixed with the encoding id)
    /// @param cause      the underlying cause
    public VortexException(EncodingId encodingId, String message, Throwable cause) {
        super(prefix(encodingId) + message, cause);
        this.encodingId = encodingId;
    }

    private static String prefix(EncodingId encodingId) {
        return encodingId == null ? "" : encodingId.id() + ": ";
    }

    /// Returns the encoding that raised this exception, if known.
    ///
    /// @return an {@link Optional} containing the {@link EncodingId}, or empty if not attributed
    public Optional<EncodingId> encodingId() {
        return Optional.ofNullable(encodingId);
    }
}
