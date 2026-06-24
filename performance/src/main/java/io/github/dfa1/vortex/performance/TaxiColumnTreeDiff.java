package io.github.dfa1.vortex.performance;

import static io.github.dfa1.vortex.core.io.PTypeIO.LE_INT;

import dev.vortex.api.Session;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.fbs.FbsArray;
import io.github.dfa1.vortex.core.fbs.FbsArrayNode;
import io.github.dfa1.vortex.core.fbs.FbsBuffer;
import io.github.dfa1.vortex.parquet.ParquetImporter;
import io.github.dfa1.vortex.core.proto.ProtoALPMetadata;
import io.github.dfa1.vortex.core.proto.ProtoBitPackedMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.reader.Footer;
import io.github.dfa1.vortex.reader.Layout;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.reader.VortexReader;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Per-column encoding-tree diff: prints the full FbsArrayNode chain (encoding id +
/// decoded metadata + buffer byte counts) for a single column in the Rust JNI
/// file and the Java file side-by-side. Use to investigate why a column is
/// larger in Java by comparing the encoding choices chunk-by-chunk.
///
/// Run: java -cp performance/target/benchmarks.jar \
///        --enable-native-access=ALL-UNNAMED \
///        --add-opens java.base/java.nio=ALL-UNNAMED \
///        io.github.dfa1.vortex.performance.TaxiColumnTreeDiff [column-name]
///
/// Defaults to `trip_distance` when no argument is given.
public final class TaxiColumnTreeDiff {

    private static final Path PARQUET =
            Path.of(System.getProperty("java.io.tmpdir"), "yellow_tripdata_2024-01.parquet");
    private static final Session SESSION = Session.create();

    static {
        NativeLoader.loadJni();
    }

    private TaxiColumnTreeDiff() {
    }

    public static void main(String[] args) throws Exception {
        String column = args.length > 0 ? args[0] : "trip_distance";
        if (!Files.exists(PARQUET)) {
            System.err.println("Missing: " + PARQUET);
            System.exit(1);
        }
        Path jniVortex = Files.createTempFile("taxi-jni", ".vortex");
        Path javaVortex = Files.createTempFile("taxi-java", ".vortex");
        try {
            var m = TaxiLayoutInspector.class.getDeclaredMethod("writeJni", Path.class, Path.class);
            m.setAccessible(true);
            m.invoke(null, PARQUET, jniVortex);
            ParquetImporter.importParquet(PARQUET, javaVortex);

            System.out.println("════════════════════════════════════════════════════════════════════");
            System.out.println("  JNI / Rust writer — column: " + column);
            System.out.println("════════════════════════════════════════════════════════════════════");
            dump(jniVortex, column);

            System.out.println();
            System.out.println("════════════════════════════════════════════════════════════════════");
            System.out.println("  Java writer — column: " + column);
            System.out.println("════════════════════════════════════════════════════════════════════");
            dump(javaVortex, column);
        } finally {
            Files.deleteIfExists(jniVortex);
            Files.deleteIfExists(javaVortex);
        }
    }

    private static void dump(Path file, String column) throws IOException {
        try (VortexReader r = VortexReader.open(file, ReadRegistry.loadAll())) {
            DType dtype = r.dtype();
            if (!(dtype instanceof DType.Struct s)) {
                throw new IllegalStateException("expected struct root, got: " + dtype);
            }
            int colIdx = s.fieldNames().indexOf(column);
            if (colIdx < 0) {
                throw new IllegalArgumentException("missing column: " + column);
            }
            Layout struct = unwrapToStruct(r.layout());
            Layout colLayout = struct.children().get(colIdx);
            Footer footer = r.footer();
            long colBytes = sumLeafSegments(colLayout, footer.segmentSpecs());
            System.out.printf("column=%s  totalBytes=%,d (%.2f MB)%n",
                    column, colBytes, colBytes / 1_048_576.0);
            walkLayout(r, colLayout, footer, "  ");
        }
    }

    private static void walkLayout(VortexReader reader, Layout layout, Footer footer, String indent) {
        String header = indent + layout.encodingId() + " rows=" + layout.rowCount();
        if (layout.isFlat() && !layout.segments().isEmpty()) {
            int segIdx = layout.segments().getFirst();
            SegmentSpec spec = footer.segmentSpecs().get(segIdx);
            header += " seg=" + segIdx + " segBytes=" + String.format("%,d", spec.length());
            System.out.println(header);
            if (spec.compression().code != 0) {
                System.out.println(indent + "  [segment is compressed: " + spec.compression().name() + "]");
                return;
            }
            MemorySegment seg = reader.rawSegment(spec);
            dumpFlatRoot(seg, footer.arraySpecs(), indent + "  ");
        } else {
            System.out.println(header);
        }
        for (Layout child : layout.children()) {
            walkLayout(reader, child, footer, indent + "  ");
        }
    }

    private static void dumpFlatRoot(MemorySegment seg, List<String> arraySpecs, String indent) {
        int segLen = (int) seg.byteSize();
        int fbLen = seg.get(LE_INT, segLen - 4);
        int fbStart = segLen - 4 - fbLen;
        FbsArray arr = FbsArray.getRootAsFbsArray(seg.asSlice(fbStart, fbLen));
        int bufCount = arr.buffersLength();
        long[] bufBytes = new long[bufCount];
        for (int i = 0; i < bufCount; i++) {
            FbsBuffer b = arr.buffers(i);
            bufBytes[i] = b.length();
        }
        long dataBytes = (long) segLen - 4L - fbLen;
        System.out.printf("%sdata=%,dB  fb=%,dB  buffers=%d%n", indent, dataBytes, fbLen, bufCount);
        for (int i = 0; i < bufCount; i++) {
            System.out.printf("%s  buf[%d]=%,d B%n", indent, i, bufBytes[i]);
        }
        dumpNode(arr.root(), arraySpecs, bufBytes, indent + "  ");
    }

    private static void dumpNode(FbsArrayNode node, List<String> arraySpecs, long[] bufBytes, String indent) {
        if (node == null) {
            return;
        }
        String id = arraySpecs.get(node.encoding());
        int[] bufs = new int[node.buffersLength()];
        long nodeBytes = 0;
        for (int i = 0; i < bufs.length; i++) {
            bufs[i] = node.buffers(i);
            nodeBytes += bufBytes[bufs[i]];
        }
        StringBuilder line = new StringBuilder();
        line.append(indent).append(id);
        if (bufs.length > 0) {
            line.append("  buf=").append(java.util.Arrays.toString(bufs));
            line.append("  bytes=").append(String.format("%,d", nodeBytes));
        }
        java.lang.foreign.MemorySegment metaSeg = node.metadataAsSegment();
        ByteBuffer meta = metaSeg != null ? metaSeg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN) : null;
        if (meta != null && meta.remaining() > 0) {
            line.append("  ").append(decodeMeta(id, meta));
        }
        System.out.println(line);
        for (int i = 0; i < node.childrenLength(); i++) {
            dumpNode(node.children(i), arraySpecs, bufBytes, indent + "  ");
        }
    }

    private static String decodeMeta(String id, ByteBuffer meta) {
        try {
            MemorySegment seg = MemorySegment.ofBuffer(meta.duplicate());
            if (EncodingId.FASTLANES_BITPACKED.id().equals(id)) {
                ProtoBitPackedMetadata m = ProtoBitPackedMetadata.decode(seg, 0, seg.byteSize());
                return "bitWidth=" + m.bit_width() + " offset=" + m.offset()
                        + " patches=" + describePatches(m.patches());
            }
            if (EncodingId.VORTEX_ALP.id().equals(id)) {
                ProtoALPMetadata m = ProtoALPMetadata.decode(seg, 0, seg.byteSize());
                return "expE=" + m.exp_e() + " expF=" + m.exp_f()
                        + " patches=" + describePatches(m.patches());
            }
            return "metaBytes=" + meta.remaining();
        } catch (Exception e) {
            return "meta-decode-error: " + e.getMessage();
        }
    }

    private static String describePatches(ProtoPatchesMetadata p) {
        if (p == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(p.len()).append("(").append(p.indices_ptype()).append(")");
        if (p.chunk_offsets_len() != null) {
            sb.append(" chunkedOffsets=").append(p.chunk_offsets_len());
        }
        return sb.toString();
    }

    private static Layout unwrapToStruct(Layout layout) {
        while (!layout.isStruct()) {
            if (layout.children().isEmpty()) {
                throw new IllegalStateException("hit leaf before struct: " + layout.encodingId());
            }
            layout = layout.children().get(0);
        }
        return layout;
    }

    private static long sumLeafSegments(Layout layout, List<SegmentSpec> segs) {
        long total = 0;
        for (Integer idx : layout.segments()) {
            total += segs.get(idx).length();
        }
        for (Layout child : layout.children()) {
            total += sumLeafSegments(child, segs);
        }
        return total;
    }
}
