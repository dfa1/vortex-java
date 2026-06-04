package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.VarBinArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VarBinEncodingTest {


	@Nested
	class Encode {

		@Test
		void encodingId_isVortexVarbin() {
			// Given / When / Then
			assertThat(new VarBinEncoding().encodingId()).isEqualTo(EncodingId.VORTEX_VARBIN);
		}

		@Test
		void accepts_utf8Dtype_returnsTrue() {
			// Given / When / Then
			assertThat(new VarBinEncoding().accepts(DTypes.UTF8)).isTrue();
		}

		@Test
		void accepts_binaryDtype_returnsTrue() {
			// Given / When / Then
			assertThat(new VarBinEncoding().accepts(DTypes.BINARY)).isTrue();
		}

		@Test
		void accepts_primitiveDtype_returnsFalse() {
			// Given / When / Then
			assertThat(new VarBinEncoding().accepts(new DType.Primitive(PType.I64, false))).isFalse();
		}

		@Test
		void encode_singleString_roundTrips() {
			// Given
			var sut = new VarBinEncoding();
			String[] data = {"hello"};

			// When
			EncodeResult result = sut.encode(DTypes.UTF8, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(result, data.length, DTypes.UTF8, buildRegistry());
			VarBinArray decoded = (VarBinArray) sut.decode(ctx);

			// Then
			assertThat(decoded.length()).isEqualTo(1);
			assertThat(decoded.getBytes(0)).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
		}

		@Test
		void encode_multipleStrings_roundTrips() {
			// Given
			var sut = new VarBinEncoding();
			String[] data = {"foo", "bar", "baz"};

			// When
			EncodeResult result = sut.encode(DTypes.UTF8, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(result, data.length, DTypes.UTF8, buildRegistry());
			VarBinArray decoded = (VarBinArray) sut.decode(ctx);

			// Then
			assertThat(decoded.length()).isEqualTo(3);
			for (int i = 0; i < data.length; i++) {
				assertThat(decoded.getBytes(i)).isEqualTo(data[i].getBytes(StandardCharsets.UTF_8));
			}
		}

		@Test
		void encode_unicodeString_roundTrips() {
			// Given
			var sut = new VarBinEncoding();
			String[] data = {"héllo", "wörld", "日本語"};

			// When
			EncodeResult result = sut.encode(DTypes.UTF8, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(result, data.length, DTypes.UTF8, buildRegistry());
			VarBinArray decoded = (VarBinArray) sut.decode(ctx);

			// Then
			assertThat(decoded.length()).isEqualTo(3);
			for (int i = 0; i < data.length; i++) {
				assertThat(decoded.getBytes(i)).isEqualTo(data[i].getBytes(StandardCharsets.UTF_8));
			}
		}

		@Test
		void encode_emptyStringInArray_roundTrips() {
			// Given
			var sut = new VarBinEncoding();
			String[] data = {"a", "", "b"};

			// When
			EncodeResult result = sut.encode(DTypes.UTF8, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(result, data.length, DTypes.UTF8, buildRegistry());
			VarBinArray decoded = (VarBinArray) sut.decode(ctx);

			// Then
			assertThat(decoded.length()).isEqualTo(3);
			assertThat(decoded.getBytes(0)).isEqualTo(new byte[]{'a'});
			assertThat(decoded.getBytes(1)).isEmpty();
			assertThat(decoded.getBytes(2)).isEqualTo(new byte[]{'b'});
		}

		@Test
		void encode_emptyArray_producesZeroLengthResult() {
			// Given
			var sut = new VarBinEncoding();
			String[] data = {};

			// When
			EncodeResult result = sut.encode(DTypes.UTF8, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(result, data.length, DTypes.UTF8, buildRegistry());
			VarBinArray decoded = (VarBinArray) sut.decode(ctx);

			// Then
			assertThat(decoded.length()).isZero();
		}

		private static EncodingRegistry buildRegistry() {
			EncodingRegistry registry = TestRegistry.of(new VarBinEncoding(), new PrimitiveEncoding());
			return registry;
		}
	}

	@Nested
	class Decode {

		@Test
		void decode_missingMetadata_throwsVortexException() {
			// Given
			var sut = new VarBinEncoding();
			ArrayNode node = new ArrayNode(EncodingId.VORTEX_VARBIN, null, new ArrayNode[0], new int[0], null);
			DecodeContext ctx = new DecodeContext(node, DTypes.UTF8, 3, new MemorySegment[0],
					EncodingRegistry.empty(), Arena.ofAuto());

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("missing metadata");
		}
	}
}
