package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.Arena;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/// Encoding context passed to every {@link Encoding#encode} and {@link Encoding#encodeCascade} call.
///
/// <p>Carries a caller-scoped {@link Arena} for encode output buffers, an
/// {@link EncodingRegistry} for cross-encoder delegation, and cascading compression
/// parameters (depth, exclusions, sampling) used by {@link CascadingCompressor}.
///
/// <p>In non-cascading paths, use {@link #of(Arena, EncodingRegistry)} — cascade
/// parameters default to depth 0 with no exclusions.
/// In cascading paths, use {@link #ofDepth(int, Arena, EncodingRegistry)} and let
/// {@link CascadingCompressor} derive child contexts via {@link #withDecrementedDepth()}
/// and {@link #withExcluded(EncodingId)}.
///
/// @param arena            the arena to allocate encode output buffers from
/// @param encodings        the registry used for {@link #lookupEncoding} calls
/// @param allowedCascading remaining cascade depth; 0 means only terminal encodings are considered
/// @param excluded         encoding ids excluded from consideration at the current recursion level
/// @param sampleSeed       random seed used for stratified sampling
/// @param minSampleSize    minimum number of rows to include in a sample
/// @param sampleFraction   fraction of rows to sample when the array is large
public record EncodeContext(
		Arena arena,
		EncodingRegistry encodings,
		int allowedCascading,
		Set<EncodingId> excluded,
		long sampleSeed,
		int minSampleSize,
		double sampleFraction
) {

	/// Creates a non-cascading context (depth 0, no exclusions, default sampling).
	///
	/// @param arena     the arena to allocate encode output buffers from
	/// @param encodings the registry used for {@link #lookupEncoding} calls
	/// @return a new {@link EncodeContext} ready for non-cascading encoding
	public static EncodeContext of(Arena arena, EncodingRegistry encodings) {
		return new EncodeContext(arena, encodings, 0, Set.of(), 42L, 4096, 0.05);
	}

	/// Creates a cascading context with the given depth and default sampling parameters.
	///
	/// @param depth     maximum allowed cascade depth
	/// @param arena     the arena to allocate encode output buffers from
	/// @param encodings the registry used for {@link #lookupEncoding} calls
	/// @return a new {@link EncodeContext} ready for cascading compression
	public static EncodeContext ofDepth(int depth, Arena arena, EncodingRegistry encodings) {
		return new EncodeContext(arena, encodings, depth, Set.of(), 42L, 4096, 0.05);
	}

	/// Returns a copy of this context with the cascade depth decremented by one.
	///
	/// @return a new {@link EncodeContext} with {@code allowedCascading} reduced by 1
	public EncodeContext withDecrementedDepth() {
		return new EncodeContext(arena, encodings, allowedCascading - 1, excluded, sampleSeed, minSampleSize, sampleFraction);
	}

	/// Returns a copy of this context with the given encoding id added to the excluded set.
	///
	/// @param id the encoding id to exclude from consideration at this recursion level
	/// @return a new {@link EncodeContext} with {@code id} added to the excluded set
	public EncodeContext withExcluded(EncodingId id) {
		Set<EncodingId> next = new HashSet<>(excluded);
		next.add(id);
		return new EncodeContext(arena, encodings, allowedCascading, Collections.unmodifiableSet(next), sampleSeed, minSampleSize, sampleFraction);
	}

	/// Returns the encoding registered for {@code id}.
	///
	/// @param id the encoding id to look up
	/// @return the registered {@link Encoding}
	/// @throws VortexException if no encoding is registered for {@code id}
	public Encoding lookupEncoding(EncodingId id) {
		Encoding enc = encodings.lookup(id);
		if (enc == null) {
			throw new VortexException(id, "no encoding registered for " + id.id());
		}
		return enc;
	}
}
