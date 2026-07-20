package io.github.dfa1.vortex.fsst;

import java.util.List;

/// Trains an FSST [Compressor] from a corpus of rows using the FSST paper's bottom-up algorithm
/// (Algorithm 3) plus the Rust reference's four engineering refinements (issue #287).
///
/// Training runs [#GENERATIONS] generations starting from an empty symbol table. Each generation
/// compress-counts a growing fraction of one fixed [Sample] with the current table, then rebuilds
/// the table from the top-gain candidates (see [TrainingGeneration]). The refinements — adaptive
/// per-generation sample fraction, per-generation min-count pruning, the 8x single-byte gain boost,
/// and a final cost-based prune — are all applied inside that per-generation step; this class only
/// drives the loop and threads the seed through.
///
/// Training is deterministic: the same rows and seed always produce a byte-identical [Compressor],
/// so an encoder built on top of it produces byte-identical output for a given input. Use the
/// builder to override the seed:
///
/// ```java
/// Compressor compressor = new CompressorBuilder().seed(42L).train(rows);
/// ```
public final class CompressorBuilder {

    /// Number of training generations. The FSST paper reports the table stabilizing after roughly
    /// five passes; each pass can pair adjacent symbols to roughly double their length, so five
    /// passes grow symbols to the full 8-byte length from an empty start.
    private static final int GENERATIONS = Sample.SAMPLE_FRACTION_NUMERATORS.length;

    /// Default PRNG seed when [#seed(long)] is not called. A fixed constant (not wall-clock derived)
    /// keeps training reproducible out of the box.
    private static final long DEFAULT_SEED = 0x5EEDL;

    private long seed = DEFAULT_SEED;

    /// Creates a builder using the default seed until [#seed(long)] is called.
    public CompressorBuilder() {
        // Explicit no-arg constructor: fields default to the reproducible DEFAULT_SEED.
    }

    /// Sets the PRNG seed used to draw the training sample. The same rows and seed always train a
    /// byte-identical compressor.
    ///
    /// @param seed the sample-drawing seed
    /// @return this builder, for chaining
    public CompressorBuilder seed(long seed) {
        this.seed = seed;
        return this;
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
        Sample sample = Sample.draw(rows, seed);
        Compressor compressor = Compressor.of(List.of());
        if (sample.chunkCount() == 0) {
            return compressor;
        }
        for (int gen = 0; gen < GENERATIONS; gen++) {
            boolean finalGeneration = gen == GENERATIONS - 1;
            int chunkLimit = sample.chunkCountForGeneration(gen);
            int fractionNumerator = Sample.SAMPLE_FRACTION_NUMERATORS[gen];
            List<Symbol> symbols = TrainingGeneration.run(
                    compressor, sample, chunkLimit, fractionNumerator, finalGeneration);
            compressor = Compressor.of(symbols);
        }
        return compressor;
    }
}
