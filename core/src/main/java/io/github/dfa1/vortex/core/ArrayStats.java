package io.github.dfa1.vortex.core;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.dfa1.vortex.proto.ScalarProtos;

import java.nio.ByteBuffer;

/// Per-array statistics embedded in the encoding tree.
///
/// @param min            minimum value in the array, or {@code null} if unknown
/// @param max            maximum value in the array, or {@code null} if unknown
/// @param trueCount      number of {@code true} values for bool columns, or {@code null} if unknown
/// @param nullCount      number of null values, or {@code null} if unknown
/// @param isSorted       {@code true} if the array is sorted in ascending order, or {@code null} if unknown
/// @param isStrictSorted {@code true} if the array is strictly sorted (no duplicates), or {@code null} if unknown
public record ArrayStats(
		Object min,
		Object max,
		Long trueCount,
		Long nullCount,
		Boolean isSorted,
		Boolean isStrictSorted
) {
	private static final ArrayStats EMPTY = new ArrayStats(null, null, null, null, null, null);

	/// Returns an empty stats instance with all fields set to {@code null}.
	///
	/// @return an empty {@link ArrayStats} with no known statistics
	public static ArrayStats empty() {
		return EMPTY;
	}

	/// Parses stats from a FlatBuffers {@link io.github.dfa1.vortex.fbs.ArrayStats} table.
	/// Returns an empty instance when {@code fbs} is {@code null} or carries no min/max.
	///
	/// @param fbs the FlatBuffers stats table, or {@code null}
	/// @return parsed stats, or an empty instance if no usable data is present
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
				case STRING_VALUE -> sv.getStringValue();
				case BYTES_VALUE -> sv.getBytesValue().toStringUtf8();
				default -> null;
			};
		} catch (InvalidProtocolBufferException e) {
			throw new VortexException("invalid scalar value in array stats", e);
		}
	}
}
