package io.github.dfa1.vortex.core.compute;

/// The tANS state spread pcodec's `vortex.pco` encoding uses on both the read and write sides: the
/// encoder and decoder must lay symbols out identically, so there is one implementation.
public final class PcoAns {

    private PcoAns() {
    }

    /// Port of pcodec's `Spec::spread_state_symbols`: spreads each symbol over as many table
    /// states as its weight, stepping by an odd stride of about 3/5 of the table.
    ///
    /// @param weights   each symbol's weight; the weights sum to `tableSize`
    /// @param tableSize the number of states, a power of two
    /// @return the symbol of each state
    public static int[] spreadStateSymbols(int[] weights, int tableSize) {
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
