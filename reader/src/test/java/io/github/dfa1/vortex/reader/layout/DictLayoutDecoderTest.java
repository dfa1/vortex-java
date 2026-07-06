package io.github.dfa1.vortex.reader.layout;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.encoding.TestSegments;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
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
