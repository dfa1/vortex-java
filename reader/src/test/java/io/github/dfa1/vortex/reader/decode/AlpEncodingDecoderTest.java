package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoALPMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AlpEncodingDecoderTest {

    private static final AlpEncodingDecoder SUT = new AlpEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            SUT, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());

    private static final DType F64 = DType.F64;
    private static final DType F32 = DType.F32;

    private static MemorySegment leLongs(long... vs) {
        byte[] b = new byte[vs.length * 8];
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        for (long v : vs) {
            bb.putLong(v);
        }
        return MemorySegment.ofArray(b);
    }

    private static MemorySegment leInts(int... vs) {
        byte[] b = new byte[vs.length * 4];
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : vs) {
            bb.putInt(v);
        }
        return MemorySegment.ofArray(b);
    }

    private static MemorySegment leDoubles(double... vs) {
        byte[] b = new byte[vs.length * 8];
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : vs) {
            bb.putDouble(v);
        }
        return MemorySegment.ofArray(b);
    }

    @Test
    void decode_nonPrimitiveDtype_throws() {
        // Given a Utf8 dtype on an ALP node
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALP, MemorySegment.ofArray(new ProtoALPMetadata(0, 0, null).encode()),
                new ArrayNode[0], new int[0]);
        DecodeContext ctx = new DecodeContext(node, DType.UTF8, 1,
                new MemorySegment[0], REGISTRY, Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> SUT.decode(ctx)).hasMessageContaining("expected primitive dtype");
    }

    @Test
    void decode_missingMetadata_defaultsToZeroExponents() {
        // Given no metadata — decoder falls back to exp_e=0, exp_f=0 (scale 1.0)
        ArrayNode enc = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALP, null, new ArrayNode[]{enc}, new int[0]);
        DecodeContext ctx = new DecodeContext(node, F64, 2, new MemorySegment[]{leLongs(5L, 7L)}, REGISTRY, Arena.ofAuto());

        // When
        DoubleArray result = (DoubleArray) SUT.decode(ctx);

        // Then
        assertThat(result.getDouble(0)).isCloseTo(5.0, within(1e-9));
        assertThat(result.getDouble(1)).isCloseTo(7.0, within(1e-9));
    }

    @Test
    void decode_f64_broadcastNoPatches_returnsConstant() {
        // Given a single encoded value but 4 logical rows (capacity < n) and no patches:
        // the decoder broadcasts it into a constant array
        ArrayNode enc = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        byte[] meta = new ProtoALPMetadata(2, 0, null).encode(); // exp_e=2 -> *0.01
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALP, MemorySegment.ofArray(meta), new ArrayNode[]{enc}, new int[0]);
        DecodeContext ctx = new DecodeContext(node, F64, 4, new MemorySegment[]{leLongs(123L)}, REGISTRY, Arena.ofAuto());

        // When
        DoubleArray result = (DoubleArray) SUT.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(4);
        for (int i = 0; i < 4; i++) {
            assertThat(result.getDouble(i)).as("index %d", i).isCloseTo(1.23, within(1e-9));
        }
    }

    @Test
    void decode_f32_broadcastNoPatches_returnsConstant() {
        // Given single value, 3 rows, no patches
        ArrayNode enc = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        byte[] meta = new ProtoALPMetadata(1, 0, null).encode(); // exp_e=1 -> *0.1
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALP, MemorySegment.ofArray(meta), new ArrayNode[]{enc}, new int[0]);
        DecodeContext ctx = new DecodeContext(node, F32, 3, new MemorySegment[]{leInts(25)}, REGISTRY, Arena.ofAuto());

        // When
        FloatArray result = (FloatArray) SUT.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(result.getFloat(i)).as("index %d", i).isCloseTo(2.5f, within(1e-6f));
        }
    }

    @Test
    void decode_f64_patches_withU8Indices() {
        // Given patches whose index child uses U8 storage — exercises the U8 arm of
        // readUnsigned (the encoder always emits U32 indices)
        ProtoPatchesMetadata pm = new ProtoPatchesMetadata(1L, 0L, ProtoPType.U8, null, null, null);
        byte[] meta = new ProtoALPMetadata(2, 0, pm).encode(); // *0.01

        ArrayNode enc = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode idx = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
        ArrayNode val = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{2});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALP, MemorySegment.ofArray(meta),
                new ArrayNode[]{enc, idx, val}, new int[0]);

        MemorySegment idxSeg = MemorySegment.ofArray(new byte[]{1}); // patch row 1
        MemorySegment[] segs = {leLongs(100L, 0L, 300L), idxSeg, leDoubles(9.0)};
        DecodeContext ctx = new DecodeContext(node, F64, 3, segs, REGISTRY, Arena.ofAuto());

        // When
        DoubleArray result = (DoubleArray) SUT.decode(ctx);

        // Then
        assertThat(result.getDouble(0)).isCloseTo(1.0, within(1e-9));
        assertThat(result.getDouble(1)).isCloseTo(9.0, within(1e-9)); // patched
        assertThat(result.getDouble(2)).isCloseTo(3.0, within(1e-9));
    }

    @Test
    void decode_patches_nonUnsignedIndexPtype_throws() {
        // Given a signed (I32) patch-index ptype — readUnsigned rejects it
        ProtoPatchesMetadata pm = new ProtoPatchesMetadata(1L, 0L, ProtoPType.I32, null, null, null);
        byte[] meta = new ProtoALPMetadata(2, 0, pm).encode();

        ArrayNode enc = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode idx = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
        ArrayNode val = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{2});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALP, MemorySegment.ofArray(meta),
                new ArrayNode[]{enc, idx, val}, new int[0]);

        MemorySegment[] segs = {leLongs(100L, 0L), leInts(1), leDoubles(9.0)};
        DecodeContext ctx = new DecodeContext(node, F64, 2, segs, REGISTRY, Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> SUT.decode(ctx)).hasMessageContaining("non-unsigned patch index ptype");
    }

    /// Adversarial metadata from an untrusted file (TODO.md §Security, per-encoding
    /// adversarial tests). `exp_e`/`exp_f` were read verbatim and used to index the
    /// power-of-ten tables, so an out-of-range exponent escaped as an
    /// `ArrayIndexOutOfBoundsException`; patch indices were scatter-written with no range
    /// check at all (unlike [BitpackedEncodingDecoder]), so a patch pointing outside the
    /// row range escaped as a raw `IndexOutOfBoundsException` from the copy, and an empty
    /// patch child divided by zero in the broadcast branch.
    ///
    /// The float-vs-double discriminator is not part of ALP metadata here — it is derived
    /// from the column's dtype — and an unsupported ptype already fails as a
    /// [VortexException] ("unsupported dtype"), so there is
    /// no out-of-enum-range byte to fuzz.
    @Nested
    class AdversarialMetadata {

        @Test
        void negativeExponent_f64_throws() {
            // Given exp_e = -1, which would index the inverse power-of-ten table below zero
            DecodeContext ctx = ctx(F64, new ProtoALPMetadata(-1, 0, null), leLongs(1L, 2L), 2);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("exponents (e=-1, f=0) out of range [0,24)");
        }

        @Test
        void exponentPastTable_f64_throws() {
            // Given exp_e = 24, one past the last entry of the 24-wide f64 table
            DecodeContext ctx = ctx(F64, new ProtoALPMetadata(24, 0, null), leLongs(1L, 2L), 2);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range [0,24)");
        }

        @Test
        void exponentPastTable_f32_throws() {
            // Given exp_f = 11 on an f32 column: valid for f64 but one past the narrower
            // 11-wide f32 table, so the width-specific bound is what matters
            DecodeContext ctx = ctx(F32, new ProtoALPMetadata(0, 11, null), leInts(1, 2), 2);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range [0,11)");
        }

        @Test
        void patchIndexPastRowCount_throws() {
            // Given a single patch pointing at row 9 of a 2-row array
            ProtoPatchesMetadata pm = new ProtoPatchesMetadata(1L, 0L,
                    ProtoPType.U8, null, null, null);
            DecodeContext ctx = patchedCtx(pm, leLongs(100L, 200L),
                    MemorySegment.ofArray(new byte[]{9}), leDoubles(9.0), 2);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("patch index 9 out of range [0,2)");
        }

        @Test
        void patchOffsetPushesIndexNegative_throws() {
            // Given patches.offset = 5 against a patch index of 1, so the absolute row index
            // is -4 and the copy would write before the start of the output buffer
            ProtoPatchesMetadata pm = new ProtoPatchesMetadata(1L, 5L,
                    ProtoPType.U8, null, null, null);
            DecodeContext ctx = patchedCtx(pm, leLongs(100L, 200L),
                    MemorySegment.ofArray(new byte[]{1}), leDoubles(9.0), 2);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("patch index -4 out of range [0,2)");
        }

        @Test
        void patchCountGreaterThanRowCount_throws() {
            // Given 3 declared patches over a 2-row array: the third index necessarily falls
            // outside the rows, which is exactly what the range guard is for
            ProtoPatchesMetadata pm = new ProtoPatchesMetadata(3L, 0L,
                    ProtoPType.U8, null, null, null);
            DecodeContext ctx = patchedCtx(pm, leLongs(100L, 200L),
                    MemorySegment.ofArray(new byte[]{0, 1, 2}), leDoubles(9.0, 9.0, 9.0), 2);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("patch index 2 out of range [0,2)");
        }

        @Test
        void emptyPatchIndexChild_throws() {
            // Given patches declared but a zero-length index child: the broadcast branch used
            // to compute `i % 0` and die with an ArithmeticException
            ProtoPatchesMetadata pm = new ProtoPatchesMetadata(1L, 0L,
                    ProtoPType.U8, null, null, null);
            DecodeContext ctx = patchedCtx(pm, leLongs(100L, 200L),
                    MemorySegment.ofArray(new byte[0]), leDoubles(9.0), 2);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("empty patch child");
        }

        private DecodeContext ctx(DType dtype, ProtoALPMetadata meta, MemorySegment encoded, long rowCount) {
            ArrayNode enc = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALP, MemorySegment.ofArray(meta.encode()),
                    new ArrayNode[]{enc}, new int[0]);
            return new DecodeContext(node, dtype, rowCount, new MemorySegment[]{encoded}, REGISTRY, Arena.ofAuto());
        }

        private DecodeContext patchedCtx(ProtoPatchesMetadata pm, MemorySegment encoded,
                MemorySegment indices, MemorySegment values, long rowCount) {
            byte[] meta = new ProtoALPMetadata(2, 0, pm).encode();
            ArrayNode enc = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
            ArrayNode idx = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
            ArrayNode val = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{2});
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALP, MemorySegment.ofArray(meta),
                    new ArrayNode[]{enc, idx, val}, new int[0]);
            return new DecodeContext(node, F64, rowCount,
                    new MemorySegment[]{encoded, indices, values}, REGISTRY, Arena.ofAuto());
        }
    }

    /// An ALP array's validity IS its encoded child's (`ValidityChild<ALP>`, #210): a nullable
    /// encoded primitive child surfaces as a [MaskedArray], and that mask must ride through both the
    /// per-row lazy path and the single-value constant-broadcast path rather than being flattened.
    @Nested
    class MaskedChildPropagation {

        @Test
        void maskedEncodedChild_lazyPath_propagatesNullsAndDecodesValues() {
            // Given — encoded I64 [100,200,300] with row 1 null; exp_e=2 scales by 0.01
            ArrayNode validityNode = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{1});
            ArrayNode enc = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[]{validityNode}, new int[]{0});
            byte[] meta = new ProtoALPMetadata(2, 0, null).encode();
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALP, MemorySegment.ofArray(meta),
                    new ArrayNode[]{enc}, new int[0]);
            MemorySegment[] segs = {leLongs(100L, 200L, 300L), boolBitmap(true, false, true)};
            DecodeContext ctx = new DecodeContext(node, F64, 3, segs, REGISTRY, Arena.ofAuto());

            // When
            Array result = SUT.decode(ctx);

            // Then — nulls survive and valid rows decode through the lazy ALP path
            MaskedArray masked = (MaskedArray) result;
            assertThat(masked.isValid(0)).isTrue();
            assertThat(masked.isValid(1)).isFalse();
            assertThat(masked.isValid(2)).isTrue();
            DoubleArray inner = (DoubleArray) masked.inner();
            assertThat(inner.getDouble(0)).isCloseTo(1.0, within(1e-9));
            assertThat(inner.getDouble(2)).isCloseTo(3.0, within(1e-9));
        }

        @Test
        void maskedEncodedChild_constantBroadcast_preservesValidity() {
            // Given — a single encoded value broadcast to 3 rows (capacity < n, no patches) routes
            // through the LazyConstant arm; the validity mask must survive that path too.
            ArrayNode validityNode = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{1});
            ArrayNode enc = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[]{validityNode}, new int[]{0});
            byte[] meta = new ProtoALPMetadata(2, 0, null).encode(); // *0.01
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALP, MemorySegment.ofArray(meta),
                    new ArrayNode[]{enc}, new int[0]);
            MemorySegment[] segs = {leLongs(100L), boolBitmap(true, false, true)};
            DecodeContext ctx = new DecodeContext(node, F64, 3, segs, REGISTRY, Arena.ofAuto());

            // When
            Array result = SUT.decode(ctx);

            // Then — every row is the broadcast constant 1.0, and row 1 stays null
            MaskedArray masked = (MaskedArray) result;
            assertThat(masked.isValid(0)).isTrue();
            assertThat(masked.isValid(1)).isFalse();
            assertThat(masked.isValid(2)).isTrue();
            DoubleArray inner = (DoubleArray) masked.inner();
            for (int i = 0; i < 3; i++) {
                assertThat(inner.getDouble(i)).as("row %d", i).isCloseTo(1.0, within(1e-9));
            }
        }

        @Test
        void plainEncodedChild_returnsPlainArray() {
            // Given — a non-nullable encoded child (no validity): no-regression path must NOT mask
            ArrayNode enc = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
            byte[] meta = new ProtoALPMetadata(2, 0, null).encode();
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALP, MemorySegment.ofArray(meta),
                    new ArrayNode[]{enc}, new int[0]);
            DecodeContext ctx = new DecodeContext(node, F64, 2,
                    new MemorySegment[]{leLongs(100L, 200L)}, REGISTRY, Arena.ofAuto());

            // When
            Array result = SUT.decode(ctx);

            // Then
            assertThat(result).isInstanceOf(DoubleArray.class).isNotInstanceOf(MaskedArray.class);
        }

        private MemorySegment boolBitmap(boolean... valid) {
            byte[] bytes = new byte[(valid.length + 7) / 8];
            for (int i = 0; i < valid.length; i++) {
                if (valid[i]) {
                    bytes[i >>> 3] |= (byte) (1 << (i & 7));
                }
            }
            return MemorySegment.ofArray(bytes);
        }
    }
}
