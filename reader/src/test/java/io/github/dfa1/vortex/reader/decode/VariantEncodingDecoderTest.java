package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.reader.ReadRegistry;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.NullArray;
import io.github.dfa1.vortex.core.array.VariantArray;
import io.github.dfa1.vortex.encoding.EncodingId;

import io.github.dfa1.vortex.proto.Primitive;
import io.github.dfa1.vortex.proto.VariantMetadata;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariantEncodingDecoderTest {

    private static final DType VARIANT_DTYPE = new DType.Variant(false);
    private static final int N = 3;

    private static final VariantEncodingDecoder SUT = new VariantEncodingDecoder();

    private static ByteBuffer variantMetaWithShredded(io.github.dfa1.vortex.proto.DType shredded) {
        return ByteBuffer.wrap(new VariantMetadata(shredded).encode());
    }

    private static ArrayNode primitiveChildNode(int segIdx) {
        return ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{segIdx}, null);
    }

    private static ArrayNode nullChildNode() {
        return ArrayNode.of(EncodingId.VORTEX_NULL, null, new ArrayNode[0], new int[]{}, null);
    }

    private static MemorySegment i32Segment(int... values) {
        MemorySegment seg = MemorySegment.ofArray(new byte[values.length * 4]);
        ByteBuffer bb = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) {
            bb.putInt(v);
        }
        return seg;
    }

    @org.junit.jupiter.api.Test
    void decode_withoutShredded_returnsCoreStorageOnly() {
        ArrayNode coreNode = nullChildNode();
        ArrayNode variantNode = ArrayNode.of(EncodingId.VORTEX_VARIANT, null,
                new ArrayNode[]{coreNode}, new int[]{}, null);

        ReadRegistry registry = TestRegistry.ofDecoders(SUT, new NullEncodingDecoder());
        DecodeContext ctx = new DecodeContext(variantNode, VARIANT_DTYPE, N,
                new MemorySegment[0], registry, Arena.ofAuto());

        Array result = SUT.decode(ctx);

        assertThat(result).isInstanceOf(VariantArray.class);
        VariantArray va = (VariantArray) result;
        assertThat(va.dtype()).isEqualTo(VARIANT_DTYPE);
        assertThat(va.length()).isEqualTo(N);
        assertThat(va.coreStorage()).isInstanceOf(NullArray.class);
        assertThat(va.shredded()).isNull();
    }

    @Test
    void decode_withShredded_decodesSecondChild() {
        io.github.dfa1.vortex.proto.DType shreddedProto = io.github.dfa1.vortex.proto.DType.ofPrimitive(
                new Primitive(io.github.dfa1.vortex.proto.PType.I32, false));
        ByteBuffer meta = variantMetaWithShredded(shreddedProto);

        ArrayNode coreNode = nullChildNode();
        ArrayNode shreddedNode = primitiveChildNode(0);
        ArrayNode variantNode = ArrayNode.of(EncodingId.VORTEX_VARIANT, meta,
                new ArrayNode[]{coreNode, shreddedNode}, new int[]{}, null);

        MemorySegment[] segments = {i32Segment(1, 2, 3)};
        ReadRegistry registry = TestRegistry.ofDecoders(SUT, new NullEncodingDecoder(), new PrimitiveEncodingDecoder());
        DecodeContext ctx = new DecodeContext(variantNode, VARIANT_DTYPE, N,
                segments, registry, Arena.ofAuto());

        Array result = SUT.decode(ctx);

        assertThat(result).isInstanceOf(VariantArray.class);
        VariantArray va = (VariantArray) result;
        assertThat(va.shredded()).isNotNull();
        assertThat(va.shredded().dtype()).isEqualTo(new DType.Primitive(PType.I32, false));
        assertThat(va.shredded().length()).isEqualTo(N);
    }

    @Test
    void decode_emptyMetadata_noShredded() {
        ArrayNode coreNode = nullChildNode();
        ArrayNode variantNode = ArrayNode.of(EncodingId.VORTEX_VARIANT, ByteBuffer.allocate(0),
                new ArrayNode[]{coreNode}, new int[]{}, null);

        ReadRegistry registry = TestRegistry.ofDecoders(SUT, new NullEncodingDecoder());
        DecodeContext ctx = new DecodeContext(variantNode, VARIANT_DTYPE, N,
                new MemorySegment[0], registry, Arena.ofAuto());

        Array result = SUT.decode(ctx);

        VariantArray va = (VariantArray) result;
        assertThat(va.shredded()).isNull();
    }

    @Test
    void decode_nullableDtype_preservedOnResult() {
        DType nullableVariant = new DType.Variant(true);
        ArrayNode coreNode = nullChildNode();
        ArrayNode variantNode = ArrayNode.of(EncodingId.VORTEX_VARIANT, null,
                new ArrayNode[]{coreNode}, new int[]{}, null);

        ReadRegistry registry = TestRegistry.ofDecoders(SUT, new NullEncodingDecoder());
        DecodeContext ctx = new DecodeContext(variantNode, nullableVariant, N,
                new MemorySegment[0], registry, Arena.ofAuto());

        VariantArray va = (VariantArray) SUT.decode(ctx);

        assertThat(va.dtype()).isEqualTo(nullableVariant);
        assertThat(va.dtype().nullable()).isTrue();
    }

    @Test
    void decode_wrongChildCount_throws() {
        ArrayNode variantNode = ArrayNode.of(EncodingId.VORTEX_VARIANT, null,
                new ArrayNode[0], new int[]{}, null);

        ReadRegistry registry = TestRegistry.ofDecoders(SUT);
        DecodeContext ctx = new DecodeContext(variantNode, VARIANT_DTYPE, N,
                new MemorySegment[0], registry, Arena.ofAuto());

        assertThatThrownBy(() -> SUT.decode(ctx))
                .hasMessageContaining("expected 1 or 2 children");
    }
}
