package io.github.dfa1.vortex.reader.layout;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DictByteArray;
import io.github.dfa1.vortex.reader.array.DictShortArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Pins the pool/codes row-validity gather in [DictLayoutDecoder.buildLazyDictPrimitive] (#210).
///
/// Driven through the public [DictLayoutDecoder#decode(LayoutDecodeContext, Layout, DType)] seam
/// with a stub [LayoutDecodeContext] that hands the decoder pre-built values and codes arrays — the
/// closest constructible seam, since the private gather takes already-decoded children and building
/// a real on-disk dict layout fixture would be disproportionate. Shapes mirror real Kepler columns:
/// `koi_gmag` (pool-null) and `koi_smet_err2` (codes-side).
class DictLayoutDecoderTest {

    private static final DType I32 = new DType.Primitive(PType.I32, true);

    @Test
    void poolNull_masksRowsPointingAtInvalidSlot() {
        // Given — pool [10,20,30] with slot 1 invalid; codes route rows 1,3 at the dead slot
        Array values = new MaskedArray(intPool(10, 20, 30), boolArray(true, false, true));
        Array codes = byteCodes(0, 1, 2, 1);

        // When
        Array result = decode(4, I32, values, codes);

        // Then
        MaskedArray masked = assertMasked(result);
        assertValidity(masked, true, false, true, false);
        assertIntValues(masked, 10, 20, 30, 20);
    }

    @Test
    void codesNull_masksRowsWithNullCode() {
        // Given — an all-valid pool but a codes child carrying its own validity (rows 1,3 null)
        Array values = intPool(100, 200);
        Array codes = new MaskedArray(byteCodes(0, 1, 0, 1), boolArray(true, false, true, false));

        // When
        Array result = decode(4, I32, values, codes);

        // Then — the codes mask passes straight through unchanged
        MaskedArray masked = assertMasked(result);
        assertValidity(masked, true, false, true, false);
        assertIntValues(masked, 100, 200, 100, 200);
    }

    @Test
    void bothSides_combineWithAndSemantics() {
        // Given — codes-side null on row 2 AND a pool-null slot 1: a row is valid iff both hold
        Array values = new MaskedArray(intPool(10, 20, 30), boolArray(true, false, true));
        Array codes = new MaskedArray(byteCodes(0, 1, 2, 1), boolArray(true, true, false, true));

        // When
        Array result = decode(4, I32, values, codes);

        // Then — only row 0 survives both masks
        MaskedArray masked = assertMasked(result);
        assertValidity(masked, true, false, false, false);
        assertIntValues(masked, 10, 20, 30, 20);
    }

    @Test
    void codeOutOfRangeWithPoolValidity_throwsVortexException() {
        // Given — pool validity of length 1 and an untrusted code 5 overrunning it; the gather guard
        // must raise a VortexException, not a raw JDK IndexOutOfBoundsException
        Array values = new MaskedArray(intPool(10), boolArray(true));
        Array codes = byteCodes(0, 5);

        // When / Then
        assertThatThrownBy(() -> decode(2, I32, values, codes))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("out of range for pool validity");
    }

    @Test
    void noValidityEitherSide_returnsPlainDictArray() {
        // Given — all-valid pool and plain codes: nothing to mask (no-regression path)
        Array values = intPool(10, 20, 30);
        Array codes = byteCodes(0, 1, 2);

        // When
        Array result = decode(3, I32, values, codes);

        // Then
        assertThat(result).isInstanceOf(IntArray.class).isNotInstanceOf(MaskedArray.class);
    }

    @Test
    void bytePool_buildsDictByteArray() {
        // Given — an I8/U8 dictionary pool: buildLazyDictPrimitive must dispatch to DictByteArray,
        // the narrow-value arm untested until now (real files use byte pools for tiny enum columns).
        DType u8 = new DType.Primitive(PType.U8, true);
        Array values = bytePool(11, 22, 33);
        Array codes = byteCodes(2, 0, 1);

        // When
        Array result = decode(3, u8, values, codes);

        // Then — a lazy DictByteArray resolving each row through its code
        assertThat(result).isInstanceOf(DictByteArray.class);
        ByteArray bytes = (ByteArray) result;
        assertThat(bytes.getByte(0)).isEqualTo((byte) 33);
        assertThat(bytes.getByte(1)).isEqualTo((byte) 11);
        assertThat(bytes.getByte(2)).isEqualTo((byte) 22);
    }

    @Test
    void shortPool_buildsDictShortArray() {
        // Given — an I16/U16 dictionary pool: dispatches to DictShortArray (the 2-byte value arm)
        DType i16 = new DType.Primitive(PType.I16, true);
        Array values = shortPool(-100, 200, 300);
        Array codes = byteCodes(0, 2, 1);

        // When
        Array result = decode(3, i16, values, codes);

        // Then
        assertThat(result).isInstanceOf(DictShortArray.class);
        ShortArray shorts = (ShortArray) result;
        assertThat(shorts.getShort(0)).isEqualTo((short) -100);
        assertThat(shorts.getShort(1)).isEqualTo((short) 300);
        assertThat(shorts.getShort(2)).isEqualTo((short) 200);
    }

    @Test
    void poolNull_gathersShortCodes() {
        // Given — pool-null validity with U16-width codes (a ShortArray): gatherRowValidity's
        // codes-type switch must take the ShortArray arm to read each code before masking.
        Array values = new MaskedArray(intPool(10, 20, 30), boolArray(true, false, true));
        Array codes = shortCodes(0, 1, 2, 1);

        // When
        Array result = decode(4, I32, values, codes);

        // Then — rows referencing the dead slot 1 are null
        MaskedArray masked = assertMasked(result);
        assertValidity(masked, true, false, true, false);
        assertIntValues(masked, 10, 20, 30, 20);
    }

    @Test
    void poolNull_gathersIntCodes() {
        // Given — pool-null validity with U32-width codes (an IntArray): the IntArray gather arm
        Array values = new MaskedArray(intPool(10, 20, 30), boolArray(true, false, true));
        Array codes = intCodes(0, 1, 2, 1);

        // When
        Array result = decode(4, I32, values, codes);

        // Then
        MaskedArray masked = assertMasked(result);
        assertValidity(masked, true, false, true, false);
        assertIntValues(masked, 10, 20, 30, 20);
    }

    @Test
    void poolNull_gathersLongCodes() {
        // Given — pool-null validity with U64-width codes (a LongArray): the LongArray gather arm
        Array values = new MaskedArray(intPool(10, 20, 30), boolArray(true, false, true));
        Array codes = longCodes(0, 1, 2, 1);

        // When
        Array result = decode(4, I32, values, codes);

        // Then
        MaskedArray masked = assertMasked(result);
        assertValidity(masked, true, false, true, false);
        assertIntValues(masked, 10, 20, 30, 20);
    }

    // ── harness ─────────────────────────────────────────────────────────────────

    private static Array decode(long n, DType dtype, Array values, Array codes) {
        // metadata null -> codesPType defaults to U8, matching the byte codes built below
        Layout valuesLayout = new Layout(LayoutId.FLAT, values.length(), null, List.of(), List.of());
        Layout codesLayout = new Layout(LayoutId.FLAT, n, null, List.of(), List.of());
        Layout dictLayout = new Layout(LayoutId.DICT, n, null,
                List.of(valuesLayout, codesLayout), List.of());
        LayoutDecodeContext ctx = new StubContext(valuesLayout, values, codesLayout, codes);
        return new DictLayoutDecoder().decode(ctx, dictLayout, dtype);
    }

    private static IntArray intPool(int... values) {
        return new MaterializedIntArray(I32, values.length, TestSegments.leInts(values));
    }

    private static Array byteCodes(int... codes) {
        byte[] bytes = new byte[codes.length];
        for (int i = 0; i < codes.length; i++) {
            bytes[i] = (byte) codes[i];
        }
        return new MaterializedByteArray(new DType.Primitive(PType.U8, false), codes.length,
                MemorySegment.ofArray(bytes));
    }

    private static Array shortCodes(int... codes) {
        short[] shorts = new short[codes.length];
        for (int i = 0; i < codes.length; i++) {
            shorts[i] = (short) codes[i];
        }
        return new MaterializedShortArray(new DType.Primitive(PType.U16, false), codes.length,
                TestSegments.leShorts(shorts));
    }

    private static Array intCodes(int... codes) {
        return new MaterializedIntArray(new DType.Primitive(PType.U32, false), codes.length,
                TestSegments.leInts(codes));
    }

    private static Array longCodes(long... codes) {
        return new MaterializedLongArray(new DType.Primitive(PType.U64, false), codes.length,
                TestSegments.leLongs(codes));
    }

    private static ByteArray bytePool(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return new MaterializedByteArray(new DType.Primitive(PType.U8, true), values.length,
                MemorySegment.ofArray(bytes));
    }

    private static ShortArray shortPool(int... values) {
        short[] shorts = new short[values.length];
        for (int i = 0; i < values.length; i++) {
            shorts[i] = (short) values[i];
        }
        return new MaterializedShortArray(new DType.Primitive(PType.I16, true), values.length,
                TestSegments.leShorts(shorts));
    }

    private static BoolArray boolArray(boolean... valid) {
        byte[] bytes = new byte[(valid.length + 7) / 8];
        for (int i = 0; i < valid.length; i++) {
            if (valid[i]) {
                bytes[i >>> 3] |= (byte) (1 << (i & 7));
            }
        }
        return new MaterializedBoolArray(DType.BOOL, valid.length, MemorySegment.ofArray(bytes));
    }

    private static MaskedArray assertMasked(Array result) {
        assertThat(result).isInstanceOf(MaskedArray.class);
        return (MaskedArray) result;
    }

    private static void assertValidity(MaskedArray masked, boolean... expected) {
        for (int i = 0; i < expected.length; i++) {
            assertThat(masked.isValid(i)).as("valid row %d", i).isEqualTo(expected[i]);
        }
    }

    private static void assertIntValues(MaskedArray masked, int... expected) {
        IntArray inner = (IntArray) masked.inner();
        for (int i = 0; i < expected.length; i++) {
            assertThat(inner.getInt(i)).as("row %d", i).isEqualTo(expected[i]);
        }
    }

    /// Stub context that hands the decoder pre-built children keyed by layout identity, and
    /// allocates the gathered validity bitmap from a real arena. Segment I/O is never reached on
    /// the primitive dict path, so those methods are unsupported.
    private record StubContext(Layout valuesLayout, Array values, Layout codesLayout, Array codes)
            implements LayoutDecodeContext {

        @Override
        public Array decodeChild(Layout child, DType dtype) {
            return child == valuesLayout ? values : codes;
        }

        @Override
        public Array decodeSegment(SegmentSpec spec, DType dtype, long rowCount) {
            throw new UnsupportedOperationException("not used on the primitive dict path");
        }

        @Override
        public SegmentSpec segmentSpec(int index) {
            throw new UnsupportedOperationException("not used on the primitive dict path");
        }

        @Override
        public SegmentAllocator arena() {
            return Arena.ofAuto();
        }
    }
}
