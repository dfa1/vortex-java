package io.github.dfa1.vortex.core;

import io.github.dfa1.vortex.encoding.EncodingId;

import java.util.Optional;

/// Unrecoverable Vortex error: malformed file, unsupported feature, or encoding failure.
///
/// <p><strong>Non-recoverable contract:</strong> once thrown, the underlying file or stream is
/// in an indeterminate state. Callers must propagate this exception — do not catch-and-swallow,
/// do not retry on the same input. The correct response is to abort the read, surface the error,
/// and close the {@code VortexFile}.
///
/// <p>Carries an optional {@link EncodingId} for diagnostic attribution; it is not intended
/// for recovery logic.
public final class VortexException extends RuntimeException {

	private final EncodingId encodingId;

	public VortexException(String message) {
		super(message);
		this.encodingId = null;
	}

	public VortexException(String message, Throwable cause) {
		super(message, cause);
		this.encodingId = null;
	}

	public VortexException(EncodingId encodingId, String message) {
		super(prefix(encodingId) + message);
		this.encodingId = encodingId;
	}

	public VortexException(EncodingId encodingId, String message, Throwable cause) {
		super(prefix(encodingId) + message, cause);
		this.encodingId = encodingId;
	}

	public Optional<EncodingId> encodingId() {
		return Optional.ofNullable(encodingId);
	}

	private static String prefix(EncodingId encodingId) {
		return encodingId == null ? "" : encodingId.id() + ": ";
	}
}
