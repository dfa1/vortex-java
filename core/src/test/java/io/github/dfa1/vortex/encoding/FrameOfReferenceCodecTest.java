package io.github.dfa1.vortex.encoding;

import dev.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class FrameOfReferenceCodecTest {

    private static final DType I64_DTYPE = new DType.Primitive(PType.I64, false);
    private static final DType I32_DTYPE = new DType.Primitive(PType.I32, false);

    @Test
    void decode_i64_addsReferenceToResiduals() {
        // Given
        long reference = 1000L;
        long[] residuals = {0, 1, 2, 3, 4};
        long[] expected  = {1000, 1001, 1002, 1003, 1004};

        DecodeContext ctx = buildForContext(I64_DTYPE, reference, residuals, PType.I64);
        FrameOfReferenceCodec sut = new FrameOfReferenceCodec();

        // When
        Array result = sut.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(residuals.length);
        var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < expected.length; i++) {
            assertThat(result.buffer(0).get(layout, (long) i * 8))
                .as("index %d", i)
                .isEqualTo(expected[i]);
        }
    }

    @Test
    void decode_i32_addsReferenceToResiduals() {
        // Given
        long reference = -100L;
        long[] residuals = {0, 5, 10, 15};
        int[]  expected  = {-100, -95, -90, -85};

        DecodeContext ctx = buildForContext(I32_DTYPE, reference, residuals, PType.I32);
        FrameOfReferenceCodec sut = new FrameOfReferenceCodec();

        // When
        Array result = sut.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(residuals.length);
        var layout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < expected.length; i++) {
            assertThat(result.buffer(0).get(layout, (long) i * 4))
                .as("index %d", i)
                .isEqualTo(expected[i]);
        }
    }

    @Test
    void decode_zeroReference_returnsChildUnchanged() {
        // Given — reference == 0, should skip the add entirely
        long[] residuals = {7, 8, 9};
        DecodeContext ctx = buildForContext(I64_DTYPE, 0L, residuals, PType.I64);
        FrameOfReferenceCodec sut = new FrameOfReferenceCodec();

        // When
        Array result = sut.decode(ctx);

        // Then — values unchanged
        var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < residuals.length; i++) {
            assertThat(result.buffer(0).get(layout, (long) i * 8)).isEqualTo(residuals[i]);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, Long.MAX_VALUE, -1L, 1L})
    void decode_wrappingAdd_i64(long reference) {
        // Given — wrapping arithmetic: MAX + 1 wraps to MIN
        long[] residuals = {1L};
        DecodeContext ctx = buildForContext(I64_DTYPE, reference, residuals, PType.I64);
        FrameOfReferenceCodec sut = new FrameOfReferenceCodec();

        // When
        Array result = sut.decode(ctx);

        // Then
        var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        long got = result.buffer(0).get(layout, 0L);
        assertThat(got).isEqualTo(residuals[0] + reference);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static DecodeContext buildForContext(
        DType dtype, long reference, long[] residuals, PType ptype
    ) {
        // Serialize the reference as a ScalarValue proto
        byte[] metaBytes = ScalarProtos.ScalarValue.newBuilder()
            .setInt64Value(reference)
            .build()
            .toByteArray();

        // Build the child primitive buffer
        int elemBytes = ptype.byteSize();
        byte[] childBytes = new byte[residuals.length * elemBytes];
        ByteBuffer bb = ByteBuffer.wrap(childBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (long v : residuals) {
            switch (ptype) {
                case I32, U32 -> bb.putInt((int) v);
                case I64, U64 -> bb.putLong(v);
                default       -> throw new UnsupportedOperationException(ptype.name());
            }
        }

        // ArrayNode for vortex.primitive child: bufferIndices=[0], no children
        ArrayNode childNode = new ArrayNode(
            "vortex.primitive",
            null,
            new ArrayNode[0],
            new int[]{0},
            ArrayStats.empty()
        );

        // ArrayNode for fastlanes.for: metadata=scalarBytes, child=childNode, no buffers
        ArrayNode forNode = new ArrayNode(
            "fastlanes.for",
            ByteBuffer.wrap(metaBytes),
            new ArrayNode[]{childNode},
            new int[0],
            ArrayStats.empty()
        );

        MemorySegment[] segments = {MemorySegment.ofArray(childBytes)};

        DecoderRegistry registry = DecoderRegistry.empty();
        registry.register(new FrameOfReferenceCodec());
        registry.register(new PrimitiveCodec());

        return new DecodeContext(forNode, dtype, residuals.length, segments, registry, java.lang.foreign.Arena.global());
    }
}
