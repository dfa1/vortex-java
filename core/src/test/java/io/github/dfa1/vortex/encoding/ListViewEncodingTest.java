package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.ListViewArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListViewEncodingTest {


	private static ArrayNode toArrayNode(EncodeNode node) {
		ArrayNode[] children = new ArrayNode[node.children().length];
		for (int i = 0; i < children.length; i++) {
			children[i] = toArrayNode(node.children()[i]);
		}
		return new ArrayNode(node.encodingId(), node.metadata(), children, node.bufferIndices(), ArrayStats.empty());
	}

	private static EncodingRegistry registry() {
		return TestRegistry.of(new ListViewEncoding(), new PrimitiveEncoding());
	}

	@Nested
	class Encode {

		@Test
		void accepts_listDtype_true() {
			// Given
			ListViewEncoding sut = new ListViewEncoding();

			// When / Then
			assertThat(sut.accepts(DTypes.LIST_I32)).isTrue();
		}

		@Test
		void accepts_primitiveDtype_false() {
			// Given
			ListViewEncoding sut = new ListViewEncoding();

			// When / Then
			assertThat(sut.accepts(DTypes.I32)).isFalse();
		}

		@Test
		void encode_producesThreeChildren_noBuffers() {
			// Given
			int[] elements = {1, 2, 3, 4, 5};
			int[] offsets = {0, 2};
			int[] sizes = {2, 3};
			ListViewData data = new ListViewData(elements, offsets, sizes, 2);
			ListViewEncoding sut = new ListViewEncoding();

			// When
			EncodeResult result = sut.encode(DTypes.LIST_I32, data);

			// Then
			assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_LISTVIEW);
			assertThat(result.rootNode().bufferIndices()).isEmpty();
			assertThat(result.rootNode().children()).hasSize(3);
		}
	}

	@Nested
	class Decode {

		@Test
		void roundTrip_i32Elements_preservesValues() {
			// Given
			int[] elements = {10, 20, 30, 40, 50};
			int[] offsets = {0, 2, 3};
			int[] sizes = {2, 1, 2};
			ListViewData data = new ListViewData(elements, offsets, sizes, 3);
			ListViewEncoding sut = new ListViewEncoding();

			// When
			EncodeResult result = sut.encode(DTypes.LIST_I32, data);
			MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
			DecodeContext ctx = new DecodeContext(
					toArrayNode(result.rootNode()), DTypes.LIST_I32, 3, bufs, registry(), Arena.global());
			ListViewArray decoded = (ListViewArray) sut.decode(ctx);

			// Then
			assertThat(decoded.length()).isEqualTo(3);
			IntArray decodedElems = (IntArray) decoded.elements();
			assertThat(decodedElems.length()).isEqualTo(5);
			for (int i = 0; i < elements.length; i++) {
				assertThat(decodedElems.getInt(i)).isEqualTo(elements[i]);
			}
			IntArray decodedOffsets = (IntArray) decoded.offsets();
			assertThat(decodedOffsets.length()).isEqualTo(3);
			IntArray decodedSizes = (IntArray) decoded.sizes();
			assertThat(decodedSizes.length()).isEqualTo(3);
		}

		@Test
		void roundTrip_emptyLists_preservesZeroSizes() {
			// Given
			int[] elements = {};
			int[] offsets = {0, 0};
			int[] sizes = {0, 0};
			ListViewData data = new ListViewData(elements, offsets, sizes, 2);
			ListViewEncoding sut = new ListViewEncoding();

			// When
			EncodeResult result = sut.encode(DTypes.LIST_I32, data);
			MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
			DecodeContext ctx = new DecodeContext(
					toArrayNode(result.rootNode()), DTypes.LIST_I32, 2, bufs, registry(), Arena.global());
			ListViewArray decoded = (ListViewArray) sut.decode(ctx);

			// Then
			assertThat(decoded.length()).isEqualTo(2);
			assertThat(decoded.elements().length()).isEqualTo(0);
			assertThat(decoded.offsets().length()).isEqualTo(2);
			assertThat(decoded.sizes().length()).isEqualTo(2);
		}

		@Test
		void roundTrip_singleList_preservesValues() {
			// Given
			int[] elements = {7, 8, 9};
			int[] offsets = {0};
			int[] sizes = {3};
			ListViewData data = new ListViewData(elements, offsets, sizes, 1);
			ListViewEncoding sut = new ListViewEncoding();

			// When
			EncodeResult result = sut.encode(DTypes.LIST_I32, data);
			MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
			DecodeContext ctx = new DecodeContext(
					toArrayNode(result.rootNode()), DTypes.LIST_I32, 1, bufs, registry(), Arena.global());
			ListViewArray decoded = (ListViewArray) sut.decode(ctx);

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
			ListViewEncoding sut = new ListViewEncoding();
			ArrayNode node = new ArrayNode(EncodingId.VORTEX_LISTVIEW, null,
					new ArrayNode[0], new int[0], ArrayStats.empty());
			DecodeContext ctx = TestDecodeContexts.of(node, DTypes.I32).registry(registry()).build();

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.hasMessageContaining("DType.List");
		}

		@Test
		void decode_wrongChildCount_throws() {
			// Given
			ListViewEncoding sut = new ListViewEncoding();
			ArrayNode child = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
					new ArrayNode[0], new int[0], ArrayStats.empty());
			ArrayNode node = new ArrayNode(EncodingId.VORTEX_LISTVIEW,
					java.nio.ByteBuffer.wrap(new byte[0]),
					new ArrayNode[]{child}, new int[0], ArrayStats.empty());
			DecodeContext ctx = TestDecodeContexts.of(node, DTypes.LIST_I32).registry(registry()).build();

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.hasMessageContaining("expected 3 or 4 children");
		}
	}
}
