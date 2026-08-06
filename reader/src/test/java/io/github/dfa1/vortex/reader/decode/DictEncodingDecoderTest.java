package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoDictMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.core.proto.ProtoVarBinMetadata;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DictByteArray;
import io.github.dfa1.vortex.reader.array.DictIntArray;
import io.github.dfa1.vortex.reader.array.DictLongArray;
import io.github.dfa1.vortex.reader.array.DictShortArray;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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

        /// The encoding path must return the same lazy `DictXxxArray` carriers
        /// `DictLayoutDecoder` builds for the layout-level form of the same dictionary. Before
        /// #336 it expanded into a `Materialized*Array` of `n * elemSize` bytes instead, so the
        /// two disagreed and a dict column decoded through this path lost the dictionary's
        /// entire memory benefit. Asserting the carrier type is the point: the value assertions
        /// below pass either way.
        @ParameterizedTest(name = "values={0}")
        @MethodSource("io.github.dfa1.vortex.reader.decode.DictEncodingDecoderTest#lazyCarrierTypes")
        void decodesToTheLazyCarrier_notAnExpandedBuffer(PType valPType, Class<?> carrier) {
            // Given — 2 codes over a 2-entry pool
            DType dtype = new DType.Primitive(valPType, false);

            // When
            Array result = decodeProto(dtype, PType.U8, new long[]{0, 1}, new long[]{10, 20});

            // Then
            assertThat(result).isInstanceOf(carrier);
            assertLongValues(result, valPType, new long[]{10, 20});
        }

        /// A dict exists to make a large column cost the pool plus the codes. Pinning that the
        /// codes child is reused as-is — rather than gathered into a fresh per-row buffer —
        /// is what stops the eager expansion creeping back.
        @Test
        void keepsThePoolAndCodesRatherThanGathering() {
            // Given / When
            Array result = decodeProto(DType.I64, PType.U8, new long[]{0, 1, 0, 1}, new long[]{10, 20});

            // Then
            DictLongArray dict = (DictLongArray) result;
            assertThat(dict.values().length()).isEqualTo(2L);
            assertThat(dict.codes().length()).isEqualTo(4L);
        }

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
        @EnumSource(value = PType.class, names = {"U8", "U16", "U32"})
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

    /// Broadcast fan-out: an undersized codes or values buffer (the `ConstantEncoding`
    /// shape) is repeated to cover every row. The eager `expandXxx` scatter loops used to
    /// implement this with an explicit `% cap`; the lazy carriers inherit it from the
    /// `Materialized*` accessors' own `i % elementCount`, so the contract is pinned here
    /// through decode() rather than against a helper that no longer exists (#336).
    @Nested
    class Broadcast {

        @ParameterizedTest(name = "codes={0}")
        @EnumSource(value = PType.class, names = {"U8", "U16", "U32"})
        void valuesBufferShorterThanPool_repeatsTheSingleEntry(PType codePType) {
            // Given — metadata declares a 2-entry pool but only one entry is on the wire
            MemorySegment values = TestSegments.leInts(77);
            MemorySegment codes = codeSegment(codePType, new long[]{0, 1});

            // When
            Array result = decodeProtoSegments(DType.I32, codePType, codes, values, 2, 2);

            // Then — both codes resolve to the only entry present
            assertThat(((IntArray) result).getInt(0)).isEqualTo(77);
            assertThat(((IntArray) result).getInt(1)).isEqualTo(77);
        }

        @ParameterizedTest(name = "codes={0}")
        @EnumSource(value = PType.class, names = {"U8", "U16", "U32"})
        void codesBufferShorterThanRowCount_repeatsTheCodes(PType codePType) {
            // Given — 4 rows declared, 2 codes on the wire
            MemorySegment values = TestSegments.leInts(10, 20);
            MemorySegment codes = codeSegment(codePType, new long[]{1, 0});

            // When
            Array result = decodeProtoSegments(DType.I32, codePType, codes, values, 2, 4);

            // Then — the code pair repeats across the row count
            IntArray ints = (IntArray) result;
            assertThat(ints.getInt(0)).isEqualTo(20);
            assertThat(ints.getInt(1)).isEqualTo(10);
            assertThat(ints.getInt(2)).isEqualTo(20);
            assertThat(ints.getInt(3)).isEqualTo(10);
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
            // Given — a single-element pool with pool validity present, and an untrusted code 5
            // that overruns both. Two guards can catch this; since #336 the decode-time
            // validateCodesInRange runs first and blames the code directly, rather than the
            // poolValid guard blaming the validity bitmap it happened to overrun second. Either
            // way it must be a VortexException, never an IOOBE (ADR 0003).
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
                    .hasMessageContaining("code 5 out of range for a values pool");
        }

        @Test
        void codesBroadcast_slowPathSetsValidityBits() {
            // Given — a single-element codes buffer (codesCap == 1 < rowCount == 4, the
            // ConstantEncoding fan-out shape) forces rowValidity's broadcast slow path
            // (the `i % codesCap` loop and the setBit S3034 fix site). The lone code points at
            // valid pool slot 0, so every row is valid and setBit runs on each iteration.
            ArrayNode codesNode = primitiveNode(0);
            ArrayNode valuesNode = maskedPrimitiveNode(1, 2);
            MemorySegment[] segs = {
                    u8Codes(0),                             // 1 code, broadcast across 4 rows
                    TestSegments.leInts(10, 20),
                    boolBitmap(true, false)                 // pool slot 0 valid, slot 1 invalid
            };

            // When
            Array result = decodeDict(DType.I32, PType.U8, 2, 4, segs, codesNode, valuesNode);

            // Then — the broadcast code resolves to valid slot 0 for every row
            MaskedArray masked = assertMasked(result);
            assertValidity(masked, true, true, true, true);
            assertIntValues(masked, 10, 10, 10, 10);
        }

        @ParameterizedTest(name = "codes={0}")
        @EnumSource(value = PType.class, names = {"U16", "U32"})
        void poolNull_readsWiderCodeWidths(PType codePType) {
            // Given — codes at the wider widths (U16/U32) with a pool-null slot. rowValidity must
            // read each code at the correct stride via readCode's U16/U32 arms (only exercised when
            // pool validity is present), then mask rows whose code points at the dead slot. Here
            // codesCap == rowCount, so the fast validity loop runs.
            ArrayNode codesNode = primitiveNode(0);
            ArrayNode valuesNode = maskedPrimitiveNode(1, 2);
            MemorySegment[] segs = {
                    codeSegment(codePType, new long[]{0, 1, 0, 1}),
                    TestSegments.leInts(10, 20),
                    boolBitmap(true, false)                 // slot 1 invalid
            };

            // When
            Array result = decodeDict(DType.I32, codePType, 2, 4, segs, codesNode, valuesNode);

            // Then — rows referencing the dead slot 1 are null; their expanded value is still present
            MaskedArray masked = assertMasked(result);
            assertValidity(masked, true, false, true, false);
            assertIntValues(masked, 10, 20, 10, 20);
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

    /// Adversarial codes and dictionary offsets from an untrusted file (TODO.md §Security,
    /// per-encoding adversarial tests). `poolValid` already guarded codes against the
    /// row-validity pool, but the value **expansion** loops indexed the values pool with no
    /// bounds check at all: a code pointing past the pool escaped as a raw
    /// `IndexOutOfBoundsException` from the segment access instead of a [VortexException].
    ///
    /// A codes ptype that cannot address the whole pool (u8 codes, pool longer than 256) is
    /// deliberately NOT an error: the writer picks the codes width from the codes actually
    /// emitted, so a pool whose tail is unreachable is well-formed, and neither the format
    /// spec nor the Rust reference rejects it.
    @Nested
    class AdversarialCodes {

        @ParameterizedTest(name = "codes={0}")
        @EnumSource(value = PType.class, names = {"U8", "U16", "U32"})
        void codePastValuesPool_protoFastPath_throws(PType codePType) {
            // Given a 2-entry I32 pool and a code of 7; codesCap == rowCount and valuesCap > 1,
            // so the un-clamped fast expansion branch runs and indexes past the pool
            MemorySegment codes = codeSegment(codePType, new long[]{0, 7});
            MemorySegment values = TestSegments.leInts(10, 20);

            // When / Then
            assertThatThrownBy(() -> decodeProtoSegments(DType.I32, codePType, codes, values, 2, 2))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for a values pool");
        }

        @Test
        void codePastValuesPool_legacyPath_throws() {
            // Given the legacy 1-byte-metadata layout with the same overrunning code
            MemorySegment values = TestSegments.leLongs(1, 2);
            MemorySegment codes = u8Codes(0, 9);

            // When / Then
            assertThatThrownBy(() -> decodeLegacy(DType.I64, PType.U8, values, codes, 2))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for a values pool");
        }

        @Test
        void codePastDictionaryOffsets_utf8_throws() {
            // Given a 2-entry string dictionary and a code of 5, which walks off the
            // dictionary offsets segment when the row is read
            VarBinArray array = decodeLegacyUtf8("abcde", TestSegments.leLongs(0, 2, 5), u8Codes(5));

            // When / Then
            assertThatThrownBy(() -> array.getString(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("dict value offset index");
        }

        @Test
        void nonMonotonicDictionaryOffsets_utf8_throws() {
            // Given dictionary offsets [0, 5, 2]: entry 1 spans [5, 2), a negative length that
            // used to reach `new byte[end - start]` as a NegativeArraySizeException
            VarBinArray array = decodeLegacyUtf8("abcde", TestSegments.leLongs(0, 5, 2), u8Codes(1));

            // When / Then
            assertThatThrownBy(() -> array.getString(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for a data buffer");
        }

        @Test
        void dictionaryOffsetPastBytes_utf8_throws() {
            // Given an entry ending at 100 over a 5-byte dictionary bytes buffer
            VarBinArray array = decodeLegacyUtf8("abcde", TestSegments.leLongs(0, 100), u8Codes(0));

            // When / Then
            assertThatThrownBy(() -> array.getString(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for a data buffer");
        }

        @Test
        void truncatedCodesSegment_utf8_throws() {
            // Given 3 declared rows but only 2 codes on the wire: the third row reads past
            // the codes segment, which must fail loudly rather than as a raw IOOBE
            MemorySegment bytes = MemorySegment.ofArray("abcde".getBytes(StandardCharsets.UTF_8));
            MemorySegment meta = MemorySegment.ofArray(new byte[]{(byte) PType.U8.ordinal()});
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_DICT, meta, new ArrayNode[0], new int[]{0, 1, 2});
            DecodeContext ctx = new DecodeContext(node, DType.UTF8, 3,
                    new MemorySegment[]{bytes, TestSegments.leLongs(0, 2, 5), u8Codes(0, 1)},
                    REGISTRY, Arena.ofAuto());
            VarBinArray array = (VarBinArray) SUT.decode(ctx);

            // When / Then
            assertThatThrownBy(() -> array.getString(2))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for a codes segment");
        }

        @Test
        void emptyValuesPool_throws() {
            // Given a zero-length values child: the broadcast branch wraps codes with
            // `% valuesCap`, so an empty pool used to die with ArithmeticException
            // (values_len stays 1 so the metadata is not elided to an empty proto payload)
            MemorySegment values = MemorySegment.ofArray(new byte[0]);

            // When / Then
            assertThatThrownBy(() -> decodeProtoSegments(DType.I32, PType.U8, u8Codes(0, 0), values, 1, 2))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("empty dict child");
        }

        @Test
        void emptyCodesChild_throws() {
            // Given a zero-length codes child, which makes the `% codesCap` wrap divide by zero
            MemorySegment codes = MemorySegment.ofArray(new byte[0]);

            // When / Then
            assertThatThrownBy(() -> decodeProtoSegments(DType.I32, PType.U8, codes, TestSegments.leInts(1, 2), 2, 2))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("empty dict child");
        }

        @Test
        void legacyPathWithoutValuesChild_throws() {
            // Given the legacy layout with an empty children vector: the values buffer used
            // to be reached by three raw array indexes on untrusted data
            MemorySegment meta = MemorySegment.ofArray(new byte[]{(byte) PType.U8.ordinal()});
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_DICT, meta, new ArrayNode[0], new int[]{});
            DecodeContext ctx = new DecodeContext(node, DType.I64, 2,
                    new MemorySegment[]{TestSegments.leLongs(1, 2)}, REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("child index 0 out of bounds");
        }

        @Test
        void truncatedCodesSegment_limited_throws() {
            // Given 5 declared rows but 2 codes on the wire, cut to 3 rows by a scan limit:
            // slicing the codes segment used to escape as a raw IOOBE
            MemorySegment bytes = MemorySegment.ofArray("abcde".getBytes(StandardCharsets.UTF_8));
            MemorySegment meta = MemorySegment.ofArray(new byte[]{(byte) PType.U8.ordinal()});
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_DICT, meta, new ArrayNode[0], new int[]{0, 1, 2});
            DecodeContext ctx = new DecodeContext(node, DType.UTF8, 5,
                    new MemorySegment[]{bytes, TestSegments.leLongs(0, 2, 5), u8Codes(0, 1)},
                    REGISTRY, Arena.ofAuto());
            VarBinArray array = (VarBinArray) SUT.decode(ctx);

            // When / Then
            assertThatThrownBy(() -> array.limited(3))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("holds fewer than 3 U8 codes");
        }

        @Test
        void codePastDictionaryOffsets_forEachByteLength_blamesTheCode() {
            // Given a code past a dictionary with I32 value offsets — the shape FSST
            // decompression produces, and the only one that takes the branch-free bulk
            // length path. That path catches the overrun at the loop boundary, and the cold
            // re-validation must name the offending code rather than blaming the offsets
            // segment it happened to read.
            VarBinArray array = decodeProtoUtf8("fizzbuzz", TestSegments.leInts(0, 4, 8), u8Codes(5), 2);

            // When / Then
            assertThatThrownBy(() -> array.forEachByteLength(len -> {
            }))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("dict code 5 at row 0 out of range for 3 value offsets");
        }

        @Test
        void consumerFailure_forEachByteLength_propagatesUnchanged() {
            // Given well-formed dictionary data and a consumer that throws its own
            // IndexOutOfBoundsException: the boundary catch must not relabel a caller bug as
            // malformed input
            VarBinArray array = decodeLegacyUtf8("abcde", TestSegments.leLongs(0, 2, 5), u8Codes(1));

            // When / Then
            assertThatThrownBy(() -> array.forEachByteLength(len -> {
                throw new IndexOutOfBoundsException("sink says no");
            }))
                    .isInstanceOf(IndexOutOfBoundsException.class)
                    .isNotInstanceOf(VortexException.class)
                    .hasMessage("sink says no");
        }

        @Test
        void poolWiderThanCodePType_decodesTheAddressableEntries() {
            // Given a 300-entry pool with u8 codes: entries 256+ are unreachable, which is
            // well-formed rather than an error (the writer sizes codes from what it emits),
            // so decode must succeed for the entries the codes can address.
            int[] pool = new int[300];
            for (int i = 0; i < pool.length; i++) {
                pool[i] = i * 10;
            }
            MemorySegment values = TestSegments.leInts(pool);

            // When
            Array result = decodeProtoSegments(DType.I32, PType.U8, u8Codes(0, 255), values, 300, 2);

            // Then
            assertThat(((IntArray) result).getInt(0)).isZero();
            assertThat(((IntArray) result).getInt(1)).isEqualTo(2550);
        }

        /// Builds the proto utf8 dict layout with a VarBin values child carrying I32 value
        /// offsets — the shape FSST decompression yields, and the one whose bulk length walk
        /// takes the branch-free I32 fast path.
        private VarBinArray decodeProtoUtf8(String dictBytes, MemorySegment offsets,
                MemorySegment codes, int valuesLen) {
            MemorySegment bytes = MemorySegment.ofArray(dictBytes.getBytes(StandardCharsets.UTF_8));
            MemorySegment dictMeta = MemorySegment.ofArray(
                    new ProtoDictMetadata(valuesLen, protoPType(PType.U8), null, null).encode());
            MemorySegment varBinMeta = MemorySegment.ofArray(
                    new ProtoVarBinMetadata(protoPType(PType.I32)).encode());
            ArrayNode valuesNode = new ArrayNode(EncodingId.VORTEX_VARBIN, varBinMeta,
                    new ArrayNode[]{primitiveNode(2)}, new int[]{1});
            ArrayNode dictNode = new ArrayNode(EncodingId.VORTEX_DICT, dictMeta,
                    new ArrayNode[]{primitiveNode(0), valuesNode}, new int[]{});
            DecodeContext ctx = new DecodeContext(dictNode, DType.UTF8, 1,
                    new MemorySegment[]{codes, bytes, offsets}, REGISTRY, Arena.ofAuto());
            return (VarBinArray) SUT.decode(ctx);
        }

        /// Builds the legacy (buffer-only) utf8 dict layout: dictionary bytes, I64 value
        /// offsets and u8 codes, with the 1-byte code-ptype metadata.
        private VarBinArray decodeLegacyUtf8(String dictBytes, MemorySegment offsets, MemorySegment codes) {
            MemorySegment bytes = MemorySegment.ofArray(dictBytes.getBytes(StandardCharsets.UTF_8));
            MemorySegment meta = MemorySegment.ofArray(new byte[]{(byte) PType.U8.ordinal()});
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_DICT, meta, new ArrayNode[0], new int[]{0, 1, 2});
            DecodeContext ctx = new DecodeContext(node, DType.UTF8, 1,
                    new MemorySegment[]{bytes, offsets, codes}, REGISTRY, Arena.ofAuto());
            return (VarBinArray) SUT.decode(ctx);
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

    /// Each primitive value ptype paired with the lazy carrier it must decode to — the same
    /// mapping `DictLayoutDecoder.buildLazyDictPrimitive` uses. F16 is absent because it has
    /// no `Array` subtype yet, on either path.
    static Stream<Arguments> lazyCarrierTypes() {
        return Stream.of(
                Arguments.of(PType.I8, DictByteArray.class),
                Arguments.of(PType.I16, DictShortArray.class),
                Arguments.of(PType.I32, DictIntArray.class),
                Arguments.of(PType.I64, DictLongArray.class));
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

    private static ProtoPType protoPType(PType core) {
        return ProtoPType.valueOf(core.name());
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
