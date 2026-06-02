package io.github.dfa1.vortex.encoding;

import io.airlift.compress.v3.zstd.ZstdJavaCompressor;
import io.airlift.compress.v3.zstd.ZstdCompressor;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.proto.EncodingProtos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdEncodingTest {

    private static final DType I32 = new DType.Primitive(PType.I32, false);
    private static final DType I64 = new DType.Primitive(PType.I64, false);
    private static final DType UTF8 = new DType.Utf8(false);

    @Nested
    class Encode {

        @Test
        void encode_i32_roundTrips() {
            // Given
            var sut = new ZstdEncoding();
            int[] data = {10, 20, 30, 40};

            // When
            EncodeResult result = sut.encode(I32, data);
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(result, data.length, I32, EncodingRegistry.empty());
            IntArray decoded = (IntArray) sut.decode(ctx);

            // Then
            assertThat(decoded.length()).isEqualTo(data.length);
            for (int i = 0; i < data.length; i++) {
                assertThat(decoded.getInt(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_i64_roundTrips() {
            // Given
            var sut = new ZstdEncoding();
            long[] data = {100L, 200L, 300L};

            // When
            EncodeResult result = sut.encode(I64, data);
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(result, data.length, I64, EncodingRegistry.empty());
            LongArray decoded = (LongArray) sut.decode(ctx);

            // Then
            assertThat(decoded.length()).isEqualTo(data.length);
            for (int i = 0; i < data.length; i++) {
                assertThat(decoded.getLong(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_utf8_roundTrips() {
            // Given
            var sut = new ZstdEncoding();
            String[] data = {"hello", "world", "zstd"};

            // When
            EncodeResult result = sut.encode(UTF8, data);
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(result, data.length, UTF8, EncodingRegistry.empty());
            VarBinArray decoded = (VarBinArray) sut.decode(ctx);

            // Then
            assertThat(decoded.length()).isEqualTo(data.length);
            for (int i = 0; i < data.length; i++) {
                assertThat(decoded.getString(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_emptyArray_roundTrips() {
            // Given
            var sut = new ZstdEncoding();
            int[] data = {};

            // When
            EncodeResult result = sut.encode(I32, data);
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(result, data.length, I32, EncodingRegistry.empty());
            IntArray decoded = (IntArray) sut.decode(ctx);

            // Then
            assertThat(decoded.length()).isZero();
        }

        @Test
        void encode_unsupportedDtype_throwsVortexException() {
            // Given
            var sut = new ZstdEncoding();

            // When / Then
            assertThatThrownBy(() -> sut.encode(new DType.Null(false), null))
                    .isInstanceOf(VortexException.class);
        }
    }

    @Nested
    class Decode {

        @Test
        void decode_primitiveI32_singleFrame_roundTrips() {
            // Given
            var sut = new ZstdEncoding();
            int[] values = {10, 20, 30, 40};
            byte[] raw = toLeBytes(values);
            byte[] compressed = compress(raw);
            DecodeContext ctx = makeCtx(
                    metaNoDict(new long[]{raw.length}, new long[]{values.length}),
                    I32, values.length, compressed
            );

            // When
            IntArray result = (IntArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(values.length);
            for (int i = 0; i < values.length; i++) {
                assertThat(result.getInt(i)).as("index %d", i).isEqualTo(values[i]);
            }
        }

        @Test
        void decode_primitiveI64_singleFrame_roundTrips() {
            // Given
            var sut = new ZstdEncoding();
            long[] values = {100L, 200L, 300L};
            byte[] raw = toLeBytes(values);
            byte[] compressed = compress(raw);
            DecodeContext ctx = makeCtx(
                    metaNoDict(new long[]{raw.length}, new long[]{values.length}),
                    I64, values.length, compressed
            );

            // When
            LongArray result = (LongArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(values.length);
            for (int i = 0; i < values.length; i++) {
                assertThat(result.getLong(i)).as("index %d", i).isEqualTo(values[i]);
            }
        }

        @Test
        void decode_utf8_singleFrame_roundTrips() {
            // Given
            var sut = new ZstdEncoding();
            String[] strings = {"hello", "world", "zstd"};
            byte[] raw = toLengthPrefixed(strings);
            byte[] compressed = compress(raw);
            DecodeContext ctx = makeCtx(
                    metaNoDict(new long[]{raw.length}, new long[]{strings.length}),
                    UTF8, strings.length, compressed
            );

            // When
            VarBinArray result = (VarBinArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(strings.length);
            for (int i = 0; i < strings.length; i++) {
                assertThat(result.getString(i)).as("index %d", i).isEqualTo(strings[i]);
            }
        }

        @Test
        void decode_primitiveI32_multipleFrames_roundTrips() {
            // Given
            var sut = new ZstdEncoding();
            int[] frame0Values = {1, 2, 3};
            int[] frame1Values = {4, 5};
            byte[] raw0 = toLeBytes(frame0Values);
            byte[] raw1 = toLeBytes(frame1Values);
            byte[] comp0 = compress(raw0);
            byte[] comp1 = compress(raw1);
            byte[] meta = metaNoDict(
                    new long[]{raw0.length, raw1.length},
                    new long[]{frame0Values.length, frame1Values.length}
            );
            DecodeContext ctx = makeCtx(meta, I32, 5, comp0, comp1);

            // When
            IntArray result = (IntArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(5);
            assertThat(result.getInt(0)).isEqualTo(1);
            assertThat(result.getInt(4)).isEqualTo(5);
        }

        @Test
        void decode_emptyArray_returnsZeroLengthArray() {
            // Given
            var sut = new ZstdEncoding();
            byte[] meta = metaNoDict(new long[0], new long[0]);
            DecodeContext ctx = makeCtx(meta, I32, 0);

            // When
            IntArray result = (IntArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isZero();
        }

        @Test
        void decode_withDictionary_throwsVortexException() {
            // Given
            var sut = new ZstdEncoding();
            byte[] meta = EncodingProtos.ZstdMetadata.newBuilder()
                    .setDictionarySize(42)
                    .build().toByteArray();
            DecodeContext ctx = makeCtx(meta, I32, 0);

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("dictionary not supported");
        }

        @Test
        void decode_withValidityChild_throwsVortexException() {
            // Given
            var sut = new ZstdEncoding();
            byte[] meta = metaNoDict(new long[0], new long[0]);
            ArrayNode validityChild = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[0], null);
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_ZSTD, ByteBuffer.wrap(meta),
                    new ArrayNode[]{validityChild}, new int[0], null);
            DecodeContext ctx = new DecodeContext(node, I32, 0, new MemorySegment[0],
                    EncodingRegistry.empty(), Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("nullable arrays not supported");
        }

        @Test
        void decode_missingMetadata_throwsVortexException() {
            // Given
            var sut = new ZstdEncoding();
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_ZSTD, null, new ArrayNode[0], new int[0], null);
            DecodeContext ctx = new DecodeContext(node, I32, 0, new MemorySegment[0],
                    EncodingRegistry.empty(), Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing metadata");
        }

        private static byte[] metaNoDict(long[] uncompressedSizes, long[] nValues) {
            EncodingProtos.ZstdMetadata.Builder builder = EncodingProtos.ZstdMetadata.newBuilder()
                    .setDictionarySize(0);
            for (int i = 0; i < uncompressedSizes.length; i++) {
                builder.addFrames(EncodingProtos.ZstdFrameMetadata.newBuilder()
                        .setUncompressedSize(uncompressedSizes[i])
                        .setNValues(nValues[i]));
            }
            return builder.build().toByteArray();
        }

        private static DecodeContext makeCtx(byte[] meta, DType dtype, long n, byte[]... compressedFrames) {
            MemorySegment[] segments = new MemorySegment[compressedFrames.length];
            int[] bufIndices = new int[compressedFrames.length];
            for (int i = 0; i < compressedFrames.length; i++) {
                segments[i] = MemorySegment.ofArray(compressedFrames[i]);
                bufIndices[i] = i;
            }
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_ZSTD, ByteBuffer.wrap(meta),
                    new ArrayNode[0], bufIndices, null);
            return new DecodeContext(node, dtype, n, segments, EncodingRegistry.empty(), Arena.ofAuto());
        }

        private static byte[] compress(byte[] input) {
            ZstdCompressor compressor = new ZstdJavaCompressor();
            byte[] out = new byte[compressor.maxCompressedLength(input.length)];
            int len = compressor.compress(input, 0, input.length, out, 0, out.length);
            return Arrays.copyOf(out, len);
        }

        private static byte[] toLeBytes(int[] values) {
            ByteBuffer buf = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : values) {
                buf.putInt(v);
            }
            return buf.array();
        }

        private static byte[] toLeBytes(long[] values) {
            ByteBuffer buf = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (long v : values) {
                buf.putLong(v);
            }
            return buf.array();
        }

        private static byte[] toLengthPrefixed(String[] strings) {
            int total = 0;
            for (String s : strings) {
                total += 4 + s.getBytes(StandardCharsets.UTF_8).length;
            }
            ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
            for (String s : strings) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                buf.putInt(bytes.length);
                buf.put(bytes);
            }
            return buf.array();
        }
    }
}
