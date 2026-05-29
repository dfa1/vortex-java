package io.github.dfa1.vortex.core;

import io.github.dfa1.vortex.encoding.CodecId;

import java.util.Optional;

/// Unrecoverable Vortex error: malformed file, unsupported feature, or codec failure.
///
/// Carries an optional [CodecId] so callers can attribute decode failures to a specific
/// encoding without parsing the message.
public final class VortexException extends RuntimeException {

	private final CodecId codecId;

	public VortexException(String message) {
		super(message);
		this.codecId = null;
	}

	public VortexException(String message, Throwable cause) {
		super(message, cause);
		this.codecId = null;
	}

	public VortexException(CodecId codecId, String message) {
		super(prefix(codecId) + message);
		this.codecId = codecId;
	}

	public VortexException(CodecId codecId, String message, Throwable cause) {
		super(prefix(codecId) + message, cause);
		this.codecId = codecId;
	}

	public Optional<CodecId> codecId() {
		return Optional.ofNullable(codecId);
	}

	private static String prefix(CodecId codecId) {
		return codecId == null ? "" : codecId.id() + ": ";
	}
}
