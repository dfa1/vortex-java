package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LazyConstantArrayTest {

    @Nested
    class LongConstant {

        @Test
        void getLong_returnsBroadcastValue() {
            // Given
            LazyConstantLongArray sut = new LazyConstantLongArray(
                    new DType.Primitive(PType.I64, false), 4, 42L);

            // When / Then — every valid index yields the same value
            assertThat(sut.getLong(0)).isEqualTo(42L);
            assertThat(sut.getLong(3)).isEqualTo(42L);
        }

        @Test
        void fold_appliesValueLengthTimes() {
            // Given — sum of three 7s
            LazyConstantLongArray sut = new LazyConstantLongArray(
                    new DType.Primitive(PType.I64, false), 3, 7L);

            // When
            long sum = sut.fold(0L, Long::sum);

            // Then
            assertThat(sum).isEqualTo(21L);
        }

        @Test
        void getLong_outOfBounds_throws() {
            LazyConstantLongArray sut = new LazyConstantLongArray(
                    new DType.Primitive(PType.I64, false), 2, 1L);
            assertThatThrownBy(() -> sut.getLong(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.getLong(2)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    class IntConstant {
        @Test
        void getInt_returnsBroadcastValue() {
            LazyConstantIntArray sut = new LazyConstantIntArray(
                    new DType.Primitive(PType.I32, false), 2, -5);
            assertThat(sut.getInt(0)).isEqualTo(-5);
            assertThat(sut.getInt(1)).isEqualTo(-5);
        }
    }

    @Nested
    class DoubleConstant {
        @Test
        void getDouble_returnsBroadcastValue() {
            LazyConstantDoubleArray sut = new LazyConstantDoubleArray(
                    new DType.Primitive(PType.F64, false), 3, 3.14);
            assertThat(sut.getDouble(2)).isEqualTo(3.14);
        }
    }

    @Nested
    class FloatConstant {
        @Test
        void getFloat_returnsBroadcastValue() {
            LazyConstantFloatArray sut = new LazyConstantFloatArray(
                    new DType.Primitive(PType.F32, false), 2, 1.5f);
            assertThat(sut.getFloat(0)).isEqualTo(1.5f);
        }
    }

    @Nested
    class ShortConstant {
        @Test
        void getShort_signedAndUnsignedWidening() {
            // Given — high-bit-set raw value
            short raw = (short) 0xFF00;

            // When / Then — signed dtype keeps sign bit
            LazyConstantShortArray signed = new LazyConstantShortArray(
                    new DType.Primitive(PType.I16, false), 1, raw);
            assertThat(signed.getInt(0)).isEqualTo((int) raw);

            // unsigned dtype zero-extends
            LazyConstantShortArray unsigned = new LazyConstantShortArray(
                    new DType.Primitive(PType.U16, false), 1, raw);
            assertThat(unsigned.getInt(0)).isEqualTo(0xFF00);
        }
    }

    @Nested
    class ByteConstant {
        @Test
        void getByte_signedAndUnsignedWidening() {
            byte raw = (byte) 0xF0;
            LazyConstantByteArray signed = new LazyConstantByteArray(
                    new DType.Primitive(PType.I8, false), 1, raw);
            assertThat(signed.getInt(0)).isEqualTo((int) raw);

            LazyConstantByteArray unsigned = new LazyConstantByteArray(
                    new DType.Primitive(PType.U8, false), 1, raw);
            assertThat(unsigned.getInt(0)).isEqualTo(0xF0);
        }
    }

    @Nested
    class BoolConstant {

        @Test
        void getBoolean_returnsBroadcastValue() {
            LazyConstantBoolArray sutTrue = new LazyConstantBoolArray(new DType.Bool(false), 3, true);
            LazyConstantBoolArray sutFalse = new LazyConstantBoolArray(new DType.Bool(false), 3, false);
            assertThat(sutTrue.getBoolean(0)).isTrue();
            assertThat(sutTrue.getBoolean(2)).isTrue();
            assertThat(sutFalse.getBoolean(0)).isFalse();
        }

        @Test
        void getBoolean_outOfBounds_throws() {
            LazyConstantBoolArray sut = new LazyConstantBoolArray(new DType.Bool(false), 2, true);
            assertThatThrownBy(() -> sut.getBoolean(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.getBoolean(2)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }
}
