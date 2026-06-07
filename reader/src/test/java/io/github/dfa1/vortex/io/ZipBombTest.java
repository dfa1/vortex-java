package io.github.dfa1.vortex.io;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.encoding.ConstantEncoding;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.encoding.PrimitiveEncoding;
import io.github.dfa1.vortex.fbs.ArrayNode;
import io.github.dfa1.vortex.fbs.ArraySpec;
import io.github.dfa1.vortex.fbs.Footer;
import io.github.dfa1.vortex.fbs.Layout;
import io.github.dfa1.vortex.fbs.LayoutSpec;
import io.github.dfa1.vortex.fbs.Postscript;
import io.github.dfa1.vortex.fbs.PostscriptSegment;
import io.github.dfa1.vortex.fbs.Primitive;
import io.github.dfa1.vortex.fbs.SegmentSpec;
import io.github.dfa1.vortex.fbs.Type;
import io.github.dfa1.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.scan.ScanOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for zip-bomb style attacks against VortexReader.
 *
 * <p>Root cause: the format trusts {@code row_count} in the layout FlatBuffer without
 * validating it against actual segment byte sizes. A ~150-byte file can claim 10⁹ rows
 * and trigger an 8 GB allocation on the first {@code iter.hasNext()} call.
 *
 * <p>Both attacks are fixed: tests use small row counts safe for CI and assert the
 * expected post-fix behavior (no OOM; either completes or throws {@link io.github.dfa1.vortex.core.VortexException}).
 */
class ZipBombTest {

    // ~130-byte file claims 1 billion I64 rows; used for both attack vectors.
    private static final long BOMB = 1_000_000_000L;

    // ── Attack 1: ConstantEncoding + inflated flat row_count ──────────────────
    //
    // A tiny ~130-byte file claims many rows encoded as a constant. Before the fix,
    // ConstantEncoding.Decoder allocated n * elemBytes unconditionally.
    // After fix: decoder stores one element; array reports length=n with O(1) buffer.
    @Test
    void attack1_constantEncoding_inflatedFlatRowCount(@TempDir Path tmp) throws Exception {
        // Given — 10M rows: 80 MB if fix reverted (clean AssertionError, not JVM crash)
        long claimedRows = 10_000_000L;
        Path bomb = buildConstantBomb(tmp, claimedRows);
        var registry = EncodingRegistry.empty();
        registry.register(new ConstantEncoding());

        // When
        try (var reader = VortexReader.open(bomb, registry);
             var iter = reader.scan(ScanOptions.all())) {
            assertThat(iter.hasNext()).isTrue();
            Array col = iter.next().column("_col");

            // Then — O(1) buffer proves fix is in place; logical length unchanged
            assertThat(col.length()).isEqualTo(claimedRows);
            assertThat(col).isInstanceOf(LongArray.class);
            assertThat(ArraySegments.of(col).byteSize())
                    .as("ConstantEncoding must not allocate O(rowCount) memory")
                    .isEqualTo(Long.BYTES);
        }
    }

    // ── Attack 2: Dict layout + inflated codes flat row_count ─────────────────
    //
    // A ~200-byte file claims many codes rows; codes segment holds 1 actual byte.
    // PrimitiveEncoding wraps the mmap'd segment without allocating (no OOM there).
    // Before the fix: expandDictPrimitive pre-allocated n * elemBytes unconditionally.
    // After fix: decodeDictLayout validates bufferCodes < n → throws VortexException.
    @Test
    void attack2_dictLayout_inflatedCodesRowCount(@TempDir Path tmp) throws Exception {
        // Given — 100 rows: no OOM risk even if fix reverted; loop hits OOB on index 1
        // → IndexOutOfBoundsException (not VortexException) → clean assertion failure
        Path bomb = buildDictBomb(tmp, 100L);
        var registry = EncodingRegistry.empty();
        registry.register(new PrimitiveEncoding());

        // When / Then — VortexException before any O(n) allocation
        assertThatThrownBy(() -> {
            try (var reader = VortexReader.open(bomb, registry);
                 var iter = reader.scan(ScanOptions.all())) {
                iter.hasNext();
            }
        }).isInstanceOf(VortexException.class)
          .hasMessageContaining("codes");
    }

