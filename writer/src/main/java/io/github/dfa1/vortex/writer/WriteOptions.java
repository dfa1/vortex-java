package io.github.dfa1.vortex.writer;

/// Tuning knobs for the Vortex writer.
public record WriteOptions(
    int     chunkSize,
    boolean enableZoneMaps,
    double  compressionRatioThreshold
) {
    public static WriteOptions defaults() {
        return new WriteOptions(65_536, true, 0.90);
    }
}
