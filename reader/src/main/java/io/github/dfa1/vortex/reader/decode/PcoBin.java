package io.github.dfa1.vortex.reader.decode;

/// One bin in a pco latent variable: a numerical range [lower, lower + 2^offsetBits).
///
/// `weight` is the bin's count in the tANS table (sum of weights == table size).
/// `lower` is the raw unsigned lower bound (U64 for 64-bit latents).
/// `offsetBits` is the log2 of the range size (0 = single value).
public record PcoBin(int weight, long lower, int offsetBits) {
}
