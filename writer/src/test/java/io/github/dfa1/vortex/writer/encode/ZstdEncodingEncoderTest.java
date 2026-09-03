package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.proto.ProtoZstdFrameMetadata;
import io.github.dfa1.vortex.core.proto.ProtoZstdMetadata;
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
        void accepts_binary_true() {
            // Given
            // When
            boolean result = ENCODER.accepts(DTypes.BINARY);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void acceptsNullable_binary_true() {
            // Given
            // When
            boolean result = ENCODER.acceptsNullable(DTypes.BINARY);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void encode_binaryRawBytes_roundTripsByteForByte_notThroughUtf8() {
            // Given — non-UTF8 bytes (0x80 alone is not a valid UTF-8 sequence); routing this
            // through String[].getBytes(UTF_8) would corrupt it via the UTF-8 replacement
            // character. DType.Binary carries data as byte[][], not String[] (#352).
            byte[][] data = {{(byte) 0x80, (byte) 0xFF, 0x00, 0x01}, {}, {0x41, 0x42}};

            // When
            EncodeResult result = ENCODER.encode(DTypes.BINARY, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(result, data.length, DTypes.BINARY, ReadRegistry.empty());
            VarBinArray decoded = (VarBinArray) DECODER.decode(ctx);

            // Then
            for (int i = 0; i < data.length; i++) {
                assertThat(decoded.getBytes(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_nullableBinary_roundTrips() {
            // Given — nullable binary as a NullableData carrier (byte[][] with null elements + the
            // derived validity), mirroring encode_nullableUtf8_roundTrips for raw bytes.
            byte[][] storage = {{0x01}, null, {(byte) 0x80, (byte) 0xFF}, null};
            boolean[] validity = {true, false, true, false};
            DType binaryNullable = new DType.Binary(true);
            NullableData data = new NullableData(storage, validity);

            // When
            EncodeResult result = ENCODER.encode(binaryNullable, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(
                    result, validity.length, binaryNullable, TestRegistry.ofDecoders(new BoolEncodingDecoder()));
            MaskedArray decoded = (MaskedArray) DECODER.decode(ctx);

            // Then
            assertThat(decoded.length()).isEqualTo(4);
            assertThat(decoded.isValid(0)).isTrue();
            assertThat(decoded.isValid(1)).isFalse();
            assertThat(decoded.isValid(2)).isTrue();
            assertThat(decoded.isValid(3)).isFalse();
            VarBinArray child = (VarBinArray) decoded.inner();
            assertThat(child.getBytes(0)).isEqualTo(storage[0]);
            assertThat(child.getBytes(2)).isEqualTo(storage[2]);
        }

        @Test
        void encode_nonNullableBinaryWithNull_throwsVortexException() {
            // Given — a non-nullable Binary dtype whose data carries a stray null. The encoder must
            // reject it rather than silently emit a nullable layout the dtype does not declare.
            byte[][] data = {{0x01}, null, {0x02}};
            DType binary = new DType.Binary(false);
            EncodeContext ctx = EncodeTestHelper.testCtx();

            // When / Then
            assertThatThrownBy(() -> ENCODER.encode(binary, data, ctx))
                    .isInstanceOf(VortexException.class);
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
        void encode_nullablePrimitive_roundTrips() {
            // Given — nulls at positions 1 and 3; the storage array holds zero placeholders there,
            // validity marks the real rows. Only valid values must reach the compressed payload,
            // so the decoder can scatter them back over the validity mask carried by child[0].
            int[] storage = {10, 0, 30, 0};
            boolean[] validity = {true, false, true, false};
            DType i32Nullable = new DType.Primitive(PType.I32, true);
            NullableData data = new NullableData(storage, validity);

            // When
            EncodeResult result = ENCODER.encode(i32Nullable, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(
                    result, validity.length, i32Nullable, TestRegistry.ofDecoders(new BoolEncodingDecoder()));
            MaskedArray decoded = (MaskedArray) DECODER.decode(ctx);

            // Then
            assertThat(decoded.length()).isEqualTo(4);
            assertThat(decoded.isValid(0)).isTrue();
            assertThat(decoded.isValid(1)).isFalse();
            assertThat(decoded.isValid(2)).isTrue();
            assertThat(decoded.isValid(3)).isFalse();
            IntArray child = (IntArray) decoded.inner();
            assertThat(child.getInt(0)).isEqualTo(10);
            assertThat(child.getInt(2)).isEqualTo(30);
        }

        @Test
        void encode_nullableUtf8_roundTrips() {
            // Given — nullable utf8 as a NullableData carrier (String[] with null elements + the
            // derived validity), the unified nullable shape. The encoder strips nulls, compresses
            // only the valid strings, and emits the validity bitmap as child[0].
            String[] storage = {"hello", null, "world", null};
            boolean[] validity = {true, false, true, false};
            DType utf8Nullable = new DType.Utf8(true);
            NullableData data = new NullableData(storage, validity);

            // When
            EncodeResult result = ENCODER.encode(utf8Nullable, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(
                    result, validity.length, utf8Nullable, TestRegistry.ofDecoders(new BoolEncodingDecoder()));
            MaskedArray decoded = (MaskedArray) DECODER.decode(ctx);

            // Then
            assertThat(decoded.length()).isEqualTo(4);
            assertThat(decoded.isValid(0)).isTrue();
            assertThat(decoded.isValid(1)).isFalse();
            assertThat(decoded.isValid(2)).isTrue();
            assertThat(decoded.isValid(3)).isFalse();
            VarBinArray child = (VarBinArray) decoded.inner();
            assertThat(child.getString(0)).isEqualTo("hello");
            assertThat(child.getString(2)).isEqualTo("world");
        }

        @Test
        void encode_allNullPrimitive_roundTrips() {
            // Given — every row null: zero valid values reach the payload, so the compressed frame
            // is built from a 0-byte slice. Guards the empty-payload corner of packValidBytes /
            // zstd compress-empty, and the all-false validity bitmap.
            int[] storage = {0, 0, 0};
            boolean[] validity = {false, false, false};
            DType i32Nullable = new DType.Primitive(PType.I32, true);
            NullableData data = new NullableData(storage, validity);

            // When
            EncodeResult result = ENCODER.encode(i32Nullable, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(
                    result, validity.length, i32Nullable, TestRegistry.ofDecoders(new BoolEncodingDecoder()));
            MaskedArray decoded = (MaskedArray) DECODER.decode(ctx);

            // Then
            assertThat(decoded.length()).isEqualTo(3);
            assertThat(decoded.isValid(0)).isFalse();
            assertThat(decoded.isValid(1)).isFalse();
            assertThat(decoded.isValid(2)).isFalse();
        }

        @Test
        void encode_allNullUtf8_roundTrips() {
            // Given — every string null: stripNulls yields an empty array, so the length-prefixed
            // payload is 0 bytes. Guards the empty-payload corner of the nullable varbin path.
            String[] storage = {null, null, null};
            boolean[] validity = {false, false, false};
            DType utf8Nullable = new DType.Utf8(true);
            NullableData data = new NullableData(storage, validity);

            // When
            EncodeResult result = ENCODER.encode(utf8Nullable, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(
                    result, validity.length, utf8Nullable, TestRegistry.ofDecoders(new BoolEncodingDecoder()));
            MaskedArray decoded = (MaskedArray) DECODER.decode(ctx);

            // Then
            assertThat(decoded.length()).isEqualTo(3);
            assertThat(decoded.isValid(0)).isFalse();
            assertThat(decoded.isValid(1)).isFalse();
            assertThat(decoded.isValid(2)).isFalse();
        }

        @Test
        void encode_nonNullableUtf8WithNull_throwsVortexException() {
            // Given — a non-nullable Utf8 dtype whose data carries a stray null. The encoder must
            // reject it rather than silently emit a nullable layout the dtype does not declare.
            String[] data = {"a", null, "c"};
            DType utf8 = new DType.Utf8(false);

            // When / Then
            assertThatThrownBy(() -> ENCODER.encode(utf8, data, EncodeTestHelper.testCtx()))
                    .isInstanceOf(VortexException.class);
        }

        @Test
        void encode_unsupportedDtype_throwsVortexException() {
            assertThatThrownBy(() -> ENCODER.encode(DType.NULL, null, EncodeTestHelper.testCtx()))
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
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_ZSTD, MemorySegment.ofArray(meta),
                    new ArrayNode[0], bufIndices);
            return new DecodeContext(node, dtype, n, segments, ReadRegistry.empty(), Arena.ofAuto());
        }

        private static DecodeContext makeNullableCtx(
                byte[] meta, DType dtype, long n, boolean[] validityBits, byte[]... compressedFrames
        ) {
            EncodeResult validityResult = BOOL_ENCODER.encode(DType.BOOL, validityBits, EncodeTestHelper.testCtx());
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
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_ZSTD, MemorySegment.ofArray(meta),
                    new ArrayNode[]{validityNode}, bufIndices);

            ReadRegistry registry = TestRegistry.ofDecoders(new BoolEncodingDecoder());

            return new DecodeContext(node, dtype, n, allSegments.toArray(new MemorySegment[0]),
                    registry, Arena.ofAuto());
        }

        private static ArrayNode toArrayNode(EncodeNode enc) {
            ArrayNode[] children = new ArrayNode[enc.children().length];
            for (int i = 0; i < children.length; i++) {
                children[i] = toArrayNode(enc.children()[i]);
            }
            return new ArrayNode(enc.encodingId(), enc.metadata(), children, enc.bufferIndices());
        }

        private static byte[] compress(byte[] input) {
            return Zstd.compress(input);
        }

        private static byte[] compressWithDict(byte[] input, byte[] dict) {
            try (ZstdCompressContext cctx = new ZstdCompressContext()) {
                return cctx.compress(input, ZstdDictionary.of(dict));
            }
        }

        private static byte[] metaNoDict(long[] uncompressedSizes, long[] nValues) {
            java.util.List<ProtoZstdFrameMetadata> frames = new java.util.ArrayList<>();
            for (int i = 0; i < uncompressedSizes.length; i++) {
                frames.add(new ProtoZstdFrameMetadata(uncompressedSizes[i], nValues[i]));
            }
            return new ProtoZstdMetadata(0, frames).encode();
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
        void decode_withDictionary_roundTrips() {
            // Given — a frame compressed against a shared dictionary; metadata carries the
            // dictionary size and the dict bytes live in buffer[0], frames in buffer[1..]
            // (mirrors the Rust reference layout).
            byte[] dict = "common-zstd-dictionary-content-for-test".getBytes(StandardCharsets.UTF_8);
            int[] values = {1, 2, 3};
            byte[] raw = toLeBytes(values);
            byte[] compressed = compressWithDict(raw, dict);
            byte[] meta = new ProtoZstdMetadata(dict.length,
                    java.util.List.of(new ProtoZstdFrameMetadata(raw.length, values.length))).encode();
            DecodeContext ctx = makeDictCtx(meta, DTypes.I32, values.length, dict, compressed);

            // When
            IntArray result = (IntArray) DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(values.length);
            for (int i = 0; i < values.length; i++) {
                assertThat(result.getInt(i)).as("index %d", i).isEqualTo(values[i]);
            }
        }

        @Test
        void decode_withDictionarySizeMismatch_throws() {
            // Given — metadata declares a dictionary_size that does not match the dict buffer's
            // actual byte size; the decoder must fail fast rather than digest a malformed
            // dictionary (the Rust reference enforces the same invariant).
            byte[] dict = "common-zstd-dictionary-content-for-test".getBytes(StandardCharsets.UTF_8);
            int[] values = {1, 2, 3};
            byte[] raw = toLeBytes(values);
            byte[] compressed = compressWithDict(raw, dict);
            byte[] meta = new ProtoZstdMetadata(dict.length + 1,
                    java.util.List.of(new ProtoZstdFrameMetadata(raw.length, values.length))).encode();
            DecodeContext ctx = makeDictCtx(meta, DTypes.I32, values.length, dict, compressed);

            // When / Then
            assertThatThrownBy(() -> DECODER.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("dictionary size metadata");
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
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_ZSTD, null, new ArrayNode[0], new int[0]);
            DecodeContext ctx = new DecodeContext(node, DTypes.I32, 0, new MemorySegment[0],
                    ReadRegistry.empty(), Arena.ofAuto());

            assertThatThrownBy(() -> DECODER.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing metadata");
        }

        @Test
        void decode_negativeFrameSize_throwsVortexException() {
            // Given — metadata declares a negative per-frame uncompressed_size. A raw
            // arena.allocate(negative) would throw IllegalArgumentException; the per-frame
            // IoBounds.toIntSize guard must convert it to a VortexException first.
            byte[] compressed = compress(toLeBytes(new int[]{0}));
            byte[] meta = metaNoDict(new long[]{-1}, new long[]{1});
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_ZSTD, MemorySegment.ofArray(meta),
                    new ArrayNode[0], new int[]{0});
            DecodeContext ctx = new DecodeContext(node, DTypes.I32, 1,
                    new MemorySegment[]{MemorySegment.ofArray(compressed)}, ReadRegistry.empty(), Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> DECODER.decode(ctx))
                    .isInstanceOf(VortexException.class);
        }

        @Test
        void decode_oversizedFrameSize_throwsVortexException() {
            // Given — metadata declares a per-frame uncompressed_size above the 2 GB int cap.
            // Caught by IoBounds.toIntSize before it can drive the (int) narrowing negative at the
            // asSlice site in decompressFrames.
            byte[] compressed = compress(toLeBytes(new int[]{0}));
            byte[] meta = metaNoDict(new long[]{(long) Integer.MAX_VALUE + 1}, new long[]{1});
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_ZSTD, MemorySegment.ofArray(meta),
                    new ArrayNode[0], new int[]{0});
            DecodeContext ctx = new DecodeContext(node, DTypes.I32, 1,
                    new MemorySegment[]{MemorySegment.ofArray(compressed)}, ReadRegistry.empty(), Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> DECODER.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("2 GB");
        }

        @Test
        void decode_varBinOversizedLengthPrefix_throwsVortexException() {
            // Given — a non-nullable VarBin payload whose single 4-byte length prefix claims
            // 1 000 000 bytes that the 4-byte decompressed buffer cannot hold. readVarBinLen must
            // reject the overrun as a VortexException instead of leaking an IndexOutOfBoundsException
            // when the cursor advances past the segment.
            byte[] raw = toLeBytes(new int[]{1_000_000});
            byte[] compressed = compress(raw);
            byte[] meta = metaNoDict(new long[]{raw.length}, new long[]{1});
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_ZSTD, MemorySegment.ofArray(meta),
                    new ArrayNode[0], new int[]{0});
            DecodeContext ctx = new DecodeContext(node, new DType.Utf8(false), 1,
                    new MemorySegment[]{MemorySegment.ofArray(compressed)}, ReadRegistry.empty(), Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> DECODER.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of bounds");
        }

        @Test
        void decode_scatteredVarBinOversizedLengthPrefix_throwsVortexException() {
            // Given — the nullable (scattered) VarBin path: one valid element whose length prefix
            // claims 1 000 000 bytes the buffer cannot hold. The same readVarBinLen guard must fire
            // on the scatter scan, not only the contiguous path.
            boolean[] validityBits = {true};
            byte[] raw = toLeBytes(new int[]{1_000_000});
            byte[] compressed = compress(raw);
            byte[] meta = metaNoDict(new long[]{raw.length}, new long[]{1});
            DecodeContext ctx = makeNullableCtx(meta, new DType.Utf8(true), 1, validityBits, compressed);

            // When / Then
            assertThatThrownBy(() -> DECODER.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of bounds");
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
            var metaSeg = result.rootNode().metadata();
            ProtoZstdMetadata meta = ProtoZstdMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            assertThat(meta.frames()).isNotEmpty();
        }
    }

    @Nested
    class MultiFrame {

        private static final ZstdEncodingEncoder FRAMED = new ZstdEncodingEncoder(4);

        @Test
        void encode_i32_splitsIntoFrames_andRoundTrips() throws Exception {
            // Given — 10 values, 4 per frame: 3 frames (4, 4, 2), one compressed buffer each.
            int[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

            // When
            EncodeResult result = FRAMED.encode(DTypes.I32, data, EncodeTestHelper.testCtx());

            // Then
            var metaSeg = result.rootNode().metadata();
            ProtoZstdMetadata meta = ProtoZstdMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            assertThat(meta.frames()).hasSize(3);
            assertThat(meta.frames().get(0).n_values()).isEqualTo(4);
            assertThat(meta.frames().get(2).n_values()).isEqualTo(2);
            assertThat(result.buffers()).hasSize(3);

            DecodeContext ctx = DecodeTestHelper.toDecodeContext(result, data.length, DTypes.I32, ReadRegistry.empty());
            IntArray decoded = (IntArray) DECODER.decode(ctx);
            for (int i = 0; i < data.length; i++) {
                assertThat(decoded.getInt(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_varBin_splitsOnValueBoundaries_andRoundTrips() throws Exception {
            // Given — 5 strings, 2 per frame: 3 frames (2, 2, 1). Entries vary in length, so the
            // frame byte spans must be found by walking the length prefixes, not a fixed stride.
            ZstdEncodingEncoder framedByTwo = new ZstdEncodingEncoder(2);
            String[] data = {"a", "bb", "ccc", "d", "eeeee"};

            // When
            EncodeResult result = framedByTwo.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());

            // Then
            var metaSeg = result.rootNode().metadata();
            ProtoZstdMetadata meta = ProtoZstdMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            assertThat(meta.frames()).hasSize(3);

            DecodeContext ctx = DecodeTestHelper.toDecodeContext(result, data.length, DTypes.UTF8, ReadRegistry.empty());
            VarBinArray decoded = (VarBinArray) DECODER.decode(ctx);
            for (int i = 0; i < data.length; i++) {
                assertThat(decoded.getString(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_nullablePrimitive_framesOverValidValues_andRoundTrips() throws Exception {
            // Given — 7 rows, 5 valid. Frames cover only the packed valid values (4 + 1), and the
            // validity child's buffers must trail the two frame buffers.
            int[] storage = {10, 0, 20, 30, 0, 40, 50};
            boolean[] validity = {true, false, true, true, false, true, true};
            DType i32Nullable = new DType.Primitive(PType.I32, true);
            NullableData data = new NullableData(storage, validity);

            // When
            EncodeResult result = FRAMED.encode(i32Nullable, data, EncodeTestHelper.testCtx());

            // Then
            var metaSeg = result.rootNode().metadata();
            ProtoZstdMetadata meta = ProtoZstdMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            assertThat(meta.frames()).hasSize(2);
            assertThat(meta.frames().get(0).n_values()).isEqualTo(4);
            assertThat(meta.frames().get(1).n_values()).isEqualTo(1);

            DecodeContext ctx = DecodeTestHelper.toDecodeContext(
                    result, validity.length, i32Nullable, TestRegistry.ofDecoders(new BoolEncodingDecoder()));
            MaskedArray decoded = (MaskedArray) DECODER.decode(ctx);
            assertThat(decoded.length()).isEqualTo(7);
            assertThat(decoded.isValid(1)).isFalse();
            assertThat(decoded.isValid(4)).isFalse();
            IntArray child = (IntArray) decoded.inner();
            assertThat(child.getInt(0)).isEqualTo(10);
            assertThat(child.getInt(2)).isEqualTo(20);
            assertThat(child.getInt(6)).isEqualTo(50);
        }
    }
}
