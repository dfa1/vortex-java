package io.github.dfa1.vortex.writer.encode;

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
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.proto.ZstdFrameMetadata;
import io.github.dfa1.vortex.proto.ZstdMetadata;
import io.github.dfa1.vortex.reader.decode.BoolEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ZstdEncodingDecoder;
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

class ZstdEncodingEncoderTest {

    private static final ZstdEncodingEncoder ENCODER = new ZstdEncodingEncoder();
    private static final ZstdEncodingDecoder DECODER = new ZstdEncodingDecoder();
    private static final BoolEncodingEncoder BOOL_ENCODER = new BoolEncodingEncoder();

    @Nested
    class Encode {

        @Test
        void encode_i32_roundTrips() {
            int[] data = {10, 20, 30, 40};
            EncodeResult result = ENCODER.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(result, data.length, DTypes.I32, ReadRegistry.empty());
            IntArray decoded = (IntArray) DECODER.decode(ctx);
            for (int i = 0; i < data.length; i++) {
                assertThat(decoded.getInt(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_i64_roundTrips() {
            long[] data = {100L, 200L, 300L};
            EncodeResult result = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(result, data.length, DTypes.I64, ReadRegistry.empty());
            LongArray decoded = (LongArray) DECODER.decode(ctx);
            for (int i = 0; i < data.length; i++) {
                assertThat(decoded.getLong(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_utf8_roundTrips() {
            String[] data = {"hello", "world", "zstd"};
            EncodeResult result = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(result, data.length, DTypes.UTF8, ReadRegistry.empty());
            VarBinArray decoded = (VarBinArray) DECODER.decode(ctx);
            for (int i = 0; i < data.length; i++) {
                assertThat(decoded.getString(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_emptyArray_roundTrips() {
            int[] data = {};
            EncodeResult result = ENCODER.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(result, data.length, DTypes.I32, ReadRegistry.empty());
            IntArray decoded = (IntArray) DECODER.decode(ctx);
            assertThat(decoded.length()).isZero();
        }

        @Test
        void encode_unsupportedDtype_throwsVortexException() {
            assertThatThrownBy(() -> ENCODER.encode(new DType.Null(false), null, EncodeTestHelper.testCtx()))
                    .isInstanceOf(VortexException.class);
        }
    }

    @Nested
    class Decode {

        private static DecodeContext makeDictCtx(
                byte[] meta, DType dtype, long n, byte[] dictBytes, byte[]... compressedFrames
        ) {
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
            return new DecodeContext(node, dtype, n, segments, ReadRegistry.empty(), Arena.ofAuto());
        }

        private static byte[] makeDictFor(byte[]... samples) {
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
            EncodeResult validityResult = BOOL_ENCODER.encode(new DType.Bool(false), validityBits, EncodeTestHelper.testCtx());
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

            ReadRegistry registry = TestRegistry.ofDecoders(new BoolEncodingDecoder());

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

        private static byte[] compress(byte[] input) {
            ZstdCompressor compressor = new ZstdJavaCompressor();
            byte[] out = new byte[compressor.maxCompressedLength(input.length)];
            int len = compressor.compress(input, 0, input.length, out, 0, out.length);
            return Arrays.copyOf(out, len);
        }

        private static byte[] metaNoDict(long[] uncompressedSizes, long[] nValues) {
            java.util.List<ZstdFrameMetadata> frames = new java.util.ArrayList<>();
            for (int i = 0; i < uncompressedSizes.length; i++) {
                frames.add(new ZstdFrameMetadata(uncompressedSizes[i], nValues[i]));
            }
            return new ZstdMetadata(0, frames).encode();
        }

        private static byte[] toLeBytes(int[] values) {
            ByteBuffer buf = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : values) {
                buf.putInt(v);
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
        void decode_withDictionary_utf8_roundTrips() {
            String[] strings = {"hello", "world", "zstd"};
            byte[] raw = toLengthPrefixed(strings);
            byte[] dictBytes = makeDictFor(raw);
            byte[] compressed = compressWithDict(raw, dictBytes);
            byte[] meta = new ZstdMetadata(dictBytes.length,
                    java.util.List.of(new ZstdFrameMetadata(raw.length, strings.length))).encode();
            DecodeContext ctx = makeDictCtx(meta, DTypes.UTF8, strings.length, dictBytes, compressed);

            VarBinArray result = (VarBinArray) DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(strings.length);
            for (int i = 0; i < strings.length; i++) {
                assertThat(result.getString(i)).as("index %d", i).isEqualTo(strings[i]);
            }
        }

        @Test
        void decode_withDictionary_multipleFrames_roundTrips() {
            int[] frame0 = {1, 2, 3};
            int[] frame1 = {4, 5};
            byte[] raw0 = toLeBytes(frame0);
            byte[] raw1 = toLeBytes(frame1);
            byte[] dictBytes = makeDictFor(raw0, raw1);
            byte[] comp0 = compressWithDict(raw0, dictBytes);
            byte[] comp1 = compressWithDict(raw1, dictBytes);
            byte[] meta = new ZstdMetadata(dictBytes.length, java.util.List.of(
                    new ZstdFrameMetadata(raw0.length, frame0.length),
                    new ZstdFrameMetadata(raw1.length, frame1.length))).encode();
            DecodeContext ctx = makeDictCtx(meta, DTypes.I32, 5, dictBytes, comp0, comp1);

            IntArray result = (IntArray) DECODER.decode(ctx);

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
            boolean[] validityBits = {true, false, true, false};
            byte[] raw = toLeBytes(new int[]{10, 30});
            byte[] compressed = compress(raw);
            DType i32Nullable = new DType.Primitive(PType.I32, true);
            DecodeContext ctx = makeNullableCtx(
                    metaNoDict(new long[]{raw.length}, new long[]{2}),
                    i32Nullable, 4, validityBits, compressed);

            MaskedArray result = (MaskedArray) DECODER.decode(ctx);

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
            boolean[] validityBits = {true, false, true};
            byte[] raw = toLengthPrefixed(new String[]{"hello", "world"});
            byte[] compressed = compress(raw);
            DType utf8Nullable = new DType.Utf8(true);
            DecodeContext ctx = makeNullableCtx(
                    metaNoDict(new long[]{raw.length}, new long[]{2}),
                    utf8Nullable, 3, validityBits, compressed);

            MaskedArray result = (MaskedArray) DECODER.decode(ctx);

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
            boolean[] validityBits = {false, false, false};
            byte[] raw = new byte[0];
            byte[] compressed = compress(raw);
            DType i32Nullable = new DType.Primitive(PType.I32, true);
            DecodeContext ctx = makeNullableCtx(
                    metaNoDict(new long[]{raw.length}, new long[]{0}),
                    i32Nullable, 3, validityBits, compressed);

            MaskedArray result = (MaskedArray) DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(3);
            assertThat(result.isValid(0)).isFalse();
            assertThat(result.isValid(1)).isFalse();
            assertThat(result.isValid(2)).isFalse();
        }

        @Test
        void decode_missingMetadata_throwsVortexException() {
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_ZSTD, null, new ArrayNode[0], new int[0], null);
            DecodeContext ctx = new DecodeContext(node, DTypes.I32, 0, new MemorySegment[0],
                    ReadRegistry.empty(), Arena.ofAuto());

            assertThatThrownBy(() -> DECODER.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing metadata");
        }
    }

    @Nested
    class Metadata {

        @Test
        void encode_i32_metadata_framesCount_isNonZero() throws Exception {
            int[] data = new int[100];
            for (int i = 0; i < data.length; i++) {
                data[i] = i;
            }
            EncodeResult result = ENCODER.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
            var metaSeg = java.lang.foreign.MemorySegment.ofBuffer(result.rootNode().metadata().duplicate());
            ZstdMetadata meta = ZstdMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            assertThat(meta.frames().size()).isGreaterThan(0);
        }
    }
}
