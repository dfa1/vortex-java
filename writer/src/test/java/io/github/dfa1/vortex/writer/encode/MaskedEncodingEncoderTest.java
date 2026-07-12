package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.reader.decode.BoolEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ConstantEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.MaskedEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import io.github.dfa1.vortex.writer.WriteRegistry;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaskedEncodingEncoderTest {

    private static final MaskedEncodingDecoder DECODER = new MaskedEncodingDecoder();
    private static final PrimitiveEncodingEncoder PRIM_ENCODER = new PrimitiveEncodingEncoder();
    private static final BoolEncodingEncoder BOOL_ENCODER = new BoolEncodingEncoder();
    private static final MaskedEncodingEncoder SUT = new MaskedEncodingEncoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());
    private static final ReadRegistry REGISTRY_WITH_CONSTANT = TestRegistry.ofDecoders(
            DECODER, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder(), new ConstantEncodingDecoder());

    private static EncodeResult maskedResult(int[] values, boolean[] validity) {
        DType i32 = DType.I32;
        EncodeResult childResult = PRIM_ENCODER.encode(i32, values, EncodeTestHelper.testCtx());

        List<MemorySegment> allBuffers = new ArrayList<>(childResult.buffers());
        EncodeNode[] children;

        if (validity == null) {
            children = new EncodeNode[]{childResult.rootNode()};
        } else {
            DType boolDtype = DType.BOOL;
            EncodeResult validityResult = BOOL_ENCODER.encode(boolDtype, validity, EncodeTestHelper.testCtx());
            EncodeNode remapped = EncodeNode.remapBufferIndices(
                    validityResult.rootNode(), childResult.buffers().size());
            allBuffers.addAll(validityResult.buffers());
            children = new EncodeNode[]{childResult.rootNode(), remapped};
        }

        EncodeNode maskedNode = new EncodeNode(
                EncodingId.VORTEX_MASKED, null, children, new int[]{});
        return new EncodeResult(maskedNode, allBuffers, null, null);
    }

    @Test
    void oneChild_noValidity_allPositionsValid() {
        // Given
        DType i32Nullable = new DType.Primitive(PType.I32, true);
        EncodeResult ctx = maskedResult(new int[]{10, 20, 30}, null);

        // When
        Array result = DECODER.decode(DecodeTestHelper.toDecodeContext(ctx, 3L, i32Nullable, REGISTRY));

        // Then
        assertThat(result).isInstanceOf(MaskedArray.class);
        MaskedArray masked = (MaskedArray) result;
        assertThat(masked.length()).isEqualTo(3);
        assertThat(masked.isValid(0)).isTrue();
        assertThat(masked.isValid(1)).isTrue();
        assertThat(masked.isValid(2)).isTrue();
    }

    @Test
    void twoChildren_withValidity_masksNulls() {
        // Given
        DType i32Nullable = new DType.Primitive(PType.I32, true);
        EncodeResult ctx = maskedResult(new int[]{1, 2, 3, 4, 5},
                new boolean[]{true, false, true, false, true});

        // When
        Array result = DECODER.decode(DecodeTestHelper.toDecodeContext(ctx, 5L, i32Nullable, REGISTRY));

        // Then
        MaskedArray masked = (MaskedArray) result;
        assertThat(masked.length()).isEqualTo(5);
        assertThat(masked.isValid(0)).isTrue();
        assertThat(masked.isValid(1)).isFalse();
        assertThat(masked.isValid(2)).isTrue();
        assertThat(masked.isValid(3)).isFalse();
        assertThat(masked.isValid(4)).isTrue();
    }

    @Test
    void dtype_isNullable() {
        // Given
        DType i32Nullable = new DType.Primitive(PType.I32, true);
        EncodeResult ctx = maskedResult(new int[]{1, 2, 3}, null);

        // When
        Array result = DECODER.decode(DecodeTestHelper.toDecodeContext(ctx, 3L, i32Nullable, REGISTRY));

        // Then
        assertThat(result.dtype().nullable()).isTrue();
    }

    @Test
    void inner_containsChildValues() {
        // Given
        DType i32Nullable = new DType.Primitive(PType.I32, true);
        EncodeResult ctx = maskedResult(new int[]{7, 8, 9}, null);

        // When
        MaskedArray result = (MaskedArray) DECODER.decode(DecodeTestHelper.toDecodeContext(ctx, 3L, i32Nullable, REGISTRY));
        IntArray inner = (IntArray) result.inner();

        // Then
        assertThat(inner.materialize(Arena.ofAuto()).get(VortexFormat.LE_INT, 0L)).isEqualTo(7);
        assertThat(inner.materialize(Arena.ofAuto()).get(VortexFormat.LE_INT, 4L)).isEqualTo(8);
        assertThat(inner.materialize(Arena.ofAuto()).get(VortexFormat.LE_INT, 8L)).isEqualTo(9);
    }

    @Test
    void buffersPresentThrows() {
        // Given
        DType i32Nullable = new DType.Primitive(PType.I32, true);

        EncodeNode childNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode maskedNode = new EncodeNode(
                EncodingId.VORTEX_MASKED, null,
                new EncodeNode[]{childNode}, new int[]{1});
        MemorySegment dummyBuf = Arena.ofAuto().allocate(4);
        EncodeResult result = new EncodeResult(maskedNode, List.of(dummyBuf, dummyBuf), null, null);

        // When
        // Then
        assertThatThrownBy(() -> DECODER.decode(DecodeTestHelper.toDecodeContext(result, 1L, i32Nullable, REGISTRY)))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("expected 0 buffers");
    }

    @Test
    void zeroChildrenThrows() {
        // Given
        DType i32Nullable = new DType.Primitive(PType.I32, true);

        EncodeNode maskedNode = new EncodeNode(
                EncodingId.VORTEX_MASKED, null, new EncodeNode[]{}, new int[]{});
        EncodeResult result = new EncodeResult(maskedNode, List.of(), null, null);

        // When
        // Then
        assertThatThrownBy(() -> DECODER.decode(DecodeTestHelper.toDecodeContext(result, 0L, i32Nullable, REGISTRY)))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("expected 1 or 2 children");
    }

    @Test
    void threeChildrenThrows() {
        // Given
        DType i32Nullable = new DType.Primitive(PType.I32, true);

        DType i32 = DType.I32;
        EncodeResult childResult = PRIM_ENCODER.encode(i32, new int[]{1}, EncodeTestHelper.testCtx());
        EncodeNode childNode = childResult.rootNode();
        EncodeNode maskedNode = new EncodeNode(
                EncodingId.VORTEX_MASKED, null,
                new EncodeNode[]{childNode, childNode, childNode}, new int[]{});
        List<MemorySegment> bufs = new ArrayList<>(childResult.buffers());
        EncodeResult result = new EncodeResult(maskedNode, bufs, null, null);

        // When
        // Then
        assertThatThrownBy(() -> DECODER.decode(DecodeTestHelper.toDecodeContext(result, 1L, i32Nullable, REGISTRY)))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("expected 1 or 2 children");
    }

    @Test
    void allValidColumn_encodesValidityAsConstant() {
        // Given
        DType i32Nullable = new DType.Primitive(PType.I32, true);
        NullableData data = new NullableData(new int[]{1, 2, 3}, new boolean[]{true, true, true});

        // When
        EncodeResult result = SUT.encode(i32Nullable, data, EncodeTestHelper.testCtx());

        // Then — the validity child is a vortex.constant scalar, not a raw bitmap
        assertThat(result.rootNode().children()[1].encodingId()).isEqualTo(EncodingId.VORTEX_CONSTANT);

        // And it still round-trips to an all-valid MaskedArray
        Array decoded = DECODER.decode(DecodeTestHelper.toDecodeContext(result, 3L, i32Nullable, REGISTRY_WITH_CONSTANT));
        MaskedArray masked = (MaskedArray) decoded;
        assertThat(masked.isValid(0)).isTrue();
        assertThat(masked.isValid(1)).isTrue();
        assertThat(masked.isValid(2)).isTrue();
    }

    @Test
    void allInvalidColumn_encodesValidityAsConstant() {
        // Given
        DType i32Nullable = new DType.Primitive(PType.I32, true);
        NullableData data = new NullableData(new int[]{0, 0, 0}, new boolean[]{false, false, false});

        // When
        EncodeResult result = SUT.encode(i32Nullable, data, EncodeTestHelper.testCtx());

        // Then
        assertThat(result.rootNode().children()[1].encodingId()).isEqualTo(EncodingId.VORTEX_CONSTANT);

        Array decoded = DECODER.decode(DecodeTestHelper.toDecodeContext(result, 3L, i32Nullable, REGISTRY_WITH_CONSTANT));
        MaskedArray masked = (MaskedArray) decoded;
        assertThat(masked.isValid(0)).isFalse();
        assertThat(masked.isValid(1)).isFalse();
        assertThat(masked.isValid(2)).isFalse();
    }

    @Test
    void mixedValidity_stillEncodesAsRawBitmap() {
        // Given — a regression guard: mixed validity must not be misdetected as constant
        DType i32Nullable = new DType.Primitive(PType.I32, true);
        NullableData data = new NullableData(new int[]{1, 2, 3}, new boolean[]{true, false, true});

        // When
        EncodeResult result = SUT.encode(i32Nullable, data, EncodeTestHelper.testCtx());

        // Then
        assertThat(result.rootNode().children()[1].encodingId()).isEqualTo(EncodingId.VORTEX_BOOL);

        Array decoded = DECODER.decode(DecodeTestHelper.toDecodeContext(result, 3L, i32Nullable, REGISTRY));
        MaskedArray masked = (MaskedArray) decoded;
        assertThat(masked.isValid(0)).isTrue();
        assertThat(masked.isValid(1)).isFalse();
        assertThat(masked.isValid(2)).isTrue();
    }

    @Test
    void withCascade_periodicNulls_prefersSparseOverRawBitmap() {
        // Given — 2000 rows, every 10th null: a clustered/regular pattern (mirrors the real
        // low-cardinality-Utf8 benchmark) whose patch-index gaps compress far below a raw bitmap
        // once cascaded (e.g. fastlanes.delta on a near-constant stride).
        int n = 2_000;
        int[] values = new int[n];
        boolean[] validity = new boolean[n];
        for (int i = 0; i < n; i++) {
            values[i] = i;
            validity[i] = i % 10 != 0;
        }
        DType i32Nullable = new DType.Primitive(PType.I32, true);
        NullableData data = new NullableData(values, validity);
        EncodeContext cascadeCtx = EncodeContext.ofDepth(3, Arena.ofAuto(), WriteRegistry.loadAll());

        // When
        EncodeResult result = SUT.encode(i32Nullable, data, cascadeCtx);

        // Then — the validity child picked vortex.sparse over a raw bitmap
        assertThat(result.rootNode().children()[1].encodingId()).isEqualTo(EncodingId.VORTEX_SPARSE);

        // And every value and null position round-trips exactly
        Array decoded = DECODER.decode(DecodeTestHelper.toDecodeContext(result, (long) n, i32Nullable, ReadRegistry.loadAll()));
        MaskedArray masked = (MaskedArray) decoded;
        for (int i = 0; i < n; i++) {
            assertThat(masked.isValid(i)).as("row %d", i).isEqualTo(i % 10 != 0);
        }
    }

    @Test
    void withCascade_denseRandomNulls_keepsRawBitmap() {
        // Given — ~50% nulls with no exploitable structure: sparse patch overhead (index + value
        // per minority row) can't beat a raw 1-bit/row bitmap, so the comparison must keep the
        // bitmap rather than always preferring sparse.
        int n = 2_000;
        int[] values = new int[n];
        boolean[] validity = new boolean[n];
        java.util.Random rng = new java.util.Random(7);
        for (int i = 0; i < n; i++) {
            values[i] = i;
            validity[i] = rng.nextBoolean();
        }
        DType i32Nullable = new DType.Primitive(PType.I32, true);
        NullableData data = new NullableData(values, validity);
        EncodeContext cascadeCtx = EncodeContext.ofDepth(3, Arena.ofAuto(), WriteRegistry.loadAll());

        // When
        EncodeResult result = SUT.encode(i32Nullable, data, cascadeCtx);

        // Then
        assertThat(result.rootNode().children()[1].encodingId()).isEqualTo(EncodingId.VORTEX_BOOL);

        // And it still round-trips exactly
        Array decoded = DECODER.decode(DecodeTestHelper.toDecodeContext(result, (long) n, i32Nullable, ReadRegistry.loadAll()));
        MaskedArray masked = (MaskedArray) decoded;
        for (int i = 0; i < n; i++) {
            assertThat(masked.isValid(i)).as("row %d", i).isEqualTo(validity[i]);
        }
    }

    @Test
    void withCascade_clusteredNulls_prefersRunEndOverSparseAndRawBitmap() {
        // Given — 2000 rows, one contiguous invalid stretch (rows 500-999): a handful of runs,
        // where sparse would pay a patch per invalid row but run-end pays only for the two
        // boundaries — the shape run-end targets that sparse does not.
        int n = 2_000;
        int[] values = new int[n];
        boolean[] validity = new boolean[n];
        for (int i = 0; i < n; i++) {
            values[i] = i;
            validity[i] = i < 500 || i >= 1_000;
        }
        DType i32Nullable = new DType.Primitive(PType.I32, true);
        NullableData data = new NullableData(values, validity);
        EncodeContext cascadeCtx = EncodeContext.ofDepth(3, Arena.ofAuto(), WriteRegistry.loadAll());

        // When
        EncodeResult result = SUT.encode(i32Nullable, data, cascadeCtx);

        // Then — the validity child picked vortex.runend over sparse and a raw bitmap
        assertThat(result.rootNode().children()[1].encodingId()).isEqualTo(EncodingId.VORTEX_RUNEND);

        // And every value and null position round-trips exactly
        Array decoded = DECODER.decode(DecodeTestHelper.toDecodeContext(result, (long) n, i32Nullable, ReadRegistry.loadAll()));
        MaskedArray masked = (MaskedArray) decoded;
        for (int i = 0; i < n; i++) {
            assertThat(masked.isValid(i)).as("row %d", i).isEqualTo(validity[i]);
        }
    }
}
