package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.proto.DictMetadata;
import io.github.dfa1.vortex.proto.VarBinMetadata;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DictEncodingDecoderTest {

    private static final DictEncodingDecoder SUT = new DictEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            SUT, new PrimitiveEncodingDecoder(), new VarBinEncodingDecoder());

    @Test
    void acceptsPrimitiveAndUtf8_rejectsOthers() {
        // Given / When / Then
        assertThat(SUT.accepts(new DType.Primitive(PType.I32, false))).isTrue();
        assertThat(SUT.accepts(new DType.Utf8(false))).isTrue();
        assertThat(SUT.accepts(new DType.Bool(false))).isFalse();
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
            MemorySegment values = doubleSegment(1.5, -2.25);
            DType dtype = new DType.Primitive(PType.F64, false);

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
            MemorySegment values = floatSegment(3.5f, 4.75f);
            DType dtype = new DType.Primitive(PType.F32, false);

            // When
            FloatArray result = (FloatArray) decodeProtoSegments(dtype, PType.U8, codes, values, 2, 2);

            // Then
            assertThat(result.getFloat(0)).isEqualTo(4.75f);
            assertThat(result.getFloat(1)).isEqualTo(3.5f);
        }

        @Test
        void unexpectedCodeType_throws() {
            // Given — proto declares an I64 code type, which decodeRustProto rejects
            MemorySegment codes = i64Segment(0, 1);
            MemorySegment values = i64Segment(5, 6);
            DType dtype = new DType.Primitive(PType.I64, false);

            // When / Then
            assertThatThrownBy(() -> decodeProtoSegments(dtype, PType.I64, codes, values, 2, 2))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unexpected code type");
        }

        @Test
        void unsupportedValuePType_throws() {
            // Given — F16 expands fine (2 bytes) but typedArray has no F16 mapping
            MemorySegment codes = u8Codes(0, 1);
            MemorySegment values = shortSegment((short) 1, (short) 2);
            DType dtype = new DType.Primitive(PType.F16, false);

            // When / Then
            assertThatThrownBy(() -> decodeProtoSegments(dtype, PType.U8, codes, values, 2, 2))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unsupported ptype");
        }

        @Test
        void missingMetadata_throws() {
            // Given — primitive dict with no metadata
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_DICT, null, new ArrayNode[0], new int[]{});
            DecodeContext ctx = new DecodeContext(node, new DType.Primitive(PType.I32, false),
                    1, new MemorySegment[0], REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing metadata");
        }

        @Test
        void malformedProtoMetadata_throws() {
            // Given — >1 byte (routes to proto path) but a truncated varint that proto decode rejects
            ByteBuffer meta = ByteBuffer.wrap(new byte[]{0x08, (byte) 0x80});
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_DICT, meta,
                    new ArrayNode[]{primitiveNode(0), primitiveNode(1)}, new int[]{});
            DecodeContext ctx = new DecodeContext(node, new DType.Primitive(PType.I32, false),
                    1, new MemorySegment[]{u8Codes(0), i64Segment(0)}, REGISTRY, Arena.ofAuto());

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
            MemorySegment values = i64Segment(dict);
            MemorySegment codes = codeSegment(codePType, new long[]{2, 0, 1, 2});

            // When
            Array result = decodeLegacy(new DType.Primitive(PType.I64, false), codePType, values, codes, 4);

            // Then
            assertLongValues(result, PType.I64, new long[]{300, 100, 200, 300});
        }

        @Test
        void nonStandardCodeType_hitsReadCodeAndThrows() {
            // Given — code ptype I8 falls into the scalar default branch, where readCode rejects it
            MemorySegment values = i64Segment(1, 2);
            MemorySegment codes = MemorySegment.ofArray(new byte[]{0, 0});

            // When / Then
            assertThatThrownBy(() ->
                    decodeLegacy(new DType.Primitive(PType.I64, false), PType.I8, values, codes, 2))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unexpected code type");
        }
    }

    @Nested
    class Utf8 {

        @Test
        void legacyLayout_decodesStringsByCode() {
            // Given — no children, 3 buffers (dict bytes, I64 offsets, codes), 1-byte metadata
            byte[] dictBytes = "abcde".getBytes(StandardCharsets.UTF_8); // "ab","cde"
            MemorySegment bytes = MemorySegment.ofArray(dictBytes);
            MemorySegment offsets = i64Segment(0, 2, 5);
            MemorySegment codes = u8Codes(1, 0, 1);

            ByteBuffer meta = ByteBuffer.wrap(new byte[]{(byte) PType.U8.ordinal()});
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_DICT, meta, new ArrayNode[0], new int[]{0, 1, 2});
            DecodeContext ctx = new DecodeContext(node, new DType.Utf8(false), 3,
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
            MemorySegment offsets = i64Segment(0, 4, 8);
            MemorySegment codes = u8Codes(0, 1, 0);
            MemorySegment[] segs = {codes, bytes, offsets};

            ByteBuffer dictMeta = ByteBuffer.wrap(
                    new DictMetadata(2, protoPType(PType.U8), null, null).encode());
            ByteBuffer varBinMeta = ByteBuffer.wrap(new VarBinMetadata(protoPType(PType.I64)).encode());

            ArrayNode codesNode = primitiveNode(0);
            ArrayNode offsetsNode = primitiveNode(2);
            ArrayNode valuesNode = ArrayNode.of(EncodingId.VORTEX_VARBIN, varBinMeta,
                    new ArrayNode[]{offsetsNode}, new int[]{1});
            ArrayNode dictNode = ArrayNode.of(EncodingId.VORTEX_DICT, dictMeta,
                    new ArrayNode[]{codesNode, valuesNode}, new int[]{});

            DecodeContext ctx = new DecodeContext(dictNode, new DType.Utf8(false), 3,
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
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_DICT, null, new ArrayNode[0], new int[]{});
            DecodeContext ctx = new DecodeContext(node, new DType.Utf8(false), 0,
                    new MemorySegment[0], REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("legacy utf8 dict");
        }

        @Test
        void protoLayout_malformedMetadata_throws() {
            // Given — children present, metadata is an invalid (truncated varint) proto blob
            ByteBuffer meta = ByteBuffer.wrap(new byte[]{0x08, (byte) 0x80});
            ArrayNode child = primitiveNode(0);
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_DICT, meta,
                    new ArrayNode[]{child, child}, new int[]{});
            DecodeContext ctx = new DecodeContext(node, new DType.Utf8(false), 1,
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
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_DICT, null, new ArrayNode[]{child, child}, new int[]{});
            DecodeContext ctx = new DecodeContext(node, new DType.Utf8(false), 1,
                    new MemorySegment[]{u8Codes(0)}, REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing metadata for utf8 dict");
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
        ByteBuffer meta = ByteBuffer.wrap(new DictMetadata(valuesLen, protoPType(codePType), null, null).encode());
        MemorySegment[] segs = {codes, values};
        ArrayNode dictNode = ArrayNode.of(EncodingId.VORTEX_DICT, meta,
                new ArrayNode[]{primitiveNode(0), primitiveNode(1)}, new int[]{});
        DecodeContext ctx = new DecodeContext(dictNode, dtype, rowCount, segs, REGISTRY, Arena.ofAuto());
        return SUT.decode(ctx);
    }

    private static Array decodeLegacy(DType dtype, PType codePType, MemorySegment values,
            MemorySegment codes, int rowCount) {
        ByteBuffer meta = ByteBuffer.wrap(new byte[]{(byte) codePType.ordinal()});
        MemorySegment[] segs = {values, codes};
        ArrayNode dictNode = ArrayNode.of(EncodingId.VORTEX_DICT, meta,
                new ArrayNode[]{primitiveNode(0), primitiveNode(1)}, new int[]{});
        DecodeContext ctx = new DecodeContext(dictNode, dtype, rowCount, segs, REGISTRY, Arena.ofAuto());
        return SUT.decode(ctx);
    }

    private static ArrayNode primitiveNode(int bufferIndex) {
        return ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{bufferIndex});
    }

    private static io.github.dfa1.vortex.proto.PType protoPType(PType core) {
        return io.github.dfa1.vortex.proto.PType.valueOf(core.name());
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
                yield shortSegment(s);
            }
            case U32 -> {
                int[] in = new int[codes.length];
                for (int i = 0; i < codes.length; i++) {
                    in[i] = (int) codes[i];
                }
                yield intSegment(in);
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
                yield shortSegment(s);
            }
            case I32, U32 -> {
                int[] in = new int[values.length];
                for (int i = 0; i < values.length; i++) {
                    in[i] = (int) values[i];
                }
                yield intSegment(in);
            }
            case I64, U64 -> i64Segment(values);
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

    private static MemorySegment shortSegment(short... values) {
        ByteBuffer bb = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : values) {
            bb.putShort(v);
        }
        return MemorySegment.ofArray(bb.array());
    }

    private static MemorySegment intSegment(int... values) {
        ByteBuffer bb = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) {
            bb.putInt(v);
        }
        return MemorySegment.ofArray(bb.array());
    }

    private static MemorySegment i64Segment(long... values) {
        ByteBuffer bb = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (long v : values) {
            bb.putLong(v);
        }
        return MemorySegment.ofArray(bb.array());
    }

    private static MemorySegment doubleSegment(double... values) {
        ByteBuffer bb = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) {
            bb.putDouble(v);
        }
        return MemorySegment.ofArray(bb.array());
    }

    private static MemorySegment floatSegment(float... values) {
        ByteBuffer bb = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            bb.putFloat(v);
        }
        return MemorySegment.ofArray(bb.array());
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
