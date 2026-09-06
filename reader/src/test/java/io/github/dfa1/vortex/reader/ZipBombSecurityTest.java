package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import static io.github.dfa1.vortex.reader.MalformedFiles.buildFooter;
import static io.github.dfa1.vortex.reader.MalformedFiles.buildI64Dtype;
import static io.github.dfa1.vortex.reader.MalformedFiles.buildFlatLayout;
import static io.github.dfa1.vortex.reader.MalformedFiles.buildPostscript;
import static io.github.dfa1.vortex.reader.MalformedFiles.slice;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LazyConstantLongArray;

import io.github.dfa1.vortex.reader.decode.ConstantEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import io.github.dfa1.vortex.core.fbs.FbsArrayNode;
import io.github.dfa1.vortex.core.fbs.FbsLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;

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
 * <p>Root cause: the format trusts `row_count` in the layout FlatBuffer without
 * validating it against actual segment byte sizes. A ~150-byte file can claim 10⁹ rows
 * and trigger an 8 GB allocation on the first `iter.hasNext()` call.
 *
 * <p>Both attacks are fixed: tests use small row counts safe for CI and assert the
 * expected post-fix behavior (no OOM; either completes or throws [io.github.dfa1.vortex.core.error.VortexException]).
 */
class ZipBombSecurityTest {

    // ~130-byte file claims 1 billion I64 rows; used for both attack vectors.
    private static final long BOMB = 1_000_000_000L;

