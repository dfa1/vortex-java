package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.encoding.EncodeNode;
import io.github.dfa1.vortex.encoding.EncodeResult;
import io.github.dfa1.vortex.encoding.EncodeTestHelper;
import io.github.dfa1.vortex.reader.decode.DecodeTestHelper;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.reader.decode.BoolEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.MaskedEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
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
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());

    private static EncodeResult maskedResult(int[] values, boolean[] validity) {
        DType i32 = new DType.Primitive(PType.I32, false);
        EncodeResult childResult = PRIM_ENCODER.encode(i32, values, EncodeTestHelper.testCtx());

        List<MemorySegment> allBuffers = new ArrayList<>(childResult.buffers());
        EncodeNode[] children;

        if (validity == null) {
            children = new EncodeNode[]{childResult.rootNode()};
        } else {
            DType boolDtype = new DType.Bool(false);
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
        DType i32Nullable = new DType.Primitive(PType.I32, true);
        EncodeResult ctx = maskedResult(new int[]{10, 20, 30}, null);

        Array result = DECODER.decode(DecodeTestHelper.toDecodeContext(ctx, 3L, i32Nullable, REGISTRY));

        assertThat(result).isInstanceOf(MaskedArray.class);
        MaskedArray masked = (MaskedArray) result;
        assertThat(masked.length()).isEqualTo(3);
        assertThat(masked.isValid(0)).isTrue();
        assertThat(masked.isValid(1)).isTrue();
        assertThat(masked.isValid(2)).isTrue();
    }

    @Test
    void twoChildren_withValidity_masksNulls() {
        DType i32Nullable = new DType.Primitive(PType.I32, true);
        EncodeResult ctx = maskedResult(new int[]{1, 2, 3, 4, 5},
                new boolean[]{true, false, true, false, true});

        Array result = DECODER.decode(DecodeTestHelper.toDecodeContext(ctx, 5L, i32Nullable, REGISTRY));

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
        DType i32Nullable = new DType.Primitive(PType.I32, true);
        EncodeResult ctx = maskedResult(new int[]{1, 2, 3}, null);

        Array result = DECODER.decode(DecodeTestHelper.toDecodeContext(ctx, 3L, i32Nullable, REGISTRY));

        assertThat(result.dtype().nullable()).isTrue();
    }

    @Test
    void inner_containsChildValues() {
        DType i32Nullable = new DType.Primitive(PType.I32, true);
        EncodeResult ctx = maskedResult(new int[]{7, 8, 9}, null);

        MaskedArray result = (MaskedArray) DECODER.decode(DecodeTestHelper.toDecodeContext(ctx, 3L, i32Nullable, REGISTRY));
        IntArray inner = (IntArray) result.inner();

        assertThat(ArraySegments.of(inner).get(PTypeIO.LE_INT, 0L)).isEqualTo(7);
        assertThat(ArraySegments.of(inner).get(PTypeIO.LE_INT, 4L)).isEqualTo(8);
        assertThat(ArraySegments.of(inner).get(PTypeIO.LE_INT, 8L)).isEqualTo(9);
    }

    @Test
    void buffersPresentThrows() {
        DType i32Nullable = new DType.Primitive(PType.I32, true);

        EncodeNode childNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode maskedNode = new EncodeNode(
                EncodingId.VORTEX_MASKED, null,
                new EncodeNode[]{childNode}, new int[]{1});
        MemorySegment dummyBuf = Arena.ofAuto().allocate(4);
        EncodeResult result = new EncodeResult(maskedNode, List.of(dummyBuf, dummyBuf), null, null);

        assertThatThrownBy(() -> DECODER.decode(DecodeTestHelper.toDecodeContext(result, 1L, i32Nullable, REGISTRY)))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("expected 0 buffers");
    }

    @Test
    void zeroChildrenThrows() {
        DType i32Nullable = new DType.Primitive(PType.I32, true);

        EncodeNode maskedNode = new EncodeNode(
                EncodingId.VORTEX_MASKED, null, new EncodeNode[]{}, new int[]{});
        EncodeResult result = new EncodeResult(maskedNode, List.of(), null, null);

        assertThatThrownBy(() -> DECODER.decode(DecodeTestHelper.toDecodeContext(result, 0L, i32Nullable, REGISTRY)))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("expected 1 or 2 children");
    }

    @Test
    void threeChildrenThrows() {
        DType i32Nullable = new DType.Primitive(PType.I32, true);

        DType i32 = new DType.Primitive(PType.I32, false);
        EncodeResult childResult = PRIM_ENCODER.encode(i32, new int[]{1}, EncodeTestHelper.testCtx());
        EncodeNode childNode = childResult.rootNode();
        EncodeNode maskedNode = new EncodeNode(
                EncodingId.VORTEX_MASKED, null,
                new EncodeNode[]{childNode, childNode, childNode}, new int[]{});
        List<MemorySegment> bufs = new ArrayList<>(childResult.buffers());
        EncodeResult result = new EncodeResult(maskedNode, bufs, null, null);

        assertThatThrownBy(() -> DECODER.decode(DecodeTestHelper.toDecodeContext(result, 1L, i32Nullable, REGISTRY)))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("expected 1 or 2 children");
    }
}
