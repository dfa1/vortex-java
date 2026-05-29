package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.array.NullArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;

class NullEncodingTest {

	private static final DType NULL_DTYPE = new DType.Null(true);

	private static DecodeContext buildNullCtx(long rowCount) {
		ArrayNode node = new ArrayNode(EncodingId.VORTEX_NULL, null, new ArrayNode[0], new int[0], null);
		EncodingRegistry registry = EncodingRegistry.empty();
		registry.register(new NullEncoding());
		return new DecodeContext(node, NULL_DTYPE, rowCount, new MemorySegment[0], registry,
				java.lang.foreign.Arena.ofAuto());
	}

	@Test
	void decode_nullArray_returnsNullArrayWithCorrectLength() {
		// Given
		long rowCount = 42L;
		DecodeContext ctx = buildNullCtx(rowCount);
		var sut = new NullEncoding();

		// When
		var result = sut.decode(ctx);

		// Then
		assertThat(result).isInstanceOf(NullArray.class);
		assertThat(result.length()).isEqualTo(rowCount);
		assertThat(result.dtype()).isEqualTo(NULL_DTYPE);
	}
}