    // ── Attack 1: ConstantEncoding + inflated flat row_count ──────────────────
    //
    // A tiny ~130-byte file claims many rows encoded as a constant. Before the fix,
    // ConstantEncoding.Decoder allocated n * elemBytes unconditionally.
    // After fix: decoder emits a metadata-only LazyConstantLongArray; no buffer.
    @Test
    void attack1_constantEncoding_inflatedFlatRowCount(@TempDir Path tmp) throws Exception {
        // Given — 10M rows: 80 MB if fix reverted (clean AssertionError, not JVM crash)
        long claimedRows = 10_000_000L;
        Path bomb = buildConstantBomb(tmp, claimedRows);
        var registry = ReadRegistry.builder().register(new ConstantEncodingDecoder()).build();

        // When
        try (var reader = VortexReader.open(bomb, registry);
             var iter = reader.scan(ScanOptions.all())) {
            assertThat(iter.hasNext()).isTrue();
            Array col = iter.next().column("_col");

            // Then — LazyConstantLongArray carries no buffer at all, only metadata,
            // so the O(rowCount) allocation the bomb would trigger cannot occur by
            // construction. Length is preserved and the broadcast value resolves
            // identically for any valid index.
            assertThat(col.length()).isEqualTo(claimedRows);
            assertThat(col)
                    .as("ConstantEncoding must produce a metadata-only LazyConstantLongArray")
                    .isInstanceOf(LazyConstantLongArray.class);
            LazyConstantLongArray lazy = (LazyConstantLongArray) col;
            assertThat(lazy.getLong(0)).isEqualTo(lazy.value());
            assertThat(lazy.getLong(claimedRows - 1)).isEqualTo(lazy.value());
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
        var registry = ReadRegistry.builder().register(new PrimitiveEncodingDecoder()).build();

        // When / Then — VortexException before any O(n) allocation. Decode now runs
        // in next() (hasNext() is side-effect free), so the validation throws there.
        assertThatThrownBy(() -> decodeFirstChunk(bomb, registry))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("codes");
    }

    /// Opens the file and decodes its first chunk, discarding it. Keeps the assertion lambda
    /// down to a single call so the decode-time guard is the only thing that can throw.
    private static void decodeFirstChunk(Path file, ReadRegistry registry) throws Exception {
        try (var reader = VortexReader.open(file, registry);
             var iter = reader.scan(ScanOptions.all())) {
            iter.next();
        }
    }

    // ── File builders ─────────────────────────────────────────────────────────

    /**
     * Crafts a ~130-byte .vtx file:
     * <pre>
     * [ConstantEncoding segment: proto(I64=42) + Array FlatBuffer + 4-byte fbLen]
     * [Footer: arraySpecs=["vortex.constant"], layoutSpecs=["vortex.flat"],
     *          segmentSpecs=[{offset=0, length=segLen}]]
     * [DType: I64 primitive]
     * [FbsLayout: flat { encoding=0, row_count=claimedRows, segments=[0] }]
     * [Postscript]
     * [8-byte trailer]
     * </pre>
     */
    private static Path buildConstantBomb(Path dir, long claimedRows) throws Exception {
        // ConstantEncoding stores the scalar value in buffer 0 as protobuf bytes.
        byte[] protoBytes = ProtoScalarValue.ofInt64Value(42L).encode();
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
     * [FbsLayout: dict { encoding=3, row_count=claimedRows,
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
     * Builds: `[rawData][Array FlatBuffer (1 buffer)][4-byte LE fbLen]`.
     *
     * <p>The Array FlatBuffer describes one buffer at offset 0 with length `rawData.length`.
     * Buffer index 0 in the FbsArrayNode refers to this buffer.
     */
    private static byte[] buildOneBufferSegment(byte[] rawData) {
        var fbb = new FbsBuilder(128);

        // FbsArrayNode: encoding index 0, buffers=[0], no children, no metadata
        int bufIdxVec = FbsArrayNode.createBuffersVector(fbb, new int[]{0});
        int nodeOff = FbsArrayNode.createFbsArrayNode(fbb, 0, 0, 0, bufIdxVec, 0);

        // Array.buffers: one Buffer struct describing rawData
        io.github.dfa1.vortex.core.fbs.FbsArray.startBuffersVector(fbb, 1);
        // FlatBuffers builds inline structs in reverse; struct layout (LE):
        // padding(u16) | alignmentExponent(u8) | compression(u8) | length(u32)
        fbb.prep(4, 8);
        fbb.putInt(rawData.length); // length
        fbb.putByte((byte) 0);     // compression = None
        fbb.putByte((byte) 6);     // alignmentExponent = 6 (64-byte)
        fbb.putShort((short) 0);   // padding = 0
        int bufsVec = fbb.endVector();

        int arrOff = io.github.dfa1.vortex.core.fbs.FbsArray.createFbsArray(fbb, nodeOff, bufsVec);
        io.github.dfa1.vortex.core.fbs.FbsArray.finishFbsArrayBuffer(fbb, arrOff);

        // Segment = rawData + FlatBuffer bytes + 4-byte LE fbLen
        byte[] fbBytes = fbb.sizedByteArray();
        int fbLen = fbBytes.length;

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

    /**
     * Dict layout pointing at layoutSpecs[3] = "vortex.dict":
     * <pre>
     * dict(rowCount=claimedRows) {
     *   child[0] = flat(enc=0, rowCount=1,           seg=0)  ← values pool
     *   child[1] = flat(enc=0, rowCount=claimedRows, seg=1)  ← codes (INFLATED)
     * }
     * </pre>
     * `decodeDictLayout` reads `n = codesLayout.rowCount() = claimedRows`
     * then calls `expandDictPrimitive(..., n, arena)` which allocates `n * elemBytes`.
     */
    private static ByteBuffer buildDictLayout(long claimedRows) {
        var fbb = new FbsBuilder(256);
        // Children must be built before the parent table
        int vSegV = FbsLayout.createSegmentsVector(fbb, new long[]{0});
        int valuesFlat = FbsLayout.createFbsLayout(fbb, 0, 1L, 0, 0, vSegV);
        int cSegV = FbsLayout.createSegmentsVector(fbb, new long[]{1});
        int codesFlat = FbsLayout.createFbsLayout(fbb, 0, claimedRows, 0, 0, cSegV);
        int childV = FbsLayout.createChildrenVector(fbb, new int[]{valuesFlat, codesFlat});
        int dictOff = FbsLayout.createFbsLayout(fbb, 3, claimedRows, 0, childV, 0);
        FbsLayout.finishFbsLayoutBuffer(fbb, dictOff);
        return slice(fbb);
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


    private static void writeBuf(OutputStream out, ByteBuffer buf) throws Exception {
        buf = buf.duplicate();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        out.write(bytes);
    }
}
