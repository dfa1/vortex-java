package io.github.dfa1.vortex.encoding;

import com.google.protobuf.ByteString;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.proto.EncodingProtos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PcoEncodingTest {

	private static DecodeContext ctxWithMeta(ByteBuffer meta) {
		DType dtype = new DType.Primitive(PType.I64, false);
		ArrayNode node = new ArrayNode(EncodingId.VORTEX_PCO, meta, new ArrayNode[0], new int[0], null);
		return new DecodeContext(node, dtype, 3, new MemorySegment[0], EncodingRegistry.empty(), Arena.ofAuto());
	}

	private static ByteBuffer validMetaBuffer() {
		EncodingProtos.PcoMetadata meta = EncodingProtos.PcoMetadata.newBuilder()
				.setHeader(ByteString.copyFrom(new byte[]{PcoEncoding.PCO_FORMAT_MAJOR, PcoEncoding.PCO_FORMAT_MINOR}))
				.build();
		return ByteBuffer.wrap(meta.toByteArray());
	}

	@Nested
	class EncodingIdTest {

		@Test
		void encodingId_isVortexPco() {
			// Given / When / Then
			assertThat(new PcoEncoding().encodingId()).isEqualTo(EncodingId.VORTEX_PCO);
		}
	}

	@Nested
	class Encode {

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
		void decode_nullMetadata_throwsMissingMeta() {
			// Given
			var sut = new PcoEncoding();
			DecodeContext ctx = ctxWithMeta(null);

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("missing PcoMetadata");
		}

		@Test
		void decode_invalidHeaderVersion_throwsUnsupported() {
			// Given
			var sut = new PcoEncoding();
			EncodingProtos.PcoMetadata meta = EncodingProtos.PcoMetadata.newBuilder()
					.setHeader(ByteString.copyFrom(new byte[]{0x03, 0x00}))
					.build();
			DecodeContext ctx = ctxWithMeta(ByteBuffer.wrap(meta.toByteArray()));

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("unsupported pco format version 03.00");
		}

		@Test
		void decode_validHeader_throwsPhase2Pending() {
			// Given
			var sut = new PcoEncoding();
			DecodeContext ctx = ctxWithMeta(validMetaBuffer());

			// When / Then — Phase 1: skeleton parses meta + header, defers actual decode
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("Phase 2 pending");
		}
	}
}
