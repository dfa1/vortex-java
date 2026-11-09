package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.jfr.SegmentEncodedEvent;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.Edition;
import io.github.dfa1.vortex.core.model.EditionFamily;
import io.github.dfa1.vortex.core.model.Editions;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.writer.encode.AlpEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.AlpRdEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.BitpackedEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.BoolEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.CascadingCompressor;
import io.github.dfa1.vortex.writer.encode.ConstantEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DateTimePartsEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DecimalBytePartsEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DecimalEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DeltaEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DictEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.EncodeContext;
import io.github.dfa1.vortex.writer.encode.EncodeResult;
import io.github.dfa1.vortex.writer.encode.EncodeTrace;
import io.github.dfa1.vortex.writer.encode.EncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ExtEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FixedSizeListEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FrameOfReferenceEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FsstEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ListEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.MapEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.MaskedEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.MinMax;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.OnPairEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.PcoEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.PrimitiveEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.RleEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.RowCount;
import io.github.dfa1.vortex.writer.encode.RunEndEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.SequenceEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.SparseEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.VarBinEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.VariantEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ZigZagEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ZstdEncodingEncoder;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/// Picks and runs the encoders for one [VortexWriter]'s segments: the default and cascade candidate
/// lists, the dict-layout probe, per-column [ColumnEncoding] overrides and the edition guard.
///
/// Immutable once built, so every encoding task on [WriteOptions#executor()] shares one instance
/// without synchronization; the writer keeps all mutable state on its own thread.
final class SegmentEncoder {

    static final List<EncodingEncoder> DEFAULT_CODECS = List.of(
            new AlpEncodingEncoder(), new PrimitiveEncodingEncoder(), new BoolEncodingEncoder(),
            new DictEncodingEncoder(), new VarBinEncodingEncoder(), new ExtEncodingEncoder(),
            new FixedSizeListEncodingEncoder(), new ListEncodingEncoder(), new DecimalEncodingEncoder());

    // values() clones its array on every call; the edition filter reads it once per writer.
    private static final List<EncodingId.WellKnown> WELL_KNOWN_IDS = List.of(EncodingId.WellKnown.values());

    private final WriteOptions options;
    // Only the legacy vortex.stats zone map records a segment's sum: under vortex.zoned it is never read.
    private final boolean segmentSums;
    // a copy, so a caller's list changed after create() cannot race the encoding tasks
    private final List<EncodingEncoder> encodings;
    // whether the writer was created with DEFAULT_CODECS rather than a caller's encoder list
    private final boolean defaultCodecs;
    private final WriteRegistry defaultRegistry;
    private final List<EncodingEncoder> cascadeCodecs;
    private final WriteRegistry cascadeRegistry;
    // The dict-layout probe's codecs: the cascade with the integer dictionary back in, as Rust's
    // probe is its full compressor while its data compressor leaves IntDictScheme out.
    private final List<EncodingEncoder> probeCodecs;
    private final WriteRegistry probeRegistry;
    // Edition guard (issue #301): the cumulative member set of every WriteOptions#editions()
    // family enabled for this writer, or empty when no edition is configured (guard off).
    // editionExcluded is its complement over every well-known encoding id plus the custom encoders this writer holds
    // (encodings + cascadeCodecs) - seeded into every EncodeContext's initial `excluded` set so
    // CascadingCompressor's existing per-candidate exclusion check (already consulted at every
    // selection site, including nested competitions like a masked column's validity-bitmap
    // cascade) skips ineligible candidates and gracefully falls back, rather than the guard only
    // discovering a violation after the fact. checkEdition still checks editionAllowed
    // directly as a backstop for paths that select an encoding without consulting excluded at all
    // (e.g. a forced encodingOverride, or MaskedEncodingEncoder#encodeValidity's own top-level
    // Sparse/RunEnd/Rle/Bool choice, which are all core-family and so never actually trip this in
    // practice - see adr/0023-vortex-editions-adoption.md).
    private final Set<EncodingId> editionAllowed;
    private final Set<EncodingId> editionExcluded;
    // WriteOptions.columnEncodings resolved to the encoder each overridden column is written with.
    private final Map<ColumnName, EncodingEncoder> columnEncoders;

