package io.github.dfa1.vortex.integration;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

class OhlcGenerator {

	private OhlcGenerator() {}

	static final String[] TICKERS = {
			"AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "TSLA", "BRK.B", "JPM", "V",
			"UNH", "XOM", "LLY", "JNJ", "MA", "PG", "HD", "MRK", "AVGO", "CVX",
			"PEP", "ABBV", "KO", "COST", "WMT", "MCD", "CSCO", "ACN", "BAC", "CRM"
	};

	record OhlcBatch(String[] symbols, int[] dates, double[] open, double[] high,
	                 double[] low, double[] close, long[] volume) {}

	static List<OhlcBatch> generate(int totalRows, int batchSize) {
		return generate(totalRows, batchSize, 42L);
	}

	static List<OhlcBatch> generate(int totalRows, int batchSize, long seed) {
		double[] prices = new double[TICKERS.length];
		Arrays.fill(prices, 100.0);
		var rng = new Random(seed);
		int startDay = (int) LocalDate.of(2020, 1, 2).toEpochDay();
		int rowsLeft = totalRows;
		int rowsDone = 0;
		var batches = new ArrayList<OhlcBatch>();

		while (rowsLeft > 0) {
			int n = Math.min(rowsLeft, batchSize);
			String[] symbols = new String[n];
			int[] dates = new int[n];
			double[] open = new double[n], high = new double[n], low = new double[n], close = new double[n];
			long[] volume = new long[n];
			for (int i = 0; i < n; i++) {
				int ticker = i % TICKERS.length;
				double px = prices[ticker];
				double ret = rng.nextGaussian() * 0.02;
				double o = Math.round(px * (1 + ret * 0.3) * 100.0) * 0.01;
				double c = Math.round(px * (1 + ret) * 100.0) * 0.01;
				double spread = Math.abs(px * rng.nextDouble() * 0.03);
				symbols[i] = TICKERS[ticker];
				dates[i]   = startDay + (rowsDone + i) / TICKERS.length;
				open[i]    = o;
				high[i]    = Math.round((Math.max(o, c) + spread) * 100.0) * 0.01;
				low[i]     = Math.round((Math.min(o, c) - spread) * 100.0) * 0.01;
				close[i]   = c;
				volume[i]  = Math.max(100_000L, Math.round(1_000_000 + rng.nextGaussian() * 200_000));
				prices[ticker] = c;
			}
			batches.add(new OhlcBatch(symbols, dates, open, high, low, close, volume));
			rowsLeft -= n;
			rowsDone += n;
		}
		return batches;
	}
}