    // ── File builders ─────────────────────────────────────────────────────────

    /**
     * Crafts a ~130-byte .vtx file:
     * <pre>
     * [ConstantEncoding segment: proto(I64=42) + Array FlatBuffer + 4-byte fbLen]
     * [Footer: arraySpecs=["vortex.constant"], layoutSpecs=["vortex.flat"],
     *          segmentSpecs=[{offset=0, length=segLen}]]
     * [DType: I64 primitive]
     * [Layout: flat { encoding=0, row_count=claimedRows, segments=[0] }]
     * [Postscript]
     * [8-byte trailer]
     * </pre>
     */
    private static Path buildConstantBomb(Path dir, long claimedRows) throws Exception {
        // ConstantEncoding stores the scalar value in buffer 0 as protobuf bytes.
        byte[] protoBytes = ScalarProtos.ScalarValue.newBuilder()
                .setInt64Value(42L).build().toByteArray();
        byte[] seg = buildOneBufferSegment(protoBytes);

        ByteBuffer footerBuf = buildFooter(
                new String[]{"vortex.constant"},
                new String[]{"vortex.flat"},
                new long[]{0L},
                new long[]{seg.length});
        ByteBuffer dtypeBuf = buildI64Dtype();
        ByteBuffer layoutBuf = buildFlatLayout(0, claimedRows, 0);

        return writeVtxFile(dir, "constant_bomb.vtx",
                new byte[][]{seg}, footerBuf, dtypeBuf, layoutBuf);
    }

    /**
     * Crafts a ~200-byte .vtx file:
     * <pre>
     * [Segment 0: PrimitiveEncoding I64=[42]  — 8 bytes raw + FlatBuffer + fbLen]
     * [Segment 1: PrimitiveEncoding U8=[0]    — 1 byte  raw + FlatBuffer + fbLen]
     * [Footer: arraySpecs=["vortex.flat"],
     *          layoutSpecs=["vortex.flat","vortex.chunked","vortex.struct","vortex.dict"],
     *          segmentSpecs=[{0, seg0Len}, {seg0Len, seg1Len}]]
     * [DType: I64 primitive]
     * [Layout: dict { encoding=3, row_count=claimedRows,
     *            children=[values_flat(enc=0,row_count=1,seg=0),
     *                      codes_flat(enc=0,row_count=claimedRows,seg=1)] }]
     * [Postscript]
     * [8-byte trailer]
     * </pre>
     */
    private static Path buildDictBomb(Path dir, long claimedRows) throws Exception {
        // Segment 0: one I64 value = 42 (the dict value pool, 1 element).
        byte[] rawI64 = {42, 0, 0, 0, 0, 0, 0, 0};
        byte[] seg0 = buildOneBufferSegment(rawI64);

        // Segment 1: one U8 code = 0 (selects dict value 0, 1 actual row).
        byte[] rawU8 = {0};
        byte[] seg1 = buildOneBufferSegment(rawU8);

        ByteBuffer footerBuf = buildFooter(
                new String[]{"vortex.primitive"},
                new String[]{"vortex.flat", "vortex.chunked", "vortex.struct", "vortex.dict"},
                new long[]{0L, seg0.length},
                new long[]{seg0.length, seg1.length});
        ByteBuffer dtypeBuf = buildI64Dtype();
        ByteBuffer layoutBuf = buildDictLayout(claimedRows);

        return writeVtxFile(dir, "dict_bomb.vtx",
                new byte[][]{seg0, seg1}, footerBuf, dtypeBuf, layoutBuf);
    }

    // ── FlatBuffer segment builders ───────────────────────────────────────────

