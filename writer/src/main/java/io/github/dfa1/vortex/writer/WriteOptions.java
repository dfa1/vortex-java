package io.github.dfa1.vortex.writer;

/// Tuning knobs for the Vortex writer.
public record WriteOptions(
        int chunkSize,
        boolean enableZoneMaps,
        double compressionRatioThreshold,
        int allowedCascading
) {
    public static WriteOptions defaults() {
        return new WriteOptions(65_536, true, 0.90, 0);
    }

    /// Enable cascading compression with up to {@code depth} recursive levels.
    /// Depth 0 preserves current first-match behaviour.
    public static WriteOptions cascading(int depth) {
        return new WriteOptions(65_536, true, 0.90, depth);
    }
}
