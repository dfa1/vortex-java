package io.github.dfa1.vortex.io;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.fbs.ArraySpec;
import io.github.dfa1.vortex.fbs.Footer;
import io.github.dfa1.vortex.fbs.Layout;
import io.github.dfa1.vortex.fbs.LayoutSpec;
import io.github.dfa1.vortex.fbs.Postscript;
import io.github.dfa1.vortex.fbs.PostscriptSegment;
import io.github.dfa1.vortex.fbs.Primitive;
import io.github.dfa1.vortex.fbs.SegmentSpec;
import io.github.dfa1.vortex.fbs.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adversarial tests for the layout-tree recursion in
 * {@link PostscriptParser}'s {@code convertLayout}.
 *
 * <p>The reader walks the layout tree recursively when materialising a file's
 * {@code Layout} object. Without a depth cap a crafted file with thousands of
 * nested children produces a {@link StackOverflowError} during {@code VortexReader.open},
 * breaking the contract that every malformed input must surface as a {@link VortexException}.
 *
 * <p>This test pins the contract: deeply nested layouts must be rejected as
 * {@link VortexException}, never a {@link StackOverflowError}.
 */
class LayoutDepthBombSecurityTest {

    private static final byte[] MAGIC = {'V', 'T', 'X', 'F'};
    private static final EncodingRegistry REGISTRY = EncodingRegistry.empty();

    @Test
    void deeplyNestedLayout_throwsVortexException(@TempDir Path tmp) throws Exception {
        // Given — file whose layout root has 65536 levels of nested children.
        // Typical real-world layouts are ~4 levels (Struct → Zoned → Chunked → Flat); 65536 is well past
        // anything sane and reliably blows the JVM stack on the recursive convertLayout walk.
        Path file = buildDeeplyNestedFile(tmp, 65536);

        // When / Then — must surface as VortexException, not StackOverflowError
        assertThatThrownBy(() -> VortexReader.open(file, REGISTRY))
                .isInstanceOf(VortexException.class);
    }

    // ── File builders ─────────────────────────────────────────────────────────

    /**
     * Builds a .vtx file whose root Layout has {@code depth} levels of single-child nesting,
     * each level reusing the same {@code vortex.flat} layout spec.
     */
    private static Path buildDeeplyNestedFile(Path dir, int depth) throws Exception {
        byte[] body = new byte[8]; // unused placeholder
        ByteBuffer footerBuf = buildFooter(
                new String[]{"vortex.primitive"},
                new String[]{"vortex.flat"},
                new long[]{0L},
                new long[]{(long) body.length});
        ByteBuffer dtypeBuf = buildI64Dtype();
        ByteBuffer layoutBuf = buildNestedLayout(depth);

        return writeVtxFile(dir, "deep_nest.vtx", body, footerBuf, dtypeBuf, layoutBuf);
    }

    private static ByteBuffer buildNestedLayout(int depth) {
        var fbb = new FlatBufferBuilder(depth * 32);
        int segV = Layout.createSegmentsVector(fbb, new long[]{0L});
        // Build leaf first; FlatBuffer requires children be finished before parents.
        int current = Layout.createLayout(fbb, 0, 1L, 0, 0, segV);
        for (int i = 0; i < depth; i++) {
            int childV = Layout.createChildrenVector(fbb, new int[]{current});
            current = Layout.createLayout(fbb, 0, 1L, 0, childV, 0);
        }
        Layout.finishLayoutBuffer(fbb, current);
        return slice(fbb);
    }

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

    private static ByteBuffer buildPostscript(
            long footerOff, int footerLen,
            long dtypeOff, int dtypeLen,
            long layoutOff, int layoutLen) {
        var fbb = new FlatBufferBuilder(128);
        int footSeg = PostscriptSegment.createPostscriptSegment(fbb, footerOff, footerLen, 0, 0, 0);
        int dtypeSeg = PostscriptSegment.createPostscriptSegment(fbb, dtypeOff, dtypeLen, 0, 0, 0);
        int layoutSeg = PostscriptSegment.createPostscriptSegment(fbb, layoutOff, layoutLen, 0, 0, 0);
        int psOff = Postscript.createPostscript(fbb, dtypeSeg, layoutSeg, 0, footSeg);
        Postscript.finishPostscriptBuffer(fbb, psOff);
        return slice(fbb);
    }

    private static ByteBuffer slice(FlatBufferBuilder fbb) {
        ByteBuffer data = fbb.dataBuffer();
        return data.slice(data.position(), data.remaining());
    }

    private static Path writeVtxFile(
            Path dir, String name, byte[] body,
            ByteBuffer footerBuf, ByteBuffer dtypeBuf, ByteBuffer layoutBuf) throws Exception {
        long footerOff = body.length;
        long dtypeOff = footerOff + footerBuf.remaining();
        long layoutOff = dtypeOff + dtypeBuf.remaining();

        ByteBuffer psBuf = buildPostscript(
                footerOff, footerBuf.remaining(),
                dtypeOff, dtypeBuf.remaining(),
                layoutOff, layoutBuf.remaining());

        int psLen = psBuf.remaining();
        ByteBuffer trailer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        trailer.putShort((short) 1);
        trailer.putShort((short) psLen);
        trailer.put(MAGIC);
        trailer.flip();

        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(body);
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
