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
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.writer.encode.EncodeContext;
import io.github.dfa1.vortex.writer.encode.EncodeNode;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.writer.encode.EncodeResult;
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
import io.github.dfa1.vortex.writer.encode.DictEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ExtEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FixedSizeListEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FrameOfReferenceEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FsstEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ListEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.MaskedEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.PrimitiveEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.RleEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.RunEndEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.SparseEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.VarBinEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ZstdEncodingEncoder;
import io.github.dfa1.vortex.core.fbs.FbsArraySpec;
import io.github.dfa1.vortex.core.fbs.FbsExtension;
import io.github.dfa1.vortex.core.fbs.FbsFooter;
import io.github.dfa1.vortex.core.fbs.FbsLayout;
import io.github.dfa1.vortex.core.fbs.FbsLayoutSpec;
import io.github.dfa1.vortex.core.fbs.FbsPostscript;
import io.github.dfa1.vortex.core.fbs.FbsPostscriptSegment;
import io.github.dfa1.vortex.core.fbs.FbsSegmentSpec;
import io.github.dfa1.vortex.core.fbs.FbsType;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Writes a Vortex file.
///
/// Usage:
/// ```java
/// var schema = DType.structBuilder()
///         .field("id", DType.I64)
///         .field("value", DType.F64)
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

    // Stat ordinals in the Rust `Stat` enum (see ZonedStatsSchema). Emitted: MAX, MIN, SUM, NULL_COUNT.
    private static final int STAT_MAX = 3;
    private static final int STAT_MIN = 4;
    private static final int STAT_SUM = 5;
    private static final int STAT_NULL_COUNT = 6;

    // Columns with global cardinality below this threshold are dict-encoded across all chunks.
    // The cap is type-aware. Numeric stays low: a global dict hurts high-cardinality F64/I64
    // columns (ALP/bitpacked codes beat U16 dict codes). Utf8 is raised far higher — text columns
    // with thousands of repeated distinct values (street/place names) dictionary-compress well
    // (#299), and the per-chunk short[] code buffer holds codes 0..32767 (up to 32768 distinct)
    // with no wider buffer; codePTypeForSize already emits U16 codes above 256.
    static final int GLOBAL_DICT_MAX_CARDINALITY = 2_048;
    static final int GLOBAL_DICT_MAX_CARDINALITY_UTF8 = 32_768;

    // The global-dict cardinality cap for a column, by whether it is Utf8 (see the constants above).
    private static int dictMaxCardinality(boolean utf8) {
        return utf8 ? GLOBAL_DICT_MAX_CARDINALITY_UTF8 : GLOBAL_DICT_MAX_CARDINALITY;
    }

    private static final List<EncodingEncoder> DEFAULT_CODECS = List.of(
            new AlpEncodingEncoder(), new PrimitiveEncodingEncoder(), new BoolEncodingEncoder(),
            new DictEncodingEncoder(), new VarBinEncodingEncoder(), new ExtEncodingEncoder(),
            new FixedSizeListEncodingEncoder(), new ListEncodingEncoder());

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
    // Stats (ProtoScalarValue bytes) of the most recently written segment, captured for ChunkRef.
    private byte[] lastStatsMin;
    private byte[] lastStatsMax;
    private byte[] lastStatsSum;
    // Null count of the most recently written segment's input data (0 for dense arrays).
    private long lastNullCount;

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
        this.dictRetainedBudget = options.globalDictMaxRetainedBytes();
        this.encodings = encodings;
        this.defaultRegistry = buildRegistry(encodings);
        this.cascadeCodecs = buildCascadeCodecs(options);
        this.cascadeRegistry = buildRegistry(this.cascadeCodecs);
        for (ColumnName name : schema.fieldNames()) {
            colChunks.put(name, new ArrayList<>());
        }
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
        codecs.add(new RunEndEncodingEncoder());
        codecs.add(new RleEncodingEncoder());
        codecs.add(new SparseEncodingEncoder());
        codecs.add(new DictEncodingEncoder());
        codecs.add(new BitpackedEncodingEncoder());
        // FsstEncodingEncoder sits between Dict and VarBin. Utf8 goes through
        // CascadingCompressor's sample-and-measure competition (like Primitive dtypes),
        // not first-match dispatch, so Dict/FSST/VarBin genuinely compete on measured
        // size — matching Rust, which uses FSST for high-cardinality short strings
        // (e.g. taxi store_and_fwd_flag).
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

    private static ByteBuffer buildDType(DType dtype) {
        var fbb = new FbsBuilder(128);
        int off = serializeDType(fbb, dtype);
        io.github.dfa1.vortex.core.fbs.FbsDType.finishFbsDTypeBuffer(fbb, off);
        return fbb.dataSegment().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int serializeDType(FbsBuilder fbb, DType dtype) {
        return switch (dtype) {
            case DType.Null _ -> {
                io.github.dfa1.vortex.core.fbs.FbsNull.startFbsNull(fbb);
                int inner = io.github.dfa1.vortex.core.fbs.FbsNull.endFbsNull(fbb);
                yield io.github.dfa1.vortex.core.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsNull, inner);
            }
            case DType.Bool(var nullable) -> {
                int inner = io.github.dfa1.vortex.core.fbs.FbsBool.createFbsBool(fbb, nullable);
                yield io.github.dfa1.vortex.core.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsBool, inner);
            }
            case DType.Primitive(var ptype, var nullable) -> {
                int inner = io.github.dfa1.vortex.core.fbs.FbsPrimitive.createFbsPrimitive(
                        fbb, ptype.ordinal(), nullable);
                yield io.github.dfa1.vortex.core.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsPrimitive, inner);
            }
            case DType.Struct(var fieldNames, var fieldTypes, var nullable) -> {
                // Build child DType tables first (FlatBuffers bottom-up requirement)
                int[] fieldOffsets = new int[fieldTypes.size()];
                for (int i = 0; i < fieldOffsets.length; i++) {
                    fieldOffsets[i] = serializeDType(fbb, fieldTypes.get(i));
                }
                int[] nameOffsets = new int[fieldNames.size()];
                for (int i = 0; i < nameOffsets.length; i++) {
                    nameOffsets[i] = fbb.createString(fieldNames.get(i).value());
                }
                int namesVec = io.github.dfa1.vortex.core.fbs.FbsStruct.createNamesVector(fbb, nameOffsets);
                int dtypesVec = io.github.dfa1.vortex.core.fbs.FbsStruct.createDtypesVector(fbb, fieldOffsets);
                int inner = io.github.dfa1.vortex.core.fbs.FbsStruct.createFbsStruct(
                        fbb, namesVec, dtypesVec, nullable);
                yield io.github.dfa1.vortex.core.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsStruct, inner);
            }
            case DType.Utf8(var nullable) -> {
                int inner = io.github.dfa1.vortex.core.fbs.FbsUtf8.createFbsUtf8(fbb, nullable);
                yield io.github.dfa1.vortex.core.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsUtf8, inner);
            }
            case DType.List(var elementType, var nullable) -> {
                int elemTypeOff = serializeDType(fbb, elementType);
                int inner = io.github.dfa1.vortex.core.fbs.FbsList.createFbsList(fbb, elemTypeOff, nullable);
                yield io.github.dfa1.vortex.core.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsList, inner);
            }
            case DType.FixedSizeList(var elementType, var fixedSize, var nullable) -> {
                int elemTypeOff = serializeDType(fbb, elementType);
                int inner = io.github.dfa1.vortex.core.fbs.FbsFixedSizeList.createFbsFixedSizeList(
                        fbb, elemTypeOff, fixedSize, nullable);
                yield io.github.dfa1.vortex.core.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsFixedSizeList, inner);
            }
            case DType.Extension e -> {
                int idOff = fbb.createString(e.extensionId());
                int storageDtypeOff = serializeDType(fbb, e.storageDType());
                int metaOff = 0;
                if (e.metadata() != null) {
                    byte[] metaBytes = e.metadata().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                    metaOff = FbsExtension.createMetadataVector(fbb, metaBytes);
                }
                int inner = FbsExtension.createFbsExtension(fbb, idOff, storageDtypeOff, metaOff);
                yield io.github.dfa1.vortex.core.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsExtension, inner);
            }
            case DType.Variant(var nullable) -> {
                int inner = io.github.dfa1.vortex.core.fbs.FbsVariant.createFbsVariant(fbb, nullable);
                yield io.github.dfa1.vortex.core.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsVariant, inner);
            }
            default -> throw new UnsupportedOperationException("unsupported DType: " + dtype);
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

            if (!firstChunkSeen && options.globalDict()) {
                // Global dict candidate detection inspects raw primitive/String arrays. Nullable
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
                    candidate = isDictCandidate(p.ptype(), values, validity);
                } else if (colDtype instanceof DType.Utf8) {
                    candidate = isUtf8DictCandidate((String[]) values, validity);
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
                boolean admitted = ingestDictChunk(state, data);
                if (!admitted) {
                    // Cap breached by this chunk: demote (replaying the already-buffered chunks per
                    // -chunk) then write this chunk — which ingest rejected without buffering — too.
                    demoteDictColumn(colName);
                    long rowCount = arrayLength(data);
                    int segIdx = writeSegment(colDtype, data);
                    colChunks.get(colName).add(new ChunkRef(segIdx, rowCount, lastStatsMin, lastStatsMax, lastStatsSum, lastNullCount));
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
                long rowCount = arrayLength(data);
                int segIdx = writeSegment(colDtype, data);
                colChunks.get(colName).add(new ChunkRef(segIdx, rowCount, lastStatsMin, lastStatsMax, lastStatsSum, lastNullCount));
            }
        }
        firstChunkSeen = true;
    }

    @Override
    public void close() throws IOException {
        flushDictColumns();
        flushZoneMaps();
        ByteBuffer footerBuf = buildFooter();
        long footerOff = bytesWritten;
        write(footerBuf);

        ByteBuffer dtypeBuf = buildDType(schema);
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
        // Non-extension nullable columns (Primitive, Utf8) wrap with MaskedEncodingEncoder here.
        // FbsExtension columns route through ExtEncodingEncoder.encode which itself delegates to
        // MaskedEncodingEncoder when its storage data is NullableData — handled inside ExtEncoding.
        // Exception: a configured encoder that embeds validity itself (acceptsNullable, e.g.
        // vortex.zstd) takes the NullableData straight, so no masked wrapper is inserted.
        if (encodingOverride == null
                && data instanceof io.github.dfa1.vortex.writer.encode.NullableData
                && !(dtype instanceof DType.Extension)) {
            EncodingEncoder nullableCapable = nullableCapableEncoder(dtype);
            encodingOverride = nullableCapable != null ? nullableCapable : new MaskedEncodingEncoder();
        }
        // Variant columns bypass the cascade: the container encoding is structural, not a
        // compressible primitive codec, so route straight to the dedicated encoder.
        if (encodingOverride == null && dtype instanceof DType.Variant) {
            encodingOverride = new io.github.dfa1.vortex.writer.encode.VariantEncodingEncoder();
        }
        try (Arena arena = Arena.ofConfined()) {
            EncodeResult result;
            if (encodingOverride != null) {
                // Give overrides the cascade registry + depth when cascading is enabled, so
                // wrapping encoders (notably MaskedEncodingEncoder for nullable columns) can
                // compress their inner values through the full CascadingCompressor rather than a
                // fixed first-match encoder. Without cascading, a depth-0 context is passed and the
                // override behaves as before.
                EncodeContext encodeCtx = options.allowedCascading() > 0
                        ? EncodeContext.ofDepth(options.allowedCascading(), arena, cascadeRegistry)
                        : EncodeContext.of(arena, defaultRegistry);
                result = encodingOverride.encode(dtype, data, encodeCtx);
            } else if (options.allowedCascading() > 0) {
                EncodeContext encodeCtx = EncodeContext.ofDepth(options.allowedCascading(), arena, cascadeRegistry);
                for (EncodingId excluded : excludedFromCascade) {
                    encodeCtx = encodeCtx.withExcluded(excluded);
                }
                CascadingCompressor compressor = new CascadingCompressor(cascadeCodecs);
                result = compressor.encode(dtype, data, encodeCtx);
            } else {
                EncodingEncoder encoder = findEncoder(dtype);
                EncodeContext encodeCtx = EncodeContext.of(arena, defaultRegistry);
                result = encoder.encode(dtype, data, encodeCtx);
            }
            // Register all encoding IDs found in the node tree
            registerEncodingIds(result.rootNode());

            // Align segment start to 64 bytes so each buffer is Arrow-compatible
            long prePad = (64 - bytesWritten % 64) % 64;
            if (prePad > 0) {
                writePadding((int) prePad);
            }

            int segIdx = segs.size();
            long offset = bytesWritten;

            long segNullCount = data instanceof NullableData nd ? countNulls(nd.validity()) : 0L;
            ByteBuffer fbBuf = buildArrayFlatBuffer(result, segNullCount);

            // Segment format: [buffer data...] [FlatBuffer Array bytes] [4-byte LE u32 = fbLen]
            int fbLen = fbBuf.remaining();
            for (MemorySegment seg : result.buffers()) {
                write(seg);
            }
            write(fbBuf);
            var sizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fbLen);
            sizeBuf.flip();
            channel.write(sizeBuf);
            bytesWritten += 4;

            segs.add(new SegRef(offset, bytesWritten - offset));
            lastStatsMin = result.statsMin();
            lastStatsMax = result.statsMax();
            lastStatsSum = columnSum(dtype, data);
            lastNullCount = segNullCount;
            return segIdx;
        }
    }

    private void registerEncodingIds(EncodeNode node) {
        encodingIdx.computeIfAbsent(node.encodingId(), _ -> encodingIdx.size());
        for (EncodeNode child : node.children()) {
            registerEncodingIds(child);
        }
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

    private ByteBuffer buildArrayFlatBuffer(EncodeResult result, long nullCount) {
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
        var bufs = result.buffers();
        io.github.dfa1.vortex.core.fbs.FbsArray.startBuffersVector(fbb, bufs.size());
        for (int i = bufs.size() - 1; i >= 0; i--) {
            fbb.prep(4, 8);
            fbb.putInt((int) bufs.get(i).byteSize());
            fbb.putByte((byte) 0);   // compression = None
            fbb.putByte((byte) 6);   // alignment_exponent = 6 (64-byte alignment)
            fbb.putShort((short) 0); // padding = 0
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
        for (Map.Entry<ColumnName, List<ChunkRef>> e : colChunks.entrySet()) {
            ColumnName colName = e.getKey();
            List<ChunkRef> chunks = e.getValue();
            if (chunks.isEmpty()) {
                continue;
            }
            DType colDtype = columnDtype(colName);
            DType minMaxDtype = zoneMinMaxDtype(colDtype);
            boolean hasMinMax = minMaxDtype != null && chunks.stream().allMatch(ChunkRef::hasStats);
            DType sumDtype = zoneSumDtype(colDtype);
            long[] nullCounts = new long[chunks.size()];
            for (int i = 0; i < chunks.size(); i++) {
                nullCounts[i] = chunks.get(i).nullCount();
            }
            emitZoneMap(colName, hasMinMax ? minMaxDtype : null,
                    chunks.stream().map(ChunkRef::statsMin).toList(),
                    chunks.stream().map(ChunkRef::statsMax).toList(),
                    sumDtype, chunks.stream().map(ChunkRef::statsSum).toList(),
                    nullCounts);
        }
        // Dict-encoded columns (one zone per code chunk). MIN/MAX/SUM come from each chunk's logical
        // values (computed at dict-build time); NULL_COUNT always. Matches Rust, whose zone-map
        // stats are computed on the logical column dtype, independent of the dict encoding.
        for (Map.Entry<ColumnName, DictColRef> e : dictColRefs.entrySet()) {
            DictColRef ref = e.getValue();
            DType colDtype = columnDtype(e.getKey());
            DType minMaxDtype = zoneMinMaxDtype(colDtype);
            boolean hasMinMax = minMaxDtype != null
                    && ref.chunkStatsMin().stream().allMatch(java.util.Objects::nonNull)
                    && ref.chunkStatsMax().stream().allMatch(java.util.Objects::nonNull);
            long[] nullCounts = ref.chunkNullCounts().stream().mapToLong(Long::longValue).toArray();
            emitZoneMap(e.getKey(), hasMinMax ? minMaxDtype : null,
                    ref.chunkStatsMin(), ref.chunkStatsMax(),
                    zoneSumDtype(colDtype), ref.chunkStatsSum(), nullCounts);
        }
    }

    private DType columnDtype(ColumnName colName) {
        return schema.fieldTypes().get(schema.fieldNames().indexOf(colName));
    }

    /// Writes one `vortex.stats` zone-map for `colName`: one zone per chunk, with NULL_COUNT always,
    /// MAX/MIN (plus always-false `_is_truncated` flags) when `minMaxDtype` is non-null, and SUM when
    /// `sumDtype` is non-null. `minBytes`/`maxBytes`/`sumBytes` hold each zone's serialized scalar —
    /// read only when the matching dtype is set; a `null` `sumBytes` entry marks an overflowed zone
    /// (recorded as a null sum). Field/bit order follows ZonedStatsSchema: MAX(3), MIN(4), SUM(5),
    /// NULL_COUNT(6).
    private void emitZoneMap(ColumnName colName, DType minMaxDtype, List<byte[]> minBytes, List<byte[]> maxBytes,
                             DType sumDtype, List<byte[]> sumBytes, long[] nullCounts) throws IOException {
        int nZones = nullCounts.length;
        boolean[] allValid = new boolean[nZones];
        java.util.Arrays.fill(allValid, true);

        List<String> names = new java.util.ArrayList<>();
        List<DType> types = new java.util.ArrayList<>();
        List<Object> fields = new java.util.ArrayList<>();
        if (minMaxDtype != null) {
            boolean[] notTruncated = new boolean[nZones];
            names.add("max");
            types.add(minMaxDtype);
            fields.add(new NullableData(zoneStatValues(minMaxDtype, maxBytes), allValid.clone()));
            names.add("max_is_truncated");
            types.add(DType.BOOL);
            fields.add(notTruncated);
            names.add("min");
            types.add(minMaxDtype);
            fields.add(new NullableData(zoneStatValues(minMaxDtype, minBytes), allValid.clone()));
            names.add("min_is_truncated");
            types.add(DType.BOOL);
            fields.add(notTruncated.clone());
        }
        if (sumDtype != null) {
            boolean[] sumValid = new boolean[nZones];
            Object sumArr = sumColumn(sumDtype, sumBytes, sumValid);
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
                new ZoneMapRef(zonesSegIdx, nZones, options.chunkSize(), minMaxDtype != null, sumDtype != null));
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
        int metaV = FbsLayout.createMetadataVector(fbb, zonedMetadataBytes(zm.zoneLen(), zm.hasMinMax(), zm.hasSum()));
        return FbsLayout.createFbsLayout(fbb, LAYOUT_ZONED, colRows, metaV, childV, 0);
    }

    /// `vortex.stats` metadata: `u32` zone length (LE) + a 1-byte stat bitset (LSB-first) with the
    /// NULL_COUNT bit always set and the MAX/MIN and SUM bits set when present, matching
    /// [io.github.dfa1.vortex.reader] `ZonedStatsSchema`.
    private static byte[] zonedMetadataBytes(long zoneLen, boolean hasMinMax, boolean hasSum) {
        byte[] meta = new byte[5];
        ByteBuffer.wrap(meta).order(ByteOrder.LITTLE_ENDIAN).putInt((int) zoneLen);
        int bits = 1 << STAT_NULL_COUNT;
        if (hasMinMax) {
            bits |= (1 << STAT_MAX) | (1 << STAT_MIN);
        }
        if (hasSum) {
            bits |= (1 << STAT_SUM);
        }
        meta[4] = (byte) bits;
        return meta;
    }

    private static long countNulls(boolean[] validity) {
        long nulls = 0;
        for (boolean valid : validity) {
            if (!valid) {
                nulls++;
            }
        }
        return nulls;
    }

    /// The (nullable) dtype a zone-map stores per-zone min/max in for `dtype`, or `null` when the
    /// column has no recordable min/max. Primitives store the primitive; extension columns unwrap
    /// to their storage primitive (`ExtEncoding` propagates the storage min/max scalars unchanged);
    /// Utf8 stores the full string value. Binary is excluded:
    /// `vortex.varbin` records its min/max as string scalars, not `bytes`.
    private static DType zoneMinMaxDtype(DType dtype) {
        return switch (dtype) {
            case DType.Primitive p -> p.withNullable(true);
            case DType.Extension ext when ext.storageDType() instanceof DType.Primitive p -> p.withNullable(true);
            case DType.Utf8 u -> u.withNullable(true);
            default -> null;
        };
    }

    /// The (nullable) dtype a zone-map stores SUM in for `dtype`, or `null` when the column has no
    /// recordable sum. Only plain numeric primitives are summed — signed → `i64`, unsigned → `u64`,
    /// float → `f64` — matching Rust, which emits SUM for primitives and decimals but not for
    /// Utf8/extension/date columns even when their storage is numeric.
    private static DType zoneSumDtype(DType dtype) {
        if (!(dtype instanceof DType.Primitive p)) {
            return null;
        }
        return switch (p.ptype()) {
            case U8, U16, U32, U64 -> new DType.Primitive(PType.U64, true);
            case I8, I16, I32, I64 -> new DType.Primitive(PType.I64, true);
            case F16, F32, F64 -> new DType.Primitive(PType.F64, true);
        };
    }

    /// The serialized per-chunk SUM scalar for `data` of logical type `dtype`, or `null` when the
    /// column is not summable (non-primitive) or the sum overflowed. Validity placeholders are zero
    /// and therefore sum-neutral, so a nullable carrier sums correctly via its values.
    private static byte[] columnSum(DType dtype, Object data) {
        if (!(dtype instanceof DType.Primitive p)) {
            return null;
        }
        Object values = data instanceof NullableData nd ? nd.values() : data;
        return PrimitiveEncodingEncoder.sumStat(p.ptype(), values);
    }

    /// Builds the per-zone min (or max) values array for the resolved min/max `dtype`, decoding each
    /// zone's serialized [ProtoScalarValue] stat into the array shape its encoder expects.
    private static Object zoneStatValues(DType minMaxDtype, List<byte[]> statBytes) throws IOException {
        return switch (minMaxDtype) {
            case DType.Primitive p -> statColumn(p.ptype(), statBytes);
            case DType.Utf8 _ -> statStringColumn(statBytes);
            default -> throw new IllegalStateException("no zone stat values for " + minMaxDtype);
        };
    }

    /// Builds the per-zone SUM array for `sumDtype` (i64/u64 → `long[]`, f64 → `double[]`), decoding
    /// each zone's serialized scalar. Zones whose sum overflowed carry a `null` entry in `sumBytes`;
    /// `valid[i]` is set accordingly so the stat field reports them as null.
    private static Object sumColumn(DType sumDtype, List<byte[]> sumBytes, boolean[] valid) throws IOException {
        PType ptype = ((DType.Primitive) sumDtype).ptype();
        int n = sumBytes.size();
        if (ptype == PType.F64) {
            double[] a = new double[n];
            for (int i = 0; i < n; i++) {
                valid[i] = sumBytes.get(i) != null;
                a[i] = valid[i] ? scalarDouble(sumBytes.get(i)) : 0.0;
            }
            return a;
        }
        long[] a = new long[n];
        for (int i = 0; i < n; i++) {
            valid[i] = sumBytes.get(i) != null;
            a[i] = valid[i] ? scalarLong(sumBytes.get(i)) : 0L;
        }
        return a;
    }

    /// Builds the per-zone string array by decoding each zone's serialized string [ProtoScalarValue]
    /// stat. Used for Utf8 columns whose `vortex.varbin` encoder records full string min/max scalars.
    private static String[] statStringColumn(List<byte[]> statBytes) throws IOException {
        String[] out = new String[statBytes.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = decodeScalar(statBytes.get(i)).string_value();
        }
        return out;
    }

    /// Builds the per-zone values array in the storage shape the primitive encoder expects, decoding
    /// each zone's serialized [ProtoScalarValue] stat.
    private static Object statColumn(PType ptype, List<byte[]> statBytes) throws IOException {
        int n = statBytes.size();
        return switch (ptype) {
            case I8, U8 -> {
                byte[] a = new byte[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (byte) scalarLong(statBytes.get(i));
                }
                yield a;
            }
            case I16, U16 -> {
                short[] a = new short[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (short) scalarLong(statBytes.get(i));
                }
                yield a;
            }
            case I32, U32 -> {
                int[] a = new int[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (int) scalarLong(statBytes.get(i));
                }
                yield a;
            }
            case I64, U64 -> {
                long[] a = new long[n];
                for (int i = 0; i < n; i++) {
                    a[i] = scalarLong(statBytes.get(i));
                }
                yield a;
            }
            case F32 -> {
                float[] a = new float[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (float) scalarDouble(statBytes.get(i));
                }
                yield a;
            }
            case F64 -> {
                double[] a = new double[n];
                for (int i = 0; i < n; i++) {
                    a[i] = scalarDouble(statBytes.get(i));
                }
                yield a;
            }
            case F16 -> {
                // F16 min/max are serialized as f32 scalars; re-pack to float16 storage.
                short[] a = new short[n];
                for (int i = 0; i < n; i++) {
                    a[i] = Float.floatToFloat16((float) scalarDouble(statBytes.get(i)));
                }
                yield a;
            }
        };
    }

    private static long scalarLong(byte[] bytes) throws IOException {
        // Integer columns serialize min/max as int64 (signed) or uint64 (unsigned).
        ProtoScalarValue sv = decodeScalar(bytes);
        return sv.int64_value() != null ? sv.int64_value() : sv.uint64_value();
    }

    private static double scalarDouble(byte[] bytes) throws IOException {
        // Float columns serialize min/max as f64 (F64) or f32 (F32). Branch rather than use a
        // ternary so the F32 path widens Float -> double explicitly instead of mixing boxed types.
        ProtoScalarValue sv = decodeScalar(bytes);
        if (sv.f64_value() != null) {
            return sv.f64_value();
        }
        return sv.f32_value();
    }

    private static ProtoScalarValue decodeScalar(byte[] bytes) throws IOException {
        MemorySegment seg = MemorySegment.ofArray(bytes);
        return ProtoScalarValue.decode(seg, 0, seg.byteSize());
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
        // layout emitted as the legacy "vortex.stats" alias (old and new Rust readers accept it;
        // "vortex.zoned" would break older readers).
        int ls0 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(LayoutId.FLAT.id()));
        int ls1 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(LayoutId.CHUNKED.id()));
        int ls2 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(LayoutId.STRUCT.id()));
        int ls3 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(LayoutId.DICT.id()));
        int ls4 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(LayoutId.STATS.id()));
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
            long rowCount = ref.chunkRowCounts().get(j);
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
        PType codePType = codePTypeForSize((int) ref.valuesLen());
        byte[] metaBytes = buildDictLayoutMetaBytes(codePType);
        int metaVec = metaBytes.length > 0 ? FbsLayout.createMetadataVector(fbb, metaBytes) : 0;

        // Dict layout: child[0]=values, child[1]=codes (matches Rust DictLayout child order)
        int[] dictChildren = {valuesFlat, codesChunked};
        int dictChildV = FbsLayout.createChildrenVector(fbb, dictChildren);
        return FbsLayout.createFbsLayout(fbb, LAYOUT_DICT, totalCodesRows, metaVec, dictChildV, 0);
    }

    private static byte[] buildDictLayoutMetaBytes(PType codePType) {
        int ordinal = codePType.ordinal();
        if (ordinal == 0) {
            // Proto3 omits default values; U8 ordinal=0 is the default
            return new byte[0];
        }
        // Field 1, wire type 0 (varint): tag = (1<<3)|0 = 0x08
        return new byte[]{0x08, (byte) ordinal};
    }

    // ── Global dict helpers ───────────────────────────────────────────────────

    /// Cardinality-bounded buffering state for one global-dict candidate column (ADR 0021). Instead of
    /// retaining raw values from a column's first chunk until `close()`, this holds a deduplicated
    /// value-to-code map (first-seen order, capped at [#GLOBAL_DICT_MAX_CARDINALITY]), a parallel
    /// per-code occurrence count (used by the primitive path's frequency remap), and one cheap
    /// `short[]` code array per ingested chunk. Per-chunk stats are captured at ingest time from the
    /// raw chunk, before it is discarded.
    ///
    /// A null (invalid) slot buffers code `0` unconditionally: the raw placeholder value is never
    /// looked up in the map, matching the pre-ADR builders. The reader ignores those slots because the
    /// codes child is masked by the same per-chunk validity.
    private static final class DictColumnState {
        private final DType dtype;
        private final boolean utf8;
        private final PType ptype;
        private final boolean nullable;
        // First-seen value -> code map (keys are boxed primitives or String, matching readPrimitiveElement).
        private final Map<Object, Integer> valueToCode = new LinkedHashMap<>();
        // Occurrence count per code, indexed by code; grows in lockstep with valueToCode.
        private final List<Long> codeCounts = new ArrayList<>();
        // One code array per ingested chunk (null slots hold code 0).
        private final List<short[]> chunkCodes = new ArrayList<>();
        private final List<boolean[]> chunkValidity = new ArrayList<>();
        private final List<Long> chunkRowCounts = new ArrayList<>();
        private final List<Long> chunkNullCounts = new ArrayList<>();
        private final List<byte[]> chunkStatsMin = new ArrayList<>();
        private final List<byte[]> chunkStatsMax = new ArrayList<>();
        private final List<byte[]> chunkStatsSum = new ArrayList<>();
        private long codeArrayBytes;

        DictColumnState(DType dtype) {
            this.dtype = dtype;
            this.utf8 = dtype instanceof DType.Utf8;
            this.ptype = dtype instanceof DType.Primitive p ? p.ptype() : null;
            this.nullable = dtype.nullable();
        }

        int cardinality() {
            return valueToCode.size();
        }

        /// Approximate retained heap footprint: the buffered code arrays (2 B/row) plus the small
        /// cardinality-capped dedup map. The map footprint is bounded by the cap, so the code arrays
        /// dominate — this is the quantity the aggregate byte-budget safety net now tracks.
        long retainedBytes() {
            // ~40 B per map entry (boxed key + Integer code + LinkedHashMap.Entry) plus the codes.
            return codeArrayBytes + 40L * valueToCode.size();
        }
    }

    /// Ingests one chunk into a candidate column's cardinality-bounded dict state (ADR 0021): dedups
    /// each valid value into the shared value-to-code map, appends a per-chunk `short[]` code array,
    /// and captures the chunk's row/null counts and min/max/sum stats before the raw array is
    /// discarded. Null slots buffer code `0` and are excluded from the distinct set.
    ///
    /// Returns `false` — without mutating `state` — the moment a new distinct value would push the
    /// map past [#GLOBAL_DICT_MAX_CARDINALITY]; the caller then demotes the column to per-chunk
    /// encoding. This moves the cap check from `close()` to a continuous, mid-file guard so a column
    /// whose distinct set grows past the cap never accumulates unbounded memory first.
    ///
    /// @param state the column's buffering state (mutated in place on success)
    /// @param data  the chunk data (primitive array, `String[]`, or a [NullableData] wrapper)
    /// @return `true` if the chunk was ingested within the cardinality cap; `false` if the column
    ///         must be demoted
    private boolean ingestDictChunk(DictColumnState state, Object data) {
        boolean nullable = data instanceof NullableData;
        Object values = nullable ? ((NullableData) data).values() : data;
        boolean[] validity = nullable ? ((NullableData) data).validity() : null;
        int len = state.utf8 ? ((String[]) values).length : primitiveArrayLen(values, state.ptype);
        int cap = dictMaxCardinality(state.utf8);

        // First pass: would this chunk's fresh distinct values push the map past the cap? Count them
        // without mutating so the whole chunk is either ingested or rejected atomically — a partial
        // ingest would corrupt the dictionary when the caller demotes on rejection.
        var pendingNew = new HashSet<>(Math.min(cap, len) + 1);
        for (int i = 0; i < len; i++) {
            if (validity != null && !validity[i]) {
                continue;
            }
            Object v = state.utf8 ? ((String[]) values)[i] : readPrimitiveElement(values, state.ptype, i);
            if (v == null) {
                // Nullable Utf8 keeps a real null at invalid positions (ChunkImpl.adaptUtf8); treat it
                // as a null slot (code 0), never as a dictionary entry. Primitive placeholders never
                // reach here because their slots are guarded by validity above.
                continue;
            }
            if (!state.valueToCode.containsKey(v) && pendingNew.add(v)
                    && state.valueToCode.size() + pendingNew.size() > cap) {
                return false;
            }
        }

        // Second pass: commit — insert new values and build the per-chunk code array.
        short[] codes = new short[len];
        for (int i = 0; i < len; i++) {
            if (validity != null && !validity[i]) {
                continue;
            }
            Object v = state.utf8 ? ((String[]) values)[i] : readPrimitiveElement(values, state.ptype, i);
            if (v == null) {
                continue;
            }
            Integer code = state.valueToCode.get(v);
            if (code == null) {
                code = state.valueToCode.size();
                state.valueToCode.put(v, code);
                state.codeCounts.add(0L);
            }
            codes[i] = code.shortValue();
            state.codeCounts.set(code, state.codeCounts.get(code) + 1L);
        }

        state.chunkCodes.add(codes);
        state.chunkValidity.add(validity);
        state.chunkRowCounts.add((long) len);
        state.chunkNullCounts.add(validity != null ? countNulls(validity) : 0L);
        if (state.utf8) {
            byte[][] mm = VarBinEncodingEncoder.minMaxStats((String[]) values);
            state.chunkStatsMin.add(mm != null ? mm[0] : null);
            state.chunkStatsMax.add(mm != null ? mm[1] : null);
            state.chunkStatsSum.add(null);
        } else {
            byte[][] mm = PrimitiveEncodingEncoder.minMaxStats(state.ptype, values);
            state.chunkStatsMin.add(mm != null ? mm[0] : null);
            state.chunkStatsMax.add(mm != null ? mm[1] : null);
            state.chunkStatsSum.add(PrimitiveEncodingEncoder.sumStat(state.ptype, values));
        }
        state.codeArrayBytes += 2L * len;
        return true;
    }

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
        Object[] inverse = buildInverseMap(state);
        for (int c = 0; c < state.chunkCodes.size(); c++) {
            Object rawChunk = reconstructChunk(state, inverse, c);
            long rowCount = arrayLength(rawChunk);
            int segIdx = writeSegment(colDtype, rawChunk);
            colChunks.get(colName).add(
                    new ChunkRef(segIdx, rowCount, lastStatsMin, lastStatsMax, lastStatsSum, lastNullCount));
        }
    }

    /// Inverse of a column's first-seen value-to-code map: `inverse[code]` is the value with that
    /// code. Used by demotion to reconstruct raw chunks from their buffered code arrays.
    private static Object[] buildInverseMap(DictColumnState state) {
        Object[] inverse = new Object[state.valueToCode.size()];
        for (Map.Entry<Object, Integer> e : state.valueToCode.entrySet()) {
            inverse[e.getValue()] = e.getKey();
        }
        return inverse;
    }

    /// Reconstructs demoted chunk `c`'s raw array (a typed primitive array or `String[]`, wrapped in
    /// [NullableData] when the chunk carried validity) from its buffered `short[]` codes and the
    /// inverse code-to-value map. Null slots restore a zero/`null` placeholder — exactly what the
    /// per-chunk encoders expect from [NullableData].
    private static Object reconstructChunk(DictColumnState state, Object[] inverse, int c) {
        short[] codes = state.chunkCodes.get(c);
        boolean[] validity = state.chunkValidity.get(c);
        int len = codes.length;
        Object values;
        if (state.utf8) {
            String[] arr = new String[len];
            for (int i = 0; i < len; i++) {
                if (validity == null || validity[i]) {
                    arr[i] = (String) inverse[codes[i] & 0xFFFF];
                }
            }
            values = arr;
        } else {
            values = reconstructPrimitiveValues(state.ptype, codes, validity, inverse);
        }
        return validity != null ? new NullableData(values, validity) : values;
    }

    private static Object reconstructPrimitiveValues(PType ptype, short[] codes, boolean[] validity, Object[] inverse) {
        int len = codes.length;
        return switch (ptype) {
            case I32, U32 -> {
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) {
                    if (validity == null || validity[i]) {
                        arr[i] = (Integer) inverse[codes[i] & 0xFFFF];
                    }
                }
                yield arr;
            }
            case I64, U64 -> {
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) {
                    if (validity == null || validity[i]) {
                        arr[i] = (Long) inverse[codes[i] & 0xFFFF];
                    }
                }
                yield arr;
            }
            case F64 -> {
                double[] arr = new double[len];
                for (int i = 0; i < len; i++) {
                    if (validity == null || validity[i]) {
                        arr[i] = (Double) inverse[codes[i] & 0xFFFF];
                    }
                }
                yield arr;
            }
            default -> throw new IllegalStateException("ptype not admitted to the global dict: " + ptype);
        };
    }

    private void flushDictColumns() throws IOException {
        for (ColumnName colName : dictCandidates) {
            DictColumnState state = dictStates.get(colName);
            if (state == null || state.chunkCodes.isEmpty() || state.cardinality() == 0) {
                continue;
            }
            if (state.utf8) {
                writeGlobalDictUtf8Column(colName, state);
            } else {
                writeGlobalDictColumn(colName, state);
            }
        }
    }

    private void writeGlobalDictColumn(ColumnName colName, DictColumnState state) throws IOException {
        PType ptype = state.ptype;
        int dictSize = state.cardinality();
        PType codePType = codePTypeForSize(dictSize);

        // The incremental map assigns codes in first-seen order; the primitive path instead ranks
        // distinct values by occurrence count descending so the dominant value gets code 0. This lets
        // SparseEncodingEncoder (fill=0) compress the codes child when one value dominates — matching
        // Rust's FloatDictScheme (taxi mta_tax/Airport_fee/extra). Build the first-seen -> frequency
        // -rank remap once, then translate every buffered code array through it (one O(rows) pass, no
        // re-scan of raw values).
        int[] remap = buildFrequencyRemap(state);
        Object uniqueArr = buildFrequencyRankedUniqueArray(state, ptype, remap, dictSize);

        // Write values segment using the same codec path as regular segments so codes benefit from
        // bitpacking/FOR when cascading is enabled. Safe: global dict is disabled for custom-encoding
        // writers (withGlobalDict(false)), so this.encodings == DEFAULT_CODECS here.
        int valuesSegIdx = writeSegment(state.dtype, uniqueArr);

        DType codesDtype = new DType.Primitive(codePType, state.nullable);
        List<Integer> codesSegIdxes = new ArrayList<>();
        for (int c = 0; c < state.chunkCodes.size(); c++) {
            boolean[] validity = state.chunkValidity.get(c);
            Object codesArr = emitCodes(state.chunkCodes.get(c), remap, validity, codePType);
            Object codesData = validity != null ? new NullableData(codesArr, validity) : codesArr;
            codesSegIdxes.add(writeSegment(codesDtype, codesData));
        }

        dictColRefs.put(colName, new DictColRef(valuesSegIdx, dictSize, codesSegIdxes,
                state.chunkRowCounts, state.chunkNullCounts,
                state.chunkStatsMin, state.chunkStatsMax, state.chunkStatsSum));
    }

    private void writeGlobalDictUtf8Column(ColumnName colName, DictColumnState state) throws IOException {
        int dictSize = state.cardinality();
        PType codePType = codePTypeForSize(dictSize);

        // Utf8 assigns codes in first-seen order with no frequency sort, so the incremental map's
        // order already matches — no remap pass (ADR 0021). Compress the distinct-values pool
        // through the normal Utf8 competition (FSST/VarBin/Zstd) so it captures substring
        // redundancy across dictionary entries (#299), but exclude Dict so the cascade never wraps
        // the (all-unique-by-construction) dictionary in another dict the reader cannot unwrap. At
        // cascade depth 0 there is no competition to run, so force flat VarBin as before.
        String[] uniques = state.valueToCode.keySet().toArray(new String[0]);
        int valuesSegIdx = options.allowedCascading() > 0
                ? writeSegment(state.dtype, uniques, null, Set.of(EncodingId.VORTEX_DICT))
                : writeSegment(state.dtype, uniques, new VarBinEncodingEncoder());

        DType codesDtype = new DType.Primitive(codePType, state.nullable);
        List<Integer> codesSegIdxes = new ArrayList<>();
        for (int c = 0; c < state.chunkCodes.size(); c++) {
            boolean[] validity = state.chunkValidity.get(c);
            Object codesArr = emitCodes(state.chunkCodes.get(c), null, validity, codePType);
            Object codesData = validity != null ? new NullableData(codesArr, validity) : codesArr;
            codesSegIdxes.add(writeSegment(codesDtype, codesData));
        }

        dictColRefs.put(colName, new DictColRef(valuesSegIdx, dictSize, codesSegIdxes,
                state.chunkRowCounts, state.chunkNullCounts,
                state.chunkStatsMin, state.chunkStatsMax, state.chunkStatsSum));
    }

    /// Builds the first-seen -> frequency-rank code remap for the primitive dict path. Distinct values
    /// are ranked by their (incrementally tracked) occurrence count descending, so the dominant value
    /// gets rank 0. `remap[firstSeenCode]` is the frequency-rank code. Ties keep first-seen order,
    /// matching the pre-ADR stable sort on a first-seen-ordered `LinkedHashMap`.
    private static int[] buildFrequencyRemap(DictColumnState state) {
        int n = state.cardinality();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        // Stable sort by count descending; equal counts preserve first-seen (ascending index) order.
        java.util.Arrays.sort(order, (a, b) -> Long.compare(state.codeCounts.get(b), state.codeCounts.get(a)));
        int[] remap = new int[n];
        for (int rank = 0; rank < n; rank++) {
            remap[order[rank]] = rank;
        }
        return remap;
    }

    /// Builds the primitive dictionary's unique-values array in frequency-rank order: slot `rank`
    /// holds the value whose first-seen code remaps to `rank`. Only the carriers [#isDictCandidate]
    /// admits — I32/U32, I64/U64, F64 — reach here.
    private static Object buildFrequencyRankedUniqueArray(DictColumnState state, PType ptype, int[] remap, int dictSize) {
        Object[] byRank = new Object[dictSize];
        for (Map.Entry<Object, Integer> e : state.valueToCode.entrySet()) {
            byRank[remap[e.getValue()]] = e.getKey();
        }
        return switch (ptype) {
            case I32, U32 -> {
                int[] a = new int[dictSize];
                for (int i = 0; i < dictSize; i++) {
                    a[i] = (Integer) byRank[i];
                }
                yield a;
            }
            case I64, U64 -> {
                long[] a = new long[dictSize];
                for (int i = 0; i < dictSize; i++) {
                    a[i] = (Long) byRank[i];
                }
                yield a;
            }
            case F64 -> {
                double[] a = new double[dictSize];
                for (int i = 0; i < dictSize; i++) {
                    a[i] = (Double) byRank[i];
                }
                yield a;
            }
            default -> throw new IllegalStateException("ptype not admitted to the global dict: " + ptype);
        };
    }

    /// Emits one chunk's wire codes array (U8/U16/U32) from its buffered `short[]` first-seen codes,
    /// optionally translated through a frequency remap (primitive path) and masking null slots to
    /// code `0`. A null slot (validity[i] false) emits code `0` unconditionally, never remapped: the
    /// reader ignores those slots because the codes child is masked by the same validity.
    ///
    /// @param buffered the buffered first-seen codes for one chunk
    /// @param remap    the first-seen -> frequency-rank remap, or `null` to emit codes unchanged
    /// @param validity per-row validity, or `null` when every row is valid
    /// @param codePType the wire code ptype chosen from the dictionary size
    /// @return a `byte[]`, `short[]`, or `int[]` codes array matching `codePType`
    private static Object emitCodes(short[] buffered, int[] remap, boolean[] validity, PType codePType) {
        int len = buffered.length;
        return switch (codePType) {
            case U8 -> {
                byte[] codes = new byte[len];
                for (int i = 0; i < len; i++) {
                    if (validity == null || validity[i]) {
                        int fs = buffered[i] & 0xFFFF;
                        codes[i] = (byte) (remap == null ? fs : remap[fs]);
                    }
                }
                yield codes;
            }
            case U16 -> {
                short[] codes = new short[len];
                for (int i = 0; i < len; i++) {
                    if (validity == null || validity[i]) {
                        int fs = buffered[i] & 0xFFFF;
                        codes[i] = (short) (remap == null ? fs : remap[fs]);
                    }
                }
                yield codes;
            }
            default -> {
                int[] codes = new int[len];
                for (int i = 0; i < len; i++) {
                    if (validity == null || validity[i]) {
                        int fs = buffered[i] & 0xFFFF;
                        codes[i] = remap == null ? fs : remap[fs];
                    }
                }
                yield codes;
            }
        };
    }

    static boolean isUtf8DictCandidate(String[] data) {
        return isUtf8DictCandidate(data, null);
    }

    /// Like [#isUtf8DictCandidate(String[])] but ignores null (invalid) rows when counting distinct
    /// values, so a nullable low-cardinality column still qualifies for the shared global dictionary.
    /// The ratio denominator stays the total row count (not the valid-row count), matching the
    /// per-chunk encoders' convention that null placeholders occupy a row like any other value.
    ///
    /// @param data     the string values; null elements at invalid positions are skipped
    /// @param validity per-row validity bitmap, or `null` meaning every row is valid
    /// @return `true` if the column's distinct valid-value count is low enough to dictionary-encode
    static boolean isUtf8DictCandidate(String[] data, boolean[] validity) {
        if (data.length == 0) {
            return false;
        }
        var seen = new java.util.HashSet<String>(Math.min(GLOBAL_DICT_MAX_CARDINALITY_UTF8, data.length) + 1);
        for (int i = 0; i < data.length; i++) {
            if ((validity != null && !validity[i]) || data[i] == null) {
                continue;
            }
            seen.add(data[i]);
            if (seen.size() > GLOBAL_DICT_MAX_CARDINALITY_UTF8) {
                return false;
            }
        }
        if (seen.isEmpty()) {
            return false;
        }
        return seen.size() * 2 < data.length;
    }

    static boolean isDictCandidate(PType ptype, Object data) {
        return isDictCandidate(ptype, data, null);
    }

    /// Like [#isDictCandidate(PType, Object)] but ignores null (invalid) rows when counting distinct
    /// values, so a nullable low-cardinality column still qualifies for the shared global dictionary.
    /// Null slots hold zero-valued placeholders (per NullableData's contract); skipping them keeps a
    /// legitimate `0` value from being deduplicated against those placeholders and keeps nulls out of
    /// the distinct count. The ratio denominator stays the total row count (not the valid-row count),
    /// matching the per-chunk encoders' convention that a null placeholder occupies a row like any
    /// other value.
    ///
    /// @param ptype    the column's primitive type
    /// @param data     the packed values array; positions marked invalid by `validity` are skipped
    /// @param validity per-row validity bitmap, or `null` meaning every row is valid
    /// @return `true` if the column's distinct valid-value count is low enough to dictionary-encode
    static boolean isDictCandidate(PType ptype, Object data, boolean[] validity) {
        // Only the carriers the reader's lazy dict decode supports (I32/I64/F64) are admitted.
        // - I8/U8/I16/U16 excluded: dict gives little/no benefit (a U8/U16 code is no smaller
        //   than the value), the Rust compressor does not dict them either (verified by
        //   RustWritesJavaReadsIntegrationTest#jniWriter_javaReader_lowCardinalityI16), and the
        //   reader cannot decode a narrow-int dict — emitting one produced an unreadable file.
        // - F16/F32 excluded: no measured workload; ALP usually wins.
        // F64 admitted: low-card F64 columns (taxi mta_tax/Airport_fee/extra) compress better via
        // global dict + sparse-coded codes (matches Rust FloatDictScheme). The skip rule
        // (cardinality / 2 below) mirrors Rust's >50%-distinct skip.
        if (ptype == PType.I8 || ptype == PType.U8
                || ptype == PType.I16 || ptype == PType.U16
                || ptype == PType.F16 || ptype == PType.F32) {
            return false;
        }
        int n = primitiveArrayLen(data, ptype);
        if (n == 0) {
            return false;
        }
        var seen = new HashSet<>(GLOBAL_DICT_MAX_CARDINALITY + 1);
        for (int i = 0; i < n; i++) {
            if (validity != null && !validity[i]) {
                continue;
            }
            seen.add(readPrimitiveElement(data, ptype, i));
            if (seen.size() > GLOBAL_DICT_MAX_CARDINALITY) {
                return false;
            }
        }
        if (seen.isEmpty()) {
            return false;
        }
        // Single-value columns fit vortex.constant better than dict (zero dict overhead).
        // Delegate to the cascading compressor.
        if (seen.size() == 1) {
            return false;
        }
        return seen.size() * 2 < n;
    }

    /// Length of a global-dict column's chunk array. Only the dict-admitted carriers ([#isDictCandidate])
    /// — I32/U32, I64/U64, F64 — reach here; narrow-int and F16/F32 ptypes are rejected upstream.
    static int primitiveArrayLen(Object data, PType ptype) {
        return switch (ptype) {
            case I32, U32 -> ((int[]) data).length;
            case I64, U64 -> ((long[]) data).length;
            case F64 -> ((double[]) data).length;
            default -> throw new IllegalStateException("ptype not admitted to the global dict: " + ptype);
        };
    }

    /// Boxed element `i` of a global-dict column's chunk array. Only the dict-admitted carriers
    /// ([#isDictCandidate]) — I32/U32, I64/U64, F64 — reach here; narrow-int and F16/F32 ptypes are
    /// rejected upstream.
    static Object readPrimitiveElement(Object data, PType ptype, int i) {
        return switch (ptype) {
            case I32, U32 -> ((int[]) data)[i];
            case I64, U64 -> ((long[]) data)[i];
            case F64 -> ((double[]) data)[i];
            default -> throw new IllegalStateException("ptype not admitted to the global dict: " + ptype);
        };
    }

    static PType codePTypeForSize(int dictSize) {
        if (dictSize <= 256) {
            return PType.U8;
        }
        if (dictSize <= 65_536) {
            return PType.U16;
        }
        return PType.U32;
    }

    private record SegRef(long offset, long len) {
    }

    // S6218: the byte[] stat components are never value-compared — ChunkRef instances are only
    // collected in per-column lists and read positionally, so the default identity equals is fine.
    @SuppressWarnings("java:S6218")
    private record ChunkRef(int segIdx, long rowCount, byte[] statsMin, byte[] statsMax,
            byte[] statsSum, long nullCount) {
        boolean hasStats() {
            return statsMin != null && statsMax != null;
        }
    }

    /// Per-column zone-map: the flat segment holding the per-zone stats table, the zone
    /// count (one zone per chunk), the logical rows per zone, and whether the table carries
    /// MIN/MAX (else NULL_COUNT only).
    private record ZoneMapRef(int zonesSegIdx, long nZones, long zoneLen, boolean hasMinMax, boolean hasSum) {
    }

    private record DictColRef(int valuesSegIdx, long valuesLen, List<Integer> codesSegIdxes,
            List<Long> chunkRowCounts, List<Long> chunkNullCounts,
            List<byte[]> chunkStatsMin, List<byte[]> chunkStatsMax, List<byte[]> chunkStatsSum) {
        long totalRows() {
            return chunkRowCounts.stream().mapToLong(Long::longValue).sum();
        }
    }
}
