package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.MaskedArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaskedEncodingTest {

	@Nested
	class Decode {

		@Test
		void oneChild_noValidity_allPositionsValid() {
			// Given
			var sut = new MaskedEncoding();
			EncodingRegistry registry = buildRegistry();
			DType i32Nullable = new DType.Primitive(PType.I32, true);
			EncodeResult ctx = maskedResult(new int[]{10, 20, 30}, null);

			// When
			Array result = sut.decode(EncodeTestHelper.toDecodeContext(ctx, 3L, i32Nullable, registry));

			// Then
			assertThat(result).isInstanceOf(MaskedArray.class);
			MaskedArray masked = (MaskedArray) result;
			assertThat(masked.length()).isEqualTo(3);
			assertThat(masked.isValid(0)).isTrue();
			assertThat(masked.isValid(1)).isTrue();
			assertThat(masked.isValid(2)).isTrue();
		}

		@Test
		void twoChildren_withValidity_masksNulls() {
			// Given
			var sut = new MaskedEncoding();
			EncodingRegistry registry = buildRegistry();
			DType i32Nullable = new DType.Primitive(PType.I32, true);
			EncodeResult ctx = maskedResult(new int[]{1, 2, 3, 4, 5},
					new boolean[]{true, false, true, false, true});

			// When
			Array result = sut.decode(EncodeTestHelper.toDecodeContext(ctx, 5L, i32Nullable, registry));

			// Then
			assertThat(result).isInstanceOf(MaskedArray.class);
			MaskedArray masked = (MaskedArray) result;
			assertThat(masked.length()).isEqualTo(5);
			assertThat(masked.isValid(0)).isTrue();
			assertThat(masked.isValid(1)).isFalse();
			assertThat(masked.isValid(2)).isTrue();
			assertThat(masked.isValid(3)).isFalse();
			assertThat(masked.isValid(4)).isTrue();
		}

		@Test
		void dtype_isNullable() {
			// Given
			var sut = new MaskedEncoding();
			EncodingRegistry registry = buildRegistry();
			DType i32Nullable = new DType.Primitive(PType.I32, true);
			EncodeResult ctx = maskedResult(new int[]{1, 2, 3}, null);

			// When
			Array result = sut.decode(EncodeTestHelper.toDecodeContext(ctx, 3L, i32Nullable, registry));

			// Then
			assertThat(result.dtype().nullable()).isTrue();
		}

		@Test
		void buffer_delegatesToChild() {
			// Given
			var sut = new MaskedEncoding();
			EncodingRegistry registry = buildRegistry();
			DType i32Nullable = new DType.Primitive(PType.I32, true);
			EncodeResult ctx = maskedResult(new int[]{7, 8, 9}, null);

			// When
			Array result = sut.decode(EncodeTestHelper.toDecodeContext(ctx, 3L, i32Nullable, registry));

			// Then — buffer(0) works and contains child values
			assertThat(result.buffer(0).get(PTypeIO.LE_INT, 0L)).isEqualTo(7);
			assertThat(result.buffer(0).get(PTypeIO.LE_INT, 4L)).isEqualTo(8);
			assertThat(result.buffer(0).get(PTypeIO.LE_INT, 8L)).isEqualTo(9);
		}

		@Test
		void buffersPresentThrows() {
			// Given
			var sut = new MaskedEncoding();
			EncodingRegistry registry = buildRegistry();
			DType i32Nullable = new DType.Primitive(PType.I32, true);

			// Build a node with an unexpected buffer index
			EncodeNode childNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
			EncodeNode maskedNode = new EncodeNode(
					EncodingId.VORTEX_MASKED, null,
					new EncodeNode[]{childNode}, new int[]{1});
			MemorySegment dummyBuf = Arena.ofAuto().allocate(4);
			EncodeResult result = new EncodeResult(maskedNode, List.of(dummyBuf, dummyBuf), null, null);

			// When / Then
			assertThatThrownBy(() ->
					sut.decode(EncodeTestHelper.toDecodeContext(result, 1L, i32Nullable, registry)))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("expected 0 buffers");
		}

		@Test
		void zeroChildrenThrows() {
			// Given
			var sut = new MaskedEncoding();
			EncodingRegistry registry = buildRegistry();
			DType i32Nullable = new DType.Primitive(PType.I32, true);

			EncodeNode maskedNode = new EncodeNode(
					EncodingId.VORTEX_MASKED, null, new EncodeNode[]{}, new int[]{});
			EncodeResult result = new EncodeResult(maskedNode, List.of(), null, null);

			// When / Then
			assertThatThrownBy(() ->
					sut.decode(EncodeTestHelper.toDecodeContext(result, 0L, i32Nullable, registry)))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("expected 1 or 2 children");
		}

		@Test
		void threeChildrenThrows() {
			// Given
			var sut = new MaskedEncoding();
			EncodingRegistry registry = buildRegistry();
			DType i32Nullable = new DType.Primitive(PType.I32, true);

			PrimitiveEncoding primitiveEncoding = new PrimitiveEncoding();
			DType i32 = new DType.Primitive(PType.I32, false);
			EncodeResult childResult = primitiveEncoding.encode(i32, new int[]{1}, EncodeTestHelper.testCtx());
			EncodeNode childNode = childResult.rootNode();
			EncodeNode maskedNode = new EncodeNode(
					EncodingId.VORTEX_MASKED, null,
					new EncodeNode[]{childNode, childNode, childNode}, new int[]{});
			List<MemorySegment> bufs = new ArrayList<>(childResult.buffers());
			EncodeResult result = new EncodeResult(maskedNode, bufs, null, null);

			// When / Then
			assertThatThrownBy(() ->
					sut.decode(EncodeTestHelper.toDecodeContext(result, 1L, i32Nullable, registry)))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("expected 1 or 2 children");
		}
	}

	// ── helpers ───────────────────────────────────────────────────────────────

	private static EncodingRegistry buildRegistry() {
		EncodingRegistry registry = TestRegistry.of(new MaskedEncoding(), new PrimitiveEncoding(), new BoolEncoding());
		return registry;
	}

	/// Build a synthetic masked EncodeResult wrapping an I32 child and optional validity.
	private static EncodeResult maskedResult(int[] values, boolean[] validity) {
		PrimitiveEncoding primitiveEncoding = new PrimitiveEncoding();
		DType i32 = new DType.Primitive(PType.I32, false);
		EncodeResult childResult = primitiveEncoding.encode(i32, values, EncodeTestHelper.testCtx());

		List<MemorySegment> allBuffers = new ArrayList<>(childResult.buffers());
		EncodeNode[] children;

		if (validity == null) {
			children = new EncodeNode[]{childResult.rootNode()};
		} else {
			BoolEncoding boolEncoding = new BoolEncoding();
			DType boolDtype = new DType.Bool(false);
			EncodeResult validityResult = boolEncoding.encode(boolDtype, validity, EncodeTestHelper.testCtx());
			EncodeNode remapped = EncodeNode.remapBufferIndices(
					validityResult.rootNode(), childResult.buffers().size());
			allBuffers.addAll(validityResult.buffers());
			children = new EncodeNode[]{childResult.rootNode(), remapped};
		}

		EncodeNode maskedNode = new EncodeNode(
				EncodingId.VORTEX_MASKED, null, children, new int[]{});
		return new EncodeResult(maskedNode, allBuffers, null, null);
	}
}
