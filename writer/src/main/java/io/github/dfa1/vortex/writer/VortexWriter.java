package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.writer.encode.DateTimePartsData;
import io.github.dfa1.vortex.writer.encode.FixedSizeListData;
import io.github.dfa1.vortex.writer.encode.ListData;
import io.github.dfa1.vortex.writer.encode.ListViewData;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.Edition;
import io.github.dfa1.vortex.core.model.EditionFamily;
import io.github.dfa1.vortex.core.model.Editions;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.writer.encode.EncodeContext;
import io.github.dfa1.vortex.writer.encode.EncodeNode;
import io.github.dfa1.vortex.writer.encode.EncodeResult;
import io.github.dfa1.vortex.writer.encode.EncodedBuffer;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.StructData;
import io.github.dfa1.vortex.writer.encode.StructEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.EncodingEncoder;
import io.github.dfa1.vortex.writer.encode.AlpEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.AlpRdEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.BitpackedEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.BoolEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.CascadingCompressor;
import io.github.dfa1.vortex.writer.encode.ConstantEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DateExtensionEncoder;
import io.github.dfa1.vortex.writer.encode.DateTimePartsEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.TimeExtensionEncoder;
import io.github.dfa1.vortex.writer.encode.TimestampExtensionEncoder;
import io.github.dfa1.vortex.writer.encode.UuidExtensionEncoder;
import io.github.dfa1.vortex.writer.encode.DecimalBytePartsEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DecimalEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DeltaEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DictEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ZigZagEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ExtEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FixedSizeListEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FrameOfReferenceEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FsstEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.OnPairEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ListEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.MaskedEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.PrimitiveEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.RleEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.RunEndEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.SequenceEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.SparseEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.VarBinEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ZstdEncodingEncoder;
import io.github.dfa1.vortex.core.fbs.FbsArraySpec;
import io.github.dfa1.vortex.core.fbs.FbsFooter;
import io.github.dfa1.vortex.core.fbs.FbsLayout;
import io.github.dfa1.vortex.core.fbs.FbsLayoutSpec;
import io.github.dfa1.vortex.core.fbs.FbsPostscript;
import io.github.dfa1.vortex.core.fbs.FbsPostscriptSegment;
import io.github.dfa1.vortex.core.fbs.FbsSegmentSpec;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/// Writes a Vortex file.
///
/// Usage:
/// ```java
/// var schema = DType.structBuilder()
///         .field(ColumnName.of("id"), DType.I64)
///         .field(ColumnName.of("value"), DType.F64)
///         .build();
/// try (var channel = FileChannel.open(path, CREATE, WRITE);
///      var writer = VortexWriter.create(channel, schema, WriteOptions.defaults())) {
///     writer.writeChunk(Map.of(ColumnName.of("id"), idArray, ColumnName.of("value"), valueArray));
/// }
/// ```
///
/// With global dictionary encoding enabled (the default), candidate columns are buffered in the heap
/// until `close()` so a shared dictionary can span every chunk. Buffering is cardinality-bounded
/// (ADR 0021): each candidate retains a deduplicated value-to-code map capped at
/// `GLOBAL_DICT_MAX_CARDINALITY` plus cheap per-chunk code arrays, so retained memory scales with a
/// column's distinct-value count and row count — not its raw byte size. A column whose distinct set
/// would exceed the cap demotes to per-chunk encoding immediately. [WriteOptions#globalDictMaxRetainedBytes()]
/// (default 1 GB) remains a secondary safety net over the aggregate code-array bytes.
public final class VortexWriter implements Closeable {

    // Indices into layout_specs list in the FbsFooter
    private static final int LAYOUT_FLAT = 0;
    private static final int LAYOUT_CHUNKED = 1;
    private static final int LAYOUT_STRUCT = 2;
    private static final int LAYOUT_DICT = 3;
    private static final int LAYOUT_ZONED = 4;

    private static final List<EncodingEncoder> DEFAULT_CODECS = List.of(
            new AlpEncodingEncoder(), new PrimitiveEncodingEncoder(), new BoolEncodingEncoder(),
            new DictEncodingEncoder(), new VarBinEncodingEncoder(), new ExtEncodingEncoder(),
            new FixedSizeListEncodingEncoder(), new ListEncodingEncoder(), new DecimalEncodingEncoder());

    // Base cascade codec list — no Zstd. Zstd is appended (before PrimitiveEncoding) when
    // WriteOptions.enableZstd() is true. See WriteOptions.withZstd(boolean) for the tradeoff.

    private final WritableByteChannel channel;
    private final DType.Struct schema;
    private final WriteOptions options;
    private final List<EncodingEncoder> encodings;
    private final WriteRegistry defaultRegistry;
    private final List<EncodingEncoder> cascadeCodecs;
    private final WriteRegistry cascadeRegistry;
    private final List<SegRef> segs = new ArrayList<>();
    private final Map<ColumnName, List<ChunkRef>> colChunks = new LinkedHashMap<>();
    private final Map<EncodingId, Integer> encodingIdx = new LinkedHashMap<>();
    // Edition guard (issue #301): the cumulative member set of every WriteOptions#editions()
    // family enabled for this writer, or empty when no edition is configured (guard off).
    // editionExcluded is its complement over every well-known encoding id plus the custom encoders this writer holds
    // (encodings + cascadeCodecs) - seeded into every EncodeContext's initial `excluded` set so
    // CascadingCompressor's existing per-candidate exclusion check (already consulted at every
    // selection site, including nested competitions like a masked column's validity-bitmap
    // cascade) skips ineligible candidates and gracefully falls back, rather than the guard only
    // discovering a violation after the fact. registerEncodingIds still checks editionAllowed
    // directly as a backstop for paths that select an encoding without consulting excluded at all
    // (e.g. a forced encodingOverride, or MaskedEncodingEncoder#encodeValidity's own top-level
    // Sparse/RunEnd/Rle/Bool choice, which are all core-family and so never actually trip this in
    // practice - see adr/0023-vortex-editions-adoption.md).
    private final Set<EncodingId> editionAllowed;
    private final Set<EncodingId> editionExcluded;
    private long bytesWritten = 0;

    // Global dict state: columns detected as low-cardinality on their first chunk are buffered here
    // instead of encoded per-chunk. Buffering is cardinality-bounded (ADR 0021): rather than retain
    // raw values, each candidate holds a deduplicated value->code map (capped at
    // GLOBAL_DICT_MAX_CARDINALITY) plus cheap per-chunk code arrays. Flushed in close() as one Dict
    // layout. When a column's distinct set would exceed the cap mid-file, it demotes immediately.
    private final Set<ColumnName> dictCandidates = new LinkedHashSet<>();
    private final Map<ColumnName, DictColumnState> dictStates = new LinkedHashMap<>();
    private final Map<ColumnName, DictColRef> dictColRefs = new LinkedHashMap<>();
    // Code-array bytes retained per global-dict-candidate column, and their running sum; together
    // they guard the aggregate memory budget (dictRetainedBudget) as a secondary safety net (ADR
    // 0021) so no set of mis-detected columns can pin the heap. Now that codes cost ~2 B/row instead
    // of raw values, this budget rarely fires; when the sum crosses it, the largest columns demote.
    private final Map<ColumnName, Long> dictRetainedBytes = new LinkedHashMap<>();
    private long dictRetainedTotal = 0;
    // Effective aggregate global-dict retention budget, configured via
    // WriteOptions.globalDictMaxRetainedBytes(); see that field's javadoc for the rationale.
    private final long dictRetainedBudget;
    private boolean firstChunkSeen = false;

    // Per-column zone-maps, populated by flushZoneMaps() in close() when enableZoneMaps is set.
    private final Map<ColumnName, ZoneMapRef> zoneMaps = new LinkedHashMap<>();
    // Rust's vortex.zoned: fixed 8192-row zones fed from the raw batches, independent of chunking.
    // Used when the targeted core edition includes the layout; otherwise the legacy per-chunk
    // vortex.stats zone map is built from colChunks/dictColRefs at close.
    private final boolean zonedLayout;
    private final Map<ColumnName, ZoneAccumulator> zoneAccumulators = new LinkedHashMap<>();
    // Each column's zone stats accumulate on the executor, chained so a column's batches still
    // arrive in order; flushZoneMaps waits for every chain.
    private final Map<ColumnName, CompletableFuture<Void>> zoneTails = new LinkedHashMap<>();
    // WriteOptions.columnEncodings resolved to the encoder each overridden column is written with.
    private final Map<ColumnName, EncodingEncoder> columnEncoders = new LinkedHashMap<>();
    // Rust's repartition: each column's batches coalesce into ~1 MB chunks of 8192-row multiples.
    private final Map<ColumnName, Repartitioner> repartitioners = new LinkedHashMap<>();
    // Segments encoding on WriteOptions#executor(), written to the channel in submission order so
    // segment indexes are known at submit time and the file bytes do not depend on the executor.
    private final ArrayDeque<CompletableFuture<EncodedSegment>> pending = new ArrayDeque<>();
    private int submittedSegments = 0;

