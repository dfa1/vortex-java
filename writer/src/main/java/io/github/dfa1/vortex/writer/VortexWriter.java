package io.github.dfa1.vortex.writer;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexFormat;
import io.github.dfa1.vortex.encoding.AlpEncoding;
import io.github.dfa1.vortex.encoding.BitpackedEncoding;
import io.github.dfa1.vortex.encoding.BoolEncoding;
import io.github.dfa1.vortex.encoding.CascadingCompressor;
import io.github.dfa1.vortex.encoding.ConstantEncoding;
import io.github.dfa1.vortex.encoding.DateTimePartsData;
import io.github.dfa1.vortex.encoding.DateTimePartsEncoding;
import io.github.dfa1.vortex.encoding.DictEncoding;
import io.github.dfa1.vortex.encoding.EncodeContext;
import io.github.dfa1.vortex.encoding.EncodeNode;
import io.github.dfa1.vortex.encoding.EncodeResult;
import io.github.dfa1.vortex.encoding.Encoding;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.Registry;
import io.github.dfa1.vortex.encoding.FrameOfReferenceEncoding;
import io.github.dfa1.vortex.encoding.ListData;
import io.github.dfa1.vortex.encoding.ListViewData;
import io.github.dfa1.vortex.encoding.PrimitiveEncoding;
import io.github.dfa1.vortex.encoding.RleEncoding;
import io.github.dfa1.vortex.encoding.RunEndEncoding;
import io.github.dfa1.vortex.encoding.VarBinEncoding;
import io.github.dfa1.vortex.encoding.ZstdEncoding;
import io.github.dfa1.vortex.fbs.ArraySpec;
import io.github.dfa1.vortex.fbs.Extension;
import io.github.dfa1.vortex.fbs.Footer;
import io.github.dfa1.vortex.fbs.Layout;
import io.github.dfa1.vortex.fbs.LayoutSpec;
import io.github.dfa1.vortex.fbs.Postscript;
import io.github.dfa1.vortex.fbs.PostscriptSegment;
import io.github.dfa1.vortex.fbs.SegmentSpec;
import io.github.dfa1.vortex.fbs.Type;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
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
///                               List.of(new DType.Primitive(PType.I64, false),
///                                       new DType.Primitive(PType.F64, false)), false);
/// try (var channel = FileChannel.open(path, CREATE, WRITE);
///      var writer = VortexWriter.create(channel, schema, WriteOptions.defaults())) {
///     writer.writeChunk(Map.of("id", idArray, "value", valueArray));
/// }
/// ```
public final class VortexWriter implements Closeable {

    // Indices into layout_specs list in the Footer
    private static final int LAYOUT_FLAT = 0;
    private static final int LAYOUT_CHUNKED = 1;
    private static final int LAYOUT_STRUCT = 2;
    private static final int LAYOUT_DICT = 3;

    // Columns with global cardinality below this threshold are dict-encoded across all chunks.
    // Kept low: global dict hurts high-cardinality F64 columns (ALP codes beat U16 dict codes).
    private static final int GLOBAL_DICT_MAX_CARDINALITY = 2_048;
    // Fraction of distinct values in first chunk below which a column is a dict candidate.
    private static final double GLOBAL_DICT_RATIO_THRESHOLD = 0.5;

    private static final List<Encoding> DEFAULT_CODECS = List.of(
            new AlpEncoding(), new PrimitiveEncoding(), new BoolEncoding(), new DictEncoding(), new VarBinEncoding());

    // Base cascade codec list — no Zstd. Zstd is appended (before PrimitiveEncoding) when
    // WriteOptions.enableZstd() is true. See WriteOptions.withZstd(boolean) for the tradeoff.

    private final WritableByteChannel channel;
    private final DType.Struct schema;
    private final WriteOptions options;
    private final List<Encoding> encodings;
    private final Registry defaultRegistry;
    private final List<Encoding> cascadeCodecs;
    private final Registry cascadeRegistry;
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

    private VortexWriter(
            WritableByteChannel channel, DType.Struct schema, WriteOptions options, List<Encoding> encodings
    ) {
        this.channel = channel;
        this.schema = schema;
        this.options = options;
        this.encodings = encodings;
        this.defaultRegistry = buildWriterRegistry(encodings);
        this.cascadeCodecs = buildCascadeCodecs(options);
        this.cascadeRegistry = buildWriterRegistry(this.cascadeCodecs);
        for (String name : schema.fieldNames()) {
            colChunks.put(name, new ArrayList<>());
        }
    }

