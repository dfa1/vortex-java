package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.proto.DTypeProtos;
import io.github.dfa1.vortex.proto.EncodingProtos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FsstEncodingTest {

	private static final DType UTF8 = new DType.Utf8(false);

	@Nested
	class Encode {

		@Test
		void encode_throwsUnsupportedOperationException() {
			// Given
			var sut = new FsstEncoding();

			// When / Then
			assertThatThrownBy(() -> sut.encode(UTF8, new String[]{"hello"}))
					.isInstanceOf(UnsupportedOperationException.class);
		}
	}

	@Nested
	class Decode {

		@Test
		void decode_singleByteSymbol_decompressesCorrectly() {
			// Given: symbol 0 = 'A' (LE u64 = 0x41, length 1); string "AA" → codes [0, 0]
			var sut = new FsstEncoding();
			DecodeContext ctx = buildCtx(
					new long[]{0x41L},
					new byte[]{1},
					new byte[]{0x00, 0x00},
					new int[]{2},
					new int[]{0, 2},
					1
			);

			// When
			var result = sut.decode(ctx);

			// Then
			assertThat(result).isInstanceOf(VarBinArray.class);
			VarBinArray vba = (VarBinArray) result;
			assertThat(vba.length()).isEqualTo(1);
			assertThat(vba.getBytes(0)).isEqualTo("AA".getBytes(StandardCharsets.UTF_8));
		}

		@Test
		void decode_escapeByte_decompressesCorrectly() {
			// Given: no symbols; string "XY" → ESCAPE 'X' ESCAPE 'Y'
			var sut = new FsstEncoding();
			DecodeContext ctx = buildCtx(
					new long[0],
					new byte[0],
					new byte[]{(byte) 0xFF, 0x58, (byte) 0xFF, 0x59},
					new int[]{2},
					new int[]{0, 4},
					1
			);

			// When
			var result = sut.decode(ctx);

			// Then
			assertThat(result).isInstanceOf(VarBinArray.class);
			VarBinArray vba = (VarBinArray) result;
			assertThat(vba.length()).isEqualTo(1);
			assertThat(vba.getBytes(0)).isEqualTo("XY".getBytes(StandardCharsets.UTF_8));
		}

		@Test
		void decode_multiByteSymbol_decompressesCorrectly() {
			// Given: symbol 0 = "ab" (LE u64 = 0x6261, length 2); string "ab" → code [0]
			var sut = new FsstEncoding();
			DecodeContext ctx = buildCtx(
					new long[]{0x6261L},
					new byte[]{2},
					new byte[]{0x00},
					new int[]{2},
					new int[]{0, 1},
					1
			);

			// When
			var result = sut.decode(ctx);

			// Then
			assertThat(result).isInstanceOf(VarBinArray.class);
			VarBinArray vba = (VarBinArray) result;
			assertThat(vba.length()).isEqualTo(1);
			assertThat(vba.getBytes(0)).isEqualTo("ab".getBytes(StandardCharsets.UTF_8));
		}

		@Test
		void decode_multipleStrings_decompressesAll() {
			// Given: symbol 0 = 'H'; strings ["H", "HH", "!"] where "!" uses ESCAPE
			// compressed: [0x00] | [0x00, 0x00] | [0xFF, 0x21]
			var sut = new FsstEncoding();
			DecodeContext ctx = buildCtx(
					new long[]{0x48L},
					new byte[]{1},
					new byte[]{0x00, 0x00, 0x00, (byte) 0xFF, 0x21},
					new int[]{1, 2, 1},
					new int[]{0, 1, 3, 5},
					3
			);

			// When
			var result = sut.decode(ctx);

			// Then
			assertThat(result).isInstanceOf(VarBinArray.class);
			VarBinArray vba = (VarBinArray) result;
			assertThat(vba.length()).isEqualTo(3);
			assertThat(vba.getBytes(0)).isEqualTo("H".getBytes(StandardCharsets.UTF_8));
			assertThat(vba.getBytes(1)).isEqualTo("HH".getBytes(StandardCharsets.UTF_8));
			assertThat(vba.getBytes(2)).isEqualTo("!".getBytes(StandardCharsets.UTF_8));
		}

		@Test
		void decode_missingMetadata_throwsVortexException() {
			// Given
			var sut = new FsstEncoding();
			ArrayNode node = new ArrayNode(EncodingId.VORTEX_FSST, null, new ArrayNode[0], new int[0], null);
			DecodeContext ctx = new DecodeContext(node, UTF8, 0, new MemorySegment[0],
					buildRegistry(), Arena.ofAuto());

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class);
		}

		private static DecodeContext buildCtx(
				long[] symbols, byte[] symLens, byte[] compressed,
				int[] uncompLens, int[] codesOffsets, long n
		) {
			Arena arena = Arena.ofAuto();

			// Buffer 0: symbol table (8 bytes per symbol, LE u64)
			MemorySegment symBuf = arena.allocate(Math.max(symbols.length * 8L, 1), 8);
			for (int i = 0; i < symbols.length; i++) {
				symBuf.setAtIndex(PTypeIO.LE_LONG, i, symbols[i]);
			}

			// Buffer 1: symbol lengths (1 byte per symbol)
			MemorySegment symLenBuf = arena.allocate(Math.max(symLens.length, 1));
			for (int i = 0; i < symLens.length; i++) {
				symLenBuf.set(ValueLayout.JAVA_BYTE, i, symLens[i]);
			}

			// Buffer 2: compressed bytes
			MemorySegment compBuf = arena.allocate(Math.max(compressed.length, 1));
			for (int i = 0; i < compressed.length; i++) {
				compBuf.set(ValueLayout.JAVA_BYTE, i, compressed[i]);
			}

			// Buffer 3: uncompressed_lengths (I32)
			MemorySegment uncompLenBuf = arena.allocate((long) uncompLens.length * Integer.BYTES, Integer.BYTES);
			for (int i = 0; i < uncompLens.length; i++) {
				uncompLenBuf.setAtIndex(PTypeIO.LE_INT, i, uncompLens[i]);
			}

			// Buffer 4: codes_offsets (I32, n+1 elements)
			MemorySegment codesOffBuf = arena.allocate((long) codesOffsets.length * Integer.BYTES, Integer.BYTES);
			for (int i = 0; i < codesOffsets.length; i++) {
				codesOffBuf.setAtIndex(PTypeIO.LE_INT, i, codesOffsets[i]);
			}

			MemorySegment[] segs = {symBuf, symLenBuf, compBuf, uncompLenBuf, codesOffBuf};

			byte[] metaBytes = EncodingProtos.FSSTMetadata.newBuilder()
					.setUncompressedLengthsPtype(DTypeProtos.PType.forNumber(PType.I32.ordinal()))
					.setCodesOffsetsPtype(DTypeProtos.PType.forNumber(PType.I32.ordinal()))
					.build().toByteArray();

			ArrayNode uncompLensNode = new ArrayNode(
					EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{3}, null);
			ArrayNode codesOffNode = new ArrayNode(
					EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{4}, null);
			ArrayNode root = new ArrayNode(
					EncodingId.VORTEX_FSST, ByteBuffer.wrap(metaBytes),
					new ArrayNode[]{uncompLensNode, codesOffNode}, new int[]{0, 1, 2}, null);

			return new DecodeContext(root, UTF8, n, segs, buildRegistry(), arena);
		}

		private static EncodingRegistry buildRegistry() {
			EncodingRegistry registry = EncodingRegistry.empty();
			registry.register(new PrimitiveEncoding());
			return registry;
		}
	}
}
