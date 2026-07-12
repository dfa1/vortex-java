package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.proto.ProtoFSSTMetadata;
import io.github.dfa1.vortex.reader.decode.FsstEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FsstEncodingEncoderTest {

    private static final FsstEncodingEncoder ENCODER = new FsstEncodingEncoder();
    private static final FsstEncodingDecoder DECODER = new FsstEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    @Nested
    class Encode {

        private static ArrayNode toArrayNode(EncodeNode node) {
            ArrayNode[] children = new ArrayNode[node.children().length];
            for (int i = 0; i < children.length; i++) {
                children[i] = toArrayNode(node.children()[i]);
            }
            return new ArrayNode(node.encodingId(), node.metadata(), children, node.bufferIndices());
        }

        static Stream<Arguments> stringArrays() {
            return Stream.of(
                    Arguments.of("empty-array", new String[0]),
                    Arguments.of("single-empty-string", new String[]{""}),
                    Arguments.of("short-strings", new String[]{"hi", "ok", "no"}),
                    Arguments.of("repeated-bigram", new String[]{"aaaa", "aaaa", "aaaa"}),
                    Arguments.of("long-strings", new String[]{"the quick brown fox jumps over the lazy dog"}),
                    Arguments.of("mixed-lengths", new String[]{"a", "hello", "this is a longer string than twelve"}),
                    Arguments.of("repeated-short", repeat("a", 1)),
                    Arguments.of("repeated-short", repeat("ab", 50)),
                    Arguments.of("all-empty", new String[]{"", "", "", ""}),
                    Arguments.of("unicode", new String[]{"héllo", "wörld", "こんにちは"})
            );
        }

        private static String[] repeat(String s, int n) {
            String[] arr = new String[n];
            java.util.Arrays.fill(arr, s);
            return arr;
        }

        @Test
        void accepts_utf8_true() {
            // Given
            // When
            boolean result = ENCODER.accepts(DTypes.UTF8);

            // Then
            assertThat(result).isTrue();
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
        void accepts_primitive_false() {
            // Given
            // When
            boolean result = ENCODER.accepts(DTypes.I32);

            // Then
            assertThat(result).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("stringArrays")
        void encode_thenDecode_roundtripsAllStrings(String name, String[] values) {
            // Given
            Arena arena = Arena.ofAuto();

            // When
            EncodeResult result = ENCODER.encode(DTypes.UTF8, values, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            ArrayNode node = toArrayNode(result.rootNode());
            DecodeContext ctx = new DecodeContext(node, DTypes.UTF8, values.length, bufs, REGISTRY, arena);
            var decoded = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(decoded.length()).isEqualTo(values.length);
            for (int i = 0; i < values.length; i++) {
                assertThat(decoded.getString(i)).as("index %d", i).isEqualTo(values[i]);
            }
        }
    }

    @Nested
    class Decode {

        private static DecodeContext buildCtx(
                long[] symbols, byte[] symLens, byte[] compressed,
                int[] uncompLens, int[] codesOffsets, long n
        ) {
            Arena arena = Arena.ofAuto();

            MemorySegment symBuf = arena.allocate(Math.max(symbols.length * 8L, 1), 8);
            for (int i = 0; i < symbols.length; i++) {
                symBuf.setAtIndex(VortexFormat.LE_LONG, i, symbols[i]);
            }

            MemorySegment symLenBuf = arena.allocate(Math.max(symLens.length, 1));
            for (int i = 0; i < symLens.length; i++) {
                symLenBuf.set(ValueLayout.JAVA_BYTE, i, symLens[i]);
            }

            MemorySegment compBuf = arena.allocate(Math.max(compressed.length, 1));
            for (int i = 0; i < compressed.length; i++) {
                compBuf.set(ValueLayout.JAVA_BYTE, i, compressed[i]);
            }

            MemorySegment uncompLenBuf = arena.allocate((long) uncompLens.length * Integer.BYTES, Integer.BYTES);
            for (int i = 0; i < uncompLens.length; i++) {
                uncompLenBuf.setAtIndex(VortexFormat.LE_INT, i, uncompLens[i]);
            }

            MemorySegment codesOffBuf = arena.allocate((long) codesOffsets.length * Integer.BYTES, Integer.BYTES);
            for (int i = 0; i < codesOffsets.length; i++) {
                codesOffBuf.setAtIndex(VortexFormat.LE_INT, i, codesOffsets[i]);
            }

            MemorySegment[] segs = {symBuf, symLenBuf, compBuf, uncompLenBuf, codesOffBuf};

            byte[] metaBytes = new ProtoFSSTMetadata(io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I32.ordinal()), io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I32.ordinal())).encode();

            ArrayNode uncompLensNode = new ArrayNode(
                    EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{3});
            ArrayNode codesOffNode = new ArrayNode(
                    EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{4});
            ArrayNode root = new ArrayNode(
                    EncodingId.VORTEX_FSST, MemorySegment.ofArray(metaBytes),
                    new ArrayNode[]{uncompLensNode, codesOffNode}, new int[]{0, 1, 2});

            return new DecodeContext(root, DTypes.UTF8, n, segs, REGISTRY, arena);
        }

        @Test
        void decode_singleByteSymbol_decompressesCorrectly() {
            // Given
            DecodeContext ctx = buildCtx(
                    new long[]{0x41L}, new byte[]{1}, new byte[]{0x00, 0x00},
                    new int[]{2}, new int[]{0, 2}, 1);

            // When
            var result = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(result.getBytes(0)).isEqualTo("AA".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void decode_escapeByte_decompressesCorrectly() {
            // Given
            DecodeContext ctx = buildCtx(
                    new long[0], new byte[0], new byte[]{(byte) 0xFF, 0x58, (byte) 0xFF, 0x59},
                    new int[]{2}, new int[]{0, 4}, 1);

            // When
            var result = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(result.getBytes(0)).isEqualTo("XY".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void decode_multiByteSymbol_decompressesCorrectly() {
            // Given
            DecodeContext ctx = buildCtx(
                    new long[]{0x6261L}, new byte[]{2}, new byte[]{0x00},
                    new int[]{2}, new int[]{0, 1}, 1);

            // When
            var result = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(result.getBytes(0)).isEqualTo("ab".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void decode_multipleStrings_decompressesAll() {
            // Given
            DecodeContext ctx = buildCtx(
                    new long[]{0x48L}, new byte[]{1},
                    new byte[]{0x00, 0x00, 0x00, (byte) 0xFF, 0x21},
                    new int[]{1, 2, 1}, new int[]{0, 1, 3, 5}, 3);

            // When
            var result = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(result.getBytes(0)).isEqualTo("H".getBytes(StandardCharsets.UTF_8));
            assertThat(result.getBytes(1)).isEqualTo("HH".getBytes(StandardCharsets.UTF_8));
            assertThat(result.getBytes(2)).isEqualTo("!".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void decode_missingMetadata_throwsVortexException() {
            // Given
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_FSST, null, new ArrayNode[0], new int[0]);
            DecodeContext ctx = new DecodeContext(node, DTypes.UTF8, 0, new MemorySegment[0], REGISTRY, Arena.ofAuto());

            // When
            // Then
            assertThatThrownBy(() -> DECODER.decode(ctx)).isInstanceOf(VortexException.class);
        }
    }

    @Nested
    class Metadata {

        @Test
        void encode_metadata_ptypes_areI32() throws Exception {
            // Given
            String[] data = {"hello", "world", "hello", "fsst"};

            // When
            EncodeResult result = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            var metaSeg = result.rootNode().metadata();
            ProtoFSSTMetadata meta = ProtoFSSTMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            // Then
            assertThat(meta.uncompressed_lengths_ptype().value()).isEqualTo(6);
            assertThat(meta.codes_offsets_ptype().value()).isEqualTo(6);
        }
    }
}
