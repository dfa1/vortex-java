package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class RleEncodingTest {

	private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfInt  LE_INT  = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private static EncodingRegistry registry() {
		EncodingRegistry r = EncodingRegistry.empty();
		r.register(new RleEncoding());
		r.register(new PrimitiveEncoding());
		return r;
	}

	@Nested
	class Encode {

		@Test
		void roundTrip_empty_i32() {
			// Given
			var sut = new RleEncoding();
			DType dtype = new DType.Primitive(PType.I32, false);

			// When
			EncodeResult encoded = sut.encode(dtype, new int[0]);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, 0, dtype, registry());
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isZero();
		}

		@Test
		void roundTrip_singleElement_i32() {
			// Given
			var sut = new RleEncoding();
			DType dtype = new DType.Primitive(PType.I32, false);
			int[] data = {42};

			// When
			EncodeResult encoded = sut.encode(dtype, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, dtype, registry());
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(1);
			assertThat(result.buffer(0).get(LE_INT, 0)).isEqualTo(42);
		}

		@Test
		void roundTrip_constantArray_i32() {
			// Given
			var sut = new RleEncoding();
			DType dtype = new DType.Primitive(PType.I32, false);
			int n = 2048;
			int[] data = new int[n];
			for (int i = 0; i < n; i++) {
				data[i] = 99;
			}

			// When
			EncodeResult encoded = sut.encode(dtype, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, n, dtype, registry());
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(n);
			for (int i = 0; i < n; i++) {
				assertThat(result.buffer(0).get(LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(99);
			}
		}

		@Test
		void roundTrip_classicRunLengthData_i32() {
			// Given
			var sut = new RleEncoding();
			DType dtype = new DType.Primitive(PType.I32, false);
			int[] data = {1, 1, 1, 2, 2, 3};

			// When
			EncodeResult encoded = sut.encode(dtype, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, dtype, registry());
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(data.length);
			int[] expected = {1, 1, 1, 2, 2, 3};
			for (int i = 0; i < expected.length; i++) {
				assertThat(result.buffer(0).get(LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(expected[i]);
			}
		}

		@Test
		void roundTrip_multipleChunks_i32() {
			// Given: spans 3 chunks
			var sut = new RleEncoding();
			DType dtype = new DType.Primitive(PType.I32, false);
			int n = 3000;
			int[] data = new int[n];
			for (int i = 0; i < n; i++) {
				data[i] = i / 100;
			}

			// When
			EncodeResult encoded = sut.encode(dtype, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, n, dtype, registry());
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(n);
			for (int i = 0; i < n; i++) {
				assertThat(result.buffer(0).get(LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(i / 100);
			}
		}

		@Test
		void roundTrip_i64() {
			// Given
			var sut = new RleEncoding();
			DType dtype = new DType.Primitive(PType.I64, false);
			long[] data = {100L, 100L, 200L, 300L, 300L, 300L};

			// When
			EncodeResult encoded = sut.encode(dtype, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, dtype, registry());
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(data.length);
			for (int i = 0; i < data.length; i++) {
				assertThat(result.buffer(0).get(LE_LONG, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
			}
		}

		@ParameterizedTest
		@ValueSource(ints = {1, 512, 1023, 1024, 1025, 2048, 2049})
		void roundTrip_variousLengths_i32(int n) {
			// Given
			var sut = new RleEncoding();
			DType dtype = new DType.Primitive(PType.I32, false);
			int[] data = new int[n];
			for (int i = 0; i < n; i++) {
				data[i] = i / 50;
			}

			// When
			EncodeResult encoded = sut.encode(dtype, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, n, dtype, registry());
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(n);
			for (int i = 0; i < n; i++) {
				assertThat(result.buffer(0).get(LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(i / 50);
			}
		}

		@Test
		void roundTrip_allDifferent_u16() {
			// Given: worst case — every consecutive value is unique (no compression)
			var sut = new RleEncoding();
			DType dtype = new DType.Primitive(PType.U16, false);
			short[] data = new short[256];
			for (int i = 0; i < 256; i++) {
				data[i] = (short) i;
			}

			// When
			EncodeResult encoded = sut.encode(dtype, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, dtype, registry());
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(data.length);
			var le = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < data.length; i++) {
				assertThat(Short.toUnsignedInt(result.buffer(0).get(le, (long) i * 2)))
						.as("index %d", i).isEqualTo(i);
			}
		}

		@Test
		void roundTrip_negativeValues_i32() {
			// Given
			var sut = new RleEncoding();
			DType dtype = new DType.Primitive(PType.I32, false);
			int[] data = {-3, -3, -1, -1, 0, 0, 5};

			// When
			EncodeResult encoded = sut.encode(dtype, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, dtype, registry());
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(data.length);
			for (int i = 0; i < data.length; i++) {
				assertThat(result.buffer(0).get(LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
			}
		}
	}

	@Nested
	class Decode {

		@Test
		void decode_exactlyOneChunk_correctLength() {
			// Given
			var sut = new RleEncoding();
			DType dtype = new DType.Primitive(PType.I32, false);
			int[] data = new int[1024];
			for (int i = 0; i < 1024; i++) {
				data[i] = i / 10;
			}

			// When
			EncodeResult encoded = sut.encode(dtype, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, 1024, dtype, registry());
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(1024);
		}

		@Test
		void decode_crossesChunkBoundary_correctValues() {
			// Given: values span the chunk boundary at element 1024
			var sut = new RleEncoding();
			DType dtype = new DType.Primitive(PType.I32, false);
			int n = 2048;
			int[] data = new int[n];
			for (int i = 0; i < n; i++) {
				data[i] = i / 100;
			}

			// When
			EncodeResult encoded = sut.encode(dtype, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, n, dtype, registry());
			Array result = sut.decode(ctx);

			// Then — verify values near the chunk boundary
			for (int i = 1000; i < 1048; i++) {
				assertThat(result.buffer(0).get(LE_INT, (long) i * 4))
						.as("index %d", i).isEqualTo(i / 100);
			}
		}

		@Test
		void decode_partialLastChunk_correctLength() {
			// Given: 1500 elements — two chunks (1024 full + 476 partial)
			var sut = new RleEncoding();
			DType dtype = new DType.Primitive(PType.I32, false);
			int n = 1500;
			int[] data = new int[n];
			for (int i = 0; i < n; i++) {
				data[i] = i / 100;
			}

			// When
			EncodeResult encoded = sut.encode(dtype, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, n, dtype, registry());
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(n);
			for (int i = 0; i < n; i++) {
				assertThat(result.buffer(0).get(LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(i / 100);
			}
		}
	}
}
