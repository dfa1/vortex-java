package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.DTypeProtos;
import dev.vortex.proto.EncodingProtos;
import dev.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.List;

/// Encoding for {@code fastlanes.bitpacked} — spec-compliant FastLanes bit-packing.
///
/// <p>Metadata: protobuf {@code BitPackedMetadata} — {@code bit_width u32} (tag 1),
/// {@code offset u32} (tag 2, element offset within the first 1024-element block).
///
/// <p>Buffer layout: {@code ceil((len + offset) / 1024)} blocks, each block {@code 128 * bit_width}
/// bytes. Within each block the values are transposed using the FastLanes FL_ORDER permutation so
/// that adjacent bit-planes are contiguous (enables SIMD-friendly decompression).
public final class BitpackedEncoding implements Encoding {

	// FL_ORDER permutation from the FastLanes paper / spiraldb/fastlanes-rs.
	static final int[] FL_ORDER = {0, 4, 2, 6, 1, 5, 3, 7};

	@Override
	public EncodingId encodingId() {
		return EncodingId.FASTLANES_BITPACKED;
	}

	@Override
	public boolean accepts(DType dtype) {
		if (!(dtype instanceof DType.Primitive p)) {
			return false;
		}
		return switch (p.ptype()) {
			case I8, I16, I32, I64, U8, U16, U32, U64 -> true;
			default -> false;
		};
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		return Encoder.encode(dtype, data);
	}

	@Override
	public Array decode(DecodeContext ctx) {
		return Decoder.decode(ctx);
	}

	private static final class Encoder {

		static EncodeResult encode(DType dtype, Object data) {
			PType ptype = ((DType.Primitive) dtype).ptype();
			long[] longs = toLongs(data, ptype);
			int n = longs.length;
			int typeBits = ptype.byteSize() * 8;
			long typeMask = typeMask(typeBits);
			boolean unsign = isUnsigned(ptype);

			long signedMin = 0L;
			long signedMax = 0L;
			long maxUnsigned = 0L;
			int bitWidth = 0;

			if (n > 0) {
				signedMin = longs[0];
				signedMax = longs[0];
				for (int i = 0; i < n; i++) {
					long v = longs[i];
					if (unsign ? Long.compareUnsigned(v, signedMin) < 0 : v < signedMin) {
						signedMin = v;
					}
					if (unsign ? Long.compareUnsigned(v, signedMax) > 0 : v > signedMax) {
						signedMax = v;
					}
					long uv = v & typeMask;
					if (Long.compareUnsigned(uv, maxUnsigned) > 0) {
						maxUnsigned = uv;
					}
				}
				bitWidth = maxUnsigned == 0L ? 0 : (Long.SIZE - Long.numberOfLeadingZeros(maxUnsigned));
			}

			MemorySegment packed = packFastLanes(longs, n, bitWidth, typeBits);

			byte[] metaBytes = EncodingProtos.BitPackedMetadata.newBuilder()
					.setBitWidth(bitWidth)
					.setOffset(0)
					.build()
					.toByteArray();

			byte[] statsMin = n > 0 ? statsBytes(ptype, signedMin) : null;
			byte[] statsMax = n > 0 ? statsBytes(ptype, signedMax) : null;

			EncodeNode root = new EncodeNode(EncodingId.FASTLANES_BITPACKED, ByteBuffer.wrap(metaBytes),
					new EncodeNode[0], new int[]{0});
			return new EncodeResult(root, List.of(packed), statsMin, statsMax);
		}

