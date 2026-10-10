package io.github.dfa1.vortex.core.simd;

import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimdOperationsTest {

    // Full-range values: a wrong width, or sign vs zero extension, changes the result
    private static final long[] VALUES = {0L, -1L, 1L, 0x80L, 0xFFL, 0x8000L, 0xFFFFL, 0x80000000L,
            0xFFFFFFFFL, Long.MIN_VALUE, Long.MAX_VALUE, 0x123456789ABCDEFL};

    private final SimdOperations sut = SimdOperationsSupport.preferred();

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void widenInto_roundTripsNarrowedValues(PType ptype) {
        // Given values narrowed to ptype's width with the PTypeIO reference writer
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(VALUES.length * ptype.byteSize());
            long[] expected = new long[VALUES.length];
            for (int i = 0; i < VALUES.length; i++) {
                PTypeIO.set(src, i * ptype.byteSize(), ptype, VALUES[i]);
                expected[i] = PrimitiveArrays.readLong(src, i * ptype.byteSize(), ptype, EncodingId.VORTEX_PRIMITIVE);
            }

            // When widened from element 2, leaving the rest of out untouched
            long[] result = new long[VALUES.length];
            sut.widenInto(src, 2, VALUES.length - 2, ptype, result);

            // Then each element is sign- or zero-extended exactly as the reference read does
            for (int i = 0; i < VALUES.length - 2; i++) {
                assertThat(result[i]).isEqualTo(expected[i + 2]);
            }
            assertThat(result[VALUES.length - 1]).isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void minMax_matchesWidenedSignedReference(PType ptype) {
        // Given seeded-random arrays of full-range values, so a wrong width or sign/zero extension
        // changes the answer; lengths straddle the vector lane counts
        Random random = new Random(ptype.ordinal());
        for (int length : new int[]{1, 2, 7, 33, 1000}) {
            Object values = randomArray(ptype, length, random);
            long[] widened = PrimitiveArrays.toLongs(values, ptype, EncodingId.VORTEX_PRIMITIVE);
            long expectedMin = Long.MAX_VALUE;
            long expectedMax = Long.MIN_VALUE;
            for (long v : widened) {
                expectedMin = Math.min(expectedMin, v);
                expectedMax = Math.max(expectedMax, v);
            }

            // When
            long[] result = sut.minMax(values, ptype);

            // Then the signed min and max of the widened values
            assertThat(result).containsExactly(expectedMin, expectedMax);
        }
    }

    @Test
    void minMax_u32AboveSignedRange_isZeroExtended() {
        // Given U32 values straddling 2^31, where sign-bit handling would swap min and max
        int[] values = {0x8000_0000, 5, 0xFFFF_FFFF};

        // When
        long[] result = sut.minMax(values, PType.U32);

        // Then
        assertThat(result).containsExactly(5L, 0xFFFF_FFFFL);
    }

    @Test
    void minMax_u64AboveSignedRange_isOrderedAsSigned() {
        // Given a U64 value with the top bit set, which sits at a negative signed position
        long[] values = {1L, -1L};

        // When
        long[] result = sut.minMax(values, PType.U64);

        // Then the contract is signed order over the widened values (ArrayStats' dense span relies on it)
        assertThat(result).containsExactly(-1L, 1L);
    }

    @Test
    void minMax_emptyArray_throws() {
        // Given
        int[] values = {};

        // When / Then
        assertThatThrownBy(() -> sut.minMax(values, PType.I32)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"F16", "F32", "F64"})
    void minMax_floatingPType_throws(PType ptype) {
        // Given
        Object values = ptype == PType.F64 ? new double[]{1.0} : ptype == PType.F32 ? new float[]{1f} : new short[]{1};

        // When / Then
        assertThatThrownBy(() -> sut.minMax(values, ptype)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "F16", "I32", "U32", "F32"})
    void narrowInto_matchesPTypeIoSet(PType ptype) {
        // Given
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment expected = arena.allocate(VALUES.length * ptype.byteSize());
            for (int i = 0; i < VALUES.length; i++) {
                PTypeIO.set(expected, i * ptype.byteSize(), ptype, VALUES[i]);
            }
            MemorySegment result = arena.allocate(VALUES.length * ptype.byteSize());

            // When
            sut.narrowInto(VALUES, ptype, result);

            // Then
            assertThat(result.mismatch(expected)).isEqualTo(-1L);
        }
    }

    @Test
    void widenInto_countZero_writesNothing() {
        // Given
        long[] result = {7L};

        // When
        sut.widenInto(MemorySegment.NULL, 0, 0, PType.I32, result);

        // Then
        assertThat(result).containsExactly(7L);
    }

    @ParameterizedTest
    @CsvSource({"U8, 0", "U8, 1", "U8, 7", "U8, 1000", "U16, 3", "U16, 1001", "U32, 5", "U32, 1003"})
    void maxUnsigned_findsMaxAtEveryPosition(PType ptype, int count) {
        // Given small random values with the type's top value planted at each position in turn:
        // that covers each of the four accumulators and the tail, and the top value has the sign
        // bit set, so a signed compare (or sign extension) anywhere would lose it
        long top = FastLanes.lowMask(ptype.byteSize() * 8);
        Random random = new Random(count);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(Math.max(count, 1) * (long) ptype.byteSize());
            for (int i = 0; i < count; i++) {
                PTypeIO.set(src, (long) i * ptype.byteSize(), ptype, random.nextInt(100));
            }
            for (int planted = 0; planted < count; planted++) {
                long saved = PrimitiveArrays.readLong(src, (long) planted * ptype.byteSize(), ptype,
                        EncodingId.VORTEX_PRIMITIVE);
                PTypeIO.set(src, (long) planted * ptype.byteSize(), ptype, top);

                // When
                long result = sut.maxUnsigned(src, count, ptype);

                // Then
                assertThat(result).as("top planted at %d", planted).isEqualTo(top);
                PTypeIO.set(src, (long) planted * ptype.byteSize(), ptype, saved);
            }
            assertThat(sut.maxUnsigned(src, count, ptype)).isLessThan(100);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "I16", "I32", "U64", "F32"})
    void maxUnsigned_nonCodePType_throws(PType ptype) {
        // When / Then
        assertThatThrownBy(() -> sut.maxUnsigned(MemorySegment.NULL, 0, ptype))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"F16", "F32", "F64"})
    void widenInto_floatingPType_throws(PType ptype) {
        // When / Then
        assertThatThrownBy(() -> sut.widenInto(MemorySegment.NULL, 0, 0, ptype, new long[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I64", "U64", "F64"})
    void narrowInto_eightByteType_throws(PType ptype) {
        // When / Then
        assertThatThrownBy(() -> sut.narrowInto(new long[0], ptype, MemorySegment.NULL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 16, 32, 64})
    void undeltaChunk_matchesLaneByLaneReference(int typeBits) {
        // Given random deltas wider than the element (as sign-extended signed values are), so a
        // missing final mask shows up; the reference is the original lane-outer prefix sum
        Random random = new Random(typeBits);
        int lanes = FastLanes.CHUNK / typeBits;
        long mask = FastLanes.lowMask(typeBits);
        long[] deltas = random.longs(FastLanes.CHUNK).toArray();
        long[] bases = random.longs(lanes).toArray();
        long[] expected = new long[FastLanes.CHUNK];
        for (int lane = 0; lane < lanes; lane++) {
            long prev = bases[lane] & mask;
            for (int row = 0; row < typeBits; row++) {
                int idx = FastLanes.iterateIndex(row, lane);
                prev = ((deltas[idx] & mask) + prev) & mask;
                expected[idx] = prev;
            }
        }

        // When
        long[] result = new long[FastLanes.CHUNK];
        sut.undeltaChunk(deltas, bases, lanes, typeBits, mask, result);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 16, 32, 64})
    void deltaChunk_matchesLaneByLaneReference_andUndeltaInverts(int typeBits) {
        // Given
        Random random = new Random(typeBits);
        int lanes = FastLanes.CHUNK / typeBits;
        long mask = FastLanes.lowMask(typeBits);
        long[] values = random.longs(FastLanes.CHUNK).toArray();
        long[] bases = random.longs(lanes).toArray();
        long[] expected = new long[FastLanes.CHUNK];
        for (int lane = 0; lane < lanes; lane++) {
            long prev = bases[lane] & mask;
            for (int row = 0; row < typeBits; row++) {
                int idx = FastLanes.iterateIndex(row, lane);
                long next = values[idx] & mask;
                expected[idx] = (next - prev) & mask;
                prev = next;
            }
        }

        // When
        long[] result = new long[FastLanes.CHUNK];
        sut.deltaChunk(values, bases, lanes, typeBits, mask, result);

        // Then it matches the reference, and undelta restores the masked input
        assertThat(result).isEqualTo(expected);
        long[] restored = new long[FastLanes.CHUNK];
        sut.undeltaChunk(result, bases, lanes, typeBits, mask, restored);
        for (int i = 0; i < FastLanes.CHUNK; i++) {
            assertThat(restored[i]).isEqualTo(values[i] & mask);
        }
    }

    @ParameterizedTest
    @CsvSource({"8,1", "8,3", "8,8", "16,5", "16,16", "32,7", "32,13", "32,32", "64,1", "64,33", "64,64"})
    void packBlock_matchesBitByBitReference(int typeBits, int bitWidth) {
        // Given random values wider than bitWidth (patch candidates), and an offset into the array
        Random random = new Random(typeBits * 100L + bitWidth);
        int lanes = FastLanes.CHUNK / typeBits;
        long[] values = random.longs(FastLanes.CHUNK + 5).toArray();
        long widthMask = bitWidth >= 64 ? -1L : (1L << bitWidth) - 1L;

        // The reference places value (row, lane) at bit row*bitWidth of the lane's bitstream, one
        // bit at a time, so it shares no shift/carry logic with the kernel
        long[] expected = new long[bitWidth * lanes];
        for (int lane = 0; lane < lanes; lane++) {
            for (int row = 0; row < typeBits; row++) {
                long value = values[5 + FastLanes.iterateIndex(row, lane)] & widthMask;
                for (int bit = 0; bit < bitWidth; bit++) {
                    int position = row * bitWidth + bit;
                    int word = position / typeBits;
                    int inWord = position % typeBits;
                    expected[word * lanes + lane] |= ((value >>> bit) & 1L) << inWord;
                }
            }
        }

        // When
        long[] result = new long[bitWidth * lanes];
        sut.packBlock(values, 5, bitWidth, typeBits, result);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    private static Object randomArray(PType ptype, int length, Random random) {
        return switch (ptype) {
            case I8, U8 -> {
                byte[] a = new byte[length];
                random.nextBytes(a);
                yield a;
            }
            case I16, U16 -> {
                short[] a = new short[length];
                for (int i = 0; i < length; i++) {
                    a[i] = (short) random.nextInt();
                }
                yield a;
            }
            case I32, U32 -> {
                int[] a = new int[length];
                for (int i = 0; i < length; i++) {
                    a[i] = random.nextInt();
                }
                yield a;
            }
            case I64, U64 -> {
                long[] a = new long[length];
                for (int i = 0; i < length; i++) {
                    a[i] = random.nextLong();
                }
                yield a;
            }
            default -> throw new IllegalArgumentException(ptype.toString());
        };
    }
}
