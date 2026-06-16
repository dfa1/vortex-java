package io.github.dfa1.vortex.writer;

/// Tuning knobs for the Vortex writer.
///
/// @param chunkSize                 target row count per chunk (default 65 536)
/// @param enableZoneMaps            write per-chunk min/max statistics for zone-map pruning
/// @param compressionRatioThreshold minimum compression ratio for an encoding to be accepted (0–1)
/// @param allowedCascading          maximum recursive cascade depth; 0 = no cascading
/// @param globalDict                build one shared dictionary across all chunks for low-cardinality columns
/// @param enableZstd                add Zstandard to the cascade codec list
///                                  When `true`, Zstd competes with structural encodings (ALP, bitpack, etc.)
///                                  on every chunk and wins when it produces smaller output — typically reducing
///                                  file size by 10–15% on real-world datasets compared to ALP+bitpack alone.
///                                  Trade-off: Zstd decompression is ~6× slower than ALP decode;
///                                  prefer the default (`false`) for read-heavy workloads.
public record WriteOptions(
        int chunkSize,
        boolean enableZoneMaps,
        double compressionRatioThreshold,
        int allowedCascading,
        boolean globalDict,
        boolean enableZstd
) {
    /// Default options: global dictionary encoding enabled, no cascading compression, Zstd disabled.
    ///
    /// @return default `WriteOptions`
    public static WriteOptions defaults() {
        return new WriteOptions(65_536, true, 0.90, 0, true, false);
    }

    /// Enable cascading compression with up to `depth` recursive levels.
    /// Depth 0 preserves current first-match behaviour.
    ///
    /// @param depth maximum cascade depth
    /// @return `WriteOptions` with cascading enabled at the given depth
    public static WriteOptions cascading(int depth) {
        return new WriteOptions(65_536, true, 0.90, depth, true, false);
    }

    /// Returns a copy of these options with global dictionary encoding set to `enabled`.
    ///
    /// @param enabled `true` to enable global dictionary encoding across chunks
    /// @return a new `WriteOptions` with the global dict flag updated
    public WriteOptions withGlobalDict(boolean enabled) {
        return new WriteOptions(chunkSize, enableZoneMaps, compressionRatioThreshold, allowedCascading, enabled, enableZstd);
    }

    /// Returns a copy of these options with Zstandard compression set to `enabled`.
    ///
    /// When enabled, Zstd is added to the cascade codec list and competes with structural encodings
    /// (ALP, bitpack, FOR, etc.) on every chunk. Zstd typically wins on high-cardinality numeric columns
    /// (e.g. `fare_amount`, `total_amount`), reducing file size by 10–15%.
    ///
    /// Trade-off: Zstd decompression is ~6× slower than ALP reconstruction or bitpack unpack.
    /// Use `false` (the default) for read-heavy or latency-sensitive workloads.
    ///
    /// @param enabled `true` to enable Zstd in the compression cascade
    /// @return a new `WriteOptions` with the Zstd flag updated
    public WriteOptions withZstd(boolean enabled) {
        return new WriteOptions(chunkSize, enableZoneMaps, compressionRatioThreshold, allowedCascading, globalDict, enabled);
    }
}
