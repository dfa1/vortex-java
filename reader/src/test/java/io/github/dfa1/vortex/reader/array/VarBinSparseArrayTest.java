package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;
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

class VarBinSparseArrayTest {

    private static final DType UTF8 = DType.UTF8;

    /// Fill `"zz"` with patches `["b", "cccc"]` at absolute positions 1 and 4. The fill and the
    /// two patches all have different byte lengths, so a row resolved against the wrong slot is
    /// visible in `getByteLength` alone, not only in the bytes.
    private static VarBinSparseArray sut(long length, long offset) {
        return new VarBinSparseArray(UTF8, length, "zz".getBytes(StandardCharsets.UTF_8),
                values("b", "cccc"), indices(1, 4), offset);
    }

    @Nested
    class Accessors {

        @ParameterizedTest
        @CsvSource({"0,zz", "1,b", "2,zz", "3,zz", "4,cccc", "5,zz"})
        void getString_resolvesPatchedRowsToPatchesAndTheRestToTheFill(long row, String expected) {
            // Given
            VarBinSparseArray sut = sut(6, 0);

            // When
            String result = sut.getString(row);

            // Then
            assertThat(result).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({"0,2", "1,1", "2,2", "4,4", "5,2"})
        void getByteLength_matchesTheResolvedValue(long row, int expected) {
            // Given
            VarBinSparseArray sut = sut(6, 0);

            // When
            int result = sut.getByteLength(row);

            // Then
            assertThat(result).isEqualTo(expected);
        }

        @Test
        void getBytes_returnsThePatchBytes() {
            // Given
            VarBinSparseArray sut = sut(6, 0);

            // When
            byte[] result = sut.getBytes(4);

            // Then
            assertThat(result).isEqualTo("cccc".getBytes(StandardCharsets.UTF_8));
        }

        /// [VarBinArray#getBytes(long)]'s contract is a copy per call. The fill is one array
        /// shared by every unpatched row, so handing it out directly would let one row's
        /// mutation leak into all the others.
        @Test
        void getBytes_returnsIndependentCopyOfTheFillEachCall() {
            // Given
            VarBinSparseArray sut = sut(6, 0);

            // When
            byte[] first = sut.getBytes(0);
            first[0] = (byte) 'Q';

            // Then
            assertThat(sut.getBytes(2)).isEqualTo("zz".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void getString_outOfBoundsIndex_throws() {
            // Given
            VarBinSparseArray sut = sut(6, 0);

            // When / Then
            assertThatThrownBy(() -> sut.getString(6)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        /// An empty fill is what a null-fill column carries, since its unpatched rows are
        /// invalid and their bytes never observed. The accessors must still answer for them
        /// rather than assume at least one byte.
        @Test
        void emptyFill_unpatchedRowsAreEmpty() {
            // Given
            VarBinSparseArray sut = new VarBinSparseArray(UTF8, 3, new byte[0],
                    values("b"), indices(1), 0);

            // When / Then
            assertThat(sut.getString(0)).isEmpty();
            assertThat(sut.getByteLength(0)).isZero();
            assertThat(sut.getString(1)).isEqualTo("b");
        }
    }

    @Nested
    class Offset {

        /// A sliced chunk starts partway into the absolute patch positions: with `offset = 2`,
        /// logical row 2 is absolute position 4, the second patch.
        @ParameterizedTest
        @CsvSource({"0,zz", "1,zz", "2,cccc", "3,zz"})
        void getString_rebasesRowsThroughOffset(long row, String expected) {
            // Given
            VarBinSparseArray sut = sut(4, 2);

            // When
            String result = sut.getString(row);

            // Then
            assertThat(result).isEqualTo(expected);
        }
    }

    @Nested
    class Sequential {

        @Test
        void forEachByteLength_emitsFillAndPatchLengthsInRowOrder() {
            // Given
            VarBinSparseArray sut = sut(6, 0);
            List<Integer> lengths = new ArrayList<>();

            // When
            sut.forEachByteLength(lengths::add);

            // Then
            assertThat(lengths).containsExactly(2, 1, 2, 2, 4, 2);
        }

        /// The walk and the per-row binary search are two independent resolutions of the same
        /// data; a disagreement between them is the bug this guards.
        @Test
        void forEachByteLength_agreesWithGetByteLength() {
            // Given
            VarBinSparseArray sut = sut(6, 0);
            List<Integer> walked = new ArrayList<>();
            List<Integer> searched = new ArrayList<>();

            // When
            sut.forEachByteLength(walked::add);
            for (long i = 0; i < sut.length(); i++) {
                searched.add(sut.getByteLength(i));
            }

            // Then
            assertThat(walked).isEqualTo(searched);
        }

        /// The patch indices come from an untrusted file and the walk assumes them sorted;
        /// unsorted ones must fail as a [VortexException], never a raw JDK exception (ADR 0003).
        @Test
        void forEachByteLength_unsortedPatchIndices_throws() {
            // Given — patches at 4 then 1, going backwards
            VarBinSparseArray sut = new VarBinSparseArray(UTF8, 6, "zz".getBytes(StandardCharsets.UTF_8),
                    values("b", "cccc"), indices(4, 1), 0);

            // When / Then
            assertThatThrownBy(() -> sut.forEachByteLength(len -> { }))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("not sorted");
        }
    }

    @Nested
    class Representation {

        /// No single contiguous buffer holds the resolved rows, so generic consumers must be
        /// steered to [VarBinArray#toOffsetMode] rather than handed a bytes segment.
        @Test
        void bytesSegment_isNullAndSegmentIfPresentIsEmpty() {
            // Given
            VarBinSparseArray sut = sut(6, 0);

            // When / Then
            assertThat(sut.bytesSegment()).isEqualTo(MemorySegment.NULL);
            assertThat(sut.segmentIfPresent()).isEmpty();
        }

        /// The flattening path every consumer that needs bytes-plus-offsets goes through.
        @Test
        void toOffsetMode_flattensFillAndPatchesInRowOrder() {
            // Given
            VarBinSparseArray sut = sut(6, 0);

            // When
            VarBinArray result = VarBinArray.toOffsetMode(sut, Arena.ofAuto());

            // Then
            assertThat(strings(result)).containsExactly("zz", "b", "zz", "zz", "cccc", "zz");
        }

        @Test
        void limited_keepsPatchesAndShrinksOnlyTheRowCount() {
            // Given
            VarBinSparseArray sut = sut(6, 0);

            // When
            VarBinArray result = sut.limited(3);

            // Then — zero-copy: same fill, patches, indices and offset
            assertThat(result).isEqualTo(new VarBinSparseArray(UTF8, 3, sut.fill(),
                    sut.patchValues(), sut.patchIndices(), 0));
            assertThat(strings(result)).containsExactly("zz", "b", "zz");
        }

        @Test
        void limited_atOrAboveLength_returnsSameInstance() {
            // Given
            VarBinSparseArray sut = sut(6, 0);

            // When
            VarBinArray result = sut.limited(6);

            // Then
            assertThat(result).isSameAs(sut);
        }
    }

    private static VarBinOffsetArray values(String... patchValues) {
        byte[] allBytes = String.join("", patchValues).getBytes(StandardCharsets.UTF_8);
        int[] offs = new int[patchValues.length + 1];
        for (int i = 0; i < patchValues.length; i++) {
            offs[i + 1] = offs[i] + patchValues[i].getBytes(StandardCharsets.UTF_8).length;
        }
        ByteBuffer bb = ByteBuffer.allocate(offs.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int o : offs) {
            bb.putInt(o);
        }
        return new VarBinOffsetArray(UTF8, patchValues.length, MemorySegment.ofArray(allBytes),
                MemorySegment.ofArray(bb.array()), PType.I32);
    }

    private static Array indices(int... positions) {
        ByteBuffer bb = ByteBuffer.allocate(positions.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int p : positions) {
            bb.putInt(p);
        }
        return new MaterializedIntArray(new DType.Primitive(PType.U32, false), positions.length,
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
