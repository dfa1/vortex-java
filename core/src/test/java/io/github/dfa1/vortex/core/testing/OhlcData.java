package io.github.dfa1.vortex.core.testing;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/// Writer-agnostic synthetic OHLC (open/high/low/close + volume) data generator, shared by tests
/// across modules via the core test-jar.
///
/// Produces deterministic column arrays (seeded random walk per ticker, one row per ticker per
/// day) and exposes the matching [DType.Struct] schema. It performs no I/O — each module writes
/// the batches with its own writer (the in-house Java writer, the JNI writer, …).
public final class OhlcData {

    /// The 30 ticker symbols, assigned round-robin within each batch.
    public static final String[] TICKERS = {
            "AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "TSLA", "BRK.B", "JPM", "V",
            "UNH", "XOM", "LLY", "JNJ", "MA", "PG", "HD", "MRK", "AVGO", "CVX",
            "PEP", "ABBV", "KO", "COST", "WMT", "MCD", "CSCO", "ACN", "BAC", "CRM"
    };

    /// The column schema: `date` (I32 epoch day), `symbol` (Utf8), `open`/`high`/`low`/`close`
    /// (F64), `volume` (I64); all non-nullable.
    public static final DType.Struct SCHEMA = new DType.Struct(
            List.of("date", "symbol", "open", "high", "low", "close", "volume"),
            List.of(
                    new DType.Primitive(PType.I32, false),
                    new DType.Utf8(false),
                    new DType.Primitive(PType.F64, false),
                    new DType.Primitive(PType.F64, false),
                    new DType.Primitive(PType.F64, false),
                    new DType.Primitive(PType.F64, false),
                    new DType.Primitive(PType.I64, false)),
            false);

    /// One chunk of OHLC rows as parallel column arrays.
    ///
    /// @param date   epoch-day per row
    /// @param symbol ticker per row
    /// @param open   opening price per row
    /// @param high   high price per row
    /// @param low    low price per row
    /// @param close  closing price per row
    /// @param volume traded volume per row
    public record Batch(int[] date, String[] symbol, double[] open, double[] high,
                        double[] low, double[] close, long[] volume) {
    }

    private OhlcData() {
    }

    /// Generates `rows` rows split into chunks of at most `chunkSize`, using the default seed.
    ///
    /// @param rows      total number of rows
    /// @param chunkSize maximum rows per batch
    /// @return the batches, in row order
    public static List<Batch> generate(int rows, int chunkSize) {
        return generate(rows, chunkSize, 42L);
    }

    /// Generates `rows` rows split into chunks of at most `chunkSize`, seeded by `seed`.
    ///
    /// @param rows      total number of rows
    /// @param chunkSize maximum rows per batch
    /// @param seed      the PRNG seed
    /// @return the batches, in row order
    public static List<Batch> generate(int rows, int chunkSize, long seed) {
        double[] prices = new double[TICKERS.length];
        Arrays.fill(prices, 100.0);
        var rng = new Random(seed);
        int startDay = (int) LocalDate.of(2020, 1, 2).toEpochDay();
        int rowsDone = 0;
        var batches = new ArrayList<Batch>();

        int rowsLeft = rows;
        while (rowsLeft > 0) {
            int n = Math.min(rowsLeft, chunkSize);
            int[] date = new int[n];
            String[] symbol = new String[n];
            double[] open = new double[n];
            double[] high = new double[n];
            double[] low = new double[n];
            double[] close = new double[n];
            long[] volume = new long[n];
            for (int i = 0; i < n; i++) {
                int ticker = i % TICKERS.length;
                double px = prices[ticker];
                double ret = rng.nextGaussian() * 0.02;
                double o = round(px * (1 + ret * 0.3));
                double c = round(px * (1 + ret));
                double spread = Math.abs(px * rng.nextDouble() * 0.03);
                date[i] = startDay + (rowsDone + i) / TICKERS.length;
                symbol[i] = TICKERS[ticker];
                open[i] = o;
                high[i] = round(Math.max(o, c) + spread);
                low[i] = round(Math.min(o, c) - spread);
                close[i] = c;
                volume[i] = Math.max(100_000L, Math.round(1_000_000 + rng.nextGaussian() * 200_000));
                prices[ticker] = c;
            }
            batches.add(new Batch(date, symbol, open, high, low, close, volume));
            rowsLeft -= n;
            rowsDone += n;
        }
        return batches;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) * 0.01;
    }
}
