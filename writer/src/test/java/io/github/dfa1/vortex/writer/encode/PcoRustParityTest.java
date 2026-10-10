package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.WriteRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;

/// Byte-for-byte parity with Rust: the fixtures under `pco/` are pco 1.0.1's output with Vortex's
/// settings (level 8, 2^18-value chunks, `EqualPagesUpTo(8192)`) for 9000 values regenerated here
/// with the same xorshift64, written as all chunk metas then all pages (Vortex's buffer order).
///
/// Encoder speed-ups must keep these identical: every choice Rust makes (IntMult base, delta,
/// histogram bins, bin optimization, weight quantization, paging) shows up in the bytes. The three
/// shapes take the three encoder paths: Classic without delta, Classic with consecutive delta, and
/// IntMult (base 100).
class PcoRustParityTest {

    private final PcoEncodingEncoder sut = new PcoEncodingEncoder();

    @ParameterizedTest
    @CsvSource({
        // shape,   mode (low nibble of the chunk meta), delta variant (high nibble, Classic only)
        "random,    0, 0",
        "timestamp, 0, 1",
        "cents,     1, 0",
    })
    void encodesTheBytesRustPcoWrites(String shape, int mode, int deltaVariant) throws IOException {
        // Given
        long[] values = shape(shape, 9000);
        byte[] expected = fixture(shape + "-i64.pco");

        // When
        byte[] result;
        try (Arena arena = Arena.ofConfined()) {
            EncodeResult encoded = sut.encode(new DType.Primitive(PType.I64, false), values,
                EncodeContext.of(arena, WriteRegistry.loadAll()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (MemorySegment buffer : encoded.buffers()) {
                out.write(buffer.toArray(ValueLayout.JAVA_BYTE));
            }
            result = out.toByteArray();
        }

        // Then — the fixture takes the path this case is meant to cover, and Java matches it
        assertThat(expected[0] & 0x0F).isEqualTo(mode);
        if (mode == 0) {
            assertThat((expected[0] & 0xF0) >>> 4).isEqualTo(deltaVariant);
        }
        assertThat(result).containsExactly(expected);
    }

    // Same generator as the Rust program that wrote the fixtures.
    private static long[] shape(String name, int n) {
        long x = 0x9E37_79B9_7F4A_7C15L;
        long t = 1_700_000_000_000L;
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            out[i] = switch (name) {
                case "random" -> Long.remainderUnsigned(x, 1_000_000_000L);
                case "timestamp" -> t += Long.remainderUnsigned(x, 1_000L);
                case "cents" -> Long.remainderUnsigned(x, 100_000L) * 100
                    + (Long.remainderUnsigned(x >>> 20, 10L) == 0 ? 99 : 0);
                default -> throw new IllegalArgumentException(name);
            };
        }
        return out;
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = PcoRustParityTest.class.getResourceAsStream("/pco/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return in.readAllBytes();
        }
    }
}
