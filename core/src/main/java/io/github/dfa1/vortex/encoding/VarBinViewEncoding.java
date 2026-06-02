package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.VarBinArray;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;

/// Decoder for {@code vortex.varbinview} — Apache Arrow StringView/BinaryView format.
///
/// <p>Each element is a 16-byte view (LE):
/// <ul>
///   <li>Inlined (size ≤ 12): {@code [size:u32 | data:12bytes]}</li>
///   <li>Reference (size > 12): {@code [size:u32 | prefix:4bytes | buffer_index:u32 | offset:u32]}</li>
/// </ul>
///
/// <p>Buffer layout: {@code [data_buf_0, ..., data_buf_k, views_buf]} — views is always last.
/// Metadata: empty. Children: 0 (non-nullable) or 1 (validity bitmap, ignored).
///
/// <p>Decode materialises all values into a flat {@link VarBinArray} (bytes + I64 offsets).
public final class VarBinViewEncoding implements Encoding {

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_VARBINVIEW;
	}

	@Override
	public boolean accepts(DType dtype) {
		return dtype instanceof DType.Utf8 || dtype instanceof DType.Binary;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		return Encoder.encode((String[]) data);
	}

	@Override
	public Array decode(DecodeContext ctx) {
		return Decoder.decode(ctx);
	}

	private static final class Encoder {

		private static final int MAX_INLINED_SIZE = 12;
		private static final int VIEW_SIZE = 16;

		static EncodeResult encode(String[] strings) {
			int n = strings.length;

			byte[][] bytes = new byte[n][];
			int totalDataBytes = 0;
			for (int i = 0; i < n; i++) {
				bytes[i] = strings[i].getBytes(StandardCharsets.UTF_8);
				if (bytes[i].length > MAX_INLINED_SIZE) {
					totalDataBytes += bytes[i].length;
				}
			}

			Arena arena = Arena.ofAuto();
			boolean hasDataBuf = totalDataBytes > 0;
			MemorySegment dataBuf = arena.allocate(hasDataBuf ? totalDataBytes : 1);
			MemorySegment viewsBuf = arena.allocate(n > 0 ? (long) n * VIEW_SIZE : 1);

			int dataOffset = 0;
			for (int i = 0; i < n; i++) {
				byte[] b = bytes[i];
				long viewOff = (long) i * VIEW_SIZE;
				viewsBuf.set(PTypeIO.LE_INT, viewOff, b.length);
				if (b.length <= MAX_INLINED_SIZE) {
					MemorySegment.copy(MemorySegment.ofArray(b), 0, viewsBuf, viewOff + 4, b.length);
				} else {
					MemorySegment.copy(MemorySegment.ofArray(b), 0, viewsBuf, viewOff + 4, 4);
					viewsBuf.set(PTypeIO.LE_INT, viewOff + 8, 0);
					viewsBuf.set(PTypeIO.LE_INT, viewOff + 12, dataOffset);
					MemorySegment.copy(MemorySegment.ofArray(b), 0, dataBuf, dataOffset, b.length);
					dataOffset += b.length;
				}
			}

			int[] bufIndices;
			List<MemorySegment> buffers;
			if (hasDataBuf) {
				bufIndices = new int[]{0, 1};
				buffers = List.of(dataBuf, viewsBuf);
			} else {
				bufIndices = new int[]{0};
				buffers = List.of(viewsBuf);
			}

			EncodeNode root = new EncodeNode(EncodingId.VORTEX_VARBINVIEW, null, new EncodeNode[0], bufIndices);
			return new EncodeResult(root, buffers, null, null);
		}
	}

	private static final class Decoder {

		private static final int MAX_INLINED_SIZE = 12;
		private static final int VIEW_SIZE = 16;

		private static Array decode(DecodeContext ctx) {
			if (!(ctx.dtype() instanceof DType.Utf8 || ctx.dtype() instanceof DType.Binary)) {
				throw new VortexException(EncodingId.VORTEX_VARBINVIEW,
						"expected Utf8/Binary dtype, got " + ctx.dtype());
			}

			int numBufs = ctx.node().bufferIndices().length;
			if (numBufs < 1) {
				throw new VortexException(EncodingId.VORTEX_VARBINVIEW,
						"expected at least 1 buffer (views), got 0");
			}

			// Views buffer is the last; data buffers are 0..numBufs-2
			MemorySegment viewsBuf = ctx.buffer(numBufs - 1);
			MemorySegment[] dataBufs = new MemorySegment[numBufs - 1];
			for (int i = 0; i < dataBufs.length; i++) {
				dataBufs[i] = ctx.buffer(i);
			}

			long n = ctx.rowCount();

			long totalBytes = 0;
			for (long i = 0; i < n; i++) {
				long size = Integer.toUnsignedLong(viewsBuf.get(PTypeIO.LE_INT, i * VIEW_SIZE));
				totalBytes += size;
			}

			MemorySegment outBytes = ctx.arena().allocate(totalBytes > 0 ? totalBytes : 1);
			MemorySegment outOffsets = ctx.arena().allocate((n + 1) * Long.BYTES, Long.BYTES);

			long bytePos = 0;
			outOffsets.setAtIndex(PTypeIO.LE_LONG, 0, 0L);
			for (long i = 0; i < n; i++) {
				long viewOff = i * VIEW_SIZE;
				long size = Integer.toUnsignedLong(viewsBuf.get(PTypeIO.LE_INT, viewOff));
				if (size <= MAX_INLINED_SIZE) {
					MemorySegment.copy(viewsBuf, viewOff + 4, outBytes, bytePos, size);
				} else {
					int bufferIndex = viewsBuf.get(PTypeIO.LE_INT, viewOff + 8);
					long srcOffset = Integer.toUnsignedLong(viewsBuf.get(PTypeIO.LE_INT, viewOff + 12));
					MemorySegment.copy(dataBufs[bufferIndex], srcOffset, outBytes, bytePos, size);
				}
				bytePos += size;
				outOffsets.setAtIndex(PTypeIO.LE_LONG, i + 1, bytePos);
			}

			Array offsetsArr = new LongArray(new DType.Primitive(PType.I64, false), n + 1,
					outOffsets, ArrayStats.empty());
			return new VarBinArray(ctx.dtype(), n, outBytes.asReadOnly(), offsetsArr, PType.I64,
					ArrayStats.empty());
		}
	}
}