		private static MemorySegment packFastLanes(long[] values, int n, int bitWidth, int typeBits) {
			if (bitWidth == 0 || n == 0) {
				return MemorySegment.ofArray(new byte[0]);
			}
			int lanes = 1024 / typeBits;
			int wordBytes = typeBits / 8;
			int blockCount = (n + 1023) / 1024;
			long typeMask = typeMask(typeBits);
			MemorySegment seg = Arena.ofAuto().allocate((long) blockCount * 128 * bitWidth);

			for (int block = 0; block < blockCount; block++) {
				int blockByteOff = block * 128 * bitWidth;
				int blockStart = block * 1024;

				for (int row = 0; row < typeBits; row++) {
					int currWord = (row * bitWidth) / typeBits;
					int nextWord = ((row + 1) * bitWidth) / typeBits;
					int shift = (row * bitWidth) % typeBits;
					int remainingBits = (nextWord > currWord) ? ((row + 1) * bitWidth) % typeBits : 0;
					int currentBits = bitWidth - remainingBits;

					for (int lane = 0; lane < lanes; lane++) {
						int o = row / 8;
						int s = row % 8;
						int logicalIdx = blockStart + FL_ORDER[o] * 16 + s * 128 + lane;
						long value = (logicalIdx < n) ? (values[logicalIdx] & typeMask) : 0L;

						int wordOff = blockByteOff + (lanes * currWord + lane) * wordBytes;
						long existing = readWordFromSeg(seg, wordOff, typeBits);
						existing |= (value << shift) & typeMask;
						writeWordToSeg(seg, wordOff, existing, typeBits);

						if (remainingBits > 0) {
							int hiWordOff = blockByteOff + (lanes * nextWord + lane) * wordBytes;
							long existingHi = readWordFromSeg(seg, hiWordOff, typeBits);
							existingHi |= (value >>> currentBits) & typeMask;
							writeWordToSeg(seg, hiWordOff, existingHi, typeBits);
						}
					}
				}
			}
			return seg;
		}

		private static long[] toLongs(Object data, PType ptype) {
			return switch (ptype) {
				case I8 -> {
					byte[] arr = (byte[]) data;
					long[] r = new long[arr.length];
					for (int i = 0; i < arr.length; i++) {
						r[i] = arr[i];
					}
					yield r;
				}
				case U8 -> {
					byte[] arr = (byte[]) data;
					long[] r = new long[arr.length];
					for (int i = 0; i < arr.length; i++) {
						r[i] = Byte.toUnsignedLong(arr[i]);
					}
					yield r;
				}
				case I16 -> {
					short[] arr = (short[]) data;
					long[] r = new long[arr.length];
					for (int i = 0; i < arr.length; i++) {
						r[i] = arr[i];
					}
					yield r;
				}
				case U16 -> {
					short[] arr = (short[]) data;
					long[] r = new long[arr.length];
					for (int i = 0; i < arr.length; i++) {
						r[i] = Short.toUnsignedLong(arr[i]);
					}
					yield r;
				}
				case I32 -> {
					int[] arr = (int[]) data;
					long[] r = new long[arr.length];
					for (int i = 0; i < arr.length; i++) {
						r[i] = arr[i];
					}
					yield r;
				}
				case U32 -> {
					int[] arr = (int[]) data;
					long[] r = new long[arr.length];
					for (int i = 0; i < arr.length; i++) {
						r[i] = Integer.toUnsignedLong(arr[i]);
					}
					yield r;
				}
				case I64, U64 -> (long[]) data;
				default -> throw new VortexException(EncodingId.FASTLANES_BITPACKED, "unsupported ptype: " + ptype);
			};
		}

		private static long typeMask(int typeBits) {
			return typeBits == 64 ? -1L : (1L << typeBits) - 1L;
		}

		private static boolean isUnsigned(PType ptype) {
			return switch (ptype) {
				case U8, U16, U32, U64 -> true;
				default -> false;
			};
		}

		private static byte[] statsBytes(PType ptype, long value) {
			if (isUnsigned(ptype)) {
				return ScalarProtos.ScalarValue.newBuilder().setUint64Value(value).build().toByteArray();
			}
			return ScalarProtos.ScalarValue.newBuilder().setInt64Value(value).build().toByteArray();
		}

