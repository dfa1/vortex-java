package io.github.dfa1.vortex.writer;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.BoolCodec;
import io.github.dfa1.vortex.encoding.Codec;
import io.github.dfa1.vortex.encoding.EncodeNode;
import io.github.dfa1.vortex.encoding.EncodeResult;
import io.github.dfa1.vortex.encoding.PrimitiveCodec;
import io.github.dfa1.vortex.fbs.ArraySpec;
import io.github.dfa1.vortex.fbs.Footer;
import io.github.dfa1.vortex.fbs.Layout;
import io.github.dfa1.vortex.fbs.LayoutSpec;
import io.github.dfa1.vortex.fbs.Postscript;
import io.github.dfa1.vortex.fbs.PostscriptSegment;
import io.github.dfa1.vortex.fbs.SegmentSpec;
import io.github.dfa1.vortex.fbs.Type;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final int VERSION = 1;

    // Indices into layout_specs list in the Footer
    private static final int LAYOUT_FLAT    = 0;
    private static final int LAYOUT_CHUNKED = 1;
    private static final int LAYOUT_STRUCT  = 2;

    private static final ByteBuffer MAGIC = ByteBuffer.wrap(new byte[]{'V', 'T', 'X', 'F'})
        .asReadOnlyBuffer();

    private static final List<Codec> DEFAULT_CODECS = List.of(new PrimitiveCodec(), new BoolCodec());

    private final WritableByteChannel  channel;
    private final DType.Struct         schema;
    private final WriteOptions         options;
    private final List<Codec>          codecs;

    private long bytesWritten = 0;

    private final List<SegRef>                segs        = new ArrayList<>();
    private final Map<String, List<ChunkRef>> colChunks   = new LinkedHashMap<>();
    private final Map<String, Integer>        encodingIdx = new LinkedHashMap<>();

    private record SegRef(long offset, int len) {}
    private record ChunkRef(int segIdx, long rowCount) {}

    private VortexWriter(
        WritableByteChannel channel, DType.Struct schema, WriteOptions options, List<Codec> codecs
    ) {
        this.channel = channel;
        this.schema  = schema;
        this.options = options;
        this.codecs  = codecs;
        for (String name : schema.fieldNames()) {
            colChunks.put(name, new ArrayList<>());
        }
    }

    public static VortexWriter create(
        WritableByteChannel channel, DType.Struct schema, WriteOptions options
    ) {
        return new VortexWriter(channel, schema, options, DEFAULT_CODECS);
    }

    public static VortexWriter create(
        WritableByteChannel channel, DType.Struct schema, WriteOptions options, List<Codec> codecs
    ) {
        return new VortexWriter(channel, schema, options, codecs);
    }

    /// Write one chunk. Each column is encoded by the first registered [Codec] that accepts its dtype.
    public void writeChunk(Map<String, Object> columns) throws IOException {
        for (int i = 0; i < schema.fieldNames().size(); i++) {
            String colName  = schema.fieldNames().get(i);
            DType  colDtype = schema.fieldTypes().get(i);
            Object data     = columns.get(colName);
            if (data == null) {
                throw new IllegalArgumentException("missing column: " + colName);
            }
            long rowCount = arrayLength(data);
            int  segIdx   = writeSegment(colDtype, data);
            colChunks.get(colName).add(new ChunkRef(segIdx, rowCount));
        }
    }

    @Override
    public void close() throws IOException {
        ByteBuffer footerBuf = buildFooter();
        long       footerOff = bytesWritten;
        write(footerBuf);

        ByteBuffer dtypeBuf = buildDType(schema);
        long       dtypeOff = bytesWritten;
        write(dtypeBuf);

        ByteBuffer layoutBuf = buildLayout();
        long       layoutOff = bytesWritten;
        write(layoutBuf);

        ByteBuffer psBuf = buildPostscript(
            footerOff, footerBuf.capacity(),
            dtypeOff,  dtypeBuf.capacity(),
            layoutOff, layoutBuf.capacity());
        write(psBuf);

        // 8-byte trailer: version(u16 LE) | postscriptLen(u16 LE) | magic(4)
        var trailer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        trailer.putShort((short) VERSION);
        trailer.putShort((short) psBuf.capacity());
        trailer.put(MAGIC.duplicate());
        trailer.flip();
        channel.write(trailer);
    }

    // ── Segment encoding ─────────────────────────────────────────────────────

    private int writeSegment(DType dtype, Object data) throws IOException {
        Codec        codec  = findCodec(dtype);
        EncodeResult result = codec.encode(dtype, data);

        // Register all encoding IDs found in the node tree
        registerEncodingIds(result.rootNode());

        // Align segment start to 64 bytes so each buffer is Arrow-compatible
        long prePad = (64 - bytesWritten % 64) % 64;
        if (prePad > 0) {
            writePadding((int) prePad);
        }

        int  segIdx = segs.size();
        long offset = bytesWritten;

        ByteBuffer fbBuf = buildArrayFlatBuffer(result);

        // Segment format: [buffer data...] [FlatBuffer Array bytes] [4-byte LE u32 = fbLen]
        int fbLen = fbBuf.remaining();
        for (ByteBuffer buf : result.buffers()) {
            write(buf);
        }
        write(fbBuf);
        var sizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fbLen);
        sizeBuf.flip();
        channel.write(sizeBuf);
        bytesWritten += 4;

        segs.add(new SegRef(offset, (int) (bytesWritten - offset)));
        return segIdx;
    }

    private void registerEncodingIds(EncodeNode node) {
        encodingIdx.computeIfAbsent(node.encodingId().id(), k -> encodingIdx.size());
        for (EncodeNode child : node.children()) {
            registerEncodingIds(child);
        }
    }

    private Codec findCodec(DType dtype) {
        for (Codec c : codecs) {
            if (c.accepts(dtype)) {
                return c;
            }
        }
        throw new UnsupportedOperationException("no codec for dtype: " + dtype);
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
            fbb.putInt(bufs.get(i).remaining());
            fbb.putByte((byte) 0);   // compression = None
            fbb.putByte((byte) 6);   // alignment_exponent = 6 (64-byte alignment)
            fbb.putShort((short) 0); // padding = 0
        }
        int bufVec = fbb.endVector();

        int arrayOff = io.github.dfa1.vortex.fbs.Array.createArray(fbb, rootNodeOff, bufVec);
        io.github.dfa1.vortex.fbs.Array.finishArrayBuffer(fbb, arrayOff);
        return fbb.dataBuffer().slice(fbb.dataBuffer().position(), fbb.dataBuffer().remaining());
    }

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
        int encIdx    = encodingIdx.get(node.encodingId().id());
        return io.github.dfa1.vortex.fbs.ArrayNode.createArrayNode(
            fbb, encIdx, metaOff, childVec, bufIdxVec, statsOff);
    }

    private static long arrayLength(Object data) {
        return switch (data) {
            case byte[]    a -> a.length;
            case short[]   a -> a.length;
            case int[]     a -> a.length;
            case long[]    a -> a.length;
            case float[]   a -> a.length;
            case double[]  a -> a.length;
            case boolean[] a -> a.length;
            default -> throw new UnsupportedOperationException(
                "unsupported data type: " + data.getClass());
        };
    }

    // ── Footer / metadata serialization ──────────────────────────────────────

    private ByteBuffer buildFooter() {
        var fbb = new FlatBufferBuilder(512);

        // array_specs: all encoding IDs used across all written segments, in registration order
        String[] encIds    = encodingIdx.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .toArray(String[]::new);
        int[] asOffsets = new int[encIds.length];
        for (int i = 0; i < encIds.length; i++) {
            asOffsets[i] = ArraySpec.createArraySpec(fbb, fbb.createString(encIds[i]));
        }
        int asv = Footer.createArraySpecsVector(fbb, asOffsets);

        // layout_specs: ["vortex.flat", "vortex.chunked", "vortex.struct"]
        int ls0 = LayoutSpec.createLayoutSpec(fbb, fbb.createString("vortex.flat"));
        int ls1 = LayoutSpec.createLayoutSpec(fbb, fbb.createString("vortex.chunked"));
        int ls2 = LayoutSpec.createLayoutSpec(fbb, fbb.createString("vortex.struct"));
        int lsv = Footer.createLayoutSpecsVector(fbb, new int[]{ls0, ls1, ls2});

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
                int namesVec  = io.github.dfa1.vortex.fbs.Struct_.createNamesVector(fbb, nameOffsets);
                int dtypesVec = io.github.dfa1.vortex.fbs.Struct_.createDtypesVector(fbb, fieldOffsets);
                int inner = io.github.dfa1.vortex.fbs.Struct_.createStruct_(
                    fbb, namesVec, dtypesVec, s.nullable());
                yield io.github.dfa1.vortex.fbs.DType.createDType(fbb, Type.Struct_, inner);
            }
            default -> throw new UnsupportedOperationException("unsupported DType: " + dtype);
        };
    }

    private ByteBuffer buildLayout() {
        var fbb      = new FlatBufferBuilder(256);
        int colCount = schema.fieldNames().size();

        // Pass 1: build all Flat layout nodes (children must precede parents in FlatBuffers)
        int[][] flatsByCol   = new int[colCount][];
        long[]  colRowCounts = new long[colCount];
        for (int c = 0; c < colCount; c++) {
            String         colName = schema.fieldNames().get(c);
            List<ChunkRef> chunks  = colChunks.get(colName);
            int[]          flats   = new int[chunks.size()];
            long           colRows = 0;
            for (int i = 0; i < chunks.size(); i++) {
                ChunkRef cr   = chunks.get(i);
                int      segV = Layout.createSegmentsVector(fbb, new long[]{cr.segIdx()});
                flats[i]      = Layout.createLayout(fbb, LAYOUT_FLAT, cr.rowCount(), 0, 0, segV);
                colRows      += cr.rowCount();
            }
            flatsByCol[c]   = flats;
            colRowCounts[c] = colRows;
        }

        // Pass 2: build Chunked layout per column
        int[]  colLayouts = new int[colCount];
        long   totalRows  = colCount > 0 ? colRowCounts[0] : 0;
        for (int c = 0; c < colCount; c++) {
            int childV    = Layout.createChildrenVector(fbb, flatsByCol[c]);
            colLayouts[c] = Layout.createLayout(fbb, LAYOUT_CHUNKED, colRowCounts[c], 0, childV, 0);
        }

        // Pass 3: Struct root
        int rootChildV = Layout.createChildrenVector(fbb, colLayouts);
        int rootLayout = Layout.createLayout(fbb, LAYOUT_STRUCT, totalRows, 0, rootChildV, 0);
        Layout.finishLayoutBuffer(fbb, rootLayout);
        return fbb.dataBuffer().slice(fbb.dataBuffer().position(), fbb.dataBuffer().remaining());
    }

    private static ByteBuffer buildPostscript(
        long footerOff, int footerLen,
        long dtypeOff,  int dtypeLen,
        long layoutOff, int layoutLen
    ) {
        var fbb = new FlatBufferBuilder(256);

        int footerSegOff = PostscriptSegment.createPostscriptSegment(
            fbb, footerOff, footerLen, 0, 0, 0);
        int dtypeSegOff  = PostscriptSegment.createPostscriptSegment(
            fbb, dtypeOff, dtypeLen, 0, 0, 0);
        int layoutSegOff = PostscriptSegment.createPostscriptSegment(
            fbb, layoutOff, layoutLen, 0, 0, 0);

        int psOff = Postscript.createPostscript(fbb, dtypeSegOff, layoutSegOff, 0, footerSegOff);
        Postscript.finishPostscriptBuffer(fbb, psOff);
        return fbb.dataBuffer().slice(fbb.dataBuffer().position(), fbb.dataBuffer().remaining());
    }
}
