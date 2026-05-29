package io.github.dfa1.vortex.core;

import io.github.dfa1.vortex.encoding.EncodingId;

import java.util.Optional;

/// Unrecoverable Vortex error: malformed file, unsupported feature, or encoding failure.
///
/// Carries an optional [EncodingId] so callers can attribute decode failures to a specific
/// encoding without parsing the message.
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