    private VortexWriter(
            WritableByteChannel channel, DType.Struct schema, WriteOptions options, List<EncodingEncoder> encodings
    ) {
        // Wire contract, enforced by the reference writer ("StructLayout must have unique field
        // names"): duplicate-name schemas are constructible via the DType.Struct record (only
        // StructBuilder validates), so guard here — colChunks below is name-keyed and would
        // silently collapse the duplicates anyway. The name policy itself (non-blank, no control
        // characters — NUL additionally aborts the reference toolchain's Arrow FFI export) is
        // already certified by the ColumnName type carried in schema.fieldNames().
        var uniqueNames = new java.util.HashSet<ColumnName>();
        for (ColumnName name : schema.fieldNames()) {
            if (!uniqueNames.add(name)) {
                throw new IllegalArgumentException("duplicate field name: " + name);
            }
        }
        this.channel = channel;
        this.schema = schema;
        this.options = options;
        this.dictRetainedBudget = options.globalDictMaxRetainedBytes().bytes();
        this.encodings = encodings;
        this.defaultRegistry = buildRegistry(encodings);
        // A custom encoder list is the whole candidate set, cascade included: emitting a built-in
        // the caller left out would produce a file their own read registry may not decode.
        this.cascadeCodecs = encodings == DEFAULT_CODECS ? buildCascadeCodecs(options) : List.copyOf(encodings);
        this.cascadeRegistry = buildRegistry(this.cascadeCodecs);
        this.editionAllowed = editionAllowed(options.editions());
        this.zonedLayout = options.enableZoneMaps() && editionHasZonedLayout(options.editions());
        for (Map.Entry<ColumnName, ColumnEncoding> e : options.columnEncodings().entrySet()) {
            if (!schema.fieldNames().contains(e.getKey())) {
                throw new IllegalArgumentException("column encoding for unknown column: " + e.getKey());
            }
            columnEncoders.put(e.getKey(), columnEncoder(e.getKey(), e.getValue()));
        }
        this.editionExcluded = editionExcluded(this.editionAllowed, encodings, this.cascadeCodecs);
        for (ColumnName name : schema.fieldNames()) {
            colChunks.put(name, new ArrayList<>());
        }
    }

    /// The union of every enabled edition's cumulative member set, or empty if no edition is
    /// configured — an empty result means the guard is off entirely.
    /// Whether the write may emit Rust's `vortex.zoned` layout, added in `core2026.08.0`: with the
    /// edition guard off (Rust's `disable_editions()` writes its latest layouts), or when the
    /// enabled core edition is that one or later.
    private static boolean editionHasZonedLayout(Map<EditionFamily, Edition> editions) {
        if (editions.isEmpty()) {
            return true;
        }
        Edition core = editions.get(EditionFamily.CORE);
        return core != null && Editions.ALL.indexOf(core) >= Editions.ALL.indexOf(Editions.CORE_2026_08_0);
    }

    private static Set<EncodingId> editionAllowed(Map<EditionFamily, Edition> editions) {
        if (editions.isEmpty()) {
            return Set.of();
        }
        Set<EncodingId> allowed = new LinkedHashSet<>();
        for (Edition edition : editions.values()) {
            allowed.addAll(Editions.cumulativeMembers(edition));
        }
        return Set.copyOf(allowed);
    }

    /// Every encoder id — well-known, plus the custom ones this writer holds — outside `allowed`. Seeding
    /// this into every [EncodeContext]'s initial exclusion set lets [CascadingCompressor]'s
    /// existing per-candidate check skip them and fall back to the best remaining candidate,
    /// instead of the edition guard only surfacing as a hard failure after encoding completes.
    private static Set<EncodingId> editionExcluded(
            Set<EncodingId> allowed, List<EncodingEncoder> encodings, List<EncodingEncoder> cascadeCodecs) {
        if (allowed.isEmpty()) {
            return Set.of();
        }
        Set<EncodingId> excluded = new LinkedHashSet<>();
        // Every well-known id, not only the encoders listed here: an encoder can carry its own private
        // candidate list (Sparse's bool patch-index cascade offers fastlanes.delta), and those
        // candidates must be filtered by the same edition policy.
        for (EncodingId.WellKnown id : EncodingId.WellKnown.values()) {
            if (!allowed.contains(id)) {
                excluded.add(id);
            }
        }
        // custom (non-well-known) encoders this writer holds
        for (EncodingEncoder enc : encodings) {
            if (!allowed.contains(enc.encodingId())) {
                excluded.add(enc.encodingId());
            }
        }
        for (EncodingEncoder enc : cascadeCodecs) {
            if (!allowed.contains(enc.encodingId())) {
                excluded.add(enc.encodingId());
            }
        }
        return Set.copyOf(excluded);
    }

    /// Builds a [WriteRegistry] from the given encoder list plus all built-in extension encoders.
    private static WriteRegistry buildRegistry(List<EncodingEncoder> encoders) {
        WriteRegistry.Builder b = WriteRegistry.builder();
        for (EncodingEncoder e : encoders) {
            b.register(e);
        }
        b.register(DateExtensionEncoder.INSTANCE)
                .register(TimeExtensionEncoder.INSTANCE)
                .register(TimestampExtensionEncoder.INSTANCE)
                .register(UuidExtensionEncoder.INSTANCE);
        return b.build();
    }

    private static List<EncodingEncoder> buildCascadeCodecs(WriteOptions options) {
        List<EncodingEncoder> codecs = new ArrayList<>();
        // FbsExtension-dtype dispatch order matters: findPrimitiveEncoding picks the first
        // accepting codec. DateTimePartsEncoding goes first because it consumes
        // pre-decomposed DateTimePartsData (Parquet importer path); when the data is
        // raw primitive storage (JDBC's long[] via TimestampExtension.encodeAll) it
        // returns notApplicable and spliceResult excludes it, falling back to
        // ExtEncoding which cascades the storage child through FoR/Bitpacked/RLE/ALP.
        // FixedSizeListEncoding handles UUID-style fixed-size byte storage downstream
        // of ExtEncoding.
        codecs.add(new DateTimePartsEncodingEncoder());
        codecs.add(new ExtEncodingEncoder());
        codecs.add(new FixedSizeListEncodingEncoder());
        codecs.add(new ListEncodingEncoder());
        codecs.add(new ConstantEncodingEncoder());
        codecs.add(new AlpEncodingEncoder());
        // ALP-RD competes for high-precision F64/F32 that plain ALP can't fit without too many
        // exceptions (e.g. nyc-311 Latitude): without it in the competition such columns fall back
        // to raw vortex.primitive or dict, matching neither the Rust reference (which uses alprd) nor
        // its size (#304). Registered on WriteRegistry already; this adds it as a top-level candidate.
        codecs.add(new AlpRdEncodingEncoder());
        codecs.add(new FrameOfReferenceEncodingEncoder());
        // ZigZag folds signed values to small unsigned magnitudes and cascades them into
        // bit-packing, as Rust's ZigZagScheme does (issue #410). It competes with FoR, which
        // always fits in as few bits, so it is here for parity rather than for size.
        codecs.add(new ZigZagEncodingEncoder());
        codecs.add(new RunEndEncodingEncoder());
        codecs.add(new RleEncodingEncoder());
        // Delta is in no edition ("no edition includes fastlanes.delta yet"), so like Rust's
        // DeltaScheme it competes only when the edition guard is off (WriteOptions#withoutEditions).
        Set<EncodingId> allowed = editionAllowed(options.editions());
        if (allowed.isEmpty() || allowed.contains(EncodingId.FASTLANES_DELTA)) {
            codecs.add(new DeltaEncodingEncoder());
        }
        codecs.add(new SparseEncodingEncoder());
        // Rust's SequenceScheme: an exact integer arithmetic sequence costs two scalars (#507).
        codecs.add(new SequenceEncodingEncoder());
        codecs.add(new DictEncodingEncoder());
        codecs.add(new BitpackedEncodingEncoder());
        // Decimals: byte-parts (precision <= 18) cascades its i64 mantissa through FoR/bit-packing;
        // plain vortex.decimal covers every width. The competition keeps the smaller.
        codecs.add(new DecimalBytePartsEncodingEncoder());
        codecs.add(new DecimalEncodingEncoder());
        // FsstEncodingEncoder sits between Dict and VarBin. Utf8 goes through
        // CascadingCompressor's sample-and-measure competition (like Primitive dtypes),
        // not first-match dispatch, so Dict/FSST/VarBin genuinely compete on measured
        // size — matching Rust, which uses FSST for high-cardinality short strings
        // (e.g. taxi store_and_fwd_flag).
        // OnPair (core2026.08.1) competes with FSST whenever the enabled editions include it, as
        // Rust's OnPairScheme does in its default compressor: the default edition does.
        if (allowed.isEmpty() || allowed.contains(EncodingId.VORTEX_ONPAIR)) {
            codecs.add(new OnPairEncodingEncoder());
        }
        codecs.add(new FsstEncodingEncoder());
        codecs.add(new VarBinEncodingEncoder());
        if (options.enableZstd()) {
            codecs.add(new ZstdEncodingEncoder());
        }
        codecs.add(new PrimitiveEncodingEncoder());
        codecs.add(new BoolEncodingEncoder());
        return List.copyOf(codecs);
    }

    /// Creates a [VortexWriter] using the default encoder set.
    ///
    /// @param channel the channel to write to
    /// @param schema  the struct schema for the file
    /// @param options write options
    /// @return a new writer
    public static VortexWriter create(
            WritableByteChannel channel, DType.Struct schema, WriteOptions options
    ) {
        return new VortexWriter(channel, schema, options, DEFAULT_CODECS);
    }

