package io.github.dfa1.vortex.encoding;

import com.github.luben.zstd.ZstdCompressCtx;
import io.airlift.compress.v3.zstd.ZstdCompressor;
import io.airlift.compress.v3.zstd.ZstdJavaCompressor;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.proto.EncodingProtos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdEncodingTest {

    @Nested
    class Encode {

        @Test
        void encode_i32_roundTrips() {
            // Given
            var sut = new ZstdEncoding();
            int[] data = {10, 20, 30, 40};

            // When
            EncodeResult result = sut.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(result, data.length, DTypes.I32, EncodingRegistry.empty());
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
            EncodeResult result = sut.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(result, data.length, DTypes.I64, EncodingRegistry.empty());
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
            EncodeResult result = sut.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(result, data.length, DTypes.UTF8, EncodingRegistry.empty());
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
            EncodeResult result = sut.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(result, data.length, DTypes.I32, EncodingRegistry.empty());
            IntArray decoded = (IntArray) sut.decode(ctx);

            // Then
            assertThat(decoded.length()).isZero();
        }

        @Test
        void encode_unsupportedDtype_throwsVortexException() {
            // Given
            var sut = new ZstdEncoding();

            // When / Then
            assertThatThrownBy(() -> sut.encode(new DType.Null(false), null, EncodeTestHelper.testCtx()))
                    .isInstanceOf(VortexException.class);
        }
    }

    @Nested
    class Decode {

        private static DecodeContext makeDictCtx(
                byte[] meta, DType dtype, long n, byte[] dictBytes, byte[]... compressedFrames
        ) {
            // buffer[0] = dict, buffer[1..] = frames
            MemorySegment[] segments = new MemorySegment[1 + compressedFrames.length];
            segments[0] = MemorySegment.ofArray(dictBytes);
            int[] bufIndices = new int[1 + compressedFrames.length];
            bufIndices[0] = 0;
            for (int i = 0; i < compressedFrames.length; i++) {
                segments[i + 1] = MemorySegment.ofArray(compressedFrames[i]);
                bufIndices[i + 1] = i + 1;
            }
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_ZSTD, ByteBuffer.wrap(meta),
                    new ArrayNode[0], bufIndices, null);
            return new DecodeContext(node, dtype, n, segments, EncodingRegistry.empty(), Arena.ofAuto());
        }

        private static byte[] makeDictFor(byte[]... samples) {
            // Repeat samples to meet zstd's minimum training data requirement (~1 KB)
            int total = 0;
            for (byte[] s : samples) {
                total += s.length;
            }
            int repeats = Math.max(1, 1024 / Math.max(total, 1));
            byte[][] expanded = new byte[samples.length * repeats][];
            for (int r = 0; r < repeats; r++) {
                System.arraycopy(samples, 0, expanded, r * samples.length, samples.length);
            }
            byte[] dict = new byte[256];
            com.github.luben.zstd.Zstd.trainFromBuffer(expanded, dict);
            return dict;
        }

        private static byte[] compressWithDict(byte[] data, byte[] dictBytes) {
            try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
                ctx.loadDict(dictBytes);
                return ctx.compress(data);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static DecodeContext makeNullableCtx(
                byte[] meta, DType dtype, long n, boolean[] validityBits, byte[]... compressedFrames
        ) {
            BoolEncoding boolEncoding = new BoolEncoding();
            EncodeResult validityResult = boolEncoding.encode(new DType.Bool(false), validityBits, EncodeTestHelper.testCtx());
            EncodeNode remappedValidity = EncodeNode.remapBufferIndices(
                    validityResult.rootNode(), compressedFrames.length);

            List<MemorySegment> allSegments = new ArrayList<>();
            int[] bufIndices = new int[compressedFrames.length];
            for (int i = 0; i < compressedFrames.length; i++) {
                allSegments.add(MemorySegment.ofArray(compressedFrames[i]));
                bufIndices[i] = i;
            }
            allSegments.addAll(validityResult.buffers());

            ArrayNode validityNode = toArrayNode(remappedValidity);
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_ZSTD, ByteBuffer.wrap(meta),
                    new ArrayNode[]{validityNode}, bufIndices, null);

            EncodingRegistry registry = EncodingRegistry.builder().register(new BoolEncoding()).build();

            return new DecodeContext(node, dtype, n, allSegments.toArray(new MemorySegment[0]),
                    registry, Arena.ofAuto());
        }

        private static ArrayNode toArrayNode(EncodeNode enc) {
            ArrayNode[] children = new ArrayNode[enc.children().length];
            for (int i = 0; i < children.length; i++) {
                children[i] = toArrayNode(enc.children()[i]);
            }
            return ArrayNode.of(enc.encodingId(), enc.metadata(), children, enc.bufferIndices(), null);
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
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_ZSTD, ByteBuffer.wrap(meta),
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

        @Test
        void decode_primitiveI32_singleFrame_roundTrips() {
            // Given
            var sut = new ZstdEncoding();
            int[] values = {10, 20, 30, 40};
            byte[] raw = toLeBytes(values);
            byte[] compressed = compress(raw);
            DecodeContext ctx = makeCtx(
                    metaNoDict(new long[]{raw.length}, new long[]{values.length}),
                    DTypes.I32, values.length, compressed
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
                    DTypes.I64, values.length, compressed
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
                    DTypes.UTF8, strings.length, compressed
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
            DecodeContext ctx = makeCtx(meta, DTypes.I32, 5, comp0, comp1);

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
            DecodeContext ctx = makeCtx(meta, DTypes.I32, 0);

            // When
            IntArray result = (IntArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isZero();
        }

        @Test
        void decode_withDictionary_primitive_roundTrips() {
            // Given
            var sut = new ZstdEncoding();
            int[] values = {10, 20, 30, 40};
            byte[] raw = toLeBytes(values);
            byte[] dictBytes = makeDictFor(raw);
            byte[] compressed = compressWithDict(raw, dictBytes);
            byte[] meta = EncodingProtos.ZstdMetadata.newBuilder()
                                  .setDictionarySize(dictBytes.length)
                                  .addFrames(EncodingProtos.ZstdFrameMetadata.newBuilder()
                                                     .setUncompressedSize(raw.length)
                                                     .setNValues(values.length))
                                  .build().toByteArray();
            // buffer[0]=dict, buffer[1]=frame
            DecodeContext ctx = makeDictCtx(meta, DTypes.I32, values.length, dictBytes, compressed);

            // When
            IntArray result = (IntArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(values.length);
            for (int i = 0; i < values.length; i++) {
                assertThat(result.getInt(i)).as("index %d", i).isEqualTo(values[i]);
            }
        }

        @Test
        void decode_withDictionary_utf8_roundTrips() {
            // Given
            var sut = new ZstdEncoding();
            String[] strings = {"hello", "world", "zstd"};
            byte[] raw = toLengthPrefixed(strings);
            byte[] dictBytes = makeDictFor(raw);
            byte[] compressed = compressWithDict(raw, dictBytes);
            byte[] meta = EncodingProtos.ZstdMetadata.newBuilder()
                                  .setDictionarySize(dictBytes.length)
                                  .addFrames(EncodingProtos.ZstdFrameMetadata.newBuilder()
                                                     .setUncompressedSize(raw.length)
                                                     .setNValues(strings.length))
                                  .build().toByteArray();
            DecodeContext ctx = makeDictCtx(meta, DTypes.UTF8, strings.length, dictBytes, compressed);

            // When
            VarBinArray result = (VarBinArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(strings.length);
            for (int i = 0; i < strings.length; i++) {
                assertThat(result.getString(i)).as("index %d", i).isEqualTo(strings[i]);
            }
        }

        @Test
        void decode_withDictionary_multipleFrames_roundTrips() {
            // Given
            var sut = new ZstdEncoding();
            int[] frame0 = {1, 2, 3};
            int[] frame1 = {4, 5};
            byte[] raw0 = toLeBytes(frame0);
            byte[] raw1 = toLeBytes(frame1);
            byte[] dictBytes = makeDictFor(raw0, raw1);
            byte[] comp0 = compressWithDict(raw0, dictBytes);
            byte[] comp1 = compressWithDict(raw1, dictBytes);
            byte[] meta = EncodingProtos.ZstdMetadata.newBuilder()
                                  .setDictionarySize(dictBytes.length)
                                  .addFrames(EncodingProtos.ZstdFrameMetadata.newBuilder()
                                                     .setUncompressedSize(raw0.length).setNValues(frame0.length))
                                  .addFrames(EncodingProtos.ZstdFrameMetadata.newBuilder()
                                                     .setUncompressedSize(raw1.length).setNValues(frame1.length))
                                  .build().toByteArray();
            DecodeContext ctx = makeDictCtx(meta, DTypes.I32, 5, dictBytes, comp0, comp1);

            // When
            IntArray result = (IntArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(5);
            for (int i = 0; i < 3; i++) {
                assertThat(result.getInt(i)).isEqualTo(frame0[i]);
            }
            for (int i = 0; i < 2; i++) {
                assertThat(result.getInt(3 + i)).isEqualTo(frame1[i]);
            }
        }

        @Test
        void decode_nullable_primitive_scattersValuesCorrectly() {
            // Given
            var sut = new ZstdEncoding();
            // validity: [true, false, true, false] — positions 0,2 are valid
            boolean[] validityBits = {true, false, true, false};
            // only valid values compressed: 10, 30
            byte[] raw = toLeBytes(new int[]{10, 30});
            byte[] compressed = compress(raw);
            DType i32Nullable = new DType.Primitive(PType.I32, true);
            DecodeContext ctx = makeNullableCtx(
                    metaNoDict(new long[]{raw.length}, new long[]{2}),
                    i32Nullable, 4, validityBits, compressed);

            // When
            MaskedArray result = (MaskedArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(4);
            assertThat(result.isValid(0)).isTrue();
            assertThat(result.isValid(1)).isFalse();
            assertThat(result.isValid(2)).isTrue();
            assertThat(result.isValid(3)).isFalse();
            IntArray child = (IntArray) result.inner();
            assertThat(child.getInt(0)).isEqualTo(10);
            assertThat(child.getInt(2)).isEqualTo(30);
        }

        @Test
        void decode_nullable_utf8_scattersValuesCorrectly() {
            // Given
            var sut = new ZstdEncoding();
            // validity: [true, false, true] — positions 0,2 are valid
            boolean[] validityBits = {true, false, true};
            // only valid strings compressed
            byte[] raw = toLengthPrefixed(new String[]{"hello", "world"});
            byte[] compressed = compress(raw);
            DType utf8Nullable = new DType.Utf8(true);
            DecodeContext ctx = makeNullableCtx(
                    metaNoDict(new long[]{raw.length}, new long[]{2}),
                    utf8Nullable, 3, validityBits, compressed);

            // When
            MaskedArray result = (MaskedArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(3);
            assertThat(result.isValid(0)).isTrue();
            assertThat(result.isValid(1)).isFalse();
            assertThat(result.isValid(2)).isTrue();
            VarBinArray child = (VarBinArray) result.inner();
            assertThat(child.getString(0)).isEqualTo("hello");
            assertThat(child.getString(2)).isEqualTo("world");
        }

        @Test
        void decode_allNull_returnsEmptyMaskedArray() {
            // Given
            var sut = new ZstdEncoding();
            boolean[] validityBits = {false, false, false};
            // no valid values — zero-length compressed buffer
            byte[] raw = new byte[0];
            byte[] compressed = compress(raw);
            DType i32Nullable = new DType.Primitive(PType.I32, true);
            DecodeContext ctx = makeNullableCtx(
                    metaNoDict(new long[]{raw.length}, new long[]{0}),
                    i32Nullable, 3, validityBits, compressed);

            // When
            MaskedArray result = (MaskedArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(3);
            assertThat(result.isValid(0)).isFalse();
            assertThat(result.isValid(1)).isFalse();
            assertThat(result.isValid(2)).isFalse();
        }

        @Test
        void decode_missingMetadata_throwsVortexException() {
            // Given
            var sut = new ZstdEncoding();
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_ZSTD, null, new ArrayNode[0], new int[0], null);
            DecodeContext ctx = new DecodeContext(node, DTypes.I32, 0, new MemorySegment[0],
                    EncodingRegistry.empty(), Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing metadata");
        }
    }

    @Nested
    class Metadata {

        @Test
        void encode_i32_metadata_framesCount_isNonZero() throws Exception {
            // Given — any non-empty encode produces at least one zstd frame
            // if tag drifts, frames list is empty and decode silently produces no data
            int[] data = new int[100];
            for (int i = 0; i < data.length; i++) {
                data[i] = i;
            }
            ZstdEncoding sut = new ZstdEncoding();

            // When
            EncodeResult result = sut.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
            EncodingProtos.ZstdMetadata meta =
                    EncodingProtos.ZstdMetadata.parseFrom(result.rootNode().metadata().duplicate());

            // Then
            assertThat(meta.getFramesCount()).isGreaterThan(0);
        }
    }
}