    /// @throws IllegalArgumentException if a [ColumnEncoding] names a column outside `columns` or an
    ///         encoding this writer cannot produce
    SegmentEncoder(WriteOptions options, List<EncodingEncoder> encodings, List<ColumnName> columns, boolean segmentSums) {
        this.options = options;
        this.segmentSums = segmentSums;
        this.defaultCodecs = encodings == DEFAULT_CODECS;
        this.encodings = List.copyOf(encodings);
        this.defaultRegistry = buildRegistry(this.encodings);
        // A custom encoder list is the whole candidate set, cascade included: emitting a built-in
        // the caller left out would produce a file their own read registry may not decode.
        this.cascadeCodecs = defaultCodecs ? buildCascadeCodecs(options) : this.encodings;
        this.cascadeRegistry = buildRegistry(this.cascadeCodecs);
        this.probeCodecs = this.cascadeCodecs.stream()
                .map(e -> e instanceof DictEncodingEncoder ? new DictEncodingEncoder() : e)
                .toList();
        this.probeRegistry = buildRegistry(this.probeCodecs);
        this.editionAllowed = editionAllowed(options.editions());
        // before the column encoders, which seed every context they build with it
        this.editionExcluded = editionExcluded(this.editionAllowed, this.encodings, this.cascadeCodecs);
        Map<ColumnName, EncodingEncoder> resolved = new LinkedHashMap<>();
        for (Map.Entry<ColumnName, ColumnEncoding> e : options.columnEncodings().entrySet()) {
            if (!columns.contains(e.getKey())) {
                throw new IllegalArgumentException("column encoding for unknown column: " + e.getKey());
            }
            resolved.put(e.getKey(), buildColumnEncoder(e.getKey(), e.getValue()));
        }
        this.columnEncoders = Map.copyOf(resolved);
    }

    /// The encoder a column's [ColumnEncoding] override resolved to, or `null` when the column has none.
    EncodingEncoder columnEncoder(ColumnName column) {
        return columnEncoders.get(column);
    }

    /// Encodes one segment and computes its stats: the CPU-bound half of a segment write, run on
    /// [WriteOptions#executor()]. The returned arena is shared so the writing thread can read and
    /// close it.
    ///
    /// `excludedFromCascade` lets the global Utf8 dictionary path compress its values pool
    /// through the normal Utf8 competition (FSST/VarBin/Zstd) while excluding
    /// [DictEncodingEncoder] — the cascade would otherwise re-pick it and wrap the dictionary
    /// in another dict the reader cannot unwrap.
    ///
    /// @param trace               the column and segment being encoded, for the compressor's JFR events
    /// @param dtype               logical type of the segment
    /// @param data                the values to encode
    /// @param encodingOverride    a specific encoder to force, or `null` to use the cascade
    /// @param excludedFromCascade encoding ids to exclude from the cascade competition
    /// @return the encoded segment, owning its arena
    EncodedSegment encode(EncodeTrace trace, DType dtype, Object data, EncodingEncoder encodingOverride,
            Set<EncodingId> excludedFromCascade) {
        var event = new SegmentEncodedEvent();
        event.begin();
        EncodingEncoder override = resolveOverride(dtype, data, encodingOverride);
        Arena arena = Arena.ofShared();
        try {
            EncodeResult result;
            if (override != null) {
                // Give overrides the cascade registry + depth when cascading is enabled, so
                // wrapping encoders (notably MaskedEncodingEncoder for nullable columns) can
                // compress their inner values through the full CascadingCompressor rather than a
                // fixed first-match encoder. Without cascading, a depth-0 context is passed and the
                // override behaves as before.
                EncodeContext encodeCtx = EncodeContext.of(arena, defaultRegistry, editionExcluded).withTrace(trace);
                if (options.allowedCascading() > 0) {
                    encodeCtx = EncodeContext.ofDepth(options.allowedCascading(), arena, cascadeRegistry, editionExcluded)
                            .withTrace(trace);
                }
                // A wrapping override (masked) cascades its inner values, so the exclusions hold
                // there too -- a nullable global-dict pool must never become a dict itself.
                encodeCtx = encodeCtx.withExcluded(excludedFromCascade);
                result = override.encode(dtype, data, encodeCtx);
            } else if (options.allowedCascading() > 0) {
                EncodeContext encodeCtx = EncodeContext.ofDepth(options.allowedCascading(), arena, cascadeRegistry, editionExcluded)
                        .withTrace(trace);
                for (EncodingId excluded : excludedFromCascade) {
                    encodeCtx = encodeCtx.withExcluded(excluded);
                }
                CascadingCompressor compressor = new CascadingCompressor(cascadeCodecs);
                result = compressor.encode(dtype, data, encodeCtx);
            } else {
                EncodingEncoder encoder = findEncoder(dtype);
                EncodeContext encodeCtx = EncodeContext.of(arena, defaultRegistry, editionExcluded).withTrace(trace);
                result = encoder.encode(dtype, data, encodeCtx);
            }
            long nullCount = data instanceof NullableData nd ? nd.nullCount() : 0L;
            // The winning encoder's own stats win when present (a cheaper override, e.g.
            // vortex.constant already knows its value is both extremes) -- otherwise the generic
            // fallback computes them from the untouched input, independent of which encoder ran.
            // This is what makes stats coverage an encoder-independent guarantee rather than a
            // per-encoder convention every new encoder has to remember (ADR 0025).
            ProtoScalarValue min;
            ProtoScalarValue max;
            if (result.hasStats()) {
                min = result.statsMin();
                max = result.statsMax();
            } else {
                MinMax fallback = ZoneMapStatCodec.columnMinMax(dtype, data);
                min = fallback != null ? fallback.min() : null;
                max = fallback != null ? fallback.max() : null;
            }
            ProtoScalarValue sum = null;
            if (segmentSums) {
                sum = ZoneMapStatCodec.columnSum(dtype, data);
            }
            SegmentStats stats = new SegmentStats(min, max, sum, nullCount);
            // the stats the flat segment's root node carries are Rust's, not the zone map's
            result = result.withRootStats(dtype, min, max, RowCount.of(data), nullCount);
            if (event.shouldCommit()) {
                List<MemorySegment> buffers = result.buffers();
                long encodedSize = 0;
                for (MemorySegment buffer : buffers) {
                    encodedSize += buffer.byteSize();
                }
                event.column = trace.column();
                event.segment = trace.segment();
                event.encoding = result.rootNode().encodingId().id();
                event.bufferCount = buffers.size();
                event.encodedSize = encodedSize;
                event.commit();
            }
            return new EncodedSegment(result, arena, stats);
        } catch (RuntimeException | Error e) {
            arena.close();
            throw e;
        }
    }