    /// Creates a [VortexWriter] with a custom encoder list.
    ///
    /// Creates a [VortexWriter] with a custom encoder list.
    ///
    /// @param channel   the channel to write to
    /// @param schema    the struct schema for the file
    /// @param options   write options
    /// @param encodings custom encoder list
    /// @return a new writer
    public static VortexWriter create(
            WritableByteChannel channel, DType.Struct schema, WriteOptions options, List<EncodingEncoder> encodings
    ) {
        // Custom encoding list: disable global dict — using DEFAULT_CODECS for values/codes behind the scenes
        // would violate the user's expectation that only their encoding list is used.
        return new VortexWriter(channel, schema, options.withGlobalDict(false), encodings);
    }

    /// Creates a [VortexWriter] with a custom [WriteRegistry].
    ///
    /// @param channel  the channel to write to
    /// @param schema   the struct schema for the file
    /// @param options  write options
    /// @param registry write registry supplying encoders and extensions
    /// @return a new writer
    public static VortexWriter create(
            WritableByteChannel channel, DType.Struct schema, WriteOptions options, WriteRegistry registry
    ) {
        return new VortexWriter(channel, schema, options.withGlobalDict(false),
                List.copyOf(registry.encoderMap().values()));
    }

    /// Counts rows for the length-consistency check in [#writeChunk]. Accepts the
    /// same shapes the writer takes plus pre-conversion [java.util.Collection]s
    /// from the extension-column auto-route path.
    private static long rowCountForValidation(ColumnName colName, Object data) {
        if (data instanceof java.util.Collection<?> coll) {
            return coll.size();
        }
        try {
            return arrayLength(data);
        } catch (UnsupportedOperationException _) {
            throw new IllegalArgumentException(
                    "column '" + colName + "' has unsupported data type: "
                            + data.getClass().getSimpleName());
        }
    }

    private static long arrayLength(Object data) {
        return switch (data) {
            case byte[] a -> a.length;
            case short[] a -> a.length;
            case int[] a -> a.length;
            case long[] a -> a.length;
            case float[] a -> a.length;
            case double[] a -> a.length;
            case boolean[] a -> a.length;
            case String[] a -> a.length;
            case java.math.BigDecimal[] a -> a.length;
            case byte[][] a -> a.length;
            // A struct column's row count is its fields' row count (all fields share length,
            // enforced by StructEncodingEncoder); an empty struct carries no rows.
            case StructData d -> d.fieldArrays().isEmpty() ? 0L : arrayLength(d.fieldArrays().getFirst());
            case ListData d -> d.outerLen();
            case ListViewData d -> d.outerLen();
            case DateTimePartsData d -> d.timestamps().length;
            case FixedSizeListData d -> d.outerLen();
            case io.github.dfa1.vortex.writer.encode.NullableData d -> d.validity().length;
            case io.github.dfa1.vortex.writer.encode.VariantData d -> d.length();
            default -> throw new UnsupportedOperationException(
                    "unsupported data type: " + data.getClass());
        };
    }

