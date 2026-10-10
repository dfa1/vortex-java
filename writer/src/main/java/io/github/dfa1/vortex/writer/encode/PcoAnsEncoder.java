package io.github.dfa1.vortex.writer.encode;

/// tANS encode table — port of `pco/src/ans/encoding.rs` and `spec.rs`.
///
/// State is kept in Rust convention: `state ∈ [tableSize, 2*tableSize)`.
/// The page header stores `state - tableSize` as the decoder initial state index.
final class PcoAnsEncoder {

    /// Result of one ANS encode step.
    ///
    /// @param newState  next ANS state (Rust convention: `∈ [tableSize, 2*tableSize)`)
    /// @param bits      low `numBits` bits of the old state, to be written to the stream
    /// @param numBits   number of bits to write (renormalization bits)
    record Step(int newState, int bits, int numBits) {
    }

    private final int tableSize;
    private final int[] minRenormBits;
    private final int[] renormBitCutoff;
    // nextStates[nextStateBase[sym] + xS] = Rust state for the (xS - w)-th occurrence of sym; one
    // flat table, so an encode step is one indexed load rather than two dependent ones
    private final int[] nextStateBase;
    private final int[] nextStates;

    private PcoAnsEncoder(int tableSize,
            int[] minRenormBits, int[] renormBitCutoff, int[] nextStateBase, int[] nextStates) {
        this.tableSize = tableSize;
        this.minRenormBits = minRenormBits;
        this.renormBitCutoff = renormBitCutoff;
        this.nextStateBase = nextStateBase;
        this.nextStates = nextStates;
    }

    /// Build an encoder from quantized ANS weights (sum must equal `2^sizeLog`).
    ///
    /// @param sizeLog log₂ of the ANS table size
    /// @param weights quantized weight per symbol (sum == `1 << sizeLog`)
    /// @return a ready-to-use encoder
    static PcoAnsEncoder build(int sizeLog, int[] weights) {
        int tableSize = 1 << sizeLog;
        int nSymbols = weights.length;

        int[] minR = new int[nSymbols];
        int[] cutoff = new int[nSymbols];
        int[] base = new int[nSymbols];
        int[] firstIdx = new int[nSymbols];

        int start = 0;
        for (int sym = 0; sym < nSymbols; sym++) {
            int w = weights[sym];
            int maxXS = 2 * w - 1;
            int mr = sizeLog - (31 - Integer.numberOfLeadingZeros(maxXS));
            minR[sym] = mr;
            cutoff[sym] = 2 * w * (1 << mr);
            firstIdx[sym] = start;
            base[sym] = start - w;
            start += w;
        }

        int[] nextSt = new int[start];
        int[] stateSymbols = spreadStateSymbols(weights, tableSize);
        for (int stateIdx = 0; stateIdx < tableSize; stateIdx++) {
            nextSt[firstIdx[stateSymbols[stateIdx]]++] = tableSize + stateIdx;
        }

        return new PcoAnsEncoder(tableSize, minR, cutoff, base, nextSt);
    }

    /// Encode one symbol. Caller writes the low [Step#numBits()] bits of
    /// the old state to the bit stream (in LIFO order; caller reverses per batch).
    ///
    /// @param state  current ANS state `∈ [tableSize, 2*tableSize)`
    /// @param symbol bin symbol index
    /// @return encode step
    Step encode(int state, int symbol) {
        int renormBits = minRenormBits[symbol] + (state >= renormBitCutoff[symbol] ? 1 : 0);
        int bitsVal = state & ((1 << renormBits) - 1);
        int xS = state >>> renormBits; // in [w, 2w)
        int newState = nextStates[nextStateBase[symbol] + xS];
        return new Step(newState, bitsVal, renormBits);
    }

    /// Starting state for all 4 interleaved streams.
    ///
    /// @return `tableSize` (Rust convention initial state)
    int defaultState() {
        return tableSize;
    }

    /// Convert a final encoder state to the decoder initial state index written in the page header.
    ///
    /// @param state final Rust-convention state after encoding
    /// @return 0-based state index (= `state - tableSize`)
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
