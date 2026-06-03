package io.github.dfa1.vortex.encoding;

import com.google.protobuf.ByteString;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.proto.EncodingProtos;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

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

	/// Build a nullable DecodeContext: validity buffer at index 0, pco buffers at indices 1..N.
	/// Validity is a bit-packed Bool array (LSB-first, 1=valid).
	private static DecodeContext ctxWithValidity(ByteBuffer meta, DType dtype, long rowCount,
			MemorySegment validityBuf, MemorySegment[] pcoBuffers) {
		MemorySegment[] allBuffers = new MemorySegment[1 + pcoBuffers.length];
		allBuffers[0] = validityBuf;
		System.arraycopy(pcoBuffers, 0, allBuffers, 1, pcoBuffers.length);

		ArrayNode validityNode = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0],
				new int[]{0}, null);

		int[] pcoBufferIndices = new int[pcoBuffers.length];
		for (int i = 0; i < pcoBuffers.length; i++) {
			pcoBufferIndices[i] = i + 1;
		}
		ArrayNode pcoNode = new ArrayNode(EncodingId.VORTEX_PCO, meta, new ArrayNode[]{validityNode},
				pcoBufferIndices, null);

		EncodingRegistry registry = EncodingRegistry.empty();
		registry.register(new BoolEncoding());
		return new DecodeContext(pcoNode, dtype, rowCount, allBuffers, registry, Arena.ofAuto());
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

	@Nested
	class DecodeNullable {

		@Test
		void decode_nullable_someNulls_scattersCorrectly() {
			// Given — U64 sequence: 3 total rows, validity=[true,false,true], valid values=[100,200].
			// Validity bits LSB-first: bit0=1, bit1=0, bit2=1 → byte 0x05.
			// Pco encodes only valid values: 1 chunk, 2 pages of nValues=1 (Consecutive order=1).
			var sut = new PcoEncoding();
			EncodingProtos.PcoMetadata meta = EncodingProtos.PcoMetadata.newBuilder()
					.setHeader(ByteString.copyFrom(new byte[]{PcoEncoding.PCO_FORMAT_MAJOR, PcoEncoding.PCO_FORMAT_MINOR}))
					.addChunks(EncodingProtos.PcoChunkInfo.newBuilder()
							.addPages(EncodingProtos.PcoPageInfo.newBuilder().setNValues(1).build())
							.addPages(EncodingProtos.PcoPageInfo.newBuilder().setNValues(1).build())
							.build())
					.build();
			MemorySegment validityBuf = segmentOf((byte) 0x05); // bits: 1,0,1
			DecodeContext ctx = ctxWithValidity(
					ByteBuffer.wrap(meta.toByteArray()),
					new DType.Primitive(PType.U64, true),
					3,
					validityBuf,
					new MemorySegment[]{chunkMetaConsecutive(1), pageWithMoments(100L), pageWithMoments(200L)});

			// When
			var result = sut.decode(ctx);

			// Then — MaskedArray with 3 slots; positions 0 and 2 valid, position 1 null
			assertThat(result).isInstanceOf(MaskedArray.class);
			assertThat(result.length()).isEqualTo(3);
			MaskedArray masked = (MaskedArray) result;
			assertThat(masked.isValid(0)).isTrue();
			assertThat(masked.isValid(1)).isFalse();
			assertThat(masked.isValid(2)).isTrue();
			assertThat(((LongArray) masked.child(0)).getLong(0)).isEqualTo(100L);
			assertThat(((LongArray) masked.child(0)).getLong(2)).isEqualTo(200L);
		}

		@Test
		void decode_nullable_allNull_returnsAllZeroed() {
			// Given — 2 total rows, validity=[false,false], validCount=0. Pco has 0 chunks.
			// Validity bits LSB-first: 0x00.
			var sut = new PcoEncoding();
			MemorySegment validityBuf = segmentOf((byte) 0x00);
			DecodeContext ctx = ctxWithValidity(
					validMetaBuffer(),
					new DType.Primitive(PType.U64, true),
					2,
					validityBuf,
					new MemorySegment[0]);

			// When
			var result = sut.decode(ctx);

			// Then — MaskedArray, length 2, both null, values zeroed
			assertThat(result).isInstanceOf(MaskedArray.class);
			assertThat(result.length()).isEqualTo(2);
			MaskedArray masked = (MaskedArray) result;
			assertThat(masked.isValid(0)).isFalse();
			assertThat(masked.isValid(1)).isFalse();
			assertThat(((LongArray) masked.child(0)).getLong(0)).isZero();
			assertThat(((LongArray) masked.child(0)).getLong(1)).isZero();
		}

		@Test
		void decode_nullable_allValid_returnsMaskedWithAllValues() {
			// Given — 2 total rows, validity=[true,true], valid values=[10,20].
			// Validity bits: 0x03.
			var sut = new PcoEncoding();
			MemorySegment validityBuf = segmentOf((byte) 0x03); // bits: 1,1
			DecodeContext ctx = ctxWithValidity(
					metaWithOneChunk(2),
					new DType.Primitive(PType.U64, true),
					2,
					validityBuf,
					new MemorySegment[]{chunkMetaConsecutive(2), pageWithMoments(10L, 10L)});

			// When
			var result = sut.decode(ctx);

			// Then — MaskedArray, all valid, values [10, 20]
			assertThat(result).isInstanceOf(MaskedArray.class);
			assertThat(result.length()).isEqualTo(2);
			MaskedArray masked = (MaskedArray) result;
			assertThat(masked.isValid(0)).isTrue();
			assertThat(masked.isValid(1)).isTrue();
			assertThat(((LongArray) masked.child(0)).getLong(0)).isEqualTo(10L);
			assertThat(((LongArray) masked.child(0)).getLong(1)).isEqualTo(20L);
		}
	}

	/// Chunk-meta bytes for Classic mode + Lookback delta with windowNLog=1 (windowN=2), stateNLog=0 (stateN=1),
	/// deltaAnsSizeLog=0, primaryAnsSizeLog=0, no bins.
	///
	/// Bit layout:
	/// byte0: mode=0[3:0], delta=2[7:4] → 0x20
	/// bytes 1-6: windowNLog-1(5b)=0, stateNLog(4b)=0, secondary(1b)=0,
	///            deltaAnsSizeLog(4b)=0, nDeltaBins(15b)=0,
	///            primaryAnsSizeLog(4b)=0, nBins(15b)=0, align → all 0x00
	private static MemorySegment chunkMetaLookback() {
		return segmentOf((byte) 0x20, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00);
	}

	/// Page bytes for Lookback with stateN=1, U64, deltaAnsSizeLog=0, primaryAnsSizeLog=0.
	/// Format: 8 bytes (one 64-bit initial state). No ANS state bits (sizeLog=0). No decoded bits.
	private static MemorySegment lookbackPage(long initialState) {
		byte[] buf = new byte[Long.BYTES];
		java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(initialState);
		return segmentOf(buf);
	}

	@Nested
	class DecodeLookback {

		@Test
		void decode_lookback_corruptIndexZero_throwsVortexException() {
			// Given — Classic+Lookback, windowN=2, stateN=1, degenerate ANS (0 bins).
			// Degenerate tANS always outputs lower=0; lb=0 is out of [1, windowN=2].
			// pageN=2: stateN=1 initial value + 1 decoded value with lb=0.
			var sut = new PcoEncoding();
			DecodeContext ctx = ctxWith(
					metaWithOneChunk(2),
					new DType.Primitive(PType.U64, false),
					2,
					new MemorySegment[]{chunkMetaLookback(), lookbackPage(0L)});

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("corrupt lookback index 0");
		}

		@Test
		void decode_lookback_singleInitialValue_returnsIt() {
			// Given — pageN=1, stateN=1, decodeN=0: only the initial state value; no decoded values.
			var sut = new PcoEncoding();
			DecodeContext ctx = ctxWith(
					metaWithOneChunk(1),
					new DType.Primitive(PType.U64, false),
					1,
					new MemorySegment[]{chunkMetaLookback(), lookbackPage(42L)});

			// When
			var result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(1);
			assertThat(((LongArray) result).getLong(0)).isEqualTo(42L);
		}
	}

	/// Adversarial coverage: malformed inputs must throw VortexException — never AIOOBE, NPE, or OOM.
	@Nested
	class Adversarial {

		/// Random chunk-meta bytes — any exception must be a VortexException, not a JVM crash exception.
		@Property(tries = 50)
		void randomChunkMetaBytes_neverThrowsJvmException(
				@ForAll @Size(min = 1, max = 64) byte[] chunkMetaBytes) {
			// Given — valid pco header + 1 chunk with 1 page of 1 value; garbage chunk-meta bytes.
			var sut = new PcoEncoding();
			DecodeContext ctx = ctxWith(
					metaWithOneChunk(1),
					new DType.Primitive(PType.U64, false),
					1,
					new MemorySegment[]{segmentOf(chunkMetaBytes), segmentOf((byte) 0x00)});

			// When / Then — either succeeds or throws VortexException; never AIOOBE/NPE/OOM
			try {
				sut.decode(ctx);
			} catch (VortexException ignored) {
				// expected — malformed input
			}
		}

		/// Random page bytes after a valid Classic-mode chunk meta — must not crash the JVM.
		@Property(tries = 50)
		void randomPageBytes_classicMode_neverThrowsJvmException(
				@ForAll @Size(min = 4, max = 128) byte[] pageBytes) {
			// Given — Classic mode, delta=NoOp, ansSizeLog=0, nBins=0 chunk meta.
			var sut = new PcoEncoding();
			// byte0: mode=0 (bits3:0), deltaVariant=0 (bits7:4) → 0x00
			// byte1: ansSizeLog=0 (bits3:0), nBins low bits = 0
			// bytes 2-3: nBins high bits = 0
			DecodeContext ctx = ctxWith(
					metaWithOneChunk(1),
					new DType.Primitive(PType.U64, false),
					1,
					new MemorySegment[]{segmentOf((byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00),
							segmentOf(pageBytes)});

			// When / Then
			try {
				sut.decode(ctx);
			} catch (VortexException ignored) {
				// expected — malformed page data
			}
		}

		/// Invalid mode nibbles (5–15) must produce a VortexException naming the mode number.
		@ParameterizedTest
		@ValueSource(ints = {5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
		void invalidModeNibble_throwsVortexException(int modeNibble) {
			// Given — chunk meta with unsupported mode nibble in bits[3:0].
			var sut = new PcoEncoding();
			// bits[3:0] = modeNibble, delta nibble doesn't matter (won't be reached)
			byte modeByte = (byte) (modeNibble & 0x0F);
			DecodeContext ctx = ctxWith(
					metaWithOneChunk(1),
					new DType.Primitive(PType.U64, false),
					1,
					new MemorySegment[]{
							segmentOf(modeByte, (byte) 0x00, (byte) 0x00, (byte) 0x00),
							segmentOf((byte) 0x00)});

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("pco mode " + modeNibble);
		}

		/// Invalid delta variants (4–15) must produce a VortexException naming the variant number.
		@ParameterizedTest
		@ValueSource(ints = {4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
		void invalidDeltaVariant_throwsVortexException(int deltaVariant) {
			// Given — Classic mode (nibble=0) + invalid delta nibble in bits[7:4].
			var sut = new PcoEncoding();
			// byte0: bits[3:0]=mode=0, bits[7:4]=deltaVariant
			byte modeDeltaByte = (byte) ((deltaVariant & 0x0F) << 4);
			DecodeContext ctx = ctxWith(
					metaWithOneChunk(1),
					new DType.Primitive(PType.U64, false),
					1,
					new MemorySegment[]{
							segmentOf(modeDeltaByte, (byte) 0x00, (byte) 0x00, (byte) 0x00),
							segmentOf((byte) 0x00)});

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("delta variant " + deltaVariant);
		}

		/// Conv1 delta with 64-bit dtype must throw VortexException; pcodec only supports 16/32-bit Conv1.
		@ParameterizedTest
		@EnumSource(value = PType.class, names = {"I64", "U64", "F64"})
		void conv1Delta_with64BitDtype_throwsVortexException(PType ptype) {
			// Given — Conv1 delta variant (nibble=3 in bits[7:4]), Classic mode (nibble=0 in bits[3:0]).
			// byte0: bits[3:0]=0 (Classic), bits[7:4]=3 (Conv1) → 0x30
			// Remaining bytes: conv1 bit fields (don't matter — error fires before parsing them).
			var sut = new PcoEncoding();
			DecodeContext ctx = ctxWith(
					metaWithOneChunk(1),
					new DType.Primitive(ptype, false),
					1,
					new MemorySegment[]{
							segmentOf((byte) 0x30, (byte) 0x00, (byte) 0x00, (byte) 0x00,
									(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
									(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
									(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00),
							segmentOf((byte) 0x00)});

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("Conv1");
		}
	}
}
