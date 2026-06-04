package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.NullValue;
import io.github.dfa1.vortex.proto.DTypeProtos;
import io.github.dfa1.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
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

class SparseEncodingTest {


	@Nested
	class Encode {

		@Test
		void encode_allZeros_noPatches() throws InvalidProtocolBufferException {
			// Given
			long[] data = {0L, 0L, 0L, 0L};
			SparseEncoding sut = new SparseEncoding();

			// When
			EncodeResult result = sut.encode(DTypes.I64, data);

			// Then
			EncodingProtos.SparseMetadata meta = EncodingProtos.SparseMetadata.parseFrom(
					result.rootNode().metadata().array());
			assertThat(meta.getPatches().getLen()).isZero();
		}

		@Test
		void encode_withNonZero_createsPatches() throws InvalidProtocolBufferException {
			// Given — [0, 10, 0, 50, 0]
			long[] data = {0L, 10L, 0L, 50L, 0L};
			SparseEncoding sut = new SparseEncoding();

			// When
			EncodeResult result = sut.encode(DTypes.I64, data);

			// Then
			EncodingProtos.SparseMetadata meta = EncodingProtos.SparseMetadata.parseFrom(
					result.rootNode().metadata().array());
			assertThat(meta.getPatches().getLen()).isEqualTo(2);
		}

		@Test
		void encode_roundTrip_i64() {
			// Given — sparse long array
			long[] data = {0L, 0L, 42L, 0L, 99L, 0L, 0L, 7L};
			SparseEncoding sut = new SparseEncoding();

			// When
			EncodeResult encoded = sut.encode(DTypes.I64, data);
			Array decoded = decodeResult(encoded, DTypes.I64, data.length);

			// Then
			var layout = PTypeIO.LE_LONG;
			for (int i = 0; i < data.length; i++) {
				assertThat(decoded.buffer(0).get(layout, (long) i * 8))
						.as("index %d", i).isEqualTo(data[i]);
			}
		}

		@Test
		void encode_roundTrip_f64() {
			// Given
			double[] data = {0.0, 3.14, 0.0, 0.0, 2.72};
			SparseEncoding sut = new SparseEncoding();

			// When
			EncodeResult encoded = sut.encode(DTypes.F64, data);
			Array decoded = decodeResult(encoded, DTypes.F64, data.length);

			// Then
			var layout = PTypeIO.LE_DOUBLE;
			for (int i = 0; i < data.length; i++) {
				assertThat(decoded.buffer(0).get(layout, (long) i * 8))
						.as("index %d", i).isEqualTo(data[i]);
			}
		}

		@ParameterizedTest
		@ValueSource(ints = {0, 1, 100})
		void encode_empty_or_allZero_noPatches(int size) throws InvalidProtocolBufferException {
			// Given
			long[] data = new long[size];
			SparseEncoding sut = new SparseEncoding();

			// When
			EncodeResult result = sut.encode(DTypes.I64, data);

			// Then
			EncodingProtos.SparseMetadata meta = EncodingProtos.SparseMetadata.parseFrom(
					result.rootNode().metadata().array());
			assertThat(meta.getPatches().getLen()).isZero();
		}

		private static Array decodeResult(EncodeResult encoded, DType dtype, int n) {
			EncodeNode root = encoded.rootNode();
			List<MemorySegment> bufs = encoded.buffers();

			MemorySegment[] segments = bufs.toArray(new MemorySegment[0]);

			ArrayNode idxNode = new ArrayNode(root.children()[0].encodingId(), null,
					new ArrayNode[0], root.children()[0].bufferIndices(), ArrayStats.empty());
			ArrayNode valNode = new ArrayNode(root.children()[1].encodingId(), null,
					new ArrayNode[0], root.children()[1].bufferIndices(), ArrayStats.empty());
			ArrayNode sparseNode = new ArrayNode(root.encodingId(), root.metadata(),
					new ArrayNode[]{idxNode, valNode}, root.bufferIndices(), ArrayStats.empty());

			EncodingRegistry registry = TestRegistry.of(new SparseEncoding(), new PrimitiveEncoding());

			DecodeContext ctx = new DecodeContext(sparseNode, dtype, n, segments, registry, Arena.global());
			return new SparseEncoding().decode(ctx);
		}
	}

	@Nested
	class Decode {

