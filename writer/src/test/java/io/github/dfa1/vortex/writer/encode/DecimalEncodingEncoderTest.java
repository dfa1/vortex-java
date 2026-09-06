package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.core.proto.ProtoDecimalMetadata;
import io.github.dfa1.vortex.reader.decode.DecimalEncodingDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecimalEncodingEncoderTest {

    private static final DecimalEncodingEncoder ENCODER = new DecimalEncodingEncoder();
    private static final DecimalEncodingDecoder DECODER = new DecimalEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER);

    @Test
    void roundTrip_i64Precision_preservesBuffer() {
        // Given
        long[] values = {100L, -200L, 300L};
        MemorySegment input = TestSegments.leLongs(values);
        DType dtype = new DType.Decimal((byte) 18, (byte) 2, false);
        EncodeResult encoded = ENCODER.encode(dtype, input, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, values.length, dtype, REGISTRY);

        // When
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(values.length);
        for (int i = 0; i < values.length; i++) {
            assertThat(result.materialize(Arena.ofAuto()).get(VortexFormat.LE_LONG, (long) i * 8)).isEqualTo(values[i]);
        }
    }

    @Test
    void accepts_decimalDtype_true_primitiveReturnsFalse() {
        // Given
        // When / Then
        assertThat(ENCODER.accepts(new DType.Decimal((byte) 18, (byte) 2, false))).isTrue();
        assertThat(ENCODER.accepts(DType.I64)).isFalse();
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

        // When
        EncodeResult encoded = ENCODER.encode(dtype, input, EncodeTestHelper.testCtx());

        // Then
        java.lang.foreign.MemorySegment metaSeg = encoded.rootNode().metadata();
        ProtoDecimalMetadata meta = ProtoDecimalMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        assertThat(meta.values_type()).isEqualTo(expectedValuesType);
    }

    @Test
    void invalidBufferSize_throws() {
        // Given
        MemorySegment input = Arena.ofAuto().allocate(7);
        DType dtype = new DType.Decimal((byte) 18, (byte) 0, false);
        EncodeContext ctx = EncodeTestHelper.testCtx();

        // When / Then
        assertThatThrownBy(() -> ENCODER.encode(dtype, input, ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("not multiple of byteWidth");
    }
}
