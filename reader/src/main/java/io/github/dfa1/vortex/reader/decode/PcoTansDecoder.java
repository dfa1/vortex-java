package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;

import java.lang.foreign.MemorySegment;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_LONG;

/// 4-way interleaved tANS decoder for one pco latent variable.
///
/// Build via [#build(int, PcoBin[])]; then call [#decodePage] once per page.
/// Port of `pco/src/ans/spec.rs` (spread) and `pco/src/ans/decoding.rs` (nodes).
public final class PcoTansDecoder {

    public static final int BATCH_N = 256;
    public static final int ANS_INTERLEAVING = 4;
    // All arrays indexed by state index in [0, tableSize).
    private final int[] nextStateIdxBase; // = (symbolXs[sym] << bitsToRead) - tableSize
    private final int[] bitsToRead;       // bits consumed from bit stream per ANS step
    private final int[] nodeOffsetBits;   // offset bits for this bin (stored per-state for cache locality)
    private final long[] stateLowers;     // bin.lower for each state

    private PcoTansDecoder(int[] nextStateIdxBase, int[] bitsToRead,
            int[] nodeOffsetBits, long[] stateLowers) {
        this.nextStateIdxBase = nextStateIdxBase;
        this.bitsToRead = bitsToRead;
        this.nodeOffsetBits = nodeOffsetBits;
        this.stateLowers = stateLowers;
    }

    /// Build the decode table from chunk latent-var metadata.
    ///
    /// Port of `Spec::from_weights` + `Decoder::new` from pcodec.
    public static PcoTansDecoder build(int ansSizeLog, PcoBin[] bins) {
        int tableSize = 1 << ansSizeLog;
        if (bins.length == 0) {
            // Degenerate: no bins → every state decodes to offset zero. Sized to tableSize
            // (not a fixed 1-state table): the initial ANS state indices a page carries are
            // read with ansSizeLog bits (so any value in [0, tableSize) is possible) before
            // this decoder is consulted — a corrupt file pairing zero bins with a nonzero
            // ansSizeLog previously indexed a real 1-entry table out of bounds, a raw
            // ArrayIndexOutOfBoundsException instead of a VortexException (ADR 0003).
            return new PcoTansDecoder(new int[tableSize], new int[tableSize], new int[tableSize], new long[tableSize]);
        }

        int[] weights = new int[bins.length];
        for (int i = 0; i < bins.length; i++) {
            weights[i] = bins[i].weight();
        }

        int[] stateSymbols = spreadStateSymbols(weights, tableSize);

        int[] symbolXs = weights.clone();
        int[] nextStateIdxBase = new int[tableSize];
        int[] bitsToRead = new int[tableSize];
        int[] nodeOffsetBits = new int[tableSize];
        long[] stateLowers = new long[tableSize];

        for (int s = 0; s < tableSize; s++) {
            int sym = stateSymbols[s];
            int xs = symbolXs[sym];
            int btr = Integer.numberOfLeadingZeros(xs) - Integer.numberOfLeadingZeros(tableSize);
            int nextBase = xs << btr;
            nextStateIdxBase[s] = nextBase - tableSize;
            bitsToRead[s] = btr;
            nodeOffsetBits[s] = sym < bins.length ? bins[sym].offsetBits() : 0;
            stateLowers[s] = sym < bins.length ? bins[sym].lower() : 0L;
            symbolXs[sym]++;
        }

        return new PcoTansDecoder(nextStateIdxBase, bitsToRead, nodeOffsetBits, stateLowers);
    }

    /// Port of `Spec::spread_state_symbols` from pcodec.
    ///
    /// Spreads symbols across the table with a stride of ~3/5 * tableSize (odd).
    static int[] spreadStateSymbols(int[] weights, int tableSize) {
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

    /// Decode `n` raw latent values (U64) from `reader` into `out`.
    ///
    /// Caller must have already read 4 initial ANS state indices and called
    /// [LeBitReader#alignToByte()] before this call.
    /// `ansStateIdxs` is modified in place and not valid after return.
    /// `batchLowers` and `batchOffsetBits` are caller-provided scratch arrays of
    /// length ≥ [#BATCH_N]; they are fully overwritten before use.
    public void decodePage(LeBitReader reader, int[] ansStateIdxs, int n,
            MemorySegment out, long outByteOffset,
            long[] batchLowers, int[] batchOffsetBits) {
        int remaining = n;
        long pos = outByteOffset;
        while (remaining > 0) {
            int batchN = Math.min(remaining, BATCH_N);
            decodeBatch(reader, ansStateIdxs, batchN, batchLowers, batchOffsetBits, out, pos);
            pos += (long) batchN * Long.BYTES;
            remaining -= batchN;
        }
    }

    /// Decode exactly `batchN` latent values into `out[outByteOffset..]` and advance
    /// the ANS states.
    ///
    /// `batchLowers` and `batchOffsetBits` are caller-provided scratch arrays of
    /// length ≥ `batchN`; they are fully overwritten before use.
    public void decodeBatch(LeBitReader reader, int[] ansStateIdxs, int batchN,
            long[] batchLowers, int[] batchOffsetBits,
            MemorySegment out, long outByteOffset) {
        int tableSize = nextStateIdxBase.length;
        for (int i = 0; i < batchN; i++) {
            int si = ansStateIdxs[i % ANS_INTERLEAVING];
            batchLowers[i] = stateLowers[si];
            batchOffsetBits[i] = nodeOffsetBits[si];
            long ansVal = reader.readBits(bitsToRead[si]);
            int nextSi = nextStateIdxBase[si] + (int) ansVal;
            if (nextSi < 0 || nextSi >= tableSize) {
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "corrupt pco ANS state " + nextSi + " out of range [0, " + tableSize + ")");
            }
            ansStateIdxs[i % ANS_INTERLEAVING] = nextSi;
        }
        long pos = outByteOffset;
        for (int i = 0; i < batchN; i++) {
            long offset = reader.readBits(batchOffsetBits[i]);
            out.set(LE_LONG, pos, batchLowers[i] + offset);
            pos += Long.BYTES;
        }
    }
}
