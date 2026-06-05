package io.github.dfa1.vortex.encoding;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/// Immutable context passed through cascading compression.
///
/// <p>{@code allowedCascading=0} means only terminal encodings (no open children).
/// Each recursive step decrements the depth and adds the current encoding to excluded.
///
/// @param allowedCascading remaining cascade depth; 0 = only terminal encodings allowed
/// @param excluded         encoding ids excluded from consideration at the current recursion level
/// @param sampleSeed       random seed used for stratified sampling
/// @param minSampleSize    minimum number of rows to include in a sample
/// @param sampleFraction   fraction of rows to sample when the array is large
public record CompressorContext(
		int allowedCascading,
		Set<EncodingId> excluded,
		long sampleSeed,
		int minSampleSize,
		double sampleFraction
) {
	/// Creates a default context with the given cascade depth and sensible sampling defaults.
	///
	/// @param depth maximum allowed cascade depth
	/// @return a new {@link CompressorContext} ready for top-level compression
	public static CompressorContext ofDepth(int depth) {
		return new CompressorContext(depth, Set.of(), 42L, 4096, 0.05);
	}

	/// Returns a copy of this context with the cascade depth decremented by one.
	///
	/// @return a new {@link CompressorContext} with {@code allowedCascading} reduced by 1
	public CompressorContext withDecrementedDepth() {
		return new CompressorContext(allowedCascading - 1, excluded, sampleSeed, minSampleSize, sampleFraction);
	}

	/// Returns a copy of this context with the given encoding id added to the excluded set.
	///
	/// @param id the encoding id to exclude from consideration at this recursion level
	/// @return a new {@link CompressorContext} with {@code id} added to the excluded set
	public CompressorContext withExcluded(EncodingId id) {
		Set<EncodingId> next = new HashSet<>(excluded);
		next.add(id);
		return new CompressorContext(allowedCascading, Collections.unmodifiableSet(next), sampleSeed, minSampleSize, sampleFraction);
	}
}
