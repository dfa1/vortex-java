package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.Edition;
import io.github.dfa1.vortex.core.model.EditionFamily;
import io.github.dfa1.vortex.core.model.Editions;
import io.github.dfa1.vortex.core.model.MemorySize;

import java.util.HashMap;
import java.util.Map;

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
/// @param editions                  the [Edition] enabled per family, gating which
///                                  encodings this writer may emit — see [#withEdition(Edition)]. Defaults to
///                                  the latest frozen `core` edition ([Editions#CORE_2026_08_0]): vortex-java
///                                  implements every `core`-family encoding through `core2026.08.0`, and the
///                                  default cascade never selects an `unstable`-family one, so this is a
///                                  zero-cost guardrail against ever silently widening a file's minimum
///                                  required reader. Deliberately has no "disable the guard" method: an
///                                  `unstable`-family encoding is reached by enabling its edition explicitly
///                                  via [#withEdition(Edition)], not by opting out of the guard altogether.
public record WriteOptions(
        int chunkSize,
        boolean enableZoneMaps,
        double compressionRatioThreshold,
        int allowedCascading,
        boolean globalDict,
        boolean enableZstd,
        MemorySize globalDictMaxRetainedBytes,
        Map<EditionFamily, Edition> editions
) {
    /// Defensively copies `editions` into an immutable map.
    public WriteOptions {
        editions = Map.copyOf(editions);
    }

    /// Default aggregate retention budget (2 GB) for the buffered per-chunk code arrays of global
    /// -dictionary candidate columns. Raised from 256 MB when buffering became cardinality-bounded
    /// (ADR 0021), then from 1 GB (#303): a wide, high-cardinality file (NYC 311, ~30 admitted string
    /// columns × ~37 MB of buffered codes ≈ 1.15 GB) crossed the 1 GB budget and evicted its
    /// highest-cardinality columns to per-chunk dictionaries, repeating their values pool each chunk
    /// (~35 MB larger). 2 GB fits that file with headroom while still bounding the pathological
    /// many-wide-columns risk. Constrained-heap writers can lower it via
    /// [#withGlobalDictMaxRetainedBytes(MemorySize)].
    private static final MemorySize DEFAULT_GLOBAL_DICT_MAX_RETAINED_BYTES = MemorySize.ofGiB(2);

    /// The default edition guard: only the latest frozen `core` edition enabled. See the `editions`
    /// parameter's javadoc above for the safety rationale.
    private static final Map<EditionFamily, Edition> DEFAULT_EDITIONS = Map.of(EditionFamily.CORE, Editions.CORE_2026_08_0);

    /// Default options: global dictionary encoding enabled, no cascading compression, Zstd disabled,
    /// edition guard targeting the latest frozen `core` edition.
    ///
    /// @return default `WriteOptions`
    public static WriteOptions defaults() {
        return new WriteOptions(65_536, true, 0.90, 0, true, false, DEFAULT_GLOBAL_DICT_MAX_RETAINED_BYTES,
                DEFAULT_EDITIONS);
    }

    /// Enable cascading compression with up to `depth` recursive levels.
    /// Depth 0 preserves current first-match behavior.
    ///
    /// @param depth maximum cascade depth
    /// @return `WriteOptions` with cascading enabled at the given depth
    public static WriteOptions cascading(int depth) {
        return new WriteOptions(65_536, true, 0.90, depth, true, false, DEFAULT_GLOBAL_DICT_MAX_RETAINED_BYTES,
                DEFAULT_EDITIONS);
    }

    /// Returns a copy of these options with zone-map statistics set to `enabled`.
    ///
    /// @param enabled `true` to write per-chunk min/max/sum statistics for zone-map pruning
    /// @return a new `WriteOptions` with the zone-map flag updated
    public WriteOptions withZoneMaps(boolean enabled) {
        return new WriteOptions(chunkSize, enabled, compressionRatioThreshold, allowedCascading, globalDict, enableZstd,
                globalDictMaxRetainedBytes, editions);
    }

    /// Returns a copy of these options with global dictionary encoding set to `enabled`.
    ///
    /// @param enabled `true` to enable global dictionary encoding across chunks
    /// @return a new `WriteOptions` with the global dict flag updated
    public WriteOptions withGlobalDict(boolean enabled) {
        return new WriteOptions(chunkSize, enableZoneMaps, compressionRatioThreshold, allowedCascading, enabled, enableZstd,
                globalDictMaxRetainedBytes, editions);
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
                globalDictMaxRetainedBytes, editions);
    }

    /// Returns a copy of these options with the global-dictionary retention budget set to `budget`.
    ///
    /// This is the aggregate byte budget across all global-dictionary candidate columns' buffered
    /// per-chunk code arrays, retained in the heap while the writer waits to build shared dictionaries
    /// at `close()`. It is a secondary safety net behind the per-column cardinality cap (ADR 0021).
    /// Lower it to demote columns to per-chunk encoding sooner (bounding memory on huge files); raise
    /// it on memory-rich hosts to keep more columns dictionary-encoded.
    ///
    /// @param budget aggregate retention budget for buffered global-dict candidate columns
    /// @return a new `WriteOptions` with the global-dict retention budget updated
    public WriteOptions withGlobalDictMaxRetainedBytes(MemorySize budget) {
        return new WriteOptions(chunkSize, enableZoneMaps, compressionRatioThreshold, allowedCascading, globalDict,
                enableZstd, budget, editions);
    }

    /// Returns a copy of these options with `edition` enabled, replacing any edition already
    /// enabled for `edition.id().family()`. Enabling an edition from a different family than any
    /// currently configured adds to, rather than replaces, the enabled set — a writer may target at
    /// most one edition per family, but multiple families at once (e.g. `core` and `unstable`
    /// simultaneously).
    ///
    /// Encoding an id outside the union of every currently-enabled edition's cumulative members
    /// fails the write immediately (see [io.github.dfa1.vortex.writer.VortexWriter]) — the edition
    /// guarantee is checked at write time, never persisted into the file itself.
    ///
    /// @param edition the edition to enable
    /// @return a new `WriteOptions` with `edition` enabled for its family
    public WriteOptions withEdition(Edition edition) {
        Map<EditionFamily, Edition> updated = new HashMap<>(editions);
        updated.put(edition.id().family(), edition);
        return new WriteOptions(chunkSize, enableZoneMaps, compressionRatioThreshold, allowedCascading, globalDict,
                enableZstd, globalDictMaxRetainedBytes, updated);
    }
}
