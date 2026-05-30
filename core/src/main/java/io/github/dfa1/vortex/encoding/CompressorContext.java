package io.github.dfa1.vortex.encoding;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/// Immutable context passed through cascading compression.
///
/// <p>{@code allowedCascading=0} means only terminal encodings (no open children).
/// Each recursive step decrements the depth and adds the current encoding to excluded.
public record CompressorContext(
		int allowedCascading,
		Set<EncodingId> excluded,
		long sampleSeed,
		int minSampleSize,
		double sampleFraction
) {
	public static CompressorContext ofDepth(int depth) {
		return new CompressorContext(depth, Set.of(), 42L, 1024, 0.01);
	}

	public CompressorContext withDecrementedDepth() {
		return new CompressorContext(allowedCascading - 1, excluded, sampleSeed, minSampleSize, sampleFraction);
	}

	public CompressorContext withExcluded(EncodingId id) {
		Set<EncodingId> next = new HashSet<>(excluded);
		next.add(id);
		return new CompressorContext(allowedCascading, Collections.unmodifiableSet(next), sampleSeed, minSampleSize, sampleFraction);
	}
}
