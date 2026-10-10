package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.Edition;
import io.github.dfa1.vortex.core.model.EditionFamily;
import io.github.dfa1.vortex.core.model.Editions;
import io.github.dfa1.vortex.core.model.MemorySize;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/// Tuning knobs for the Vortex writer: an immutable configuration built from [#defaults()] or
/// [#cascading(int)] and adjusted with the `withXxx` methods, each returning a copy.
///
/// A plain class rather than a record: it is configuration, not a value — the [#executor()] it
/// carries has no meaningful equality, so neither do the options.
public final class WriteOptions {
    private final boolean enableZoneMaps;
    private final double compressionRatioThreshold;
    private final int allowedCascading;
    private final boolean globalDict;
    private final boolean compact;
    private final MemorySize globalDictMaxRetainedBytes;
    private final Map<EditionFamily, Edition> editions;
    private final Map<ColumnName, ColumnEncoding> columnEncodings;
    private final Executor executor;

    /// Options with no per-column encoding overrides, compressing on the calling thread.
    ///
    /// @param enableZoneMaps             see [#enableZoneMaps()]
    /// @param compressionRatioThreshold  see [#compressionRatioThreshold()]
    /// @param allowedCascading           see [#allowedCascading()]
    /// @param globalDict                 see [#globalDict()]
    /// @param globalDictMaxRetainedBytes see [#globalDictMaxRetainedBytes()]
    /// @param editions                   see [#editions()]
    public WriteOptions(boolean enableZoneMaps, double compressionRatioThreshold, int allowedCascading,
                        boolean globalDict, MemorySize globalDictMaxRetainedBytes,
                        Map<EditionFamily, Edition> editions) {
        this(enableZoneMaps, compressionRatioThreshold, allowedCascading, globalDict, false,
                globalDictMaxRetainedBytes, editions, Map.of(), CALLER_RUNS);
    }

