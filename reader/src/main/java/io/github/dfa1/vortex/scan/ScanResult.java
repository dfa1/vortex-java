package io.github.dfa1.vortex.scan;

import io.github.dfa1.vortex.encoding.Array;

import java.util.Map;

/** One decoded chunk returned by {@link ScanIterator}. */
public record ScanResult(
    long               rowCount,
    Map<String, Array> columns
) {}
