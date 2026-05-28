package io.github.dfa1.vortex.core;

import dev.vortex.proto.ScalarProtos;

import java.nio.ByteBuffer;

/// Per-array statistics embedded in the encoding tree.
public record ArrayStats(
		Object min,
		Object max,
		Long trueCount,
		Long nullCount,
		Boolean isSorted,
		Boolean isStrictSorted
) {
	private static final ArrayStats EMPTY = new ArrayStats(null, null, null, null, null, null);

	public static ArrayStats empty() {
		return EMPTY;
	}

	public static ArrayStats fromFbs(io.github.dfa1.vortex.fbs.ArrayStats fbs) {
		if (fbs == null) {
			return EMPTY;
		}
		Object min = decodeScalar(fbs.minAsByteBuffer());
		Object max = decodeScalar(fbs.maxAsByteBuffer());
		if (min == null && max == null) {
			return EMPTY;
		}
		return new ArrayStats(min, max, null, null, null, null);
	}

	private static Object decodeScalar(ByteBuffer bytes) {
		if (bytes == null || !bytes.hasRemaining()) {
			return null;
		}
		try {
			ScalarProtos.ScalarValue sv = ScalarProtos.ScalarValue.parseFrom(bytes.duplicate());
			return switch (sv.getKindCase()) {
				case INT64_VALUE -> sv.getInt64Value();
				case UINT64_VALUE -> sv.getUint64Value();
				case F32_VALUE -> sv.getF32Value();
				case F64_VALUE -> sv.getF64Value();
				case BOOL_VALUE -> sv.getBoolValue();
				default -> null;
			};
		} catch (com.google.protobuf.InvalidProtocolBufferException e) {
			return null;
		}
	}
}
