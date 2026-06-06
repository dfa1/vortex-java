package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.array.BoolArray;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Encoding for `vortex.bool` — bit-packed boolean arrays (LSB first).
public final class BoolEncoding implements Encoding {

	/// Creates a new {@code BoolEncoding} instance.
	public BoolEncoding() {
	}

	private static MemorySegment encodeBool(boolean[] data, Arena arena) {
		long packedBytes = (data.length + 7L) / 8;
		if (packedBytes == 0) {
			return MemorySegment.NULL;
		}
		MemorySegment seg = arena.allocate(packedBytes);
		for (int i = 0; i < data.length; i++) {
			if (data[i]) {
				long byteIdx = i / 8;
				byte cur = seg.get(ValueLayout.JAVA_BYTE, byteIdx);
				seg.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) (cur | (1 << (i % 8))));
			}
		}
		return seg;
	}

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_BOOL;
	}

	@Override
	public boolean accepts(DType dtype) {
		return dtype instanceof DType.Bool;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
		boolean[] bools = (boolean[]) data;
		boolean hasTrue = false;
		boolean hasFalse = false;
		for (boolean b : bools) {
			if (b) {
				hasTrue = true;
			} else {
				hasFalse = true;
			}
			if (hasTrue && hasFalse) {
				break;
			}
		}
		byte[] statsMin = bools.length > 0
				? ScalarProtos.ScalarValue.newBuilder().setBoolValue(!hasFalse).build().toByteArray()
				: null;
		byte[] statsMax = bools.length > 0
				? ScalarProtos.ScalarValue.newBuilder().setBoolValue(hasTrue).build().toByteArray()
				: null;
		return EncodeResult.simple(encodingId(), encodeBool(bools, ctx.arena()), statsMin, statsMax);
	}

	@Override
	public Array decode(DecodeContext ctx) {
		return new BoolArray(ctx.dtype(), ctx.rowCount(), ctx.buffer(0), ArrayStats.empty());
	}
}