		@Test
		void decode_noPatches_returnsFillValue() {
			// Given — 5 elements, fill=99, no patches
			long fill = 99L;
			DecodeContext ctx = buildSparseCtx(DTypes.I64, 5, fill, PType.U32, new long[0], new long[0]);
			SparseEncoding sut = new SparseEncoding();

			// When
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(5L);
			var layout = PTypeIO.LE_LONG;
			for (int i = 0; i < 5; i++) {
				assertThat(result.buffer(0).get(layout, (long) i * 8))
						.as("index %d", i).isEqualTo(fill);
			}
		}

		@Test
		void decode_withPatches_overwritesAtIndices() {
			// Given — 8 elements, fill=0, patches at indices [1, 5] with values [10, 50]
			long fill = 0L;
			long[] patchIndices = {1L, 5L};
			long[] patchValues = {10L, 50L};
			DecodeContext ctx = buildSparseCtx(DTypes.I64, 8, fill, PType.U32, patchIndices, patchValues);
			SparseEncoding sut = new SparseEncoding();

			// When
			Array result = sut.decode(ctx);

			// Then
			var layout = PTypeIO.LE_LONG;
			long[] expected = {0, 10, 0, 0, 0, 50, 0, 0};
			for (int i = 0; i < expected.length; i++) {
				assertThat(result.buffer(0).get(layout, (long) i * 8))
						.as("index %d", i).isEqualTo(expected[i]);
			}
		}

		@Test
		void decode_f64_fillAndPatches() {
			// Given — 4 F64 elements, fill=NaN bits, patch at index 2 with value 3.14
			double fillVal = Double.NaN;
			double patchVal = 3.14;
			DecodeContext ctx = buildSparseCtxF64(DTypes.F64, 4, fillVal, new long[]{2L}, new double[]{patchVal});
			SparseEncoding sut = new SparseEncoding();

			// When
			Array result = sut.decode(ctx);

			// Then
			var layout = PTypeIO.LE_DOUBLE;
			assertThat(result.buffer(0).get(layout, 0L)).isNaN();
			assertThat(result.buffer(0).get(layout, 8L)).isNaN();
			assertThat(result.buffer(0).get(layout, 16L)).isEqualTo(3.14);
			assertThat(result.buffer(0).get(layout, 24L)).isNaN();
		}

		@Test
		void decode_offsetSubtracted() {
			// Given — offset=10, patch index=12 → absolute position = 12 - 10 = 2
			long[] patchIndices = {12L};
			long[] patchValues = {777L};
			DecodeContext ctx = buildSparseCtxWithOffset(DTypes.I64, 5, 0L, PType.U32, patchIndices, patchValues, 10L);
			SparseEncoding sut = new SparseEncoding();

			// When
			Array result = sut.decode(ctx);

			// Then
			var layout = PTypeIO.LE_LONG;
			assertThat(result.buffer(0).get(layout, 16L)).isEqualTo(777L);
		}

		// regression: NULL_VALUE fill caused "unexpected scalar kind NULL_VALUE" on nullable cols
		@Test
		void decode_nullValueFill_treatedAsZero() {
			// Given — fill encoded as ScalarValue.NULL_VALUE (as Rust writes for nullable cols)
			byte[] nullFill = ScalarProtos.ScalarValue.newBuilder()
					.setNullValue(NullValue.NULL_VALUE).build().toByteArray();
			byte[] meta = buildSparseMetaBytes(0, 0L, PType.U32);
			DecodeContext ctx = buildCtx(DTypes.I64, 4, nullFill, meta, new byte[0], new byte[0],
					new DType.Primitive(PType.U32, false));
			SparseEncoding sut = new SparseEncoding();

			// When
			Array result = sut.decode(ctx);

			// Then
			var layout = PTypeIO.LE_LONG;
			for (int i = 0; i < 4; i++) {
				assertThat(result.buffer(0).get(layout, (long) i * 8)).as("index %d", i).isZero();
			}
		}

