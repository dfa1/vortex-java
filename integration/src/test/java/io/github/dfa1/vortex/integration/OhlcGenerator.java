package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.core.testing.OhlcData;

import java.util.List;

/// Thin adapter over the shared [OhlcData] generator (core test-jar), mapping each batch to the
/// field order the integration writers expect (`symbols`/`dates` first). The random-walk lives in
/// [OhlcData]; this only adapts the shape.
final class OhlcGenerator {

    static final String[] TICKERS = OhlcData.TICKERS;

    private OhlcGenerator() {
    }

    static List<OhlcBatch> generate(int totalRows, int batchSize) {
        return generate(totalRows, batchSize, 42L);
    }

    static List<OhlcBatch> generate(int totalRows, int batchSize, long seed) {
        return OhlcData.generate(totalRows, batchSize, seed).stream()
                .map(b -> new OhlcBatch(b.symbol(), b.date(), b.open(), b.high(), b.low(), b.close(), b.volume()))
                .toList();
    }

    record OhlcBatch(String[] symbols, int[] dates, double[] open, double[] high,
                     double[] low, double[] close, long[] volume) {
    }
}
