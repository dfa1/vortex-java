package io.github.dfa1.vortex.encoding;

/// One bin in a pco latent variable: a numerical range [lower, lower + 2^offsetBits).
///
/// {@code weight} is the bin's count in the tANS table (sum of weights == table size).
/// {@code lower} is the raw unsigned lower bound (U64 for 64-bit latents).
/// {@code offsetBits} is the log2 of the range size (0 = single value).
record PcoBin(int weight, long lower, int offsetBits) {
}
