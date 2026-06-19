package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.reader.Layout;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.fbs.Array;
import io.github.dfa1.vortex.fbs.ArrayNode;
import io.github.dfa1.vortex.fbs.Buffer;
import io.github.dfa1.vortex.reader.VortexReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Phase 0 scoping for `vortex.pco` decode port.
///
/// Inspects each pco-blocked fixture: walks layout, locates `vortex.pco`-encoded
/// `ArrayNode`s, hand-parses the `PcoMetadata` proto (no generated stub needed yet),
/// and dumps mode/delta nibbles from the first chunk_meta buffer.
///
/// Output drives the Phase 5 scope decision (which pco modes / delta encodings to
/// implement first).
class PcoFixtureInspectionIntegrationTest {

    private static final String BASE = "https://vortex-compat-fixtures.s3.amazonaws.com/v0.72.0/arrays/";
    private static final String[] FIXTURES = {
            "pco.vortex",
            "tpch_lineitem.compact.vortex",
            "tpch_orders.compact.vortex",
            "clickbench_hits_5k.compact.vortex"
    };

    private static void inspect(Path file, StringBuilder out) throws Exception {
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.empty())) {
            out.append("dtype: ").append(formatDType(vf.dtype())).append('\n');
            out.append("size: ").append(vf.fileSize()).append(" bytes\n");

            List<String> arraySpecs = vf.footer().arraySpecs();
            List<SegmentSpec> segmentSpecs = vf.footer().segmentSpecs();

            PcoStats stats = new PcoStats();
            walkLayout(vf, vf.layout(), arraySpecs, segmentSpecs, stats, columnPath(vf.dtype()));

            out.append("pco arrays found: ").append(stats.pcoArrayCount).append('\n');
            out.append("pco array column paths: ").append(stats.columnPaths).append('\n');
            out.append("pco array ptypes: ").append(stats.ptypeCounts).append('\n');
            out.append("header version bytes seen: ").append(stats.headerVersions).append('\n');
            out.append("chunks per pco array: ").append(stats.chunksPerArray).append('\n');
            out.append("pages per pco array: ").append(stats.pagesPerArray).append('\n');
            out.append("n_values per page (sample first 10): ").append(stats.firstPageSizes).append('\n');
            out.append("first chunk_meta bytes (hex, first 16) per array:\n");
            for (String hex : stats.firstChunkMetaHexes) {
                out.append("  ").append(hex).append('\n');
            }
            out.append("decoded leading nibbles (mode | delta as 4b):\n");
            for (String n : stats.firstChunkMetaNibbles) {
                out.append("  ").append(n).append('\n');
            }
        }
    }

    private static List<String> columnPath(DType root) {
        List<String> names = new ArrayList<>();
        if (root instanceof DType.Struct s) {
            names.addAll(s.fieldNames());
        } else {
            names.add("<root>");
        }
        return names;
    }

    private static void walkLayout(VortexReader vf, Layout layout, List<String> arraySpecs,
            List<SegmentSpec> segmentSpecs, PcoStats stats, List<String> colNames) {
        walkLayoutInner(vf, layout, arraySpecs, segmentSpecs, stats, colNames, "");
    }

    private static void walkLayoutInner(VortexReader vf, Layout layout, List<String> arraySpecs,
            List<SegmentSpec> segmentSpecs, PcoStats stats,
            List<String> colNames, String currentPath) {
        if (layout.isStruct()) {
            for (int i = 0; i < layout.children().size(); i++) {
                String name = i < colNames.size() ? colNames.get(i) : "col" + i;
                walkLayoutInner(vf, layout.children().get(i), arraySpecs, segmentSpecs, stats, List.of(), name);
            }
            return;
        }
        if ((layout.isFlat() || layout.isDict()) && !layout.segments().isEmpty()) {
            int segIdx = layout.segments().getFirst();
            SegmentSpec spec = segmentSpecs.get(segIdx);
            MemorySegment seg = vf.rawSegment(spec);
            scanFlatSegment(seg, arraySpecs, stats, currentPath);
            return;
        }
        for (Layout child : layout.children()) {
            walkLayoutInner(vf, child, arraySpecs, segmentSpecs, stats, colNames, currentPath);
        }
    }

    private static void scanFlatSegment(MemorySegment seg, List<String> arraySpecs,
            PcoStats stats, String columnPath) {
        int segLen = (int) seg.byteSize();
        ByteBuffer bb = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        int fbLen = bb.getInt(segLen - 4);
        int fbStart = segLen - 4 - fbLen;
        ByteBuffer fbBuf = bb.slice(fbStart, fbLen).order(ByteOrder.LITTLE_ENDIAN);
        Array fbArray = Array.getRootAsArray(fbBuf);

        // Materialize per-buffer offsets (need to read chunk_meta buffer for pco arrays).
        int numBuffers = fbArray.buffersLength();
        long[] bufOffsets = new long[numBuffers];
        long[] bufLengths = new long[numBuffers];
        long dataOffset = 0;
        for (int i = 0; i < numBuffers; i++) {
            Buffer bufDesc = fbArray.buffers(i);
            dataOffset += bufDesc.padding();
            bufOffsets[i] = dataOffset;
            bufLengths[i] = bufDesc.length();
            dataOffset += bufDesc.length();
        }

        visitNode(fbArray.root(), arraySpecs, seg, bufOffsets, bufLengths, stats, columnPath);
    }

    private static void visitNode(ArrayNode node, List<String> arraySpecs, MemorySegment seg,
            long[] bufOffsets, long[] bufLengths, PcoStats stats, String columnPath) {
        if (node == null) {
            return;
        }
        String encId = arraySpecs.get(node.encoding());
        if ("vortex.pco".equals(encId)) {
            recordPco(node, seg, bufOffsets, bufLengths, stats, columnPath);
        }
        for (int i = 0; i < node.childrenLength(); i++) {
            visitNode(node.children(i), arraySpecs, seg, bufOffsets, bufLengths, stats, columnPath);
        }
    }

    private static void recordPco(ArrayNode node, MemorySegment seg,
            long[] bufOffsets, long[] bufLengths,
            PcoStats stats, String columnPath) {
        stats.pcoArrayCount++;
        stats.columnPaths.add(columnPath);

        ByteBuffer meta = node.metadataAsByteBuffer();
        ByteBuffer metaSlice = meta != null ? meta.slice() : null;
        PcoMeta pm = metaSlice != null ? parsePcoMetadata(metaSlice) : new PcoMeta();
        stats.chunksPerArray.add(pm.chunkCount);
        stats.pagesPerArray.add(pm.pageCount);
        if (!pm.firstPageSizes.isEmpty() && stats.firstPageSizes.size() < 10) {
            stats.firstPageSizes.add(pm.firstPageSizes.get(0));
        }
        if (pm.headerBytes != null) {
            stats.headerVersions.merge(hex(pm.headerBytes), 1, Integer::sum);
        }

        // First chunk_meta buffer is bufferIndices[0] (Vortex layer puts chunk_metas first).
        if (node.buffersLength() > 0) {
            int bufIdx = node.buffers(0);
            if (bufIdx < bufOffsets.length && bufLengths[bufIdx] > 0) {
                int n = (int) Math.min(16, bufLengths[bufIdx]);
                byte[] first = new byte[n];
                MemorySegment slice = seg.asSlice(bufOffsets[bufIdx], n);
                for (int i = 0; i < n; i++) {
                    first[i] = slice.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i);
                }
                stats.firstChunkMetaHexes.add(columnPath + ": " + hex(first));
                int b0 = first[0] & 0xFF;
                int lowMode = b0 & 0x0F;
                int highDelta = (b0 >>> 4) & 0x0F;
                stats.firstChunkMetaNibbles.add(columnPath
                                                        + ": mode_nibble=0x" + Integer.toHexString(lowMode)
                                                        + " next_nibble=0x" + Integer.toHexString(highDelta));
            }
        }
    }

    /// Minimal protobuf wire-format parse for PcoMetadata.
    /// Schema:
    ///   message PcoMetadata { bytes header = 1; repeated PcoChunkInfo chunks = 2; }
    ///   message PcoChunkInfo { repeated PcoPageInfo pages = 1; }
    ///   message PcoPageInfo { uint32 n_values = 1; }
    private static PcoMeta parsePcoMetadata(ByteBuffer buf) {
        PcoMeta out = new PcoMeta();
        buf.order(ByteOrder.LITTLE_ENDIAN);
        while (buf.hasRemaining()) {
            int tag = (int) readVarint(buf);
            int fieldNum = tag >>> 3;
            int wireType = tag & 0x7;
            if (fieldNum == 1 && wireType == 2) {
                int len = (int) readVarint(buf);
                out.headerBytes = new byte[len];
                buf.get(out.headerBytes);
            } else if (fieldNum == 2 && wireType == 2) {
                int len = (int) readVarint(buf);
                ByteBuffer chunkBuf = buf.slice();
                chunkBuf.limit(len);
                buf.position(buf.position() + len);
                out.chunkCount++;
                parseChunkInfo(chunkBuf, out);
            } else {
                skipField(buf, wireType);
            }
        }
        return out;
    }

    private static void parseChunkInfo(ByteBuffer buf, PcoMeta out) {
        while (buf.hasRemaining()) {
            int tag = (int) readVarint(buf);
            int fieldNum = tag >>> 3;
            int wireType = tag & 0x7;
            if (fieldNum == 1 && wireType == 2) {
                int len = (int) readVarint(buf);
                ByteBuffer pageBuf = buf.slice();
                pageBuf.limit(len);
                buf.position(buf.position() + len);
                out.pageCount++;
                parsePageInfo(pageBuf, out);
            } else {
                skipField(buf, wireType);
            }
        }
    }

    private static void parsePageInfo(ByteBuffer buf, PcoMeta out) {
        while (buf.hasRemaining()) {
            int tag = (int) readVarint(buf);
            int fieldNum = tag >>> 3;
            int wireType = tag & 0x7;
            if (fieldNum == 1 && wireType == 0) {
                int nv = (int) readVarint(buf);
                out.firstPageSizes.add(nv);
            } else {
                skipField(buf, wireType);
            }
        }
    }

    private static void skipField(ByteBuffer buf, int wireType) {
        switch (wireType) {
            case 0 -> readVarint(buf);
            case 1 -> buf.position(buf.position() + 8);
            case 2 -> {
                int len = (int) readVarint(buf);
                buf.position(buf.position() + len);
            }
            case 5 -> buf.position(buf.position() + 4);
            default -> throw new IllegalStateException("unknown wire type " + wireType);
        }
    }

    private static long readVarint(ByteBuffer buf) {
        long result = 0;
        int shift = 0;
        while (true) {
            byte b = buf.get();
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02x", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String formatDType(DType d) {
        if (d instanceof DType.Struct s) {
            StringBuilder sb = new StringBuilder("struct{");
            for (int i = 0; i < s.fieldNames().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(s.fieldNames().get(i)).append(':').append(s.fieldTypes().get(i));
            }
            return sb.append('}').toString();
        }
        return String.valueOf(d);
    }

    private static Path downloadIfMissing(Path tmp, String name) throws Exception {
        // Reuse /tmp/pco-fixtures cache if present.
        Path cached = Path.of("/tmp/pco-fixtures", name);
        if (Files.exists(cached)) {
            return cached;
        }
        Path dest = tmp.resolve(name);
        try (var in = URI.create(BASE + name).toURL().openStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }

    private static void assumeNetworkAvailable() {
        try {
            URI.create("https://vortex-compat-fixtures.s3.amazonaws.com").toURL().openStream().close();
        } catch (Exception _) {
            assumeTrue(false, "no network");
        }
    }

    @Test
    void dumpPcoMetadataForAllBlockedFixtures(@TempDir Path tmp) throws Exception {
        // Given
        assumeNetworkAvailable();

        // When
        StringBuilder result = new StringBuilder();
        for (String name : FIXTURES) {
            Path local = downloadIfMissing(tmp, name);
            result.append("=== ").append(name).append(" ===\n");
            inspect(local, result);
            result.append('\n');
        }

        // Then
        System.out.println(result);
        assertThat(result.toString()).isNotEmpty();
    }

    private static final class PcoStats {
        final List<String> columnPaths = new ArrayList<>();
        final Map<String, Integer> ptypeCounts = new TreeMap<>();
        final Map<String, Integer> headerVersions = new HashMap<>();
        final List<Integer> chunksPerArray = new ArrayList<>();
        final List<Integer> pagesPerArray = new ArrayList<>();
        final List<Integer> firstPageSizes = new ArrayList<>();
        final List<String> firstChunkMetaHexes = new ArrayList<>();
        final List<String> firstChunkMetaNibbles = new ArrayList<>();
        int pcoArrayCount;
    }

    private static final class PcoMeta {
        final List<Integer> firstPageSizes = new ArrayList<>();
        byte[] headerBytes;
        int chunkCount;
        int pageCount;
    }
}