    private WriteOptions(boolean enableZoneMaps, double compressionRatioThreshold, int allowedCascading,
                         boolean globalDict, boolean compact,
                         MemorySize globalDictMaxRetainedBytes,
                         Map<EditionFamily, Edition> editions, Map<ColumnName, ColumnEncoding> columnEncodings,
                         Executor executor) {
        if (compact && allowedCascading == 0) {
            throw new IllegalArgumentException(
                    "compact requires allowedCascading > 0 (Zstd and Pco only compete inside the cascade); "
                            + "use WriteOptions.cascading(depth).withCompact(true)");
        }
        this.enableZoneMaps = enableZoneMaps;
        this.compressionRatioThreshold = compressionRatioThreshold;
        this.allowedCascading = allowedCascading;
        this.globalDict = globalDict;
        this.compact = compact;
        this.globalDictMaxRetainedBytes = globalDictMaxRetainedBytes;
        this.editions = Map.copyOf(editions);
        this.columnEncodings = Map.copyOf(columnEncodings);
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Whether the writer emits per-chunk min/max statistics for zone-map pruning.
    ///
    /// @return `true` if zone maps are written
    public boolean enableZoneMaps() {
        return enableZoneMaps;
    }

    /// The minimum compression ratio (0–1) for an encoding to be accepted.
    ///
    /// @return the compression-ratio threshold
    public double compressionRatioThreshold() {
        return compressionRatioThreshold;
    }

    /// The maximum recursive cascade depth; `0` disables cascading.
    ///
    /// @return the cascade depth
    public int allowedCascading() {
        return allowedCascading;
    }

    /// Whether low-cardinality columns share one dictionary across all chunks.
    ///
    /// @return `true` if global dictionary encoding is enabled
    public boolean globalDict() {
        return globalDict;
    }

    /// Whether the cascade follows Rust's compact preset — see [#withCompact(boolean)].
    ///
    /// @return `true` if Zstd (for text) and Pco (for numbers) compete in the cascade
    public boolean compact() {
        return compact;
    }

    /// The aggregate heap budget for the code arrays global-dictionary candidate columns buffer until
    /// `close()` — see [#withGlobalDictMaxRetainedBytes(MemorySize)].
    ///
    /// @return the global-dict retention budget
    public MemorySize globalDictMaxRetainedBytes() {
        return globalDictMaxRetainedBytes;
    }

    /// The [Edition] enabled per family, gating which encodings the writer may emit — see
    /// [#withEdition(Edition)]. Defaults to the newest frozen `core` edition
    /// ([Editions#CORE_2026_08_3]), the one Rust's default session enables. Empty means the guard is
    /// off ([#withoutEditions()]).
    ///
    /// @return the enabled editions, immutable
    public Map<EditionFamily, Edition> editions() {
        return editions;
    }

    /// Per-column overrides of the default encoder selection — see
    /// [#withColumnEncoding(ColumnName, ColumnEncoding)]; empty by default.
    ///
    /// @return the overrides, immutable
    public Map<ColumnName, ColumnEncoding> columnEncodings() {
        return columnEncodings;
    }

    /// The executor segment compression runs on — see [#withExecutor(Executor)]; by default the
    /// thread calling the writer.
    ///
    /// @return the compression executor
    public Executor executor() {
        return executor;
    }

    /// Runs each task on the calling thread: the writer compresses sequentially.
    private static final Executor CALLER_RUNS = Runnable::run;

    /// Returns a copy of these options compressing segments on `executor`.
    ///
    /// Columns, and chunks within a column, compress independently, so a multi-threaded executor
    /// encodes them concurrently while the writer still appends them to the channel in order: the
    /// file is byte-identical to a sequential write. [java.util.concurrent.ForkJoinPool#commonPool()]
    /// is the natural choice:
    ///
    /// ```java
    /// WriteOptions.defaults().withExecutor(ForkJoinPool.commonPool())
    /// ```
    ///
    /// The writer owns the arrays passed to `writeChunk` until `close()` returns — a caller must not
    /// modify them, as they may still be compressing on another thread. The writer itself stays
    /// single-threaded: call it from one thread at a time.
    ///
    /// @param executor runs the compression tasks; the writer never shuts it down
    /// @return a new `WriteOptions` with the executor set
    public WriteOptions withExecutor(Executor executor) {
        return new WriteOptions(enableZoneMaps, compressionRatioThreshold, allowedCascading, globalDict,
                compact, globalDictMaxRetainedBytes, editions, columnEncodings, executor);
    }

    /// Returns a copy of these options choosing `column`'s encodings by `encoding` instead of the
    /// default cascade, replacing any override already set for it.
    ///
    /// @param column   the column to override, which must be in the schema the options write
    /// @param encoding how the column's encodings are chosen
    /// @return a new `WriteOptions` with the override set
    public WriteOptions withColumnEncoding(ColumnName column, ColumnEncoding encoding) {
        Map<ColumnName, ColumnEncoding> updated = new HashMap<>(columnEncodings);
        updated.put(Objects.requireNonNull(column, "column"), Objects.requireNonNull(encoding, "encoding"));
        return new WriteOptions(enableZoneMaps, compressionRatioThreshold, allowedCascading, globalDict,
                compact, globalDictMaxRetainedBytes, editions, updated, executor);
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
    private static final Map<EditionFamily, Edition> DEFAULT_EDITIONS = Map.of(EditionFamily.CORE, Editions.CORE_2026_08_3);

    /// Default cascade depth: Rust's `MAX_CASCADE`. The Rust writer always runs its cascading
    /// compressor, so a depth-0 default stored e.g. ALP's integers unpacked (#458).
    private static final int DEFAULT_CASCADE_DEPTH = 3;

    /// Default options: global dictionary encoding enabled, cascading compression up to depth 3
    /// (as Rust), no Zstd or Pco, edition guard targeting the latest frozen `core` edition.
    ///
    /// @return default `WriteOptions`
    public static WriteOptions defaults() {
        return cascading(DEFAULT_CASCADE_DEPTH);
    }

    /// Enable cascading compression with up to `depth` recursive levels.
    /// Depth 0 disables cascading: each column takes the first accepting encoder.
    ///
    /// @param depth maximum cascade depth
    /// @return `WriteOptions` with cascading enabled at the given depth
    public static WriteOptions cascading(int depth) {
        return new WriteOptions(true, 0.90, depth, true, DEFAULT_GLOBAL_DICT_MAX_RETAINED_BYTES,
                DEFAULT_EDITIONS);
    }

    /// Returns a copy of these options with zone-map statistics set to `enabled`.
    ///
    /// @param enabled `true` to write per-chunk min/max/sum statistics for zone-map pruning
    /// @return a new `WriteOptions` with the zone-map flag updated
    public WriteOptions withZoneMaps(boolean enabled) {
        return new WriteOptions(enabled, compressionRatioThreshold, allowedCascading, globalDict, compact, globalDictMaxRetainedBytes, editions, columnEncodings, executor);
    }

    /// Returns a copy of these options with global dictionary encoding set to `enabled`.
    ///
    /// @param enabled `true` to enable global dictionary encoding across chunks
    /// @return a new `WriteOptions` with the global dict flag updated
    public WriteOptions withGlobalDict(boolean enabled) {
        return new WriteOptions(enableZoneMaps, compressionRatioThreshold, allowedCascading, enabled, compact, globalDictMaxRetainedBytes, editions, columnEncodings, executor);
    }

    /// Returns a copy of these options with Rust's compact preset set to `enabled`
    /// (`BtrBlocksCompressorBuilder::with_compact`): Zstandard competes for strings and binary, and
    /// Pco for integers and floats, next to the default encodings. Each wins a chunk only where it
    /// makes it smaller.
    ///
    /// On a ClinVar variant summary (4.6M rows, 43 columns, mostly free text) the default cascade
    /// wrote 382 MB and compact about 235 MB.
    ///
    /// Trade-off: both encodings are slower to write and to decode than the structural ones, Pco
    /// the slowest to write (that file took 33 s instead of 24 s). Off by default, as in Rust.
    ///
    /// `enabled=true` requires `allowedCascading() > 0`: both only ever compete inside the cascade.
    ///
    /// @param enabled `true` to enable the compact preset
    /// @return a new `WriteOptions` with the compact flag updated
    /// @throws IllegalArgumentException if `enabled` is `true` and `allowedCascading()` is `0`
    public WriteOptions withCompact(boolean enabled) {
        return new WriteOptions(enableZoneMaps, compressionRatioThreshold, allowedCascading, globalDict,
                enabled, globalDictMaxRetainedBytes, editions, columnEncodings, executor);
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
        return new WriteOptions(enableZoneMaps, compressionRatioThreshold, allowedCascading, globalDict,
                compact, budget, editions, columnEncodings, executor);
    }

    /// Returns a copy of these options with `edition` enabled, replacing any edition already
    /// enabled for `edition.id().family()`. Enabling an edition from a different family than any
    /// currently configured adds to, rather than replaces, the enabled set — a writer may target at
    /// most one edition per family, but multiple families at once (e.g. `core` and `preview`
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
        return new WriteOptions(enableZoneMaps, compressionRatioThreshold, allowedCascading, globalDict,
                compact, globalDictMaxRetainedBytes, updated, columnEncodings, executor);
    }

    /// Returns a copy of these options with the edition guard turned off, the counterpart of Rust's
    /// `VortexWriteOptions::disable_editions()`: every encoding this writer implements may be
    /// emitted, including those in no edition at all (`fastlanes.delta`, `vortex.patched`), which
    /// the cascade then also offers as candidates. A reader is only guaranteed to support the
    /// encodings of the editions it knows, so a file written this way may not be readable by other
    /// Vortex versions or configurations.
    ///
    /// @return a new `WriteOptions` with no edition enabled
    public WriteOptions withoutEditions() {
        return new WriteOptions(enableZoneMaps, compressionRatioThreshold, allowedCascading, globalDict,
                compact, globalDictMaxRetainedBytes, Map.of(), columnEncodings, executor);
    }
}
