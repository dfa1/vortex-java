package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.proto.ProtoNullValue;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoSparseMetadata;
import io.github.dfa1.vortex.core.proto.ProtoVarBinMetadata;
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

            ArrayNode idxNode = new ArrayNode(root.children()[0].encodingId(), null,
                    new ArrayNode[0], root.children()[0].bufferIndices());
            ArrayNode valNode = new ArrayNode(root.children()[1].encodingId(), null,
                    new ArrayNode[0], root.children()[1].bufferIndices());
            ArrayNode sparseNode = new ArrayNode(root.encodingId(), root.metadata(),
                    new ArrayNode[]{idxNode, valNode}, root.bufferIndices());

            DecodeContext ctx = new DecodeContext(sparseNode, dtype, n, segments, REGISTRY, Arena.global());
            return DECODER.decode(ctx);
        }

        @Test
        void encode_allZeros_noPatches() throws java.io.IOException {
            // Given
            long[] data = {0L, 0L, 0L, 0L};

            // When
            EncodeResult result = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());

            // Then
            var metaSeg = result.rootNode().metadata();
            ProtoSparseMetadata meta = ProtoSparseMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            assertThat(meta.patches().len()).isZero();
        }

        @Test
        void encodeCascade_allEqual_declinesBecauseRustRejectsEmptyPatches() {
            // Given -- a forced vortex.sparse on constant data would write zero patches, which Rust
            // refuses to read ("Patch indices must not be empty")
            long[] data = {7L, 7L, 7L, 7L};

            // When
            CascadeStep result = ENCODER.encodeCascade(DTypes.I64, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.applicable()).isFalse();
        }

        @Test
        void encode_withNonZero_createsPatches() throws java.io.IOException {
            // Given
            long[] data = {0L, 10L, 0L, 50L, 0L};

            // When
            EncodeResult result = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());

            // Then
            var metaSeg = result.rootNode().metadata();
            ProtoSparseMetadata meta = ProtoSparseMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            assertThat(meta.patches().len()).isEqualTo(2);
        }

        @Test
        void encode_roundTrip_i64() {
            // Given
            long[] data = {0L, 0L, 42L, 0L, 99L, 0L, 0L, 7L};
            EncodeResult encoded = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());

            // When
            Array result = decodeResult(encoded, DTypes.I64, data.length);

            // Then
            LongArray la = (LongArray) result;
            for (int i = 0; i < data.length; i++) {
                assertThat(la.getLong(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @ValueSource(longs = {42L, -7L})
        void encode_roundTrip_nonZeroFill_storesOnlyTheOtherValues(long fill) throws java.io.IOException {
            // Given: the fill is the most frequent value, as in Rust, not a hardcoded 0 — and a
            // negative one must survive the sign-extended widening both ways
            long[] data = {fill, fill, 5L, fill, fill, fill, 0L, fill};
            EncodeResult encoded = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());

            // When
            Array result = decodeResult(encoded, DTypes.I64, data.length);

            // Then
            var metaSeg = encoded.rootNode().metadata();
            assertThat(ProtoSparseMetadata.decode(metaSeg, 0, metaSeg.byteSize()).patches().len()).isEqualTo(2);
            LongArray la = (LongArray) result;
            for (int i = 0; i < data.length; i++) {
                assertThat(la.getLong(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_roundTrip_f64() {
            // Given
            double[] data = {0.0, 3.14, 0.0, 0.0, 2.72};
            EncodeResult encoded = ENCODER.encode(DTypes.F64, data, EncodeTestHelper.testCtx());

            // When
            Array result = decodeResult(encoded, DTypes.F64, data.length);

            // Then
            DoubleArray da = (DoubleArray) result;
            for (int i = 0; i < data.length; i++) {
                assertThat(da.getDouble(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 100})
        void encode_empty_or_allZero_noPatches(int size) throws java.io.IOException {
            // Given
            long[] data = new long[size];

            // When
            EncodeResult result = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());

            // Then
            var metaSeg = result.rootNode().metadata();
            ProtoSparseMetadata meta = ProtoSparseMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            assertThat(meta.patches().len()).isZero();
        }
    }

    @Nested
    class Decode {

        private static DecodeContext buildSparseCtx(
                DType dtype, long rowCount, long fillLong, PType idxPtype,
                long[] patchIndices, long[] patchValues) {
            return buildSparseCtxWithOffset(dtype, rowCount, fillLong, idxPtype, patchIndices, patchValues, 0L);
        }

        private static DecodeContext buildSparseCtxWithOffset(
                DType dtype, long rowCount, long fillLong, PType idxPtype,
                long[] patchIndices, long[] patchValues, long offset) {
            byte[] fillBytes = ProtoScalarValue.ofInt64Value(fillLong).encode();
            byte[] metaBytes = buildSparseMetaBytes(patchIndices.length, offset, idxPtype);
            byte[] idxBuf = toLEBytes(patchIndices, idxPtype);
            byte[] valBuf = toLEBytes(patchValues, PType.I64);

            return buildCtx(dtype, rowCount, fillBytes, metaBytes, idxBuf, valBuf);
        }

        private static DecodeContext buildSparseCtxF64(
                DType dtype, long rowCount, double fillDouble,
                long[] patchIndices, double[] patchValues) {
            byte[] fillBytes = ProtoScalarValue.ofF64Value(fillDouble).encode();
            byte[] metaBytes = buildSparseMetaBytes(patchIndices.length, 0L, PType.U32);
            byte[] idxBuf = toLEBytes(patchIndices, PType.U32);
            byte[] valBuf = f64LEBytes(patchValues);

            return buildCtx(dtype, rowCount, fillBytes, metaBytes, idxBuf, valBuf);
        }

        private static DecodeContext buildCtx(DType dtype, long rowCount,
                byte[] fillBytes, byte[] metaBytes, byte[] idxBuf, byte[] valBuf) {
            ArrayNode idxNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{1});
            ArrayNode valNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{2});
            ArrayNode sparseNode = new ArrayNode(EncodingId.VORTEX_SPARSE,
                    MemorySegment.ofArray(metaBytes),
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
            ProtoPatchesMetadata patchesMeta = new ProtoPatchesMetadata(numPatches, offset,
                    io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(idxPtype.ordinal()), null, null, null);
            return new ProtoSparseMetadata(patchesMeta).encode();
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
            // Given
            long fill = 99L;
            DecodeContext ctx = buildSparseCtx(DTypes.I64, 5, fill, PType.U32, new long[0], new long[0]);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(5L);
            LongArray la = (LongArray) result;
            for (int i = 0; i < 5; i++) {
                assertThat(la.getLong(i)).as("index %d", i).isEqualTo(fill);
            }
        }

        @Test
        void decode_withPatches_overwritesAtIndices() {
            // Given
            long fill = 0L;
            long[] patchIndices = {1L, 5L};
            long[] patchValues = {10L, 50L};
            DecodeContext ctx = buildSparseCtx(DTypes.I64, 8, fill, PType.U32, patchIndices, patchValues);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            long[] expected = {0, 10, 0, 0, 0, 50, 0, 0};
            LongArray la = (LongArray) result;
            for (int i = 0; i < expected.length; i++) {
                assertThat(la.getLong(i)).as("index %d", i).isEqualTo(expected[i]);
            }
        }

        @Test
        void decode_f64_fillAndPatches() {
            // Given
            double fillVal = Double.NaN;
            double patchVal = 3.14;
            DecodeContext ctx = buildSparseCtxF64(DTypes.F64, 4, fillVal, new long[]{2L}, new double[]{patchVal});

            // When
            Array result = DECODER.decode(ctx);

            // Then
            DoubleArray da = (DoubleArray) result;
            assertThat(da.getDouble(0)).isNaN();
            assertThat(da.getDouble(1)).isNaN();
            assertThat(da.getDouble(2)).isEqualTo(3.14);
            assertThat(da.getDouble(3)).isNaN();
        }

        @Test
        void decode_offsetSubtracted() {
            // Given
            long[] patchIndices = {12L};
            long[] patchValues = {777L};
            DecodeContext ctx = buildSparseCtxWithOffset(DTypes.I64, 5, 0L, PType.U32, patchIndices, patchValues, 10L);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(((LongArray) result).getLong(2)).isEqualTo(777L);
        }

        @Test
        void decode_nullValueFill_nullsAllUnpatchedRows() {
            // Given — a null fill with no patches: every position is unpatched, so every row
            // is null (Rust `ValidityVTable<Sparse>`: fill validity is fill.is_valid()). Before
            // #226 the null fill's zero bits leaked through as literal 0 values.
            byte[] nullFill = ProtoScalarValue.ofNullValue(ProtoNullValue.NULL_VALUE).encode();
            byte[] meta = buildSparseMetaBytes(0, 0L, PType.U32);
            DecodeContext ctx = buildCtx(DTypes.I64, 4, nullFill, meta, new byte[0], new byte[0]);

            // When
            Array result = DECODER.decode(ctx);

            // Then — all four rows are null
            MaskedArray masked = (MaskedArray) result;
            for (int i = 0; i < 4; i++) {
                assertThat(masked.isValid(i)).as("valid row %d", i).isFalse();
            }
        }

        @Test
        void decode_utf8_noPatches_allEmpty() {
            // Given
            DType utf8 = new DType.Utf8(true);
            byte[] nullFill = ProtoScalarValue.ofNullValue(ProtoNullValue.NULL_VALUE).encode();
            byte[] meta = buildSparseMetaBytes(0, 0L, PType.U32);
            DecodeContext ctx = buildCtx(utf8, 3, nullFill, meta, new byte[0], new byte[0]);

            // When
            Array result = DECODER.decode(ctx);

            // Then — null fill wraps the VarBin values in a MaskedArray whose rows are all
            // invalid (#232); the inner values are still zero-length strings.
            assertThat(result.length()).isEqualTo(3L);
            MaskedArray masked = (MaskedArray) result;
            VarBinArray varBin = (VarBinArray) masked.inner();
            for (int i = 0; i < 3; i++) {
                assertThat(masked.isValid(i)).as("valid index %d", i).isFalse();
                assertThat(varBin.getByteLength(i)).as("index %d", i).isZero();
            }
        }

        @Test
        void decode_utf8_withPatches_writesStringsAtIndices() {
            // Given
            DType utf8 = new DType.Utf8(true);
            byte[] nullFill = ProtoScalarValue.ofNullValue(ProtoNullValue.NULL_VALUE).encode();
            byte[] meta = buildSparseMetaBytes(2, 0L, PType.U32);

            byte[] idxBuf = toLEBytes(new long[]{1L, 3L}, PType.U32);
            byte[] strBytes = "hibye".getBytes(StandardCharsets.UTF_8);
            byte[] offsets = intLEBytes(new int[]{0, 2, 5});
            byte[] varBinMeta = new ProtoVarBinMetadata(io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I32.ordinal())).encode();

            ArrayNode offsetsNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{3});
            ArrayNode valNode = new ArrayNode(EncodingId.VORTEX_VARBIN,
                    MemorySegment.ofArray(varBinMeta),
                    new ArrayNode[]{offsetsNode}, new int[]{2});
            ArrayNode idxNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{1});
            ArrayNode sparseNode = new ArrayNode(EncodingId.VORTEX_SPARSE,
                    MemorySegment.ofArray(meta),
                    new ArrayNode[]{idxNode, valNode}, new int[]{0});

            ReadRegistry registry = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder(), new VarBinEncodingDecoder());

            MemorySegment[] segments = {
                    MemorySegment.ofArray(nullFill),
                    MemorySegment.ofArray(idxBuf),
                    MemorySegment.ofArray(strBytes),
                    MemorySegment.ofArray(offsets),
            };
            DecodeContext ctx = new DecodeContext(sparseNode, utf8, 5, segments, registry, Arena.global());

            // When
            Array result = DECODER.decode(ctx);

            // Then — null fill: unpatched rows 0/2/4 are null, patched rows 1/3 carry the
            // strings and are valid (#232). Unwrap the MaskedArray to reach the VarBin values.
            MaskedArray masked = (MaskedArray) result;
            VarBinArray varBin = (VarBinArray) masked.inner();
            assertThat(varBin.length()).isEqualTo(5L);
            assertThat(masked.isValid(0)).isFalse();
            assertThat(masked.isValid(1)).isTrue();
            assertThat(varBin.getString(1)).isEqualTo("hi");
            assertThat(masked.isValid(2)).isFalse();
            assertThat(masked.isValid(3)).isTrue();
            assertThat(varBin.getString(3)).isEqualTo("bye");
            assertThat(masked.isValid(4)).isFalse();
        }

        @Test
        void decode_bool_withPatches_setsBitsAtIndices() {
            // Given
            DType bool = new DType.Bool(true);
            byte[] nullFill = ProtoScalarValue.ofNullValue(ProtoNullValue.NULL_VALUE).encode();
            byte[] meta = buildSparseMetaBytes(2, 0L, PType.U32);
            byte[] idxBuf = toLEBytes(new long[]{2L, 5L}, PType.U32);
            byte[] boolBits = new byte[]{0b00000011};

            ArrayNode valNode = new ArrayNode(EncodingId.VORTEX_BOOL, null,
                    new ArrayNode[0], new int[]{2});
            ArrayNode idxNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{1});
            ArrayNode sparseNode = new ArrayNode(EncodingId.VORTEX_SPARSE,
                    MemorySegment.ofArray(meta),
                    new ArrayNode[]{idxNode, valNode}, new int[]{0});

            ReadRegistry registry = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());

            MemorySegment[] segments = {
                    MemorySegment.ofArray(nullFill),
                    MemorySegment.ofArray(idxBuf),
                    MemorySegment.ofArray(boolBits),
            };
            DecodeContext ctx = new DecodeContext(sparseNode, bool, 6, segments, registry, Arena.global());

            // When
            Array result = DECODER.decode(ctx);

            // Then — patched bits sit at 2 and 5; the null fill nulls every unpatched row (#226),
            // so only positions 2 and 5 are valid and both carry `true`.
            MaskedArray masked = (MaskedArray) result;
            assertThat(masked.length()).isEqualTo(6L);
            BoolArray boolArr = (BoolArray) masked.inner();
            assertThat(boolArr.getBoolean(2)).isTrue();
            assertThat(boolArr.getBoolean(5)).isTrue();
            for (int i = 0; i < 6; i++) {
                assertThat(masked.isValid(i)).as("valid row %d", i).isEqualTo(i == 2 || i == 5);
            }
        }
    }

    /// Rust 0.95's `NullDominatedSparseScheme` for text: only a column more than 90% null, its
    /// valid rows kept as patches under a null fill.
    @Nested
    class NullDominatedText {

        private final SparseEncodingEncoder sut = new SparseEncodingEncoder();

        @ParameterizedTest(name = "{0} of 1000 null")
        @ValueSource(ints = {0, 500, 900, 1000})
        void estimate_skipsUnlessOver90PercentNullAndNotAllNull(int nulls) {
            // Given
            NullableData data = text(1000, nulls);

            // When
            Estimate result = sut.expectedRatio(DTypes.UTF8, new ArrayAndStats(DTypes.UTF8, data,
                    StatsOptions.defaults()), EncodeTestHelper.cascadingCtx());

            // Then
            assertThat(result).isEqualTo(Estimate.SKIP);
        }

        @Test
        void estimate_pastTheCascade_dropsTheNullViewsAndAddsAnIndexPerValue() {
            // Given — 950 null of 1000 short rows: 16,000 view bytes and a 125-byte bitmap, of
            // which the 50 valid views and the bitmap stay, plus 50 u16 indices
            NullableData data = text(1000, 950);

            // When
            Estimate result = sut.expectedRatio(DTypes.UTF8, new ArrayAndStats(DTypes.UTF8, data,
                    StatsOptions.defaults()), EncodeTestHelper.testCtx());

            // Then
            assertThat(result).isEqualTo(Estimate.ratio(16_125.0 / (925 + 100)));
        }

        @Test
        void estimate_whileCascading_trialSkipsWhenTheIndicesAloneCannotBeatTheBest() {
            // Given — the indices alone bound the ratio at 16,125 / 100
            NullableData data = text(1000, 950);
            ArrayAndStats input = new ArrayAndStats(DTypes.UTF8, data, StatsOptions.defaults());
            Estimate estimate = sut.expectedRatio(DTypes.UTF8, input, EncodeTestHelper.cascadingCtx());
            CascadingCompressor compressor = new CascadingCompressor(
                    List.copyOf(io.github.dfa1.vortex.writer.WriteRegistry.loadAll().encoderMap().values()));

            // When
            Estimate result = ((TrialEstimate) estimate).trial().run(compressor, DTypes.UTF8, input,
                    EncodeTestHelper.cascadingCtx(), 161.25, () -> Double.NaN);

            // Then
            assertThat(result).isEqualTo(Estimate.SKIP);
        }

        @Test
        void encodeCascade_keepsTheValidRowsUnderANullFill() {
            // Given — 300 rows, valid at 0, 100 and 200: the indices narrow to u8 by their largest
            NullableData data = text(300, 0);
            boolean[] validity = data.validity();
            for (int i = 0; i < 300; i++) {
                validity[i] = i % 100 == 0;
            }

            // When
            CascadeStep result = sut.encodeCascade(DTypes.UTF8, data, EncodeTestHelper.cascadingCtx());

            // Then
            assertThat(result.applicable()).isTrue();
            assertThat(result.ownedBuffers().getFirst().data().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE))
                    .isEqualTo(ProtoScalarValue.ofNullValue(ProtoNullValue.NULL_VALUE).encode());
            ChildSlot indices = result.openChildren().get(0);
            assertThat(indices.childDtype()).isEqualTo(new DType.Primitive(PType.U8, false));
            assertThat((byte[]) indices.childData()).containsExactly(0, 100, (byte) 200);
            assertThat(indices.excluded()).containsExactlyInAnyOrder(EncodingId.VORTEX_SPARSE, EncodingId.VORTEX_DICT);
            ChildSlot values = result.openChildren().get(1);
            NullableData patchValues = (NullableData) values.childData();
            assertThat(patchValues.validity()).containsExactly(true, true, true);
            assertThat(new String(((byte[][]) patchValues.values())[2], StandardCharsets.UTF_8)).isEqualTo("row-200");
            assertThat(values.excluded()).isEmpty();
        }

        @Test
        void encodeCascade_notOver90PercentNull_isNotApplicable() {
            // When
            CascadeStep result = sut.encodeCascade(DTypes.UTF8, text(1000, 900), EncodeTestHelper.cascadingCtx());

            // Then
            assertThat(result.applicable()).isFalse();
        }

        /// `rows` short values `row-<i>`, the first `nulls` of them null.
        private static NullableData text(int rows, int nulls) {
            String[] values = new String[rows];
            boolean[] validity = new boolean[rows];
            for (int i = 0; i < rows; i++) {
                validity[i] = i >= nulls;
                values[i] = validity[i] ? "row-" + i : null;
            }
            return new NullableData(values, validity);
        }
    }
}
