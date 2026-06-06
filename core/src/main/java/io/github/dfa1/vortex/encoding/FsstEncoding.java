package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.dfa1.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/// Decoder for {@code vortex.fsst} — Fast Static Symbol Table string compression.
///
/// <p>Wire format (3-buffer current format):
/// <ul>
///   <li>Buffer 0: symbol table — up to 256 entries, each 8 bytes LE (Symbol = u64)</li>
///   <li>Buffer 1: symbol_lengths — 1 byte per symbol, actual byte count in the symbol</li>
///   <li>Buffer 2: compressed code bytes for all strings concatenated</li>
///   <li>Child 0: uncompressed_lengths — primitive array (I32/I64) of original string lengths</li>
///   <li>Child 1: codes_offsets — primitive array (I32/I64) of offsets into buf[2], length = n+1</li>
/// </ul>
///
/// <p>Output: varbin-compatible {@code Array} (buffer[0] = uncompressed bytes, child[0] = I32 offsets).
public final class FsstEncoding implements Encoding {

	/// Creates a new {@code FsstEncoding} instance; use via {@link EncodingRegistry}.
	public FsstEncoding() {
	}

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_FSST;
	}

	@Override
	public boolean accepts(DType dtype) {
		return dtype instanceof DType.Utf8 || dtype instanceof DType.Binary;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
		return Encoder.encode((String[]) data, ctx);
	}

	@Override
	public Array decode(DecodeContext ctx) {
		return Decoder.decode(ctx);
	}

	private static final class Encoder {

		private static final int MAX_SYMBOLS = 255;
		private static final int BIGRAM_COUNT = 65536;

		static EncodeResult encode(String[] strings, EncodeContext ctx) {
			int n = strings.length;

			byte[][] byteArrays = new byte[n][];
			for (int i = 0; i < n; i++) {
				byteArrays[i] = strings[i].getBytes(StandardCharsets.UTF_8);
			}

			// Count bigram frequencies; pack freq+index into long for sort-free top-K
			int[] freq = new int[BIGRAM_COUNT];
			for (byte[] b : byteArrays) {
				for (int i = 0; i + 1 < b.length; i++) {
					freq[(Byte.toUnsignedInt(b[i]) << 8) | Byte.toUnsignedInt(b[i + 1])]++;
				}
			}
			long[] ranked = new long[BIGRAM_COUNT];
			for (int i = 0; i < BIGRAM_COUNT; i++) {
				ranked[i] = ((long) freq[i] << 16) | i;
			}
			Arrays.sort(ranked);

			// Build symbol table (top-255 bigrams, descending freq from end of sorted array)
			int numSymbols = 0;
			int[] codeForBigram = new int[BIGRAM_COUNT];
			Arrays.fill(codeForBigram, -1);
			long[] symbolValues = new long[MAX_SYMBOLS];
			for (int rank = BIGRAM_COUNT - 1; rank >= 0 && numSymbols < MAX_SYMBOLS; rank--) {
				int bg = (int) (ranked[rank] & 0xFFFF);
				if (freq[bg] == 0) {
					break;
				}
				codeForBigram[bg] = numSymbols;
				int hi = bg >>> 8;
				int lo = bg & 0xFF;
				symbolValues[numSymbols] = hi | ((long) lo << 8);
				numSymbols++;
			}

			// Compress each string
			byte[][] compressed = new byte[n][];
			for (int i = 0; i < n; i++) {
				compressed[i] = compressString(byteArrays[i], codeForBigram);
			}

			Arena arena = ctx.arena();

			// Buffer 0: symbol table (8 bytes per symbol, LE u64)
			MemorySegment symBuf = arena.allocate(Math.max(numSymbols * 8L, 1), 8);
			for (int i = 0; i < numSymbols; i++) {
				symBuf.setAtIndex(PTypeIO.LE_LONG, i, symbolValues[i]);
			}

			// Buffer 1: symbol lengths (all 2 = bigram)
			MemorySegment symLenBuf = arena.allocate(Math.max(numSymbols, 1));
			for (int i = 0; i < numSymbols; i++) {
				symLenBuf.set(ValueLayout.JAVA_BYTE, i, (byte) 2);
			}

			// Buffer 2: compressed bytes (all strings concatenated)
			int totalCompressed = 0;
			for (byte[] c : compressed) {
				totalCompressed += c.length;
			}
			MemorySegment compBuf = arena.allocate(Math.max(totalCompressed, 1));
			long pos = 0;
			for (byte[] c : compressed) {
				MemorySegment.copy(MemorySegment.ofArray(c), 0, compBuf, pos, c.length);
				pos += c.length;
			}

			// Buffer 3: uncompressed lengths (I32, n elements)
			MemorySegment uncompLenBuf = arena.allocate(Math.max(n * 4L, 1), 4);
			for (int i = 0; i < n; i++) {
				uncompLenBuf.setAtIndex(PTypeIO.LE_INT, i, byteArrays[i].length);
			}

			// Buffer 4: codes offsets (I32, n+1 elements)
			MemorySegment codesOffBuf = arena.allocate((long) (n + 1) * 4, 4);
			long off = 0;
			codesOffBuf.setAtIndex(PTypeIO.LE_INT, 0, 0);
			for (int i = 0; i < n; i++) {
				off += compressed[i].length;
				codesOffBuf.setAtIndex(PTypeIO.LE_INT, i + 1, (int) off);
			}

			byte[] metaBytes = EncodingProtos.FSSTMetadata.newBuilder()
					.setUncompressedLengthsPtype(
							io.github.dfa1.vortex.proto.DTypeProtos.PType.forNumber(PType.I32.ordinal()))
					.setCodesOffsetsPtype(
							io.github.dfa1.vortex.proto.DTypeProtos.PType.forNumber(PType.I32.ordinal()))
					.build()
					.toByteArray();

			EncodeNode uncompLensNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 3);
			EncodeNode codesOffNode   = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 4);
			EncodeNode root = new EncodeNode(
					EncodingId.VORTEX_FSST,
					ByteBuffer.wrap(metaBytes),
					new EncodeNode[]{uncompLensNode, codesOffNode},
					new int[]{0, 1, 2});

			return new EncodeResult(root,
					List.of(symBuf, symLenBuf, compBuf, uncompLenBuf, codesOffBuf),
					null, null);
		}

		private static byte[] compressString(byte[] input, int[] codeForBigram) {
			byte[] out = new byte[input.length * 2];
			int outLen = 0;
			int i = 0;
			while (i < input.length) {
				if (i + 1 < input.length) {
					int bg = (Byte.toUnsignedInt(input[i]) << 8) | Byte.toUnsignedInt(input[i + 1]);
					int code = codeForBigram[bg];
					if (code >= 0) {
						out[outLen++] = (byte) code;
						i += 2;
						continue;
					}
				}
				out[outLen++] = (byte) 0xFF;
				out[outLen++] = input[i];
				i++;
			}
			return Arrays.copyOf(out, outLen);
		}
	}

	private static final class Decoder {

		private static final int ESCAPE = 0xFF;

		private static Array decode(DecodeContext ctx) {
			ByteBuffer rawMeta = ctx.metadata();
			if (rawMeta == null) {
				throw new VortexException(EncodingId.VORTEX_FSST, "missing metadata");
			}
			EncodingProtos.FSSTMetadata meta;
			try {
				meta = EncodingProtos.FSSTMetadata.parseFrom(rawMeta.duplicate());
			} catch (InvalidProtocolBufferException e) {
				throw new VortexException(EncodingId.VORTEX_FSST, "invalid metadata", e);
			}

			PType uncompLenPType = PType.values()[meta.getUncompressedLengthsPtype().getNumber()];
			PType codesOffPType  = PType.values()[meta.getCodesOffsetsPtype().getNumber()];

			long n = ctx.rowCount();

			MemorySegment symbolsBuf      = ctx.buffer(0); // 8 bytes per symbol (LE u64)
			MemorySegment symbolLensBuf   = ctx.buffer(1); // 1 byte per symbol
			MemorySegment compressedBytes = ctx.buffer(2); // FSST-compressed heap

			Array uncompLens = decodeChild(ctx, 0, uncompLenPType, n);
			Array codesOffsets = decodeChild(ctx, 1, codesOffPType, n + 1);
			MemorySegment uncompLensSeg   = uncompLens.buffer(0);
			MemorySegment codesOffsetsSeg = codesOffsets.buffer(0);

			long totalUncompressed = 0L;
			for (long i = 0; i < n; i++) {
				totalUncompressed += readUnsigned(uncompLensSeg, i, uncompLenPType);
			}

			MemorySegment outBytes   = ctx.arena().allocate(totalUncompressed);
			MemorySegment outOffsets = ctx.arena().allocate((n + 1) * 4L, 4);
			outOffsets.setAtIndex(PTypeIO.LE_INT, 0, 0);

			long outPos = 0L;
			for (long i = 0; i < n; i++) {
				long cStart = readUnsigned(codesOffsetsSeg, i, codesOffPType);
				long cEnd   = readUnsigned(codesOffsetsSeg, i + 1, codesOffPType);
				outPos = decompressString(compressedBytes, symbolsBuf, symbolLensBuf,
						cStart, cEnd, outBytes, outPos);
				outOffsets.setAtIndex(PTypeIO.LE_INT, i + 1, (int) outPos);
			}

			DType i32 = new DType.Primitive(PType.I32, false);
			Array offsets = new IntArray(i32, n + 1, outOffsets.asReadOnly(), ArrayStats.empty());
			return new VarBinArray(ctx.dtype(), n, outBytes.asReadOnly(), offsets, PType.I32, ArrayStats.empty());
		}

		private static Array decodeChild(DecodeContext parent, int idx, PType ptype, long rowCount) {
			ArrayNode childNode = parent.node().children()[idx];
			DType dtype = new DType.Primitive(ptype, false);
			DecodeContext childCtx = new DecodeContext(
					childNode, dtype, rowCount,
					parent.segmentBuffers(), parent.registry(), parent.arena());
			return parent.registry().decode(childCtx);
		}

		private static long decompressString(
				MemorySegment compressed, MemorySegment symbols, MemorySegment symLens,
				long start, long end, MemorySegment out, long outPos
		) {
			for (long j = start; j < end; j++) {
				int b = Byte.toUnsignedInt(compressed.get(ValueLayout.JAVA_BYTE, j));
				if (b == ESCAPE) {
					// next byte is literal
					out.set(ValueLayout.JAVA_BYTE, outPos++, compressed.get(ValueLayout.JAVA_BYTE, ++j));
				} else {
					int symLen = Byte.toUnsignedInt(symLens.get(ValueLayout.JAVA_BYTE, b));
					// symbols[b] is 8 bytes LE; emit first symLen bytes
					long sym = symbols.getAtIndex(PTypeIO.LE_LONG, b);
					for (int k = 0; k < symLen; k++) {
						out.set(ValueLayout.JAVA_BYTE, outPos++, (byte) (sym >>> (k * 8)));
					}
				}
			}
			return outPos;
		}

		private static long readUnsigned(MemorySegment seg, long idx, PType ptype) {
			return switch (ptype) {
				case U8  -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, idx));
				case U16 -> Short.toUnsignedLong(seg.get(PTypeIO.LE_SHORT, idx * 2));
				case U32 -> Integer.toUnsignedLong(seg.getAtIndex(PTypeIO.LE_INT, idx));
				case I32 -> seg.getAtIndex(PTypeIO.LE_INT, idx);
				case I64, U64 -> seg.getAtIndex(PTypeIO.LE_LONG, idx);
				default  -> throw new VortexException(EncodingId.VORTEX_FSST, "unsupported ptype " + ptype);
			};
		}
	}
}
