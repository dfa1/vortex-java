package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VarBinRunEndArrayTest {

    private static final DType UTF8 = DType.UTF8;

    /// Runs `["aa", "b", "cccc"]` ending at absolute 2, 3, 7 — so absolute positions
    /// 0..1 are "aa", 2 is "b", and 3..6 are "cccc". Deliberately three different byte
    /// lengths so a row resolved against the wrong run is visible in `getByteLength`
    /// alone, not just in the bytes.
    private static VarBinRunEndArray sut(long length, long offset) {
        return new VarBinRunEndArray(UTF8, length, values("aa", "b", "cccc"), runEnds(2, 3, 7), offset);
    }

    @Nested
    class Accessors {

        @ParameterizedTest
        @CsvSource({"0,aa", "1,aa", "2,b", "3,cccc", "4,cccc", "5,cccc", "6,cccc"})
        void getString_resolvesRowToItsRunValue(long row, String expected) {
            // Given
            VarBinRunEndArray sut = sut(7, 0);

            // When
            String result = sut.getString(row);

            // Then
            assertThat(result).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({"0,2", "1,2", "2,1", "3,4", "6,4"})
        void getByteLength_resolvesRowToItsRunValueLength(long row, int expected) {
            // Given
            VarBinRunEndArray sut = sut(7, 0);

            // When
            int result = sut.getByteLength(row);

            // Then
            assertThat(result).isEqualTo(expected);
        }

        @Test
        void getBytes_returnsTheRunValueBytes() {
            // Given
            VarBinRunEndArray sut = sut(7, 0);

            // When
            byte[] result = sut.getBytes(3);

            // Then
            assertThat(result).isEqualTo("cccc".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void forEachByteLength_emitsOneLengthPerRowInRunOrder() {
            // Given
            VarBinRunEndArray sut = sut(7, 0);
            List<Integer> result = new ArrayList<>();

            // When
            sut.forEachByteLength(result::add);

            // Then
            assertThat(result).containsExactly(2, 2, 1, 4, 4, 4, 4);
        }

        /// Row indexing is the caller's contract, not untrusted file data: `findRun`
        /// saturates at the last run rather than failing, so without the explicit bound a
        /// past-the-end row would silently return the final run's value.
        @Test
        void getString_pastLastRow_throwsIndexOutOfBounds() {
            // Given
            VarBinRunEndArray sut = sut(7, 0);

            // When / Then
            assertThatThrownBy(() -> sut.getString(7)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    class Offset {

        /// A sliced runend column carries `offset`: logical row `i` resolves against
        /// absolute position `i + offset`, so the window starts mid-run.
        @ParameterizedTest
        @CsvSource({"0,b", "1,cccc", "2,cccc"})
        void getString_resolvesAgainstAbsolutePosition(long row, String expected) {
            // Given — window starts at absolute 2, which is inside run 1 ("b")
            VarBinRunEndArray sut = sut(3, 2);

            // When
            String result = sut.getString(row);

            // Then
            assertThat(result).isEqualTo(expected);
        }

        @Test
        void forEachByteLength_emitsOnlyTheWindowsRows() {
            // Given
            VarBinRunEndArray sut = sut(3, 2);
            List<Integer> result = new ArrayList<>();

            // When
            sut.forEachByteLength(result::add);

            // Then
            assertThat(result).containsExactly(1, 4, 4);
        }
    }

    @Nested
    class NoContiguousBuffer {

        /// Follows the [VarBinChunkedArray] / [VarBinConstantArray] convention: no single
        /// segment holds the expanded rows, so generic consumers must be told to flatten
        /// rather than handed a buffer that only covers the runs.
        @Test
        void segmentIfPresent_isEmpty() {
            // Given
            VarBinRunEndArray sut = sut(7, 0);

            // When / Then
            assertThat(sut.segmentIfPresent()).isEmpty();
        }

        @Test
        void bytesSegment_isTheNullSentinel() {
            // Given
            VarBinRunEndArray sut = sut(7, 0);

            // When / Then
            assertThat(sut.bytesSegment()).isEqualTo(MemorySegment.NULL);
        }

        /// The escape hatch every generic VarBin consumer uses. Flattening must reproduce
        /// exactly the array the old eager `expandStrings` built, which is what keeps
        /// downstream code that needs bytes-plus-offsets working unchanged.
        @Test
        void toOffsetMode_flattensToTheFullyExpandedRows() {
            // Given
            VarBinRunEndArray sut = sut(7, 0);

            // When
            VarBinOffsetArray result = VarBinArray.toOffsetMode(sut, Arena.ofAuto());

            // Then
            assertThat(result.length()).isEqualTo(7L);
            assertThat(strings(result)).containsExactly("aa", "aa", "b", "cccc", "cccc", "cccc", "cccc");
        }
    }

    @Nested
    class Limited {

        @Test
        void limited_shrinksRowCountWithoutTouchingTheRuns() {
            // Given
            VarBinRunEndArray sut = sut(7, 0);

            // When
            VarBinArray result = sut.limited(3);

            // Then — same runs and offset, only the row count changes (zero-copy)
            assertThat(result).isEqualTo(new VarBinRunEndArray(UTF8, 3, sut.values(), sut.runEnds(), 0));
            assertThat(strings(result)).containsExactly("aa", "aa", "b");
        }

        @Test
        void limited_atOrAboveLength_returnsSameInstance() {
            // Given
            VarBinRunEndArray sut = sut(7, 0);

            // When
            VarBinArray result = sut.limited(7);

            // Then
            assertThat(result).isSameAs(sut);
        }
    }

    private static VarBinOffsetArray values(String... runValues) {
        byte[] allBytes = String.join("", runValues).getBytes(StandardCharsets.UTF_8);
        int[] offs = new int[runValues.length + 1];
        for (int i = 0; i < runValues.length; i++) {
            offs[i + 1] = offs[i] + runValues[i].getBytes(StandardCharsets.UTF_8).length;
        }
        ByteBuffer bb = ByteBuffer.allocate(offs.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int o : offs) {
            bb.putInt(o);
        }
        return new VarBinOffsetArray(UTF8, runValues.length, MemorySegment.ofArray(allBytes),
                MemorySegment.ofArray(bb.array()), PType.I32);
    }

    private static Array runEnds(int... ends) {
        ByteBuffer bb = ByteBuffer.allocate(ends.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int e : ends) {
            bb.putInt(e);
        }
        return new MaterializedIntArray(new DType.Primitive(PType.U32, false), ends.length,
                MemorySegment.ofArray(bb.array()));
    }

    private static List<String> strings(VarBinArray array) {
        List<String> out = new ArrayList<>();
        for (long i = 0; i < array.length(); i++) {
            out.add(array.getString(i));
        }
        return out;
    }
}
