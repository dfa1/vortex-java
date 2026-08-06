package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LazySequenceArrayTest {

    private static DType primitive(PType ptype) {
        return new DType.Primitive(ptype, false);
    }

    @Nested
    class Longs {

        @Test
        void getLong_appliesTheFormula() {
            // Given
            LazySequenceLongArray sut = new LazySequenceLongArray(primitive(PType.I64), 5, 100, -7);

            // When / Then
            assertThat(sut.getLong(0)).isEqualTo(100L);
            assertThat(sut.getLong(3)).isEqualTo(79L);
        }

        @Test
        void forEachLong_emitsEveryRowInOrder() {
            // Given
            LazySequenceLongArray sut = new LazySequenceLongArray(primitive(PType.I64), 4, 1, 10);
            List<Long> result = new ArrayList<>();

            // When
            sut.forEachLong(result::add);

            // Then
            assertThat(result).containsExactly(1L, 11L, 21L, 31L);
        }

        @Test
        void fold_matchesTheSumOfEveryRow() {
            // Given
            LazySequenceLongArray sut = new LazySequenceLongArray(primitive(PType.I64), 4, 1, 10);

            // When
            long result = sut.fold(0L, Long::sum);

            // Then — 1 + 11 + 21 + 31
            assertThat(result).isEqualTo(64L);
        }

        @Test
        void getLong_pastLastRow_throwsIndexOutOfBounds() {
            // Given
            LazySequenceLongArray sut = new LazySequenceLongArray(primitive(PType.I64), 5, 0, 1);

            // When / Then
            assertThatThrownBy(() -> sut.getLong(5)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        /// The whole point of the encoding: a huge row count costs nothing to represent, so
        /// `materialize` is the only place an allocation can happen, on demand.
        @Test
        void hugeRowCount_costsNoAllocation() {
            // Given
            LazySequenceLongArray sut = new LazySequenceLongArray(primitive(PType.I64), Integer.MAX_VALUE, 0, 1);

            // When / Then
            assertThat(sut.length()).isEqualTo(Integer.MAX_VALUE);
            assertThat(sut.getLong(2_000_000_000L)).isEqualTo(2_000_000_000L);
        }
    }

    @Nested
    class Truncation {

        @Test
        void limited_keepsTheFormulaAndShrinksTheRowCount() {
            // Given
            LazySequenceLongArray sut = new LazySequenceLongArray(primitive(PType.I64), 100, 5, 2);

            // When
            Array result = sut.limited(3);

            // Then
            assertThat(result).isEqualTo(new LazySequenceLongArray(primitive(PType.I64), 3, 5, 2));
            assertThat(((LongArray) result).getLong(2)).isEqualTo(9L);
        }

        @Test
        void limited_atOrAboveLength_returnsSameInstance() {
            // Given
            LazySequenceLongArray sut = new LazySequenceLongArray(primitive(PType.I64), 10, 0, 1);

            // When
            Array result = sut.limited(10);

            // Then
            assertThat(result).isSameAs(sut);
        }
    }

    @Nested
    class UnsignedWidening {

        /// `fold` widens through the column's ptype, so a U8 sequence stepping past 127 must
        /// accumulate 128, not -128. The signed reading silently halves large sums.
        @Test
        void byteFold_zeroExtendsForU8() {
            // Given — rows 0..2 are 126, 127, 128 (the last wraps negative as a raw byte)
            LazySequenceByteArray sut = new LazySequenceByteArray(primitive(PType.U8), 3, 126, 1);

            // When
            long result = sut.fold(0L, Long::sum);

            // Then
            assertThat(result).isEqualTo(381L);
        }

        @Test
        void byteFold_staysSignedForI8() {
            // Given — same raw bytes, read as signed: 126 + 127 + (-128)
            LazySequenceByteArray sut = new LazySequenceByteArray(primitive(PType.I8), 3, 126, 1);

            // When
            long result = sut.fold(0L, Long::sum);

            // Then
            assertThat(result).isEqualTo(125L);
        }

        @Test
        void shortFold_zeroExtendsForU16() {
            // Given — rows 0..1 are 32767, 32768 (the second wraps negative as a raw short)
            LazySequenceShortArray sut = new LazySequenceShortArray(primitive(PType.U16), 2, 32767, 1);

            // When
            long result = sut.fold(0L, Long::sum);

            // Then
            assertThat(result).isEqualTo(65535L);
        }

        /// `getInt` widening comes from the [ByteArray] interface default rather than an
        /// override, so pin it here: a U8 row past 127 must read as positive.
        @Test
        void byteGetInt_zeroExtendsForU8() {
            // Given
            LazySequenceByteArray sut = new LazySequenceByteArray(primitive(PType.U8), 3, 126, 1);

            // When / Then
            assertThat(sut.getInt(2)).isEqualTo(128);
            assertThat(sut.getByte(2)).isEqualTo((byte) -128);
        }
    }

    @Nested
    class HalfPrecision {

        /// 0.1f is not representable in half precision, so the eager decode's stored value and
        /// the value read back differ from the plain float arithmetic. The lazy carrier must
        /// round-trip identically or F16 sequences shift under it.
        @Test
        void getFloat_roundTripsThroughHalfPrecision() {
            // Given
            LazySequenceFloat16Array sut = new LazySequenceFloat16Array(primitive(PType.F16), 5, 0f, 0.1f);

            // When
            float result = sut.getFloat(3);

            // Then
            assertThat(result).isEqualTo(Float.float16ToFloat(Float.floatToFloat16(0.3f)));
        }

        /// [Float16Array] declares no default `materialize`, so this carrier supplies one; it
        /// must produce the same little-endian half-precision buffer the eager decode wrote.
        @Test
        void materialize_writesLittleEndianHalfPrecision() {
            // Given
            LazySequenceFloat16Array sut = new LazySequenceFloat16Array(primitive(PType.F16), 3, 1.5f, 0.25f);

            // When
            MemorySegment result = sut.materialize(Arena.ofAuto());

            // Then
            assertThat(result.byteSize()).isEqualTo(6L);
            assertThat(result.getAtIndex(VortexFormat.LE_SHORT, 0)).isEqualTo(Float.floatToFloat16(1.5f));
            assertThat(result.getAtIndex(VortexFormat.LE_SHORT, 2)).isEqualTo(Float.floatToFloat16(2.0f));
        }
    }

    @Nested
    class Floats {

        /// Arithmetic stays in `float`, matching the eager decode — a `double` accumulator
        /// would produce different bits for steps that are not exactly representable.
        @Test
        void getFloat_staysInSinglePrecision() {
            // Given
            LazySequenceFloatArray sut = new LazySequenceFloatArray(primitive(PType.F32), 10, 0f, 0.1f);

            // When
            float result = sut.getFloat(3);

            // Then
            assertThat(result).isEqualTo(0f + 3 * 0.1f);
        }

        @Test
        void doubleFold_matchesTheSumOfEveryRow() {
            // Given
            LazySequenceDoubleArray sut = new LazySequenceDoubleArray(primitive(PType.F64), 4, 1.0, 0.5);

            // When
            double result = sut.fold(0.0, Double::sum);

            // Then
            assertThat(result).isEqualTo(7.0);
        }
    }
}
