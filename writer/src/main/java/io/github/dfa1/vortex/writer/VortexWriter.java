package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.Edition;
import io.github.dfa1.vortex.core.model.EditionFamily;
import io.github.dfa1.vortex.core.model.Editions;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.writer.encode.EncodingEncoder;
import io.github.dfa1.vortex.writer.encode.RowCount;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/// Writes a Vortex file.
///
/// Usage:
/// ```java
/// var schema = DType.structBuilder()
///         .field(ColumnName.of("id"), DTypes.I64)
///         .field(ColumnName.of("value"), DTypes.F64)
///         .build();
/// try (var channel = FileChannel.open(path, CREATE, WRITE);
///      var writer = VortexWriter.create(channel, schema, WriteOptions.defaults())) {
///     writer.writeChunk(Map.of(ColumnName.of("id"), idArray, ColumnName.of("value"), valueArray));
/// }
/// ```
///
/// With global dictionary encoding enabled (the default), candidate columns are buffered in the heap
/// until `close()` so a shared dictionary can span every chunk. Buffering is bounded (ADR 0021): each
/// candidate retains a deduplicated value-to-code map of at most 65,535 entries or 1 MiB per
/// dictionary, as Rust's dict layout does, plus cheap per-chunk code arrays; a column that fills a
/// dictionary starts the next, so retained memory scales with the open dictionary and the row count,
/// not the raw byte size. [WriteOptions#globalDictMaxRetainedBytes()] (default 1 GB) remains a
/// secondary safety net over the aggregate code-array bytes.
public final class VortexWriter implements Closeable {

    private static final WriteRegistry DEFAULT_EXTENSIONS = WriteRegistry.builder().registerDefaultExtensions().build();

    private final DType.Struct schema;
    private final WriteOptions options;
    // The only writer state the encoding tasks on WriteOptions#executor() touch; immutable.
    private final SegmentEncoder segmentEncoder;
    private final SegmentSink sink;
    // Extension encoders for the Collection auto-route: the built-ins, or the caller's registry.
    private final WriteRegistry extensionRegistry;
    /// Serializes the schema at close, reusing the extension metadata bytes checked at create.
    private final DTypeFbsSerializer dtypeSerializer;
    // Rust's vortex.zoned: fixed 8192-row zones fed from the raw batches, independent of chunking.
    // Used when the targeted core edition includes the layout; otherwise the legacy per-chunk
    // vortex.stats zone map, whose targets also keep one chunk per batch.
    private final boolean zonedLayout;
    private final GlobalDictWriter globalDict;
    private final ZoneMapWriter zoneMaps;
    private final Map<ColumnName, List<ChunkRef>> colChunks;
    // Rust's repartition: each column's batches coalesce into ~1 MB chunks of 8192-row multiples.
    private final Map<ColumnName, Repartitioner> repartitioners;
    // The batch being written: its columns' piece sizes, measured on the executor ahead of appendChunk.
    private final Map<ColumnName, CompletableFuture<long[]>> pieceSizes;
    private boolean firstChunkSeen;

    private VortexWriter(
            WritableByteChannel channel, DType.Struct schema, WriteOptions options, List<EncodingEncoder> encodings,
            WriteRegistry extensionRegistry) {
        // A nullable struct layout carries a leading validity child (Rust's validate_children:
        // nfields + 1 children); the root layout written here has one child per column and no way
        // to mark a row null, so Rust refused every such file ("nchildren == expected").
        if (schema.nullable()) {
            throw new IllegalArgumentException("the root struct cannot be nullable: rows cannot be null");
        }
        // Wire contract, enforced by the reference writer ("StructLayout must have unique field
        // names"): duplicate-name schemas are constructible via the DType.Struct record (only
        // StructBuilder validates), so guard here — colChunks below is name-keyed and would
        // silently collapse the duplicates anyway. The name policy itself (non-blank, no control
        // characters — NUL additionally aborts the reference toolchain's Arrow FFI export) is
        // already certified by the ColumnName type carried in schema.fieldNames().
        var uniqueNames = new HashSet<ColumnName>();
        for (ColumnName name : schema.fieldNames()) {
            if (!uniqueNames.add(name)) {
                throw new IllegalArgumentException("duplicate field name: " + name);
            }
        }
        this.schema = schema;
        this.options = options;
        this.extensionRegistry = extensionRegistry;
        this.dtypeSerializer = new DTypeFbsSerializer(extensionRegistry);
        validateExtensions(schema);
        this.zonedLayout = options.enableZoneMaps() && editionHasZonedLayout(options.editions());
        this.segmentEncoder = new SegmentEncoder(options, encodings, schema.fieldNames());
        this.sink = new SegmentSink(channel, segmentEncoder, options.executor());
        this.globalDict = new GlobalDictWriter(options, segmentEncoder, sink, zonedLayout, this::appendChunk);
        this.zoneMaps = new ZoneMapWriter(schema, options, zonedLayout, segmentEncoder, sink);
        this.colChunks = new LinkedHashMap<>();
        for (ColumnName name : schema.fieldNames()) {
            colChunks.put(name, new ArrayList<>());
        }
        this.repartitioners = new LinkedHashMap<>();
        this.pieceSizes = new HashMap<>();
        this.firstChunkSeen = false;
    }

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