    /// Builds the writer's registry: the explicit encoding list plus every
    /// {@link io.github.dfa1.vortex.extension.Extension} discovered via ServiceLoader,
    /// so {@code writeChunk} can auto-route {@code Collection<DomainT>} inputs through
    /// the matching extension impl — including third-party extensions outside
    /// {@link io.github.dfa1.vortex.extension.Extension#findKnown}.
    private static Registry buildWriterRegistry(List<Encoding> encodings) {
        Registry.Builder b = Registry.builder().registerExtensionsServiceLoaded();
        for (Encoding e : encodings) {
            b.register(e);
        }
        return b.build();
    }

    private static List<Encoding> buildCascadeCodecs(WriteOptions options) {
        List<Encoding> codecs = new ArrayList<>(List.of(
                new DateTimePartsEncoding(),
                new ConstantEncoding(),
                new AlpEncoding(), new FrameOfReferenceEncoding(), new RunEndEncoding(), new RleEncoding(),
                new DictEncoding(), new BitpackedEncoding(),
                new VarBinEncoding()));
        if (options.enableZstd()) {
            codecs.add(new ZstdEncoding());
        }
        codecs.add(new PrimitiveEncoding());
        codecs.add(new BoolEncoding());
        return List.copyOf(codecs);
    }

    public static VortexWriter create(
            WritableByteChannel channel, DType.Struct schema, WriteOptions options
    ) {
        return new VortexWriter(channel, schema, options, DEFAULT_CODECS);
    }