    /**
     * Builds: {@code [rawData][Array FlatBuffer (1 buffer)][4-byte LE fbLen]}.
     *
     * <p>The Array FlatBuffer describes one buffer at offset 0 with length {@code rawData.length}.
     * Buffer index 0 in the ArrayNode refers to this buffer.
     */
    private static byte[] buildOneBufferSegment(byte[] rawData) {
        var fbb = new FlatBufferBuilder(128);

        // ArrayNode: encoding index 0, buffers=[0], no children, no metadata
        int bufIdxVec = ArrayNode.createBuffersVector(fbb, new int[]{0});
        int nodeOff = ArrayNode.createArrayNode(fbb, 0, 0, 0, bufIdxVec, 0);

        // Array.buffers: one Buffer struct describing rawData
        io.github.dfa1.vortex.fbs.Array.startBuffersVector(fbb, 1);
        // FlatBuffers builds inline structs in reverse; struct layout (LE):
        // padding(u16) | alignmentExponent(u8) | compression(u8) | length(u32)
        fbb.prep(4, 8);
        fbb.putInt(rawData.length); // length
        fbb.putByte((byte) 0);     // compression = None
        fbb.putByte((byte) 6);     // alignmentExponent = 6 (64-byte)
        fbb.putShort((short) 0);   // padding = 0
        int bufsVec = fbb.endVector();

        int arrOff = io.github.dfa1.vortex.fbs.Array.createArray(fbb, nodeOff, bufsVec);
        io.github.dfa1.vortex.fbs.Array.finishArrayBuffer(fbb, arrOff);

        // Segment = rawData + FlatBuffer bytes + 4-byte LE fbLen
        ByteBuffer data = fbb.dataBuffer();
        int fbLen = data.remaining();
        byte[] fbBytes = new byte[fbLen];
        data.get(fbBytes);

        byte[] seg = new byte[rawData.length + fbLen + 4];
        System.arraycopy(rawData, 0, seg, 0, rawData.length);
        System.arraycopy(fbBytes, 0, seg, rawData.length, fbLen);
        seg[rawData.length + fbLen]     = (byte) (fbLen & 0xFF);
        seg[rawData.length + fbLen + 1] = (byte) ((fbLen >> 8) & 0xFF);
        seg[rawData.length + fbLen + 2] = (byte) ((fbLen >> 16) & 0xFF);
        seg[rawData.length + fbLen + 3] = (byte) ((fbLen >> 24) & 0xFF);
        return seg;
    }

    // ── FlatBuffer metadata builders ──────────────────────────────────────────

    private static ByteBuffer buildFooter(
            String[] arraySpecs, String[] layoutSpecs,
            long[] segOffsets, long[] segLengths) {
        var fbb = new FlatBufferBuilder(256);

        int[] asOffs = new int[arraySpecs.length];
        for (int i = 0; i < arraySpecs.length; i++) {
            asOffs[i] = ArraySpec.createArraySpec(fbb, fbb.createString(arraySpecs[i]));
        }
        int asv = Footer.createArraySpecsVector(fbb, asOffs);

        int[] lsOffs = new int[layoutSpecs.length];
        for (int i = 0; i < layoutSpecs.length; i++) {
            lsOffs[i] = LayoutSpec.createLayoutSpec(fbb, fbb.createString(layoutSpecs[i]));
        }
        int lsv = Footer.createLayoutSpecsVector(fbb, lsOffs);

        // SegmentSpec is an inline struct — write in reverse order
        Footer.startSegmentSpecsVector(fbb, segOffsets.length);
        for (int i = segOffsets.length - 1; i >= 0; i--) {
            SegmentSpec.createSegmentSpec(fbb, segOffsets[i], segLengths[i], 6, 0, 0);
        }
        int ssv = fbb.endVector();

        int footOff = Footer.createFooter(fbb, asv, lsv, ssv, 0, 0);
        fbb.finish(footOff);
        return slice(fbb);
    }

    private static ByteBuffer buildI64Dtype() {
        var fbb = new FlatBufferBuilder(64);
        int prim = Primitive.createPrimitive(fbb, io.github.dfa1.vortex.fbs.PType.I64, false);
        int off = io.github.dfa1.vortex.fbs.DType.createDType(fbb, Type.Primitive, prim);
        io.github.dfa1.vortex.fbs.DType.finishDTypeBuffer(fbb, off);
        return slice(fbb);
    }

    /** Flat layout: {@code vortex.flat} at layoutSpecs[layoutSpecIdx], pointing to segmentSpecs[segIdx]. */
    private static ByteBuffer buildFlatLayout(int layoutSpecIdx, long rowCount, int segIdx) {
        var fbb = new FlatBufferBuilder(128);
        int segV = Layout.createSegmentsVector(fbb, new long[]{segIdx});
        int layoutOff = Layout.createLayout(fbb, layoutSpecIdx, rowCount, 0, 0, segV);
        Layout.finishLayoutBuffer(fbb, layoutOff);
        return slice(fbb);
    }

