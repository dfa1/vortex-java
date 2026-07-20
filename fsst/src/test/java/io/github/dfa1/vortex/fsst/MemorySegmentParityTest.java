package io.github.dfa1.vortex.fsst;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the `MemorySegment`-native `compress`/`decompress` hot paths against their `byte[]`
/// counterparts and covers the paper's unconditional-8-byte-store decode trick.
class MemorySegmentParityTest {

    @Nested
    class Parity {

        @Test
        void compressAndDecompress_emptyInput_matchByteArrayPath() {
            // Given — nothing to compress; both paths must agree on the zero-length result.
            Compressor sut = new CompressorBuilder().train(logLikeRows(500));

            // When / Then
            assertRoundTripParity(sut, new byte[0]);
        }

        @Test
        void compressAndDecompress_singleByte_matchByteArrayPath() {
            // Given — a lone byte, the smallest non-trivial input.
            Compressor sut = new CompressorBuilder().train(logLikeRows(500));

            // When / Then
            assertRoundTripParity(sut, new byte[]{'x'});
        }

        @Test
        void compressAndDecompress_repetitiveText_matchByteArrayPath() {
            // Given — highly repetitive text that trains multi-byte symbols, so most positions take
            // the match branch rather than the escape branch on both paths.
            Compressor sut = new CompressorBuilder().train(logLikeRows(2_000));

            // When / Then
            assertRoundTripParity(sut, logLikeRows(2_000)[7]);
        }

        @Test
        void compressAndDecompress_incompressibleData_matchByteArrayPath() {
            // Given — pseudo-random bytes trained on unrelated text, so almost every position escapes;
            // this exercises the escape branch of both compress and decompress on both paths.
            Compressor sut = new CompressorBuilder().train(logLikeRows(500));
            byte[] noise = new byte[4_096];
            new Random(99).nextBytes(noise);

            // When / Then
            assertRoundTripParity(sut, noise);
        }

        @Test
        void compressAndDecompress_boundaryOverrunScenario_matchByteArrayPath() {
            // Given — PR 3's regression case: a length-8 symbol "AB\0\0\0\0\0\0" and the 3-byte input
            // "AB\0", where a dropped end guard would spuriously match the padded symbol. Both paths
            // must produce identical output, proving the MemorySegment overload carries the guard.
            long packed = ('A' & 0xFFL) | (('B' & 0xFFL) << 8);
            Compressor sut = Compressor.of(List.of(new Symbol(packed, 8)));

            // When / Then
            assertRoundTripParity(sut, new byte[]{'A', 'B', 0});
        }
    }

    @Nested
    class UnconditionalStoreTrick {

