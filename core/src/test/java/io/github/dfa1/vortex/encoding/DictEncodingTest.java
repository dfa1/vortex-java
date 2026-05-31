package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.VarBinArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Property: encode then decode is lossless; low-cardinality data compresses smaller than raw.
class DictEncodingTest {

	private static final DType I32_DTYPE = new DType.Primitive(PType.I32, false);
	private static final DType I64_DTYPE = new DType.Primitive(PType.I64, false);
	private static final DType UTF8_DTYPE = new DType.Utf8(false);

	@Nested
	class Encode {

		@ParameterizedTest
		@MethodSource("i32Arrays")
		void encodeDecode_i32_isLossless(int[] data) {
			// Given
			var sut = new DictEncoding();
			EncodingRegistry registry = EncodingRegistry.empty();
			registry.register(sut);
			registry.register(new PrimitiveEncoding());

			// When
			EncodeResult encoded = sut.encode(I32_DTYPE, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, I32_DTYPE, registry);
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(data.length);
			var le = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < data.length; i++) {
				assertThat(result.buffer(0).get(le, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
			}
		}

		@ParameterizedTest
		@MethodSource("i64Arrays")
		void encodeDecode_i64_isLossless(long[] data) {
			// Given
			var sut = new DictEncoding();
			EncodingRegistry registry = EncodingRegistry.empty();
			registry.register(sut);
			registry.register(new PrimitiveEncoding());

			// When
			EncodeResult encoded = sut.encode(I64_DTYPE, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, I64_DTYPE, registry);
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(data.length);
			var le = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < data.length; i++) {
				assertThat(result.buffer(0).get(le, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
			}
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("repetitiveI32Arrays")
		void encodedSize_lowCardinality_compressesWellVsRaw(String name, int[] data) {
			// Given
			var sut = new DictEncoding();

			// When
			EncodeResult encoded = sut.encode(I32_DTYPE, data);

			// Then — dict-encoded size < raw size (n * 4 bytes)
			long encodedBytes = encoded.buffers().stream().mapToLong(MemorySegment::byteSize).sum();
			long rawBytes = (long) data.length * 4;
			assertThat(encodedBytes).isLessThan(rawBytes);
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("utf8Arrays")
		void encodeDecode_utf8_isLossless(String name, String[] data) {
			// Given
			var sut = new DictEncoding();
			EncodingRegistry registry = EncodingRegistry.empty();
			registry.register(sut);
			registry.register(new PrimitiveEncoding());
			registry.register(new VarBinEncoding());

			// When
			EncodeResult encoded = sut.encode(UTF8_DTYPE, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, UTF8_DTYPE, registry);
			Array result = sut.decode(ctx);

			// Then
			assertThat(result).isInstanceOf(VarBinArray.class);
			VarBinArray arr = (VarBinArray) result;
			assertThat(arr.length()).isEqualTo(data.length);
			for (int i = 0; i < data.length; i++) {
				String actual = arr.getString(i);
				assertThat(actual).as("index %d", i).isEqualTo(data[i]);
			}
		}

		@Test
		void encodedSize_lowCardinalityUtf8_compressesWellVsRaw() {
			// Given
			var sut = new DictEncoding();
			String[] symbols = {"AAPL", "GOOG", "MSFT"};
			String[] data = repeat(symbols, 1000);  // 3 000 rows, 3 unique ~4-byte symbols

			// When
			EncodeResult encoded = sut.encode(UTF8_DTYPE, data);

			// Then — dict overhead << repeating every string verbatim
			long encodedBytes = encoded.buffers().stream().mapToLong(MemorySegment::byteSize).sum();
			long rawBytes = 3000L * 5;  // avg 5 bytes/symbol (4 chars + 1 for overhead)
			assertThat(encodedBytes).isLessThan(rawBytes);
		}

		static Stream<int[]> i32Arrays() {
			return Stream.of(
					new int[]{0},
					new int[]{1, 2, 3},
					new int[]{0, 1, 2, 0, 1, 2, 0, 1, 2},
					new int[]{42, 42, 42, 42, 42},
					new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, 0, Integer.MIN_VALUE, Integer.MAX_VALUE}
			);
		}

		static Stream<long[]> i64Arrays() {
			return Stream.of(
					new long[]{0L},
					new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 0L, Long.MIN_VALUE},
					new long[]{1L, 2L, 1L, 2L, 1L, 2L, 1L, 2L}
			);
		}

		static Stream<Arguments> repetitiveI32Arrays() {
			return Stream.of(
					// 2 unique values repeated many times → codes + dict much smaller than raw
					Arguments.of("binary-100",
							repeat(new int[]{0, 1}, 50)),
					Arguments.of("single-value-50",
							repeat(new int[]{42}, 50)),
					Arguments.of("three-values-60",
							repeat(new int[]{10, 20, 30}, 20))
			);
		}

		static Stream<Arguments> utf8Arrays() {
			return Stream.of(
					Arguments.of("single", new String[]{"hello"}),
					Arguments.of("all-unique", new String[]{"a", "b", "c"}),
					Arguments.of("repeated", new String[]{"AAPL", "GOOG", "AAPL", "MSFT", "GOOG", "AAPL"}),
					Arguments.of("unicode", new String[]{"café", "naïve", "café", "résumé", "naïve"}),
					Arguments.of("empty-string", new String[]{"", "x", "", "y", ""})
			);
		}

		private static int[] repeat(int[] pattern, int times) {
			int[] result = new int[pattern.length * times];
			for (int i = 0; i < times; i++) {
				System.arraycopy(pattern, 0, result, i * pattern.length, pattern.length);
			}
			return result;
		}

		private static String[] repeat(String[] pattern, int times) {
			String[] result = new String[pattern.length * times];
			for (int i = 0; i < times; i++) {
				System.arraycopy(pattern, 0, result, i * pattern.length, pattern.length);
			}
			return result;
		}
	}
}
