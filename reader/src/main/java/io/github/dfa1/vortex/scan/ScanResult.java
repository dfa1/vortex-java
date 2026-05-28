package io.github.dfa1.vortex.scan;

import io.github.dfa1.vortex.core.Array;

import java.util.Map;

/// One decoded chunk returned by [ScanIterator].
public record ScanResult(
		long rowCount,
		Map<String, Array> columns
) {
}
