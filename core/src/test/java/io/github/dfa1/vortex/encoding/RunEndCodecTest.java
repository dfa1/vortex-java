package io.github.dfa1.vortex.encoding;

import dev.vortex.proto.DTypeProtos;
import dev.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class RunEndCodecTest {

	private static final DType I64_DTYPE = new DType.Primitive(PType.I64, false);

	private static DecodeContext buildCtx(
			DType dtype, long rowCount,
			long[] ends, long[] values, PType endsPtype, long offset
	) {
		byte[] metaBytes = EncodingProtos.RunEndMetadata.newBuilder()
				.setEndsPtype(DTypeProtos.PType.forNumber(endsPtype.ordinal()))
				.setNumRuns(ends.length)
				.setOffset(offset)
				.build()
				.toByteArray();

		byte[] endsBuf = toLEBytes(ends, endsPtype);
		byte[] valBuf = toLEBytes(values, PType.I64);

		ArrayNode endsNode = new ArrayNode(CodecId.VORTEX_PRIMITIVE, null,
				new ArrayNode[0], new int[]{0}, ArrayStats.empty());
		ArrayNode valsNode = new ArrayNode(CodecId.VORTEX_PRIMITIVE, null,
				new ArrayNode[0], new int[]{1}, ArrayStats.empty());
		ArrayNode reNode = new ArrayNode(CodecId.VORTEX_RUNEND,
				ByteBuffer.wrap(metaBytes),
				new ArrayNode[]{endsNode, valsNode},
				new int[0], ArrayStats.empty());

		MemorySegment[] segments = {
				MemorySegment.ofArray(endsBuf),
				MemorySegment.ofArray(valBuf)
		};

		CodecRegistry registry = CodecRegistry.empty();
		registry.register(new RunEndCodec());
		registry.register(new PrimitiveCodec());

		return new DecodeContext(reNode, dtype, rowCount, segments, registry, java.lang.foreign.Arena.global());
	}

	private static byte[] toLEBytes(long[] values, PType ptype) {
		int elemBytes = ptype.byteSize();
		byte[] buf = new byte[values.length * elemBytes];
		ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
		for (long v : values) {
			switch (ptype) {
				case U8, I8 -> bb.put((byte) v);
				case U16, I16 -> bb.putShort((short) v);
				case U32, I32 -> bb.putInt((int) v);
				case U64, I64 -> bb.putLong(v);
				default -> throw new UnsupportedOperationException(ptype.name());
			}
		}
		return buf;
	}

	@Test
	void decode_singleRun_fillsAllElements() {
		// Given — 1 run: ends=[5], values=[42]; output = [42, 42, 42, 42, 42]
		long[] ends = {5L};
		long[] values = {42L};
		DecodeContext ctx = buildCtx(I64_DTYPE, 5, ends, values, PType.U32, 0L);
		RunEndCodec sut = new RunEndCodec();

		// When
		Array result = sut.decode(ctx);

		// Then
		assertThat(result.length()).isEqualTo(5L);
		var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < 5; i++) {
			assertThat(result.buffer(0).get(layout, (long) i * 8)).isEqualTo(42L);
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	@Test
	void decode_multipleRuns_expandsCorrectly() {
		// Given — runs: [0,2)=10, [2,5)=20, [5,7)=30
		// ends=[2,5,7], values=[10,20,30]
		long[] ends = {2L, 5L, 7L};
		long[] values = {10L, 20L, 30L};
		DecodeContext ctx = buildCtx(I64_DTYPE, 7, ends, values, PType.U32, 0L);
		RunEndCodec sut = new RunEndCodec();

		// When
		Array result = sut.decode(ctx);

		// Then
		long[] expected = {10, 10, 20, 20, 20, 30, 30};
		var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < expected.length; i++) {
			assertThat(result.buffer(0).get(layout, (long) i * 8))
					.as("index %d", i).isEqualTo(expected[i]);
		}
	}

	@Test
	void decode_withOffset_skipsLogicalElements() {
		// Given — logical array: [0,3)=10, [3,6)=20; offset=2, rowCount=3
		// output elements [2..5): [10, 20, 20]
		long[] ends = {3L, 6L};
		long[] values = {10L, 20L};
		DecodeContext ctx = buildCtx(I64_DTYPE, 3, ends, values, PType.U32, 2L);
		RunEndCodec sut = new RunEndCodec();

		// When
		Array result = sut.decode(ctx);

		// Then
		long[] expected = {10L, 20L, 20L};
		var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < expected.length; i++) {
			assertThat(result.buffer(0).get(layout, (long) i * 8))
					.as("index %d", i).isEqualTo(expected[i]);
		}
	}
}
