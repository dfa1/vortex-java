package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.PTypeIO;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LazyConstantArrayTest {

    @Nested
    class LongConstant {
        private final LazyConstantLongArray sut =
                new LazyConstantLongArray(DType.I64, 4, 42L);

        @Test
        void getLong_returnsBroadcastValue() {
            // When / Then — every valid index yields the same value
            assertThat(sut.getLong(0)).isEqualTo(42L);
            assertThat(sut.getLong(3)).isEqualTo(42L);
        }

        @Test
        void forEachLong_visitsValueOncePerRow() {
            // Given
            AtomicLong sum = new AtomicLong();
            AtomicLong count = new AtomicLong();

            // When
            sut.forEachLong(v -> {
                sum.addAndGet(v);
                count.incrementAndGet();
            });

            // Then
            assertThat(count.get()).isEqualTo(4);
            assertThat(sum.get()).isEqualTo(168L);
        }

        @Test
        void fold_appliesValueLengthTimes() {
            // When
            long result = new LazyConstantLongArray(DType.I64, 3, 7L)
                    .fold(0L, Long::sum);

            // Then
            assertThat(result).isEqualTo(21L);
        }

        @Test
        void getLong_outOfBounds_throws() {
            // When / Then
            assertThatThrownBy(() -> sut.getLong(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.getLong(4)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    class IntConstant {
        private final LazyConstantIntArray sut =
                new LazyConstantIntArray(DType.I32, 3, -5);

        @Test
        void getInt_returnsBroadcastValue() {
            assertThat(sut.getInt(0)).isEqualTo(-5);
            assertThat(sut.getInt(2)).isEqualTo(-5);
        }

        @Test
        void forEachInt_visitsValueOncePerRow() {
            // Given
            AtomicLong sum = new AtomicLong();

            // When
            sut.forEachInt(sum::addAndGet);

            // Then
            assertThat(sum.get()).isEqualTo(-15L);
        }

        @Test
        void fold_appliesValueLengthTimes() {
            // When
            int result = sut.fold(0, Integer::sum);

            // Then
            assertThat(result).isEqualTo(-15);
        }

        @Test
        void getInt_outOfBounds_throws() {
            assertThatThrownBy(() -> sut.getInt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.getInt(3)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    class ShortConstant {
        private static final short RAW = (short) 0xFF00; // high bit set

        @Test
        void getShort_returnsBroadcastValue() {
            LazyConstantShortArray sut = new LazyConstantShortArray(DType.I16, 2, RAW);
            assertThat(sut.getShort(0)).isEqualTo(RAW);
            assertThat(sut.getShort(1)).isEqualTo(RAW);
        }

        @Test
        void getInt_signedKeepsSign_unsignedZeroExtends() {
            assertThat(new LazyConstantShortArray(DType.I16, 1, RAW).getInt(0))
                    .isEqualTo((int) RAW);
            assertThat(new LazyConstantShortArray(DType.U16, 1, RAW).getInt(0))
                    .isEqualTo(0xFF00);
        }

        @Test
        void fold_signedAndUnsignedWidening() {
            // signed short value is negative; unsigned is 0xFF00
            assertThat(new LazyConstantShortArray(DType.I16, 2, RAW)
                    .fold(0L, Long::sum)).isEqualTo(2L * RAW);
            assertThat(new LazyConstantShortArray(DType.U16, 2, RAW)
                    .fold(0L, Long::sum)).isEqualTo(2L * 0xFF00);
        }

        @Test
        void nonPrimitiveDtype_treatedAsSigned() {
            // Given — a non-Primitive dtype hits the defensive `instanceof` false branch
            LazyConstantShortArray sut = new LazyConstantShortArray(DType.BOOL, 2, RAW);

            // When / Then — falls back to signed widening
            assertThat(sut.getInt(0)).isEqualTo((int) RAW);
            assertThat(sut.fold(0L, Long::sum)).isEqualTo(2L * RAW);
        }

        @Test
        void outOfBounds_throws() {
            LazyConstantShortArray sut = new LazyConstantShortArray(DType.I16, 1, RAW);
            assertThatThrownBy(() -> sut.getShort(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.getShort(1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.getInt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.getInt(1)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    class ByteConstant {
        private static final byte RAW = (byte) 0xF0; // high bit set

        @Test
        void getByte_returnsBroadcastValue() {
            LazyConstantByteArray sut = new LazyConstantByteArray(DType.I8, 2, RAW);
            assertThat(sut.getByte(0)).isEqualTo(RAW);
            assertThat(sut.getByte(1)).isEqualTo(RAW);
        }

        @Test
        void getInt_signedKeepsSign_unsignedZeroExtends() {
            assertThat(new LazyConstantByteArray(DType.I8, 1, RAW).getInt(0))
                    .isEqualTo((int) RAW);
            assertThat(new LazyConstantByteArray(DType.U8, 1, RAW).getInt(0))
                    .isEqualTo(0xF0);
        }

        @Test
        void fold_signedAndUnsignedWidening() {
            assertThat(new LazyConstantByteArray(DType.I8, 2, RAW)
                    .fold(0L, Long::sum)).isEqualTo(2L * RAW);
            assertThat(new LazyConstantByteArray(DType.U8, 2, RAW)
                    .fold(0L, Long::sum)).isEqualTo(2L * 0xF0);
        }

        @Test
        void nonPrimitiveDtype_treatedAsSigned() {
            // Given — a non-Primitive dtype hits the defensive `instanceof` false branch
            LazyConstantByteArray sut = new LazyConstantByteArray(DType.BOOL, 2, RAW);

            // When / Then — falls back to signed widening
            assertThat(sut.getInt(0)).isEqualTo((int) RAW);
            assertThat(sut.fold(0L, Long::sum)).isEqualTo(2L * RAW);
        }

        @Test
        void outOfBounds_throws() {
            LazyConstantByteArray sut = new LazyConstantByteArray(DType.I8, 1, RAW);
            assertThatThrownBy(() -> sut.getByte(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.getByte(1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.getInt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.getInt(1)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    class DoubleConstant {
        private final LazyConstantDoubleArray sut =
                new LazyConstantDoubleArray(DType.F64, 3, 3.14);

        @Test
        void getDouble_returnsBroadcastValue() {
            assertThat(sut.getDouble(0)).isEqualTo(3.14);
            assertThat(sut.getDouble(2)).isEqualTo(3.14);
        }

        @Test
        void forEachDouble_visitsValueOncePerRow() {
            // Given
            double[] acc = {0.0};
            int[] count = {0};

            // When
            sut.forEachDouble(v -> {
                acc[0] += v;
                count[0]++;
            });

            // Then
            assertThat(count[0]).isEqualTo(3);
            assertThat(acc[0]).isEqualTo(9.42, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        void fold_appliesValueLengthTimes() {
            assertThat(sut.fold(0.0, Double::sum)).isEqualTo(9.42, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        void getDouble_outOfBounds_throws() {
            assertThatThrownBy(() -> sut.getDouble(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.getDouble(3)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    class FloatConstant {
        private final LazyConstantFloatArray sut =
                new LazyConstantFloatArray(DType.F32, 2, 1.5f);

        @Test
        void getFloat_returnsBroadcastValue() {
            assertThat(sut.getFloat(0)).isEqualTo(1.5f);
            assertThat(sut.getFloat(1)).isEqualTo(1.5f);
        }

        @Test
        void fold_appliesValueLengthTimes() {
            assertThat(sut.fold(0.0, Double::sum)).isEqualTo(3.0);
        }

        @Test
        void getFloat_outOfBounds_throws() {
            assertThatThrownBy(() -> sut.getFloat(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.getFloat(2)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    class BoolConstant {
        @Test
        void getBoolean_returnsBroadcastValue() {
            assertThat(new LazyConstantBoolArray(DType.BOOL, 3, true).getBoolean(2)).isTrue();
            assertThat(new LazyConstantBoolArray(DType.BOOL, 3, false).getBoolean(0)).isFalse();
        }

        @Test
        void forEachBoolean_visitsValueOncePerRow() {
            // Given
            int[] trueCount = {0};
            int[] total = {0};

            // When
            new LazyConstantBoolArray(DType.BOOL, 3, true).forEachBoolean(v -> {
                if (v) {
                    trueCount[0]++;
                }
                total[0]++;
            });

            // Then
            assertThat(total[0]).isEqualTo(3);
            assertThat(trueCount[0]).isEqualTo(3);
        }

        @Test
        void getBoolean_outOfBounds_throws() {
            LazyConstantBoolArray sut = new LazyConstantBoolArray(DType.BOOL, 2, true);
            assertThatThrownBy(() -> sut.getBoolean(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.getBoolean(2)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    class DecimalConstant {
        private final DType dtype = new DType.Decimal((byte) 15, (byte) 2, false);
        private final BigDecimal value = new BigDecimal(BigInteger.valueOf(4321), 2); // 43.21

        @Test
        void getDecimal_returnsBroadcastValue() {
            LazyConstantDecimalArray sut = new LazyConstantDecimalArray(dtype, 3, value, 8);
            assertThat(sut.getDecimal(0)).isEqualTo(value);
            assertThat(sut.getDecimal(2)).isEqualTo(value);
        }

        @Test
        void getDecimal_outOfBounds_throws() {
            LazyConstantDecimalArray sut = new LazyConstantDecimalArray(dtype, 2, value, 8);
            assertThatThrownBy(() -> sut.getDecimal(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.getDecimal(2)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        void limited_narrowsRowCountKeepingValue() {
            // When
            Array result = new LazyConstantDecimalArray(dtype, 10, value, 8).limited(3);

            // Then
            assertThat(result).isInstanceOf(LazyConstantDecimalArray.class);
            LazyConstantDecimalArray narrowed = (LazyConstantDecimalArray) result;
            assertThat(narrowed.length()).isEqualTo(3);
            assertThat(narrowed.getDecimal(0)).isEqualTo(value);
        }

        @Test
        void materialize_writesLittleEndianTwosComplement_forEachByteWidth() {
            try (Arena arena = Arena.ofConfined()) {
                assertThat(readByte(arena)).containsExactly(4321 & 0xFF, 4321 & 0xFF);
                assertThat(readShort(arena)).containsExactly((short) 4321, (short) 4321);
                assertThat(readInt(arena)).containsExactly(4321, 4321);
                assertThat(readLong(arena)).containsExactly(4321L, 4321L);
            }
        }

        @Test
        void materialize_unsupportedByteWidth_throws() {
            try (Arena arena = Arena.ofConfined()) {
                LazyConstantDecimalArray sut = new LazyConstantDecimalArray(dtype, 1, value, 16);

                // When / Then
                assertThatThrownBy(() -> sut.materialize(arena))
                        .isInstanceOf(VortexException.class)
                        .hasMessageContaining("unsupported byteWidth");
            }
        }

        private int[] readByte(Arena arena) {
            MemorySegment seg = new LazyConstantDecimalArray(dtype, 2, value, 1).materialize(arena);
            return new int[]{seg.get(ValueLayout.JAVA_BYTE, 0) & 0xFF, seg.get(ValueLayout.JAVA_BYTE, 1) & 0xFF};
        }

        private short[] readShort(Arena arena) {
            MemorySegment seg = new LazyConstantDecimalArray(dtype, 2, value, 2).materialize(arena);
            return new short[]{seg.get(PTypeIO.LE_SHORT, 0), seg.get(PTypeIO.LE_SHORT, 2)};
        }

        private int[] readInt(Arena arena) {
            MemorySegment seg = new LazyConstantDecimalArray(dtype, 2, value, 4).materialize(arena);
            return new int[]{seg.get(PTypeIO.LE_INT, 0), seg.get(PTypeIO.LE_INT, 4)};
        }

        private long[] readLong(Arena arena) {
            MemorySegment seg = new LazyConstantDecimalArray(dtype, 2, value, 8).materialize(arena);
            return new long[]{seg.get(PTypeIO.LE_LONG, 0), seg.get(PTypeIO.LE_LONG, 8)};
        }
    }
}
