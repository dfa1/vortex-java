package io.github.dfa1.vortex.writer.encode;

/// tANS encode table — port of {@code pco/src/ans/encoding.rs} and {@code spec.rs}.
///
/// State is kept in Rust convention: {@code state ∈ [tableSize, 2*tableSize)}.
/// The page header stores {@code state - tableSize} as the decoder initial state index.
final class PcoAnsEncoder {

    /// Result of one ANS encode step.
    ///
    /// @param newState  next ANS state (Rust convention: {@code ∈ [tableSize, 2*tableSize)})
    /// @param bits      low {@code numBits} bits of the old state, to be written to the stream
    /// @param numBits   number of bits to write (renormalization bits)
    record Step(int newState, int bits, int numBits) {
    }

    private final int sizeLog;
    private final int tableSize;
    private final int[] minRenormBits;
    private final int[] renormBitCutoff;
    private final int[][] nextStates; // nextStates[sym][k] = Rust state for k-th occurrence

    private PcoAnsEncoder(int sizeLog, int tableSize,
            int[] minRenormBits, int[] renormBitCutoff, int[][] nextStates) {
        this.sizeLog = sizeLog;
        this.tableSize = tableSize;
        this.minRenormBits = minRenormBits;
        this.renormBitCutoff = renormBitCutoff;
        this.nextStates = nextStates;
    }

    /// Build an encoder from quantized ANS weights (sum must equal {@code 2^sizeLog}).
    ///
    /// @param sizeLog log₂ of the ANS table size
    /// @param weights quantized weight per symbol (sum == {@code 1 << sizeLog})
    /// @return a ready-to-use encoder
    static PcoAnsEncoder build(int sizeLog, int[] weights) {
        int tableSize = 1 << sizeLog;
        int nSymbols = weights.length;

        int[] minR = new int[nSymbols];
        int[] cutoff = new int[nSymbols];
        int[][] nextSt = new int[nSymbols][];

        for (int sym = 0; sym < nSymbols; sym++) {
            int w = weights[sym];
            int maxXS = 2 * w - 1;
            int mr = sizeLog - (31 - Integer.numberOfLeadingZeros(maxXS));
            minR[sym] = mr;
            cutoff[sym] = 2 * w * (1 << mr);
            nextSt[sym] = new int[w];
        }

        int[] stateSymbols = spreadStateSymbols(weights, tableSize);
        int[] symbolXs = weights.clone();
        for (int stateIdx = 0; stateIdx < tableSize; stateIdx++) {
            int sym = stateSymbols[stateIdx];
            int w = weights[sym];
            int k = symbolXs[sym] - w; // 0-based position into next_states[sym]
            nextSt[sym][k] = tableSize + stateIdx;
            symbolXs[sym]++;
        }

        return new PcoAnsEncoder(sizeLog, tableSize, minR, cutoff, nextSt);
    }

    /// Encode one symbol. Caller writes the low {@link Step#numBits()} bits of
    /// the old state to the bit stream (in LIFO order; caller reverses per batch).
    ///
    /// @param state  current ANS state {@code ∈ [tableSize, 2*tableSize)}
    /// @param symbol bin symbol index
    /// @return encode step
    Step encode(int state, int symbol) {
        int w = nextStates[symbol].length;
        int renormBits = state >= renormBitCutoff[symbol]
                ? minRenormBits[symbol] + 1
                : minRenormBits[symbol];
        int bitsVal = state & ((1 << renormBits) - 1);
        int xS = state >>> renormBits; // in [w, 2w)
        int newState = nextStates[symbol][xS - w];
        return new Step(newState, bitsVal, renormBits);
    }

    /// Starting state for all 4 interleaved streams.
    ///
    /// @return {@code tableSize} (Rust convention initial state)
    int defaultState() {
        return tableSize;
    }

    /// Convert a final encoder state to the decoder initial state index written in the page header.
    ///
    /// @param state final Rust-convention state after encoding
    /// @return 0-based state index (= {@code state - tableSize})
    int toStateIdx(int state) {
        return state - tableSize;
    }

    // Port of PcoTansDecoder.spreadStateSymbols (duplicated: writer cannot depend on reader).
    private static int[] spreadStateSymbols(int[] weights, int tableSize) {
        int[] stateSymbols = new int[tableSize];
        int stride = (3 * tableSize) / 5;
        if (stride % 2 == 0) {
            stride++;
        }
        int modMask = tableSize - 1;
        int step = 0;
        for (int sym = 0; sym < weights.length; sym++) {
            for (int k = 0; k < weights[sym]; k++) {
                stateSymbols[(stride * step) & modMask] = sym;
                step++;
            }
        }
        return stateSymbols;
    }
}
