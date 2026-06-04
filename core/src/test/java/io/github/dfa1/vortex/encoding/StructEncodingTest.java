package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.core.array.StructArray;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructEncodingTest {

	private static ArrayNode toArrayNode(EncodeNode node) {
		ArrayNode[] children = new ArrayNode[node.children().length];
		for (int i = 0; i < children.length; i++) {
			children[i] = toArrayNode(node.children()[i]);
		}
		return new ArrayNode(node.encodingId(), node.metadata(), children, node.bufferIndices(), ArrayStats.empty());
	}

	@Nested
	class Encode {

		@Test
		void accepts_structDtype_trueForStruct_falseForPrimitive() {
			// Given
			StructEncoding sut = new StructEncoding();
			DType.Struct structDtype = new DType.Struct(
					List.of("x"), List.of(DTypes.I64), false);

			// When / Then
			assertThat(sut.accepts(structDtype)).isTrue();
			assertThat(sut.accepts(DTypes.I64)).isFalse();
		}

		@Test
		void roundTrip_twoI64Fields_preservesValues() {
			// Given
			long[] ids = {1L, 2L, 3L};
			long[] values = {10L, 20L, 30L};
			DType.Struct dtype = new DType.Struct(
					List.of("id", "value"), List.of(DTypes.I64, DTypes.I64), false);
			StructData data = new StructData(List.of(ids, values));
			StructEncoding sut = new StructEncoding();

			// When
			EncodeResult result = sut.encode(dtype, data);

			// Then — decode round-trip
			MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
			EncodingRegistry registry = TestRegistry.of(new StructEncoding(), new PrimitiveEncoding());
			DecodeContext ctx = new DecodeContext(
					toArrayNode(result.rootNode()), dtype, ids.length, bufs, registry, Arena.global());
			StructArray decoded = (StructArray) sut.decode(ctx);

			assertThat(decoded.length()).isEqualTo(ids.length);
			assertThat(decoded.fieldCount()).isEqualTo(2);
			LongArray idField = (LongArray) decoded.field(0);
			LongArray valueField = (LongArray) decoded.field(1);
			for (int i = 0; i < ids.length; i++) {
				assertThat(idField.getLong(i)).isEqualTo(ids[i]);
				assertThat(valueField.getLong(i)).isEqualTo(values[i]);
			}
		}

		@Test
		void singleField_encodeResult_hasOneChildAndNoBuffers() {
			// Given
			long[] data = {7L, 14L, 21L};
			DType.Struct dtype = new DType.Struct(List.of("v"), List.of(DTypes.I64), false);
			StructEncoding sut = new StructEncoding();

			// When
			EncodeResult result = sut.encode(dtype, new StructData(List.of(data)));

			// Then — struct node wraps one field child with remapped buffers
			assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_STRUCT);
			assertThat(result.rootNode().children()).hasSize(1);
			assertThat(result.rootNode().bufferIndices()).isEmpty();
			assertThat(result.buffers()).hasSize(1); // one buffer for the DTypes.I64 field
		}

		@Test
		void fieldCountMismatch_throwsVortexException() {
			// Given
			DType.Struct dtype = new DType.Struct(List.of("a", "b"), List.of(DTypes.I64, DTypes.I64), false);
			StructData data = new StructData(List.of(new long[]{1L})); // only 1 field, dtype has 2
			StructEncoding sut = new StructEncoding();

			// When / Then
			org.junit.jupiter.api.Assertions.assertThrows(
					io.github.dfa1.vortex.core.VortexException.class,
					() -> sut.encode(dtype, data));
		}
	}

	@Nested
	class Decode {

		@Test
		void decode_nonNullableWrapper_oneChild_returnsValues() {
			// Given — struct{values: DTypes.I64} (non-nullable, 1 child)
			long[] data = {10L, 20L, 30L};
			MemorySegment seg = TestSegments.leLongs(data);
			ArrayNode valuesNode = primitiveNode(0);
			ArrayNode structNode = new ArrayNode(EncodingId.VORTEX_STRUCT, null,
					new ArrayNode[]{valuesNode}, new int[0], ArrayStats.empty());

			DecodeContext ctx = buildStructCtx(structNode, new MemorySegment[]{seg}, data.length);
			StructEncoding sut = new StructEncoding();

			// When
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(data.length);
			for (int i = 0; i < data.length; i++) {
				assertThat(result.buffer(0).get(PTypeIO.LE_LONG, (long) i * 8)).isEqualTo(data[i]);
			}
		}

		@Test
		void decode_nullableWrapper_twoChildren_returnsMaskedArray() {
			// Given — struct{validity: Bool, values: DTypes.I64} (nullable, 2 children)
			long[] data = {7L, 14L, 21L};
			MemorySegment validitySeg = MemorySegment.ofArray(new byte[]{(byte) 0xFF}); // all valid
			MemorySegment valuesSeg = TestSegments.leLongs(data);

			ArrayNode validityNode = boolNode(0);    // slot 0 = validity bitmap
			ArrayNode valuesNode = primitiveNode(1); // slot 1 = actual values
			ArrayNode structNode = new ArrayNode(EncodingId.VORTEX_STRUCT, null,
					new ArrayNode[]{validityNode, valuesNode}, new int[0], ArrayStats.empty());

			EncodingRegistry registry = TestRegistry.of(new StructEncoding(), new PrimitiveEncoding(), new BoolEncoding());
			DecodeContext ctx = new DecodeContext(
					structNode, DTypes.I64, data.length,
					new MemorySegment[]{validitySeg, valuesSeg},
					registry, Arena.global());

			StructEncoding sut = new StructEncoding();

			// When
			Array result = sut.decode(ctx);

			// Then — validity preserved; values accessible via inner array
			assertThat(result).isInstanceOf(MaskedArray.class);
			MaskedArray masked = (MaskedArray) result;
			LongArray values = (LongArray) masked.child(0);
			assertThat(values.length()).isEqualTo(data.length);
			for (int i = 0; i < data.length; i++) {
				assertThat(masked.isValid(i)).isTrue();
				assertThat(values.getLong(i)).isEqualTo(data[i]);
			}
		}

		private static ArrayNode primitiveNode(int bufferIdx) {
			return new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0],
					new int[]{bufferIdx}, ArrayStats.empty());
		}

		private static ArrayNode boolNode(int bufferIdx) {
			return new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0],
					new int[]{bufferIdx}, ArrayStats.empty());
		}

		private static DecodeContext buildStructCtx(ArrayNode structNode, MemorySegment[] segs, long rowCount) {
			EncodingRegistry registry = TestRegistry.of(new StructEncoding(), new PrimitiveEncoding());
			return new DecodeContext(structNode, DTypes.I64, rowCount, segs, registry, Arena.global());
		}
	}
}
