package io.github.dfa1.vortex.reader;

import static io.github.dfa1.vortex.reader.MalformedFiles.buildFooter;
import static io.github.dfa1.vortex.reader.MalformedFiles.buildI64Dtype;
import static io.github.dfa1.vortex.reader.MalformedFiles.buildFlatLayout;
import static io.github.dfa1.vortex.reader.MalformedFiles.buildPostscript;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.VortexFormat;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adversarial tests for `footer.segmentSpecs[i]` bounds.
 *
 * <p>Every [SegmentSpec] declared in the footer is later sliced from the memory-mapped
 * file via `fileSegment.asSlice(spec.offset(), spec.length())` at scan time. Without
 * up-front validation, malformed offsets/lengths surface as `IndexOutOfBoundsException`
 * from the FFM layer rather than [VortexException], breaking the reader contract.
 *
 * <p>[PostscriptParser#validateSegmentSpecs] now rejects negative offsets, negative
 * lengths, offsets past end-of-file, and `offset + length` overflowing the file size at
 * the moment the postscript is parsed — before any scan iterator is ever created.
 */
class MalformedFooterSecurityTest {

    private static final ReadRegistry REGISTRY = ReadRegistry.empty();

    static Stream<Arguments> outOfBoundsSpecs() {
        // SegmentSpec FlatBuffer schema: offset is u64, length is u32. Negative or
        // huge length values get truncated by the FlatBuffer encoder, so the cases
        // below choose values that survive the wire round-trip and still violate
        // the bounds check: offset with sign bit set (reads back as negative long),
        // offset past EOF, and a length that exceeds the file body (u32 max).
        return Stream.of(
                // (segOffset, segLength, expected substring)
                Arguments.of(-1L, 8L, "offset=-1"),                          // signed-long negative offset
                Arguments.of(999_999_999L, 8L, "offset=999999999"),          // offset past EOF
                Arguments.of(0L, 4_294_967_295L, "length=4294967295")        // length > fileSize (u32 max)
        );
    }

    @ParameterizedTest
    @MethodSource("outOfBoundsSpecs")
    void footerSegmentSpec_outOfBounds_throwsVortexException(
            long segOffset, long segLength, String expected,
            @TempDir Path tmp) throws Exception {
        // Given — well-formed trailer/postscript/footer FlatBuffer, but segmentSpecs[0] points outside file
        Path file = buildFileWithBadSegmentSpec(tmp, segOffset, segLength);

        // When / Then
        assertThatThrownBy(() -> VortexReader.open(file, REGISTRY))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("segmentSpecs[0]")
                .hasMessageContaining(expected);
    }

    // ── File builders ─────────────────────────────────────────────────────────

    /**
     * Crafts a minimal .vtx file with a single I64 flat-layout column and a footer whose
     * `segmentSpecs[0]` contains the supplied (deliberately bad) offset/length.
     *
     * <p>The file body is 8 bytes of placeholder data: the bad spec means
     * `VortexReader.open` should never look at them.
     */
    private static Path buildFileWithBadSegmentSpec(Path dir, long segOffset, long segLength) throws Exception {
        byte[] body = new byte[8]; // unused; placeholder data segment

        ByteBuffer footerBuf = buildFooter(
                new String[]{"vortex.primitive"},
                new String[]{"vortex.flat"},
                new long[]{segOffset},
                new long[]{segLength});
        ByteBuffer dtypeBuf = buildI64Dtype();
        ByteBuffer layoutBuf = buildFlatLayout(0, 1L, 0);

        return writeVtxFile(dir, "bad_seg.vtx", body, footerBuf, dtypeBuf, layoutBuf);
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
