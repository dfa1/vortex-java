package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.UnknownArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncodingRegistryTest {

	private static final DType I32 = new DType.Primitive(PType.I32, false);

	@Test
	void empty() {
		// Given
		EncodingRegistry sut = EncodingRegistry.empty();

		// When
		boolean result1 = sut.hasEncoding(EncodingId.VORTEX_DECIMAL);

		// Then
		assertThat(result1).isFalse();

		// When
		sut.register(new DecimalEncoding());
		boolean result2 = sut.hasEncoding(EncodingId.VORTEX_DECIMAL);

		// Then
		assertThat(result2).isTrue();
	}

	@Test
	void duplicateIdThrows() {
		// Given
		EncodingRegistry sut = EncodingRegistry.empty();
		sut.register(new DecimalEncoding());

		// When / Then
		assertThatThrownBy(() -> sut.register(new DecimalEncoding()))
				.isInstanceOf(VortexException.class)
				.hasMessageContaining("already registered");
	}

	@Test
	void all() {
		// Given
		EncodingRegistry sut = EncodingRegistry.loadAll();

		// When
		for (EncodingId encodingId : EncodingId.values()) {
			assertThat(sut.hasEncoding(encodingId)).describedAs("%s".formatted(encodingId)).isTrue();
		}
	}

	@Test
	void decodeUnknownEncodingThrowsByDefault() {
		// Given
		EncodingRegistry sut = EncodingRegistry.empty();
		ArrayNode node = new UnknownArrayNode("some.unknown",
				ByteBuffer.allocate(0), new ArrayNode[0], new int[0], ArrayStats.empty());
		DecodeContext ctx = new DecodeContext(node, I32, 0L,
				new MemorySegment[0], sut, Arena.ofAuto());

		// When / Then
		assertThatThrownBy(() -> sut.decode(ctx))
				.isInstanceOf(VortexException.class)
				.hasMessageContaining("some.unknown");
	}

	@Test
	void decodeKnownEncodingWithoutDecoderThrowsByDefault() {
		// Given — EncodingId is known but no Encoding registered for it
		EncodingRegistry sut = EncodingRegistry.empty();
		ArrayNode node = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE,
				ByteBuffer.allocate(0), new ArrayNode[0], new int[0], ArrayStats.empty());
		DecodeContext ctx = new DecodeContext(node, I32, 0L,
				new MemorySegment[0], sut, Arena.ofAuto());

		// When / Then
		assertThatThrownBy(() -> sut.decode(ctx))
				.isInstanceOf(VortexException.class)
				.hasMessageContaining("vortex.primitive");
	}

	@Test
	void decodeKnownEncodingWithoutDecoderReturnsUnknownArrayWhenAllowed() {
		// Given — EncodingId is known but no Encoding registered; allowUnknown covers this too
		EncodingRegistry sut = EncodingRegistry.empty().allowUnknown();
		ArrayNode node = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE,
				ByteBuffer.allocate(0), new ArrayNode[0], new int[0], ArrayStats.empty());
		DecodeContext ctx = new DecodeContext(node, I32, 0L,
				new MemorySegment[0], sut, Arena.ofAuto());

		// When
		Array result = sut.decode(ctx);

		// Then
		assertThat(result).isInstanceOf(UnknownArray.class);
		assertThat(((UnknownArray) result).encodingId()).isEqualTo("vortex.primitive");
	}

	@Test
	void decodeUnknownEncodingReturnsUnknownArrayWhenAllowed() {
		// Given
		EncodingRegistry sut = EncodingRegistry.empty().allowUnknown();
		ByteBuffer metadata = ByteBuffer.wrap(new byte[]{1, 2, 3});
		MemorySegment buf = Arena.ofAuto().allocate(4);
		buf.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, 42);
		ArrayNode node = new UnknownArrayNode("some.unknown",
				metadata, new ArrayNode[0], new int[]{0}, ArrayStats.empty());
		DecodeContext ctx = new DecodeContext(node, I32, 5L,
				new MemorySegment[]{buf}, sut, Arena.ofAuto());

		// When
		Array result = sut.decode(ctx);

		// Then
		assertThat(result).isInstanceOf(UnknownArray.class);
		UnknownArray unknown = (UnknownArray) result;
		assertThat(unknown.encodingId()).isEqualTo("some.unknown");
		assertThat(unknown.dtype()).isEqualTo(I32);
		assertThat(unknown.length()).isEqualTo(5L);
		assertThat(unknown.metadata()).isEqualTo(metadata);
		assertThat(unknown.buffers()).hasSize(1);
		assertThat(unknown.buffers()[0].get(java.lang.foreign.ValueLayout.JAVA_INT, 0)).isEqualTo(42);
		assertThat(unknown.children()).isEmpty();
	}

	@Test
	void decodeUnknownEncodingWrapsChildrenAsUnknown() {
		// Given
		EncodingRegistry sut = EncodingRegistry.empty().allowUnknown();
		// Child uses a known id (vortex.primitive); allow-unknown still wraps it unknown because
		// its parent is unknown — mirrors Rust decode_foreign in vortex-array/src/serde.rs:380.
		ArrayNode child = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE,
				ByteBuffer.allocate(0), new ArrayNode[0], new int[0], ArrayStats.empty());
		ArrayNode parent = new UnknownArrayNode("some.unknown",
				ByteBuffer.allocate(0), new ArrayNode[]{child}, new int[0], ArrayStats.empty());
		DecodeContext ctx = new DecodeContext(parent, I32, 0L,
				new MemorySegment[0], sut, Arena.ofAuto());

		// When
		Array result = sut.decode(ctx);

		// Then
		UnknownArray unknown = (UnknownArray) result;
		assertThat(unknown.children()).hasSize(1);
		assertThat(unknown.children()[0]).isInstanceOf(UnknownArray.class);
		assertThat(((UnknownArray) unknown.children()[0]).encodingId()).isEqualTo("vortex.primitive");
	}
}
