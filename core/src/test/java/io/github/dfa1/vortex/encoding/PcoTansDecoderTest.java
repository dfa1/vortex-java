package io.github.dfa1.vortex.encoding;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class PcoTansDecoderTest {

    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    @Nested
    class Spread {

        @Test
        void matchesSpecRsReferenceOutput() {
            // Given — from pcodec spec.rs test: ans_spec_new
            // weights=[1,1,3,11], total=16=2^4 → size_log=4
            int[] weights = {1, 1, 3, 11};
            int tableSize = 16;

            // When
            int[] stateSymbols = PcoTansDecoder.spreadStateSymbols(4, weights, tableSize);

            // Then — exact output from pcodec spec.rs reference test
            assertThat(stateSymbols).containsExactly(0, 3, 2, 3, 2, 3, 3, 3, 3, 1, 3, 2, 3, 3, 3, 3);
        }

        @Test
        void trivialSingleSymbol_fillsTable() {
            // Given — from pcodec spec.rs test: ans_spec_new_trivial (weight=[1])
            int[] weights = {1};

            // When
            int[] stateSymbols = PcoTansDecoder.spreadStateSymbols(0, weights, 1);

            // Then
            assertThat(stateSymbols).containsExactly(0);
        }

        @Test
        void twoEqualWeights_twoElementTable() {
            // Given — weights=[2], total=2=2^1
            int[] weights = {2};

            // When
            int[] stateSymbols = PcoTansDecoder.spreadStateSymbols(1, weights, 2);

            // Then
            assertThat(stateSymbols).containsExactly(0, 0);
        }
    }

    @Nested
    class Decode {

        @Test
        void singleBin_zeroBits_constantOutput() {
            // Given — 1 bin: lower=42, offsetBits=0 → every value is exactly 42
            PcoBin[] bins = {new PcoBin(1, 42L, 0)};
            PcoTansDecoder sut = PcoTansDecoder.build(0, bins);

            MemorySegment pageBuf = Arena.ofAuto().allocate(8); // all-zero bytes sufficient
            LeBitReader reader = new LeBitReader(pageBuf);
            int[] stateIdxs = {0, 0, 0, 0};

            int n = 5;
            MemorySegment out = Arena.ofAuto().allocate((long) n * Long.BYTES);

            // When
            sut.decodePage(reader, stateIdxs, n, out, 0L);

            // Then — all raw latent values = 42
            for (int i = 0; i < n; i++) {
                assertThat(out.get(LE_LONG, (long) i * Long.BYTES))
                        .as("latent[%d]", i)
                        .isEqualTo(42L);
            }
        }

        @Test
        void singleBin_oneBitOffset_decodesOffset() {
            // Given — 1 bin: lower=10, offsetBits=1 → values are 10 or 11 depending on offset bit
            PcoBin[] bins = {new PcoBin(1, 10L, 1)};
            PcoTansDecoder sut = PcoTansDecoder.build(0, bins);

            // Page buffer: all-zero → offset bits all 0 → all values = 10
            MemorySegment pageBuf = Arena.ofAuto().allocate(8);
            LeBitReader reader = new LeBitReader(pageBuf);
            int[] stateIdxs = {0, 0, 0, 0};

            int n = 4;
            MemorySegment out = Arena.ofAuto().allocate((long) n * Long.BYTES);

            // When
            sut.decodePage(reader, stateIdxs, n, out, 0L);

            // Then — offsets all zero → all values = lower + 0 = 10
            for (int i = 0; i < n; i++) {
                assertThat(out.get(LE_LONG, (long) i * Long.BYTES))
                        .as("latent[%d]", i)
                        .isEqualTo(10L);
            }
        }

        @Test
        void degenerateBins_zeroBins_outputZero() {
            // Given — 0 bins → degenerate decoder, output = 0
            PcoTansDecoder sut = PcoTansDecoder.build(0, new PcoBin[0]);

            MemorySegment pageBuf = Arena.ofAuto().allocate(8);
            LeBitReader reader = new LeBitReader(pageBuf);
            int[] stateIdxs = {0, 0, 0, 0};

            int n = 3;
            MemorySegment out = Arena.ofAuto().allocate((long) n * Long.BYTES);

            // When
            sut.decodePage(reader, stateIdxs, n, out, 0L);

            // Then — all zero
            for (int i = 0; i < n; i++) {
                assertThat(out.get(LE_LONG, (long) i * Long.BYTES)).isZero();
            }
        }

        @Test
        void moreThanOneBatch_decodesCorrectly() {
            // Given — 1 bin, lower=7, n=300 (> BATCH_N=256 → two batches)
            PcoBin[] bins = {new PcoBin(1, 7L, 0)};
            PcoTansDecoder sut = PcoTansDecoder.build(0, bins);

            MemorySegment pageBuf = Arena.ofAuto().allocate(8);
            LeBitReader reader = new LeBitReader(pageBuf);
            int[] stateIdxs = {0, 0, 0, 0};

            int n = 300;
            MemorySegment out = Arena.ofAuto().allocate((long) n * Long.BYTES);

            // When
            sut.decodePage(reader, stateIdxs, n, out, 0L);

            // Then — all values = 7
            for (int i = 0; i < n; i++) {
                assertThat(out.get(LE_LONG, (long) i * Long.BYTES))
                        .as("latent[%d]", i)
                        .isEqualTo(7L);
            }
        }
    }
}