		// regression: Utf8 dtype caused "expected primitive dtype, got Utf8[nullable=true]"
		@Test
		void decode_utf8_noPatches_allEmpty() {
			// Given — Utf8 sparse, no patches → all positions empty (null fill)
			DType utf8 = new DType.Utf8(true);
			byte[] nullFill = ScalarProtos.ScalarValue.newBuilder()
					.setNullValue(NullValue.NULL_VALUE).build().toByteArray();
			byte[] meta = buildSparseMetaBytes(0, 0L, PType.U32);
			DecodeContext ctx = buildCtx(utf8, 3, nullFill, meta, new byte[0], new byte[0],
					new DType.Primitive(PType.U32, false));
			SparseEncoding sut = new SparseEncoding();

			// When
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(3L);
			VarBinArray varBin = (VarBinArray) result;
			for (int i = 0; i < 3; i++) {
				assertThat(varBin.getByteLength(i)).as("index %d", i).isZero();
			}
		}

		// regression: Utf8 dtype caused "expected primitive dtype, got Utf8[nullable=true]"
		@Test
		void decode_utf8_withPatches_writesStringsAtIndices() {
			// Given — 5 Utf8 elements, patches at [1]="hi" and [3]="bye"
			DType utf8 = new DType.Utf8(true);
			byte[] nullFill = ScalarProtos.ScalarValue.newBuilder()
					.setNullValue(NullValue.NULL_VALUE).build().toByteArray();
			byte[] meta = buildSparseMetaBytes(2, 0L, PType.U32);

			byte[] idxBuf = toLEBytes(new long[]{1L, 3L}, PType.U32);
			byte[] strBytes = "hibye".getBytes(StandardCharsets.UTF_8);
			byte[] offsets = intLEBytes(new int[]{0, 2, 5});
			byte[] varBinMeta = EncodingProtos.VarBinMetadata.newBuilder()
					.setOffsetsPtype(DTypeProtos.PType.forNumber(PType.I32.ordinal()))
					.build().toByteArray();

			ArrayNode offsetsNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
					new ArrayNode[0], new int[]{3}, ArrayStats.empty());
			ArrayNode valNode = new ArrayNode(EncodingId.VORTEX_VARBIN,
					ByteBuffer.wrap(varBinMeta),
					new ArrayNode[]{offsetsNode}, new int[]{2}, ArrayStats.empty());
			ArrayNode idxNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
					new ArrayNode[0], new int[]{1}, ArrayStats.empty());
			ArrayNode sparseNode = new ArrayNode(EncodingId.VORTEX_SPARSE,
					ByteBuffer.wrap(meta),
					new ArrayNode[]{idxNode, valNode}, new int[]{0}, ArrayStats.empty());

			EncodingRegistry registry = TestRegistry.of(new SparseEncoding(), new PrimitiveEncoding(), new VarBinEncoding());

			MemorySegment[] segments = {
					MemorySegment.ofArray(nullFill),
					MemorySegment.ofArray(idxBuf),
					MemorySegment.ofArray(strBytes),
					MemorySegment.ofArray(offsets),
			};
			DecodeContext ctx = new DecodeContext(sparseNode, utf8, 5, segments, registry, Arena.global());
			SparseEncoding sut = new SparseEncoding();

			// When
			Array result = sut.decode(ctx);