    /**
     * Dict layout pointing at layoutSpecs[3] = "vortex.dict":
     * <pre>
     * dict(rowCount=claimedRows) {
     *   child[0] = flat(enc=0, rowCount=1,           seg=0)  ← values pool
     *   child[1] = flat(enc=0, rowCount=claimedRows, seg=1)  ← codes (INFLATED)
     * }
     * </pre>
     * {@code decodeDictLayout} reads {@code n = codesLayout.rowCount() = claimedRows}
     * then calls {@code expandDictPrimitive(..., n, arena)} which allocates {@code n * elemBytes}.
     */
    private static ByteBuffer buildDictLayout(long claimedRows) {
        var fbb = new FlatBufferBuilder(256);
        // Children must be built before the parent table
        int vSegV = Layout.createSegmentsVector(fbb, new long[]{0});
        int valuesFlat = Layout.createLayout(fbb, 0, 1L, 0, 0, vSegV);
        int cSegV = Layout.createSegmentsVector(fbb, new long[]{1});
        int codesFlat = Layout.createLayout(fbb, 0, claimedRows, 0, 0, cSegV);
        int childV = Layout.createChildrenVector(fbb, new int[]{valuesFlat, codesFlat});
        int dictOff = Layout.createLayout(fbb, 3, claimedRows, 0, childV, 0);
        Layout.finishLayoutBuffer(fbb, dictOff);
        return slice(fbb);
    }

    private static ByteBuffer slice(FlatBufferBuilder fbb) {
        ByteBuffer data = fbb.dataBuffer();
        return data.slice(data.position(), data.remaining());
    }

    // ── File assembly ─────────────────────────────────────────────────────────

    private static Path writeVtxFile(
            Path dir, String name,
            byte[][] segments,
            ByteBuffer footerBuf, ByteBuffer dtypeBuf, ByteBuffer layoutBuf
    ) throws Exception {
        long totalSegBytes = 0;
        for (byte[] seg : segments) {
            totalSegBytes += seg.length;
        }
        long footerOff = totalSegBytes;
        long dtypeOff  = footerOff + footerBuf.remaining();
        long layoutOff = dtypeOff  + dtypeBuf.remaining();

        ByteBuffer psBuf = buildPostscript(
                footerOff, footerBuf.remaining(),
                dtypeOff,  dtypeBuf.remaining(),
                layoutOff, layoutBuf.remaining());

        // Trailer: version(u16 LE) | postscriptLen(u16 LE) | magic(VTXF)
        int psLen = psBuf.remaining();
        ByteBuffer trailer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        trailer.putShort((short) 1);
        trailer.putShort((short) psLen);
        trailer.put(new byte[]{'V', 'T', 'X', 'F'});
        trailer.flip();

        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            for (byte[] seg : segments) {
                out.write(seg);
            }
            writeBuf(out, footerBuf);
            writeBuf(out, dtypeBuf);
            writeBuf(out, layoutBuf);
            writeBuf(out, psBuf);
            out.write(trailer.array());
        }
        return file;
    }

    private static ByteBuffer buildPostscript(
            long footerOff, int footerLen,
            long dtypeOff,  int dtypeLen,
            long layoutOff, int layoutLen) {
        var fbb = new FlatBufferBuilder(128);
        int footSeg   = PostscriptSegment.createPostscriptSegment(fbb, footerOff, footerLen, 0, 0, 0);
        int dtypeSeg  = PostscriptSegment.createPostscriptSegment(fbb, dtypeOff,  dtypeLen,  0, 0, 0);
        int layoutSeg = PostscriptSegment.createPostscriptSegment(fbb, layoutOff, layoutLen, 0, 0, 0);
        int psOff = Postscript.createPostscript(fbb, dtypeSeg, layoutSeg, 0, footSeg);
        Postscript.finishPostscriptBuffer(fbb, psOff);
        return slice(fbb);
    }

    private static void writeBuf(OutputStream out, ByteBuffer buf) throws Exception {
        buf = buf.duplicate();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        out.write(bytes);
    }
}