		private static long readWordFromSeg(MemorySegment seg, int off, int typeBits) {
			return switch (typeBits) {
				case 8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, off));
				case 16 -> Short.toUnsignedLong(seg.get(PTypeIO.LE_SHORT, off));
				case 32 -> Integer.toUnsignedLong(seg.get(PTypeIO.LE_INT, off));
				case 64 -> seg.get(PTypeIO.LE_LONG, off);
				default -> throw new VortexException(EncodingId.FASTLANES_BITPACKED, "unsupported typeBits: " + typeBits);
			};
		}

		private static void writeWordToSeg(MemorySegment seg, int off, long value, int typeBits) {
			switch (typeBits) {
				case 8 -> seg.set(ValueLayout.JAVA_BYTE, off, (byte) value);
				case 16 -> seg.set(PTypeIO.LE_SHORT, off, (short) value);
				case 32 -> seg.set(PTypeIO.LE_INT, off, (int) value);
				case 64 -> seg.set(PTypeIO.LE_LONG, off, value);
				default -> throw new VortexException(EncodingId.FASTLANES_BITPACKED, "unsupported typeBits: " + typeBits);
			}
		}
	}

	private static final class Decoder {

		static Array decode(DecodeContext ctx) {
			ByteBuffer rawMeta = ctx.metadata();
			if (rawMeta == null) {
				throw new VortexException(EncodingId.FASTLANES_BITPACKED, "missing metadata");
			}

			EncodingProtos.BitPackedMetadata meta;
			try {
				meta = EncodingProtos.BitPackedMetadata.parseFrom(rawMeta.duplicate());
			} catch (InvalidProtocolBufferException e) {
				throw new VortexException(EncodingId.FASTLANES_BITPACKED, "invalid metadata", e);
			}

			int bitWidth = meta.getBitWidth();
			int offset = meta.getOffset();
			PType ptype = ((DType.Primitive) ctx.dtype()).ptype();
			int typeBits = ptype.byteSize() * 8;
			long rowCount = ctx.rowCount();

			MemorySegment packed = ctx.buffer(0);
			MemorySegment output = ctx.arena().allocate(rowCount * ptype.byteSize());
			fastlanesUnpackToSeg(packed, bitWidth, offset, typeBits, rowCount, output);

			if (meta.hasPatches()) {
				applyPatches(ctx, meta.getPatches(), output, ptype.byteSize());
			}

			return switch (ptype) {
				case I64, U64 -> new LongArray(ctx.dtype(), rowCount, output, ArrayStats.empty());
				case I32, U32 -> new IntArray(ctx.dtype(), rowCount, output, ArrayStats.empty());
				case I16, U16 -> new ShortArray(ctx.dtype(), rowCount, output, ArrayStats.empty());
				case I8, U8   -> new ByteArray(ctx.dtype(), rowCount, output, ArrayStats.empty());
				default -> throw new VortexException(EncodingId.FASTLANES_BITPACKED, "unsupported ptype " + ptype);
			};
		}

		private static void fastlanesUnpackToSeg(
				MemorySegment buf, int bitWidth, int offset, int typeBits, long rowCount,
				MemorySegment output) {
			if (bitWidth == 0) {
				return;
			}
			switch (typeBits) {
				case 8  -> unpackLoop8(buf, bitWidth, offset, rowCount, output);
				case 16 -> unpackLoop16(buf, bitWidth, offset, rowCount, output);
				case 32 -> unpackLoop32(buf, bitWidth, offset, rowCount, output);
				case 64 -> unpackLoop64(buf, bitWidth, offset, rowCount, output);
				default -> throw new VortexException(EncodingId.FASTLANES_BITPACKED, "unsupported typeBits: " + typeBits);
			}
		}

		private static void unpackLoop8(MemorySegment buf, int bitWidth, int offset, long rowCount, MemorySegment out) {
			final int lanes = 128;
			long totalElems = rowCount + offset;
			int blockCount = (int) ((totalElems + 1023) / 1024);
			long bitMask = (1L << bitWidth) - 1L;

			long blockByteOff = 0L;
			long blockByteStride = 128L * bitWidth;
			for (int block = 0; block < blockCount; block++, blockByteOff += blockByteStride) {
				int blockLogicStart = block * 1024 - offset;
				boolean fullBlock = blockLogicStart >= 0 && (long) blockLogicStart + 1023L < rowCount;

				for (int row = 0; row < 8; row++) {
					int currWord = (row * bitWidth) / 8;
					int nextWord = ((row + 1) * bitWidth) / 8;
					int shift = (row * bitWidth) % 8;
					int remainingBits = (nextWord > currWord) ? ((row + 1) * bitWidth) % 8 : 0;
					int currentBits = bitWidth - remainingBits;
					int o = row / 8;
					int s = row % 8;
					int baseIdx = blockLogicStart + FL_ORDER[o] * 16 + s * 128;
					long wordBase = blockByteOff + (long) lanes * currWord;

					if (fullBlock) {
						if (remainingBits > 0) {
							long hiBase = blockByteOff + (long) lanes * nextWord;
							long loMask = (1L << currentBits) - 1L;
							long hiMask = (1L << remainingBits) - 1L;
							for (int lane = 0; lane < lanes; lane++) {
								long lo = (Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, wordBase + lane)) >>> shift) & loMask;
								long hi = Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, hiBase + lane)) & hiMask;
								out.set(ValueLayout.JAVA_BYTE, baseIdx + lane, (byte) (lo | (hi << currentBits)));
							}
						} else {
							for (int lane = 0; lane < lanes; lane++) {
								out.set(ValueLayout.JAVA_BYTE, baseIdx + lane,
										(byte) ((Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, wordBase + lane)) >>> shift) & bitMask));
							}
						}
					} else {
						long hiBase = (remainingBits > 0) ? blockByteOff + (long) lanes * nextWord : 0L;
						long loMask = (remainingBits > 0) ? (1L << currentBits) - 1L : 0L;
						long hiMask = (remainingBits > 0) ? (1L << remainingBits) - 1L : 0L;
						for (int lane = 0; lane < lanes; lane++) {
							int logicalIdx = baseIdx + lane;
							if (logicalIdx < 0 || logicalIdx >= rowCount) {
								continue;
							}
							long src = Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, wordBase + lane));
							long value;
							if (remainingBits > 0) {
								long lo = (src >>> shift) & loMask;
								long hi = Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, hiBase + lane)) & hiMask;
								value = lo | (hi << currentBits);
							} else {
								value = (src >>> shift) & bitMask;
							}
							out.set(ValueLayout.JAVA_BYTE, logicalIdx, (byte) value);
						}
					}
				}
			}
		}

		private static void unpackLoop16(MemorySegment buf, int bitWidth, int offset, long rowCount, MemorySegment out) {
			final int lanes = 64;
			long totalElems = rowCount + offset;
			int blockCount = (int) ((totalElems + 1023) / 1024);
			long bitMask = (1L << bitWidth) - 1L;

			long blockByteOff = 0L;
			long blockByteStride = 128L * bitWidth;
			for (int block = 0; block < blockCount; block++, blockByteOff += blockByteStride) {
				int blockLogicStart = block * 1024 - offset;
				boolean fullBlock = blockLogicStart >= 0 && (long) blockLogicStart + 1023L < rowCount;

				for (int row = 0; row < 16; row++) {
					int currWord = (row * bitWidth) / 16;
					int nextWord = ((row + 1) * bitWidth) / 16;
					int shift = (row * bitWidth) % 16;
					int remainingBits = (nextWord > currWord) ? ((row + 1) * bitWidth) % 16 : 0;
					int currentBits = bitWidth - remainingBits;
					int o = row / 8;
					int s = row % 8;
					int baseIdx = blockLogicStart + FL_ORDER[o] * 16 + s * 128;
					long wordBase = blockByteOff + (long) lanes * currWord * 2;

					if (fullBlock) {
						long outBase = (long) baseIdx * 2;
						if (remainingBits > 0) {
							long hiBase = blockByteOff + (long) lanes * nextWord * 2;
							long loMask = (1L << currentBits) - 1L;
							long hiMask = (1L << remainingBits) - 1L;
							long laneOff = 0L;
							for (int lane = 0; lane < lanes; lane++, laneOff += 2L) {
								long lo = (Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, wordBase + laneOff)) >>> shift) & loMask;
								long hi = Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, hiBase + laneOff)) & hiMask;
								out.set(PTypeIO.LE_SHORT, outBase + laneOff, (short) (lo | (hi << currentBits)));
							}
						} else {
							long laneOff = 0L;
							for (int lane = 0; lane < lanes; lane++, laneOff += 2L) {
								out.set(PTypeIO.LE_SHORT, outBase + laneOff,
										(short) ((Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, wordBase + laneOff)) >>> shift) & bitMask));
							}
						}
					} else {
						long hiBase = (remainingBits > 0) ? blockByteOff + (long) lanes * nextWord * 2 : 0L;
						long loMask = (remainingBits > 0) ? (1L << currentBits) - 1L : 0L;
						long hiMask = (remainingBits > 0) ? (1L << remainingBits) - 1L : 0L;
						for (int lane = 0; lane < lanes; lane++) {
							int logicalIdx = baseIdx + lane;
							if (logicalIdx < 0 || logicalIdx >= rowCount) {
								continue;
							}
							long src = Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, wordBase + (long) lane * 2));
							long value;
							if (remainingBits > 0) {
								long lo = (src >>> shift) & loMask;
								long hi = Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, hiBase + (long) lane * 2)) & hiMask;
								value = lo | (hi << currentBits);
							} else {
								value = (src >>> shift) & bitMask;
							}
							out.set(PTypeIO.LE_SHORT, (long) logicalIdx * 2, (short) value);
						}
					}
				}
			}
		}

		private static void unpackLoop32(MemorySegment buf, int bitWidth, int offset, long rowCount, MemorySegment out) {
			final int lanes = 32;
			long totalElems = rowCount + offset;
			int blockCount = (int) ((totalElems + 1023) / 1024);
			long bitMask = (1L << bitWidth) - 1L;

			long blockByteOff = 0L;
			long blockByteStride = 128L * bitWidth;
			for (int block = 0; block < blockCount; block++, blockByteOff += blockByteStride) {
				int blockLogicStart = block * 1024 - offset;
				boolean fullBlock = blockLogicStart >= 0 && (long) blockLogicStart + 1023L < rowCount;

				for (int row = 0; row < 32; row++) {
					int currWord = (row * bitWidth) / 32;
					int nextWord = ((row + 1) * bitWidth) / 32;
					int shift = (row * bitWidth) % 32;
					int remainingBits = (nextWord > currWord) ? ((row + 1) * bitWidth) % 32 : 0;
					int currentBits = bitWidth - remainingBits;
					int o = row / 8;
					int s = row % 8;
					int baseIdx = blockLogicStart + FL_ORDER[o] * 16 + s * 128;
					long wordBase = blockByteOff + (long) lanes * currWord * 4;

					if (fullBlock) {
						long outBase = (long) baseIdx * 4;
						if (remainingBits > 0) {
							long hiBase = blockByteOff + (long) lanes * nextWord * 4;
							long loMask = (1L << currentBits) - 1L;
							long hiMask = (1L << remainingBits) - 1L;
							long laneOff = 0L;
							for (int lane = 0; lane < lanes; lane++, laneOff += 4L) {
								long lo = (Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, wordBase + laneOff)) >>> shift) & loMask;
								long hi = Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, hiBase + laneOff)) & hiMask;
								out.set(PTypeIO.LE_INT, outBase + laneOff, (int) (lo | (hi << currentBits)));
							}
						} else {
							long laneOff = 0L;
							for (int lane = 0; lane < lanes; lane++, laneOff += 4L) {
								out.set(PTypeIO.LE_INT, outBase + laneOff,
										(int) ((Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, wordBase + laneOff)) >>> shift) & bitMask));
							}
						}
					} else {
						long hiBase = (remainingBits > 0) ? blockByteOff + (long) lanes * nextWord * 4 : 0L;
						long loMask = (remainingBits > 0) ? (1L << currentBits) - 1L : 0L;
						long hiMask = (remainingBits > 0) ? (1L << remainingBits) - 1L : 0L;
						for (int lane = 0; lane < lanes; lane++) {
							int logicalIdx = baseIdx + lane;
							if (logicalIdx < 0 || logicalIdx >= rowCount) {
								continue;
							}
							long src = Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, wordBase + (long) lane * 4));
							long value;
							if (remainingBits > 0) {
								long lo = (src >>> shift) & loMask;
								long hi = Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, hiBase + (long) lane * 4)) & hiMask;
								value = lo | (hi << currentBits);
							} else {
								value = (src >>> shift) & bitMask;
							}
							out.set(PTypeIO.LE_INT, (long) logicalIdx * 4, (int) value);
						}
					}
				}
			}
		}

		private static void unpackLoop64(MemorySegment buf, int bitWidth, int offset, long rowCount, MemorySegment out) {
			final int lanes = 16;
			long totalElems = rowCount + offset;
			int blockCount = (int) ((totalElems + 1023) / 1024);
			long bitMask = bitWidth == 64 ? -1L : (1L << bitWidth) - 1L;

			long blockByteOff = 0L;
			long blockByteStride = 128L * bitWidth;
			for (int block = 0; block < blockCount; block++, blockByteOff += blockByteStride) {
				int blockLogicStart = block * 1024 - offset;
				boolean fullBlock = blockLogicStart >= 0 && (long) blockLogicStart + 1023L < rowCount;

				for (int row = 0; row < 64; row++) {
					int currWord = (row * bitWidth) / 64;
					int nextWord = ((row + 1) * bitWidth) / 64;
					int shift = (row * bitWidth) % 64;
					int remainingBits = (nextWord > currWord) ? ((row + 1) * bitWidth) % 64 : 0;
					int currentBits = bitWidth - remainingBits;
					int o = row / 8;
					int s = row % 8;
					int baseIdx = blockLogicStart + FL_ORDER[o] * 16 + s * 128;
					long wordBase = blockByteOff + (long) lanes * currWord * 8;

					if (fullBlock) {
						long outBase = (long) baseIdx * 8;
						if (remainingBits > 0) {
							long hiBase = blockByteOff + (long) lanes * nextWord * 8;
							long loMask = (1L << currentBits) - 1L;
							long hiMask = (1L << remainingBits) - 1L;
							long laneOff = 0L;
							for (int lane = 0; lane < lanes; lane++, laneOff += 8L) {
								long lo = (buf.get(PTypeIO.LE_LONG, wordBase + laneOff) >>> shift) & loMask;
								long hi = buf.get(PTypeIO.LE_LONG, hiBase + laneOff) & hiMask;
								out.set(PTypeIO.LE_LONG, outBase + laneOff, lo | (hi << currentBits));
							}
						} else {
							long laneOff = 0L;
							for (int lane = 0; lane < lanes; lane++, laneOff += 8L) {
								out.set(PTypeIO.LE_LONG, outBase + laneOff,
										(buf.get(PTypeIO.LE_LONG, wordBase + laneOff) >>> shift) & bitMask);
							}
						}
					} else {
						long hiBase = (remainingBits > 0) ? blockByteOff + (long) lanes * nextWord * 8 : 0L;
						long loMask = (remainingBits > 0) ? (1L << currentBits) - 1L : 0L;
						long hiMask = (remainingBits > 0) ? (1L << remainingBits) - 1L : 0L;
						for (int lane = 0; lane < lanes; lane++) {
							int logicalIdx = baseIdx + lane;
							if (logicalIdx < 0 || logicalIdx >= rowCount) {
								continue;
							}
							long src = buf.get(PTypeIO.LE_LONG, wordBase + (long) lane * 8);
							long value;
							if (remainingBits > 0) {
								long lo = (src >>> shift) & loMask;
								long hi = buf.get(PTypeIO.LE_LONG, hiBase + (long) lane * 8) & hiMask;
								value = lo | (hi << currentBits);
							} else {
								value = (src >>> shift) & bitMask;
							}
							out.set(PTypeIO.LE_LONG, (long) logicalIdx * 8, value);
						}
					}
				}
			}
		}

		private static void applyPatches(DecodeContext ctx, EncodingProtos.PatchesMetadata pm,
		                                 MemorySegment out, int elemBytes) {
			long numPatches = pm.getLen();
			if (numPatches == 0) {
				return;
			}
			long offset = pm.getOffset();
			PType idxPtype = ptypeFromProto(pm.getIndicesPtype());

			Array idxArr = decodeChildAs(ctx, 0, new DType.Primitive(idxPtype, false), numPatches);
			Array valArr = decodeChildAs(ctx, 1, ctx.dtype(), numPatches);

			MemorySegment idxSeg = idxArr.buffer(0);
			MemorySegment valSeg = valArr.buffer(0);

			long n = ctx.rowCount();
			for (long i = 0; i < numPatches; i++) {
				long absIdx = readUnsignedIdx(idxSeg, i, idxPtype) - offset;
				if (absIdx < 0 || absIdx >= n) {
					throw new VortexException(EncodingId.FASTLANES_BITPACKED,
							"patch index " + absIdx + " out of range [0," + n + ")");
				}
				MemorySegment.copy(valSeg, i * elemBytes, out, absIdx * elemBytes, elemBytes);
			}
		}

		private static Array decodeChildAs(DecodeContext parent, int childIdx, DType dtype, long rowCount) {
			ArrayNode childNode = parent.node().children()[childIdx];
			DecodeContext childCtx = new DecodeContext(
					childNode, dtype, rowCount, parent.segmentBuffers(), parent.registry(), parent.arena());
			return parent.registry().decode(childCtx);
		}

		private static long readUnsignedIdx(MemorySegment seg, long i, PType ptype) {
			return switch (ptype) {
				case U8  -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, i));
				case U16 -> Short.toUnsignedLong(seg.get(PTypeIO.LE_SHORT, i * 2));
				case U32 -> Integer.toUnsignedLong(seg.get(PTypeIO.LE_INT, i * 4));
				case U64 -> seg.get(PTypeIO.LE_LONG, i * 8);
				default  -> throw new VortexException(EncodingId.FASTLANES_BITPACKED,
						"non-unsigned patch index ptype " + ptype);
			};
		}

		private static PType ptypeFromProto(DTypeProtos.PType proto) {
			return PType.values()[proto.getNumber()];
		}
	}
}
