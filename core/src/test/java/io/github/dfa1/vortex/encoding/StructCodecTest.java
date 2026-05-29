package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class StructCodecTest {

	private static final DType I64 = new DType.Primitive(PType.I64, false);
	private static final ValueLayout.OfLong LE_LONG =
			ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private static MemorySegment longSegment(long... values) {
		byte[] bytes = new byte[values.length * 8];
		ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		for (long v : values) {
			bb.putLong(v);
		}
		return MemorySegment.ofArray(bytes);
	}

	private static ArrayNode primitiveNode(int bufferIdx) {
		return new ArrayNode(CodecId.VORTEX_PRIMITIVE, null, new ArrayNode[0],
				new int[]{bufferIdx}, ArrayStats.empty());
	}

	private static DecodeContext buildStructCtx(ArrayNode structNode, MemorySegment[] segs, long rowCount) {
		CodecRegistry registry = CodecRegistry.empty();
		registry.register(new StructCodec());
		registry.register(new PrimitiveCodec());
		return new DecodeContext(structNode, I64, rowCount, segs, registry, Arena.global());
	}

	@Test
	void decode_nonNullableWrapper_oneChild_returnsValues() {
		// Given — struct{values: I64} (non-nullable, 1 child)
		long[] data = {10L, 20L, 30L};
		MemorySegment seg = longSegment(data);
		ArrayNode valuesNode = primitiveNode(0);
		ArrayNode structNode = new ArrayNode(CodecId.VORTEX_STRUCT, null,
				new ArrayNode[]{valuesNode}, new int[0], ArrayStats.empty());

		DecodeContext ctx = buildStructCtx(structNode, new MemorySegment[]{seg}, data.length);
		StructCodec sut = new StructCodec();

		// When
		Array result = sut.decode(ctx);

		// Then
		assertThat(result.length()).isEqualTo(data.length);
		for (int i = 0; i < data.length; i++) {
			assertThat(result.buffer(0).get(LE_LONG, (long) i * 8)).isEqualTo(data[i]);
		}
	}

	@Test
	void decode_nullableWrapper_twoChildren_returnsValues() {
		// Given — struct{validity: Bool, values: I64} (nullable, 2 children)
		// validity buffer: dummy (not decoded, just needs a slot)
		long[] data = {7L, 14L, 21L};
		MemorySegment validitySeg = MemorySegment.ofArray(new byte[]{(byte) 0xFF}); // all valid
		MemorySegment valuesSeg = longSegment(data);

		ArrayNode validityNode = primitiveNode(0); // slot 0 = dummy validity
		ArrayNode valuesNode = primitiveNode(1);   // slot 1 = actual values
		ArrayNode structNode = new ArrayNode(CodecId.VORTEX_STRUCT, null,
				new ArrayNode[]{validityNode, valuesNode}, new int[0], ArrayStats.empty());

		CodecRegistry registry = CodecRegistry.empty();
		registry.register(new StructCodec());
		registry.register(new PrimitiveCodec());
		DecodeContext ctx = new DecodeContext(
				structNode, I64, data.length,
				new MemorySegment[]{validitySeg, valuesSeg},
				registry, Arena.global());

		StructCodec sut = new StructCodec();

		// When
		Array result = sut.decode(ctx);

		// Then
		assertThat(result.length()).isEqualTo(data.length);
		for (int i = 0; i < data.length; i++) {
			assertThat(result.buffer(0).get(LE_LONG, (long) i * 8)).isEqualTo(data[i]);
		}
	}
}
