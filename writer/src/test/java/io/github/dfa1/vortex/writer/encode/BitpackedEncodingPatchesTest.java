package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.proto.ProtoBitPackedMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.reader.decode.BitpackedEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ConstantEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class BitpackedEncodingPatchesTest {

    private static final BitpackedEncodingEncoder ENCODER = new BitpackedEncodingEncoder();
    private static final BitpackedEncodingDecoder DECODER = new BitpackedEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder(),
            new ConstantEncodingDecoder());

    @Nested
    class Decode {

        @Test
        void decode_appliesPatches_overridingBitPackedValues() {
            // Given
            int[] base = {10, 20, 30, 40, 50};
            EncodeResult packed = ENCODER.encode(DTypes.I32, base, EncodeTestHelper.testCtx());

            MemorySegment packedSeg = packed.buffers().getFirst();
            byte[] packedBytes = packedSeg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);

            ProtoPatchesMetadata patches = new ProtoPatchesMetadata(2L, 0L,
                    io.github.dfa1.vortex.core.proto.ProtoPType.U32, null, null, null);
            byte[] metaBytes = new ProtoBitPackedMetadata(6, 0, patches).encode();

            byte[] idxBuf = new byte[2 * 4];
            ByteBuffer.wrap(idxBuf).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(3);
            byte[] valBuf = new byte[2 * 4];
            ByteBuffer.wrap(valBuf).order(ByteOrder.LITTLE_ENDIAN).putInt(777).putInt(999);

            ArrayNode idxNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{1});
            ArrayNode valNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{2});
            ArrayNode bpNode = new ArrayNode(EncodingId.FASTLANES_BITPACKED,
                    MemorySegment.ofArray(metaBytes),
                    new ArrayNode[]{idxNode, valNode},
                    new int[]{0});

            MemorySegment[] segments = {
                    MemorySegment.ofArray(packedBytes),
                    MemorySegment.ofArray(idxBuf),
                    MemorySegment.ofArray(valBuf)
            };

            DecodeContext ctx = new DecodeContext(
                    bpNode, DTypes.I32, base.length, segments, REGISTRY, Arena.global());

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(base.length);
            assertThat(result.materialize(Arena.ofAuto()).get(VortexFormat.LE_INT, 0L)).isEqualTo(10);
            assertThat(result.materialize(Arena.ofAuto()).get(VortexFormat.LE_INT, 4L)).isEqualTo(777);
            assertThat(result.materialize(Arena.ofAuto()).get(VortexFormat.LE_INT, 8L)).isEqualTo(30);
            assertThat(result.materialize(Arena.ofAuto()).get(VortexFormat.LE_INT, 12L)).isEqualTo(999);
            assertThat(result.materialize(Arena.ofAuto()).get(VortexFormat.LE_INT, 16L)).isEqualTo(50);
        }
    }

    @Nested
    class Encode {

        /// Rust's `gather_patches` + `compress_patches`: children [indices, values, chunk_offsets],
        /// the indices and chunk offsets narrowed to the smallest unsigned type holding the largest.
        @Test
        void distinctOutliers_patchesCarryNarrowedIndicesAndChunkOffsets() throws IOException {
            // Given — 3000 small values over 3 chunks of 1024, two wide ones: positions up to 2500
            // need u16, while the chunk offsets [0, 1, 1] fit in u8
            int[] values = new int[3000];
            values[42] = 70_000;
            values[2500] = 80_000;

            // When
            EncodeResult result = ENCODER.encode(DTypes.I32, values, EncodeTestHelper.testCtx());

            // Then
            ProtoPatchesMetadata patches = metadata(result).patches();
            assertThat(patches.indices_ptype()).isEqualTo(ProtoPType.U16);
            assertThat(patches.chunk_offsets_len()).isEqualTo(3L);
            assertThat(patches.chunk_offsets_ptype()).isEqualTo(ProtoPType.U8);
            assertThat(childIds(result)).containsExactly(EncodingId.VORTEX_PRIMITIVE, EncodingId.VORTEX_PRIMITIVE,
                    EncodingId.VORTEX_PRIMITIVE);
            assertThat(decodeInts(result, values.length)).isEqualTo(values);
        }

        /// `compress_patches` keeps patch values that are all equal as a constant.
        @Test
        void equalOutliers_patchValuesAreConstant() {
            // Given
            int[] values = new int[2000];
            values[7] = 1_000_000;
            values[1500] = 1_000_000;

            // When
            EncodeResult result = ENCODER.encode(DTypes.I32, values, EncodeTestHelper.testCtx());

            // Then
            assertThat(childIds(result)).containsExactly(EncodingId.VORTEX_PRIMITIVE, EncodingId.VORTEX_CONSTANT,
                    EncodingId.VORTEX_PRIMITIVE);
            assertThat(decodeInts(result, values.length)).isEqualTo(values);
        }

        /// Outliers over every width of patch value, signed and unsigned: one patch and equal
        /// ones (a constant), and distinct ones. U8 200 and the U64 above 2^63 catch a value
        /// sign-extended where it must be zero-extended, in the primitive and the constant alike.
        static Stream<Arguments> outliers() {
            return Stream.of(
                    Arguments.of(PType.U8, new long[]{200}),
                    Arguments.of(PType.U8, new long[]{200, 200}),
                    Arguments.of(PType.I8, new long[]{100, 120}),
                    Arguments.of(PType.U16, new long[]{40_000, 50_000}),
                    Arguments.of(PType.I16, new long[]{30_000, 30_000}),
                    Arguments.of(PType.I32, new long[]{70_000}),
                    Arguments.of(PType.U32, new long[]{3_000_000_000L, 4_000_000_000L}),
                    Arguments.of(PType.I64, new long[]{1L << 40, 1L << 40}),
                    Arguments.of(PType.U64, new long[]{0x8000_0000_0000_0001L, Long.MAX_VALUE}),
                    Arguments.of(PType.U64, new long[]{-1L, -1L}));
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("outliers")
        void outliers_roundTripThroughTheirPatches(PType ptype, long[] outliers) {
            // Given — small values over three 1024-value chunks, the outliers spread across them
            int n = 3000;
            long[] longs = new long[n];
            for (int i = 0; i < n; i++) {
                longs[i] = i % 4;
            }
            for (int o = 0; o < outliers.length; o++) {
                longs[100 + o * 1200] = outliers[o];
            }
            DType dtype = new DType.Primitive(ptype, false);
            Object values = PrimitiveArrays.fromLongsArray(longs, ptype, EncodingId.FASTLANES_BITPACKED);

            // When
            EncodeResult result = ENCODER.encode(dtype, values, EncodeTestHelper.testCtx());

            // Then
            // a single patch is constant too, as Rust's is_constant holds for one value
            EncodingId valuesId = EncodingId.VORTEX_PRIMITIVE;
            if (Arrays.stream(outliers).distinct().count() == 1) {
                valuesId = EncodingId.VORTEX_CONSTANT;
            }
            assertThat(childIds(result)).containsExactly(EncodingId.VORTEX_PRIMITIVE, valuesId,
                    EncodingId.VORTEX_PRIMITIVE);
            assertThat(decodeLongs(result, dtype, n)).isEqualTo(longs);
        }
    }

    /// Rust's `gather_patches` patches valid rows only: a null row's value never becomes a patch,
    /// however wide the placeholder it holds.
    @Nested
    class NullRows {

        @Test
        void wideValueAtANullRow_isNotPatched() throws IOException {
            // Given
            int[] values = new int[2000];
            boolean[] validity = new boolean[values.length];
            Arrays.fill(validity, true);
            values[5] = 1_000_000;
            validity[5] = false;

            // When
            EncodeResult result = ENCODER.encode(DTypes.I32_N, new NullableData(values, validity),
                    EncodeTestHelper.testCtx());

            // Then only the validity child, no patches
            assertThat(metadata(result).patches()).isNull();
            assertThat(childIds(result)).containsExactly(EncodingId.VORTEX_BOOL);
        }

        /// FoR shifts every slot by the minimum: a null slot's zero placeholder in a column of
        /// negatives would become a wide residual, unless the null slot is set to the reference.
        @Test
        void forOverNegatives_nullPlaceholderIsNotPatched() throws IOException {
            // Given
            int[] values = new int[2000];
            boolean[] validity = new boolean[values.length];
            for (int i = 0; i < values.length; i++) {
                values[i] = -1000 + i % 4;
                validity[i] = i % 7 != 3;
            }
            for (int i = 0; i < values.length; i++) {
                if (!validity[i]) {
                    values[i] = 0;
                }
            }

            // When
            CascadeStep step = new FrameOfReferenceEncodingEncoder().encodeCascade(DTypes.I32_N,
                    new NullableData(values, validity), EncodeTestHelper.testCtx());

            // Then
            EncodeNode packed = step.partialRoot().children()[0];
            assertThat(packed.encodingId()).isEqualTo(EncodingId.FASTLANES_BITPACKED);
            MemorySegment meta = packed.metadata();
            assertThat(ProtoBitPackedMetadata.decode(meta, 0, meta.byteSize()).patches()).isNull();
        }
    }

    private static ProtoBitPackedMetadata metadata(EncodeResult result) throws IOException {
        MemorySegment meta = result.rootNode().metadata();
        return ProtoBitPackedMetadata.decode(meta, 0, meta.byteSize());
    }

    private static List<EncodingId> childIds(EncodeResult result) {
        return Arrays.stream(result.rootNode().children()).map(EncodeNode::encodingId).toList();
    }

    private static int[] decodeInts(EncodeResult result, int n) {
        MemorySegment[] segments = result.buffers().toArray(new MemorySegment[0]);
        DecodeContext ctx = new DecodeContext(toArrayNode(result.rootNode()), DTypes.I32, n, segments, REGISTRY,
                Arena.ofAuto());
        return DECODER.decode(ctx).materialize(Arena.ofAuto()).toArray(VortexFormat.LE_INT);
    }

    private static long[] decodeLongs(EncodeResult result, DType dtype, int n) {
        MemorySegment[] segments = result.buffers().toArray(new MemorySegment[0]);
        DecodeContext ctx = new DecodeContext(toArrayNode(result.rootNode()), dtype, n, segments, REGISTRY,
                Arena.ofAuto());
        MemorySegment decoded = DECODER.decode(ctx).materialize(Arena.ofAuto());
        PType ptype = ((DType.Primitive) dtype).ptype();
        return PrimitiveArrays.toLongs(decoded, 0, n, ptype, EncodingId.FASTLANES_BITPACKED);
    }

    private static ArrayNode toArrayNode(EncodeNode node) {
        ArrayNode[] children = Arrays.stream(node.children())
                .map(BitpackedEncodingPatchesTest::toArrayNode)
                .toArray(ArrayNode[]::new);
        return new ArrayNode(node.encodingId(), node.metadata(), children, node.bufferIndices());
    }
}
