package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.writer.encode.DateTimePartsData;
import io.github.dfa1.vortex.writer.encode.FixedSizeListData;
import io.github.dfa1.vortex.writer.encode.ListData;
import io.github.dfa1.vortex.writer.encode.ListViewData;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.writer.encode.EncodeContext;
import io.github.dfa1.vortex.writer.encode.EncodeNode;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.writer.encode.EncodeResult;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.StructData;
import io.github.dfa1.vortex.writer.encode.StructEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.EncodingEncoder;
import io.github.dfa1.vortex.writer.encode.AlpEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.BitpackedEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.BoolEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.CascadingCompressor;
import io.github.dfa1.vortex.writer.encode.ConstantEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DateTimePartsEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DictEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ExtEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FixedSizeListEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FrameOfReferenceEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FsstEncodingEncoder;
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
/// var schema = new DType.Struct(List.of("id", "value"),
///                               List.of(DType.I64,
///                                       DType.F64), false);
/// try (var channel = FileChannel.open(path, CREATE, WRITE);
///      var writer = VortexWriter.create(channel, schema, WriteOptions.defaults())) {
///     writer.writeChunk(Map.of("id", idArray, "value", valueArray));
/// }
/// ```
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
    // Kept low: global dict hurts high-cardinality F64 columns (ALP codes beat U16 dict codes).
    static final int GLOBAL_DICT_MAX_CARDINALITY = 2_048;

    private static final List<EncodingEncoder> DEFAULT_CODECS = List.of(
            new AlpEncodingEncoder(), new PrimitiveEncodingEncoder(), new BoolEncodingEncoder(),
            new DictEncodingEncoder(), new VarBinEncodingEncoder(), new ExtEncodingEncoder(),
            new FixedSizeListEncodingEncoder());

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
    private final Map<String, List<ChunkRef>> colChunks = new LinkedHashMap<>();
    private final Map<EncodingId, Integer> encodingIdx = new LinkedHashMap<>();
    private long bytesWritten = 0;

    // Global dict state: columns detected as low-cardinality on first chunk are buffered
    // here instead of encoded per-chunk. Flushed in close() as a single Dict layout.
    private final Set<String> dictCandidates = new LinkedHashSet<>();
    private final Map<String, List<Object>> dictBuffers = new LinkedHashMap<>();
    private final Map<String, DictColRef> dictColRefs = new LinkedHashMap<>();
    private boolean firstChunkSeen = false;

    // Per-column zone-maps, populated by flushZoneMaps() in close() when enableZoneMaps is set.
    private final Map<String, ZoneMapRef> zoneMaps = new LinkedHashMap<>();
    // Stats (ProtoScalarValue bytes) of the most recently written segment, captured for ChunkRef.
    private byte[] lastStatsMin;
    private byte[] lastStatsMax;
    private byte[] lastStatsSum;
    // Null count of the most recently written segment's input data (0 for dense arrays).
    private long lastNullCount;

    private VortexWriter(
            WritableByteChannel channel, DType.Struct schema, WriteOptions options, List<EncodingEncoder> encodings
    ) {
        this.channel = channel;
        this.schema = schema;
        this.options = options;
        this.encodings = encodings;
        this.defaultRegistry = buildRegistry(encodings);
        this.cascadeCodecs = buildCascadeCodecs(options);
        this.cascadeRegistry = buildRegistry(this.cascadeCodecs);
        for (String name : schema.fieldNames()) {
            colChunks.put(name, new ArrayList<>());
        }
    }

    /// Builds a [WriteRegistry] from the given encoder list plus all service-loaded extensions.
    private static WriteRegistry buildRegistry(List<EncodingEncoder> encoders) {
        WriteRegistry.Builder b = WriteRegistry.builder();
        for (EncodingEncoder e : encoders) {
            b.register(e);
        }
        for (ExtensionEncoder ext :
                java.util.ServiceLoader.load(ExtensionEncoder.class)) {
            b.register(ext);
        }
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
        codecs.add(new ConstantEncodingEncoder());
        codecs.add(new AlpEncodingEncoder());
        codecs.add(new FrameOfReferenceEncodingEncoder());
        codecs.add(new RunEndEncodingEncoder());
        codecs.add(new RleEncodingEncoder());
        codecs.add(new SparseEncodingEncoder());
        codecs.add(new DictEncodingEncoder());
        codecs.add(new BitpackedEncodingEncoder());
        // FsstEncodingEncoder sits between Dict and VarBin. Today's non-primitive dispatch
        // (CascadingCompressor.findPrimitiveEncoding) is first-match, so Dict still
        // wins for Utf8; FSST only fires when Dict is excluded (cascade nested re-runs
        // via spliceResult's notApplicable retry). Listing it here matches Rust which
        // uses FSST for high-cardinality short strings (e.g. taxi store_and_fwd_flag).
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
    private static long rowCountForValidation(String colName, Object data) {
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
            case DType.Bool b -> {
                int inner = io.github.dfa1.vortex.core.fbs.FbsBool.createFbsBool(fbb, b.nullable());
                yield io.github.dfa1.vortex.core.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsBool, inner);
            }
            case DType.Primitive p -> {
                int inner = io.github.dfa1.vortex.core.fbs.FbsPrimitive.createFbsPrimitive(
                        fbb, p.ptype().ordinal(), p.nullable());
                yield io.github.dfa1.vortex.core.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsPrimitive, inner);
            }
            case DType.Struct s -> {
                // Build child DType tables first (FlatBuffers bottom-up requirement)
                int[] fieldOffsets = new int[s.fieldTypes().size()];
                for (int i = 0; i < fieldOffsets.length; i++) {
                    fieldOffsets[i] = serializeDType(fbb, s.fieldTypes().get(i));
                }
                int[] nameOffsets = new int[s.fieldNames().size()];
                for (int i = 0; i < nameOffsets.length; i++) {
                    nameOffsets[i] = fbb.createString(s.fieldNames().get(i));
                }
                int namesVec = io.github.dfa1.vortex.core.fbs.FbsStruct_.createNamesVector(fbb, nameOffsets);
                int dtypesVec = io.github.dfa1.vortex.core.fbs.FbsStruct_.createDtypesVector(fbb, fieldOffsets);
                int inner = io.github.dfa1.vortex.core.fbs.FbsStruct_.createFbsStruct_(
                        fbb, namesVec, dtypesVec, s.nullable());
                yield io.github.dfa1.vortex.core.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsStruct_, inner);
            }
            case DType.Utf8 u -> {
                int inner = io.github.dfa1.vortex.core.fbs.FbsUtf8.createFbsUtf8(fbb, u.nullable());
                yield io.github.dfa1.vortex.core.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsUtf8, inner);
            }
            case DType.List l -> {
                int elemTypeOff = serializeDType(fbb, l.elementType());
                int inner = io.github.dfa1.vortex.core.fbs.FbsList.createFbsList(fbb, elemTypeOff, l.nullable());
                yield io.github.dfa1.vortex.core.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsList, inner);
            }
            case DType.FixedSizeList fsl -> {
                int elemTypeOff = serializeDType(fbb, fsl.elementType());
                int inner = io.github.dfa1.vortex.core.fbs.FbsFixedSizeList.createFbsFixedSizeList(
                        fbb, elemTypeOff, fsl.fixedSize(), fsl.nullable());
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
            case DType.Variant v -> {
                int inner = io.github.dfa1.vortex.core.fbs.FbsVariant.createFbsVariant(fbb, v.nullable());
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
    ///     .put("timestamp", new long[]   {1_700_000_000_000L, 1_700_000_001_000L})
    ///     .put("symbol",    new String[] {"AAPL", "AAPL"})
    ///     .put("price",     new double[] {189.95, 190.10})
    ///     .put("volume",    new Long[]   {100L, null}));  // boxed → nullable
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
    /// @param columns map from column name to typed array data
    /// @throws IOException              if an I/O error occurs writing to the underlying channel
    /// @throws IllegalArgumentException if a schema column is missing from `columns`,
    ///         or if column arrays disagree on row count
    public void writeChunk(Map<String, Object> columns) throws IOException {
        // Adapt each column up front so the map entry point accepts the same shapes as the
        // builder: boxed nullable arrays (Long[], Integer[], Boolean[], …) become NullableData,
        // raw primitive arrays pass through. Done before the row-count check so length validation
        // and encoding both see the normalized carrier.
        Map<String, Object> adapted = new LinkedHashMap<>();
        for (int i = 0; i < schema.fieldNames().size(); i++) {
            String colName = schema.fieldNames().get(i);
            Object data = columns.get(colName);
            if (data == null) {
                throw new IllegalArgumentException("missing column: " + colName);
            }
            adapted.put(colName, ChunkImpl.validateAndAdapt(colName, schema.fieldTypes().get(i), data));
        }

        // Pre-validate row counts so a length mismatch is rejected with a clear error
        // before any data is serialised. Without this check, the writer would produce a
        // file whose column chunks claim different row counts — readable but logically
        // inconsistent.
        long expectedLen = -1L;
        String expectedFrom = null;
        for (int i = 0; i < schema.fieldNames().size(); i++) {
            String colName = schema.fieldNames().get(i);
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
            String colName = schema.fieldNames().get(i);
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

            if (!firstChunkSeen && options.globalDict()
                    && !(data instanceof io.github.dfa1.vortex.writer.encode.NullableData)) {
                // Global dict candidate detection inspects raw primitive/String arrays; nullable
                // columns route through MaskedEncoding instead of DictEncoding.
                boolean candidate = false;
                if (colDtype instanceof DType.Primitive p) {
                    candidate = isDictCandidate(p.ptype(), data);
                } else if (colDtype instanceof DType.Utf8) {
                    candidate = isUtf8DictCandidate((String[]) data);
                }
                if (candidate) {
                    dictCandidates.add(colName);
                }
            }

            if (dictCandidates.contains(colName)) {
                dictBuffers.computeIfAbsent(colName, _ -> new ArrayList<>()).add(data);
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

    /// Writes a segment, optionally forcing a specific `encodingOverride` instead of
    /// the configured cascade. Used by the global Utf8 dictionary path where the values
    /// segment must be flat varbin — the cascade would otherwise re-pick
    /// [DictEncodingEncoder] and wrap the dictionary in another dict (which the reader
    /// cannot unwrap).
    private int writeSegment(DType dtype, Object data, EncodingEncoder encodingOverride) throws IOException {
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
                EncodeContext encodeCtx = EncodeContext.of(arena, defaultRegistry);
                result = encodingOverride.encode(dtype, data, encodeCtx);
            } else if (options.allowedCascading() > 0) {
                EncodeContext encodeCtx = EncodeContext.ofDepth(options.allowedCascading(), arena, cascadeRegistry);
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
        // forceDefaults only while building ArrayStats, so null_count = 0 is serialised (flatbuffers
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
        for (Map.Entry<String, List<ChunkRef>> e : colChunks.entrySet()) {
            String colName = e.getKey();
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
        for (Map.Entry<String, DictColRef> e : dictColRefs.entrySet()) {
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

    private DType columnDtype(String colName) {
        return schema.fieldTypes().get(schema.fieldNames().indexOf(colName));
    }

    /// Writes one `vortex.stats` zone-map for `colName`: one zone per chunk, with NULL_COUNT always,
    /// MAX/MIN (plus always-false `_is_truncated` flags) when `minMaxDtype` is non-null, and SUM when
    /// `sumDtype` is non-null. `minBytes`/`maxBytes`/`sumBytes` hold each zone's serialised scalar —
    /// read only when the matching dtype is set; a `null` `sumBytes` entry marks an overflowed zone
    /// (recorded as a null sum). Field/bit order follows ZonedStatsSchema: MAX(3), MIN(4), SUM(5),
    /// NULL_COUNT(6).
    private void emitZoneMap(String colName, DType minMaxDtype, List<byte[]> minBytes, List<byte[]> maxBytes,
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

        DType.Struct statsDtype = new DType.Struct(List.copyOf(names), List.copyOf(types), false);
        int zonesSegIdx = writeSegment(statsDtype, new StructData(fields), new StructEncodingEncoder());
        zoneMaps.put(colName,
                new ZoneMapRef(zonesSegIdx, nZones, options.chunkSize(), minMaxDtype != null, sumDtype != null));
    }

    /// Wraps a column's data layout in a `vortex.stats` (zoned) layout when a zone-map was
    /// emitted for it; otherwise returns the data layout unchanged.
    private int wrapZoneMap(FbsBuilder fbb, String colName, int dataLayout, long colRows) {
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
    /// Utf8 stores the full string value. Matches [ZonedStatsSchema#statDtype]. Binary is excluded:
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

    /// The serialised per-chunk SUM scalar for `data` of logical type `dtype`, or `null` when the
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
    /// zone's serialised [ProtoScalarValue] stat into the array shape its encoder expects.
    private static Object zoneStatValues(DType minMaxDtype, List<byte[]> statBytes) throws IOException {
        return switch (minMaxDtype) {
            case DType.Primitive p -> statColumn(p.ptype(), statBytes);
            case DType.Utf8 ignored -> statStringColumn(statBytes);
            default -> throw new IllegalStateException("no zone stat values for " + minMaxDtype);
        };
    }

    /// Builds the per-zone SUM array for `sumDtype` (i64/u64 → `long[]`, f64 → `double[]`), decoding
    /// each zone's serialised scalar. Zones whose sum overflowed carry a `null` entry in `sumBytes`;
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

    /// Builds the per-zone string array by decoding each zone's serialised string [ProtoScalarValue]
    /// stat. Used for Utf8 columns whose `vortex.varbin` encoder records full string min/max scalars.
    private static String[] statStringColumn(List<byte[]> statBytes) throws IOException {
        String[] out = new String[statBytes.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = decodeScalar(statBytes.get(i)).string_value();
        }
        return out;
    }

    /// Builds the per-zone values array in the storage shape the primitive encoder expects, decoding
    /// each zone's serialised [ProtoScalarValue] stat.
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
                // F16 min/max are serialised as f32 scalars; re-pack to float16 storage.
                short[] a = new short[n];
                for (int i = 0; i < n; i++) {
                    a[i] = Float.floatToFloat16((float) scalarDouble(statBytes.get(i)));
                }
                yield a;
            }
        };
    }

    private static long scalarLong(byte[] bytes) throws IOException {
        // Integer columns serialise min/max as int64 (signed) or uint64 (unsigned).
        ProtoScalarValue sv = decodeScalar(bytes);
        return sv.int64_value() != null ? sv.int64_value() : sv.uint64_value();
    }

    private static double scalarDouble(byte[] bytes) throws IOException {
        // Float columns serialise min/max as f64 (F64) or f32 (F32). Branch rather than use a
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

        // layout_specs: ["vortex.flat", "vortex.chunked", "vortex.struct", "vortex.dict"]
        int ls0 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString("vortex.flat"));
        int ls1 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString("vortex.chunked"));
        int ls2 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString("vortex.struct"));
        int ls3 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString("vortex.dict"));
        int ls4 = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString("vortex.stats"));
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
            String colName = schema.fieldNames().get(c);
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

    private void flushDictColumns() throws IOException {
        for (String colName : dictCandidates) {
            List<Object> chunks = dictBuffers.getOrDefault(colName, List.of());
            if (chunks.isEmpty()) {
                continue;
            }
            int colIdx = schema.fieldNames().indexOf(colName);
            DType colDtype = schema.fieldTypes().get(colIdx);
            if (colDtype instanceof DType.Primitive p) {
                writeGlobalDictColumn(colName, p, chunks);
            } else if (colDtype instanceof DType.Utf8 u) {
                writeGlobalDictUtf8Column(colName, u, chunks);
            }
        }
    }

    private void writeGlobalDictColumn(String colName, DType.Primitive dtype, List<Object> chunks)
            throws IOException {
        PType ptype = dtype.ptype();

        // Count occurrences per distinct value across all chunks, then assign codes in
        // frequency-descending order so the dominant value gets code 0. This lets
        // SparseEncodingEncoder (fill=0) compress the codes child when one value dominates —
        // matches Rust's FloatDictScheme path that powers the dict+sparse layout on taxi
        // mta_tax/Airport_fee/extra columns.
        var counts = new LinkedHashMap<Object, Long>();
        for (Object chunk : chunks) {
            int len = primitiveArrayLen(chunk, ptype);
            for (int i = 0; i < len; i++) {
                Object v = readPrimitiveElement(chunk, ptype, i);
                counts.merge(v, 1L, Long::sum);
            }
        }
        List<Map.Entry<Object, Long>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<Object, Long>comparingByValue().reversed());
        var valueMap = new LinkedHashMap<Object, Integer>();
        for (Map.Entry<Object, Long> entry : sorted) {
            valueMap.put(entry.getKey(), valueMap.size());
        }

        int dictSize = valueMap.size();
        if (dictSize > GLOBAL_DICT_MAX_CARDINALITY) {
            // Cardinality exceeded threshold after seeing all data — fall back to per-chunk
            for (Object chunk : chunks) {
                long rowCount = arrayLength(chunk);
                int segIdx = writeSegment(dtype, chunk);
                colChunks.get(colName).add(new ChunkRef(segIdx, rowCount, lastStatsMin, lastStatsMax, lastStatsSum, lastNullCount));
            }
            return;
        }

        PType codePType = codePTypeForSize(dictSize);

        // Write values segment using the same codec path as regular segments so
        // codes benefit from bitpacking/FOR when cascading is enabled.
        // Safe: global dict is disabled for custom-encoding writers (withGlobalDict(false)),
        // so this.encodings == DEFAULT_CODECS here and the two-arg form is always valid.
        Object uniqueArr = buildTypedUniqueArray(ptype, valueMap.keySet(), dictSize);
        int valuesSegIdx = writeSegment(dtype, uniqueArr);

        // Write one codes segment per original chunk
        DType codesDtype = new DType.Primitive(codePType, false);
        List<Integer> codesSegIdxes = new ArrayList<>();
        List<Long> chunkRowCounts = new ArrayList<>();
        List<Long> chunkNullCounts = new ArrayList<>();
        List<byte[]> chunkStatsMin = new ArrayList<>();
        List<byte[]> chunkStatsMax = new ArrayList<>();
        List<byte[]> chunkStatsSum = new ArrayList<>();
        for (Object chunk : chunks) {
            int len = primitiveArrayLen(chunk, ptype);
            Object codesArr = buildCodesArray(chunk, ptype, valueMap, codePType, len);
            codesSegIdxes.add(writeSegment(codesDtype, codesArr));
            chunkRowCounts.add((long) len);
            chunkNullCounts.add(chunk instanceof NullableData nd ? countNulls(nd.validity()) : 0L);
            // Per-zone min/max + sum over the chunk's logical values (matches the flat primitive
            // path: computed on nd.values(), placeholders included). Lets the dict zone-map prune
            // and aggregate like a plain primitive column.
            Object values = chunk instanceof NullableData nd ? nd.values() : chunk;
            byte[][] mm = PrimitiveEncodingEncoder.minMaxStats(ptype, values);
            chunkStatsMin.add(mm != null ? mm[0] : null);
            chunkStatsMax.add(mm != null ? mm[1] : null);
            chunkStatsSum.add(PrimitiveEncodingEncoder.sumStat(ptype, values));
        }

        dictColRefs.put(colName, new DictColRef(valuesSegIdx, dictSize, codesSegIdxes,
                chunkRowCounts, chunkNullCounts, chunkStatsMin, chunkStatsMax, chunkStatsSum));
    }

    private void writeGlobalDictUtf8Column(String colName, DType.Utf8 dtype, List<Object> chunks)
            throws IOException {
        // Build global string -> code map across all chunks (insertion order = code value).
        var valueMap = new LinkedHashMap<String, Integer>();
        for (Object chunk : chunks) {
            for (String s : (String[]) chunk) {
                valueMap.computeIfAbsent(s, _ -> valueMap.size());
            }
        }

        int dictSize = valueMap.size();
        if (dictSize > GLOBAL_DICT_MAX_CARDINALITY) {
            // Cardinality exceeded threshold after seeing all data — fall back to per-chunk.
            for (Object chunk : chunks) {
                long rowCount = arrayLength(chunk);
                int segIdx = writeSegment(dtype, chunk);
                colChunks.get(colName).add(new ChunkRef(segIdx, rowCount, lastStatsMin, lastStatsMax, lastStatsSum, lastNullCount));
            }
            return;
        }

        PType codePType = codePTypeForSize(dictSize);

        // Write unique strings as a flat VarBin segment — bypass cascade so DictEncoding
        // doesn't wrap the (all-unique-by-construction) dictionary in another dict that
        // the reader's decodeDictLayout cannot unwrap.
        String[] uniques = valueMap.keySet().toArray(new String[0]);
        int valuesSegIdx = writeSegment(dtype, uniques, new VarBinEncodingEncoder());

        // Write one codes segment per original chunk.
        DType codesDtype = new DType.Primitive(codePType, false);
        List<Integer> codesSegIdxes = new ArrayList<>();
        List<Long> chunkRowCounts = new ArrayList<>();
        List<Long> chunkNullCounts = new ArrayList<>();
        List<byte[]> chunkStatsMin = new ArrayList<>();
        List<byte[]> chunkStatsMax = new ArrayList<>();
        for (Object chunk : chunks) {
            String[] strs = (String[]) chunk;
            Object codesArr = buildUtf8CodesArray(strs, valueMap, codePType);
            codesSegIdxes.add(writeSegment(codesDtype, codesArr));
            chunkRowCounts.add((long) strs.length);
            chunkNullCounts.add(0L); // global-dict Utf8 columns are dense (non-nullable)
            // Per-zone string min/max over the chunk's values (matches the flat varbin path), so the
            // dict zone-map prunes like a plain Utf8 column.
            byte[][] mm = VarBinEncodingEncoder.minMaxStats(strs);
            chunkStatsMin.add(mm != null ? mm[0] : null);
            chunkStatsMax.add(mm != null ? mm[1] : null);
        }
        // Utf8 columns are not summed (zoneSumDtype is null), so the sum bytes are never read.
        List<byte[]> noSum = java.util.Collections.nCopies(codesSegIdxes.size(), null);
        dictColRefs.put(colName, new DictColRef(valuesSegIdx, dictSize, codesSegIdxes,
                chunkRowCounts, chunkNullCounts, chunkStatsMin, chunkStatsMax, noSum));
    }

    private static Object buildUtf8CodesArray(String[] strs, Map<String, Integer> valueMap, PType codePType) {
        return switch (codePType) {
            case U8 -> {
                byte[] codes = new byte[strs.length];
                for (int i = 0; i < strs.length; i++) {
                    codes[i] = (byte) (int) valueMap.get(strs[i]);
                }
                yield codes;
            }
            case U16 -> {
                short[] codes = new short[strs.length];
                for (int i = 0; i < strs.length; i++) {
                    codes[i] = (short) (int) valueMap.get(strs[i]);
                }
                yield codes;
            }
            default -> {
                int[] codes = new int[strs.length];
                for (int i = 0; i < strs.length; i++) {
                    codes[i] = valueMap.get(strs[i]);
                }
                yield codes;
            }
        };
    }

    static boolean isUtf8DictCandidate(String[] data) {
        if (data.length == 0) {
            return false;
        }
        var seen = new java.util.HashSet<String>(GLOBAL_DICT_MAX_CARDINALITY + 1);
        for (String s : data) {
            seen.add(s);
            if (seen.size() > GLOBAL_DICT_MAX_CARDINALITY) {
                return false;
            }
        }
        return seen.size() * 2 < data.length;
    }

    static boolean isDictCandidate(PType ptype, Object data) {
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
            seen.add(readPrimitiveElement(data, ptype, i));
            if (seen.size() > GLOBAL_DICT_MAX_CARDINALITY) {
                return false;
            }
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

    /// Builds the dictionary's unique-values array for a global-dict column. Only the carriers
    /// [#isDictCandidate] admits — I32/U32, I64/U64, F64 — reach here; the narrow-int and F16/F32
    /// ptypes are rejected as dict candidates upstream, so they are not handled.
    private static Object buildTypedUniqueArray(PType ptype, java.util.Set<Object> keys, int size) {
        return switch (ptype) {
            case I32, U32 -> {
                int[] a = new int[size];
                int i = 0;
                for (Object v : keys) {
                    a[i++] = (Integer) v;
                }
                yield a;
            }
            case I64, U64 -> {
                long[] a = new long[size];
                int i = 0;
                for (Object v : keys) {
                    a[i++] = (Long) v;
                }
                yield a;
            }
            case F64 -> {
                double[] a = new double[size];
                int i = 0;
                for (Object v : keys) {
                    a[i++] = (Double) v;
                }
                yield a;
            }
            default -> throw new IllegalStateException("ptype not admitted to the global dict: " + ptype);
        };
    }

    private static Object buildCodesArray(Object data, PType ptype, Map<Object, Integer> valueMap,
            PType codePType, int len) {
        return switch (codePType) {
            case U8 -> {
                byte[] codes = new byte[len];
                for (int i = 0; i < len; i++) {
                    codes[i] = (byte) (int) valueMap.get(readPrimitiveElement(data, ptype, i));
                }
                yield codes;
            }
            case U16 -> {
                short[] codes = new short[len];
                for (int i = 0; i < len; i++) {
                    codes[i] = (short) (int) valueMap.get(readPrimitiveElement(data, ptype, i));
                }
                yield codes;
            }
            default -> {
                int[] codes = new int[len];
                for (int i = 0; i < len; i++) {
                    codes[i] = valueMap.get(readPrimitiveElement(data, ptype, i));
                }
                yield codes;
            }
        };
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