        @Test
        void decompress_multipleRowsIntoSharedSegment_noCrossRowCorruption() {
            // Given — three consecutive rows decoded into one shared output segment. The 8-byte-store
            // trick over-writes up to 7 slack bytes past each symbol; if a row's slack bled into the
            // next row's real data it would corrupt it, so we verify each row's region independently.
            Symbol ab = Symbol.of("ab".getBytes(StandardCharsets.UTF_8), 0, 2);
            Symbol wxyz = Symbol.of("wxyz".getBytes(StandardCharsets.UTF_8), 0, 4);
            Decompressor sut = Decompressor.of(new long[]{ab.packedBytes(), wxyz.packedBytes()}, new int[]{2, 4});

            byte[] row0 = {0x00};                              // "ab"
            byte[] row1 = {(byte) Decompressor.ESCAPE, 'Q'};   // "Q"
            byte[] row2 = {0x01, 0x00};                        // "wxyzab"
            byte[] expected0 = "ab".getBytes(StandardCharsets.UTF_8);
            byte[] expected1 = "Q".getBytes(StandardCharsets.UTF_8);
            byte[] expected2 = "wxyzab".getBytes(StandardCharsets.UTF_8);
            long total = expected0.length + expected1.length + expected2.length;

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment c0 = segmentOf(arena, row0);
                MemorySegment c1 = segmentOf(arena, row1);
                MemorySegment c2 = segmentOf(arena, row2);
                // +7 slack past the total decoded length is the documented precondition of the trick.
                MemorySegment out = arena.allocate(total + 7);

                // When — decode all three rows sequentially into the shared segment.
                long after0 = sut.decompress(c0, 0, c0.byteSize(), out, 0);
                long after1 = sut.decompress(c1, 0, c1.byteSize(), out, after0);
                long after2 = sut.decompress(c2, 0, c2.byteSize(), out, after1);

                // Then — each row's region is exactly correct; no row bled into the next.
                assertThat(after0).isEqualTo(expected0.length);
                assertThat(after1).isEqualTo((long) expected0.length + expected1.length);
                assertThat(after2).isEqualTo(total);
                assertThat(bytesOf(out, 0, expected0.length)).isEqualTo(expected0);
                assertThat(bytesOf(out, after0, expected1.length)).isEqualTo(expected1);
                assertThat(bytesOf(out, after1, expected2.length)).isEqualTo(expected2);
            }
        }
    }

    @Nested
    class EndOfInput {

        @Test
        void compress_symbolWithTrailingZeros_doesNotMatchPastEndOfInput_memorySegment() {
            // Given — the exact PR 3 regression ported to the MemorySegment overload: a length-8
            // symbol "AB\0\0\0\0\0\0" and the 3-byte input "AB\0". The MemorySegment compress must
            // carry the pos+length<=end guard and escape rather than emit an over-long match that
            // would decode to 8 bytes and corrupt the tail of this NUL-tailed row.
            long packed = ('A' & 0xFFL) | (('B' & 0xFFL) << 8);
            Compressor sut = Compressor.of(List.of(new Symbol(packed, 8)));
            byte[] input = {'A', 'B', 0};

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment in = segmentOf(arena, input);
                MemorySegment compressed = arena.allocate(input.length * 2L);
                MemorySegment decompressed = arena.allocate(input.length + 7L);

                // When — compress then decompress the shorter input.
                long compressedLength = sut.compress(in, 0, in.byteSize(), compressed, 0);
                long decompressedLength =
                        sut.toDecompressor().decompress(compressed, 0, compressedLength, decompressed, 0);

                // Then — the round-trip preserves the exact original length and bytes.
                assertThat(decompressedLength).isEqualTo(input.length);
                assertThat(bytesOf(decompressed, 0, input.length)).isEqualTo(input);
            }
        }
    }

    private static void assertRoundTripParity(Compressor sut, byte[] input) {
        // byte[] path.
        byte[] heapCompressed = new byte[input.length * 2];
        long heapCompressedLength = sut.compress(input, 0, input.length, heapCompressed, 0);
        byte[] heapDecompressed = new byte[input.length + 7];
        long heapDecompressedLength =
                sut.toDecompressor().decompress(heapCompressed, 0, (int) heapCompressedLength, heapDecompressed, 0);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = segmentOf(arena, input);
            MemorySegment segCompressed = arena.allocate(Math.max(1, input.length * 2L));
            long segCompressedLength = sut.compress(in, 0, in.byteSize(), segCompressed, 0);

            MemorySegment segDecompressed = arena.allocate(input.length + 7L);
            long segDecompressedLength =
                    sut.toDecompressor().decompress(segCompressed, 0, segCompressedLength, segDecompressed, 0);

            // Then — both paths compress to the identical code stream and decompress to the identical
            // bytes, so the two implementations can never silently diverge.
            assertThat(segCompressedLength).isEqualTo(heapCompressedLength);
            assertThat(bytesOf(segCompressed, 0, segCompressedLength))
                    .isEqualTo(java.util.Arrays.copyOf(heapCompressed, (int) heapCompressedLength));
            assertThat(segDecompressedLength).isEqualTo(heapDecompressedLength);
            assertThat(bytesOf(segDecompressed, 0, segDecompressedLength))
                    .isEqualTo(java.util.Arrays.copyOf(heapDecompressed, (int) heapDecompressedLength));
            // And the decoded bytes equal the original input.
            assertThat(bytesOf(segDecompressed, 0, input.length)).isEqualTo(input);
        }
    }

    private static MemorySegment segmentOf(Arena arena, byte[] data) {
        MemorySegment segment = arena.allocate(Math.max(1, data.length));
        MemorySegment.copy(data, 0, segment, ValueLayout.JAVA_BYTE, 0, data.length);
        return segment.asSlice(0, data.length);
    }

    private static byte[] bytesOf(MemorySegment segment, long offset, long length) {
        byte[] result = new byte[(int) length];
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, result, 0, (int) length);
        return result;
    }

    private static byte[][] logLikeRows(int count) {
        byte[][] rows = new byte[count][];
        String prefix = "2026-07-20T12:34:56Z ERROR request failed host=example.com id=";
        for (int i = 0; i < count; i++) {
            rows[i] = (prefix + i).getBytes(StandardCharsets.UTF_8);
        }
        return rows;
    }
}