			// Then
			VarBinArray varBin = (VarBinArray) result;
			assertThat(varBin.length()).isEqualTo(5L);
			assertThat(varBin.getByteLength(0)).isZero();
			assertThat(varBin.getString(1)).isEqualTo("hi");
			assertThat(varBin.getByteLength(2)).isZero();
			assertThat(varBin.getString(3)).isEqualTo("bye");
			assertThat(varBin.getByteLength(4)).isZero();
		}

		// regression: Bool dtype caused "expected primitive dtype, got Bool[nullable=true]"
		@Test
		void decode_bool_withPatches_setsBitsAtIndices() {
			// Given — 6 Bool elements, patches at [2]=true and [5]=true
			DType bool = new DType.Bool(true);
			byte[] nullFill = ScalarProtos.ScalarValue.newBuilder()
					.setNullValue(NullValue.NULL_VALUE).build().toByteArray();
			byte[] meta = buildSparseMetaBytes(2, 0L, PType.U32);
			byte[] idxBuf = toLEBytes(new long[]{2L, 5L}, PType.U32);
			byte[] boolBits = new byte[]{0b00000011};

			ArrayNode valNode = new ArrayNode(EncodingId.VORTEX_BOOL, null,
					new ArrayNode[0], new int[]{2}, ArrayStats.empty());
			ArrayNode idxNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
					new ArrayNode[0], new int[]{1}, ArrayStats.empty());
			ArrayNode sparseNode = new ArrayNode(EncodingId.VORTEX_SPARSE,
					ByteBuffer.wrap(meta),
					new ArrayNode[]{idxNode, valNode}, new int[]{0}, ArrayStats.empty());

			EncodingRegistry registry = TestRegistry.of(new SparseEncoding(), new PrimitiveEncoding(), new BoolEncoding());

			MemorySegment[] segments = {
					MemorySegment.ofArray(nullFill),
					MemorySegment.ofArray(idxBuf),
					MemorySegment.ofArray(boolBits),
			};
			DecodeContext ctx = new DecodeContext(sparseNode, bool, 6, segments, registry, Arena.global());
			SparseEncoding sut = new SparseEncoding();

			// When
			Array result = sut.decode(ctx);

			// Then
			BoolArray boolArr = (BoolArray) result;
			assertThat(boolArr.length()).isEqualTo(6L);
			assertThat(boolArr.getBoolean(0)).isFalse();
			assertThat(boolArr.getBoolean(1)).isFalse();
			assertThat(boolArr.getBoolean(2)).isTrue();
			assertThat(boolArr.getBoolean(3)).isFalse();
			assertThat(boolArr.getBoolean(4)).isFalse();
			assertThat(boolArr.getBoolean(5)).isTrue();
		}

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
			byte[] fillBytes = ScalarProtos.ScalarValue.newBuilder()
					.setInt64Value(fillLong).build().toByteArray();

			byte[] metaBytes = buildSparseMetaBytes(patchIndices.length, offset, idxPtype);

			byte[] idxBuf = toLEBytes(patchIndices, idxPtype);
			byte[] valBuf = toLEBytes(patchValues, PType.I64);

			return buildCtx(dtype, rowCount, fillBytes, metaBytes, idxBuf, valBuf,
					new DType.Primitive(idxPtype, false));
		}

		private static DecodeContext buildSparseCtxF64(
				DType dtype, long rowCount, double fillDouble,
				long[] patchIndices, double[] patchValues
		) {
			byte[] fillBytes = ScalarProtos.ScalarValue.newBuilder()
					.setF64Value(fillDouble).build().toByteArray();
			byte[] metaBytes = buildSparseMetaBytes(patchIndices.length, 0L, PType.U32);

			byte[] idxBuf = toLEBytes(patchIndices, PType.U32);
			byte[] valBuf = f64LEBytes(patchValues);

			return buildCtx(dtype, rowCount, fillBytes, metaBytes, idxBuf, valBuf,
					new DType.Primitive(PType.U32, false));
		}

		private static DecodeContext buildCtx(
				DType dtype, long rowCount,
				byte[] fillBytes, byte[] metaBytes,
				byte[] idxBuf, byte[] valBuf,
				DType idxDtype
		) {
			ArrayNode idxNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
					new ArrayNode[0], new int[]{1}, ArrayStats.empty());
			ArrayNode valNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
					new ArrayNode[0], new int[]{2}, ArrayStats.empty());
			ArrayNode sparseNode = new ArrayNode(EncodingId.VORTEX_SPARSE,
					ByteBuffer.wrap(metaBytes),
					new ArrayNode[]{idxNode, valNode},
					new int[]{0},
					ArrayStats.empty());

			MemorySegment[] segments = {
					MemorySegment.ofArray(fillBytes),
					MemorySegment.ofArray(idxBuf),
					MemorySegment.ofArray(valBuf)
			};

			EncodingRegistry registry = TestRegistry.of(new SparseEncoding(), new PrimitiveEncoding());

			return new DecodeContext(sparseNode, dtype, rowCount, segments, registry, java.lang.foreign.Arena.global());
		}

		private static byte[] buildSparseMetaBytes(long numPatches, long offset, PType idxPtype) {
			EncodingProtos.PatchesMetadata patchesMeta = EncodingProtos.PatchesMetadata.newBuilder()
					.setLen(numPatches)
					.setOffset(offset)
					.setIndicesPtype(DTypeProtos.PType.forNumber(idxPtype.ordinal()))
					.build();
			return EncodingProtos.SparseMetadata.newBuilder()
					.setPatches(patchesMeta)
					.build()
					.toByteArray();
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
	}
}
