package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.proto.NullValue;
import io.github.dfa1.vortex.proto.PatchesMetadata;
import io.github.dfa1.vortex.proto.ScalarValue;
import io.github.dfa1.vortex.proto.SparseMetadata;
import io.github.dfa1.vortex.proto.VarBinMetadata;
import io.github.dfa1.vortex.reader.decode.BoolEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.SparseEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.VarBinEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SparseEncodingEncoderTest {

    private static final SparseEncodingEncoder ENCODER = new SparseEncodingEncoder();
    private static final SparseEncodingDecoder DECODER = new SparseEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    @Nested
    class Encode {

        private static Array decodeResult(EncodeResult encoded, DType dtype, int n) {
            EncodeNode root = encoded.rootNode();
            List<MemorySegment> bufs = encoded.buffers();

            MemorySegment[] segments = bufs.toArray(new MemorySegment[0]);

            ArrayNode idxNode = ArrayNode.of(root.children()[0].encodingId(), null,
                    new ArrayNode[0], root.children()[0].bufferIndices());
            ArrayNode valNode = ArrayNode.of(root.children()[1].encodingId(), null,
                    new ArrayNode[0], root.children()[1].bufferIndices());
            ArrayNode sparseNode = ArrayNode.of(root.encodingId(), root.metadata(),
                    new ArrayNode[]{idxNode, valNode}, root.bufferIndices());

            DecodeContext ctx = new DecodeContext(sparseNode, dtype, n, segments, REGISTRY, Arena.global());
            return DECODER.decode(ctx);
        }

        @Test
        void encode_allZeros_noPatches() throws java.io.IOException {
            long[] data = {0L, 0L, 0L, 0L};
            EncodeResult result = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            var metaSeg = java.lang.foreign.MemorySegment.ofBuffer(result.rootNode().metadata().duplicate());
            SparseMetadata meta = SparseMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            assertThat(meta.patches().len()).isZero();
        }

        @Test
        void encode_withNonZero_createsPatches() throws java.io.IOException {
            long[] data = {0L, 10L, 0L, 50L, 0L};
            EncodeResult result = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            var metaSeg = java.lang.foreign.MemorySegment.ofBuffer(result.rootNode().metadata().duplicate());
            SparseMetadata meta = SparseMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            assertThat(meta.patches().len()).isEqualTo(2);
        }

        @Test
        void encode_roundTrip_i64() {
            long[] data = {0L, 0L, 42L, 0L, 99L, 0L, 0L, 7L};
            EncodeResult encoded = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            Array decoded = decodeResult(encoded, DTypes.I64, data.length);

            for (int i = 0; i < data.length; i++) {
                assertThat(ArraySegments.of(decoded).get(PTypeIO.LE_LONG, (long) i * 8))
                        .as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_roundTrip_f64() {
            double[] data = {0.0, 3.14, 0.0, 0.0, 2.72};
            EncodeResult encoded = ENCODER.encode(DTypes.F64, data, EncodeTestHelper.testCtx());
            Array decoded = decodeResult(encoded, DTypes.F64, data.length);

            for (int i = 0; i < data.length; i++) {
                assertThat(ArraySegments.of(decoded).get(PTypeIO.LE_DOUBLE, (long) i * 8))
                        .as("index %d", i).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 100})
        void encode_empty_or_allZero_noPatches(int size) throws java.io.IOException {
            long[] data = new long[size];
            EncodeResult result = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            var metaSeg = java.lang.foreign.MemorySegment.ofBuffer(result.rootNode().metadata().duplicate());
            SparseMetadata meta = SparseMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            assertThat(meta.patches().len()).isZero();
        }
    }

    @Nested
    class Decode {

        private static DecodeContext buildSparseCtx(
                DType dtype, long rowCount, long fillLong, PType idxPtype,
                long[] patchIndices, long[] patchValues
        ) {
            return buildSparseCtxWithOffset(dtype, rowCount, fillLong, idxPtype, patchIndices, patchValues, 0L);
        }

        private static DecodeContext buildSparseCtxWithOffset(
                DType dtype, long rowCount, long fillLong, PType idxPtype,
                long[] patchIndices, long[] patchValues, long offset
        ) {
            byte[] fillBytes = ScalarValue.ofInt64Value(fillLong).encode();
            byte[] metaBytes = buildSparseMetaBytes(patchIndices.length, offset, idxPtype);
            byte[] idxBuf = toLEBytes(patchIndices, idxPtype);
            byte[] valBuf = toLEBytes(patchValues, PType.I64);

            return buildCtx(dtype, rowCount, fillBytes, metaBytes, idxBuf, valBuf);
        }

        private static DecodeContext buildSparseCtxF64(
                DType dtype, long rowCount, double fillDouble,
                long[] patchIndices, double[] patchValues
        ) {
            byte[] fillBytes = ScalarValue.ofF64Value(fillDouble).encode();
            byte[] metaBytes = buildSparseMetaBytes(patchIndices.length, 0L, PType.U32);
            byte[] idxBuf = toLEBytes(patchIndices, PType.U32);
            byte[] valBuf = f64LEBytes(patchValues);

            return buildCtx(dtype, rowCount, fillBytes, metaBytes, idxBuf, valBuf);
        }

        private static DecodeContext buildCtx(DType dtype, long rowCount,
                byte[] fillBytes, byte[] metaBytes, byte[] idxBuf, byte[] valBuf) {
            ArrayNode idxNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{1});
            ArrayNode valNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{2});
            ArrayNode sparseNode = ArrayNode.of(EncodingId.VORTEX_SPARSE,
                    ByteBuffer.wrap(metaBytes),
                    new ArrayNode[]{idxNode, valNode},
                    new int[]{0});

            MemorySegment[] segments = {
                    MemorySegment.ofArray(fillBytes),
                    MemorySegment.ofArray(idxBuf),
                    MemorySegment.ofArray(valBuf)
            };
            return new DecodeContext(sparseNode, dtype, rowCount, segments, REGISTRY, java.lang.foreign.Arena.global());
        }

        private static byte[] buildSparseMetaBytes(long numPatches, long offset, PType idxPtype) {
            PatchesMetadata patchesMeta = new PatchesMetadata(numPatches, offset,
                    io.github.dfa1.vortex.proto.PType.fromValue(idxPtype.ordinal()), null, null, null);
            return new SparseMetadata(patchesMeta).encode();
        }

        private static byte[] toLEBytes(long[] values, PType ptype) {
            int elemBytes = ptype.byteSize();
            byte[] buf = new byte[values.length * elemBytes];
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            for (long v : values) {
                switch (ptype) {
                    case U8, I8 -> bb.put((byte) v);
                    case U16, I16 -> bb.putShort((short) v);
                    case U32, I32 -> bb.putInt((int) v);
                    case U64, I64 -> bb.putLong(v);
                    default -> throw new UnsupportedOperationException(ptype.name());
                }
            }
            return buf;
        }

        private static byte[] f64LEBytes(double[] values) {
            byte[] buf = new byte[values.length * 8];
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            for (double v : values) {
                bb.putDouble(v);
            }
            return buf;
        }

        private static byte[] intLEBytes(int[] values) {
            byte[] buf = new byte[values.length * 4];
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : values) {
                bb.putInt(v);
            }
            return buf;
        }

        @Test
        void decode_noPatches_returnsFillValue() {
            long fill = 99L;
            DecodeContext ctx = buildSparseCtx(DTypes.I64, 5, fill, PType.U32, new long[0], new long[0]);
            Array result = DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(5L);
            for (int i = 0; i < 5; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_LONG, (long) i * 8))
                        .as("index %d", i).isEqualTo(fill);
            }
        }

        @Test
        void decode_withPatches_overwritesAtIndices() {
            long fill = 0L;
            long[] patchIndices = {1L, 5L};
            long[] patchValues = {10L, 50L};
            DecodeContext ctx = buildSparseCtx(DTypes.I64, 8, fill, PType.U32, patchIndices, patchValues);
            Array result = DECODER.decode(ctx);

            long[] expected = {0, 10, 0, 0, 0, 50, 0, 0};
            for (int i = 0; i < expected.length; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_LONG, (long) i * 8))
                        .as("index %d", i).isEqualTo(expected[i]);
            }
        }

        @Test
        void decode_f64_fillAndPatches() {
            double fillVal = Double.NaN;
            double patchVal = 3.14;
            DecodeContext ctx = buildSparseCtxF64(DTypes.F64, 4, fillVal, new long[]{2L}, new double[]{patchVal});
            Array result = DECODER.decode(ctx);

            assertThat(ArraySegments.of(result).get(PTypeIO.LE_DOUBLE, 0L)).isNaN();
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_DOUBLE, 8L)).isNaN();
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_DOUBLE, 16L)).isEqualTo(3.14);
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_DOUBLE, 24L)).isNaN();
        }

        @Test
        void decode_offsetSubtracted() {
            long[] patchIndices = {12L};
            long[] patchValues = {777L};
            DecodeContext ctx = buildSparseCtxWithOffset(DTypes.I64, 5, 0L, PType.U32, patchIndices, patchValues, 10L);
            Array result = DECODER.decode(ctx);

            assertThat(ArraySegments.of(result).get(PTypeIO.LE_LONG, 16L)).isEqualTo(777L);
        }

        @Test
        void decode_nullValueFill_treatedAsZero() {
            byte[] nullFill = ScalarValue.ofNullValue(NullValue.NULL_VALUE).encode();
            byte[] meta = buildSparseMetaBytes(0, 0L, PType.U32);
            DecodeContext ctx = buildCtx(DTypes.I64, 4, nullFill, meta, new byte[0], new byte[0]);
            Array result = DECODER.decode(ctx);

            for (int i = 0; i < 4; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_LONG, (long) i * 8)).as("index %d", i).isZero();
            }
        }

        @Test
        void decode_utf8_noPatches_allEmpty() {
            DType utf8 = new DType.Utf8(true);
            byte[] nullFill = ScalarValue.ofNullValue(NullValue.NULL_VALUE).encode();
            byte[] meta = buildSparseMetaBytes(0, 0L, PType.U32);
            DecodeContext ctx = buildCtx(utf8, 3, nullFill, meta, new byte[0], new byte[0]);
            Array result = DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(3L);
            VarBinArray varBin = (VarBinArray) result;
            for (int i = 0; i < 3; i++) {
                assertThat(varBin.getByteLength(i)).as("index %d", i).isZero();
            }
        }

        @Test
        void decode_utf8_withPatches_writesStringsAtIndices() {
            DType utf8 = new DType.Utf8(true);
            byte[] nullFill = ScalarValue.ofNullValue(NullValue.NULL_VALUE).encode();
            byte[] meta = buildSparseMetaBytes(2, 0L, PType.U32);

            byte[] idxBuf = toLEBytes(new long[]{1L, 3L}, PType.U32);
            byte[] strBytes = "hibye".getBytes(StandardCharsets.UTF_8);
            byte[] offsets = intLEBytes(new int[]{0, 2, 5});
            byte[] varBinMeta = new VarBinMetadata(io.github.dfa1.vortex.proto.PType.fromValue(PType.I32.ordinal())).encode();

            ArrayNode offsetsNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{3});
            ArrayNode valNode = ArrayNode.of(EncodingId.VORTEX_VARBIN,
                    ByteBuffer.wrap(varBinMeta),
                    new ArrayNode[]{offsetsNode}, new int[]{2});
            ArrayNode idxNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{1});
            ArrayNode sparseNode = ArrayNode.of(EncodingId.VORTEX_SPARSE,
                    ByteBuffer.wrap(meta),
                    new ArrayNode[]{idxNode, valNode}, new int[]{0});

            ReadRegistry registry = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder(), new VarBinEncodingDecoder());

            MemorySegment[] segments = {
                    MemorySegment.ofArray(nullFill),
                    MemorySegment.ofArray(idxBuf),
                    MemorySegment.ofArray(strBytes),
                    MemorySegment.ofArray(offsets),
            };
            DecodeContext ctx = new DecodeContext(sparseNode, utf8, 5, segments, registry, Arena.global());

            Array result = DECODER.decode(ctx);

            VarBinArray varBin = (VarBinArray) result;
            assertThat(varBin.length()).isEqualTo(5L);
            assertThat(varBin.getByteLength(0)).isZero();
            assertThat(varBin.getString(1)).isEqualTo("hi");
            assertThat(varBin.getByteLength(2)).isZero();
            assertThat(varBin.getString(3)).isEqualTo("bye");
            assertThat(varBin.getByteLength(4)).isZero();
        }

        @Test
        void decode_bool_withPatches_setsBitsAtIndices() {
            DType bool = new DType.Bool(true);
            byte[] nullFill = ScalarValue.ofNullValue(NullValue.NULL_VALUE).encode();
            byte[] meta = buildSparseMetaBytes(2, 0L, PType.U32);
            byte[] idxBuf = toLEBytes(new long[]{2L, 5L}, PType.U32);
            byte[] boolBits = new byte[]{0b00000011};

            ArrayNode valNode = ArrayNode.of(EncodingId.VORTEX_BOOL, null,
                    new ArrayNode[0], new int[]{2});
            ArrayNode idxNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{1});
            ArrayNode sparseNode = ArrayNode.of(EncodingId.VORTEX_SPARSE,
                    ByteBuffer.wrap(meta),
                    new ArrayNode[]{idxNode, valNode}, new int[]{0});

            ReadRegistry registry = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());

            MemorySegment[] segments = {
                    MemorySegment.ofArray(nullFill),
                    MemorySegment.ofArray(idxBuf),
                    MemorySegment.ofArray(boolBits),
            };
            DecodeContext ctx = new DecodeContext(sparseNode, bool, 6, segments, registry, Arena.global());

            Array result = DECODER.decode(ctx);

            BoolArray boolArr = (BoolArray) result;
            assertThat(boolArr.length()).isEqualTo(6L);
            assertThat(boolArr.getBoolean(0)).isFalse();
            assertThat(boolArr.getBoolean(1)).isFalse();
            assertThat(boolArr.getBoolean(2)).isTrue();
            assertThat(boolArr.getBoolean(3)).isFalse();
            assertThat(boolArr.getBoolean(4)).isFalse();
            assertThat(boolArr.getBoolean(5)).isTrue();
        }
    }
}
