package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.encoding.TestSegments;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoDictMetadata;
import io.github.dfa1.vortex.core.proto.ProtoVarBinMetadata;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DictEncodingDecoderTest {

    private static final DictEncodingDecoder SUT = new DictEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            SUT, new PrimitiveEncodingDecoder(), new VarBinEncodingDecoder(), new BoolEncodingDecoder());

    @Test
    void encodingId_isVortexDict() {
        // Given / When / Then
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.VORTEX_DICT);
    }

    @Nested
    class PrimitiveProto {

        @ParameterizedTest(name = "codes={0} values={1}")
        @MethodSource("io.github.dfa1.vortex.reader.decode.DictEncodingDecoderTest#codeAndValueTypes")
        void fastPath_indexesDictionary(PType codePType, PType valPType) {
            // Given — 4 codes pointing at 4 distinct dict values (codesCap == rowCount, valuesCap > 1)
            long[] dict = {10, 20, 30, 40};
            long[] codes = {0, 1, 2, 3};
            DType dtype = new DType.Primitive(valPType, false);

            // When
            Array result = decodeProto(dtype, codePType, codes, dict);

            // Then
            assertLongValues(result, valPType, new long[]{10, 20, 30, 40});
        }

        @ParameterizedTest(name = "codes={0} values={1}")
        @MethodSource("io.github.dfa1.vortex.reader.decode.DictEncodingDecoderTest#codeAndValueTypes")
        void slowPath_broadcastsSingleValue(PType codePType, PType valPType) {
            // Given — a single dict value (valuesCap == 1) forces the broadcast/modulo path
            long[] dict = {77};
            long[] codes = {0, 0, 0, 0};
            DType dtype = new DType.Primitive(valPType, false);

            // When
            Array result = decodeProto(dtype, codePType, codes, dict);

            // Then — every row resolves to the lone value
            assertLongValues(result, valPType, new long[]{77, 77, 77, 77});
        }

        @Test
        void f64Values_roundTripThroughDoubleArray() {
            // Given — covers typedArray's F64 branch and the 8-byte expand path
            MemorySegment codes = u8Codes(0, 1, 0);
            MemorySegment values = TestSegments.leDoubles(1.5, -2.25);
            DType dtype = DType.F64;

            // When
            DoubleArray result = (DoubleArray) decodeProtoSegments(dtype, PType.U8, codes, values, 2, 3);

            // Then
            assertThat(result.getDouble(0)).isEqualTo(1.5);
            assertThat(result.getDouble(1)).isEqualTo(-2.25);
            assertThat(result.getDouble(2)).isEqualTo(1.5);
        }

        @Test
        void f32Values_roundTripThroughFloatArray() {
            // Given — covers typedArray's F32 branch and the 4-byte expand path
            MemorySegment codes = u8Codes(1, 0);
            MemorySegment values = TestSegments.leFloats(3.5f, 4.75f);
            DType dtype = DType.F32;

            // When
            FloatArray result = (FloatArray) decodeProtoSegments(dtype, PType.U8, codes, values, 2, 2);

            // Then
            assertThat(result.getFloat(0)).isEqualTo(4.75f);
            assertThat(result.getFloat(1)).isEqualTo(3.5f);
        }

        @Test
        void unexpectedCodeType_throws() {
            // Given — proto declares an I64 code type, which decodeRustProto rejects
            MemorySegment codes = TestSegments.leLongs(0, 1);
            MemorySegment values = TestSegments.leLongs(5, 6);
            DType dtype = DType.I64;

            // When / Then
            assertThatThrownBy(() -> decodeProtoSegments(dtype, PType.I64, codes, values, 2, 2))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unexpected code type");
        }

        @Test
        void unsupportedValuePType_throws() {
            // Given — F16 expands fine (2 bytes) but typedArray has no F16 mapping
            MemorySegment codes = u8Codes(0, 1);
            MemorySegment values = TestSegments.leShorts((short) 1, (short) 2);
            DType dtype = DType.F16;

            // When / Then
            assertThatThrownBy(() -> decodeProtoSegments(dtype, PType.U8, codes, values, 2, 2))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unsupported ptype");
        }

        @Test
        void missingMetadata_throws() {
            // Given — primitive dict with no metadata
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_DICT, null, new ArrayNode[0], new int[]{});
            DecodeContext ctx = new DecodeContext(node, DType.I32,
                    1, new MemorySegment[0], REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing metadata");
        }

        @Test
        void emptyMetadata_throws() {
            // Given — metadata present but with zero remaining bytes (exercises !hasRemaining)
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_DICT, MemorySegment.ofArray(new byte[0]),
                    new ArrayNode[0], new int[]{});
            DecodeContext ctx = new DecodeContext(node, DType.I32,
                    1, new MemorySegment[0], REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing metadata");
        }

        @Test
        void malformedProtoMetadata_throws() {
            // Given — >1 byte (routes to proto path) but a truncated varint that proto decode rejects
            MemorySegment meta = MemorySegment.ofArray(new byte[]{0x08, (byte) 0x80});
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_DICT, meta,
                    new ArrayNode[]{primitiveNode(0), primitiveNode(1)}, new int[]{});
            DecodeContext ctx = new DecodeContext(node, DType.I32,
                    1, new MemorySegment[]{u8Codes(0), TestSegments.leLongs(0)}, REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("invalid proto metadata");
        }
    }

    @Nested
    class PrimitiveLegacy {

        @ParameterizedTest(name = "codes={0}")
        @org.junit.jupiter.params.provider.EnumSource(value = PType.class, names = {"U8", "U16", "U32"})
        void singleByteMetadata_decodesViaLegacyPath(PType codePType) {
            // Given — legacy layout: 1-byte metadata (code ptype), child[0]=values, child[1]=codes
            long[] dict = {100, 200, 300};
            MemorySegment values = TestSegments.leLongs(dict);
            MemorySegment codes = codeSegment(codePType, new long[]{2, 0, 1, 2});

            // When
            Array result = decodeLegacy(DType.I64, codePType, values, codes, 4);

            // Then
            assertLongValues(result, PType.I64, new long[]{300, 100, 200, 300});
        }

        @Test
        void nonStandardCodeType_throws() {
            // Given — code ptype I8 is not U8/U16/U32, so the legacy switch rejects it
            MemorySegment values = TestSegments.leLongs(1, 2);
            MemorySegment codes = MemorySegment.ofArray(new byte[]{0, 0});

            // When / Then
            assertThatThrownBy(() ->
                    decodeLegacy(DType.I64, PType.I8, values, codes, 2))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unexpected code type");
        }
    }

    /// Directly exercises the generic copy fallback (element width not 1/2/4/8) in each
    /// expand* helper — unreachable through decode() since primitive widths are always
    /// 1/2/4/8, but kept as a defensive bulk-copy path.
    @Nested
    class ExpandGenericElemSize {

        @ParameterizedTest(name = "codes={0}")
        @org.junit.jupiter.params.provider.EnumSource(value = PType.class, names = {"U8", "U16", "U32"})
        void fastPath_copiesPerElement(PType codePType) {
            // Given — two 3-byte elements, codes [0, 1] (codesCap == rowCount, valuesCap > 1)
            MemorySegment values = bytes(1, 2, 3, 4, 5, 6);
            MemorySegment codes = codeSegment(codePType, new long[]{0, 1});
            MemorySegment out = Arena.ofAuto().allocate(6);

            // When
            expand(codePType, codes, values, out, 2, 3);

            // Then
            assertThat(toByteArray(out, 6)).containsExactly(1, 2, 3, 4, 5, 6);
        }

        @ParameterizedTest(name = "codes={0}")
        @org.junit.jupiter.params.provider.EnumSource(value = PType.class, names = {"U8", "U16", "U32"})
        void slowPath_broadcastsSingleElement(PType codePType) {
            // Given — one 3-byte element (valuesCap == 1) forces the broadcast branch
            MemorySegment values = bytes(7, 8, 9);
            MemorySegment codes = codeSegment(codePType, new long[]{0, 0});
            MemorySegment out = Arena.ofAuto().allocate(6);

            // When
            expand(codePType, codes, values, out, 2, 3);

            // Then
            assertThat(toByteArray(out, 6)).containsExactly(7, 8, 9, 7, 8, 9);
        }

        @ParameterizedTest(name = "codes={0}")
        @org.junit.jupiter.params.provider.EnumSource(value = PType.class, names = {"U8", "U16", "U32"})
        void broadcastCodes_whenCodesShorterThanRowCount(PType codePType) {
            // Given — a single code element (codesCap < rowCount) takes the codes-broadcast branch
            MemorySegment values = bytes(10, 11, 12, 20, 21, 22);
            MemorySegment codes = codeSegment(codePType, new long[]{1});
            MemorySegment out = Arena.ofAuto().allocate(6);

            // When
            expand(codePType, codes, values, out, 2, 3);

            // Then — code 1 resolves to the second element for every row
            assertThat(toByteArray(out, 6)).containsExactly(20, 21, 22, 20, 21, 22);
        }

        private void expand(PType codePType, MemorySegment codes, MemorySegment values,
                MemorySegment out, long rowCount, int elemSize) {
            switch (codePType) {
                case U8 -> DictEncodingDecoder.expandU8(codes, values, out, rowCount, elemSize);
                case U16 -> DictEncodingDecoder.expandU16(codes, values, out, rowCount, elemSize);
                case U32 -> DictEncodingDecoder.expandU32(codes, values, out, rowCount, elemSize);
                default -> throw new IllegalArgumentException("unsupported: " + codePType);
            }
        }

        private MemorySegment bytes(int... values) {
            byte[] a = new byte[values.length];
            for (int i = 0; i < values.length; i++) {
                a[i] = (byte) values[i];
            }
            return MemorySegment.ofArray(a);
        }

        private byte[] toByteArray(MemorySegment seg, int n) {
            byte[] a = new byte[n];
            for (int i = 0; i < n; i++) {
                a[i] = seg.get(ValueLayout.JAVA_BYTE, i);
            }
            return a;
        }
    }

    @Nested
    class Utf8 {

        @Test
        void legacyLayout_decodesStringsByCode() {
            // Given — no children, 3 buffers (dict bytes, I64 offsets, codes), 1-byte metadata
            byte[] dictBytes = "abcde".getBytes(StandardCharsets.UTF_8); // "ab","cde"
            MemorySegment bytes = MemorySegment.ofArray(dictBytes);
            MemorySegment offsets = TestSegments.leLongs(0, 2, 5);
            MemorySegment codes = u8Codes(1, 0, 1);

            MemorySegment meta = MemorySegment.ofArray(new byte[]{(byte) PType.U8.ordinal()});
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_DICT, meta, new ArrayNode[0], new int[]{0, 1, 2});
            DecodeContext ctx = new DecodeContext(node, DType.UTF8, 3,
                    new MemorySegment[]{bytes, offsets, codes}, REGISTRY, Arena.ofAuto());

            // When
            VarBinArray result = (VarBinArray) SUT.decode(ctx);

            // Then
            assertThat(result.getString(0)).isEqualTo("cde");
            assertThat(result.getString(1)).isEqualTo("ab");
            assertThat(result.getString(2)).isEqualTo("cde");
        }

        @Test
        void protoLayout_decodesStringsByCode() {
            // Given — children present: child[0]=codes, child[1]=varbin dictionary values
            byte[] dictBytes = "fizzbuzz".getBytes(StandardCharsets.UTF_8); // "fizz","buzz"
            MemorySegment bytes = MemorySegment.ofArray(dictBytes);
            MemorySegment offsets = TestSegments.leLongs(0, 4, 8);
            MemorySegment codes = u8Codes(0, 1, 0);
            MemorySegment[] segs = {codes, bytes, offsets};

            MemorySegment dictMeta = MemorySegment.ofArray(
                    new ProtoDictMetadata(2, protoPType(PType.U8), null, null).encode());
            MemorySegment varBinMeta = MemorySegment.ofArray(new ProtoVarBinMetadata(protoPType(PType.I64)).encode());

            ArrayNode codesNode = primitiveNode(0);
            ArrayNode offsetsNode = primitiveNode(2);
            ArrayNode valuesNode = new ArrayNode(EncodingId.VORTEX_VARBIN, varBinMeta,
                    new ArrayNode[]{offsetsNode}, new int[]{1});
            ArrayNode dictNode = new ArrayNode(EncodingId.VORTEX_DICT, dictMeta,
                    new ArrayNode[]{codesNode, valuesNode}, new int[]{});

            DecodeContext ctx = new DecodeContext(dictNode, DType.UTF8, 3,
                    segs, REGISTRY, Arena.ofAuto());

            // When
            VarBinArray result = (VarBinArray) SUT.decode(ctx);

            // Then
            assertThat(result.getString(0)).isEqualTo("fizz");
            assertThat(result.getString(1)).isEqualTo("buzz");
            assertThat(result.getString(2)).isEqualTo("fizz");
        }

        @Test
        void legacyLayout_missingMetadata_throws() {
            // Given — no children and no metadata
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_DICT, null, new ArrayNode[0], new int[]{});
            DecodeContext ctx = new DecodeContext(node, DType.UTF8, 0,
                    new MemorySegment[0], REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("legacy utf8 dict");
        }

        @Test
        void protoLayout_malformedMetadata_throws() {
            // Given — children present, metadata is an invalid (truncated varint) proto blob
            MemorySegment meta = MemorySegment.ofArray(new byte[]{0x08, (byte) 0x80});
            ArrayNode child = primitiveNode(0);
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_DICT, meta,
                    new ArrayNode[]{child, child}, new int[]{});
            DecodeContext ctx = new DecodeContext(node, DType.UTF8, 1,
                    new MemorySegment[]{u8Codes(0)}, REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("invalid utf8 dict proto metadata");
        }

        @Test
        void protoLayout_missingMetadata_throws() {
            // Given — children present but metadata absent
            ArrayNode child = primitiveNode(0);
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_DICT, null, new ArrayNode[]{child, child}, new int[]{});
            DecodeContext ctx = new DecodeContext(node, DType.UTF8, 1,
                    new MemorySegment[]{u8Codes(0)}, REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing metadata for utf8 dict");
        }

        @Test
        void legacyLayout_emptyMetadata_throws() {
            // Given — no children and zero-remaining metadata (exercises !hasRemaining)
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_DICT, MemorySegment.ofArray(new byte[0]),
                    new ArrayNode[0], new int[]{});
            DecodeContext ctx = new DecodeContext(node, DType.UTF8, 0,
                    new MemorySegment[0], REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("legacy utf8 dict");
        }

        @Test
        void protoLayout_emptyMetadata_throws() {
            // Given — children present, zero-remaining metadata
            ArrayNode child = primitiveNode(0);
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_DICT, MemorySegment.ofArray(new byte[0]),
                    new ArrayNode[]{child, child}, new int[]{});
            DecodeContext ctx = new DecodeContext(node, DType.UTF8, 1,
                    new MemorySegment[]{u8Codes(0)}, REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing metadata for utf8 dict");
        }
    }

    /// Per-row validity for the Rust proto primitive path (`decodeRustProto`, #210). A DictArray
    /// row is null when its CODE is null (codes-side validity child) or when the code points at an
    /// invalid pool slot (pool-null representation); both arrive as [MaskedArray] children and must
    /// combine per row. Shapes mirror real Kepler columns: `koi_gmag` is pool-null, `koi_smet_err2`
    /// is codes-side.
    @Nested
    class RowValidity {

        @Test
        void poolNull_masksRowsPointingAtInvalidSlot() {
            // Given — pool [10,20,30] with slot 1 invalid (koi_gmag shape); codes point rows 1 and 3
            // at that dead slot, so those rows are null while their expanded value is still present.
            ArrayNode codesNode = primitiveNode(0);                 // plain codes: no codes-side nulls
            ArrayNode valuesNode = maskedPrimitiveNode(1, 2);       // masked pool: slot validity
            MemorySegment[] segs = {
                    u8Codes(0, 1, 2, 1),
                    TestSegments.leInts(10, 20, 30),
                    boolBitmap(true, false, true)                   // slot 1 invalid
            };

            // When
            Array result = decodeDict(DType.I32, PType.U8, 3, 4, segs, codesNode, valuesNode);

            // Then
            MaskedArray masked = assertMasked(result);
            assertValidity(masked, true, false, true, false);
            assertIntValues(masked, 10, 20, 30, 20);
        }

        @Test
        void codesNull_masksRowsWithNullCode() {
            // Given — pool [100,200] all valid; the codes child carries its own validity
            // (koi_smet_err2 shape). Row nulls must mirror the codes mask exactly.
            ArrayNode codesNode = maskedPrimitiveNode(0, 1);        // masked codes: rows 1,3 null
            ArrayNode valuesNode = primitiveNode(2);                // plain pool: all valid
            MemorySegment[] segs = {
                    u8Codes(0, 1, 0, 1),
                    boolBitmap(true, false, true, false),
                    TestSegments.leInts(100, 200)
            };

            // When
            Array result = decodeDict(DType.I32, PType.U8, 2, 4, segs, codesNode, valuesNode);

            // Then
            MaskedArray masked = assertMasked(result);
            assertValidity(masked, true, false, true, false);
            assertIntValues(masked, 100, 200, 100, 200);
        }

        @Test
        void bothSides_combineWithAndSemantics() {
            // Given — codes-side nulls AND a pool-null slot: row i is valid iff its code is valid and
            // the pool slot it references is valid. Row 2 is null via its code; rows 1,3 via the pool.
            ArrayNode codesNode = maskedPrimitiveNode(0, 1);        // codes: row 2 null
            ArrayNode valuesNode = maskedPrimitiveNode(2, 3);       // pool: slot 1 invalid
            MemorySegment[] segs = {
                    u8Codes(0, 1, 2, 1),
                    boolBitmap(true, true, false, true),            // codes valid except row 2
                    TestSegments.leInts(10, 20, 30),
                    boolBitmap(true, false, true)                   // pool slot 1 invalid
            };

            // When
            Array result = decodeDict(DType.I32, PType.U8, 3, 4, segs, codesNode, valuesNode);

            // Then — only row 0 survives both masks
            MaskedArray masked = assertMasked(result);
            assertValidity(masked, true, false, false, false);
            assertIntValues(masked, 10, 20, 30, 20);
        }

        @Test
        void codeOutOfRangeWithPoolValidity_throwsVortexException() {
            // Given — a single-element pool (so expandU8 broadcasts, never reading out of bounds) with
            // pool validity present, and an untrusted code 5 that overruns the validity bitmap. The
            // poolValid guard must convert the overrun into a VortexException, not an IOOBE.
            ArrayNode codesNode = primitiveNode(0);
            ArrayNode valuesNode = maskedPrimitiveNode(1, 2);
            MemorySegment[] segs = {
                    u8Codes(0, 5),
                    TestSegments.leInts(10),
                    boolBitmap(true)                                // pool length 1
            };

            // When / Then
            assertThatThrownBy(() -> decodeDict(DType.I32, PType.U8, 1, 2, segs, codesNode, valuesNode))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for pool validity");
        }

        private Array decodeDict(DType dtype, PType codePType, int valuesLen, long rowCount,
                MemorySegment[] segs, ArrayNode codesNode, ArrayNode valuesNode) {
            MemorySegment meta = MemorySegment.ofArray(
                    new ProtoDictMetadata(valuesLen, protoPType(codePType), null, null).encode());
            ArrayNode dictNode = new ArrayNode(EncodingId.VORTEX_DICT, meta,
                    new ArrayNode[]{codesNode, valuesNode}, new int[]{});
            DecodeContext ctx = new DecodeContext(dictNode, dtype, rowCount, segs, REGISTRY, Arena.ofAuto());
            return SUT.decode(ctx);
        }

        private MaskedArray assertMasked(Array result) {
            assertThat(result).isInstanceOf(MaskedArray.class);
            return (MaskedArray) result;
        }

        private void assertValidity(MaskedArray masked, boolean... expected) {
            for (int i = 0; i < expected.length; i++) {
                assertThat(masked.isValid(i)).as("valid row %d", i).isEqualTo(expected[i]);
            }
        }

        private void assertIntValues(MaskedArray masked, int... expected) {
            IntArray inner = (IntArray) masked.inner();
            for (int i = 0; i < expected.length; i++) {
                assertThat(inner.getInt(i)).as("row %d", i).isEqualTo(expected[i]);
            }
        }

        /// A primitive node whose single validity child (a `vortex.bool` bitmap) makes
        /// [PrimitiveEncodingDecoder] surface it as a [MaskedArray].
        private ArrayNode maskedPrimitiveNode(int dataBufIndex, int validityBufIndex) {
            ArrayNode validity = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0],
                    new int[]{validityBufIndex});
            return new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[]{validity},
                    new int[]{dataBufIndex});
        }

        private MemorySegment boolBitmap(boolean... valid) {
            byte[] bytes = new byte[(valid.length + 7) / 8];
            for (int i = 0; i < valid.length; i++) {
                if (valid[i]) {
                    bytes[i >>> 3] |= (byte) (1 << (i & 7));
                }
            }
            return MemorySegment.ofArray(bytes);
        }
    }

    // ── parameter sources ──────────────────────────────────────────────────────

    static Stream<Arguments> codeAndValueTypes() {
        PType[] codeTypes = {PType.U8, PType.U16, PType.U32};
        PType[] valueTypes = {PType.I8, PType.I16, PType.I32, PType.I64};
        Stream.Builder<Arguments> b = Stream.builder();
        for (PType code : codeTypes) {
            for (PType val : valueTypes) {
                b.add(Arguments.of(code, val));
            }
        }
        return b.build();
    }

    // ── decode harnesses ───────────────────────────────────────────────────────

    private static Array decodeProto(DType dtype, PType codePType, long[] codes, long[] dict) {
        MemorySegment codesSeg = codeSegment(codePType, codes);
        MemorySegment valuesSeg = valueSegment(((DType.Primitive) dtype).ptype(), dict);
        return decodeProtoSegments(dtype, codePType, codesSeg, valuesSeg, dict.length, codes.length);
    }

    private static Array decodeProtoSegments(DType dtype, PType codePType, MemorySegment codes,
            MemorySegment values, int valuesLen, int rowCount) {
        MemorySegment meta = MemorySegment.ofArray(new ProtoDictMetadata(valuesLen, protoPType(codePType), null, null).encode());
        MemorySegment[] segs = {codes, values};
        ArrayNode dictNode = new ArrayNode(EncodingId.VORTEX_DICT, meta,
                new ArrayNode[]{primitiveNode(0), primitiveNode(1)}, new int[]{});
        DecodeContext ctx = new DecodeContext(dictNode, dtype, rowCount, segs, REGISTRY, Arena.ofAuto());
        return SUT.decode(ctx);
    }

    private static Array decodeLegacy(DType dtype, PType codePType, MemorySegment values,
            MemorySegment codes, int rowCount) {
        MemorySegment meta = MemorySegment.ofArray(new byte[]{(byte) codePType.ordinal()});
        MemorySegment[] segs = {values, codes};
        ArrayNode dictNode = new ArrayNode(EncodingId.VORTEX_DICT, meta,
                new ArrayNode[]{primitiveNode(0), primitiveNode(1)}, new int[]{});
        DecodeContext ctx = new DecodeContext(dictNode, dtype, rowCount, segs, REGISTRY, Arena.ofAuto());
        return SUT.decode(ctx);
    }

    private static ArrayNode primitiveNode(int bufferIndex) {
        return new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{bufferIndex});
    }

    private static io.github.dfa1.vortex.core.proto.ProtoPType protoPType(PType core) {
        return io.github.dfa1.vortex.core.proto.ProtoPType.valueOf(core.name());
    }

    // ── segment builders (little-endian) ───────────────────────────────────────

    private static MemorySegment codeSegment(PType codePType, long[] codes) {
        return switch (codePType) {
            case U8 -> {
                byte[] a = new byte[codes.length];
                for (int i = 0; i < codes.length; i++) {
                    a[i] = (byte) codes[i];
                }
                yield MemorySegment.ofArray(a);
            }
            case U16 -> {
                short[] s = new short[codes.length];
                for (int i = 0; i < codes.length; i++) {
                    s[i] = (short) codes[i];
                }
                yield TestSegments.leShorts(s);
            }
            case U32 -> {
                int[] in = new int[codes.length];
                for (int i = 0; i < codes.length; i++) {
                    in[i] = (int) codes[i];
                }
                yield TestSegments.leInts(in);
            }
            default -> throw new IllegalArgumentException("unsupported code ptype: " + codePType);
        };
    }

    private static MemorySegment valueSegment(PType valPType, long[] values) {
        return switch (valPType) {
            case I8, U8 -> {
                byte[] a = new byte[values.length];
                for (int i = 0; i < values.length; i++) {
                    a[i] = (byte) values[i];
                }
                yield MemorySegment.ofArray(a);
            }
            case I16, U16 -> {
                short[] s = new short[values.length];
                for (int i = 0; i < values.length; i++) {
                    s[i] = (short) values[i];
                }
                yield TestSegments.leShorts(s);
            }
            case I32, U32 -> {
                int[] in = new int[values.length];
                for (int i = 0; i < values.length; i++) {
                    in[i] = (int) values[i];
                }
                yield TestSegments.leInts(in);
            }
            case I64, U64 -> TestSegments.leLongs(values);
            default -> throw new IllegalArgumentException("unsupported value ptype: " + valPType);
        };
    }

    private static MemorySegment u8Codes(int... codes) {
        byte[] a = new byte[codes.length];
        for (int i = 0; i < codes.length; i++) {
            a[i] = (byte) codes[i];
        }
        return MemorySegment.ofArray(a);
    }






    private static void assertLongValues(Array array, PType valPType, long[] expected) {
        for (int i = 0; i < expected.length; i++) {
            long actual = switch (valPType) {
                case I8, U8 -> ((ByteArray) array).getByte(i);
                case I16, U16 -> ((ShortArray) array).getShort(i);
                case I32, U32 -> ((IntArray) array).getInt(i);
                case I64, U64 -> ((LongArray) array).getLong(i);
                default -> throw new IllegalArgumentException("unsupported: " + valPType);
            };
            assertThat(actual).as("index %d", i).isEqualTo(expected[i]);
        }
    }
}
