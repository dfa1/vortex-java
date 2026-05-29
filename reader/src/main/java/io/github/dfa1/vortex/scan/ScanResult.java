package io.github.dfa1.vortex.scan;

import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.VortexException;

import java.util.Map;

/// One decoded chunk returned by [ScanIterator].
public record ScanResult(
		long rowCount,
		Map<String, Array> columns
) {
	@SuppressWarnings("unchecked")
	public <T extends Array> T column(String name) {
		Array arr = columns.get(name);
		if (arr == null) {
			throw new VortexException("unknown column: " + name);
		}
		return (T) arr;
	}
}
