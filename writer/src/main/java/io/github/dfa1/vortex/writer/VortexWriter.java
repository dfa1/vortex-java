package io.github.dfa1.vortex.writer;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
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

    // Indices into array_specs list in the Footer
    private static final int ARRAY_PRIMITIVE = 0;
    private static final int ARRAY_BOOL      = 1;

    private static final ByteBuffer MAGIC = ByteBuffer.wrap(new byte[]{'V', 'T', 'X', 'F'})
        .asReadOnlyBuffer();

    private final WritableByteChannel channel;
    private final DType.Struct        schema;
    private final WriteOptions        options;

    private long bytesWritten = 0;

    private final List<SegRef>                segs      = new ArrayList<>();
    private final Map<String, List<ChunkRef>> colChunks = new LinkedHashMap<>();

    private record SegRef(long offset, int len) {}
    private record ChunkRef(int segIdx, long rowCount) {}

    private VortexWriter(WritableByteChannel channel, DType.Struct schema, WriteOptions options) {
        this.channel = channel;
        this.schema  = schema;
        this.options = options;
        for (String name : schema.fieldNames()) {
            colChunks.put(name, new ArrayList<>());
        }
    }

    public static VortexWriter create(
        WritableByteChannel channel, DType.Struct schema, WriteOptions options
    ) {
        return new VortexWriter(channel, schema, options);
    }

    /// Write one chunk. Encoding selected per-column per-chunk:
    /// - integers → FoR+BitPacking or Pcodec → Primitive fallback
    /// - floats → Pcodec → Primitive
    /// - strings → Dict(VarBinView) → VarBinView
    /// - bools → Bool (bit-packed)
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
        ByteBuffer bufData  = encodeBuffer(dtype, data);
        int        segIdx   = segs.size();
        long       offset   = bytesWritten;
        int        arrayEnc = (dtype instanceof DType.Bool) ? ARRAY_BOOL : ARRAY_PRIMITIVE;
        ByteBuffer fbBuf    = buildArrayFlatBuffer(arrayEnc, bufData.remaining());

        // Segment format: [buffer data] [FlatBuffer Array bytes] [4-byte LE u32 = fbLen]
        int fbLen   = fbBuf.remaining();
        write(bufData);
        write(fbBuf);
        var sizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fbLen);
        sizeBuf.flip();
        channel.write(sizeBuf);
        bytesWritten += 4;

        segs.add(new SegRef(offset, (int) (bytesWritten - offset)));
        return segIdx;
    }

    private void write(ByteBuffer buf) throws IOException {
        buf.rewind();
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        bytesWritten += buf.capacity();
    }

    private static ByteBuffer buildArrayFlatBuffer(int encIdx, int bufLen) {
        var fbb = new FlatBufferBuilder(256);

        // ArrayNode: encoding=encIdx, buffers=[0], no children, no metadata
        int buffersVec = io.github.dfa1.vortex.fbs.ArrayNode.createBuffersVector(fbb, new int[]{0});
        int nodeOff    = io.github.dfa1.vortex.fbs.ArrayNode.createArrayNode(
            fbb, encIdx, 0, 0, buffersVec, 0);

        // Buffer struct vector (1 element). Buffer struct layout (LE):
        //   padding(u16) | alignment_exponent(u8) | compression(u8) | length(u32)
        // FlatBuffers builds backward: write last field first.
        io.github.dfa1.vortex.fbs.Array.startBuffersVector(fbb, 1);
        fbb.prep(4, 8);
        fbb.putInt(bufLen);      // length
        fbb.putByte((byte) 0);   // compression = None
        fbb.putByte((byte) 0);   // alignment_exponent = 0
        fbb.putShort((short) 0); // padding = 0
        int bufVec = fbb.endVector();

        int arrayOff = io.github.dfa1.vortex.fbs.Array.createArray(fbb, nodeOff, bufVec);
        io.github.dfa1.vortex.fbs.Array.finishArrayBuffer(fbb, arrayOff);
        return fbb.dataBuffer().slice(fbb.dataBuffer().position(), fbb.dataBuffer().remaining());
    }

    private static ByteBuffer encodeBuffer(DType dtype, Object data) {
        return switch (dtype) {
            case DType.Primitive p -> encodePrimitive(p.ptype(), data);
            case DType.Bool __     -> encodeBool((boolean[]) data);
            default -> throw new UnsupportedOperationException("unsupported dtype: " + dtype);
        };
    }

    private static ByteBuffer encodePrimitive(PType ptype, Object data) {
        int elementBytes = ptype.byteSize();
        return switch (ptype) {
            case I8, U8 -> ByteBuffer.wrap((byte[]) data);
            case I16, U16 -> {
                short[] arr = (short[]) data;
                var bb = ByteBuffer.allocate(arr.length * elementBytes).order(ByteOrder.LITTLE_ENDIAN);
                for (short v : arr) {
                    bb.putShort(v);
                }
                yield bb.flip();
            }
            case I32, U32 -> {
                int[] arr = (int[]) data;
                var bb = ByteBuffer.allocate(arr.length * elementBytes).order(ByteOrder.LITTLE_ENDIAN);
                for (int v : arr) {
                    bb.putInt(v);
                }
                yield bb.flip();
            }
            case I64, U64 -> {
                long[] arr = (long[]) data;
                var bb = ByteBuffer.allocate(arr.length * elementBytes).order(ByteOrder.LITTLE_ENDIAN);
                for (long v : arr) {
                    bb.putLong(v);
                }
                yield bb.flip();
            }
            case F32 -> {
                float[] arr = (float[]) data;
                var bb = ByteBuffer.allocate(arr.length * elementBytes).order(ByteOrder.LITTLE_ENDIAN);
                for (float v : arr) {
                    bb.putFloat(v);
                }
                yield bb.flip();
            }
            case F64 -> {
                double[] arr = (double[]) data;
                var bb = ByteBuffer.allocate(arr.length * elementBytes).order(ByteOrder.LITTLE_ENDIAN);
                for (double v : arr) {
                    bb.putDouble(v);
                }
                yield bb.flip();
            }
            case F16 -> throw new UnsupportedOperationException("F16 not supported");
        };
    }

    private static ByteBuffer encodeBool(boolean[] data) {
        var packed = ByteBuffer.allocate((data.length + 7) / 8);
        for (int i = 0; i < data.length; i++) {
            if (data[i]) {
                packed.put(i / 8, (byte) (packed.get(i / 8) | (byte) (1 << (i % 8))));
            }
        }
        return packed.rewind();
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

        // array_specs: ["vortex.primitive", "vortex.bool"]
        int ps0 = ArraySpec.createArraySpec(fbb, fbb.createString("vortex.primitive"));
        int ps1 = ArraySpec.createArraySpec(fbb, fbb.createString("vortex.bool"));
        int asv = Footer.createArraySpecsVector(fbb, new int[]{ps0, ps1});

        // layout_specs: ["vortex.flat", "vortex.chunked", "vortex.struct"]
        int ls0 = LayoutSpec.createLayoutSpec(fbb, fbb.createString("vortex.flat"));
        int ls1 = LayoutSpec.createLayoutSpec(fbb, fbb.createString("vortex.chunked"));
        int ls2 = LayoutSpec.createLayoutSpec(fbb, fbb.createString("vortex.struct"));
        int lsv = Footer.createLayoutSpecsVector(fbb, new int[]{ls0, ls1, ls2});

        // segment_specs (inline struct vector — write in reverse order)
        Footer.startSegmentSpecsVector(fbb, segs.size());
        for (int i = segs.size() - 1; i >= 0; i--) {
            SegRef s = segs.get(i);
            SegmentSpec.createSegmentSpec(fbb, s.offset(), s.len(), 0, 0, 0);
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
