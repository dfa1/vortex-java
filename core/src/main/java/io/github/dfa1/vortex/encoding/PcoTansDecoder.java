package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/// 4-way interleaved tANS decoder for one pco latent variable.
///
/// Build via {@link #build(int, PcoBin[])}; then call {@link #decodePage} once per page.
/// Port of {@code pco/src/ans/spec.rs} (spread) and {@code pco/src/ans/decoding.rs} (nodes).
final class PcoTansDecoder {

    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    static final int BATCH_N = 256;
    static final int ANS_INTERLEAVING = 4;

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
    /// Port of {@code Spec::from_weights} + {@code Decoder::new} from pcodec.
    static PcoTansDecoder build(int ansSizeLog, PcoBin[] bins) {
        if (bins.length == 0) {
            // Degenerate: no bins → 1-state table, all offsets zero.
            return new PcoTansDecoder(new int[]{0}, new int[]{0}, new int[]{0}, new long[]{0L});
        }

        int tableSize = 1 << ansSizeLog;
        int[] weights = new int[bins.length];
        for (int i = 0; i < bins.length; i++) {
            weights[i] = bins[i].weight();
        }

        int[] stateSymbols = spreadStateSymbols(ansSizeLog, weights, tableSize);

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

    /// Port of {@code Spec::spread_state_symbols} from pcodec.
    ///
    /// Spreads symbols across the table with a stride of ~3/5 * tableSize (odd).
    static int[] spreadStateSymbols(int ansSizeLog, int[] weights, int tableSize) {
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

    /// Decode {@code n} raw latent values (U64) from {@code reader} into {@code out}.
    ///
    /// Caller must have already read 4 initial ANS state indices and called
    /// {@link LeBitReader#alignToByte()} before this call.
    /// {@code ansStateIdxs} is modified in place and not valid after return.
    /// {@code batchLowers} and {@code batchOffsetBits} are caller-provided scratch arrays of
    /// length ≥ {@link #BATCH_N}; they are fully overwritten before use.
    void decodePage(LeBitReader reader, int[] ansStateIdxs, int n,
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

    /// Decode exactly {@code batchN} latent values into {@code out[outByteOffset..]} and advance
    /// the ANS states.
    ///
    /// {@code batchLowers} and {@code batchOffsetBits} are caller-provided scratch arrays of
    /// length ≥ {@code batchN}; they are fully overwritten before use.
    void decodeBatch(LeBitReader reader, int[] ansStateIdxs, int batchN,
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
