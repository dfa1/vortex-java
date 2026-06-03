package io.github.dfa1.vortex.encoding;

import com.google.protobuf.ByteString;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.proto.EncodingProtos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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

	private static MemorySegment segmentOf(byte... bytes) {
		MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
		for (int i = 0; i < bytes.length; i++) {
			seg.set(ValueLayout.JAVA_BYTE, i, bytes[i]);
		}
		return seg;
	}

	/// Build a PcoMetadata proto with one chunk containing one page of {@code nValues} values.
	private static ByteBuffer metaWithOneChunk(int nValues) {
		EncodingProtos.PcoMetadata meta = EncodingProtos.PcoMetadata.newBuilder()
				.setHeader(ByteString.copyFrom(new byte[]{PcoEncoding.PCO_FORMAT_MAJOR, PcoEncoding.PCO_FORMAT_MINOR}))
				.addChunks(EncodingProtos.PcoChunkInfo.newBuilder()
						.addPages(EncodingProtos.PcoPageInfo.newBuilder().setNValues(nValues).build())
						.build())
				.build();
		return ByteBuffer.wrap(meta.toByteArray());
	}

	/// Chunk-meta bytes for Classic mode, Consecutive delta at {@code order}, ansSizeLog=0, nBins=0.
	///
	/// Bit layout (LSB-first per byte):
	/// byte0: mode_nibble=0, delta_nibble=1
	/// byte1: order (3b), secondary_uses_delta=0 (1b), ansSizeLog=0 (4b)
	/// byte2–3: nBins=0 (15b), align padding
	private static MemorySegment chunkMetaConsecutive(int order) {
		return segmentOf(
				(byte) 0x10,        // mode=0, delta_variant=1
				(byte) order,       // order[2:0], secondary=0, ansSizeLog=0  (order ≤ 7)
				(byte) 0x00,        // nBins bits16-23 = 0
				(byte) 0x00         // nBins bits24-30 = 0, padding
		);
	}

	/// Page bytes: {@code order} LE-U64 moments, then 4 zero ANS-state slots (0 bits each).
	private static MemorySegment pageWithMoments(long... moments) {
		byte[] buf = new byte[moments.length * Long.BYTES];
		java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (long m : moments) {
			bb.putLong(m);
		}
		return segmentOf(buf);
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
		void decode_unsupportedPtype_throws() {
			// Given — F16 not supported by pco
			var sut = new PcoEncoding();
			DecodeContext ctx = ctxWith(validMetaBuffer(), new DType.Primitive(PType.F16, false), 0,
					new MemorySegment[0]);

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("unsupported ptype");
		}

		@ParameterizedTest
		@EnumSource(value = PType.class, names = {"I16", "U16", "I32", "U32", "F32", "I64", "U64", "F64"})
		void decode_zeroChunks_returnsEmptyArray(PType ptype) {
			// Given — valid metadata with 0 chunks, 0 rows, any supported ptype
			var sut = new PcoEncoding();
			DecodeContext ctx = ctxWith(validMetaBuffer(), new DType.Primitive(ptype, false), 0,
					new MemorySegment[0]);

			// When
			var result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isZero();
		}

		@Test
		void decode_consecutiveDelta_order1_singleValue_decodes() {
			// Given — U64 sequence [42] encoded with Classic mode, Consecutive delta order=1.
			// With pageN=1 and order=1, decodedN=0: the single output value is the moment itself.
			var sut = new PcoEncoding();
			DecodeContext ctx = ctxWith(
					metaWithOneChunk(1),
					new DType.Primitive(PType.U64, false),
					1,
					new MemorySegment[]{chunkMetaConsecutive(1), pageWithMoments(42L)});

			// When
			var result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(1);
			assertThat(((LongArray) result).getLong(0)).isEqualTo(42L);
		}

		@Test
		void decode_consecutiveDelta_order2_twoValues_decodes() {
			// Given — U64 sequence [10, 17] encoded with Consecutive delta order=2.
			// With pageN=2, order=2: decodedN=0; moments=[m0=10, m1=delta1=7].
			// Expected reconstruction: [m0, m0+m1] = [10, 17].
			var sut = new PcoEncoding();
			DecodeContext ctx = ctxWith(
					metaWithOneChunk(2),
					new DType.Primitive(PType.U64, false),
					2,
					new MemorySegment[]{chunkMetaConsecutive(2), pageWithMoments(10L, 7L)});

			// When
			var result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(2);
			assertThat(((LongArray) result).getLong(0)).isEqualTo(10L);
			assertThat(((LongArray) result).getLong(1)).isEqualTo(17L);
		}

		@Test
		void decode_multiPage_singleChunk_decodes() {
			// Given — 1 chunk, 2 pages each containing 1 value (Consecutive order=1).
			// buffers: [chunkMeta, page0, page1]; page0 moment=10→value 10, page1 moment=20→value 20.
			var sut = new PcoEncoding();
			EncodingProtos.PcoMetadata meta = EncodingProtos.PcoMetadata.newBuilder()
					.setHeader(ByteString.copyFrom(new byte[]{PcoEncoding.PCO_FORMAT_MAJOR, PcoEncoding.PCO_FORMAT_MINOR}))
					.addChunks(EncodingProtos.PcoChunkInfo.newBuilder()
							.addPages(EncodingProtos.PcoPageInfo.newBuilder().setNValues(1).build())
							.addPages(EncodingProtos.PcoPageInfo.newBuilder().setNValues(1).build())
							.build())
					.build();
			DecodeContext ctx = ctxWith(
					ByteBuffer.wrap(meta.toByteArray()),
					new DType.Primitive(PType.U64, false),
					2,
					new MemorySegment[]{chunkMetaConsecutive(1), pageWithMoments(10L), pageWithMoments(20L)});

			// When
			var result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(2);
			assertThat(((LongArray) result).getLong(0)).isEqualTo(10L);
			assertThat(((LongArray) result).getLong(1)).isEqualTo(20L);
		}

		@Test
		void decode_multiChunk_decodes() {
			// Given — 2 chunks each with 1 page containing 1 value (Consecutive order=1).
			// buffers: [chunkMeta0, page0, chunkMeta1, page1]; values=[100, 200].
			var sut = new PcoEncoding();
			EncodingProtos.PcoMetadata meta = EncodingProtos.PcoMetadata.newBuilder()
					.setHeader(ByteString.copyFrom(new byte[]{PcoEncoding.PCO_FORMAT_MAJOR, PcoEncoding.PCO_FORMAT_MINOR}))
					.addChunks(EncodingProtos.PcoChunkInfo.newBuilder()
							.addPages(EncodingProtos.PcoPageInfo.newBuilder().setNValues(1).build())
							.build())
					.addChunks(EncodingProtos.PcoChunkInfo.newBuilder()
							.addPages(EncodingProtos.PcoPageInfo.newBuilder().setNValues(1).build())
							.build())
					.build();
			DecodeContext ctx = ctxWith(
					ByteBuffer.wrap(meta.toByteArray()),
					new DType.Primitive(PType.U64, false),
					2,
					new MemorySegment[]{
							chunkMetaConsecutive(1), pageWithMoments(100L),
							chunkMetaConsecutive(1), pageWithMoments(200L)});

			// When
			var result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(2);
			assertThat(((LongArray) result).getLong(0)).isEqualTo(100L);
			assertThat(((LongArray) result).getLong(1)).isEqualTo(200L);
		}
	}
}
