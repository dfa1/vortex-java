package io.github.dfa1.vortex.writer;

/// Tuning knobs for the Vortex writer.
public record WriteOptions(
        int chunkSize,
        boolean enableZoneMaps,
        double compressionRatioThreshold,
        int allowedCascading,
        boolean globalDict
) {
    /// Default options: global dictionary encoding enabled, no cascading compression.
    public static WriteOptions defaults() {
        return new WriteOptions(65_536, true, 0.90, 0, true);
    }

    /// Enable cascading compression with up to {@code depth} recursive levels.
    /// Depth 0 preserves current first-match behaviour.
    public static WriteOptions cascading(int depth) {
        return new WriteOptions(65_536, true, 0.90, depth, true);
    }

    /// Returns a copy of these options with global dictionary encoding set to {@code enabled}.
    ///
    /// @param enabled {@code true} to enable global dictionary encoding across chunks
    /// @return a new {@code WriteOptions} with the global dict flag updated
    public WriteOptions withGlobalDict(boolean enabled) {
        return new WriteOptions(chunkSize, enableZoneMaps, compressionRatioThreshold, allowedCascading, enabled);
    }
}
