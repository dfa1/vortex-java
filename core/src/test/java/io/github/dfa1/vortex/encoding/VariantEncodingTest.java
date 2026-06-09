package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.NullArray;
import io.github.dfa1.vortex.core.array.VariantArray;
import io.github.dfa1.vortex.proto.DTypeProtos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariantEncodingTest {

    private static final DType VARIANT_DTYPE = new DType.Variant(false);
    private static final int N = 3;

    // Build a DType proto bytes for VariantMetadataProto { shredded_dtype = 1 }
    private static ByteBuffer variantMetaWithShredded(DTypeProtos.DType shredded) {
        byte[] dtypeBytes = shredded.toByteArray();
        // field 1, wire type 2 (length-delimited): tag = 0x0A, then varint length, then bytes
        byte[] meta = new byte[1 + 1 + dtypeBytes.length];
        meta[0] = 0x0A;
        meta[1] = (byte) dtypeBytes.length;
        System.arraycopy(dtypeBytes, 0, meta, 2, dtypeBytes.length);
        return ByteBuffer.wrap(meta);
    }

    // Build inner / shredded child nodes backed by primitive buffer at the given segment index.
    private static ArrayNode primitiveChildNode(int segIdx) {
        return ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{segIdx}, null);
    }

    // Build a NullEncoding child node (no buffers needed).
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

    @Nested
    class Decode {

        @Test
        void decode_withoutShredded_returnsCoreStorageOnly() {
            // Given — 1 child: NullEncoding core_storage; no shredded_dtype in metadata
            ArrayNode coreNode = nullChildNode();
            ArrayNode variantNode = ArrayNode.of(EncodingId.VORTEX_VARIANT, null,
                    new ArrayNode[]{coreNode}, new int[]{}, null);

            Registry registry = TestRegistry.of(new VariantEncoding(), new NullEncoding());
            DecodeContext ctx = new DecodeContext(variantNode, VARIANT_DTYPE, N,
                    new MemorySegment[0], registry, Arena.ofAuto());

            // When
            Array result = new VariantEncoding().decode(ctx);

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
            // Given — 2 children: null core_storage + primitive shredded; metadata encodes I32 dtype
            DTypeProtos.DType shreddedProto = DTypeProtos.DType.newBuilder()
                    .setPrimitive(DTypeProtos.Primitive.newBuilder()
                            .setType(DTypeProtos.PType.I32)
                            .setNullable(false)
                            .build())
                    .build();
            ByteBuffer meta = variantMetaWithShredded(shreddedProto);

            ArrayNode coreNode = nullChildNode();
            ArrayNode shreddedNode = primitiveChildNode(0);
            ArrayNode variantNode = ArrayNode.of(EncodingId.VORTEX_VARIANT, meta,
                    new ArrayNode[]{coreNode, shreddedNode}, new int[]{}, null);

            MemorySegment[] segments = {i32Segment(1, 2, 3)};
            Registry registry = TestRegistry.of(new VariantEncoding(), new NullEncoding(), new PrimitiveEncoding());
            DecodeContext ctx = new DecodeContext(variantNode, VARIANT_DTYPE, N,
                    segments, registry, Arena.ofAuto());

            // When
            Array result = new VariantEncoding().decode(ctx);

            // Then
            assertThat(result).isInstanceOf(VariantArray.class);
            VariantArray va = (VariantArray) result;
            assertThat(va.shredded()).isNotNull();
            assertThat(va.shredded().dtype()).isEqualTo(new DType.Primitive(PType.I32, false));
            assertThat(va.shredded().length()).isEqualTo(N);
        }

        @Test
        void decode_emptyMetadata_noShredded() {
            // Given — empty metadata byte buffer = no shredded_dtype
            ArrayNode coreNode = nullChildNode();
            ArrayNode variantNode = ArrayNode.of(EncodingId.VORTEX_VARIANT, ByteBuffer.allocate(0),
                    new ArrayNode[]{coreNode}, new int[]{}, null);

            Registry registry = TestRegistry.of(new VariantEncoding(), new NullEncoding());
            DecodeContext ctx = new DecodeContext(variantNode, VARIANT_DTYPE, N,
                    new MemorySegment[0], registry, Arena.ofAuto());

            // When
            Array result = new VariantEncoding().decode(ctx);

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
                    new ArrayNode[]{coreNode}, new int[]{}, null);

            Registry registry = TestRegistry.of(new VariantEncoding(), new NullEncoding());
            DecodeContext ctx = new DecodeContext(variantNode, nullableVariant, N,
                    new MemorySegment[0], registry, Arena.ofAuto());

            // When
            VariantArray va = (VariantArray) new VariantEncoding().decode(ctx);

            // Then
            assertThat(va.dtype()).isEqualTo(nullableVariant);
            assertThat(va.dtype().nullable()).isTrue();
        }

        @Test
        void decode_wrongChildCount_throws() {
            // Given — 0 children is invalid (need at least 1)
            ArrayNode variantNode = ArrayNode.of(EncodingId.VORTEX_VARIANT, null,
                    new ArrayNode[0], new int[]{}, null);

            Registry registry = TestRegistry.of(new VariantEncoding());
            DecodeContext ctx = new DecodeContext(variantNode, VARIANT_DTYPE, N,
                    new MemorySegment[0], registry, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> new VariantEncoding().decode(ctx))
                    .hasMessageContaining("expected 1 or 2 children");
        }
    }

}
