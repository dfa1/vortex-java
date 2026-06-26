package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.reader.MalformedFiles.NestKind;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.dfa1.vortex.reader.MalformedFiles.buildDeeplyNestedDtype;
import static io.github.dfa1.vortex.reader.MalformedFiles.buildFlatLayout;
import static io.github.dfa1.vortex.reader.MalformedFiles.buildFooter;
import static io.github.dfa1.vortex.reader.MalformedFiles.buildPostscript;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Adversarial test for the DType-tree recursion in [PostscriptParser]'s `convertDType`.
///
/// `convertDType` walks Struct fields, List/FixedSizeList element types, and Extension storage
/// types recursively. Without the [PostscriptParser#MAX_DTYPE_DEPTH] cap a crafted file with
/// thousands of nested `List` types produces a [StackOverflowError] during `VortexReader.open` —
/// an `Error` that escapes the [VortexException] sanitization and leaks the reader's memory-mapped
/// Arena (`open`'s `catch (Exception)` never runs `arena.close()`). This pins the contract: deeply
/// nested dtypes must be rejected as [VortexException], never a [StackOverflowError].
class DTypeDepthBombSecurityTest {

    private static final ReadRegistry REGISTRY = ReadRegistry.empty();

    @ParameterizedTest(name = "nested via {0}")
    @EnumSource(NestKind.class)
    void deeplyNestedDtype_throwsVortexException(NestKind kind, @TempDir Path tmp) throws Exception {
        // Given — a file whose DType nests 65536 levels of `kind`. Real schemas nest a handful of
        // levels; 65536 reliably blows the JVM stack on the recursive convertDType walk. Each kind
        // drives a different recursion arm (List/Struct/FixedSizeList/Extension), so all four must
        // increment the depth counter for the MAX_DTYPE_DEPTH guard to bound them.
        Path file = buildDeeplyNestedDtypeFile(tmp, 65536, kind);

        // When / Then — must surface as VortexException, not StackOverflowError
        assertThatThrownBy(() -> VortexReader.open(file, REGISTRY))
                .isInstanceOf(VortexException.class);
    }

    private static Path buildDeeplyNestedDtypeFile(Path dir, int depth, NestKind kind) throws Exception {
        byte[] body = new byte[8]; // unused placeholder
        ByteBuffer footerBuf = buildFooter(
                new String[]{"vortex.primitive"},
                new String[]{"vortex.flat"},
                new long[]{0L},
                new long[]{(long) body.length});
        ByteBuffer dtypeBuf = buildDeeplyNestedDtype(depth, kind);
        ByteBuffer layoutBuf = buildFlatLayout(0, 1L, 0);

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

        Path file = dir.resolve("deep_dtype.vtx");
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
