package io.github.dfa1.vortex.fsst;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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

    @ParameterizedTest
    @ValueSource(strings = {"ids", "logs", "random"})
    void train_noTwoLongSymbolsShareARustHashSlot(String corpus) {
        // Given — Rust rebuilds a compressor from a file's symbol table to compress a filter
        // literal, and panics ("rebuild symbol insertion into PHT must succeed") when two 3-8 byte
        // symbols hash to the same slot, aborting the JVM in vortex-jni. Java's table broke this on
        // shared-prefix ids, since it hashed differently and kept colliding symbols.
        byte[][] rows = switch (corpus) {
            case "ids" -> idRows(20_000);
            case "logs" -> logLikeRows(2_000);
            default -> randomRows(2_000);
        };

        // When
        Compressor result = new CompressorBuilder().train(rows);

        // Then
        java.util.Set<Integer> slots = new java.util.HashSet<>();
        for (int code = 0; code < result.symbolCount(); code++) {
            if (result.symbolLength(code) >= 3) {
                assertThat(slots.add(rustSlot(result.packedSymbol(code)))).as("code %d", code).isTrue();
            }
        }
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
        void train_sameInput_producesByteIdenticalTable() {
            // Given — the same corpus trained twice.
            byte[][] rows = logLikeRows(2_000);

            // When
            Compressor first = new CompressorBuilder().train(rows);
            Compressor second = new CompressorBuilder().train(rows);

            // Then — identical symbol count and identical (packed, length) for every code.
            assertThat(second.symbolCount()).isEqualTo(first.symbolCount());
            for (int code = 0; code < first.symbolCount(); code++) {
                assertThat(second.packedSymbol(code)).isEqualTo(first.packedSymbol(code));
                assertThat(second.symbolLength(code)).isEqualTo(first.symbolLength(code));
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
            // (count 1). A non-final generation scales its min-count floor with its sample
            // fraction (5 * 98 / 128 = 3 here), so the rare "cd" pair — and its bytes 'c'/'d' — must be pruned
            // before ranking, unlike a naive keep-everything scheme.
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 1_000; i++) {
                text.append("ab");
            }
            text.append("cd");
            Sample sample = Sample.draw(new byte[][]{utf8(text.toString())});
            Compressor emptyTable = Compressor.of(List.of());

            // When — one non-final generation (fraction 98 of 128: a floor of 5 * 98 / 128 = 3).
            List<Symbol> result =
                    TrainingGeneration.run(emptyTable, sample, 98, new TrainingGeneration.Counts());

            // Then — the rare bytes are no symbols of their own (a merge with a frequent code may carry
            // them, as Rust's candidates do: its floor is on the first code's count)
            assertThat(hasSymbol(result, 1, (byte) 'c')).isFalse();
            assertThat(hasSymbol(result, 1, (byte) 'd')).isFalse();
            // Sanity: the frequent 'a'/'b' content is retained.
            assertThat(containsByte(result, (byte) 'a')).isTrue();
        }
    }

    @Nested
    class Growth {

        @Test
        void train_aRunOfOneByte_growsEightByteSymbols() {
            // Given — rows of one repeated byte: each generation doubles the symbol by merging a
            // symbol with itself, up to the eight bytes a symbol holds
            byte[] row = new byte[1_000];
            Arrays.fill(row, (byte) 'x');

            // When
            Compressor sut = new CompressorBuilder().train(new byte[][]{row, row, row});

            // Then
            assertThat(hasSymbol(sut, 8, (byte) 'x', (byte) 'x', (byte) 'x', (byte) 'x',
                    (byte) 'x', (byte) 'x', (byte) 'x', (byte) 'x')).isTrue();
        }
    }

    @Nested
    class NulByteSymbols {

        @Test
        void train_dataWithZeroBytes_neverTakesASymbolEndingInOne() {
            // Given — "A" followed by a zero byte, and "AX", equally often. Rust measures a symbol by
            // its last non-zero byte, so the merge of "A" and a zero byte is just "A": a symbol
            // ending in zeros would have a length its bytes do not show.
            byte[] row = interleavedStandaloneAndNulSuffixed(2_000);

            // When
            Compressor sut = new CompressorBuilder().train(new byte[][]{row});

            // Then — every symbol's length is where its last non-zero byte is
            for (int code = 0; code < sut.symbolCount(); code++) {
                long packed = sut.packedSymbol(code);
                int length = Math.max(1, 8 - Long.numberOfLeadingZeros(packed) / 8);
                assertThat(sut.symbolLength(code)).as("symbol %d", code).isEqualTo(length);
            }
        }

        private byte[] interleavedStandaloneAndNulSuffixed(int repeats) {
            byte[] row = new byte[repeats * 4];
            int pos = 0;
            for (int i = 0; i < repeats; i++) {
                row[pos++] = 'A';
                row[pos++] = 'X';
                row[pos++] = 'A';
                row[pos++] = 0;
            }
            return row;
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

    @Nested
    class ContiguousRowsParity {

        /// Both `train` overloads must produce a byte-identical table: encoding is required to be
        /// reproducible for a given input, so the contiguous-rows overload cannot be allowed to
        /// drift from the `byte[][]` one — a divergence here would silently change written files
        /// depending on which shape the caller happened to have.
        @Test
        void train_contiguousRows_matchesPerRowTableExactly() {
            // Given — log-like rows with real repeated substrings, plus empty rows interleaved
            // (empty rows are skipped when drawing the sample, so they exercise the index
            // pre-filtering both overloads do independently).
            byte[][] rows = new byte[600][];
            var rng = new Random(7);
            for (int i = 0; i < rows.length; i++) {
                rows[i] = i % 17 == 0
                                  ? new byte[0]
                                  : utf8("2026-09-18 ERROR GET /api/v1/users/" + rng.nextInt(10_000)
                                                 + " -> 500 Internal Server Error");
            }

            // When
            Compressor perRow = new CompressorBuilder().train(rows);
            Compressor result = new CompressorBuilder()
                                        .train(flatten(rows), offsetsOf(rows), rows.length);

            // Then
            assertThat(result.symbolCount()).isEqualTo(perRow.symbolCount());
            for (int code = 0; code < perRow.symbolCount(); code++) {
                assertThat(result.packedSymbol(code)).isEqualTo(perRow.packedSymbol(code));
                assertThat(result.symbolLength(code)).isEqualTo(perRow.symbolLength(code));
            }
        }

        @Test
        void train_contiguousAllEmptyRows_producesEmptyTable() {
            // Given — every row empty, so there is nothing to learn and no chunk can be drawn.
            byte[][] rows = {new byte[0], new byte[0], new byte[0]};

            // When
            Compressor result = new CompressorBuilder()
                                        .train(flatten(rows), offsetsOf(rows), rows.length);

            // Then
            assertThat(result.symbolCount()).isZero();
        }

        @Test
        void train_contiguousNoRows_producesEmptyTable() {
            // Given — a zero-row corpus, the degenerate shape an empty chunk produces.
            // When
            Compressor result = new CompressorBuilder().train(new byte[0], new int[]{0}, 0);

            // Then
            assertThat(result.symbolCount()).isZero();
        }

        private byte[] flatten(byte[][] rows) {
            int total = 0;
            for (byte[] row : rows) {
                total += row.length;
            }
            byte[] out = new byte[total];
            int pos = 0;
            for (byte[] row : rows) {
                System.arraycopy(row, 0, out, pos, row.length);
                pos += row.length;
            }
            return out;
        }

        private int[] offsetsOf(byte[][] rows) {
            int[] offsets = new int[rows.length + 1];
            for (int i = 0; i < rows.length; i++) {
                offsets[i + 1] = offsets[i] + rows[i].length;
            }
            return offsets;
        }
    }

    private static boolean hasSymbol(List<Symbol> symbols, int length, byte... bytes) {
        for (Symbol symbol : symbols) {
            if (symbol.length() == length && matches(symbol, bytes)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSymbol(Compressor compressor, int length, byte... bytes) {
        for (int code = 0; code < compressor.symbolCount(); code++) {
            if (compressor.symbolLength(code) == length
                    && matches(new Symbol(compressor.packedSymbol(code), length), bytes)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Symbol symbol, byte[] bytes) {
        for (int k = 0; k < bytes.length; k++) {
            if (symbol.byteAt(k) != bytes[k]) {
                return false;
            }
        }
        return true;
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

    /// fsst-rs 0.7 `fsst_hash` of the first three bytes, masked to its 2048-slot table: written
    /// out here rather than calling [LossyPerfectHashTable#slotFor(long)], so the test checks Rust's
    /// slots and not whatever hash Java uses.
    private static int rustSlot(long packed) {
        long prefix = packed & 0xFF_FFFFL;
        return (int) ((prefix * 2971215073L ^ prefix >>> 15) & 2047);
    }

    private static byte[][] idRows(int n) {
        byte[][] rows = new byte[n][];
        for (int i = 0; i < n; i++) {
            rows[i] = utf8(i % 100 == 0 ? "\uD83D\uDE00" : String.format("EVT%010d", i));
        }
        return rows;
    }

    private static byte[][] randomRows(int n) {
        Random random = new Random(42);
        byte[][] rows = new byte[n][];
        for (int i = 0; i < n; i++) {
            rows[i] = new byte[random.nextInt(40)];
            random.nextBytes(rows[i]);
        }
        return rows;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
