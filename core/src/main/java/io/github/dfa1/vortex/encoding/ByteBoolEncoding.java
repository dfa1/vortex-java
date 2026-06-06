package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.array.BoolArray;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Encoding for {@code vortex.bytebool} — one byte per boolean element (0 = false, non-zero = true).
///
/// <p>Buffer 0: raw byte values, length = rowCount.
/// Metadata: empty.
/// Child slot 0: validity array (optional, only when nullable with explicit validity bitmap).
///
/// <p>Decode: pack the byte buffer into a bit-packed {@link BoolArray} (LSB-first),
/// matching the layout of {@code vortex.bool}.
public final class ByteBoolEncoding implements Encoding {

	/// Creates a new {@code ByteBoolEncoding} instance.
	public ByteBoolEncoding() {
	}

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_BYTEBOOL;
	}

	@Override
	public boolean accepts(DType dtype) {
		return dtype instanceof DType.Bool;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
		boolean[] bools = (boolean[]) data;
		MemorySegment seg = ctx.arena().allocate(bools.length);
		for (int i = 0; i < bools.length; i++) {
			seg.set(ValueLayout.JAVA_BYTE, i, bools[i] ? (byte) 1 : (byte) 0);
		}
		return EncodeResult.simple(encodingId(), seg);
	}

	@Override
	public Array decode(DecodeContext ctx) {
		long n = ctx.rowCount();
		MemorySegment bytes = ctx.buffer(0);
		long packedBytes = (n + 7) >>> 3;
		MemorySegment packed = ctx.arena().allocate(packedBytes > 0 ? packedBytes : 1);
		for (long i = 0; i < n; i++) {
			if (bytes.get(ValueLayout.JAVA_BYTE, i) != 0) {
				long byteIdx = i >>> 3;
				byte cur = packed.get(ValueLayout.JAVA_BYTE, byteIdx);
				packed.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) (cur | (1 << (i & 7))));
			}
		}
		return new BoolArray(ctx.dtype(), n, packed, ArrayStats.empty());
	}
}
