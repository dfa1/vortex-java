package io.github.dfa1.vortex.encoding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EncodingRegistryTest {

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
	void all() {
		// Given
		EncodingRegistry sut = EncodingRegistry.loadAll();

		// When
		for (EncodingId encodingId : EncodingId.values()) {
			assertThat(sut.hasEncoding(encodingId)).describedAs("%s".formatted(encodingId)).isTrue();
		}
	}
}
