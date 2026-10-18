package io.github.dfa1.vortex.fuzz;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.github.dfa1.vortex.fsst.Compressor;
import io.github.dfa1.vortex.fsst.CompressorBuilder;
import io.github.dfa1.vortex.fsst.Decompressor;
import org.junit.jupiter.api.Tag;

/// FSST compressor correctness (#493): a table trained on fuzzed rows must compress every row,
/// fuzzed or not, to codes that decompress back to it, through both the `byte[]` and the
/// `MemorySegment` paths, which must emit the same codes. Not crash fuzzing of [Decompressor]:
/// it documents itself as an internal boundary, not an untrusted-input one; the reader's FSST
/// validation of untrusted files is covered by [VortexReaderFuzzTest].
@Tag("fuzz")
class FsstFuzzTest {

    @FuzzTest
    void compressDecompressRoundTrip(FuzzedDataProvider data) {
        // Given
        byte[][] rows = new byte[data.consumeInt(0, 64)][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = data.consumeBytes(data.consumeInt(0, 128));
        }
        Compressor compressor = new CompressorBuilder().seed(data.consumeLong()).train(rows);
        Decompressor decompressor = compressor.toDecompressor();
        // A row the table was not trained on: all escapes is still a valid encoding.
        byte[] unseen = data.consumeRemainingAsBytes();

        // When / Then — every row round-trips (asserted per row)
        for (byte[] row : rows) {
            roundTrip(compressor, decompressor, row);
        }
        roundTrip(compressor, decompressor, unseen);
    }

    private static void roundTrip(Compressor compressor, Decompressor decompressor, byte[] row) {
        // Worst case: one escape pair per input byte; a code expands to at most 8 bytes.
        byte[] codes = new byte[2 * row.length];
        int codesEnd = (int) compressor.compress(row, 0, row.length, codes, 0);

        MemorySegment segmentCodes = MemorySegment.ofArray(new byte[2 * row.length]);
        long segmentEnd = compressor.compress(MemorySegment.ofArray(row), 0, row.length, segmentCodes, 0);
        assertEquals(codesEnd, segmentEnd, "byte[] and MemorySegment code lengths");
        assertArrayEquals(Arrays.copyOf(codes, codesEnd),
                segmentCodes.asSlice(0, segmentEnd).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                "byte[] and MemorySegment codes");

        byte[] decoded = new byte[8 * codesEnd];
        int decodedEnd = (int) decompressor.decompress(codes, 0, codesEnd, decoded, 0);
        assertArrayEquals(row, Arrays.copyOf(decoded, decodedEnd), "decompress(compress(row))");
    }
}