    /// Rust's `DictStrategy` admission: the column gets a dict layout only when the full cascade,
    /// its probe compressor, turns the first 8192-row block into a `vortex.dict`
    /// (`vortex-layout/src/layouts/dict/writer.rs`). Without a cascade there is no compressor to
    /// ask, and the cardinality check alone decides. `column` only labels the probe's JFR events.
    boolean probeCompressesToDict(ColumnName column, DType colDtype, Object data) {
        if (options.allowedCascading() == 0) {
            return true;
        }
        Object block = Repartitioner.head(data, Math.min(Repartitioner.BLOCK_ROWS, RowCount.of(data)));
        try (Arena arena = Arena.ofConfined()) {
            EncodeContext ctx = EncodeContext.ofDepth(options.allowedCascading(), arena, probeRegistry, editionExcluded)
                    .withTrace(EncodeTrace.of(column.value(), -1));
            EncodeResult result = new CascadingCompressor(probeCodecs).encode(colDtype, block, ctx);
            return result.rootNode().encodingId().equals(EncodingId.VORTEX_DICT);
        }
    }

    /// Encodes a `vortex.zoned` stats table through the cascade at any depth and with any encoder
    /// list, as Rust's stats strategy always compresses it: [CascadingCompressor] encodes the
    /// struct itself (including the nullable `bounded_max` state) and falls back to canonical
    /// encodings for any field no candidate accepts.
    ///
    /// The candidates are the probe's: Rust compresses stats tables with its full compressor,
    /// integer dictionaries included, and keeps `IntDictScheme` out of the data compressor only
    /// (`vortex-file` `strategy.rs`), so a zone's `null_count` column can be a `vortex.dict`.
    EncodingEncoder zoneTableEncoder() {
        List<EncodingEncoder> codecs = probeCodecs;
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

    /// Edition guard backstop (issue #301): `editionExcluded`, seeded into every [EncodeContext],
    /// already steers [CascadingCompressor] away from out-of-edition candidates during selection;
    /// this direct check catches whatever still reaches a written segment anyway: a forced
    /// encoding override, or a selection path that does not consult [EncodeContext#excluded()] at
    /// all. Never silently write a file outside the declared edition.
    ///
    /// @throws VortexException if `id` is outside every configured edition
    void checkEdition(EncodingId id) {
        if (!editionAllowed.isEmpty() && !editionAllowed.contains(id)) {
            throw editionViolation(id);
        }
    }

    private VortexException editionViolation(EncodingId id) {
        String configured = options.editions().values().stream()
                .map(e -> e.id().toString())
                .sorted()
                .collect(Collectors.joining(", "));
        Optional<Edition> owning = Editions.owningEdition(id);
        String hint = "; it is not part of any edition — only WriteOptions.withoutEditions() permits it";
        if (owning.isPresent()) {
            hint = "; it joins " + owning.get().id() + " — enable that edition via WriteOptions.withEdition(...)";
        }
        return new VortexException(id, "outside the configured edition(s) [" + configured + "]" + hint);
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
            return new MapEncodingEncoder();
        }
        // With cascading on, a nullable primitive column goes to the compressor, which keeps the
        // validity in the encoding that stores the values, as Rust does (#604)
        // and so does a nullable struct, list or text column, which carry it as a child of their own (#648)
        boolean keepsValidity = options.allowedCascading() > 0
                && (CascadingCompressor.keepsValidity(dtype) || CascadingCompressor.carriesValidity(dtype));
        if (encodingOverride == null
                && data instanceof NullableData
                && !(dtype instanceof DType.Extension) && !keepsValidity) {
            EncodingEncoder nullableCapable = nullableCapableEncoder(dtype);
            return nullableCapable != null ? nullableCapable : new MaskedEncodingEncoder();
        }
        // Variant columns bypass the cascade: the container encoding is structural, not a
        // compressible primitive codec, so route straight to the dedicated encoder.
        if (encodingOverride == null && dtype instanceof DType.Variant) {
            return new VariantEncodingEncoder();
        }
        return encodingOverride;
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
    /// so nullable columns keep the masked layout. Text stays masked too: its first match, a
    /// dictionary, needs the compressor's distinct count to apply.
    private EncodingEncoder nullableCapableEncoder(DType dtype) {
        if (options.allowedCascading() > 0 || dtype instanceof DType.Utf8 || dtype instanceof DType.Binary) {
            return null;
        }
        for (EncodingEncoder c : encodings) {
            if (c.accepts(dtype) && c.acceptsNullable(dtype)) {
                return c;
            }
        }
        return null;
    }

    /// The encoder a column with a [ColumnEncoding] override is written with: the cascade over the
    /// column's candidates only, wrapping nullable data in `vortex.masked` as the default path does.
    ///
    /// @throws IllegalArgumentException if this writer has no encoder for one of the encodings
    private EncodingEncoder buildColumnEncoder(ColumnName column, ColumnEncoding encoding) {
        // The encoders this writer cascades with; a default writer also offers the other built-ins
        // (Pco, Zstd, …) that are registered but not default candidates. A custom encoder list stays
        // the whole set, as it is for every other column.
        Map<EncodingId, EncodingEncoder> available = new LinkedHashMap<>();
        for (EncodingEncoder enc : cascadeCodecs) {
            available.putIfAbsent(enc.encodingId(), enc);
        }
        if (defaultCodecs) {
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
                        registry, editionExcluded).withTrace(ctx.trace());
                // a decimal, struct, list or text column keeps its validity in its own encoding, as on
                // the default path
                boolean masked = data instanceof NullableData && !(dtype instanceof DType.Extension)
                        && !(options.allowedCascading() > 0 && CascadingCompressor.carriesValidity(dtype));
                if (masked) {
                    return new MaskedEncodingEncoder().encode(dtype, data, columnCtx);
                }
                return new CascadingCompressor(encoders).encode(dtype, data, columnCtx);
            }
        };
    }

    /// The union of every enabled edition's cumulative member set, or empty if no edition is
    /// configured — an empty result means the guard is off entirely.
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
        for (EncodingId.WellKnown id : WELL_KNOWN_IDS) {
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

    /// Builds a [WriteRegistry] from the given encoder list.
    private static WriteRegistry buildRegistry(List<EncodingEncoder> encoders) {
        WriteRegistry.Builder b = WriteRegistry.builder();
        for (EncodingEncoder e : encoders) {
            b.register(e);
        }
        return b.build();
    }

    private static List<EncodingEncoder> buildCascadeCodecs(WriteOptions options) {
        List<EncodingEncoder> codecs = new ArrayList<>();
        // FbsExtension-dtype dispatch order matters: findPrimitiveEncoding picks the first
        // accepting codec. DateTimePartsEncoding goes first: it splits any vortex.timestamp
        // storage (DateTimePartsData, long[], or NullableData over long[] with its validity on
        // the days child); for other extensions, Days units or an all-null chunk it returns
        // notApplicable and spliceResult excludes it, falling back to ExtEncoding, which
        // cascades the storage child through FoR/Bitpacked/RLE/ALP.
        // FixedSizeListEncoding handles UUID-style fixed-size byte storage downstream
        // of ExtEncoding.
        codecs.add(new DateTimePartsEncodingEncoder());
        codecs.add(new ExtEncodingEncoder());
        codecs.add(new FixedSizeListEncodingEncoder());
        codecs.add(new ListEncodingEncoder());
        codecs.add(new ConstantEncodingEncoder());
        // The primitive schemes in Rust's registration order (vortex-btrblocks session.rs), which
        // breaks a tie between equal scores: integers FoR, ZigZag, BitPacking, Sparse, IntDict,
        // RunEnd, Sequence, RLE, Delta; floats ALP, ALP-RD, FloatDict, sparse, RLE. One list
        // serves both, so Dict sits before Sparse as the floats have it; for integers the two only
        // meet in the probe and zone tables, on an exact tie of two estimates.
        codecs.add(new FrameOfReferenceEncodingEncoder());
        // ZigZag folds signed values to small unsigned magnitudes and cascades them into
        // bit-packing, as Rust's ZigZagScheme does (issue #410). It competes with FoR, which
        // always fits in as few bits, so it is here for parity rather than for size.
        codecs.add(new ZigZagEncodingEncoder());
        codecs.add(new BitpackedEncodingEncoder());
        codecs.add(new AlpEncodingEncoder());
        // ALP-RD competes for high-precision F64/F32 that plain ALP can't fit without too many
        // exceptions (e.g. nyc-311 Latitude): without it in the competition such columns fall back
        // to raw vortex.primitive or dict, matching neither the Rust reference (which uses alprd) nor
        // its size (#304). Registered on WriteRegistry already; this adds it as a top-level candidate.
        codecs.add(new AlpRdEncodingEncoder());
        // Rust's file writer keeps integers out of the data cascade because its DictStrategy
        // dictionary-encodes suitable columns as a layout. That is our global dictionary: with it
        // off (a streaming CSV import cannot buffer a whole column), an integer chunk keeps its
        // per-chunk dictionary, or a low-cardinality integer column loses its dictionary entirely.
        codecs.add(options.globalDict() ? DictEncodingEncoder.forDataCascade() : new DictEncodingEncoder());
        codecs.add(new SparseEncodingEncoder());
        codecs.add(new RunEndEncodingEncoder());
        // Rust's SequenceScheme: an exact integer arithmetic sequence costs two scalars (#507).
        codecs.add(new SequenceEncodingEncoder());
        codecs.add(new RleEncodingEncoder());
        // Delta is in no edition ("no edition includes fastlanes.delta yet"), so like Rust's
        // DeltaScheme it competes only when the edition guard is off (WriteOptions#withoutEditions).
        Set<EncodingId> allowed = editionAllowed(options.editions());
        if (allowed.isEmpty() || allowed.contains(EncodingId.FASTLANES_DELTA)) {
            codecs.add(new DeltaEncodingEncoder());
        }
        // Decimals: byte-parts (precision <= 18) narrows its mantissa and cascades it through
        // FoR/bit-packing, as Rust's DecimalScheme always does (AlwaysUse); it is found first, and
        // plain vortex.decimal takes what it declines (wider precisions, chunks under two values).
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
        if (options.compact()) {
            // Rust's with_compact(): Zstd for strings and binary, Pco for numerics.
            codecs.add(ZstdEncodingEncoder.forText());
            codecs.add(new PcoEncodingEncoder());
        }
        codecs.add(new PrimitiveEncodingEncoder());
        codecs.add(new BoolEncodingEncoder());
        return List.copyOf(codecs);
    }

    /// One segment's zone-map stats, computed on the logical values before encoding.
    record SegmentStats(ProtoScalarValue min, ProtoScalarValue max, ProtoScalarValue sum, long nullCount) {
    }

    /// An encoded segment awaiting its ordered write; `arena` owns its buffers.
    record EncodedSegment(EncodeResult result, Arena arena, SegmentStats stats) {
    }
}
