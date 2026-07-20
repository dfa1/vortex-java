package io.github.dfa1.vortex.fsst;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Golden test reproducing the worked example in Figure 2 of the FSST paper (Boncz, Neumann, Leis,
/// "FSST: Fast Random Access String Compression", PVLDB 13(11), 2020, p. 2653, Figure 2: "Four
/// iterations of symbol table construction algorithm on the corpus 'tumcwitumvldb' with a maximum
/// symbol length of 3 and a maximum symbol table size of 5").
///
/// After the paper's fourth iteration the symbol table is `{tum, cwi, vld, b}` and the figure's
/// "Compressed" row at the bottom shows `tum | cwi | tum | vld | b`, len = 5 (down from the 13-byte
/// uncompressed corpus). This test hand-builds exactly that symbol table and asserts the greedy
/// longest-match compressor reproduces the figure's compressed code sequence and length, then that
/// the result round-trips back to the original 13 bytes.
///
/// The symbol table is hand-constructed on purpose. Using [CompressorBuilder#train(byte[][])] would
/// re-derive a table through this codebase's own training heuristics — a circular check. A golden
/// test needs an external, human-verifiable fixed point, so the symbols and their codes come
/// straight off the paper's figure, not off our trainer.
///
/// Symbol codes equal the symbol's index in the gain-descending list [Compressor#of(List)] receives.
/// Figure 2's iteration-4 table lists the symbols in gain order `tum` (gain 6), `cwi` (gain 3),
/// `vld` (gain 3), `b` (gain 1), so the codes assigned here are `tum`=0, `cwi`=1, `vld`=2, `b`=3.
class PaperFigure2Test {

    /// The paper's Figure 2 corpus, exactly as printed. ASCII, so US-ASCII and UTF-8 are equivalent.
    private static final byte[] CORPUS = "tumcwitumvldb".getBytes(StandardCharsets.US_ASCII);

    /// Codes for the figure's compressed row `tum | cwi | tum | vld | b`, using the code assignment
    /// documented on the class: tum=0, cwi=1, vld=2, b=3.
    private static final byte[] EXPECTED_CODES = {0, 1, 0, 2, 3};

    @Test
    void compress_paperFigure2Corpus_producesFigure2CodeSequenceAndLength() {
        // Given — the exact iteration-4 symbol table from Figure 2, in gain-descending order so
        // each symbol's code matches its column in the figure (tum=0, cwi=1, vld=2, b=3).
        Compressor sut = Compressor.of(List.of(
                symbol("tum"),
                symbol("cwi"),
                symbol("vld"),
                symbol("b")));
        byte[] out = new byte[CORPUS.length * 2];

        // When — greedy longest-match compression of the 13-byte corpus.
        long compressedLength = sut.compress(CORPUS, 0, CORPUS.length, out, 0);

        // Then — the figure's "Compressed ... len = 5" row: five codes [tum, cwi, tum, vld, b], no
        // escapes, exactly the sequence the paper draws.
        assertThat(compressedLength).isEqualTo(5L);
        assertThat(java.util.Arrays.copyOf(out, (int) compressedLength)).isEqualTo(EXPECTED_CODES);
    }

    @Test
    void compress_thenDecompress_paperFigure2Corpus_roundTripsToOriginal() {
        // Given — same hand-built Figure 2 table.
        Compressor sut = Compressor.of(List.of(
                symbol("tum"),
                symbol("cwi"),
                symbol("vld"),
                symbol("b")));
        byte[] compressed = new byte[CORPUS.length * 2];

        // When — compress, then decompress through the same table.
        long compressedLength = sut.compress(CORPUS, 0, CORPUS.length, compressed, 0);
        byte[] decompressed = new byte[CORPUS.length];
        long decompressedLength = sut.toDecompressor()
                .decompress(compressed, 0, (int) compressedLength, decompressed, 0);

        // Then — the primary correctness gate: the exact original 13 bytes come back.
        assertThat(decompressedLength).isEqualTo(CORPUS.length);
        assertThat(decompressed).isEqualTo(CORPUS);
    }

    private static Symbol symbol(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        return Symbol.of(bytes, 0, bytes.length);
    }
}
