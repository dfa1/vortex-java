package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.encoding.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.VariantArray;
import io.github.dfa1.vortex.encoding.EncodingId;

import io.github.dfa1.vortex.proto.Primitive;
import io.github.dfa1.vortex.proto.VariantMetadata;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

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
        return ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{segIdx});
    }

    private static ArrayNode nullChildNode() {
        return ArrayNode.of(EncodingId.VORTEX_NULL, null, new ArrayNode[0], new int[]{});
    }


    @org.junit.jupiter.api.Test
    void decode_withoutShredded_returnsCoreStorageOnly() {
        // Given
        ArrayNode coreNode = nullChildNode();
        ArrayNode variantNode = ArrayNode.of(EncodingId.VORTEX_VARIANT, null,
                new ArrayNode[]{coreNode}, new int[]{});

        ReadRegistry registry = TestRegistry.ofDecoders(SUT, new NullEncodingDecoder());
        DecodeContext ctx = new DecodeContext(variantNode, VARIANT_DTYPE, N,
                new MemorySegment[0], registry, Arena.ofAuto());

        // When
        Array result = SUT.decode(ctx);

        // Then
        assertThat(result).isInstanceOf(VariantArray.class);
        VariantArray va = (VariantArray) result;
        assertThat(va.dtype()).isEqualTo(VARIANT_DTYPE);
        assertThat(va.length()).isEqualTo(N);
        assertThat(va.coreStorage()).isInstanceOf(NullArray.class);
        assertThat(va.shredded()).isNull();
    }

    @Test
    void decode_withShredded_decodesSecondChild() {
        // Given
        io.github.dfa1.vortex.proto.DType shreddedProto = io.github.dfa1.vortex.proto.DType.ofPrimitive(
                new Primitive(io.github.dfa1.vortex.proto.PType.I32, false));
        ByteBuffer meta = variantMetaWithShredded(shreddedProto);

        ArrayNode coreNode = nullChildNode();
        ArrayNode shreddedNode = primitiveChildNode(0);
        ArrayNode variantNode = ArrayNode.of(EncodingId.VORTEX_VARIANT, meta,
                new ArrayNode[]{coreNode, shreddedNode}, new int[]{});

        MemorySegment[] segments = {TestSegments.leInts(1, 2, 3)};
        ReadRegistry registry = TestRegistry.ofDecoders(SUT, new NullEncodingDecoder(), new PrimitiveEncodingDecoder());
        DecodeContext ctx = new DecodeContext(variantNode, VARIANT_DTYPE, N,
                segments, registry, Arena.ofAuto());

        // When
        Array result = SUT.decode(ctx);

        // Then
        assertThat(result).isInstanceOf(VariantArray.class);
        VariantArray va = (VariantArray) result;
        assertThat(va.shredded()).isNotNull();
        assertThat(va.shredded().dtype()).isEqualTo(new DType.Primitive(PType.I32, false));
        assertThat(va.shredded().length()).isEqualTo(N);
    }

    @Test
    void decode_emptyMetadata_noShredded() {
        // Given
        ArrayNode coreNode = nullChildNode();
        ArrayNode variantNode = ArrayNode.of(EncodingId.VORTEX_VARIANT, ByteBuffer.allocate(0),
                new ArrayNode[]{coreNode}, new int[]{});

        ReadRegistry registry = TestRegistry.ofDecoders(SUT, new NullEncodingDecoder());
        DecodeContext ctx = new DecodeContext(variantNode, VARIANT_DTYPE, N,
                new MemorySegment[0], registry, Arena.ofAuto());

        // When
        Array result = SUT.decode(ctx);

        // Then
        VariantArray va = (VariantArray) result;
        assertThat(va.shredded()).isNull();
    }

    @Test
    void decode_nullableDtype_preservedOnResult() {
        // Given
        DType nullableVariant = new DType.Variant(true);
        ArrayNode coreNode = nullChildNode();
        ArrayNode variantNode = ArrayNode.of(EncodingId.VORTEX_VARIANT, null,
                new ArrayNode[]{coreNode}, new int[]{});

        ReadRegistry registry = TestRegistry.ofDecoders(SUT, new NullEncodingDecoder());
        DecodeContext ctx = new DecodeContext(variantNode, nullableVariant, N,
                new MemorySegment[0], registry, Arena.ofAuto());

        // When
        VariantArray result = (VariantArray) SUT.decode(ctx);

        // Then
        assertThat(result.dtype()).isEqualTo(nullableVariant);
        assertThat(result.dtype().nullable()).isTrue();
    }

    @Test
    void decode_wrongChildCount_throws() {
        // Given
        ArrayNode variantNode = ArrayNode.of(EncodingId.VORTEX_VARIANT, null,
                new ArrayNode[0], new int[]{});

        ReadRegistry registry = TestRegistry.ofDecoders(SUT);
        DecodeContext ctx = new DecodeContext(variantNode, VARIANT_DTYPE, N,
                new MemorySegment[0], registry, Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> SUT.decode(ctx))
                .hasMessageContaining("expected 1 or 2 children");
    }
}
