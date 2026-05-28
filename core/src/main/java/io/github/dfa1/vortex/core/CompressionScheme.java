package io.github.dfa1.vortex.core;

public enum CompressionScheme {
	NONE(0), LZ4(1), ZLIB(2), ZSTD(3);

	public final int code;

	CompressionScheme(int code) {
		this.code = code;
	}

	public static CompressionScheme of(int code) {
		return switch (code) {
			case 0 -> NONE;
			case 1 -> LZ4;
			case 2 -> ZLIB;
			case 3 -> ZSTD;
			default -> throw new IllegalArgumentException("unknown compression code: " + code);
		};
	}
}
