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
/// @param globalDictMaxRetainedBytes aggregate byte budget for the buffered per-chunk code arrays all
///                                  global-dictionary candidate columns may retain in the heap while
///                                  waiting for `close()` (default 1 GB). A shared dictionary must see
///                                  every chunk before it can be built; buffering is cardinality
///                                  -bounded (ADR 0021), so each candidate holds a capped value-to-code
///                                  map plus cheap ~2 B/row code arrays rather than raw values. This
///                                  budget is a secondary safety net over the aggregate code-array
///                                  bytes: when the running total across all candidate columns crosses
///                                  it, the largest-retained columns are demoted to per-chunk encoding
///                                  until back under it. Because codes are ~35–45× smaller than raw
///                                  strings, this rarely fires at the 1 GB default; the primary
///                                  demotion signal is now a column's actual cardinality exceeding the
///                                  cap. Trade-off: demoted columns lose the shared-dictionary size
///                                  benefit; raise the budget on memory-rich hosts to keep more wide,
///                                  low-cardinality columns dictionary-encoded.
public record WriteOptions(
        int chunkSize,
        boolean enableZoneMaps,
        double compressionRatioThreshold,
        int allowedCascading,
        boolean globalDict,
        boolean enableZstd,
        long globalDictMaxRetainedBytes
) {
    /// Default aggregate retention budget (2 GB) for the buffered per-chunk code arrays of global
    /// -dictionary candidate columns. Raised from 256 MB when buffering became cardinality-bounded
    /// (ADR 0021), then from 1 GB (#303): a wide, high-cardinality file (NYC 311, ~30 admitted string
    /// columns × ~37 MB of buffered codes ≈ 1.15 GB) crossed the 1 GB budget and evicted its
    /// highest-cardinality columns to per-chunk dictionaries, repeating their values pool each chunk
    /// (~35 MB larger). 2 GB fits that file with headroom while still bounding the pathological
    /// many-wide-columns risk. Constrained-heap writers can lower it via
    /// [#withGlobalDictMaxRetainedBytes(long)].
    private static final long DEFAULT_GLOBAL_DICT_MAX_RETAINED_BYTES = 2L * 1024 * 1024 * 1024;

    /// Default options: global dictionary encoding enabled, no cascading compression, Zstd disabled.
    ///
    /// @return default `WriteOptions`
    public static WriteOptions defaults() {
        return new WriteOptions(65_536, true, 0.90, 0, true, false, DEFAULT_GLOBAL_DICT_MAX_RETAINED_BYTES);
    }

    /// Enable cascading compression with up to `depth` recursive levels.
    /// Depth 0 preserves current first-match behavior.
    ///
    /// @param depth maximum cascade depth
    /// @return `WriteOptions` with cascading enabled at the given depth
    public static WriteOptions cascading(int depth) {
        return new WriteOptions(65_536, true, 0.90, depth, true, false, DEFAULT_GLOBAL_DICT_MAX_RETAINED_BYTES);
    }

    /// Returns a copy of these options with zone-map statistics set to `enabled`.
    ///
    /// @param enabled `true` to write per-chunk min/max/sum statistics for zone-map pruning
    /// @return a new `WriteOptions` with the zone-map flag updated
    public WriteOptions withZoneMaps(boolean enabled) {
        return new WriteOptions(chunkSize, enabled, compressionRatioThreshold, allowedCascading, globalDict, enableZstd,
                globalDictMaxRetainedBytes);
    }

    /// Returns a copy of these options with global dictionary encoding set to `enabled`.
    ///
    /// @param enabled `true` to enable global dictionary encoding across chunks
    /// @return a new `WriteOptions` with the global dict flag updated
    public WriteOptions withGlobalDict(boolean enabled) {
        return new WriteOptions(chunkSize, enableZoneMaps, compressionRatioThreshold, allowedCascading, enabled, enableZstd,
                globalDictMaxRetainedBytes);
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
        return new WriteOptions(chunkSize, enableZoneMaps, compressionRatioThreshold, allowedCascading, globalDict, enabled,
                globalDictMaxRetainedBytes);
    }

    /// Returns a copy of these options with the global-dictionary retention budget set to `budgetBytes`.
    ///
    /// This is the aggregate byte budget across all global-dictionary candidate columns' buffered
    /// per-chunk code arrays, retained in the heap while the writer waits to build shared dictionaries
    /// at `close()`. It is a secondary safety net behind the per-column cardinality cap (ADR 0021).
    /// Lower it to demote columns to per-chunk encoding sooner (bounding memory on huge files); raise
    /// it on memory-rich hosts to keep more columns dictionary-encoded.
    ///
    /// @param budgetBytes aggregate retention budget in bytes for buffered global-dict candidate columns
    /// @return a new `WriteOptions` with the global-dict retention budget updated
    public WriteOptions withGlobalDictMaxRetainedBytes(long budgetBytes) {
        return new WriteOptions(chunkSize, enableZoneMaps, compressionRatioThreshold, allowedCascading, globalDict,
                enableZstd, budgetBytes);
    }
}
