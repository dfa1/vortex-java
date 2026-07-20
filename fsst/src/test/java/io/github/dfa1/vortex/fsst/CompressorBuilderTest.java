package io.github.dfa1.vortex.fsst;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class CompressorBuilderTest {

    @Test
    void train_emptyInput_producesEmptyTable() {
        // Given — no rows at all.
        CompressorBuilder sut = new CompressorBuilder();

        // When
        Compressor result = sut.train(new byte[0][]);

        // Then
        assertThat(result.symbolCount()).isZero();
    }

    @Test
    void train_allEmptyRows_producesEmptyTable() {
        // Given — rows exist but every one is empty, so there is nothing to learn.
        CompressorBuilder sut = new CompressorBuilder();

        // When
        Compressor result = sut.train(new byte[][]{new byte[0], new byte[0]});

        // Then
        assertThat(result.symbolCount()).isZero();
    }

    @Test
    void train_repetitiveText_learnsMultiByteSymbols() {
        // Given — realistic log-like lines with heavily repeated substrings ("ERROR", timestamps,
        // "host=example.com"), which FSST should collapse into multi-byte symbols.
        byte[][] rows = logLikeRows(2_000);
        CompressorBuilder sut = new CompressorBuilder();

        // When
        Compressor result = sut.train(rows);

        // Then — at least one learned symbol is longer than a bigram, i.e. training pairs adjacent
        // matches into longer symbols rather than stopping at length 2.
        assertThat(hasSymbolLongerThan(result, 2)).isTrue();
    }

    @Test
    void train_repetitiveText_compressesAndRoundTrips() {
        // Given
        byte[][] rows = logLikeRows(2_000);
        Compressor sut = new CompressorBuilder().train(rows);

        // When — compress then decompress row 0 through the trained table.
        byte[] input = rows[0];
        byte[] compressed = new byte[input.length * 2];
        long compressedLength = sut.compress(input, 0, input.length, compressed, 0);
        byte[] decompressed = new byte[input.length];
        long decompressedLength =
                sut.toDecompressor().decompress(compressed, 0, (int) compressedLength, decompressed, 0);

        // Then — round-trips exactly and the compressed form is smaller than the raw input.
        assertThat(decompressedLength).isEqualTo(input.length);
        assertThat(decompressed).isEqualTo(input);
        assertThat(compressedLength).isLessThan(input.length);
    }

    @Nested
    class EndOfInput {

        @Test
        void compress_symbolWithTrailingZeros_doesNotMatchPastEndOfInput() {
            // Given — a table with one length-8 symbol "AB\0\0\0\0\0\0". Compressing the 3-byte
            // input "AB\0" must NOT match that symbol: loadWord zero-pads the 5 bytes past the
            // input, so the branch-free matcher's masked compare would spuriously satisfy the full
            // 8-byte compare. Trusting it emits one code that decodes to 8 bytes, silently
            // appending 5 garbage bytes past the true length — a real corruption on any NUL-tailed
            // or binary row, which this encoder also accepts (DType.Binary). The caller must reject
            // any match reaching past the input end and escape instead.
            long packed = ('A' & 0xFFL) | (('B' & 0xFFL) << 8); // "AB" then six zero bytes.
            Compressor sut = Compressor.of(List.of(new Symbol(packed, 8)));
            byte[] input = {'A', 'B', 0};

            // When — compress then decompress the shorter input.
            byte[] compressed = new byte[input.length * 2];
            long compressedLength = sut.compress(input, 0, input.length, compressed, 0);
            byte[] decompressed = new byte[input.length];
            long decompressedLength = sut.toDecompressor()
                    .decompress(compressed, 0, (int) compressedLength, decompressed, 0);

            // Then — the round-trip preserves the exact original length and bytes.
            assertThat(decompressedLength).isEqualTo(input.length);
            assertThat(decompressed).isEqualTo(input);
        }
    }

    @Nested
    class Determinism {

        @Test
        void train_sameInputAndSeed_producesByteIdenticalTable() {
            // Given — the same corpus trained twice with the same explicit seed.
            byte[][] rows = logLikeRows(2_000);

            // When
            Compressor first = new CompressorBuilder().seed(1234L).train(rows);
            Compressor second = new CompressorBuilder().seed(1234L).train(rows);

            // Then — identical symbol count and identical (packed, length) for every code.
            assertThat(second.symbolCount()).isEqualTo(first.symbolCount());
            for (int code = 0; code < first.symbolCount(); code++) {
                assertThat(second.packedSymbol(code)).isEqualTo(first.packedSymbol(code));
                assertThat(second.symbolLength(code)).isEqualTo(first.symbolLength(code));
            }
        }

        @Test
        void train_defaultSeed_isAlsoDeterministic() {
            // Given — no explicit seed: the default seed must be a fixed constant, not wall-clock.
            byte[][] rows = logLikeRows(1_000);

            // When
            Compressor first = new CompressorBuilder().train(rows);
            Compressor second = new CompressorBuilder().train(rows);

            // Then
            assertThat(second.symbolCount()).isEqualTo(first.symbolCount());
            for (int code = 0; code < first.symbolCount(); code++) {
                assertThat(second.packedSymbol(code)).isEqualTo(first.packedSymbol(code));
            }
        }
    }

    @Nested
    class WireOrder {

        @Test
        void codesSortedByLength_multiByteAscendingThenSingleByteLast() {
            // Given — a trained table with a spread of symbol lengths.
            Compressor sut = new CompressorBuilder().train(logLikeRows(2_000));

            // When
            int[] result = sut.codesSortedByLength();

            // Then — multi-byte symbols come first in non-decreasing length order, then every
            // length-1 symbol, mirroring the vortex.fsst wire contract this permutation feeds.
            assertThat(result).hasSize(sut.symbolCount());
            int previousMultiByteLength = 0;
            boolean seenSingleByte = false;
            for (int code : result) {
                int length = sut.symbolLength(code);
                if (length == 1) {
                    seenSingleByte = true;
                } else {
                    assertThat(seenSingleByte)
                            .as("no multi-byte symbol may follow a length-1 symbol")
                            .isFalse();
                    assertThat(length)
                            .as("multi-byte symbols are in non-decreasing length order")
                            .isGreaterThanOrEqualTo(previousMultiByteLength);
                    previousMultiByteLength = length;
                }
            }
        }
    }

    @Nested
    class MinCountPruning {

        @Test
        void run_nonFinalGeneration_prunesCandidateBelowFloor() {
            // Given — "ab" repeated a thousand times (very frequent) with a single "cd" appended
            // (count 1). A non-final generation at full sample fraction (numerator 128) uses a
            // min-count floor of 5, so the rare "cd" pair — and its bytes 'c'/'d' — must be pruned
            // before ranking, unlike a naive keep-everything scheme.
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 1_000; i++) {
                text.append("ab");
            }
            text.append("cd");
            Sample sample = Sample.draw(new byte[][]{utf8(text.toString())}, 1L);
            Compressor emptyTable = Compressor.of(List.of());

            // When — one non-final generation over the whole sample.
            List<Symbol> result =
                    TrainingGeneration.run(emptyTable, sample, sample.chunkCount(), 128, false);

            // Then — the rare bytes never make it into the table.
            assertThat(containsByte(result, (byte) 'c')).isFalse();
            assertThat(containsByte(result, (byte) 'd')).isFalse();
            // Sanity: the frequent 'a'/'b' content is retained.
            assertThat(containsByte(result, (byte) 'a')).isTrue();
        }
    }

    @Nested
    class SingleByteGainBoost {

        @Test
        void train_frequentSingleByte_survivesAmidManyMultiByteCompetitors() {
            // Given — a frequent single byte 'x' separates many distinct 3-byte groups. Under plain
            // count*length gain a single byte is easily out-ranked by longer candidates; the 8x
            // single-byte boost is exactly what keeps a frequent byte in the table, so it is not
            // crowded out and its occurrences do not degrade into 2-byte escapes.
            String[] groups = {"abc", "def", "ghi", "jkl", "mno", "pqr", "stu", "vwx"};
            Random random = new Random(3);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 4_000; i++) {
                text.append(groups[random.nextInt(groups.length)]).append('x');
            }
            Compressor sut = new CompressorBuilder().train(new byte[][]{utf8(text.toString())});

            // When
            boolean result = hasSingleByteSymbol(sut, (byte) 'x');

            // Then
            assertThat(result).isTrue();
        }
    }

    private static byte[][] logLikeRows(int count) {
        // Real repeated substrings: a shared timestamp/level/host prefix plus a varying tail, the
        // kind of column FSST is meant to compress well.
        byte[][] rows = new byte[count][];
        String prefix = "2026-07-20T12:34:56Z ERROR request failed host=example.com id=";
        for (int i = 0; i < count; i++) {
            rows[i] = utf8(prefix + i);
        }
        return rows;
    }

    private static boolean hasSymbolLongerThan(Compressor compressor, int length) {
        for (int code = 0; code < compressor.symbolCount(); code++) {
            if (compressor.symbolLength(code) > length) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSingleByteSymbol(Compressor compressor, byte value) {
        for (int code = 0; code < compressor.symbolCount(); code++) {
            if (compressor.symbolLength(code) == 1 && (byte) compressor.packedSymbol(code) == value) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsByte(List<Symbol> symbols, byte value) {
        for (Symbol symbol : symbols) {
            for (int k = 0; k < symbol.length(); k++) {
                if (symbol.byteAt(k) == value) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
