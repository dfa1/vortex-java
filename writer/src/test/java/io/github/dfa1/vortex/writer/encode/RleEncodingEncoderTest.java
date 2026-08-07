package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.proto.ProtoRLEMetadata;
import io.github.dfa1.vortex.reader.decode.BoolEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.RleEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RleEncodingEncoderTest {

    private static final RleEncodingEncoder ENCODER = new RleEncodingEncoder();
    private static final RleEncodingDecoder DECODER = new RleEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    private static ArrayNode toArrayNode(EncodeNode enc) {
        ArrayNode[] children = new ArrayNode[enc.children().length];
        for (int i = 0; i < children.length; i++) {
            children[i] = toArrayNode(enc.children()[i]);
        }
        return new ArrayNode(enc.encodingId(), enc.metadata(), children, enc.bufferIndices());
    }

    @Nested
    class Encode {

        @Test
        void roundTrip_empty_i32() {
            // Given
            DType dtype = DType.I32;
            EncodeResult encoded = ENCODER.encode(dtype, new int[0], EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, 0, dtype, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isZero();
        }

        @Test
        void roundTrip_singleElement_i32() {
            // Given
            DType dtype = DType.I32;
            int[] data = {42};
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, dtype, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(1);
            assertThat(((io.github.dfa1.vortex.reader.array.IntArray) result).getInt(0)).isEqualTo(42);
        }

        @Test
        void roundTrip_constantArray_i32() {
            // Given
            DType dtype = DType.I32;
            int n = 2048;
            int[] data = new int[n];
            for (int i = 0; i < n; i++) {
                data[i] = 99;
            }
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, n, dtype, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(n);
            for (int i = 0; i < n; i++) {
                assertThat(((io.github.dfa1.vortex.reader.array.IntArray) result).getInt(i)).as("index %d", i).isEqualTo(99);
            }
        }

        @Test
        void roundTrip_classicRunLengthData_i32() {
            // Given
            DType dtype = DType.I32;
            int[] data = {1, 1, 1, 2, 2, 3};
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, dtype, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            for (int i = 0; i < data.length; i++) {
                assertThat(((io.github.dfa1.vortex.reader.array.IntArray) result).getInt(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void roundTrip_multipleChunks_i32() {
            // Given
            DType dtype = DType.I32;
            int n = 3000;
            int[] data = new int[n];
            for (int i = 0; i < n; i++) {
                data[i] = i / 100;
            }
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, n, dtype, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(n);
            for (int i = 0; i < n; i++) {
                assertThat(((io.github.dfa1.vortex.reader.array.IntArray) result).getInt(i)).as("index %d", i).isEqualTo(i / 100);
            }
        }

        @Test
        void roundTrip_i64() {
            // Given
            DType dtype = DType.I64;
            long[] data = {100L, 100L, 200L, 300L, 300L, 300L};
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, dtype, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            for (int i = 0; i < data.length; i++) {
                assertThat(((io.github.dfa1.vortex.reader.array.LongArray) result).getLong(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 512, 1023, 1024, 1025, 2048, 2049})
        void roundTrip_variousLengths_i32(int n) {
            // Given
            DType dtype = DType.I32;
            int[] data = new int[n];
            for (int i = 0; i < n; i++) {
                data[i] = i / 50;
            }
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, n, dtype, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(n);
            for (int i = 0; i < n; i++) {
                assertThat(((io.github.dfa1.vortex.reader.array.IntArray) result).getInt(i)).as("index %d", i).isEqualTo(i / 50);
            }
        }

        @Test
        void roundTrip_allDifferent_u16() {
            // Given
            DType dtype = DType.U16;
            short[] data = new short[256];
            for (int i = 0; i < 256; i++) {
                data[i] = (short) i;
            }
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, dtype, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            for (int i = 0; i < data.length; i++) {
                assertThat(Short.toUnsignedInt(((io.github.dfa1.vortex.reader.array.ShortArray) result).getShort(i)))
                        .as("index %d", i).isEqualTo(i);
            }
        }

        @Test
        void roundTrip_negativeValues_i32() {
            // Given
            DType dtype = DType.I32;
            int[] data = {-3, -3, -1, -1, 0, 0, 5};
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, dtype, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            for (int i = 0; i < data.length; i++) {
                assertThat(((io.github.dfa1.vortex.reader.array.IntArray) result).getInt(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }
    }

    @Nested
    class Bool {

        private static final ReadRegistry BOOL_REGISTRY = TestRegistry.ofDecoders(
                DECODER, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());

        @Test
        void roundTrip_clusteredRuns() {
            // Given — 2000 rows, one contiguous invalid stretch (rows 500-999), spanning multiple
            // FastLanes 1024-row chunks.
            int n = 2_000;
            boolean[] data = new boolean[n];
            for (int i = 0; i < n; i++) {
                data[i] = i < 500 || i >= 1_000;
            }

            // When
            EncodeResult encoded = RleEncodingEncoder.encodeBool(data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, n, DType.BOOL, BOOL_REGISTRY);
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(n);
            io.github.dfa1.vortex.reader.array.BoolArray bools = (io.github.dfa1.vortex.reader.array.BoolArray) result;
            for (int i = 0; i < n; i++) {
                assertThat(bools.getBoolean(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 512, 1023, 1024, 1025, 2048, 2049})
        void roundTrip_variousLengths(int n) {
            // Given — alternating short runs of true/false, various lengths around chunk boundaries
            boolean[] data = new boolean[n];
            for (int i = 0; i < n; i++) {
                data[i] = (i / 7) % 2 == 0;
            }

            // When
            EncodeResult encoded = RleEncodingEncoder.encodeBool(data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, n, DType.BOOL, BOOL_REGISTRY);
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(n);
            io.github.dfa1.vortex.reader.array.BoolArray bools = (io.github.dfa1.vortex.reader.array.BoolArray) result;
            for (int i = 0; i < n; i++) {
                assertThat(bools.getBoolean(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }
    }

    @Nested
    class Decode {

        @Test
        void decode_exactlyOneChunk_correctLength() {
            // Given
            DType dtype = DType.I32;
            int[] data = new int[1024];
            for (int i = 0; i < 1024; i++) {
                data[i] = i / 10;
            }
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, 1024, dtype, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(1024);
        }

        @Test
        void decode_crossesChunkBoundary_correctValues() {
            // Given
            DType dtype = DType.I32;
            int n = 2048;
            int[] data = new int[n];
            for (int i = 0; i < n; i++) {
                data[i] = i / 100;
            }
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, n, dtype, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            for (int i = 1000; i < 1048; i++) {
                assertThat(((io.github.dfa1.vortex.reader.array.IntArray) result).getInt(i)).as("index %d", i).isEqualTo(i / 100);
            }
        }

        @Test
        void decode_nullableIndices_returnsMaskedArrayWithCorrectValidity() {
            // Given
            DType dtype = DType.I32;
            int[] data = {10, 10, 20, 20};
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());

            List<MemorySegment> originalBufs = new ArrayList<>(encoded.buffers());
            // The indices child declares `indices_len` rows, which the encoder pads to a 1024
            // chunk boundary, so its validity bitmap has to cover all 1024 — not just the four
            // rows this fixture cares about. Bits 0 and 2 set: rows 0 and 2 valid, 1 and 3 null.
            byte[] validityBits = new byte[(1024 + 7) / 8];
            validityBits[0] = 0x05;
            originalBufs.add(MemorySegment.ofArray(validityBits));
            MemorySegment[] segments = originalBufs.toArray(new MemorySegment[0]);

            ArrayNode origRoot = toArrayNode(encoded.rootNode());
            ArrayNode origIndices = origRoot.children()[1];
            ArrayNode validityNode = new ArrayNode(
                    EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{3});
            ArrayNode nullableIndices = new ArrayNode(
                    origIndices.encodingId(), origIndices.metadata(),
                    new ArrayNode[]{validityNode}, origIndices.bufferIndices());
            ArrayNode root = new ArrayNode(
                    origRoot.encodingId(), origRoot.metadata(),
                    new ArrayNode[]{origRoot.children()[0], nullableIndices, origRoot.children()[2]},
                    origRoot.bufferIndices());

            ReadRegistry reg = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());
            DecodeContext ctx = new DecodeContext(root, dtype, data.length, segments, reg, Arena.ofAuto());

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result).isInstanceOf(MaskedArray.class);
            MaskedArray masked = (MaskedArray) result;
            assertThat(masked.isValid(0)).isTrue();
            assertThat(masked.isValid(1)).isFalse();
            assertThat(masked.isValid(2)).isTrue();
            assertThat(masked.isValid(3)).isFalse();
            IntArray values = (IntArray) masked.inner();
            assertThat(values.getInt(0)).isEqualTo(10);
            assertThat(values.getInt(2)).isEqualTo(20);
        }

        @Test
        void decode_partialLastChunk_correctLength() {
            // Given
            DType dtype = DType.I32;
            int n = 1500;
            int[] data = new int[n];
            for (int i = 0; i < n; i++) {
                data[i] = i / 100;
            }
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, n, dtype, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(n);
            for (int i = 0; i < n; i++) {
                assertThat(((io.github.dfa1.vortex.reader.array.IntArray) result).getInt(i)).as("index %d", i).isEqualTo(i / 100);
            }
        }

        @Test
        void encode_i32_metadata_valuesLen_matchesRunCount() throws Exception {
            // Given
            int[] data = {1, 1, 1, 2, 2, 2};

            // When
            EncodeResult result = ENCODER.encode(DTypes.I32, data, EncodeTestHelper.testCtx());

            // Then
            var metaSeg = result.rootNode().metadata();
            ProtoRLEMetadata meta = ProtoRLEMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            assertThat(meta.values_len()).isEqualTo(2);
            assertThat(meta.indices_len()).isGreaterThan(0);
            assertThat(meta.indices_ptype().value()).isEqualTo(1);
        }
    }
}