    private static ByteBuffer buildPostscript(
            long footerOff, int footerLen,
            long dtypeOff, int dtypeLen,
            long layoutOff, int layoutLen
    ) {
        var fbb = new FbsBuilder(256);

        int footerSegOff = FbsPostscriptSegment.createFbsPostscriptSegment(
                fbb, footerOff, footerLen, 0, 0, 0);
        int dtypeSegOff = FbsPostscriptSegment.createFbsPostscriptSegment(
                fbb, dtypeOff, dtypeLen, 0, 0, 0);
        int layoutSegOff = FbsPostscriptSegment.createFbsPostscriptSegment(
                fbb, layoutOff, layoutLen, 0, 0, 0);

        int psOff = FbsPostscript.createFbsPostscript(fbb, dtypeSegOff, layoutSegOff, 0, footerSegOff);
        FbsPostscript.finishFbsPostscriptBuffer(fbb, psOff);
        return fbb.dataSegment().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    // ── Segment encoding ─────────────────────────────────────────────────────

    /// Write one chunk via the typed [Chunk] builder. Each `.put` is validated
    /// against the writer's schema (name exists, array type matches dtype, boxed arrays for
    /// nullable columns); after the consumer returns, every schema column must have been
    /// supplied and all column arrays must share the same length.
    ///
    /// ```java
    /// writer.writeChunk(c -> c
    ///     .put(ColumnName.of("timestamp"), new long[]   {1_700_000_000_000L, 1_700_000_001_000L})
    ///     .put(ColumnName.of("symbol"),    new String[] {"AAPL", "AAPL"})
    ///     .put(ColumnName.of("price"),     new double[] {189.95, 190.10})
    ///     .put(ColumnName.of("volume"),    new Long[]   {100L, null}));  // boxed → nullable
    /// ```
    ///
    /// @param builder consumer that populates a [Chunk] with all schema columns
    /// @throws IOException if an I/O error occurs writing to the underlying channel
    public void writeChunk(java.util.function.Consumer<Chunk> builder) throws IOException {
        var impl = new io.github.dfa1.vortex.writer.ChunkImpl(schema);
        builder.accept(impl);
        writeChunk(impl.finish());
    }

    /// Write one chunk. Each column is encoded by the first registered encoder that accepts its dtype.
    ///
    /// A nullable column may be supplied as a boxed array (`Long[]`, `Integer[]`, `Double[]`,
    /// `Boolean[]`, …) with `null` marking absent rows; it routes through `MaskedEncoding` just like
    /// the builder form. Non-nullable columns take the raw primitive array (`long[]`, `int[]`, …).
    /// Decimal columns take `BigDecimal[]` (nulls allowed when nullable), each rescaled exactly to
    /// the column scale; a value that would round or overflow the precision is rejected.
    ///
    /// @param columns map from [ColumnName] to typed array data
    /// @throws IOException              if an I/O error occurs writing to the underlying channel
    /// @throws IllegalArgumentException if a schema column is missing from `columns`,
    ///         or if column arrays disagree on row count
    public void writeChunk(Map<ColumnName, Object> columns) throws IOException {
        // Adapt each column up front so the map entry point accepts the same shapes as the
        // builder: boxed nullable arrays (Long[], Integer[], …) become NullableData,
        // raw primitive arrays pass through. Done before the row-count check so length validation
        // and encoding both see the normalized carrier.
        Map<ColumnName, Object> adapted = new LinkedHashMap<>();
        for (int i = 0; i < schema.fieldNames().size(); i++) {
            ColumnName colName = schema.fieldNames().get(i);
            Object data = columns.get(colName);
            if (data == null) {
                throw new IllegalArgumentException("missing column: " + colName);
            }
            adapted.put(colName, ChunkImpl.validateAndAdapt(colName.value(), schema.fieldTypes().get(i), data));
        }

        // Pre-validate row counts so a length mismatch is rejected with a clear error
        // before any data is serialized. Without this check, the writer would produce a
        // file whose column chunks claim different row counts — readable but logically
        // inconsistent.
        long expectedLen = -1L;
        ColumnName expectedFrom = null;
        for (int i = 0; i < schema.fieldNames().size(); i++) {
            ColumnName colName = schema.fieldNames().get(i);
            long len = rowCountForValidation(colName, adapted.get(colName));
            if (expectedLen < 0) {
                expectedLen = len;
                expectedFrom = colName;
            } else if (len != expectedLen) {
                throw new IllegalArgumentException(
                        "column '" + colName + "' has " + len + " rows but column '"
                                + expectedFrom + "' has " + expectedLen);
            }
        }

        for (int i = 0; i < schema.fieldNames().size(); i++) {
            ColumnName colName = schema.fieldNames().get(i);
            DType colDtype = schema.fieldTypes().get(i);
            Object data = adapted.get(colName);

            // Auto-route extension columns: callers can pass List<LocalDate>, List<Instant>,
            // etc., and we route through the matching spec extension to produce the int[] /
            // long[] / byte[] storage array. The dtype stays as DType.Extension so
            // ExtEncoding wraps the storage child below — matches Rust's nested layout
            // (ExtEncoding → PrimitiveEncoding) and lets Registry skip its unwrap path.
            if (colDtype instanceof DType.Extension extDtype && data instanceof java.util.Collection<?> coll) {
                ExtensionEncoder impl =
                        io.github.dfa1.vortex.core.model.ExtensionId.parse(extDtype.extensionId())
                                .map(defaultRegistry::lookup)
                                .orElse(null);
                if (impl != null) {
                    data = impl.encodeAll(extDtype, coll);
                }
            }

            if (zonedLayout) {
                ZoneAccumulator acc = zoneAccumulators.computeIfAbsent(colName, _ -> new ZoneAccumulator(colDtype));
                Object batch = data;
                zoneTails.put(colName, zoneTails.getOrDefault(colName, CompletableFuture.completedFuture(null))
                        .thenRunAsync(() -> acc.add(batch, arrayLength(batch)), options.executor()));
            }

            if (!firstChunkSeen && options.globalDict() && !columnEncoders.containsKey(colName)) {
                // Global dict candidate detection inspects raw primitive/String/byte[] arrays. Nullable
                // columns (carried as NullableData) run the same cardinality/ratio check against
                // their values, skipping null positions per the validity bitmap; the reader's dict
                // lazy-decode already handles masked (nullable) codes children.
                boolean nullable = data instanceof io.github.dfa1.vortex.writer.encode.NullableData;
                Object values = nullable
                        ? ((io.github.dfa1.vortex.writer.encode.NullableData) data).values() : data;
                boolean[] validity = nullable
                        ? ((io.github.dfa1.vortex.writer.encode.NullableData) data).validity() : null;
                boolean candidate = false;
                if (colDtype instanceof DType.Primitive p) {
                    candidate = DictColumnState.isDictCandidate(p.ptype(), values, validity);
                } else if (colDtype instanceof DType.Utf8 || colDtype instanceof DType.Binary) {
                    // Rust's dict layout admits Primitive | Utf8 | Binary (dict_layout_supported).
                    candidate = DictColumnState.isVarBinDictCandidate((Object[]) values, validity);
                }
                if (candidate) {
                    dictCandidates.add(colName);
                    dictStates.put(colName, new DictColumnState(colDtype));
                }
            }

            if (dictCandidates.contains(colName)) {
                // Ingest this chunk into the column's cardinality-bounded dict state: dedup values
                // into the shared value->code map, buffer a cheap per-chunk code array, and capture
                // the per-chunk stats now (before the raw array is discarded). If a new distinct
                // value would push the map past GLOBAL_DICT_MAX_CARDINALITY, ingestDictChunk returns
                // false and we demote the column immediately (mid-file cap breach, ADR 0021).
                DictColumnState state = dictStates.get(colName);
                boolean admitted = state.ingestDictChunk(data);
                if (!admitted) {
                    // Cap breached by this chunk: demote (replaying the already-buffered chunks per
                    // -chunk) then write this chunk — which ingest rejected without buffering — too.
                    demoteDictColumn(colName);
                    appendChunk(colName, colDtype, data);
                } else {
                    long before = dictRetainedBytes.getOrDefault(colName, 0L);
                    long after = state.retainedBytes();
                    dictRetainedTotal += after - before;
                    dictRetainedBytes.put(colName, after);
                    if (dictRetainedTotal > dictRetainedBudget) {
                        // Aggregate code-array budget exceeded (secondary safety net). Demote the
                        // largest-retained columns until back under budget, so writer memory stays
                        // bounded even across many wide candidate columns.
                        evictLargestDictColumnsUntilUnderBudget();
                    }
                }
            } else {
                appendChunk(colName, colDtype, data);
            }
        }
        firstChunkSeen = true;
    }

    /// Hands one batch of a column to its [Repartitioner], writing every chunk it completes; a
    /// column whose carriers cannot be coalesced is written one chunk per batch.
    private void appendChunk(ColumnName colName, DType colDtype, Object data) throws IOException {
        // The legacy vortex.stats zone map is one zone per chunk, so its targets keep batch chunks.
        if (!zonedLayout || !Repartitioner.supports(colDtype)) {
            writeDataChunk(colName, colDtype, data);
            return;
        }
        Repartitioner repartitioner = repartitioners.computeIfAbsent(colName, _ -> new Repartitioner(colDtype));
        for (Object chunk : repartitioner.add(data, arrayLength(data))) {
            writeDataChunk(colName, colDtype, chunk);
        }
    }

    private void writeDataChunk(ColumnName colName, DType colDtype, Object data) throws IOException {
        long rowCount = arrayLength(data);
        int segIdx = writeSegment(colDtype, data, columnEncoders.get(colName));
        colChunks.get(colName).add(new ChunkRef(segIdx, rowCount));
    }

    /// Writes every column's pending rows as its last chunk.
    private void flushRepartitioners() throws IOException {
        for (Map.Entry<ColumnName, Repartitioner> e : repartitioners.entrySet()) {
            Object last = e.getValue().finish();
            if (last != null) {
                writeDataChunk(e.getKey(), columnDtype(e.getKey()), last);
            }
        }
    }

    @Override
    public void close() throws IOException {
        flushRepartitioners();
        flushDictColumns();
        flushZoneMaps();
        drainSegments(0);
        ByteBuffer footerBuf = buildFooter();
        long footerOff = bytesWritten;
        write(footerBuf);

        ByteBuffer dtypeBuf = DTypeFbsSerializer.buildDType(schema);
        long dtypeOff = bytesWritten;
        write(dtypeBuf);

        ByteBuffer layoutBuf = buildLayout();
        long layoutOff = bytesWritten;
        write(layoutBuf);

        ByteBuffer psBuf = buildPostscript(
                footerOff, footerBuf.capacity(),
                dtypeOff, dtypeBuf.capacity(),
                layoutOff, layoutBuf.capacity());
        write(psBuf);

        // Trailer: version(u16 LE) | postscriptLen(u16 LE) | magic(4)
        var trailer = ByteBuffer.allocate(VortexFormat.TRAILER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        trailer.putShort((short) VortexFormat.VERSION);
        trailer.putShort((short) psBuf.capacity());
        trailer.put(VortexFormat.MAGIC.asByteBuffer());
        trailer.flip();
        channel.write(trailer);
    }

    private int writeSegment(DType dtype, Object data) throws IOException {
        return writeSegment(dtype, data, null);
    }

    private int writeSegment(DType dtype, Object data, EncodingEncoder encodingOverride) throws IOException {
        return writeSegment(dtype, data, encodingOverride, Set.of());
    }

    /// Writes a segment, optionally forcing a specific `encodingOverride` instead of the
    /// configured cascade, and optionally excluding encodings from the cascade competition.
    ///
    /// `excludedFromCascade` lets the global Utf8 dictionary path compress its values pool
    /// through the normal Utf8 competition (FSST/VarBin/Zstd) while excluding
    /// [DictEncodingEncoder] — the cascade would otherwise re-pick it and wrap the dictionary
    /// in another dict the reader cannot unwrap. Only consulted on the cascade branch
    /// (`encodingOverride == null && allowedCascading > 0`).
    ///
    /// @param dtype               logical type of the segment
    /// @param data                the values to encode
    /// @param encodingOverride    a specific encoder to force, or `null` to use the cascade
    /// @param excludedFromCascade encoding ids to exclude from the cascade competition
    /// @return the index of the written segment
    /// @throws IOException if writing to the channel fails
    private int writeSegment(DType dtype, Object data, EncodingEncoder encodingOverride,
            Set<EncodingId> excludedFromCascade) throws IOException {
        EncodingEncoder override = resolveOverride(dtype, data, encodingOverride);
        pending.add(CompletableFuture.supplyAsync(
                () -> encodeSegment(dtype, data, override, excludedFromCascade), options.executor()));
        int segIdx = submittedSegments++;
        drainSegments(MAX_IN_FLIGHT_SEGMENTS);
        return segIdx;
    }

    // ponytail: fixed in-flight window bounds memory to ~2 chunks per core; make it a WriteOptions
    // knob if a caller needs a tighter memory bound or a deeper pipeline.
    private static final int MAX_IN_FLIGHT_SEGMENTS = 2 * Runtime.getRuntime().availableProcessors();

    /// Writes completed segments from the head of the queue, in submission order, blocking on the
    /// head while more than `keep` are still in flight. `drainSegments(0)` writes them all.
    private void drainSegments(int keep) throws IOException {
        while (!pending.isEmpty() && (pending.size() > keep || pending.peek().isDone())) {
            EncodedSegment encoded;
            try {
                encoded = pending.poll().join();
            } catch (CompletionException e) {
                discardPending();
                // Encoders throw only unchecked exceptions: rethrow them as the sequential path did.
                switch (e.getCause()) {
                    case RuntimeException re -> throw re;
                    case Error err -> throw err;
                    default -> throw new IOException(e.getCause());
                }
            }
            try (Arena _ = encoded.arena()) {
                writeEncoded(encoded);
            }
        }
    }

    private void joinZoneTails() throws IOException {
        try {
            CompletableFuture.allOf(zoneTails.values().toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException e) {
            switch (e.getCause()) {
                case RuntimeException re -> throw re;
                case Error err -> throw err;
                default -> throw new IOException(e.getCause());
            }
        }
    }

    /// Waits out every in-flight segment after a failure so none of their arenas leaks.
    private void discardPending() {
        while (!pending.isEmpty()) {
            pending.poll().handle((encoded, _) -> {
                if (encoded != null) {
                    encoded.arena().close();
                }
                return null;
            }).join();
        }
    }

    /// Picks the structural encoder a segment is forced through, or `encodingOverride` unchanged.
    /// Non-extension nullable columns (Primitive, Utf8) wrap with MaskedEncodingEncoder here.
    /// FbsExtension columns route through ExtEncodingEncoder.encode which itself delegates to
    /// MaskedEncodingEncoder when its storage data is NullableData — handled inside ExtEncoding.
    /// Exception: a configured encoder that embeds validity itself (acceptsNullable, e.g.
    /// vortex.zstd) takes the NullableData straight, so no masked wrapper is inserted.
    /// Map columns bypass both: the container encoding is structural rather than a
    /// compressible primitive codec (same reason Variant does below), and their validity is
    /// delegated to the entries child's own validity slot, so no masked wrapper belongs here.
    private EncodingEncoder resolveOverride(DType dtype, Object data, EncodingEncoder encodingOverride) {
        if (encodingOverride == null && dtype instanceof DType.Map) {
            return new io.github.dfa1.vortex.writer.encode.MapEncodingEncoder();
        }
        if (encodingOverride == null
                && data instanceof io.github.dfa1.vortex.writer.encode.NullableData
                && !(dtype instanceof DType.Extension)) {
            EncodingEncoder nullableCapable = nullableCapableEncoder(dtype);
            return nullableCapable != null ? nullableCapable : new MaskedEncodingEncoder();
        }
        // Variant columns bypass the cascade: the container encoding is structural, not a
        // compressible primitive codec, so route straight to the dedicated encoder.
        if (encodingOverride == null && dtype instanceof DType.Variant) {
            return new io.github.dfa1.vortex.writer.encode.VariantEncodingEncoder();
        }
        return encodingOverride;
    }

    /// Encodes one segment and computes its stats: the CPU-bound half of a segment write, run on
    /// [WriteOptions#executor()]. Touches only immutable writer state; the returned arena is shared
    /// so the writing thread can read and close it.
    private EncodedSegment encodeSegment(DType dtype, Object data, EncodingEncoder encodingOverride,
            Set<EncodingId> excludedFromCascade) {
        Arena arena = Arena.ofShared();
        try {
            EncodeResult result;
            if (encodingOverride != null) {
                // Give overrides the cascade registry + depth when cascading is enabled, so
                // wrapping encoders (notably MaskedEncodingEncoder for nullable columns) can
                // compress their inner values through the full CascadingCompressor rather than a
                // fixed first-match encoder. Without cascading, a depth-0 context is passed and the
                // override behaves as before.
                EncodeContext encodeCtx = options.allowedCascading() > 0
                        ? EncodeContext.ofDepth(options.allowedCascading(), arena, cascadeRegistry, editionExcluded)
                        : EncodeContext.of(arena, defaultRegistry, editionExcluded);
                // A wrapping override (masked) cascades its inner values, so the exclusions hold
                // there too -- a nullable global-dict pool must never become a dict itself.
                encodeCtx = encodeCtx.withExcluded(excludedFromCascade);
                result = encodingOverride.encode(dtype, data, encodeCtx);
            } else if (options.allowedCascading() > 0) {
                EncodeContext encodeCtx = EncodeContext.ofDepth(options.allowedCascading(), arena, cascadeRegistry, editionExcluded);
                for (EncodingId excluded : excludedFromCascade) {
                    encodeCtx = encodeCtx.withExcluded(excluded);
                }
                CascadingCompressor compressor = new CascadingCompressor(cascadeCodecs);
                result = compressor.encode(dtype, data, encodeCtx);
            } else {
                EncodingEncoder encoder = findEncoder(dtype);
                EncodeContext encodeCtx = EncodeContext.of(arena, defaultRegistry, editionExcluded);
                result = encoder.encode(dtype, data, encodeCtx);
            }
            long nullCount = data instanceof NullableData nd ? countNulls(nd.validity()) : 0L;
            // The winning encoder's own stats win when present (a cheaper override, e.g.
            // vortex.constant already knows its value is both extremes) -- otherwise the generic
            // fallback computes them from the untouched input, independent of which encoder ran.
            // This is what makes stats coverage an encoder-independent guarantee rather than a
            // per-encoder convention every new encoder has to remember (ADR 0025).
            byte[] min;
            byte[] max;
            if (result.hasStats()) {
                min = result.statsMin();
                max = result.statsMax();
            } else {
                byte[][] fallback = ZoneMapStatCodec.columnMinMax(dtype, data);
                min = fallback != null ? fallback[0] : null;
                max = fallback != null ? fallback[1] : null;
            }
            SegmentStats stats = new SegmentStats(min, max, ZoneMapStatCodec.columnSum(dtype, data), nullCount);
            return new EncodedSegment(result, arena, stats);
        } catch (RuntimeException | Error e) {
            arena.close();
            throw e;
        }
    }

    /// Appends one encoded segment to the channel: the ordered, I/O half of a segment write.
    private void writeEncoded(EncodedSegment encoded) throws IOException {
        EncodeResult result = encoded.result();
        // Register all encoding IDs found in the node tree
        registerEncodingIds(result.rootNode());

        // Align segment start to 64 bytes so each buffer is Arrow-compatible
        long prePad = (64 - bytesWritten % 64) % 64;
        if (prePad > 0) {
            writePadding((int) prePad);
        }

        long offset = bytesWritten;
        List<EncodedBuffer> buffers = result.encodedBuffers();
        int[] paddings = bufferPaddings(buffers);
        ByteBuffer fbBuf = buildArrayFlatBuffer(result, paddings, encoded.stats().nullCount());

        // Segment format: [padding, buffer]... [FlatBuffer Array bytes] [4-byte LE u32 = fbLen]
        int fbLen = fbBuf.remaining();
        for (int i = 0; i < buffers.size(); i++) {
            if (paddings[i] > 0) {
                writePadding(paddings[i]);
            }
            write(buffers.get(i).data());
        }
        write(fbBuf);
        var sizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fbLen);
        sizeBuf.flip();
        channel.write(sizeBuf);
        bytesWritten += 4;

        segs.add(new SegRef(offset, bytesWritten - offset, encoded.stats()));
    }

    private void registerEncodingIds(EncodeNode node) {
        EncodingId id = node.encodingId();
        // Edition guard backstop (issue #301): editionExcluded, seeded into every EncodeContext,
        // already steers CascadingCompressor away from out-of-edition candidates during selection
        // (see the editionExcluded/editionAllowed fields' javadoc) - this direct check catches
        // whatever still reaches here anyway: a forced encodingOverride, or a selection path that
        // does not consult EncodeContext#excluded() at all. Never silently write a file outside
        // the declared edition.
        if (!editionAllowed.isEmpty() && !editionAllowed.contains(id)) {
            throw editionViolation(id);
        }
        encodingIdx.computeIfAbsent(id, _ -> encodingIdx.size());
        for (EncodeNode child : node.children()) {
            registerEncodingIds(child);
        }
    }

    private VortexException editionViolation(EncodingId id) {
        String configured = options.editions().values().stream()
                .map(e -> e.id().toString())
                .sorted()
                .collect(Collectors.joining(", "));
        Optional<Edition> owning = Editions.owningEdition(id);
        String hint = owning.isPresent()
                ? "; it joins " + owning.get().id() + " — enable that edition via WriteOptions.withEdition(...)"
                : "; it is not part of any edition — only WriteOptions.withoutEditions() permits it";
        return new VortexException(id, "outside the configured edition(s) [" + configured + "]" + hint);
    }

    private EncodingEncoder findEncoder(DType dtype) {
        for (EncodingEncoder c : encodings) {
            if (c.accepts(dtype)) {
                return c;
            }
        }
        throw new UnsupportedOperationException("no encoder for dtype: " + dtype);
    }

    /// Returns the configured encoder for `dtype` that consumes a [NullableData] carrier directly
    /// (embedding its own validity), or `null` to fall back to `vortex.masked` wrapping. Only the
    /// first-match flat path is considered; with cascading enabled the compressor owns selection,
    /// so nullable columns keep the masked layout.
    private EncodingEncoder nullableCapableEncoder(DType dtype) {
        if (options.allowedCascading() > 0) {
            return null;
        }
        for (EncodingEncoder c : encodings) {
            if (c.accepts(dtype) && c.acceptsNullable(dtype)) {
                return c;
            }
        }
        return null;
    }

    private void write(MemorySegment seg) throws IOException {
        ByteBuffer buf = seg.asByteBuffer();
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        bytesWritten += seg.byteSize();
    }

    private void write(ByteBuffer buf) throws IOException {
        buf.rewind();
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        bytesWritten += buf.capacity();
    }

    private void writePadding(int n) throws IOException {
        ByteBuffer pad = ByteBuffer.allocate(n);
        while (pad.hasRemaining()) {
            channel.write(pad);
        }
        bytesWritten += n;
    }

    /// Zero bytes to write before each buffer so it starts on a multiple of its own alignment. The
    /// segment starts 64-byte aligned (more than any element needs), so offsets relative to the
    /// segment start are enough.
    private static int[] bufferPaddings(List<EncodedBuffer> buffers) {
        int[] paddings = new int[buffers.size()];
        long position = 0;
        for (int i = 0; i < buffers.size(); i++) {
            int alignment = buffers.get(i).alignment();
            paddings[i] = (int) ((alignment - position % alignment) % alignment);
            position += paddings[i] + buffers.get(i).data().byteSize();
        }
        return paddings;
    }

    private ByteBuffer buildArrayFlatBuffer(EncodeResult result, int[] paddings, long nullCount) {
        var fbb = new FbsBuilder(256);

        // Stats for the root node only (build vectors before the ArrayStats table). null_count is
        // always recorded; min/max only when the encoder produced them. Sum is not embedded per-flat
        // (Rust's flat writer doesn't either — flat/writer.rs retains only pre-computed stats); the
        // per-zone sum lives in the vortex.stats zone-map table emitted by flushZoneMaps().
        int minVec = result.hasStats()
                ? io.github.dfa1.vortex.core.fbs.FbsArrayStats.createMinVector(fbb, result.statsMin()) : 0;
        int maxVec = result.hasStats()
                ? io.github.dfa1.vortex.core.fbs.FbsArrayStats.createMaxVector(fbb, result.statsMax()) : 0;
        // forceDefaults only while building ArrayStats, so null_count = 0 is serialized (flatbuffers
        // omits a scalar equal to its default otherwise) — matching the Rust writer and letting the
        // reader prune IS NULL on zero-null chunks. Reset immediately so the Array/ArrayNode tables
        // keep their normal (offset-default-omitting) layout.
        fbb.forceDefaults(true);
        io.github.dfa1.vortex.core.fbs.FbsArrayStats.startFbsArrayStats(fbb);
        if (result.hasStats()) {
            io.github.dfa1.vortex.core.fbs.FbsArrayStats.addMin(fbb, minVec);
            io.github.dfa1.vortex.core.fbs.FbsArrayStats.addMax(fbb, maxVec);
        }
        io.github.dfa1.vortex.core.fbs.FbsArrayStats.addNullCount(fbb, nullCount);
        int statsOff = io.github.dfa1.vortex.core.fbs.FbsArrayStats.endFbsArrayStats(fbb);
        fbb.forceDefaults(false);

        int rootNodeOff = buildArrayNodeFlatBuffer(fbb, result.rootNode(), statsOff);

        // Buffer struct vector — one entry per buffer in result.
        // FbsLayout (LE): padding(u16) | alignment_exponent(u8) | compression(u8) | length(u32)
        // FlatBuffers builds backward: iterate in reverse.
        // alignment_exponent is the element's own alignment, as Rust declares it: the Rust reader
        // keeps it on the buffer, failing array construction when it is less than the element needs
        // and aborting on any row slice off a multiple of it when it is more (this writer used to
        // declare 64 for every buffer, which crashed filtered vortex-jni scans of zone-mapped files).
        var bufs = result.encodedBuffers();
        io.github.dfa1.vortex.core.fbs.FbsArray.startBuffersVector(fbb, bufs.size());
        for (int i = bufs.size() - 1; i >= 0; i--) {
            fbb.prep(4, 8);
            fbb.putInt((int) bufs.get(i).data().byteSize());
            fbb.putByte((byte) 0);   // compression = None
            fbb.putByte((byte) Integer.numberOfTrailingZeros(bufs.get(i).alignment()));
            fbb.putShort((short) paddings[i]);
        }
        int bufVec = fbb.endVector();

        int arrayOff = io.github.dfa1.vortex.core.fbs.FbsArray.createFbsArray(fbb, rootNodeOff, bufVec);
        io.github.dfa1.vortex.core.fbs.FbsArray.finishFbsArrayBuffer(fbb, arrayOff);
        return fbb.dataSegment().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    // ── FbsFooter / metadata serialization ──────────────────────────────────────

    private int buildArrayNodeFlatBuffer(FbsBuilder fbb, EncodeNode node, int statsOff) {
        // Build children first (FlatBuffers bottom-up: nested objects before parent table)
        int[] childOffsets = new int[node.children().length];
        for (int i = 0; i < childOffsets.length; i++) {
            childOffsets[i] = buildArrayNodeFlatBuffer(fbb, node.children()[i], 0);
        }

        int metaOff = 0;
        if (node.metadata() != null && node.metadata().byteSize() > 0) {
            byte[] metaBytes = node.metadata().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            metaOff = io.github.dfa1.vortex.core.fbs.FbsArrayNode.createMetadataVector(fbb, metaBytes);
        }

        int childVec = 0;
        if (childOffsets.length > 0) {
            childVec = io.github.dfa1.vortex.core.fbs.FbsArrayNode.createChildrenVector(fbb, childOffsets);
        }

        int bufIdxVec = io.github.dfa1.vortex.core.fbs.FbsArrayNode.createBuffersVector(fbb, node.bufferIndices());
        int encIdx = encodingIdx.get(node.encodingId());
        return io.github.dfa1.vortex.core.fbs.FbsArrayNode.createFbsArrayNode(
                fbb, encIdx, metaOff, childVec, bufIdxVec, statsOff);
    }

    /// Emits a per-column `vortex.stats` zone-map (one zone per chunk) for every fixed-width
    /// primitive column whose chunks all carry min/max stats. Must run before [#buildFooter]
    /// so the stats-table segments are present in `segment_specs`.
    private void flushZoneMaps() throws IOException {
        if (!options.enableZoneMaps()) {
            return;
        }
        // The legacy zone map below reads each chunk's stats off its written segment.
        drainSegments(0);
        if (zonedLayout) {
            joinZoneTails();
            for (Map.Entry<ColumnName, ZoneAccumulator> e : zoneAccumulators.entrySet()) {
                ZoneAccumulator acc = e.getValue();
                acc.finish();
                if (acc.zoneCount() == 0) {
                    continue;
                }
                int zonesSegIdx = writeSegment(acc.tableDtype(), acc.tableData(), zoneTableEncoder());
                zoneMaps.put(e.getKey(), new ZoneMapRef(zonesSegIdx, acc.zoneCount(), acc.metadata()));
            }
            return;
        }
        for (Map.Entry<ColumnName, List<ChunkRef>> e : colChunks.entrySet()) {
            ColumnName colName = e.getKey();
            List<ChunkRef> chunks = e.getValue();
            if (chunks.isEmpty()) {
                continue;
            }
            DType colDtype = columnDtype(colName);
            DType minMaxDtype = ZoneMapStatCodec.zoneMinMaxDtype(colDtype);
            DType sumDtype = ZoneMapStatCodec.zoneSumDtype(colDtype);
            long[] nullCounts = new long[chunks.size()];
            List<SegmentStats> stats = chunks.stream().map(c -> segs.get(c.segIdx()).stats()).toList();
            for (int i = 0; i < chunks.size(); i++) {
                nullCounts[i] = stats.get(i).nullCount();
            }
            emitZoneMap(colName, minMaxDtype,
                    stats.stream().map(SegmentStats::min).toList(),
                    stats.stream().map(SegmentStats::max).toList(),
                    sumDtype, stats.stream().map(SegmentStats::sum).toList(),
                    nullCounts, chunks.stream().mapToLong(ChunkRef::rowCount).toArray());
        }
        // Dict-encoded columns (one zone per code chunk). MIN/MAX/SUM come from each chunk's logical
        // values (computed at dict-build time); NULL_COUNT always. Matches Rust, whose zone-map
        // stats are computed on the logical column dtype, independent of the dict encoding.
        for (Map.Entry<ColumnName, DictColRef> e : dictColRefs.entrySet()) {
            DictColRef ref = e.getValue();
            DType colDtype = columnDtype(e.getKey());
            DType minMaxDtype = ZoneMapStatCodec.zoneMinMaxDtype(colDtype);
            long[] nullCounts = ref.chunkNullCounts().stream().mapToLong(Long::longValue).toArray();
            emitZoneMap(e.getKey(), minMaxDtype,
                    ref.chunkStatsMin(), ref.chunkStatsMax(),
                    ZoneMapStatCodec.zoneSumDtype(colDtype), ref.chunkStatsSum(), nullCounts,
                    ref.chunkRowCounts().stream().mapToLong(Long::longValue).toArray());
        }
    }

    private DType columnDtype(ColumnName colName) {
        return schema.fieldTypes().get(schema.fieldNames().indexOf(colName));
    }

    /// Writes one `vortex.stats` zone-map for `colName`: one zone per chunk, with NULL_COUNT always,
    /// MAX/MIN (plus always-false `_is_truncated` flags) when `minMaxDtype` is non-null, and SUM when
    /// `sumDtype` is non-null. `minBytes`/`maxBytes`/`sumBytes` hold each zone's serialized scalar —
    /// read only when the matching dtype is set; a `null` entry marks that specific zone's stat as
    /// unknown (e.g. an all-null chunk, an overflowed sum, or an encoder that does not surface a
    /// min/max) rather than dropping the stat for the whole column — MIN/MAX/SUM are nullable per
    /// zone, matching Rust. Field/bit order follows ZonedStatsSchema: MAX(3), MIN(4), SUM(5),
    /// NULL_COUNT(6).
    private void emitZoneMap(ColumnName colName, DType minMaxDtype, List<byte[]> minBytes, List<byte[]> maxBytes,
                             DType sumDtype, List<byte[]> sumBytes, long[] nullCounts,
                             long[] rowCounts) throws IOException {
        int nZones = nullCounts.length;
        boolean[] allValid = new boolean[nZones];
        java.util.Arrays.fill(allValid, true);

        List<String> names = new java.util.ArrayList<>();
        List<DType> types = new java.util.ArrayList<>();
        List<Object> fields = new java.util.ArrayList<>();
        if (minMaxDtype != null) {
            boolean[] notTruncated = new boolean[nZones];
            boolean[] maxValid = new boolean[nZones];
            Object maxValues = ZoneMapStatCodec.zoneStatValues(minMaxDtype, maxBytes, maxValid);
            names.add("max");
            types.add(minMaxDtype);
            fields.add(new NullableData(maxValues, maxValid));
            names.add("max_is_truncated");
            types.add(DType.BOOL);
            fields.add(notTruncated);
            boolean[] minValid = new boolean[nZones];
            Object minValues = ZoneMapStatCodec.zoneStatValues(minMaxDtype, minBytes, minValid);
            names.add("min");
            types.add(minMaxDtype);
            fields.add(new NullableData(minValues, minValid));
            names.add("min_is_truncated");
            types.add(DType.BOOL);
            fields.add(notTruncated.clone());
        }
        if (sumDtype != null) {
            boolean[] sumValid = new boolean[nZones];
            Object sumArr = ZoneMapStatCodec.sumColumn(sumDtype, sumBytes, sumValid);
            names.add("sum");
            types.add(sumDtype);
            fields.add(new NullableData(sumArr, sumValid));
        }
        names.add("null_count");
        types.add(new DType.Primitive(PType.U64, true));
        fields.add(new NullableData(nullCounts, allValid.clone()));

        DType.Struct statsDtype = new DType.Struct(
                names.stream().map(ColumnName::of).toList(), List.copyOf(types), false);
        int zonesSegIdx = writeSegment(statsDtype, new StructData(fields), new StructEncodingEncoder());
        zoneMaps.put(colName,
                new ZoneMapRef(zonesSegIdx, nZones, ZoneMapStatCodec.zonedMetadataBytes(
                        ZoneMapStatCodec.uniformZoneLength(rowCounts), minMaxDtype != null, sumDtype != null)));
    }

    /// The encoder a column with a [ColumnEncoding] override is written with: the cascade over the
    /// column's candidates only, wrapping nullable data in `vortex.masked` as the default path does.
    ///
    /// @throws IllegalArgumentException if this writer has no encoder for one of the encodings
    private EncodingEncoder columnEncoder(ColumnName column, ColumnEncoding encoding) {
        // The encoders this writer cascades with; a default writer also offers the other built-ins
        // (Pco, Zstd, …) that are registered but not default candidates. A custom encoder list stays
        // the whole set, as it is for every other column.
        Map<EncodingId, EncodingEncoder> available = new LinkedHashMap<>();
        for (EncodingEncoder enc : cascadeCodecs) {
            available.putIfAbsent(enc.encodingId(), enc);
        }
        if (encodings == DEFAULT_CODECS) {
            WriteRegistry.loadAll().encoderMap().forEach(available::putIfAbsent);
        }
        List<EncodingEncoder> encoders = new ArrayList<>();
        for (EncodingId id : encoding.encodings()) {
            EncodingEncoder enc = available.get(id);
            if (enc == null) {
                throw new IllegalArgumentException("column '" + column + "': this writer cannot encode " + id);
            }
            encoders.add(enc);
        }
        WriteRegistry registry = buildRegistry(encoders);
        return new EncodingEncoder() {
            @Override
            public EncodingId encodingId() {
                return encoders.getFirst().encodingId();
            }

            @Override
            public boolean accepts(DType dtype) {
                return true;
            }

            @Override
            public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
                // The candidates are the whole set: the column's context (and so a masked column's
                // value cascade, which reads ctx.registry()) sees only them.
                EncodeContext columnCtx = EncodeContext.ofDepth(options.allowedCascading(), ctx.arena(),
                        registry, editionExcluded);
                return data instanceof NullableData && !(dtype instanceof DType.Extension)
                        ? new MaskedEncodingEncoder().encode(dtype, data, columnCtx)
                        : new CascadingCompressor(encoders).encode(dtype, data, columnCtx);
            }
        };
    }

    /// Encodes a `vortex.zoned` stats table through the cascade at any depth and with any encoder
    /// list, as Rust's stats strategy always compresses it: [CascadingCompressor] encodes the
    /// struct itself (including the nullable `bounded_max` state) and falls back to canonical
    /// encodings for any field no candidate accepts.
    private EncodingEncoder zoneTableEncoder() {
        List<EncodingEncoder> codecs = cascadeCodecs;
        return new EncodingEncoder() {
            @Override
            public EncodingId encodingId() {
                return EncodingId.VORTEX_STRUCT;
            }

            @Override
            public boolean accepts(DType dtype) {
                return dtype instanceof DType.Struct;
            }

            @Override
            public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
                return new CascadingCompressor(codecs).encode(dtype, data, ctx);
            }
        };
    }

    /// Wraps a column's data layout in a `vortex.stats` (zoned) layout when a zone-map was
    /// emitted for it; otherwise returns the data layout unchanged.
    private int wrapZoneMap(FbsBuilder fbb, ColumnName colName, int dataLayout, long colRows) {
        ZoneMapRef zm = zoneMaps.get(colName);
        if (zm == null) {
            return dataLayout;
        }
        int zonesSegV = FbsLayout.createSegmentsVector(fbb, new long[]{zm.zonesSegIdx()});
        int zonesFlat = FbsLayout.createFbsLayout(fbb, LAYOUT_FLAT, zm.nZones(), 0, 0, zonesSegV);
        int childV = FbsLayout.createChildrenVector(fbb, new int[]{dataLayout, zonesFlat});
        int metaV = FbsLayout.createMetadataVector(fbb, zm.metadata());
        return FbsLayout.createFbsLayout(fbb, LAYOUT_ZONED, colRows, metaV, childV, 0);
    }

    static long countNulls(boolean[] validity) {
        long nulls = 0;
        for (boolean valid : validity) {
            if (!valid) {
                nulls++;
            }
        }
        return nulls;
    }

    private ByteBuffer buildFooter() {
        var fbb = new FbsBuilder(512);

        // array_specs: all encoding IDs used across all written segments, in registration order
        EncodingId[] encIds = encodingIdx.entrySet().stream()
                                      .sorted(Map.Entry.comparingByValue())
                                      .map(Map.Entry::getKey)
                                      .toArray(EncodingId[]::new);
        int[] asOffsets = new int[encIds.length];
        for (int i = 0; i < encIds.length; i++) {
            asOffsets[i] = FbsArraySpec.createFbsArraySpec(fbb, fbb.createString(encIds[i].id()));
        }
        int asv = FbsFooter.createArraySpecsVector(fbb, asOffsets);

        // layout_specs, in LAYOUT_* index order: FLAT, CHUNKED, STRUCT, DICT, then the zoned
        // layout: Rust's "vortex.zoned" when the edition has it, else the legacy "vortex.stats"
        // alias every reader accepts.
        int ls0 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(LayoutId.FLAT.id()));
        int ls1 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(LayoutId.CHUNKED.id()));
        int ls2 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(LayoutId.STRUCT.id()));
        int ls3 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(LayoutId.DICT.id()));
        int ls4 = FbsLayoutSpec.createFbsLayoutSpec(fbb,
                fbb.createString((zonedLayout ? LayoutId.ZONED : LayoutId.STATS).id()));
        int lsv = FbsFooter.createLayoutSpecsVector(fbb, new int[]{ls0, ls1, ls2, ls3, ls4});

        // segment_specs (inline struct vector — write in reverse order)
        FbsFooter.startSegmentSpecsVector(fbb, segs.size());
        for (int i = segs.size() - 1; i >= 0; i--) {
            SegRef s = segs.get(i);
            FbsSegmentSpec.createFbsSegmentSpec(fbb, s.offset(), s.len(), 6, 0, 0);
        }
        int ssv = fbb.endVector();

        int off = FbsFooter.createFbsFooter(fbb, asv, lsv, ssv, 0, 0);
        fbb.finish(off);
        return fbb.dataSegment().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    private ByteBuffer buildLayout() {
        var fbb = new FbsBuilder(256);
        int colCount = schema.fieldNames().size();

        int[] colLayouts = new int[colCount];
        long totalRows = 0;

        for (int c = 0; c < colCount; c++) {
            ColumnName colName = schema.fieldNames().get(c);
            DictColRef ref = dictColRefs.get(colName);
            if (ref != null) {
                int dictLayout = buildDictColLayout(fbb, ref);
                colLayouts[c] = wrapZoneMap(fbb, colName, dictLayout, ref.totalRows());
                if (totalRows == 0) {
                    totalRows = ref.totalRows();
                }
            } else {
                List<ChunkRef> chunks = colChunks.get(colName);
                long colRows = 0;
                int[] flats = new int[chunks.size()];
                for (int i = 0; i < chunks.size(); i++) {
                    ChunkRef cr = chunks.get(i);
                    int segV = FbsLayout.createSegmentsVector(fbb, new long[]{cr.segIdx()});
                    flats[i] = FbsLayout.createFbsLayout(fbb, LAYOUT_FLAT, cr.rowCount(), 0, 0, segV);
                    colRows += cr.rowCount();
                }
                int childV = FbsLayout.createChildrenVector(fbb, flats);
                int dataChunked = FbsLayout.createFbsLayout(fbb, LAYOUT_CHUNKED, colRows, 0, childV, 0);
                colLayouts[c] = wrapZoneMap(fbb, colName, dataChunked, colRows);
                if (totalRows == 0) {
                    totalRows = colRows;
                }
            }
        }

        int rootChildV = FbsLayout.createChildrenVector(fbb, colLayouts);
        int rootLayout = FbsLayout.createFbsLayout(fbb, LAYOUT_STRUCT, totalRows, 0, rootChildV, 0);
        FbsLayout.finishFbsLayoutBuffer(fbb, rootLayout);
        return fbb.dataSegment().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    private int buildDictColLayout(FbsBuilder fbb, DictColRef ref) {
        // Build codes Chunked layout: children are one Flat per original chunk
        int numChunks = ref.codesSegIdxes().size();
        int[] codesFlats = new int[numChunks];
        long totalCodesRows = 0;
        for (int j = 0; j < numChunks; j++) {
            long rowCount = ref.codesRowCounts().get(j);
            int segV = FbsLayout.createSegmentsVector(fbb, new long[]{ref.codesSegIdxes().get(j)});
            codesFlats[j] = FbsLayout.createFbsLayout(fbb, LAYOUT_FLAT, rowCount, 0, 0, segV);
            totalCodesRows += rowCount;
        }
        int codesChildV = FbsLayout.createChildrenVector(fbb, codesFlats);
        int codesChunked = FbsLayout.createFbsLayout(fbb, LAYOUT_CHUNKED, totalCodesRows, 0, codesChildV, 0);

        // Build values Flat layout
        int valSegV = FbsLayout.createSegmentsVector(fbb, new long[]{ref.valuesSegIdx()});
        int valuesFlat = FbsLayout.createFbsLayout(fbb, LAYOUT_FLAT, ref.valuesLen(), 0, 0, valSegV);

        // DictLayoutMetadata proto (matches Rust): field 1 = codes_ptype (PType varint)
        byte[] metaBytes = buildDictLayoutMetaBytes(DictColumnState.CODES_PTYPE, ref.nullableCodes());
        int metaVec = metaBytes.length > 0 ? FbsLayout.createMetadataVector(fbb, metaBytes) : 0;

        // Dict layout: child[0]=values, child[1]=codes (matches Rust DictLayout child order)
        int[] dictChildren = {valuesFlat, codesChunked};
        int dictChildV = FbsLayout.createChildrenVector(fbb, dictChildren);
        return FbsLayout.createFbsLayout(fbb, LAYOUT_DICT, totalCodesRows, metaVec, dictChildV, 0);
    }

    /// Rust's `DictLayoutMetadata`: field 1 `codes_ptype`, field 2 `is_nullable_codes`, field 3
    /// `all_values_referenced`. Rust's writer always sets fields 2 and 3 (field 3 as `false`: its
    /// `DictLayout::new` never claims every value is referenced), so the metadata matches
    /// vortex-jni's byte for byte.
    private static byte[] buildDictLayoutMetaBytes(PType codePType, boolean nullableCodes) {
        int ordinal = codePType.ordinal();
        // Fields 2 and 3, wire type 0 (varint): tags (2<<3)|0 = 0x10 and (3<<3)|0 = 0x18
        byte[] flags = {0x10, (byte) (nullableCodes ? 1 : 0), 0x18, 0};
        if (ordinal == 0) {
            // Proto3 omits default values; U8 ordinal=0 is the default
            return flags;
        }
        // Field 1, wire type 0 (varint): tag = (1<<3)|0 = 0x08
        return new byte[]{0x08, (byte) ordinal, flags[0], flags[1], flags[2], flags[3]};
    }

    // ── Global dict helpers ───────────────────────────────────────────────────
    // Buffering state and pure encoding logic live in DictColumnState; the methods below are the
    // orchestration that needs this writer's segment-writing machinery (writeSegment, colChunks,
    // schema, options).

    /// Demotes the largest-retained global-dict candidate columns to per-chunk encoding, one at a
    /// time (largest first), until the aggregate retained bytes fall back under the budget. Demoting
    /// the largest column frees the most memory per eviction, so the fewest columns lose their shared
    /// dictionary. Called when a chunk pushes the running total over `dictRetainedBudget`.
    ///
    /// @throws IOException if writing a flushed segment fails
    private void evictLargestDictColumnsUntilUnderBudget() throws IOException {
        while (dictRetainedTotal > dictRetainedBudget && !dictRetainedBytes.isEmpty()) {
            ColumnName largest = null;
            long largestBytes = -1L;
            for (Map.Entry<ColumnName, Long> e : dictRetainedBytes.entrySet()) {
                if (e.getValue() > largestBytes) {
                    largestBytes = e.getValue();
                    largest = e.getKey();
                }
            }
            demoteDictColumn(largest);
        }
    }

    /// Abandons the shared global dictionary for one column — either because a mid-file chunk would
    /// push its distinct set past the cardinality cap, or because the aggregate code-array budget was
    /// crossed — and replays its already-buffered chunks as ordinary per-chunk segments so no data is
    /// lost (ADR 0021). Each buffered chunk is reconstructed exactly from its `short[]` codes plus the
    /// inverse code-to-value map, then written through the normal cascade path, yielding a
    /// Chunked-of-Flats layout. The buffered state is released for GC and the running retained total
    /// is decremented.
    ///
    /// @param colName the column being demoted from global-dict to per-chunk encoding
    /// @throws IOException if writing a flushed segment fails
    private void demoteDictColumn(ColumnName colName) throws IOException {
        DictColumnState state = dictStates.remove(colName);
        dictCandidates.remove(colName);
        Long freed = dictRetainedBytes.remove(colName);
        if (freed != null) {
            dictRetainedTotal -= freed;
        }
        if (state == null) {
            return;
        }
        DType colDtype = schema.fieldTypes().get(schema.fieldNames().indexOf(colName));
        Object[] inverse = state.buildInverseMap();
        for (int c = 0; c < state.chunkCount(); c++) {
            appendChunk(colName, colDtype, state.reconstructChunk(inverse, c));
        }
    }

    private void flushDictColumns() throws IOException {
        for (ColumnName colName : dictCandidates) {
            DictColumnState state = dictStates.get(colName);
            if (state == null || state.chunkCount() == 0 || state.cardinality() == 0) {
                continue;
            }
            if (state.varBin()) {
                writeGlobalDictVarBinColumn(colName, state);
            } else {
                writeGlobalDictColumn(colName, state);
            }
        }
    }

    private void writeGlobalDictColumn(ColumnName colName, DictColumnState state) throws IOException {
        // Write values segment using the same codec path as regular segments so codes benefit from
        // bitpacking/FOR when cascading is enabled. Safe: global dict is disabled for custom-encoding
        // writers (withGlobalDict(false)), so this.encodings == DEFAULT_CODECS here.
        int valuesSegIdx = writeSegment(state.dtype(), dictPool(state));
        writeGlobalDictCodes(colName, state, valuesSegIdx);
    }

    private void writeGlobalDictVarBinColumn(ColumnName colName, DictColumnState state) throws IOException {
        // Compress the distinct-values pool through the normal Utf8/Binary competition
        // (FSST/VarBin/Zstd) so it captures substring redundancy across dictionary entries (#299),
        // but exclude Dict so the cascade never wraps the (all-unique-by-construction) dictionary in
        // another dict the reader cannot unwrap. At cascade depth 0 there is no competition to run,
        // so force flat VarBin as before; a pool with a null entry is masked there instead, its
        // values still VarBin.
        Object pool = dictPool(state);
        int valuesSegIdx;
        if (options.allowedCascading() > 0) {
            valuesSegIdx = writeSegment(state.dtype(), pool, null, Set.of(EncodingId.VORTEX_DICT));
        } else if (state.nullCode() >= 0) {
            valuesSegIdx = writeSegment(state.dtype(), pool, new MaskedEncodingEncoder(), Set.of(EncodingId.VORTEX_DICT));
        } else {
            valuesSegIdx = writeSegment(state.dtype(), pool, new VarBinEncodingEncoder());
        }
        writeGlobalDictCodes(colName, state, valuesSegIdx);
    }

    // The dictionary entries in code order. A column that met a null holds one entry, invalid, that
    // every null row points at -- Rust's dict builder (vortex-array builders/dict) -- so the codes
    // need no validity of their own: Rust's dict layout writer always emits non-nullable codes.
    private static Object dictPool(DictColumnState state) {
        Object uniques = state.uniques();
        int nullCode = state.nullCode();
        if (nullCode < 0) {
            return uniques;
        }
        boolean[] poolValidity = new boolean[state.cardinality()];
        Arrays.fill(poolValidity, true);
        poolValidity[nullCode] = false;
        return new NullableData(uniques, poolValidity);
    }

    // The buffered codes are already the wire codes: first-seen order, null included. Rust's dict
    // strategy coalesces the codes like any data column, so U16 codes chunk at 512Ki rows; the
    // legacy zone map keeps one code chunk per batch, its zones.
    private void writeGlobalDictCodes(ColumnName colName, DictColumnState state, int valuesSegIdx) throws IOException {
        DType codesDtype = new DType.Primitive(DictColumnState.CODES_PTYPE, false);
        List<Integer> codesSegIdxes = new ArrayList<>();
        List<Long> codesRowCounts = new ArrayList<>();
        Repartitioner repartitioner = zonedLayout ? new Repartitioner(codesDtype) : null;
        for (int c = 0; c < state.chunkCount(); c++) {
            short[] codes = state.chunkCodes(c);
            List<Object> ready = repartitioner != null ? repartitioner.add(codes, codes.length) : List.of(codes);
            for (Object chunk : ready) {
                codesSegIdxes.add(writeSegment(codesDtype, chunk));
                codesRowCounts.add((long) ((short[]) chunk).length);
            }
        }
        Object last = repartitioner != null ? repartitioner.finish() : null;
        if (last != null) {
            codesSegIdxes.add(writeSegment(codesDtype, last));
            codesRowCounts.add((long) ((short[]) last).length);
        }
        dictColRefs.put(colName, new DictColRef(valuesSegIdx, state.cardinality(), codesSegIdxes, codesRowCounts,
                state.chunkRowCounts(), state.chunkNullCounts(),
                state.chunkStatsMin(), state.chunkStatsMax(), state.chunkStatsSum(), false));
    }

    private record SegRef(long offset, long len, SegmentStats stats) {
    }

    // S6218: the byte[] stat components are never value-compared — SegmentStats instances are only
    // read positionally, so the default identity equals is fine.
    @SuppressWarnings("java:S6218")
    private record SegmentStats(byte[] min, byte[] max, byte[] sum, long nullCount) {
    }

    private record EncodedSegment(EncodeResult result, Arena arena, SegmentStats stats) {
    }

    private record ChunkRef(int segIdx, long rowCount) {
    }

    /// Per-column zone-map: the flat segment holding the per-zone stats table, the zone
    /// count (one zone per chunk), the logical rows per zone, and whether the table carries
    /// MIN/MAX (else NULL_COUNT only).
    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record ZoneMapRef(int zonesSegIdx, long nZones, byte[] metadata) {
    }

    /// @param nullableCodes whether the codes child carries validity (Rust's `is_nullable_codes`)
    private record DictColRef(int valuesSegIdx, long valuesLen, List<Integer> codesSegIdxes,
            List<Long> codesRowCounts, List<Long> chunkRowCounts, List<Long> chunkNullCounts,
            List<byte[]> chunkStatsMin, List<byte[]> chunkStatsMax, List<byte[]> chunkStatsSum,
            boolean nullableCodes) {
        long totalRows() {
            return chunkRowCounts.stream().mapToLong(Long::longValue).sum();
        }
    }
}
