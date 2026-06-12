package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.decode.KnownArrayNode;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.proto.RLEMetadata;
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

    private static KnownArrayNode toArrayNode(EncodeNode enc) {
        ArrayNode[] children = new ArrayNode[enc.children().length];
        for (int i = 0; i < children.length; i++) {
            children[i] = toArrayNode(enc.children()[i]);
        }
        return new KnownArrayNode(enc.encodingId(), enc.metadata(), children, enc.bufferIndices(), ArrayStats.empty());
    }

    @Nested
    class Encode {

        @Test
        void roundTrip_empty_i32() {
            DType dtype = new DType.Primitive(PType.I32, false);
            EncodeResult encoded = ENCODER.encode(dtype, new int[0], EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, 0, dtype, REGISTRY);
            Array result = DECODER.decode(ctx);
            assertThat(result.length()).isZero();
        }

        @Test
        void roundTrip_singleElement_i32() {
            DType dtype = new DType.Primitive(PType.I32, false);
            int[] data = {42};
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, dtype, REGISTRY);
            Array result = DECODER.decode(ctx);
            assertThat(result.length()).isEqualTo(1);
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, 0)).isEqualTo(42);
        }

        @Test
        void roundTrip_constantArray_i32() {
            DType dtype = new DType.Primitive(PType.I32, false);
            int n = 2048;
            int[] data = new int[n];
            for (int i = 0; i < n; i++) {
                data[i] = 99;
            }
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, n, dtype, REGISTRY);
            Array result = DECODER.decode(ctx);
            assertThat(result.length()).isEqualTo(n);
            for (int i = 0; i < n; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(99);
            }
        }

        @Test
        void roundTrip_classicRunLengthData_i32() {
            DType dtype = new DType.Primitive(PType.I32, false);
            int[] data = {1, 1, 1, 2, 2, 3};
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, dtype, REGISTRY);
            Array result = DECODER.decode(ctx);
            for (int i = 0; i < data.length; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void roundTrip_multipleChunks_i32() {
            DType dtype = new DType.Primitive(PType.I32, false);
            int n = 3000;
            int[] data = new int[n];
            for (int i = 0; i < n; i++) {
                data[i] = i / 100;
            }
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, n, dtype, REGISTRY);
            Array result = DECODER.decode(ctx);
            assertThat(result.length()).isEqualTo(n);
            for (int i = 0; i < n; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(i / 100);
            }
        }

        @Test
        void roundTrip_i64() {
            DType dtype = new DType.Primitive(PType.I64, false);
            long[] data = {100L, 100L, 200L, 300L, 300L, 300L};
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, dtype, REGISTRY);
            Array result = DECODER.decode(ctx);
            for (int i = 0; i < data.length; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_LONG, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 512, 1023, 1024, 1025, 2048, 2049})
        void roundTrip_variousLengths_i32(int n) {
            DType dtype = new DType.Primitive(PType.I32, false);
            int[] data = new int[n];
            for (int i = 0; i < n; i++) {
                data[i] = i / 50;
            }
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, n, dtype, REGISTRY);
            Array result = DECODER.decode(ctx);
            assertThat(result.length()).isEqualTo(n);
            for (int i = 0; i < n; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(i / 50);
            }
        }

        @Test
        void roundTrip_allDifferent_u16() {
            DType dtype = new DType.Primitive(PType.U16, false);
            short[] data = new short[256];
            for (int i = 0; i < 256; i++) {
                data[i] = (short) i;
            }
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, dtype, REGISTRY);
            Array result = DECODER.decode(ctx);
            for (int i = 0; i < data.length; i++) {
                assertThat(Short.toUnsignedInt(ArraySegments.of(result).get(PTypeIO.LE_SHORT, (long) i * 2)))
                        .as("index %d", i).isEqualTo(i);
            }
        }

        @Test
        void roundTrip_negativeValues_i32() {
            DType dtype = new DType.Primitive(PType.I32, false);
            int[] data = {-3, -3, -1, -1, 0, 0, 5};
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, dtype, REGISTRY);
            Array result = DECODER.decode(ctx);
            for (int i = 0; i < data.length; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
            }
        }
    }

    @Nested
    class Decode {

        @Test
        void decode_exactlyOneChunk_correctLength() {
            DType dtype = new DType.Primitive(PType.I32, false);
            int[] data = new int[1024];
            for (int i = 0; i < 1024; i++) {
                data[i] = i / 10;
            }
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, 1024, dtype, REGISTRY);
            Array result = DECODER.decode(ctx);
            assertThat(result.length()).isEqualTo(1024);
        }

        @Test
        void decode_crossesChunkBoundary_correctValues() {
            DType dtype = new DType.Primitive(PType.I32, false);
            int n = 2048;
            int[] data = new int[n];
            for (int i = 0; i < n; i++) {
                data[i] = i / 100;
            }
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, n, dtype, REGISTRY);
            Array result = DECODER.decode(ctx);
            for (int i = 1000; i < 1048; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(i / 100);
            }
        }

        @Test
        void decode_nullableIndices_returnsMaskedArrayWithCorrectValidity() {
            DType dtype = new DType.Primitive(PType.I32, false);
            int[] data = {10, 10, 20, 20};
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());

            List<MemorySegment> originalBufs = new ArrayList<>(encoded.buffers());
            MemorySegment validityBuf = MemorySegment.ofArray(new byte[]{0x05});
            originalBufs.add(validityBuf);
            MemorySegment[] segments = originalBufs.toArray(new MemorySegment[0]);

            KnownArrayNode origRoot = toArrayNode(encoded.rootNode());
            KnownArrayNode origIndices = (KnownArrayNode) origRoot.children()[1];
            ArrayNode validityNode = ArrayNode.of(
                    EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{3}, ArrayStats.empty());
            ArrayNode nullableIndices = ArrayNode.of(
                    origIndices.encodingId(), origIndices.metadata(),
                    new ArrayNode[]{validityNode}, origIndices.bufferIndices(), ArrayStats.empty());
            ArrayNode root = ArrayNode.of(
                    origRoot.encodingId(), origRoot.metadata(),
                    new ArrayNode[]{origRoot.children()[0], nullableIndices, origRoot.children()[2]},
                    origRoot.bufferIndices(), ArrayStats.empty());

            ReadRegistry reg = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());
            DecodeContext ctx = new DecodeContext(root, dtype, data.length, segments, reg, Arena.ofAuto());

            Array result = DECODER.decode(ctx);

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
            DType dtype = new DType.Primitive(PType.I32, false);
            int n = 1500;
            int[] data = new int[n];
            for (int i = 0; i < n; i++) {
                data[i] = i / 100;
            }
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, n, dtype, REGISTRY);
            Array result = DECODER.decode(ctx);
            assertThat(result.length()).isEqualTo(n);
            for (int i = 0; i < n; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(i / 100);
            }
        }

        @Test
        void encode_i32_metadata_valuesLen_matchesRunCount() throws Exception {
            int[] data = {1, 1, 1, 2, 2, 2};
            EncodeResult result = ENCODER.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
            var metaSeg = MemorySegment.ofBuffer(result.rootNode().metadata().duplicate());
            RLEMetadata meta = RLEMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            assertThat(meta.values_len()).isEqualTo(2);
            assertThat(meta.indices_len()).isGreaterThan(0);
            assertThat(meta.indices_ptype().value()).isEqualTo(1);
        }
    }
}
