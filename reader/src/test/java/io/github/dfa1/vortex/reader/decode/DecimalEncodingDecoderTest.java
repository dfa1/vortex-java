package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LazyDecimalArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecimalEncodingDecoderTest {

    private static final DecimalEncodingDecoder SUT = new DecimalEncodingDecoder();
    private static final DType DECIMAL = new DType.Decimal((byte) 10, (byte) 2, false);

    private static Array decode(int valuesType, int rowCount, int bufferBytes) {
        // Encode values_type explicitly (field 1, varint) so even the proto3 default (0) is
        // present on the wire — DecimalMetadata(0).encode() would omit it and read as empty.
        MemorySegment meta = MemorySegment.ofArray(new byte[]{0x08, (byte) valuesType});
        return decode(meta, rowCount, bufferBytes);
    }

    private static Array decode(MemorySegment meta, int rowCount, int bufferBytes) {
        MemorySegment buf = MemorySegment.ofArray(new byte[bufferBytes]);
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_DECIMAL, meta, new ArrayNode[0], new int[]{0});
        DecodeContext ctx = new DecodeContext(node, DECIMAL, rowCount,
                new MemorySegment[]{buf}, ReadRegistry.empty(), Arena.ofAuto());
        return SUT.decode(ctx);
    }

    @Test
    void encodingId() {
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.VORTEX_DECIMAL);
    }

    @Nested
    class Decode {

        @ParameterizedTest(name = "valuesType={0} -> {1} bytes")
        @CsvSource({"0,1", "1,2", "2,4", "3,8", "4,16", "5,32"})
        void everyDecimalTypeWidth(int valuesType, int byteWidth) {
            // Given — buffer sized exactly for the declared width × rows
            int rows = 2;

            // When
            Array result = decode(valuesType, rows, rows * byteWidth);

            // Then
            assertThat(result).isInstanceOf(LazyDecimalArray.class);
            assertThat(result.length()).isEqualTo(rows);
        }

        @Test
        void unknownValuesType_throws() {
            // When / Then
            assertThatThrownBy(() -> decode(6, 1, 64))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unknown DecimalType");
        }

        @Test
        void bufferTooSmall_throws() {
            // Given — values_type 3 = 8 bytes/row × 2 rows = 16 needed, only 8 provided
            // When / Then
            assertThatThrownBy(() -> decode(3, 2, 8))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("buffer too small");
        }

        @Test
        void missingMetadata_throws() {
            // When / Then — null metadata
            assertThatThrownBy(() -> decode((MemorySegment) null, 1, 8))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing metadata");
        }

        @Test
        void emptyMetadata_throws() {
            // When / Then — present but zero remaining
            assertThatThrownBy(() -> decode(MemorySegment.ofArray(new byte[0]), 1, 8))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing metadata");
        }

        @Test
        void invalidMetadata_throws() {
            // Given — a truncated varint that proto decode rejects
            MemorySegment bad = MemorySegment.ofArray(new byte[]{0x08, (byte) 0x80});

            // When / Then
            assertThatThrownBy(() -> decode(bad, 1, 8))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("invalid metadata");
        }
    }
}
