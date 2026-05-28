package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/// Codec for `vortex.bool` — bit-packed boolean arrays (LSB first).
public final class BoolCodec implements Codec {

	private static ByteBuffer encodeBool(boolean[] data) {
		var packed = ByteBuffer.allocate((data.length + 7) / 8);
		for (int i = 0; i < data.length; i++) {
			if (data[i]) {
				packed.put(i / 8, (byte) (packed.get(i / 8) | (byte) (1 << (i % 8))));
			}
		}
		return packed.rewind();
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
	public EncodeResult encode(DType dtype, Object data) {
		return EncodeResult.simple(encodingId(), encodeBool((boolean[]) data));
	}

	@Override
	public Array decode(DecodeContext ctx) {
		MemorySegment buf = ctx.buffer(0);
		return new Array(ctx.dtype(), ctx.rowCount(),
				new MemorySegment[]{buf}, new Array[0], ArrayStats.empty());
	}
}
