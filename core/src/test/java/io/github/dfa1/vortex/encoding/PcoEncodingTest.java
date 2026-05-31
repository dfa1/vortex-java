package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PcoEncodingTest {

	@Nested
	class Encode {

		@Test
		void encodingId_isVortexPco() {
			// Given / When / Then
			assertThat(new PcoEncoding().encodingId()).isEqualTo(EncodingId.VORTEX_PCO);
		}

		@Test
		void encode_throwsVortexException() {
			// Given
			var sut = new PcoEncoding();
			DType dtype = new DType.Primitive(PType.I64, false);

			// When / Then
			assertThatThrownBy(() -> sut.encode(dtype, new long[]{1L, 2L, 3L}))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("not implemented");
		}
	}

	@Nested
	class Decode {

		@Test
		void decode_throwsVortexException() {
			// Given
			var sut = new PcoEncoding();
			DType dtype = new DType.Primitive(PType.I64, false);
			ArrayNode node = new ArrayNode(EncodingId.VORTEX_PCO, null, new ArrayNode[0], new int[0], null);
			DecodeContext ctx = new DecodeContext(node, dtype, 3, new MemorySegment[0],
					EncodingRegistry.empty(), Arena.ofAuto());

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("not implemented");
		}
	}
}
