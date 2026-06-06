package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.proto.DTypeProtos;
import io.github.dfa1.vortex.proto.EncodingProtos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FsstEncodingTest {

	@Nested
	class Encode {

		@Test
		void accepts_utf8_true() {
			// Given
			var sut = new FsstEncoding();

			// When / Then
			assertThat(sut.accepts(DTypes.UTF8)).isTrue();
		}

		@Test
		void accepts_binary_true() {
			// Given
			var sut = new FsstEncoding();

			// When / Then
			assertThat(sut.accepts(DTypes.BINARY)).isTrue();
		}

		@Test
		void accepts_primitive_false() {
			// Given
			var sut = new FsstEncoding();

			// When / Then
			assertThat(sut.accepts(DTypes.I32)).isFalse();
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("io.github.dfa1.vortex.encoding.FsstEncodingTest$Encode#stringArrays")
		void encode_thenDecode_roundtripsAllStrings(String name, String[] values) {
			// Given
			var sut = new FsstEncoding();
			Arena arena = Arena.ofAuto();

			// When
			EncodeResult result = sut.encode(DTypes.UTF8, values, EncodeTestHelper.testCtx());
			MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
			ArrayNode node = toArrayNode(result.rootNode());
			EncodingRegistry registry = EncodingRegistry.empty();
			registry.register(new PrimitiveEncoding());
			registry.register(sut);
			DecodeContext ctx = new DecodeContext(node, DTypes.UTF8, values.length, bufs, registry, arena);
			var decoded = (VarBinArray) sut.decode(ctx);

			// Then
			assertThat(decoded.length()).isEqualTo(values.length);
			for (int i = 0; i < values.length; i++) {
				assertThat(decoded.getString(i)).as("index %d", i).isEqualTo(values[i]);
			}
		}

		private static ArrayNode toArrayNode(EncodeNode node) {
			ArrayNode[] children = new ArrayNode[node.children().length];
			for (int i = 0; i < children.length; i++) {
				children[i] = toArrayNode(node.children()[i]);
			}
			return ArrayNode.of(node.encodingId(), node.metadata(), children, node.bufferIndices(), null);
		}

		static Stream<Arguments> stringArrays() {
			return Stream.of(
					Arguments.of("empty-array",         new String[0]),
					Arguments.of("single-empty-string", new String[]{""}),
					Arguments.of("short-strings",        new String[]{"hi", "ok", "no"}),
					Arguments.of("repeated-bigram",      new String[]{"aaaa", "aaaa", "aaaa"}),
					Arguments.of("long-strings",         new String[]{"the quick brown fox jumps over the lazy dog"}),
					Arguments.of("mixed-lengths",        new String[]{"a", "hello", "this is a longer string than twelve"}),
					Arguments.of("repeated-short",       repeat("a", 1)),
					Arguments.of("repeated-short",       repeat("ab", 50)),
					Arguments.of("all-empty",            new String[]{"", "", "", ""}),
					Arguments.of("unicode",              new String[]{"héllo", "wörld", "こんにちは"})
			);
		}

		private static String[] repeat(String s, int n) {
			String[] arr = new String[n];
			java.util.Arrays.fill(arr, s);
			return arr;
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
			ArrayNode node = ArrayNode.of(EncodingId.VORTEX_FSST, null, new ArrayNode[0], new int[0], null);
			DecodeContext ctx = new DecodeContext(node, DTypes.UTF8, 0, new MemorySegment[0],
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

			ArrayNode uncompLensNode = ArrayNode.of(
					EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{3}, null);
			ArrayNode codesOffNode = ArrayNode.of(
					EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{4}, null);
			ArrayNode root = ArrayNode.of(
					EncodingId.VORTEX_FSST, ByteBuffer.wrap(metaBytes),
					new ArrayNode[]{uncompLensNode, codesOffNode}, new int[]{0, 1, 2}, null);

			return new DecodeContext(root, DTypes.UTF8, n, segs, buildRegistry(), arena);
		}

		private static EncodingRegistry buildRegistry() {
			EncodingRegistry registry = EncodingRegistry.empty();
			registry.register(new PrimitiveEncoding());
			return registry;
		}
	}
}
