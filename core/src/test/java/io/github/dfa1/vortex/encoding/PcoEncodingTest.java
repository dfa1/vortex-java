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

	private static ByteBuffer validMetaBuffer() {
		EncodingProtos.PcoMetadata meta = EncodingProtos.PcoMetadata.newBuilder()
				.setHeader(ByteString.copyFrom(new byte[]{PcoEncoding.PCO_FORMAT_MAJOR, PcoEncoding.PCO_FORMAT_MINOR}))
				.build();
		return ByteBuffer.wrap(meta.toByteArray());
	}

	private static DecodeContext ctxWith(ByteBuffer meta, DType dtype, long rowCount, MemorySegment[] buffers) {
		ArrayNode node = new ArrayNode(EncodingId.VORTEX_PCO, meta, new ArrayNode[0],
				bufferIndices(buffers.length), null);
		return new DecodeContext(node, dtype, rowCount, buffers, EncodingRegistry.empty(), Arena.ofAuto());
	}

	private static int[] bufferIndices(int n) {
		int[] idx = new int[n];
		for (int i = 0; i < n; i++) {
			idx[i] = i;
		}
		return idx;
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
			DecodeContext ctx = ctxWith(null, new DType.Primitive(PType.I64, false), 0, new MemorySegment[0]);

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
			DecodeContext ctx = ctxWith(ByteBuffer.wrap(meta.toByteArray()),
					new DType.Primitive(PType.I64, false), 0, new MemorySegment[0]);

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("unsupported pco format version 03.00");
		}

		@Test
		void decode_nonPrimitiveDtype_throws() {
			// Given
			var sut = new PcoEncoding();
			DecodeContext ctx = ctxWith(validMetaBuffer(), new DType.Utf8(false), 0, new MemorySegment[0]);

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("Primitive dtype");
		}

		@Test
		void decode_unsupportedPtype_throwsPhase2Message() {
			// Given — F64 not yet supported in Phase 2
			var sut = new PcoEncoding();
			DecodeContext ctx = ctxWith(validMetaBuffer(), new DType.Primitive(PType.F64, false), 0,
					new MemorySegment[0]);

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("Phase 2");
		}

		@Test
		void decode_zeroChunks_returnsEmptyArray() {
			// Given — valid metadata with 0 chunks, 0 rows
			var sut = new PcoEncoding();
			DecodeContext ctx = ctxWith(validMetaBuffer(), new DType.Primitive(PType.I64, false), 0,
					new MemorySegment[0]);

			// When
			var result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isZero();
		}
	}
}