    /// Runs [ExtensionEncoder#validate(DType.Extension)] on every extension dtype in `dtype`, nested
    /// ones included, that has a registered encoder — Rust validates each extension dtype it builds.
    /// Also serializes each one's metadata once, so metadata no encoder can serialize fails here
    /// rather than when the file is closed, after its data is written.
    private void validateExtensions(DType dtype) {
        switch (dtype) {
            case DType.Extension ext -> {
                ExtensionId id = ext.extensionId();
                ExtensionEncoder encoder = extensionRegistry.lookup(id);
                if (encoder != null) {
                    encoder.validate(ext);
                } else if (id instanceof ExtensionId.WellKnown wellKnown) {
                    // written from its storage array; still held to Rust's rules for the id
                    wellKnown.validate(ext);
                }
                dtypeSerializer.metadataBytes(ext);
                validateExtensions(ext.storageDType());
            }
            case DType.Struct struct -> struct.fieldTypes().forEach(this::validateExtensions);
            case DType.List list -> validateExtensions(list.elementType());
            case DType.FixedSizeList list -> validateExtensions(list.elementType());
            case DType.Map map -> {
                validateExtensions(map.keyType());
                validateExtensions(map.valueType());
            }
            case DType.Union union -> union.variantTypes().forEach(this::validateExtensions);
            case DType.Null _, DType.Bool _, DType.Primitive _, DType.Decimal _, DType.Utf8 _,
                 DType.Binary _, DType.Variant _ -> {
                // no nested dtype
            }
        }
    }

    /// Creates a [VortexWriter] using the default encoder set.
    ///
    /// @param channel the channel to write to
    /// @param schema  the struct schema for the file
    /// @param options write options
    /// @return a new writer
    public static VortexWriter create(WritableByteChannel channel, DType.Struct schema, WriteOptions options) {
        return new VortexWriter(channel, schema, options, SegmentEncoder.DEFAULT_CODECS, DEFAULT_EXTENSIONS);
    }

    /// Creates a [VortexWriter] with a custom encoder list.
    ///
    /// @param channel   the channel to write to
    /// @param schema    the struct schema for the file
    /// @param options   write options
    /// @param encodings custom encoder list
    /// @return a new writer
    public static VortexWriter create(WritableByteChannel channel, DType.Struct schema, WriteOptions options, List<EncodingEncoder> encodings) {
        // Custom encoding list: disable global dict — using DEFAULT_CODECS for values/codes behind the scenes
        // would violate the user's expectation that only their encoding list is used.
        return new VortexWriter(channel, schema, options.withGlobalDict(false), encodings, DEFAULT_EXTENSIONS);
    }

    /// Creates a [VortexWriter] with a custom [WriteRegistry].
    ///
    /// @param channel  the channel to write to
    /// @param schema   the struct schema for the file
    /// @param options  write options
    /// @param registry write registry supplying encoders and extensions
    /// @return a new writer
    public static VortexWriter create(WritableByteChannel channel, DType.Struct schema, WriteOptions options, WriteRegistry registry) {
        return new VortexWriter(channel, schema, options.withGlobalDict(false),
                List.copyOf(registry.encoderMap().values()), registry);
    }

