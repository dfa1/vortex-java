package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.EncodingId;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.writer.WriteRegistry;

import java.lang.foreign.Arena;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/// Encoding context passed to every [EncodingEncoder#encode] and
/// [EncodingEncoder#encodeCascade] call.
///
/// Carries a caller-scoped [Arena] for encode output buffers, a
/// [WriteRegistry] for cross-encoder delegation and extension lookup,
/// and cascading compression parameters (depth, exclusions, sampling) used by the
/// [CascadingCompressor].
///
/// In non-cascading paths, use [#of(Arena, WriteRegistry)] — cascade parameters
/// default to depth 0 with no exclusions.
/// In cascading paths, use [#ofDepth(int, Arena, WriteRegistry)] and let
/// [CascadingCompressor] derive child contexts via [#withDecrementedDepth()]
/// and [#withExcluded(Set)].
///
/// @param arena            the arena to allocate encode output buffers from
/// @param registry         write registry supplying encoder and extension lookup
/// @param allowedCascading remaining cascade depth; 0 means only terminal encodings are considered
/// @param excluded         encoding ids excluded from consideration at the current recursion level
/// @param sampleSeed       random seed used for stratified sampling
/// @param minSampleSize    minimum number of rows to include in a sample
/// @param sampleFraction   fraction of rows to sample when the array is large
/// @param sample           whether the array being encoded is a sample measured during the cascade's
///                         competition rather than real output (Rust's `CompressorContext::is_sample`);
///                         some verdicts only hold on one or the other
public record EncodeContext(
        Arena arena,
        WriteRegistry registry,
        int allowedCascading,
        Set<EncodingId> excluded,
        long sampleSeed,
        int minSampleSize,
        double sampleFraction,
        boolean sample
) {

    /// Smallest sample the cost competition will measure on, matching the Rust reference's
    /// `SAMPLE_SIZE * SAMPLE_COUNT` (64 x 16). Kept a multiple of 1024 for the reason the
    /// reference gives: a sample that is not makes fastlanes bitpacking pad the sampled vector,
    /// which distorts the very size comparison the sample exists to make.
    private static final int MIN_SAMPLE_SIZE = 1024;

    /// Fraction of a large array to sample, matching the reference's "approximately 1%". Every
    /// candidate encoding is run over the sample and all but the winner's result is thrown away,
    /// so this fraction sets the cost of the competition itself.
    private static final double SAMPLE_FRACTION = 0.01;

    /// Creates a non-cascading context (depth 0, no exclusions, default sampling).
    ///
    /// @param arena    the arena to allocate encode output buffers from
    /// @param registry write registry for encoder and extension lookup
    /// @return a new [EncodeContext] ready for non-cascading encoding
    public static EncodeContext of(Arena arena, WriteRegistry registry) {
        return of(arena, registry, Set.of());
    }

    /// Creates a non-cascading context with an initial exclusion set (e.g. encoding ids outside a
    /// [io.github.dfa1.vortex.writer.WriteOptions] edition guard), default sampling.
    ///
    /// @param arena           the arena to allocate encode output buffers from
    /// @param registry        write registry for encoder and extension lookup
    /// @param initialExcluded encoding ids excluded from consideration from the start
    /// @return a new [EncodeContext] ready for non-cascading encoding
    public static EncodeContext of(Arena arena, WriteRegistry registry, Set<EncodingId> initialExcluded) {
        return new EncodeContext(arena, registry, 0, Set.copyOf(initialExcluded), 42L, MIN_SAMPLE_SIZE, SAMPLE_FRACTION, false);
    }

    /// Creates a cascading context with the given depth and default sampling parameters.
    ///
    /// @param depth    maximum allowed cascade depth
    /// @param arena    the arena to allocate encode output buffers from
    /// @param registry write registry for encoder and extension lookup
    /// @return a new [EncodeContext] ready for cascading compression
    public static EncodeContext ofDepth(int depth, Arena arena, WriteRegistry registry) {
        return ofDepth(depth, arena, registry, Set.of());
    }

    /// Creates a cascading context with an initial exclusion set (e.g. encoding ids outside a
    /// [io.github.dfa1.vortex.writer.WriteOptions] edition guard), default sampling.
    ///
    /// [CascadingCompressor] already consults [#excluded] at every candidate-selection site
    /// (including nested competitions like a masked column's validity-bitmap cascade), so seeding
    /// it here makes the edition guard a graceful "skip ineligible candidates and fall back to the
    /// best remaining one," not a hard failure after the fact.
    ///
    /// @param depth           maximum allowed cascade depth
    /// @param arena           the arena to allocate encode output buffers from
    /// @param registry        write registry for encoder and extension lookup
    /// @param initialExcluded encoding ids excluded from consideration from the start
    /// @return a new [EncodeContext] ready for cascading compression
    public static EncodeContext ofDepth(int depth, Arena arena, WriteRegistry registry, Set<EncodingId> initialExcluded) {
        return new EncodeContext(arena, registry, depth, Set.copyOf(initialExcluded), 42L, MIN_SAMPLE_SIZE, SAMPLE_FRACTION, false);
    }

    /// Returns a copy of this context with the cascade depth decremented by one.
    ///
    /// @return a new [EncodeContext] with `allowedCascading` reduced by 1
    public EncodeContext withDecrementedDepth() {
        return new EncodeContext(arena, registry, allowedCascading - 1, excluded, sampleSeed, minSampleSize, sampleFraction, sample);
    }

    /// Returns a copy of this context marked as measuring a sample (Rust's `with_sampling`).
    ///
    /// @return a new [EncodeContext] with [#sample()] set
    public EncodeContext withSampling() {
        return new EncodeContext(arena, registry, allowedCascading, excluded, sampleSeed, minSampleSize, sampleFraction, true);
    }

    /// Whether no further cascade level is allowed (Rust's `finished_cascading`): encodings that
    /// only pay off through a cascaded child skip themselves here.
    ///
    /// @return `true` when [#allowedCascading()] is exhausted
    public boolean finishedCascading() {
        return allowedCascading <= 0;
    }

    /// Returns a copy of this context with the given encoding id added to the excluded set.
    ///
    /// @param id the encoding id to exclude from consideration at this recursion level
    /// @return a new [EncodeContext] with `id` added to the excluded set
    public EncodeContext withExcluded(EncodingId id) {
        return withExcluded(Set.of(id));
    }

    /// Returns a copy of this context with `ids` added to the excluded set. Additive by design: a
    /// child's own exclusions ([ChildSlot#excluded()]) never drop what an ancestor already excluded.
    ///
    /// @param ids the encoding ids to exclude from consideration at this recursion level
    /// @return a new [EncodeContext] with `ids` added to the excluded set, or `this` if `ids` is empty
    public EncodeContext withExcluded(Set<EncodingId> ids) {
        if (excluded.containsAll(ids)) {
            return this;
        }
        Set<EncodingId> next = new HashSet<>(excluded);
        next.addAll(ids);
        return new EncodeContext(arena, registry, allowedCascading, Collections.unmodifiableSet(next), sampleSeed, minSampleSize, sampleFraction, sample);
    }

    /// Returns the encoder registered for `id`.
    ///
    /// @param id the encoding id to look up
    /// @return the registered [EncodingEncoder]
    /// @throws VortexException if no encoder is registered for `id`
    public EncodingEncoder lookupEncoder(EncodingId id) {
        EncodingEncoder enc = registry.encoderMap().get(id);
        if (enc == null) {
            throw new VortexException(id, "no encoder registered for " + id.id());
        }
        return enc;
    }
}
