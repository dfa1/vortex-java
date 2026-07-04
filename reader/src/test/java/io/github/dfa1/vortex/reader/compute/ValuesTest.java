package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.GenericArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/// Unit test for the boxing value accessor [Values] — the correctness baseline every generic
/// compute path reads through. Pins the branches the kernel oracles reach only incidentally: the
/// zero-extension of narrow unsigned columns (a `u8` `0xFF` must box as `255`, never `-1`), the
/// non-numeric boxes (Utf8 → [String], bool → [Boolean]), the extension-dtype passthrough of the
/// integer widening, and the fail-fast on an array type with no scalar accessor.
class ValuesTest {

    private static final Arena ARENA = Arena.ofAuto();

    @Test
    void unsignedNarrowColumnsZeroExtend() {
        // Given the all-ones bit pattern in u8 / u16 / u32 columns — sign-extended by the raw
        // accessors, so boxing must zero-extend or the value reads back negative
        Array u8 = byteArray(DType.U8, (byte) 0xFF);
        Array u16 = shortArray(DType.U16, (short) 0xFFFF);
        Array u32 = intArray(DType.U32, 0xFFFFFFFF);

        // When each value is boxed
        Object result8 = Values.valueAt(u8, 0);
        Object result16 = Values.valueAt(u16, 0);
        Object result32 = Values.valueAt(u32, 0);

        // Then the boxes carry the unsigned magnitude
        assertThat(result8).isEqualTo(255L);
        assertThat(result16).isEqualTo(65535L);
        assertThat(result32).isEqualTo(4294967295L);
    }

    @Test
    void signedNarrowColumnsSignExtend() {
        // Given the same all-ones bit pattern in the signed narrow columns
        Array i8 = byteArray(DType.I8, (byte) 0xFF);
        Array i16 = shortArray(DType.I16, (short) 0xFFFF);

        // When each value is boxed
        Object result8 = Values.valueAt(i8, 0);
        Object result16 = Values.valueAt(i16, 0);

        // Then the boxes keep the signed value
        assertThat(result8).isEqualTo(-1L);
        assertThat(result16).isEqualTo(-1L);
    }

    @Test
    void extensionDtypePassesIntegerThrough() {
        // Given an integer column whose dtype is an Extension (not a Primitive) — the unsigned
        // widening must pass the raw value through rather than assume a primitive ptype
        DType extension = new DType.Extension("test.ext", DType.I32, null, false);
        MemorySegment seg = ARENA.allocate(4, 4);
        seg.setAtIndex(VortexFormat.LE_INT, 0, -7);
        Array column = new MaterializedIntArray(extension, 1, seg);

        // When the value is boxed
        Object result = Values.valueAt(column, 0);

        // Then it carries the raw signed value unchanged
        assertThat(result).isEqualTo(-7L);
    }

    @Test
    void utf8AndBoolBoxNaturally() {
        // Given a Utf8 column and a bool column
        Array utf8 = ComputeArrays.utf8Array(ARENA, "alpha", "beta");
        Array bool = ComputeArrays.boolArray(ARENA, true, false);

        // When the values are boxed
        Object result = Values.valueAt(utf8, 1);
        Object resultBool = Values.valueAt(bool, 1);

        // Then Utf8 boxes as String and bool as Boolean
        assertThat(result).isEqualTo("beta");
        assertThat(resultBool).isEqualTo(false);
    }

    @Test
    void arrayWithoutScalarAccessorFailsFast() {
        // Given a GenericArray — the fallback array type with no scalar accessor for compute
        Array generic = new GenericArray(DType.I64, 1, ARENA.allocate(8, 8));

        // When a value read is attempted
        // Then the accessor fails fast instead of returning garbage
        assertThatExceptionOfType(VortexException.class)
                .isThrownBy(() -> Values.valueAt(generic, 0))
                .withMessageContaining("no scalar accessor");
    }

    @Test
    void maskedArrayReadsThroughItsPayload() {
        // Given a masked narrow column with one null position
        Array masked = new MaskedArray(byteArray(DType.U8, (byte) 42, (byte) 7),
                ComputeArrays.boolArray(ARENA, true, false));

        // When the valid position is boxed and both positions are null-tested
        Object result = Values.valueAt(masked, 0);

        // Then the payload boxes through the mask and the validity drives the null test
        assertThat(result).isEqualTo(42L);
        assertThat(Values.isNullAt(masked, 0)).isFalse();
        assertThat(Values.isNullAt(masked, 1)).isTrue();
    }

    private static Array byteArray(DType dtype, byte... values) {
        MemorySegment seg = ARENA.allocate(Math.max(1L, values.length));
        for (int i = 0; i < values.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, values[i]);
        }
        return new MaterializedByteArray(dtype, values.length, seg);
    }

    private static Array shortArray(DType dtype, short... values) {
        MemorySegment seg = ARENA.allocate(Math.max(2L, values.length * 2L), 2);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(VortexFormat.LE_SHORT, i, values[i]);
        }
        return new MaterializedShortArray(dtype, values.length, seg);
    }

    private static Array intArray(DType dtype, int... values) {
        MemorySegment seg = ARENA.allocate(Math.max(4L, values.length * 4L), 4);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(VortexFormat.LE_INT, i, values[i]);
        }
        return new MaterializedIntArray(dtype, values.length, seg);
    }
}
