package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.GenericArray;
import io.github.dfa1.vortex.proto.EncodingProtos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

class DecimalBytePartsEncodingTest {

    @Nested
    class Encode {

        @Test
        void roundTrip_longArray_preservesMspValues() {
            // Given
            long[] values = {1L, -2L, 3L};
            DType dtype = new DType.Decimal((byte) 18, (byte) 0, false);
            var sut = new DecimalBytePartsEncoding();
            Registry registry = TestRegistry.withPrimitive(sut);

            // When
            EncodeResult encoded = sut.encode(dtype, values, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, values.length, dtype, registry);
            GenericArray result = (GenericArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(values.length);
            Array msp = result.child(0);
            assertThat(msp.length()).isEqualTo(values.length);
            var le = PTypeIO.LE_LONG;
            for (int i = 0; i < values.length; i++) {
                assertThat(ArraySegments.of(msp).get(le, (long) i * 8)).isEqualTo(values[i]);
            }
        }

        @Test
        void encodeNode_hasNoBuffers_andOneMspChild() {
            // Given
            long[] values = {10L, 20L};
            DType dtype = new DType.Decimal((byte) 18, (byte) 0, false);
            var sut = new DecimalBytePartsEncoding();

            // When
            EncodeResult result = sut.encode(dtype, values, EncodeTestHelper.testCtx());

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
            EncodeResult result = sut.encode(dtype, values, EncodeTestHelper.testCtx());

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
