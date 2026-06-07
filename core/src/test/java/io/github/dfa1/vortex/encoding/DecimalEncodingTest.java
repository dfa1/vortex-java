package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.proto.EncodingProtos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecimalEncodingTest {

    @Nested
    class Encode {

        @Test
        void roundTrip_i64Precision_preservesBuffer() {
            // Given
            long[] values = {100L, -200L, 300L};
            MemorySegment input = TestSegments.leLongs(values);
            DType dtype = new DType.Decimal((byte) 18, (byte) 2, false);
            var sut = new DecimalEncoding();
            EncodingRegistry registry = TestRegistry.of(sut);

            // When
            EncodeResult encoded = sut.encode(dtype, input, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, values.length, dtype, registry);
            Array result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(values.length);
            for (int i = 0; i < values.length; i++) {
                assertThat(result.segment().get(PTypeIO.LE_LONG, (long) i * 8)).isEqualTo(values[i]);
            }
        }

        @Test
        void accepts_decimalDtype_true_primitiveReturnsFalse() {
            // Given
            var sut = new DecimalEncoding();

            // When / Then
            assertThat(sut.accepts(new DType.Decimal((byte) 18, (byte) 2, false))).isTrue();
            assertThat(sut.accepts(new DType.Primitive(PType.I64, false))).isFalse();
        }

        @ParameterizedTest(name = "precision={0} → valuesType={1}")
        @CsvSource({
                "1,  0",
                "2,  0",
                "3,  1",
                "4,  1",
                "5,  2",
                "9,  2",
                "10, 3",
                "18, 3",
                "19, 4",
                "38, 4",
                "39, 5",
        })
        void valuesType_matchesPrecisionBoundaries(int precision, int expectedValuesType) throws Exception {
            // Given
            int byteWidth = switch (expectedValuesType) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 8;
                case 4 -> 16;
                default -> 32;
            };
            MemorySegment input = Arena.ofAuto().allocate(byteWidth);
            DType dtype = new DType.Decimal((byte) precision, (byte) 0, false);
            var sut = new DecimalEncoding();

            // When
            EncodeResult encoded = sut.encode(dtype, input, EncodeTestHelper.testCtx());

            // Then
            byte[] metaBytes = new byte[encoded.rootNode().metadata().remaining()];
            encoded.rootNode().metadata().duplicate().get(metaBytes);
            EncodingProtos.DecimalMetadata meta = EncodingProtos.DecimalMetadata.parseFrom(metaBytes);
            assertThat(meta.getValuesType()).isEqualTo(expectedValuesType);
        }

        @Test
        void invalidBufferSize_throws() {
            // Given
            MemorySegment input = Arena.ofAuto().allocate(7); // 7 not divisible by 8 (I64)
            DType dtype = new DType.Decimal((byte) 18, (byte) 0, false);
            var sut = new DecimalEncoding();

            // When / Then
            assertThatThrownBy(() -> sut.encode(dtype, input, EncodeTestHelper.testCtx()))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("not multiple of byteWidth");
        }
    }
}
