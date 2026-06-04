package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.ListArray;
import io.github.dfa1.vortex.core.array.LongArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListEncodingTest {


	private static ArrayNode toArrayNode(EncodeNode node) {
		ArrayNode[] children = new ArrayNode[node.children().length];
		for (int i = 0; i < children.length; i++) {
			children[i] = toArrayNode(node.children()[i]);
		}
		return new ArrayNode(node.encodingId(), node.metadata(), children, node.bufferIndices(), ArrayStats.empty());
	}

	private static EncodingRegistry registry() {
		EncodingRegistry r = EncodingRegistry.empty();
		r.register(new ListEncoding());
		r.register(new PrimitiveEncoding());
		return r;
	}

	@Nested
	class Encode {

		@Test
		void accepts_listDtype_true() {
			// Given
			ListEncoding sut = new ListEncoding();

			// When / Then
			assertThat(sut.accepts(DTypes.LIST_I32)).isTrue();
		}

		@Test
		void accepts_primitiveDtype_false() {
			// Given
			ListEncoding sut = new ListEncoding();

			// When / Then
			assertThat(sut.accepts(DTypes.I32)).isFalse();
		}

		@Test
		void encode_producesTwoChildren_noBuffers() {
			// Given
			int[] elements = {1, 2, 3, 4, 5};
			long[] offsets = {0, 2, 5};
			ListData data = new ListData(elements, offsets, 2);
			ListEncoding sut = new ListEncoding();

			// When
			EncodeResult result = sut.encode(DTypes.LIST_I32, data);

			// Then
			assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_LIST);
			assertThat(result.rootNode().bufferIndices()).isEmpty();
			assertThat(result.rootNode().children()).hasSize(2);
		}
	}

	@Nested
	class Decode {

		@Test
		void roundTrip_i32Elements_preservesValues() {
			// Given
			int[] elements = {10, 20, 30, 40, 50};
			long[] offsets = {0, 2, 3, 5};
			ListData data = new ListData(elements, offsets, 3);
			ListEncoding sut = new ListEncoding();

			// When
			EncodeResult result = sut.encode(DTypes.LIST_I32, data);
			MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
			DecodeContext ctx = new DecodeContext(
					toArrayNode(result.rootNode()), DTypes.LIST_I32, 3, bufs, registry(), Arena.global());
			ListArray decoded = (ListArray) sut.decode(ctx);

			// Then
			assertThat(decoded.length()).isEqualTo(3);
			IntArray decodedElems = (IntArray) decoded.elements();
			assertThat(decodedElems.length()).isEqualTo(5);
			for (int i = 0; i < elements.length; i++) {
				assertThat(decodedElems.getInt(i)).isEqualTo(elements[i]);
			}
			LongArray decodedOffsets = (LongArray) decoded.offsets();
			assertThat(decodedOffsets.length()).isEqualTo(4);
			for (int i = 0; i < offsets.length; i++) {
				assertThat(decodedOffsets.getLong(i)).isEqualTo(offsets[i]);
			}
		}

		@Test
		void roundTrip_emptyLists_preservesOffsets() {
			// Given
			int[] elements = {};
			long[] offsets = {0, 0, 0};
			ListData data = new ListData(elements, offsets, 2);
			ListEncoding sut = new ListEncoding();

			// When
			EncodeResult result = sut.encode(DTypes.LIST_I32, data);
			MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
			DecodeContext ctx = new DecodeContext(
					toArrayNode(result.rootNode()), DTypes.LIST_I32, 2, bufs, registry(), Arena.global());
			ListArray decoded = (ListArray) sut.decode(ctx);

			// Then
			assertThat(decoded.length()).isEqualTo(2);
			assertThat(decoded.elements().length()).isEqualTo(0);
			assertThat(decoded.offsets().length()).isEqualTo(3);
		}

		@Test
		void roundTrip_singleList_preservesValues() {
			// Given
			int[] elements = {7, 8, 9};
			long[] offsets = {0, 3};
			ListData data = new ListData(elements, offsets, 1);
			ListEncoding sut = new ListEncoding();

			// When
			EncodeResult result = sut.encode(DTypes.LIST_I32, data);
			MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
			DecodeContext ctx = new DecodeContext(
					toArrayNode(result.rootNode()), DTypes.LIST_I32, 1, bufs, registry(), Arena.global());
			ListArray decoded = (ListArray) sut.decode(ctx);

			// Then
			assertThat(decoded.length()).isEqualTo(1);
			IntArray decodedElems = (IntArray) decoded.elements();
			assertThat(decodedElems.length()).isEqualTo(3);
			for (int i = 0; i < elements.length; i++) {
				assertThat(decodedElems.getInt(i)).isEqualTo(elements[i]);
			}
		}

		@Test
		void decode_wrongDtype_throws() {
			// Given
			ListEncoding sut = new ListEncoding();
			ArrayNode node = new ArrayNode(EncodingId.VORTEX_LIST, null,
					new ArrayNode[0], new int[0], ArrayStats.empty());
			DecodeContext ctx = new DecodeContext(node, DTypes.I32, 0, new MemorySegment[0], registry(), Arena.global());

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.hasMessageContaining("DType.List");
		}

		@Test
		void decode_wrongChildCount_throws() {
			// Given
			ListEncoding sut = new ListEncoding();
			ArrayNode child = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
					new ArrayNode[0], new int[0], ArrayStats.empty());
			ArrayNode node = new ArrayNode(EncodingId.VORTEX_LIST,
					java.nio.ByteBuffer.wrap(new byte[0]),
					new ArrayNode[]{child}, new int[0], ArrayStats.empty());
			DecodeContext ctx = new DecodeContext(node, DTypes.LIST_I32, 0, new MemorySegment[0], registry(), Arena.global());

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.hasMessageContaining("expected 2 or 3 children");
		}
	}
}
