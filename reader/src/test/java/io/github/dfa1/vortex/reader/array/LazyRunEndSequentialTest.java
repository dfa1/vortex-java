package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.io.VortexFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// The sequential traversal of a run-end array must agree, element for element, with resolving
/// each position independently.
///
/// `forEachShort`/`forEachByte` and the `materialize` defaults walk runs — one binary search for
/// the whole scan — while `getShort`/`getByte` binary-search per position. That is the whole point
/// of the optimization, and also the way it can silently go wrong: an off-by-one in the run walk
/// shifts values across a run boundary, and a mishandled `offset` drops or duplicates the first
/// run. Both produce a plausible-looking array that no round-trip test would flag, so the two
/// paths are compared directly here rather than against a hardcoded expectation.
class LazyRunEndSequentialTest {

    // Runs of length 2, 1, 4 -> values 10, 20, 30 at absolute positions 0..1, 2, 3..6. Uneven run
    // lengths (including a run of exactly 1) so a boundary slip is visible, not absorbed by
    // equal-sized runs.
    private static final int[] ENDS = {2, 3, 7};
    private static final short[] VALUES = {10, 20, 30};

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2, 3})
    void forEachShort_andMaterialize_matchPerPositionGetShort(long offset) {
        // Given — a run-end short array sliced at `offset`, covering the rest of the runs
        long length = 7 - offset;
        LazyRunEndShortArray sut = new LazyRunEndShortArray(
                new DType.Primitive(PType.I16, false), length, values(), runEnds(), offset);
        List<Short> byIndex = new ArrayList<>();
        for (long i = 0; i < length; i++) {
            byIndex.add(sut.getShort(i));
        }

        // When
        List<Short> result = new ArrayList<>();
        sut.forEachShort(result::add);

        // Then
        assertThat(result).containsExactlyElementsOf(byIndex);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = sut.materialize(arena);
            List<Short> materialized = new ArrayList<>();
            for (long i = 0; i < length; i++) {
                materialized.add(seg.getAtIndex(VortexFormat.LE_SHORT, i));
            }
            assertThat(materialized).containsExactlyElementsOf(byIndex);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2, 3})
    void forEachByte_andMaterialize_matchPerPositionGetByte(long offset) {
        // Given
        long length = 7 - offset;
        LazyRunEndByteArray sut = new LazyRunEndByteArray(
                new DType.Primitive(PType.I8, false), length, byteValues(), runEnds(), offset);
        List<Byte> byIndex = new ArrayList<>();
        for (long i = 0; i < length; i++) {
            byIndex.add(sut.getByte(i));
        }

        // When
        List<Byte> result = new ArrayList<>();
        sut.forEachByte(result::add);

        // Then
        assertThat(result).containsExactlyElementsOf(byIndex);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = sut.materialize(arena);
            List<Byte> materialized = new ArrayList<>();
            for (long i = 0; i < length; i++) {
                materialized.add(seg.get(ValueLayout.JAVA_BYTE, i));
            }
            assertThat(materialized).containsExactlyElementsOf(byIndex);
        }
    }

    private static ShortArray values() {
        ByteBuffer bb = ByteBuffer.allocate(VALUES.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : VALUES) {
            bb.putShort(v);
        }
        return new MaterializedShortArray(new DType.Primitive(PType.I16, false), VALUES.length,
                MemorySegment.ofArray(bb.array()));
    }

    private static ByteArray byteValues() {
        byte[] raw = new byte[VALUES.length];
        for (int i = 0; i < VALUES.length; i++) {
            raw[i] = (byte) VALUES[i];
        }
        return new MaterializedByteArray(new DType.Primitive(PType.I8, false), raw.length,
                MemorySegment.ofArray(raw));
    }

    private static Array runEnds() {
        ByteBuffer bb = ByteBuffer.allocate(ENDS.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int e : ENDS) {
            bb.putInt(e);
        }
        return new MaterializedIntArray(new DType.Primitive(PType.U32, false), ENDS.length,
                MemorySegment.ofArray(bb.array()));
    }
}
