package io.github.dfa1.vortex.io;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adversarial tests for the 8-byte file trailer and postscript length field.
 *
 * <p>The trailer is the entry point for every Vortex file read; any malformed input here
 * must surface as a {@link VortexException}, never as an unchecked JDK exception
 * ({@code IndexOutOfBoundsException}, {@code NegativeArraySizeException}, {@code OutOfMemoryError}),
 * a FlatBuffer runtime exception, or a Protobuf parser exception.
 *
 * <p>Trailer layout (LE):
 * <pre>
 * offset 0..1  version       u16
 * offset 2..3  postscriptLen u16
 * offset 4..7  magic         "VTXF"
 * </pre>
 */
class MalformedTrailerSecurityTest {

    private static final byte[] MAGIC = {'V', 'T', 'X', 'F'};
    private static final EncodingRegistry REGISTRY = EncodingRegistry.empty();

    @Test
    void fileSmallerThanTrailer_throwsVortexException(@TempDir Path tmp) throws Exception {
        // Given — 4 bytes; trailer needs 8
        Path file = tmp.resolve("tiny.vtx");
        Files.write(file, new byte[]{1, 2, 3, 4});

        // When / Then
        assertThatThrownBy(() -> VortexReader.open(file, REGISTRY))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("too small");
    }

    @Test
    void emptyFile_throwsVortexException(@TempDir Path tmp) throws Exception {
        // Given
        Path file = tmp.resolve("empty.vtx");
        Files.write(file, new byte[0]);

        // When / Then
        assertThatThrownBy(() -> VortexReader.open(file, REGISTRY))
                .isInstanceOf(VortexException.class);
    }

    @Test
    void wrongMagic_throwsVortexException(@TempDir Path tmp) throws Exception {
        // Given — 8 bytes with bogus magic
        Path file = tmp.resolve("bad_magic.vtx");
        Files.write(file, new byte[]{1, 0, 0, 0, 'X', 'X', 'X', 'X'});

        // When / Then
        assertThatThrownBy(() -> VortexReader.open(file, REGISTRY))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("magic");
    }

    @Test
    void truncatedMagic_throwsVortexException(@TempDir Path tmp) throws Exception {
        // Given — "VTX" + NUL (missing trailing F)
        Path file = tmp.resolve("trunc_magic.vtx");
        Files.write(file, new byte[]{1, 0, 0, 0, 'V', 'T', 'X', 0});

        // When / Then
        assertThatThrownBy(() -> VortexReader.open(file, REGISTRY))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("magic");
    }

    /**
     * postscriptLen larger than the file body would make postscriptOffset go negative,
     * which previously surfaced as {@code IndexOutOfBoundsException} from
     * {@code MemorySegment.asSlice(negative, ...)}.
     */
    @Test
    void postscriptLenPastFileStart_throwsVortexException(@TempDir Path tmp) throws Exception {
        // Given — 16-byte file, postscriptLen = 0xFFFF
        Path file = tmp.resolve("ps_overflow.vtx");
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(new byte[8]);                     // file body stand-in
        buf.putShort((short) 1);                  // version
        buf.putShort((short) 0xFFFF);             // postscriptLen — far past file body
        buf.put(MAGIC);
        Files.write(file, buf.array());

        // When / Then
        assertThatThrownBy(() -> VortexReader.open(file, REGISTRY))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("postscript");
    }

    /**
     * postscriptLen == 0 leaves no bytes to FlatBuffer-parse; without validation, the empty
     * buffer would either throw {@code ArrayIndexOutOfBoundsException} from the FlatBuffer
     * runtime or silently parse against a missing root table.
     */
    @Test
    void postscriptLenZero_throwsVortexException(@TempDir Path tmp) throws Exception {
        // Given — minimal trailer with postscriptLen=0
        Path file = tmp.resolve("ps_zero.vtx");
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 1);                  // version
        buf.putShort((short) 0);                  // postscriptLen = 0
        buf.put(MAGIC);
        Files.write(file, buf.array());

        // When / Then
        assertThatThrownBy(() -> VortexReader.open(file, REGISTRY))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("postscript");
    }

    /**
     * Unknown version must surface as a {@link VortexException} rather than silently
     * parsing against an unknown layout.
     */
    @Test
    void unknownVersion_throwsVortexException(@TempDir Path tmp) throws Exception {
        // Given — minimal trailer with version = 0xBEEF
        Path file = tmp.resolve("bad_version.vtx");
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0xBEEF);             // version — not 1
        buf.putShort((short) 0);                  // postscriptLen
        buf.put(MAGIC);
        Files.write(file, buf.array());

        // When / Then
        assertThatThrownBy(() -> VortexReader.open(file, REGISTRY))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("version");
    }

    /**
     * Garbage where the postscript FlatBuffer is expected must surface as a
     * {@link VortexException}, not a raw FlatBuffer runtime exception.
     */
    @Test
    void garbagePostscriptBytes_throwsVortexException(@TempDir Path tmp) throws Exception {
        // Given — 8 random bytes + trailer claiming postscriptLen=8
        Path file = tmp.resolve("ps_garbage.vtx");
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF,
                                 (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            ByteBuffer t = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            t.putShort((short) 1);
            t.putShort((short) 8);
            t.put(MAGIC);
            out.write(t.array());
        }

        // When / Then
        assertThatThrownBy(() -> VortexReader.open(file, REGISTRY))
                .isInstanceOf(VortexException.class);
    }
}
