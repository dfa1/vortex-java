package io.github.dfa1.vortex.reader;

import com.google.flatbuffers.FlatBufferBuilder;
import static io.github.dfa1.vortex.reader.MalformedFiles.buildFooter;
import static io.github.dfa1.vortex.reader.MalformedFiles.buildI64Dtype;
import static io.github.dfa1.vortex.reader.MalformedFiles.buildPostscript;
import static io.github.dfa1.vortex.reader.MalformedFiles.slice;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.VortexFormat;

import io.github.dfa1.vortex.fbs.Layout;
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
 * [PostscriptParser]'s `convertLayout`.
 *
 * <p>The reader walks the layout tree recursively when materialising a file's
 * `Layout` object. Without a depth cap a crafted file with thousands of
 * nested children produces a [StackOverflowError] during `VortexReader.open`,
 * breaking the contract that every malformed input must surface as a [VortexException].
 *
 * <p>This test pins the contract: deeply nested layouts must be rejected as
 * [VortexException], never a [StackOverflowError].
 */
class LayoutDepthBombSecurityTest {

    private static final ReadRegistry REGISTRY = ReadRegistry.empty();

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
     * Builds a .vtx file whose root Layout has `depth` levels of single-child nesting,
     * each level reusing the same `vortex.flat` layout spec.
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
        trailer.put(VortexFormat.MAGIC.asByteBuffer());
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
