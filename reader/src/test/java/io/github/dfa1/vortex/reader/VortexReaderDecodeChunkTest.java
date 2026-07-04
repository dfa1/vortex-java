package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.fbs.FbsArray;
import io.github.dfa1.vortex.core.fbs.FbsArrayNode;
import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.fbs.FbsDType;
import io.github.dfa1.vortex.core.fbs.FbsLayout;
import io.github.dfa1.vortex.core.fbs.FbsPType;
import io.github.dfa1.vortex.core.fbs.FbsPrimitive;
import io.github.dfa1.vortex.core.fbs.FbsStruct_;
import io.github.dfa1.vortex.core.fbs.FbsType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.decode.BoolEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests for [VortexReader#decodeChunk(int, List)] / [VortexReader#chunkCount()] — the
/// random-access "chunk-subset decode" primitive (ADR 0013 §6, step A).
///
/// The key invariant is that a chunk decoded at random must be byte-identical to the same
/// chunk obtained by streaming the full scan and taking the k-th element. Each test builds a
/// small multi-chunk struct file by hand (no writer dependency in this module) following the
/// same FlatBuffer assembly the security suite uses, writes it to a temp dir, and reopens it
/// with [VortexReader] — VortexReader mmaps a path, so a real (tiny) file is unavoidable.
class VortexReaderDecodeChunkTest {

    // A 2-column struct with chunk boundaries [2, 3, 2] = 7 rows over 3 chunks. Column "a" is
    // non-nullable; column "b" is nullable and carries an explicit validity bitmap so the
    // round-trip exercises the MaskedArray path, not just plain primitives.
    private static final long[] CHUNK_ROWS = {2, 3, 2};
    private static final long[][] COL_A = {{10, 11}, {12, 13, 14}, {15, 16}};
    private static final long[][] COL_B = {{100, 101}, {102, 103, 104}, {105, 106}};
    private static final boolean[][] COL_B_VALID = {
            {true, false}, {true, true, false}, {false, true}
    };

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void decodeChunk_matchesStreamingScan(int chunkIndex, @TempDir Path tmp) throws Exception {
        // Given — a multi-chunk file and the streamed values for every chunk
        Path file = writeMultiChunkFile(tmp);
        try (var reader = VortexReader.open(file, registry())) {
            List<Map<String, List<Long>>> streamed = streamAll(reader);

            // When — decode exactly that chunk in isolation
            List<Long> resultA;
            List<Long> resultB;
            try (Chunk result = reader.decodeChunk(chunkIndex, List.of("a", "b"))) {
                // Then — same length and same per-element values/nulls as the streamed chunk
                assertThat(result.rowCount()).isEqualTo(CHUNK_ROWS[chunkIndex]);
                resultA = values(result.column("a"));
                resultB = values(result.column("b"));
            }
            assertThat(resultA).isEqualTo(streamed.get(chunkIndex).get("a"));
            assertThat(resultB).isEqualTo(streamed.get(chunkIndex).get("b"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void decodeChunk_columnSubset_matchesStreaming(int chunkIndex, @TempDir Path tmp) throws Exception {
        // Given — only column "a" is requested out of the two
        Path file = writeMultiChunkFile(tmp);
        try (var reader = VortexReader.open(file, registry())) {
            List<Map<String, List<Long>>> streamed = streamAll(reader);

            // When
            List<Long> resultA;
            try (Chunk result = reader.decodeChunk(chunkIndex, List.of("a"))) {
                // Then — only the projected column is present, and it matches the stream
                assertThat(result.columns().keySet()).containsExactly(ColumnName.of("a"));
                resultA = values(result.column("a"));
            }
            assertThat(resultA).isEqualTo(streamed.get(chunkIndex).get("a"));
        }
    }

    @Test
    void decodeChunk_emptyColumns_decodesAllColumns(@TempDir Path tmp) throws Exception {
        // Given — empty column list means "all columns" (mirrors ScanOptions)
        Path file = writeMultiChunkFile(tmp);
        try (var reader = VortexReader.open(file, registry())) {

            // When
            try (Chunk result = reader.decodeChunk(0, List.of())) {

                // Then — both columns decoded
                assertThat(result.columns().keySet())
                        .containsExactlyInAnyOrder(ColumnName.of("a"), ColumnName.of("b"));
            }
        }
    }

    @Test
    void chunkCount_matchesChunkRowCountsAndStreamedCount(@TempDir Path tmp) throws Exception {
        // Given
        Path file = writeMultiChunkFile(tmp);
        try (var reader = VortexReader.open(file, registry())) {

            // When
            int result = reader.chunkCount();

            // Then — equal to the layout's chunk-row-counts length and the streamed count
            try (var iter = reader.scan(ScanOptions.all())) {
                assertThat(result).isEqualTo(iter.chunkRowCounts().length);
            }
            assertThat(result).isEqualTo(CHUNK_ROWS.length);
            assertThat(result).isEqualTo(streamAll(reader).size());
        }
    }

    @Test
    void decodeChunk_negativeIndex_throwsVortexException(@TempDir Path tmp) throws Exception {
        // Given
        Path file = writeMultiChunkFile(tmp);
        try (var reader = VortexReader.open(file, registry())) {

            // When / Then
            assertThatThrownBy(() -> reader.decodeChunk(-1, List.of("a")))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of bounds");
        }
    }

    @Test
    void decodeChunk_indexAtChunkCount_throwsVortexException(@TempDir Path tmp) throws Exception {
        // Given — chunkCount() is one past the last valid index
        Path file = writeMultiChunkFile(tmp);
        try (var reader = VortexReader.open(file, registry())) {
            int oob = reader.chunkCount();

            // When / Then
            assertThatThrownBy(() -> reader.decodeChunk(oob, List.of("a")))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of bounds");
        }
    }

    @Test
    void decodeChunk_unknownColumn_throwsVortexException(@TempDir Path tmp) throws Exception {
        // Given
        Path file = writeMultiChunkFile(tmp);
        try (var reader = VortexReader.open(file, registry())) {

            // When / Then
            assertThatThrownBy(() -> reader.decodeChunk(0, List.of("nope")))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unknown column");
        }
    }

    @Test
    void decodeChunk_twice_yieldsIndependentChunks(@TempDir Path tmp) throws Exception {
        // Given — two separate decodes of the same chunk
        Path file = writeMultiChunkFile(tmp);
        try (var reader = VortexReader.open(file, registry())) {
            Chunk first = reader.decodeChunk(1, List.of("a", "b"));
            Chunk second = reader.decodeChunk(1, List.of("a", "b"));

            // When — closing the first chunk
            first.close();

            // Then — the second chunk is unaffected and still fully readable
            assertThat(first.isClosed()).isTrue();
            assertThat(second.isClosed()).isFalse();
            assertThat(values(second.column("a"))).isEqualTo(List.of(12L, 13L, 14L));
            second.close();
        }
    }

    @Test
    void decodeChunk_singleChunkFile_decodesTheOnlyChunk(@TempDir Path tmp) throws Exception {
        // Given — a file with exactly one chunk of 3 rows
        Path file = writeFile(tmp, "single.vtx", new long[]{3},
                new long[][]{{20, 21, 22}}, new long[][]{{200, 201, 202}},
                new boolean[][]{{true, true, true}});
        try (var reader = VortexReader.open(file, registry())) {

            // When
            assertThat(reader.chunkCount()).isEqualTo(1);
            try (Chunk result = reader.decodeChunk(0, List.of("a", "b"))) {

                // Then
                assertThat(result.rowCount()).isEqualTo(3);
                assertThat(values(result.column("a"))).isEqualTo(List.of(20L, 21L, 22L));
                assertThat(values(result.column("b"))).isEqualTo(List.of(200L, 201L, 202L));
            }
        }
    }

    // A shared single-flat column "c": one flat covering ALL 7 rows, sliced per chunk. Its
    // validity pattern is deliberately mixed so the slice path also crosses the MaskedArray /
    // OffsetBoolArray validity-slice branch, not just plain primitives.
    private static final long[] COL_C_ALL = {1000, 1001, 1002, 1003, 1004, 1005, 1006};
    private static final boolean[] COL_C_VALID = {true, false, true, true, false, true, true};

    @Test
    void decodeChunk_sharedSingleFlatColumn_isValueEquivalentAndSurvivesIteratorClose(@TempDir Path tmp)
            throws Exception {
        // This pins the load-bearing lifetime invariant of decodeChunk for a *shared*
        // (single-flat) column — a column stored as ONE flat covering every row and sliced per
        // chunk via sliceOffsets (ScanIterator.decodeOrSliceSelfContained's else branch). The
        // streaming scan slices such a column out of ScanIterator's internal `sharedArena`,
        // which is freed when the iterator closes; decodeChunk must instead RE-decode the column
        // into the returned Chunk's OWN arena so reads stay valid after that internal iterator is
        // closed (VortexReader.decodeChunk closes it before returning). We therefore assert both
        // value-equivalence with the stream AND that the slices are still correct once decodeChunk
        // has returned — proving no read touches the freed sharedArena. Without the existing
        // per-column "a", "c" would have 1 flat == the widest column and decode as a single chunk,
        // never exercising the shared branch; "a" forces the 1-vs-N shared routing.

        // Given — a struct with a per-chunk column "a" and a single-flat shared column "c"
        Path file = writeSharedColumnFile(tmp);
        try (var reader = VortexReader.open(file, registry())) {
            List<Map<String, List<Long>>> streamed = streamAllShared(reader);

            // When / Then — every chunk's shared-column slice matches the streamed slice (values,
            // nulls, and length), proving random-access decode == streaming for the shared branch.
            for (int k = 0; k < streamed.size(); k++) {
                try (Chunk result = reader.decodeChunk(k, List.of("a", "c"))) {
                    assertThat(result.rowCount()).isEqualTo(CHUNK_ROWS[k]);
                    assertThat(values(result.column("a"))).isEqualTo(streamed.get(k).get("a"));
                    assertThat(values(result.column("c"))).isEqualTo(streamed.get(k).get("c"));
                }
            }

            // When — hold two chunks open at once; each decodeChunk's internal ScanIterator (and
            // its sharedArena) is already closed by the time the Chunk is handed back.
            Chunk firstChunk = reader.decodeChunk(0, List.of("a", "c"));
            Chunk secondChunk = reader.decodeChunk(2, List.of("a", "c"));

            // Then — reading the shared column AFTER decodeChunk returned still succeeds and is
            // correct, for the first chunk even after a second has been obtained: the Chunk owns
            // its buffers and holds no reference into any freed sharedArena.
            List<Long> firstShared = values(firstChunk.column("c"));
            List<Long> secondShared = values(secondChunk.column("c"));
            assertThat(firstShared).isEqualTo(streamed.get(0).get("c"));
            assertThat(secondShared).isEqualTo(streamed.get(2).get("c"));

            firstChunk.close();
            secondChunk.close();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static ReadRegistry registry() {
        return ReadRegistry.builder()
                .register(new PrimitiveEncodingDecoder())
                .register(new BoolEncodingDecoder())
                .build();
    }

    /// Streams the whole scan, collecting each chunk's two columns into Long lists (null for
    /// invalid positions) so a random-access decode can be compared element-by-element.
    private static List<Map<String, List<Long>>> streamAll(VortexReader reader) {
        List<Map<String, List<Long>>> out = new ArrayList<>();
        try (var iter = reader.scan(ScanOptions.all())) {
            iter.forEachRemaining(chunk ->
                    out.add(Map.of("a", values(chunk.column("a")), "b", values(chunk.column("b")))));
        }
        return out;
    }

    private static List<Long> values(Array arr) {
        List<Long> out = new ArrayList<>();
        if (arr instanceof MaskedArray masked) {
            LongArray inner = (LongArray) masked.inner();
            for (long i = 0; i < masked.length(); i++) {
                out.add(masked.isValid(i) ? inner.getLong(i) : null);
            }
        } else {
            LongArray la = (LongArray) arr;
            for (long i = 0; i < la.length(); i++) {
                out.add(la.getLong(i));
            }
        }
        return out;
    }

    private static Path writeMultiChunkFile(Path dir) throws Exception {
        return writeFile(dir, "multi.vtx", CHUNK_ROWS, COL_A, COL_B, COL_B_VALID);
    }

    /// Streams the whole scan of the shared-column file, collecting the per-chunk column "a" and
    /// the per-chunk slice of the single-flat shared column "c" so a random-access decode can be
    /// compared element-by-element.
    private static List<Map<String, List<Long>>> streamAllShared(VortexReader reader) {
        List<Map<String, List<Long>>> out = new ArrayList<>();
        try (var iter = reader.scan(ScanOptions.all())) {
            iter.forEachRemaining(chunk ->
                    out.add(Map.of("a", values(chunk.column("a")), "c", values(chunk.column("c")))));
        }
        return out;
    }

    /// Assembles a struct file whose first column "a" is chunked (one flat per chunk) and whose
    /// second column "c" is a single flat covering all rows — the shared / `sliceOffsets` shape
    /// that [ScanIterator] re-decodes per chunk. Segment order: a-chunk0..a-chunkN, then the one
    /// shared "c" segment. Layout: flat=0, chunked=1, struct=2; array specs: primitive=0, bool=1.
    private static Path writeSharedColumnFile(Path dir) throws Exception {
        int numChunks = CHUNK_ROWS.length;
        List<byte[]> segments = new ArrayList<>();
        for (int k = 0; k < numChunks; k++) {
            segments.add(primitiveSegment(COL_A[k], null));
        }
        // One flat for the whole "c" column (all rows), sliced per chunk by the reader.
        int sharedSegIdx = segments.size();
        segments.add(primitiveSegment(COL_C_ALL, COL_C_VALID));

        long[] segOffsets = new long[segments.size()];
        long[] segLengths = new long[segments.size()];
        long off = 0;
        for (int i = 0; i < segments.size(); i++) {
            segOffsets[i] = off;
            segLengths[i] = segments.get(i).length;
            off += segments.get(i).length;
        }

        ByteBuffer footerBuf = MalformedFiles.buildFooter(
                new String[]{"vortex.primitive", "vortex.bool"},
                new String[]{"vortex.flat", "vortex.chunked", "vortex.struct"},
                segOffsets, segLengths);
        ByteBuffer dtypeBuf = buildSharedDtype();
        ByteBuffer layoutBuf = buildSharedLayout(CHUNK_ROWS, sharedSegIdx);

        return writeVtxFile(dir, "shared.vtx", segments, footerBuf, dtypeBuf, layoutBuf);
    }

    /// Struct DType for the shared-column file: "a" non-nullable I64, "c" nullable I64.
    private static ByteBuffer buildSharedDtype() {
        var fbb = new FbsBuilder(256);
        int primA = FbsPrimitive.createFbsPrimitive(fbb, FbsPType.I64, false);
        int dtA = FbsDType.createFbsDType(fbb, FbsType.FbsPrimitive, primA);
        int primC = FbsPrimitive.createFbsPrimitive(fbb, FbsPType.I64, true);
        int dtC = FbsDType.createFbsDType(fbb, FbsType.FbsPrimitive, primC);
        int names = FbsStruct_.createNamesVector(fbb,
                new int[]{fbb.createString("a"), fbb.createString("c")});
        int dtypes = FbsStruct_.createDtypesVector(fbb, new int[]{dtA, dtC});
        int struct = FbsStruct_.createFbsStruct_(fbb, names, dtypes, false);
        int dt = FbsDType.createFbsDType(fbb, FbsType.FbsStruct_, struct);
        FbsDType.finishFbsDTypeBuffer(fbb, dt);
        return MalformedFiles.slice(fbb);
    }

    /// Layout for the shared-column file: child 0 = chunked "a" (one flat per chunk); child 1 =
    /// a single flat "c" with `rowCount == total` over `sharedSegIdx` — the 1-vs-N shape that
    /// routes "c" through [ScanIterator]'s shared / slice-offset branch.
    private static ByteBuffer buildSharedLayout(long[] chunkRows, int sharedSegIdx) {
        var fbb = new FbsBuilder(1024);
        int numChunks = chunkRows.length;
        long total = 0;
        for (long r : chunkRows) {
            total += r;
        }
        int[] flatOffs = new int[numChunks];
        for (int k = 0; k < numChunks; k++) {
            int segV = FbsLayout.createSegmentsVector(fbb, new long[]{k});
            flatOffs[k] = FbsLayout.createFbsLayout(fbb, 0, chunkRows[k], 0, 0, segV);
        }
        int chunkedChildV = FbsLayout.createChildrenVector(fbb, flatOffs);
        int chunkedA = FbsLayout.createFbsLayout(fbb, 1, total, 0, chunkedChildV, 0);

        int sharedSegV = FbsLayout.createSegmentsVector(fbb, new long[]{sharedSegIdx});
        int flatC = FbsLayout.createFbsLayout(fbb, 0, total, 0, 0, sharedSegV);

        int structChildV = FbsLayout.createChildrenVector(fbb, new int[]{chunkedA, flatC});
        int structOff = FbsLayout.createFbsLayout(fbb, 2, total, 0, structChildV, 0);
        FbsLayout.finishFbsLayoutBuffer(fbb, structOff);
        return MalformedFiles.slice(fbb);
    }

    /// Assembles a struct file: root struct → per-column chunked → flat-per-chunk, with column
    /// "a" non-nullable and column "b" nullable (validity bitmap). Layout: flat=0, chunked=1,
    /// struct=2; array specs: vortex.primitive=0, vortex.bool=1.
    private static Path writeFile(Path dir, String name, long[] chunkRows,
            long[][] colA, long[][] colB, boolean[][] colBValid) throws Exception {
        int numChunks = chunkRows.length;
        // Segment order: a-chunk0, a-chunk1, ..., b-chunk0, b-chunk1, ...
        List<byte[]> segments = new ArrayList<>();
        for (int k = 0; k < numChunks; k++) {
            segments.add(primitiveSegment(colA[k], null));
        }
        for (int k = 0; k < numChunks; k++) {
            segments.add(primitiveSegment(colB[k], colBValid[k]));
        }

        long[] segOffsets = new long[segments.size()];
        long[] segLengths = new long[segments.size()];
        long off = 0;
        for (int i = 0; i < segments.size(); i++) {
            segOffsets[i] = off;
            segLengths[i] = segments.get(i).length;
            off += segments.get(i).length;
        }

        // a uses segment indices [0, numChunks); b uses [numChunks, 2*numChunks).
        int[][] colSegIndices = new int[2][numChunks];
        for (int k = 0; k < numChunks; k++) {
            colSegIndices[0][k] = k;
            colSegIndices[1][k] = numChunks + k;
        }

        ByteBuffer footerBuf = MalformedFiles.buildFooter(
                new String[]{"vortex.primitive", "vortex.bool"},
                new String[]{"vortex.flat", "vortex.chunked", "vortex.struct"},
                segOffsets, segLengths);
        ByteBuffer dtypeBuf = buildStructDtype();
        ByteBuffer layoutBuf = buildLayout(colSegIndices, chunkRows);

        return writeVtxFile(dir, name, segments, footerBuf, dtypeBuf, layoutBuf);
    }

    /// Builds a flat I64 segment: raw data buffer plus (when `valid != null`) a validity
    /// bitmap buffer wired as a `vortex.bool` validity child of the primitive node.
    private static byte[] primitiveSegment(long[] values, boolean[] valid) {
        byte[] raw = new byte[values.length * 8];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().put(values);
        if (valid == null) {
            return assembleSegment(new byte[][]{raw}, fbb -> {
                int bufs = FbsArrayNode.createBuffersVector(fbb, new int[]{0});
                return FbsArrayNode.createFbsArrayNode(fbb, 0, 0, 0, bufs, 0);
            });
        }
        byte[] validityBytes = new byte[(values.length + 7) / 8];
        for (int i = 0; i < values.length; i++) {
            if (valid[i]) {
                validityBytes[i / 8] |= (byte) (1 << (i % 8));
            }
        }
        return assembleSegment(new byte[][]{raw, validityBytes}, fbb -> {
            int childBufs = FbsArrayNode.createBuffersVector(fbb, new int[]{1});
            int childNode = FbsArrayNode.createFbsArrayNode(fbb, 1, 0, 0, childBufs, 0);
            int childrenVec = FbsArrayNode.createChildrenVector(fbb, new int[]{childNode});
            int rootBufs = FbsArrayNode.createBuffersVector(fbb, new int[]{0});
            return FbsArrayNode.createFbsArrayNode(fbb, 0, 0, childrenVec, rootBufs, 0);
        });
    }

    /// Concatenates the physical buffers, then the Array FlatBuffer describing them, then the
    /// 4-byte LE FlatBuffer length — the flat-segment wire format.
    private static byte[] assembleSegment(byte[][] buffers, java.util.function.ToIntFunction<FbsBuilder> rootBuilder) {
        var fbb = new FbsBuilder(256);
        int rootNode = rootBuilder.applyAsInt(fbb);
        FbsArray.startBuffersVector(fbb, buffers.length);
        // Inline structs are written in reverse: padding(u16)|alignmentExponent(u8)|compression(u8)|length(u32)
        for (int i = buffers.length - 1; i >= 0; i--) {
            fbb.prep(4, 8);
            fbb.putInt(buffers[i].length);
            fbb.putByte((byte) 0);
            fbb.putByte((byte) 6);
            fbb.putShort((short) 0);
        }
        int bufsVec = fbb.endVector();
        int arrOff = FbsArray.createFbsArray(fbb, rootNode, bufsVec);
        FbsArray.finishFbsArrayBuffer(fbb, arrOff);

        byte[] fbBytes = fbb.sizedByteArray();
        int totalRaw = 0;
        for (byte[] b : buffers) {
            totalRaw += b.length;
        }
        byte[] seg = new byte[totalRaw + fbBytes.length + 4];
        int pos = 0;
        for (byte[] b : buffers) {
            System.arraycopy(b, 0, seg, pos, b.length);
            pos += b.length;
        }
        System.arraycopy(fbBytes, 0, seg, pos, fbBytes.length);
        int lenPos = pos + fbBytes.length;
        seg[lenPos] = (byte) (fbBytes.length & 0xFF);
        seg[lenPos + 1] = (byte) ((fbBytes.length >> 8) & 0xFF);
        seg[lenPos + 2] = (byte) ((fbBytes.length >> 16) & 0xFF);
        seg[lenPos + 3] = (byte) ((fbBytes.length >> 24) & 0xFF);
        return seg;
    }

    private static ByteBuffer buildStructDtype() {
        var fbb = new FbsBuilder(256);
        int primA = FbsPrimitive.createFbsPrimitive(fbb, FbsPType.I64, false);
        int dtA = FbsDType.createFbsDType(fbb, FbsType.FbsPrimitive, primA);
        int primB = FbsPrimitive.createFbsPrimitive(fbb, FbsPType.I64, true);
        int dtB = FbsDType.createFbsDType(fbb, FbsType.FbsPrimitive, primB);
        int names = FbsStruct_.createNamesVector(fbb,
                new int[]{fbb.createString("a"), fbb.createString("b")});
        int dtypes = FbsStruct_.createDtypesVector(fbb, new int[]{dtA, dtB});
        int struct = FbsStruct_.createFbsStruct_(fbb, names, dtypes, false);
        int dt = FbsDType.createFbsDType(fbb, FbsType.FbsStruct_, struct);
        FbsDType.finishFbsDTypeBuffer(fbb, dt);
        return MalformedFiles.slice(fbb);
    }

    private static ByteBuffer buildLayout(int[][] colSegIndices, long[] chunkRows) {
        var fbb = new FbsBuilder(1024);
        int numCols = colSegIndices.length;
        int numChunks = chunkRows.length;
        long total = 0;
        for (long r : chunkRows) {
            total += r;
        }
        int[] chunkedOffs = new int[numCols];
        for (int c = 0; c < numCols; c++) {
            int[] flatOffs = new int[numChunks];
            for (int k = 0; k < numChunks; k++) {
                int segV = FbsLayout.createSegmentsVector(fbb, new long[]{colSegIndices[c][k]});
                flatOffs[k] = FbsLayout.createFbsLayout(fbb, 0, chunkRows[k], 0, 0, segV);
            }
            int childV = FbsLayout.createChildrenVector(fbb, flatOffs);
            chunkedOffs[c] = FbsLayout.createFbsLayout(fbb, 1, total, 0, childV, 0);
        }
        int structChildV = FbsLayout.createChildrenVector(fbb, chunkedOffs);
        int structOff = FbsLayout.createFbsLayout(fbb, 2, total, 0, structChildV, 0);
        FbsLayout.finishFbsLayoutBuffer(fbb, structOff);
        return MalformedFiles.slice(fbb);
    }

    private static Path writeVtxFile(Path dir, String name, List<byte[]> segments,
            ByteBuffer footerBuf, ByteBuffer dtypeBuf, ByteBuffer layoutBuf) throws Exception {
        long totalSegBytes = 0;
        for (byte[] seg : segments) {
            totalSegBytes += seg.length;
        }
        long footerOff = totalSegBytes;
        long dtypeOff = footerOff + footerBuf.remaining();
        long layoutOff = dtypeOff + dtypeBuf.remaining();

        ByteBuffer psBuf = MalformedFiles.buildPostscript(
                footerOff, footerBuf.remaining(),
                dtypeOff, dtypeBuf.remaining(),
                layoutOff, layoutBuf.remaining());

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
        ByteBuffer dup = buf.duplicate();
        byte[] bytes = new byte[dup.remaining()];
        dup.get(bytes);
        out.write(bytes);
    }
}
