package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.reader.decode.PatchedEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchedEncodingEncoderTest {

    private static final PatchedEncodingEncoder SUT = new PatchedEncodingEncoder();
    private static final PatchedEncodingDecoder DECODER = new PatchedEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    private static Array decode(EncodeResult result, DType dtype, int n) {
        EncodeNode root = result.rootNode();
        List<MemorySegment> bufs = result.buffers();
        MemorySegment[] segments = bufs.toArray(new MemorySegment[0]);

        EncodeNode[] enc = root.children();
        ArrayNode innerNode = toArrayNode(enc[0]);
        ArrayNode laneNode = toArrayNode(enc[1]);
        ArrayNode idxNode = toArrayNode(enc[2]);
        ArrayNode valNode = toArrayNode(enc[3]);

        ArrayNode patchedNode = new ArrayNode(EncodingId.VORTEX_PATCHED, root.metadata(),
                new ArrayNode[]{innerNode, laneNode, idxNode, valNode}, new int[]{});

        DecodeContext ctx = new DecodeContext(patchedNode, dtype, n, segments, REGISTRY, Arena.ofAuto());
        return DECODER.decode(ctx);
    }

    private static ArrayNode toArrayNode(EncodeNode enc) {
        return new ArrayNode(enc.encodingId(), enc.metadata(), new ArrayNode[0], enc.bufferIndices());
    }

    @Nested
    class Accepts {
        @Test
        void accepts_integers() {
            assertThat(SUT.accepts(DTypes.I32)).isTrue();
            assertThat(SUT.accepts(DTypes.I64)).isTrue();
            assertThat(SUT.accepts(DTypes.U32)).isTrue();
            assertThat(SUT.accepts(DTypes.U64)).isTrue();
        }

        @Test
        void rejects_floats() {
            assertThat(SUT.accepts(DTypes.F32)).isFalse();
            assertThat(SUT.accepts(DTypes.F64)).isFalse();
        }

        @Test
        void rejects_nonPrimitive() {
            assertThat(SUT.accepts(DTypes.UTF8)).isFalse();
        }
    }

    @Nested
    class Encode {

        @Test
        void encode_roundTrip_i64_singleOutlier() {
            // Given
            long[] data = new long[100];
            for (int i = 0; i < 99; i++) {
                data[i] = i & 3; // 2-bit values
            }
            data[50] = 1_000_000_000L; // outlier requiring 30 bits

            // When
            EncodeResult result = SUT.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            Array decoded = decode(result, DTypes.I64, data.length);

            // Then: all values round-trip correctly
            LongArray la = (LongArray) decoded;
            for (int i = 0; i < data.length; i++) {
                assertThat(la.getLong(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_roundTrip_i32_multipleChunks() {
            // Given: 2048 values (2 chunks) with one outlier per chunk
            int n = 2048;
            int[] data = new int[n];
            for (int i = 0; i < n; i++) {
                data[i] = i & 7; // 3-bit values
            }
            data[10] = 0x7FFF_FFFF;    // outlier in chunk 0
            data[1030] = 0x3FFF_FFFF;  // outlier in chunk 1

            // When
            EncodeResult result = SUT.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
            Array decoded = decode(result, DTypes.I32, n);

            // Then
            IntArray ia = (IntArray) decoded;
            for (int i = 0; i < n; i++) {
                assertThat(ia.getInt(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
        void encode_roundTrip_allIntegerPTypes(PType ptype) {
            // Given: 100 small values + 1 outlier — exercises toLongs/PTypeIO.set for every width
            int n = 100;
            DType dtype = dtypeOf(ptype);
            Object data = buildData(ptype, n);

            // When
            EncodeResult result = SUT.encode(dtype, data, EncodeTestHelper.testCtx());
            Array decoded = decode(result, dtype, n);

            // Then
            for (int i = 0; i < n; i++) {
                assertThat(readLong(decoded, i, ptype)).as("ptype %s index %d", ptype, i)
                        .isEqualTo(expectedAt(ptype, i, n));
            }
        }

        @Test
        void encode_throws_onUnsupportedPType() {
            // Given: a primitive float dtype slips past the instanceof check but has no integer mapping
            double[] data = {1.0, 2.0, 3.0};
            EncodeContext ctx = EncodeTestHelper.testCtx();

            // When / Then
            assertThatThrownBy(() -> SUT.encode(DTypes.F64, data, ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unsupported ptype");
        }

        @Test
        void encode_throws_onNonPrimitiveDtype() {
            // Given
            long[] data = {1L, 2L, 3L};
            EncodeContext ctx = EncodeTestHelper.testCtx();

            // When / Then: non-primitive dtype is a programmer error on the encode path
            assertThatThrownBy(() -> SUT.encode(DTypes.UTF8, data, ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("expected primitive");
        }

        @ParameterizedTest
        @ValueSource(ints = {10, 100, 1023, 1024, 1025, 2048})
        void encode_roundTrip_i64_variousLengths(int n) {
            // Given: mostly small values, one outlier
            long[] data = new long[n];
            for (int i = 0; i < n; i++) {
                data[i] = i & 0xF; // 4-bit values
            }
            if (n > 1) {
                data[n / 2] = Long.MAX_VALUE; // single outlier
            }

            // When
            EncodeResult result = SUT.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            Array decoded = decode(result, DTypes.I64, n);

            // Then
            LongArray la = (LongArray) decoded;
            for (int i = 0; i < n; i++) {
                assertThat(la.getLong(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }
    }

    @Nested
    class EncodeCascade {

        @Test
        void encodeCascade_notApplicable_whenAllValuesUniform() {
            // Given: uniform values (no outliers possible — best bit width == type width)
            int[] data = new int[100];
            for (int i = 0; i < data.length; i++) {
                data[i] = Integer.MAX_VALUE; // all need full 31 bits unsigned → no gain
            }

            // When
            CascadeStep step = SUT.encodeCascade(DTypes.I32, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(step.applicable()).isFalse();
        }

        @Test
        void encodeCascade_notApplicable_whenTooManyOutliers() {
            // Given: 50% outliers (> 5% threshold)
            long[] data = new long[100];
            for (int i = 0; i < data.length; i++) {
                // alternate small and large values
                data[i] = (i % 2 == 0) ? (i & 3) : Long.MAX_VALUE;
            }

            // When
            CascadeStep step = SUT.encodeCascade(DTypes.I64, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(step.applicable()).isFalse();
        }

        @Test
        void encodeCascade_applicable_withFewOutliers() {
            // Given: 99 small values, 1 outlier (1% < 5% threshold)
            long[] data = new long[100];
            for (int i = 0; i < 99; i++) {
                data[i] = i & 3;
            }
            data[50] = Long.MAX_VALUE;

            // When
            CascadeStep step = SUT.encodeCascade(DTypes.I64, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(step.applicable()).isTrue();
            assertThat(step.openChildren()).hasSize(4);
            // child 0: inner (same dtype), child 1: laneOffsets (U32), child 2: patchIdx (U16), child 3: patchVal (same dtype)
            assertThat(step.openChildren().get(0).childDtype()).isEqualTo(DTypes.I64);
            assertThat(step.openChildren().get(1).childDtype())
                    .isEqualTo(DType.U32);
            assertThat(step.openChildren().get(2).childDtype())
                    .isEqualTo(DType.U16);
            assertThat(step.openChildren().get(3).childDtype()).isEqualTo(DTypes.I64);
        }

        @Test
        void encodeCascade_notApplicable_whenEmpty() {
            // Given
            long[] data = {};

            // When
            CascadeStep step = SUT.encodeCascade(DTypes.I64, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(step.applicable()).isFalse();
        }

        @Test
        void encodeCascade_notApplicable_whenNonPrimitiveDtype() {
            // Given
            long[] data = {1L, 2L, 3L};

            // When
            CascadeStep step = SUT.encodeCascade(DTypes.UTF8, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(step.applicable()).isFalse();
        }

        @Test
        void encodeCascade_notApplicable_whenNoBitWidthGain() {
            // Given: every value needs the full 64 bits, so no target width beats the raw cost
            long[] data = new long[100];
            for (int i = 0; i < data.length; i++) {
                data[i] = Long.MIN_VALUE | i; // bit 63 set → width 64 for all
            }

            // When
            CascadeStep step = SUT.encodeCascade(DTypes.I64, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(step.applicable()).isFalse();
        }

        @ParameterizedTest
        @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32"})
        void encodeCascade_applicable_smallIntegerPTypes(PType ptype) {
            // Given: 100 small values + 1 outlier — exercises fromLongs for every narrow width
            int n = 100;
            DType dtype = dtypeOf(ptype);
            Object data = buildData(ptype, n);

            // When
            CascadeStep step = SUT.encodeCascade(dtype, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(step.applicable()).isTrue();
            assertThat(step.openChildren()).hasSize(4);
            assertThat(step.openChildren().get(0).childDtype()).isEqualTo(dtype);
            assertThat(step.openChildren().get(3).childDtype()).isEqualTo(dtype);
        }
    }

    private static DType dtypeOf(PType ptype) {
        return new DType.Primitive(ptype, false);
    }

    /// Builds an array of the given primitive type: small values everywhere except a
    /// single high outlier at the midpoint, which forces a patch.
    private static Object buildData(PType ptype, int n) {
        return switch (ptype) {
            case I8, U8 -> {
                byte[] a = new byte[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (byte) (i & 3);
                }
                a[n / 2] = (byte) (ptype == PType.U8 ? 0xC8 : 0x7F);
                yield a;
            }
            case I16, U16 -> {
                short[] a = new short[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (short) (i & 3);
                }
                a[n / 2] = (short) (ptype == PType.U16 ? 0xFFFF : 0x7FFF);
                yield a;
            }
            case I32, U32 -> {
                int[] a = new int[n];
                for (int i = 0; i < n; i++) {
                    a[i] = i & 3;
                }
                a[n / 2] = ptype == PType.U32 ? 0xFFFF_FFFF : 0x7FFF_FFFF;
                yield a;
            }
            case I64, U64 -> {
                long[] a = new long[n];
                for (int i = 0; i < n; i++) {
                    a[i] = i & 3;
                }
                a[n / 2] = ptype == PType.U64 ? -1L : Long.MAX_VALUE;
                yield a;
            }
            default -> throw new IllegalArgumentException("unsupported: " + ptype);
        };
    }

    /// Expected raw element value for [#buildData], used to compare against the decoded array.
    private static long expectedAt(PType ptype, int i, int n) {
        if (i != n / 2) {
            return i & 3;
        }
        return switch (ptype) {
            case I8 -> 0x7F;
            case U8 -> (byte) 0xC8;
            case I16 -> 0x7FFF;
            case U16 -> (short) 0xFFFF;
            case I32 -> 0x7FFF_FFFF;
            case U32 -> 0xFFFF_FFFF;
            case I64 -> Long.MAX_VALUE;
            case U64 -> -1L;
            default -> throw new IllegalArgumentException("unsupported: " + ptype);
        };
    }

    private static long readLong(Array array, int i, PType ptype) {
        return switch (ptype) {
            case I8, U8 -> ((ByteArray) array).getByte(i);
            case I16, U16 -> ((ShortArray) array).getShort(i);
            case I32, U32 -> ((IntArray) array).getInt(i);
            case I64, U64 -> ((LongArray) array).getLong(i);
            default -> throw new IllegalArgumentException("unsupported: " + ptype);
        };
    }
}