    /// Counts rows for the length-consistency check in [#writeChunk]. Accepts the
    /// same shapes the writer takes plus pre-conversion [Collection]s
    /// from the extension-column auto-route path.
    private static long rowCountForValidation(ColumnName colName, Object data) {
        if (data instanceof Collection<?> coll) {
            return coll.size();
        }
        try {
            return RowCount.of(data);
        } catch (UnsupportedOperationException _) {
            throw new IllegalArgumentException(
                    "column '" + colName + "' has unsupported data type: "
                            + data.getClass().getSimpleName());
        }
    }

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
    public void writeChunk(Consumer<Chunk> builder) throws IOException {
        var impl = new ChunkImpl(schema);
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

        pieceSizes.clear();
        // Route extension columns first, so the columns' batches can be sized in parallel below.
        for (int i = 0; i < schema.fieldNames().size(); i++) {
            ColumnName colName = schema.fieldNames().get(i);
            DType colDtype = schema.fieldTypes().get(i);
            Object data = adapted.get(colName);

            // Auto-route extension columns: callers can pass List<LocalDate>, List<Instant>,
            // etc., and we route through the matching spec extension to produce the int[] /
            // long[] / byte[] storage array. The dtype stays as DType.Extension so
            // ExtEncoding wraps the storage child below — matches Rust's nested layout
            // (ExtEncoding → PrimitiveEncoding) and lets Registry skip its unwrap path.
            if (colDtype instanceof DType.Extension extDtype && data instanceof Collection<?> coll) {
                ExtensionEncoder impl = extensionRegistry.lookup(extDtype.extensionId());
                if (impl == null) {
                    // Without an encoder the Collection would reach the encoders unconverted and
                    // fail far from the cause; raw storage arrays still pass through.
                    throw new VortexException("column '" + colName + "': no extension encoder registered for "
                            + extDtype.extensionId() + "; register one or pass the storage array");
                }
                data = impl.encodeAll(extDtype, coll);
                adapted.put(colName, data);
            }

            // Sizing a string batch reads every string and was the caller thread's largest cost
            // of an import. Columns are independent, so the pool sizes them all at once.
            if (zonedLayout && Repartitioner.supports(colDtype) && Repartitioner.sizeReadsValues(data)
                    && !globalDict.isCandidate(colName)) {
                // Only read the column's own Repartitioner: creating it here would change the order
                // flushRepartitioners writes the last chunks in.
                Repartitioner own = repartitioners.get(colName);
                long firstRow = own == null ? 0 : own.rows();
                Object batch = data;
                pieceSizes.put(colName, CompletableFuture.supplyAsync(
                        () -> Repartitioner.sizePieces(batch, RowCount.of(batch), firstRow), options.executor()));
            }
        }

        for (int i = 0; i < schema.fieldNames().size(); i++) {
            ColumnName colName = schema.fieldNames().get(i);
            DType colDtype = schema.fieldTypes().get(i);
            Object data = adapted.get(colName);

            zoneMaps.add(colName, colDtype, data);
            if (!firstChunkSeen) {
                globalDict.detect(colName, colDtype, data);
            }
            if (globalDict.isCandidate(colName)) {
                globalDict.ingest(colName, colDtype, data);
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
        CompletableFuture<long[]> sizes = pieceSizes.remove(colName);
        for (Object chunk : repartitioner.add(data, RowCount.of(data), sizes != null ? sizes.join() : null)) {
            writeDataChunk(colName, colDtype, chunk);
        }
    }

    private void writeDataChunk(ColumnName colName, DType colDtype, Object data) throws IOException {
        long rowCount = RowCount.of(data);
        int segIdx = writeSegment(colDtype, data, segmentEncoder.columnEncoder(colName));
        colChunks.get(colName).add(new ChunkRef(segIdx, rowCount));
    }

    /// Writes every column's pending rows as its last chunk or chunks.
    private void flushRepartitioners() throws IOException {
        for (Map.Entry<ColumnName, Repartitioner> e : repartitioners.entrySet()) {
            for (Object last : e.getValue().finish()) {
                writeDataChunk(e.getKey(), columnDtype(e.getKey()), last);
            }
        }
    }

    @Override
    public void close() throws IOException {
        flushRepartitioners();
        globalDict.flush();
        zoneMaps.flush(colChunks, globalDict.written());
        // the zoned layout: Rust's "vortex.zoned" when the edition has it, else the legacy
        // "vortex.stats" alias every reader accepts
        LayoutId zoned = LayoutId.STATS;
        if (zonedLayout) {
            zoned = LayoutId.ZONED;
        }
        sink.finish(zoned, dtypeSerializer.buildDType(schema),
                LayoutTree.build(schema, colChunks, globalDict.written(), zoneMaps.written()));
    }

    private int writeSegment(DType dtype, Object data, EncodingEncoder encodingOverride) throws IOException {
        return sink.write(dtype, data, encodingOverride, Set.of());
    }

    private DType columnDtype(ColumnName colName) {
        return schema.fieldTypes().get(schema.fieldNames().indexOf(colName));
    }

    /// One written chunk of a column: its segment and row count.
    record ChunkRef(int segIdx, long rowCount) {
    }
}
