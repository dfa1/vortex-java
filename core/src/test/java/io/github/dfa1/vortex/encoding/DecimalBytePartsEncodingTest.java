package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.proto.EncodingProtos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class DecimalBytePartsEncodingTest {

	@Nested
	class Encode {

		@Test
		void roundTrip_longArray_preservesMspValues() throws Exception {
			// Given
			long[] values = {1L, -2L, 3L};
			DType dtype = new DType.Decimal((byte) 18, (byte) 0, false);
			var sut = new DecimalBytePartsEncoding();
			EncodingRegistry registry = EncodingRegistry.empty();
			registry.register(sut);
			registry.register(new PrimitiveEncoding());

			// When
			EncodeResult encoded = sut.encode(dtype, values);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, values.length, dtype, registry);
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(values.length);
			Array msp = result.child(0);
			assertThat(msp.length()).isEqualTo(values.length);
			var le = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < values.length; i++) {
				assertThat(msp.buffer(0).get(le, (long) i * 8)).isEqualTo(values[i]);
			}
		}

		@Test
		void encodeNode_hasNoBuffers_andOneMspChild() {
			// Given
			long[] values = {10L, 20L};
			DType dtype = new DType.Decimal((byte) 18, (byte) 0, false);
			var sut = new DecimalBytePartsEncoding();

			// When
			EncodeResult result = sut.encode(dtype, values);

			// Then
			assertThat(result.rootNode().bufferIndices()).isEmpty();
			assertThat(result.rootNode().children()).hasSize(1);
			assertThat(result.buffers()).hasSize(1);
		}

		@Test
		void metadata_zerothChildPtype_isI64_lowerPartCountIsZero() throws Exception {
			// Given
			long[] values = {42L};
			DType dtype = new DType.Decimal((byte) 18, (byte) 0, false);
			var sut = new DecimalBytePartsEncoding();

			// When
			EncodeResult result = sut.encode(dtype, values);

			// Then
			byte[] metaBytes = new byte[result.rootNode().metadata().remaining()];
			result.rootNode().metadata().duplicate().get(metaBytes);
			EncodingProtos.DecimalBytePartsMetadata meta =
					EncodingProtos.DecimalBytePartsMetadata.parseFrom(metaBytes);
			assertThat(meta.getZerothChildPtypeValue()).isEqualTo(7); // I64 ordinal
			assertThat(meta.getLowerPartCount()).isEqualTo(0);
		}
	}
}