    public static VortexWriter create(
            WritableByteChannel channel, DType.Struct schema, WriteOptions options, List<Encoding> encodings
    ) {
        // Custom encoding list: disable global dict — using DEFAULT_CODECS for values/codes behind the scenes
        // would violate the user's expectation that only their encoding list is used.
        return new VortexWriter(channel, schema, options.withGlobalDict(false), encodings);
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
            default -> throw new UnsupportedOperationException(
                    "unsupported data type: " + data.getClass());
        };
    }

    private static ByteBuffer buildDType(DType dtype) {
        var fbb = new FlatBufferBuilder(128);
        int off = serializeDType(fbb, dtype);
        io.github.dfa1.vortex.fbs.DType.finishDTypeBuffer(fbb, off);
        return fbb.dataBuffer().slice(fbb.dataBuffer().position(), fbb.dataBuffer().remaining());
    }

    private static int serializeDType(FlatBufferBuilder fbb, DType dtype) {
        return switch (dtype) {
            case DType.Null __ -> {
                io.github.dfa1.vortex.fbs.Null.startNull(fbb);
                int inner = io.github.dfa1.vortex.fbs.Null.endNull(fbb);
                yield io.github.dfa1.vortex.fbs.DType.createDType(fbb, Type.Null, inner);
            }
            case DType.Bool b -> {
                int inner = io.github.dfa1.vortex.fbs.Bool.createBool(fbb, b.nullable());
                yield io.github.dfa1.vortex.fbs.DType.createDType(fbb, Type.Bool, inner);
            }
            case DType.Primitive p -> {
                int inner = io.github.dfa1.vortex.fbs.Primitive.createPrimitive(
                        fbb, p.ptype().ordinal(), p.nullable());
                yield io.github.dfa1.vortex.fbs.DType.createDType(fbb, Type.Primitive, inner);
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
                int namesVec = io.github.dfa1.vortex.fbs.Struct_.createNamesVector(fbb, nameOffsets);
                int dtypesVec = io.github.dfa1.vortex.fbs.Struct_.createDtypesVector(fbb, fieldOffsets);
                int inner = io.github.dfa1.vortex.fbs.Struct_.createStruct_(
                        fbb, namesVec, dtypesVec, s.nullable());
                yield io.github.dfa1.vortex.fbs.DType.createDType(fbb, Type.Struct_, inner);
            }
            case DType.Utf8 u -> {
                int inner = io.github.dfa1.vortex.fbs.Utf8.createUtf8(fbb, u.nullable());
                yield io.github.dfa1.vortex.fbs.DType.createDType(fbb, Type.Utf8, inner);
            }
            case DType.List l -> {
                int elemTypeOff = serializeDType(fbb, l.elementType());
                int inner = io.github.dfa1.vortex.fbs.List.createList(fbb, elemTypeOff, l.nullable());
                yield io.github.dfa1.vortex.fbs.DType.createDType(fbb, Type.List, inner);
            }
            case DType.Extension e -> {
                int idOff = fbb.createString(e.extensionId());
                int storageDtypeOff = serializeDType(fbb, e.storageDType());
                int metaOff = 0;
                if (e.metadata() != null && e.metadata().remaining() > 0) {
                    byte[] metaBytes = new byte[e.metadata().remaining()];
                    e.metadata().duplicate().get(metaBytes);
                    metaOff = Extension.createMetadataVector(fbb, metaBytes);
                }
                int inner = Extension.createExtension(fbb, idOff, storageDtypeOff, metaOff);
                yield io.github.dfa1.vortex.fbs.DType.createDType(fbb, Type.Extension, inner);
            }
            default -> throw new UnsupportedOperationException("unsupported DType: " + dtype);
        };
    }

    private static ByteBuffer buildPostscript(
            long footerOff, int footerLen,
            long dtypeOff, int dtypeLen,
            long layoutOff, int layoutLen
    ) {
        var fbb = new FlatBufferBuilder(256);

        int footerSegOff = PostscriptSegment.createPostscriptSegment(
                fbb, footerOff, footerLen, 0, 0, 0);
        int dtypeSegOff = PostscriptSegment.createPostscriptSegment(
                fbb, dtypeOff, dtypeLen, 0, 0, 0);
        int layoutSegOff = PostscriptSegment.createPostscriptSegment(
                fbb, layoutOff, layoutLen, 0, 0, 0);

        int psOff = Postscript.createPostscript(fbb, dtypeSegOff, layoutSegOff, 0, footerSegOff);
        Postscript.finishPostscriptBuffer(fbb, psOff);
        return fbb.dataBuffer().slice(fbb.dataBuffer().position(), fbb.dataBuffer().remaining());
    }

    // ── Segment encoding ─────────────────────────────────────────────────────

    /// Write one chunk. Each column is encoded by the first registered [Encoding] that accepts its dtype.
    ///
    /// @param columns map from column name to typed array data
    /// @throws IOException if an I/O error occurs writing to the underlying channel
    public void writeChunk(Map<String, Object> columns) throws IOException {
        for (int i = 0; i < schema.fieldNames().size(); i++) {
            String colName = schema.fieldNames().get(i);
            DType colDtype = schema.fieldTypes().get(i);
            Object data = columns.get(colName);
            if (data == null) {
                throw new IllegalArgumentException("missing column: " + colName);
            }

            // Auto-route extension columns: callers can pass List<LocalDate>, List<Instant>,
            // etc., and we route through the matching spec extension to produce the int[] /
            // long[] / byte[] the downstream segment writer expects. The physical encoding
            // then selects against the storage dtype (I32, I64, ...) rather than the
            // Extension wrapper, which has no registered encoding.
            if (colDtype instanceof DType.Extension extDtype && data instanceof java.util.Collection<?> coll) {
                io.github.dfa1.vortex.extension.ExtensionId extId =
                        io.github.dfa1.vortex.extension.ExtensionId.tryFrom(extDtype.extensionId());
                io.github.dfa1.vortex.extension.Extension impl =
                        extId == null ? null : defaultRegistry.lookup(extId);
                if (impl != null) {
                    data = impl.encodeAll(extDtype, coll);
                    colDtype = extDtype.storageDType();
                }
            }

            if (!firstChunkSeen && options.globalDict()) {
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
                dictBuffers.computeIfAbsent(colName, k -> new ArrayList<>()).add(data);
            } else {
                long rowCount = arrayLength(data);
                int segIdx = writeSegment(colDtype, data);
                colChunks.get(colName).add(new ChunkRef(segIdx, rowCount));
            }
        }
        firstChunkSeen = true;
    }

    @Override
    public void close() throws IOException {
        flushDictColumns();
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

    /// Writes a segment, optionally forcing a specific {@code encodingOverride} instead of
    /// the configured cascade. Used by the global Utf8 dictionary path where the values
    /// segment must be flat varbin — the cascade would otherwise re-pick {@link
    /// io.github.dfa1.vortex.encoding.DictEncoding} and wrap the dictionary in another
    /// dict (which the reader cannot unwrap).
    private int writeSegment(DType dtype, Object data, Encoding encodingOverride) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            EncodeResult result;
            if (encodingOverride != null) {
                EncodeContext encodeCtx = EncodeContext.of(arena, this.defaultRegistry);
                result = encodingOverride.encode(dtype, data, encodeCtx);
            } else if (options.allowedCascading() > 0) {
                EncodeContext encodeCtx = EncodeContext.ofDepth(options.allowedCascading(), arena, cascadeRegistry);
                CascadingCompressor compressor = new CascadingCompressor(cascadeCodecs);
                result = compressor.encode(dtype, data, encodeCtx);
            } else {
                Encoding encoding = findEncoding(dtype);
                EncodeContext encodeCtx = EncodeContext.of(arena, this.defaultRegistry);
                result = encoding.encode(dtype, data, encodeCtx);
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

            ByteBuffer fbBuf = buildArrayFlatBuffer(result);

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
            return segIdx;
        }
    }

    private void registerEncodingIds(EncodeNode node) {
        encodingIdx.computeIfAbsent(node.encodingId(), k -> encodingIdx.size());
        for (EncodeNode child : node.children()) {
            registerEncodingIds(child);
        }
    }

    private Encoding findEncoding(DType dtype) {
        for (Encoding c : encodings) {
            if (c.accepts(dtype)) {
                return c;
            }
        }
        throw new UnsupportedOperationException("no encoding for dtype: " + dtype);
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

    private ByteBuffer buildArrayFlatBuffer(EncodeResult result) {
        var fbb = new FlatBufferBuilder(256);

        // Stats for root node only (build before root ArrayNode)
        int statsOff = 0;
        if (result.hasStats()) {
            int minVec = io.github.dfa1.vortex.fbs.ArrayStats.createMinVector(fbb, result.statsMin());
            int maxVec = io.github.dfa1.vortex.fbs.ArrayStats.createMaxVector(fbb, result.statsMax());
            io.github.dfa1.vortex.fbs.ArrayStats.startArrayStats(fbb);
            io.github.dfa1.vortex.fbs.ArrayStats.addMin(fbb, minVec);
            io.github.dfa1.vortex.fbs.ArrayStats.addMax(fbb, maxVec);
            statsOff = io.github.dfa1.vortex.fbs.ArrayStats.endArrayStats(fbb);
        }

        int rootNodeOff = buildArrayNodeFlatBuffer(fbb, result.rootNode(), statsOff);

        // Buffer struct vector — one entry per buffer in result.
        // Layout (LE): padding(u16) | alignment_exponent(u8) | compression(u8) | length(u32)
        // FlatBuffers builds backward: iterate in reverse.
        var bufs = result.buffers();
        io.github.dfa1.vortex.fbs.Array.startBuffersVector(fbb, bufs.size());
        for (int i = bufs.size() - 1; i >= 0; i--) {
            fbb.prep(4, 8);
            fbb.putInt((int) bufs.get(i).byteSize());
            fbb.putByte((byte) 0);   // compression = None
            fbb.putByte((byte) 6);   // alignment_exponent = 6 (64-byte alignment)
            fbb.putShort((short) 0); // padding = 0
        }
        int bufVec = fbb.endVector();

        int arrayOff = io.github.dfa1.vortex.fbs.Array.createArray(fbb, rootNodeOff, bufVec);
        io.github.dfa1.vortex.fbs.Array.finishArrayBuffer(fbb, arrayOff);
        return fbb.dataBuffer().slice(fbb.dataBuffer().position(), fbb.dataBuffer().remaining());
    }

    // ── Footer / metadata serialization ──────────────────────────────────────

    private int buildArrayNodeFlatBuffer(FlatBufferBuilder fbb, EncodeNode node, int statsOff) {
        // Build children first (FlatBuffers bottom-up: nested objects before parent table)
        int[] childOffsets = new int[node.children().length];
        for (int i = 0; i < childOffsets.length; i++) {
            childOffsets[i] = buildArrayNodeFlatBuffer(fbb, node.children()[i], 0);
        }

        int metaOff = 0;
        if (node.metadata() != null && node.metadata().hasRemaining()) {
            byte[] metaBytes = new byte[node.metadata().remaining()];
            node.metadata().duplicate().get(metaBytes);
            metaOff = io.github.dfa1.vortex.fbs.ArrayNode.createMetadataVector(fbb, metaBytes);
        }

        int childVec = 0;
        if (childOffsets.length > 0) {
            childVec = io.github.dfa1.vortex.fbs.ArrayNode.createChildrenVector(fbb, childOffsets);
        }

        int bufIdxVec = io.github.dfa1.vortex.fbs.ArrayNode.createBuffersVector(fbb, node.bufferIndices());
        int encIdx = encodingIdx.get(node.encodingId());
        return io.github.dfa1.vortex.fbs.ArrayNode.createArrayNode(
                fbb, encIdx, metaOff, childVec, bufIdxVec, statsOff);
    }

    private ByteBuffer buildFooter() {
        var fbb = new FlatBufferBuilder(512);

        // array_specs: all encoding IDs used across all written segments, in registration order
        EncodingId[] encIds = encodingIdx.entrySet().stream()
                                      .sorted(Map.Entry.comparingByValue())
                                      .map(Map.Entry::getKey)
                                      .toArray(EncodingId[]::new);
        int[] asOffsets = new int[encIds.length];
        for (int i = 0; i < encIds.length; i++) {
            asOffsets[i] = ArraySpec.createArraySpec(fbb, fbb.createString(encIds[i].id()));
        }
        int asv = Footer.createArraySpecsVector(fbb, asOffsets);

        // layout_specs: ["vortex.flat", "vortex.chunked", "vortex.struct", "vortex.dict"]
        int ls0 = LayoutSpec.createLayoutSpec(fbb, fbb.createString("vortex.flat"));
        int ls1 = LayoutSpec.createLayoutSpec(fbb, fbb.createString("vortex.chunked"));
        int ls2 = LayoutSpec.createLayoutSpec(fbb, fbb.createString("vortex.struct"));
        int ls3 = LayoutSpec.createLayoutSpec(fbb, fbb.createString("vortex.dict"));
        int lsv = Footer.createLayoutSpecsVector(fbb, new int[]{ls0, ls1, ls2, ls3});

        // segment_specs (inline struct vector — write in reverse order)
        Footer.startSegmentSpecsVector(fbb, segs.size());
        for (int i = segs.size() - 1; i >= 0; i--) {
            SegRef s = segs.get(i);
            SegmentSpec.createSegmentSpec(fbb, s.offset(), s.len(), 6, 0, 0);
        }
        int ssv = fbb.endVector();

        int off = Footer.createFooter(fbb, asv, lsv, ssv, 0, 0);
        fbb.finish(off);
        return fbb.dataBuffer().slice(fbb.dataBuffer().position(), fbb.dataBuffer().remaining());
    }

    private ByteBuffer buildLayout() {
        var fbb = new FlatBufferBuilder(256);
        int colCount = schema.fieldNames().size();

        int[] colLayouts = new int[colCount];
        long totalRows = 0;

        for (int c = 0; c < colCount; c++) {
            String colName = schema.fieldNames().get(c);
            DictColRef ref = dictColRefs.get(colName);
            if (ref != null) {
                colLayouts[c] = buildDictColLayout(fbb, ref);
                if (totalRows == 0) {
                    totalRows = ref.totalRows();
                }
            } else {
                List<ChunkRef> chunks = colChunks.get(colName);
                long colRows = 0;
                int[] flats = new int[chunks.size()];
                for (int i = 0; i < chunks.size(); i++) {
                    ChunkRef cr = chunks.get(i);
                    int segV = Layout.createSegmentsVector(fbb, new long[]{cr.segIdx()});
                    flats[i] = Layout.createLayout(fbb, LAYOUT_FLAT, cr.rowCount(), 0, 0, segV);
                    colRows += cr.rowCount();
                }
                int childV = Layout.createChildrenVector(fbb, flats);
                colLayouts[c] = Layout.createLayout(fbb, LAYOUT_CHUNKED, colRows, 0, childV, 0);
                if (totalRows == 0) {
                    totalRows = colRows;
                }
            }
        }

        int rootChildV = Layout.createChildrenVector(fbb, colLayouts);
        int rootLayout = Layout.createLayout(fbb, LAYOUT_STRUCT, totalRows, 0, rootChildV, 0);
        Layout.finishLayoutBuffer(fbb, rootLayout);
        return fbb.dataBuffer().slice(fbb.dataBuffer().position(), fbb.dataBuffer().remaining());
    }

    private int buildDictColLayout(FlatBufferBuilder fbb, DictColRef ref) {
        // Build codes Chunked layout: children are one Flat per original chunk
        int numChunks = ref.codesSegIdxes().size();
        int[] codesFlats = new int[numChunks];
        long totalCodesRows = 0;
        for (int j = 0; j < numChunks; j++) {
            long rowCount = ref.chunkRowCounts().get(j);
            int segV = Layout.createSegmentsVector(fbb, new long[]{ref.codesSegIdxes().get(j)});
            codesFlats[j] = Layout.createLayout(fbb, LAYOUT_FLAT, rowCount, 0, 0, segV);
            totalCodesRows += rowCount;
        }
        int codesChildV = Layout.createChildrenVector(fbb, codesFlats);
        int codesChunked = Layout.createLayout(fbb, LAYOUT_CHUNKED, totalCodesRows, 0, codesChildV, 0);

        // Build values Flat layout
        int valSegV = Layout.createSegmentsVector(fbb, new long[]{ref.valuesSegIdx()});
        int valuesFlat = Layout.createLayout(fbb, LAYOUT_FLAT, ref.valuesLen(), 0, 0, valSegV);

        // DictLayoutMetadata proto (matches Rust): field 1 = codes_ptype (PType varint)
        PType codePType = codePTypeForSize((int) ref.valuesLen());
        byte[] metaBytes = buildDictLayoutMetaBytes(codePType);
        int metaVec = metaBytes.length > 0 ? Layout.createMetadataVector(fbb, metaBytes) : 0;

        // Dict layout: child[0]=values, child[1]=codes (matches Rust DictLayout child order)
        int[] dictChildren = {valuesFlat, codesChunked};
        int dictChildV = Layout.createChildrenVector(fbb, dictChildren);
        return Layout.createLayout(fbb, LAYOUT_DICT, totalCodesRows, metaVec, dictChildV, 0);
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

        // Build global value map across all chunks
        var valueMap = new LinkedHashMap<Object, Integer>();
        for (Object chunk : chunks) {
            int len = primitiveArrayLen(chunk, ptype);
            for (int i = 0; i < len; i++) {
                Object v = readPrimitiveElement(chunk, ptype, i);
                valueMap.computeIfAbsent(v, k -> valueMap.size());
            }
        }

        int dictSize = valueMap.size();
        if (dictSize > GLOBAL_DICT_MAX_CARDINALITY) {
            // Cardinality exceeded threshold after seeing all data — fall back to per-chunk
            for (Object chunk : chunks) {
                long rowCount = arrayLength(chunk);
                int segIdx = writeSegment(dtype, chunk);
                colChunks.get(colName).add(new ChunkRef(segIdx, rowCount));
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
        for (Object chunk : chunks) {
            int len = primitiveArrayLen(chunk, ptype);
            Object codesArr = buildCodesArray(chunk, ptype, valueMap, codePType, len);
            codesSegIdxes.add(writeSegment(codesDtype, codesArr));
            chunkRowCounts.add((long) len);
        }

        dictColRefs.put(colName, new DictColRef(valuesSegIdx, dictSize, codesSegIdxes, chunkRowCounts));
    }

    private void writeGlobalDictUtf8Column(String colName, DType.Utf8 dtype, List<Object> chunks)
            throws IOException {
        // Build global string -> code map across all chunks (insertion order = code value).
        var valueMap = new LinkedHashMap<String, Integer>();
        for (Object chunk : chunks) {
            for (String s : (String[]) chunk) {
                valueMap.computeIfAbsent(s, k -> valueMap.size());
            }
        }

        int dictSize = valueMap.size();
        if (dictSize > GLOBAL_DICT_MAX_CARDINALITY) {
            // Cardinality exceeded threshold after seeing all data — fall back to per-chunk.
            for (Object chunk : chunks) {
                long rowCount = arrayLength(chunk);
                int segIdx = writeSegment(dtype, chunk);
                colChunks.get(colName).add(new ChunkRef(segIdx, rowCount));
            }
            return;
        }

        PType codePType = codePTypeForSize(dictSize);

        // Write unique strings as a flat VarBin segment — bypass cascade so DictEncoding
        // doesn't wrap the (all-unique-by-construction) dictionary in another dict that
        // the reader's decodeDictLayout cannot unwrap.
        String[] uniques = valueMap.keySet().toArray(new String[0]);
        int valuesSegIdx = writeSegment(dtype, uniques, new VarBinEncoding());

        // Write one codes segment per original chunk.
        DType codesDtype = new DType.Primitive(codePType, false);
        List<Integer> codesSegIdxes = new ArrayList<>();
        List<Long> chunkRowCounts = new ArrayList<>();
        for (Object chunk : chunks) {
            String[] strs = (String[]) chunk;
            Object codesArr = buildUtf8CodesArray(strs, valueMap, codePType);
            codesSegIdxes.add(writeSegment(codesDtype, codesArr));
            chunkRowCounts.add((long) strs.length);
        }
        dictColRefs.put(colName, new DictColRef(valuesSegIdx, dictSize, codesSegIdxes, chunkRowCounts));
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

    private static boolean isUtf8DictCandidate(String[] data) {
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

    private static boolean isDictCandidate(PType ptype, Object data) {
        // Float types: ALP/RLE compress repeated F64 values far better than dict codes.
        // E.g. a near-constant congestion_surcharge column costs ~0 bytes with RLE,
        // but ~1 MB with global dict U8 codes even after bitpacking.
        if (ptype == PType.F16 || ptype == PType.F32 || ptype == PType.F64) {
            return false;
        }
        int n = primitiveArrayLen(data, ptype);
        if (n == 0) {
            return false;
        }
        var seen = new java.util.HashSet<Object>(GLOBAL_DICT_MAX_CARDINALITY + 1);
        for (int i = 0; i < n; i++) {
            seen.add(readPrimitiveElement(data, ptype, i));
            if (seen.size() > GLOBAL_DICT_MAX_CARDINALITY) {
                return false;
            }
        }
        return seen.size() * 2 < n;
    }

    private static int primitiveArrayLen(Object data, PType ptype) {
        return switch (ptype) {
            case I8, U8 -> ((byte[]) data).length;
            case I16, U16, F16 -> ((short[]) data).length;
            case I32, U32 -> ((int[]) data).length;
            case I64, U64 -> ((long[]) data).length;
            case F32 -> ((float[]) data).length;
            case F64 -> ((double[]) data).length;
        };
    }

    private static Object readPrimitiveElement(Object data, PType ptype, int i) {
        return switch (ptype) {
            case I8, U8 -> ((byte[]) data)[i];
            case I16, U16, F16 -> ((short[]) data)[i];
            case I32, U32 -> ((int[]) data)[i];
            case I64, U64 -> ((long[]) data)[i];
            case F32 -> ((float[]) data)[i];
            case F64 -> ((double[]) data)[i];
        };
    }

    private static PType codePTypeForSize(int dictSize) {
        if (dictSize <= 256) {
            return PType.U8;
        }
        if (dictSize <= 65_536) {
            return PType.U16;
        }
        return PType.U32;
    }

    private static Object buildTypedUniqueArray(PType ptype, java.util.Set<Object> keys, int size) {
        return switch (ptype) {
            case I8, U8 -> {
                byte[] a = new byte[size];
                int i = 0;
                for (Object v : keys) {
                    a[i++] = (Byte) v;
                }
                yield a;
            }
            case I16, U16, F16 -> {
                short[] a = new short[size];
                int i = 0;
                for (Object v : keys) {
                    a[i++] = (Short) v;
                }
                yield a;
            }
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
            case F32 -> {
                float[] a = new float[size];
                int i = 0;
                for (Object v : keys) {
                    a[i++] = (Float) v;
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

    private record ChunkRef(int segIdx, long rowCount) {
    }

    private record DictColRef(int valuesSegIdx, long valuesLen, List<Integer> codesSegIdxes,
            List<Long> chunkRowCounts) {
        long totalRows() {
            return chunkRowCounts.stream().mapToLong(Long::longValue).sum();
        }
    }
}
