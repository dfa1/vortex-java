package io.github.dfa1.vortex.fsst;

import java.util.List;

/// Trains an FSST [Compressor] from a corpus of rows as Rust's `fsst-rs` does (`Compressor::train`,
/// the FSST paper's Algorithm 3 with the C++ reference's refinements): the same sample, the same five
/// generations over a growing share of its lines, and the same candidates, gains and cut.
///
/// Training is deterministic: the same rows always produce a byte-identical [Compressor]. Rust's own
/// tables differ from this one only in where candidates of exactly equal gain and length are
/// ordered, which depends on its hash map ([TrainingGeneration]).
///
/// ```java
/// Compressor compressor = new CompressorBuilder().train(rows);
/// ```
public final class CompressorBuilder {

    /// Creates a builder.
    public CompressorBuilder() {
        // Explicit no-arg constructor: nothing to configure, Rust's training takes no options.
    }

    /// Trains a compressor over `rows`, running the full bottom-up generation loop.
    ///
    /// Empty input (no rows, or every row empty) trains an empty compressor (`symbolCount() == 0`),
    /// so encoding an empty column degrades to all escapes rather than failing.
    ///
    /// @param rows the corpus rows, each a raw byte array (never `null`; individual rows may be
    ///             empty)
    /// @return the trained compressor
    public Compressor train(byte[][] rows) {
        if (rows.length == 0) {
            return Compressor.of(List.of());
        }
        return trainOn(Sample.draw(rows));
    }

    /// Trains a compressor over rows held contiguously, where row `i` spans
    /// `rowBytes[rowOffsets[i], rowOffsets[i + 1])`.
    ///
    /// Equivalent to [#train(byte[][])] (same sample, same table) but takes the corpus in the
    /// contiguous shape a varbin column already has, so the caller need not materialize one `byte[]`
    /// per row just to train.
    ///
    /// @param rowBytes   all rows' bytes concatenated
    /// @param rowOffsets `rowCount + 1` cumulative offsets into `rowBytes`
    /// @param rowCount   the number of rows
    /// @return the trained compressor
    public Compressor train(byte[] rowBytes, int[] rowOffsets, int rowCount) {
        if (rowCount == 0) {
            return Compressor.of(List.of());
        }
        return trainOn(Sample.draw(rowBytes, rowOffsets, rowCount));
    }

    private Compressor trainOn(Sample sample) {
        Compressor compressor = Compressor.of(List.of());
        // Reused across every generation instead of allocating fresh count arrays five times over
        // (issue #395).
        TrainingGeneration.Counts counts = new TrainingGeneration.Counts();
        for (int fraction : Sample.FRACTION_NUMERATORS) {
            List<Symbol> symbols = TrainingGeneration.run(compressor, sample, fraction, counts);
            // Reuse compressor's own (now fully consumed) matcher arrays instead of allocating a
            // fresh ~288 KB pair of tables on every one of the five generations (issue #393 #7).
            compressor = Compressor.rebuild(symbols, compressor);
        }
        return compressor;
    }
}
