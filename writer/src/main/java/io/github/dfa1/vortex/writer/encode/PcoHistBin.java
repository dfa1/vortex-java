package io.github.dfa1.vortex.writer.encode;

/// One raw histogram bucket produced before bin optimization.
///
/// {@code lowerSortKey} and {@code upperSortKey} are latents XOR'd with {@code Long.MIN_VALUE}
/// so that Java signed comparison gives the same order as unsigned latent comparison.
record PcoHistBin(long lowerSortKey, long upperSortKey, long count) {
}
