package io.github.dfa1.vortex.reader.layout;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DictByteArray;
import io.github.dfa1.vortex.reader.array.DictShortArray;
import io.github.dfa1.vortex.reader.array.FixedSizeListArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinConstantArray;
import io.github.dfa1.vortex.reader.array.VarBinDictArray;
import io.github.dfa1.vortex.reader.array.VarBinOffsetArray;
import io.github.dfa1.vortex.reader.array.VarBinViewArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Pins the pool/codes row-validity gather in [DictLayoutDecoder.buildLazyDictPrimitive] (#210)
/// and the values-pool shape dispatch (#341).
///
/// Driven through the public [DictLayoutDecoder#decode(LayoutDecodeContext, Layout, DType)] seam
/// with a stub [LayoutDecodeContext] that hands the decoder pre-built values and codes arrays — the
/// closest constructible seam, since the private gather takes already-decoded children and building
/// a real on-disk dict layout fixture would be disproportionate. Primitive shapes mirror real Kepler
/// columns: `koi_gmag` (pool-null) and `koi_smet_err2` (codes-side).
class DictLayoutDecoderTest {

    private static final DType I32 = new DType.Primitive(PType.I32, true);
    private static final int VIEW_SIZE = 16;

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("varBinPools")
    void varBinPool_resolvesLazily(String name, DType dtype, Array values,
            Class<? extends Array> expectedType, String[] expected) {
        // Given — a VarBin-shaped dict pool. Before #341 only a bare VarBinOffsetArray pool took
        // the lazy branch: a StringView pool, or a `vortex.constant` pool (plausible for a dict
        // whose distinct values collapse to one), fell through to a full per-row expansion.
        Array codes = byteCodes(2, 0, 1, 0);

        // When
        Array result = decode(4, dtype, values, codes);

        // Then — a lazy carrier resolving each row on access, never an expanded buffer
        assertThat(result).as(name).isInstanceOf(expectedType);
        assertStrings(result, expected);
    }

    @Test
    void maskedVarBinPool_appliesPoolValidity() {
        // Given — a nullable `vortex.constant` pool with slot 1 dead. Pre-#341 this combination
        // reached the fallback, which cast the still-masked `values` to VarBinArray and blew up
        // with a raw ClassCastException; even unmasked it dropped pool validity on the floor.
        Array values = new MaskedArray(constantPool(DType.UTF8, "zz", 3), boolArray(true, false, true));
        Array codes = byteCodes(0, 1, 2, 1);

        // When
        Array result = decode(4, DType.UTF8, values, codes);

        // Then — rows pointing at the dead slot are null, values still resolve lazily
        MaskedArray masked = assertMasked(result);
        assertValidity(masked, true, false, true, false);
        assertThat(masked.inner()).isInstanceOf(VarBinConstantArray.class);
        assertStrings(masked.inner(), "zz", "zz", "zz", "zz");
    }

    @Test
    void hugeConstantPool_resolvesWithoutWalkingIt() {
        // Given — a `vortex.constant` values pool declaring 2^40 entries. Its length is a bare
        // claim with no storage behind it, so normalizing it entry by entry would allocate ~8 TB
        // of offsets; the reviewer measured 2^31 at ~18 s / ~19 GB and 2^40 as non-terminating.
        // A ~200-byte crafted file can carry exactly this shape, so decode must not walk the pool.
        Array values = constantPool(DType.UTF8, "zz", 1L << 40);
        Array codes = byteCodes(0, 7, 3);

        // When
        Array result = decode(3, DType.UTF8, values, codes);

        // Then — collapsed to the broadcast constant in O(1): one distinct value, every code hits it
        assertThat(result).isInstanceOf(VarBinConstantArray.class);
        assertStrings(result, "zz", "zz", "zz");
    }

    @Test
    void viewPool_normalizesOnlyTheEntriesCodesReach() {
        // Given — a 4-entry StringView pool whose last two entries no code references. The
        // normalization walk is bounded by the highest code, not by the pool's declared length,
        // so entries 2 and 3 are never visited; the rows that are addressed must still be right.
        Array values = viewPool(DType.UTF8, "a", "bb", "ccc", "dddd");
        Array codes = byteCodes(1, 0, 1);

        // When
        Array result = decode(3, DType.UTF8, values, codes);

        // Then
        assertThat(result).isInstanceOf(VarBinDictArray.class);
        assertStrings(result, "bb", "a", "bb");
    }

    @Test
    void codeOutsideConstantPool_throwsVortexException() {
        // Given — a constant pool has no offsets table for an out-of-range code to run off, so
        // nothing downstream would catch code 9 against its 3 entries; the values-side guard has
        // to reject it here or the row silently resolves to the constant.
        Array values = constantPool(DType.UTF8, "zz", 3);
        Array codes = byteCodes(0, 9);

        // When / Then
        assertThatThrownBy(() -> decode(2, DType.UTF8, values, codes))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("dict code 9 out of range for a values pool of 3 entries");
    }

    @Test
    void fixedSizeListPool_throwsVortexException() {
        // Given — a dict-encoded vortex.uuid-shaped column: the pool is a FixedSizeList(U8, 16),
        // neither VarBin- nor primitive-shaped. The fallback used to blind-cast it to VarBinArray,
        // which breaks the security contract (a raw ClassCastException on untrusted input).
        DType.FixedSizeList storage = new DType.FixedSizeList(DType.U8, 16, false);
        DType uuid = new DType.Extension("vortex.uuid", storage, null, false);
        Array values = new FixedSizeListArray(storage, 2, bytePool(new int[32]));
        Array codes = byteCodes(0, 1, 0);

        // When / Then
        assertThatThrownBy(() -> decode(3, uuid, values, codes))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("unsupported dict values shape: FixedSizeListArray");
    }

    // ── harness ─────────────────────────────────────────────────────────────────

    private static Stream<Arguments> varBinPools() {
        DType utf8Extension = new DType.Extension("ip.address", DType.UTF8, null, false);
        String[] byCode = {"ccc", "a", "bb", "a"};
        String[] broadcast = {"zz", "zz", "zz", "zz"};
        return Stream.of(
                Arguments.of("utf8 + offsets pool", DType.UTF8, offsetPool(DType.UTF8, "a", "bb", "ccc"),
                        VarBinDictArray.class, byCode),
                Arguments.of("utf8 + view pool", DType.UTF8, viewPool(DType.UTF8, "a", "bb", "ccc"),
                        VarBinDictArray.class, byCode),
                Arguments.of("utf8 + constant pool", DType.UTF8, constantPool(DType.UTF8, "zz", 3),
                        VarBinConstantArray.class, broadcast),
                Arguments.of("varbin extension + offsets pool", utf8Extension,
                        offsetPool(utf8Extension, "a", "bb", "ccc"), VarBinDictArray.class, byCode),
                Arguments.of("varbin extension + view pool", utf8Extension,
                        viewPool(utf8Extension, "a", "bb", "ccc"), VarBinDictArray.class, byCode),
                Arguments.of("varbin extension + constant pool", utf8Extension,
                        constantPool(utf8Extension, "zz", 3), VarBinConstantArray.class, broadcast));
    }

    /// Flat bytes-plus-offsets dictionary pool — the shape the lazy branch already accepted.
    private static VarBinOffsetArray offsetPool(DType dtype, String... values) {
        StringBuilder joined = new StringBuilder();
        int[] offsets = new int[values.length + 1];
        for (int i = 0; i < values.length; i++) {
            joined.append(values[i]);
            offsets[i + 1] = joined.toString().getBytes(StandardCharsets.UTF_8).length;
        }
        return new VarBinOffsetArray(dtype, values.length,
                MemorySegment.ofArray(joined.toString().getBytes(StandardCharsets.UTF_8)),
                TestSegments.leInts(offsets), PType.I32);
    }

    /// Arrow StringView dictionary pool — the pool shape with a real per-row walk behind
    /// [VarBinArray#toOffsetMode(VarBinArray, java.lang.foreign.SegmentAllocator)], so it
    /// exercises the normalization the constant pool short-circuits. Values are short enough
    /// to be inlined in the 16-byte view, so no data buffers are needed.
    private static VarBinArray viewPool(DType dtype, String... values) {
        MemorySegment views = MemorySegment.ofArray(new byte[values.length * VIEW_SIZE]);
        for (int i = 0; i < values.length; i++) {
            byte[] bytes = values[i].getBytes(StandardCharsets.UTF_8);
            long viewOff = (long) i * VIEW_SIZE;
            views.set(VortexFormat.LE_INT, viewOff, bytes.length);
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, views, viewOff + 4, bytes.length);
        }
        return new VarBinViewArray(dtype, values.length, views, new MemorySegment[0]);
    }

    /// `vortex.constant` dictionary pool: `size` slots all holding the same value, with no
    /// offsets segment and no storage proportional to `size` at all — the shape that used to
    /// miss the lazy branch, and whose `size` is a bare claim the file need not back.
    private static VarBinArray constantPool(DType dtype, String value, long size) {
        return new VarBinConstantArray(dtype, size, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertStrings(Array result, String... expected) {
        VarBinArray strings = (VarBinArray) result;
        for (int i = 0; i < expected.length; i++) {
            assertThat(strings.getString(i)).as("row %d", i).isEqualTo(expected[i]);
        }
    }

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
    /// serves a real arena for the gathered validity bitmap and any pool normalization. The
    /// decoder resolves its children through [LayoutDecodeContext#decodeChild(Layout, DType)]
    /// only, so the file-backed segment accessors are never reached from either dict path.
    private record StubContext(Layout valuesLayout, Array values, Layout codesLayout, Array codes)
            implements LayoutDecodeContext {

        @Override
        public Array decodeChild(Layout child, DType dtype) {
            return child == valuesLayout ? values : codes;
        }

        @Override
        public Array decodeSegment(SegmentSpec spec, DType dtype, long rowCount) {
            throw new UnsupportedOperationException("children are pre-built; no segment is read");
        }

        @Override
        public SegmentSpec segmentSpec(int index) {
            throw new UnsupportedOperationException("children are pre-built; no segment is read");
        }

        @Override
        public SegmentAllocator arena() {
            return Arena.ofAuto();
        }
    }
}
