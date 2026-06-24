package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.encoding.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.VariantArray;
import io.github.dfa1.vortex.encoding.EncodingId;

import io.github.dfa1.vortex.proto.ProtoPrimitive;
import io.github.dfa1.vortex.proto.ProtoVariantMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariantEncodingDecoderTest {

    private static final DType VARIANT_DTYPE = DType.VARIANT;
    private static final int N = 3;

    private static final VariantEncodingDecoder SUT = new VariantEncodingDecoder();

    private static MemorySegment variantMetaWithShredded(io.github.dfa1.vortex.proto.ProtoDType shredded) {
        return MemorySegment.ofArray(new ProtoVariantMetadata(shredded).encode());
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
        io.github.dfa1.vortex.proto.ProtoDType shreddedProto = io.github.dfa1.vortex.proto.ProtoDType.ofPrimitive(
                new ProtoPrimitive(io.github.dfa1.vortex.proto.ProtoPType.I32, false));
        MemorySegment meta = variantMetaWithShredded(shreddedProto);

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
        assertThat(va.shredded().dtype()).isEqualTo(DType.I32);
        assertThat(va.shredded().length()).isEqualTo(N);
    }

    @Test
    void decode_emptyMetadata_noShredded() {
        // Given
        ArrayNode coreNode = nullChildNode();
        ArrayNode variantNode = ArrayNode.of(EncodingId.VORTEX_VARIANT, MemorySegment.ofArray(new byte[0]),
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

    /// Exercises every branch of [VariantEncodingDecoder#dtypeFromProto] — the
    /// proto-to-core DType translation that backs shredded-variant decoding.
    @Nested
    class DtypeFromProto {

        private static io.github.dfa1.vortex.proto.ProtoDType prim(io.github.dfa1.vortex.proto.ProtoPType pt, boolean nullable) {
            return io.github.dfa1.vortex.proto.ProtoDType.ofPrimitive(new ProtoPrimitive(pt, nullable));
        }

        @Test
        void nullType() {
            // Given / When
            DType result = VariantEncodingDecoder.dtypeFromProto(
                    io.github.dfa1.vortex.proto.ProtoDType.ofNull(new io.github.dfa1.vortex.proto.ProtoNull()));

            // Then null is always nullable
            assertThat(result).isEqualTo(new DType.Null(true));
        }

        @Test
        void bool() {
            // Given / When
            DType result = VariantEncodingDecoder.dtypeFromProto(
                    io.github.dfa1.vortex.proto.ProtoDType.ofBool(new io.github.dfa1.vortex.proto.ProtoBool(true)));

            // Then
            assertThat(result).isEqualTo(new DType.Bool(true));
        }

        @Test
        void primitive() {
            // Given / When
            DType result = VariantEncodingDecoder.dtypeFromProto(prim(io.github.dfa1.vortex.proto.ProtoPType.I64, false));

            // Then
            assertThat(result).isEqualTo(DType.I64);
        }

        @Test
        void decimal() {
            // Given / When
            DType result = VariantEncodingDecoder.dtypeFromProto(
                    io.github.dfa1.vortex.proto.ProtoDType.ofDecimal(new io.github.dfa1.vortex.proto.ProtoDecimal(10, 2, false)));

            // Then precision/scale narrow to byte
            assertThat(result).isEqualTo(new DType.Decimal((byte) 10, (byte) 2, false));
        }

        @Test
        void utf8() {
            // Given / When
            DType result = VariantEncodingDecoder.dtypeFromProto(
                    io.github.dfa1.vortex.proto.ProtoDType.ofUtf8(new io.github.dfa1.vortex.proto.ProtoUtf8(true)));

            // Then
            assertThat(result).isEqualTo(new DType.Utf8(true));
        }

        @Test
        void binary() {
            // Given / When
            DType result = VariantEncodingDecoder.dtypeFromProto(
                    io.github.dfa1.vortex.proto.ProtoDType.ofBinary(new io.github.dfa1.vortex.proto.ProtoBinary(false)));

            // Then
            assertThat(result).isEqualTo(DType.BINARY);
        }

        @Test
        void struct() {
            // Given a two-field struct with mixed child types
            var proto = io.github.dfa1.vortex.proto.ProtoDType.ofStruct(new io.github.dfa1.vortex.proto.ProtoStruct(
                    List.of("a", "b"),
                    List.of(prim(io.github.dfa1.vortex.proto.ProtoPType.I32, false),
                            io.github.dfa1.vortex.proto.ProtoDType.ofUtf8(new io.github.dfa1.vortex.proto.ProtoUtf8(true))),
                    false));

            // When children are translated recursively
            DType result = VariantEncodingDecoder.dtypeFromProto(proto);

            // Then
            assertThat(result).isEqualTo(new DType.Struct(
                    List.of("a", "b"),
                    List.of(DType.I32, new DType.Utf8(true)),
                    false));
        }

        @Test
        void list() {
            // Given / When element type is translated recursively
            DType result = VariantEncodingDecoder.dtypeFromProto(
                    io.github.dfa1.vortex.proto.ProtoDType.ofList(new io.github.dfa1.vortex.proto.ProtoList(
                            prim(io.github.dfa1.vortex.proto.ProtoPType.I32, false), true)));

            // Then
            assertThat(result).isEqualTo(new DType.List(DType.I32, true));
        }

        @Test
        void fixedSizeList() {
            // Given / When
            DType result = VariantEncodingDecoder.dtypeFromProto(
                    io.github.dfa1.vortex.proto.ProtoDType.ofFixedSizeList(new io.github.dfa1.vortex.proto.ProtoFixedSizeList(
                            prim(io.github.dfa1.vortex.proto.ProtoPType.F64, false), 4, false)));

            // Then size is carried through
            assertThat(result).isEqualTo(
                    new DType.FixedSizeList(DType.F64, 4, false));
        }

        @Test
        void extension_withMetadata() {
            // Given an extension with non-null metadata bytes
            var proto = io.github.dfa1.vortex.proto.ProtoDType.ofExtension(new io.github.dfa1.vortex.proto.ProtoExtension(
                    "ip.address", prim(io.github.dfa1.vortex.proto.ProtoPType.I32, false), new byte[]{1, 2, 3}));

            // When
            DType result = VariantEncodingDecoder.dtypeFromProto(proto);

            // Then id, storage dtype, and metadata bytes are preserved
            assertThat(result).isInstanceOf(DType.Extension.class);
            DType.Extension ext = (DType.Extension) result;
            assertThat(ext.extensionId()).isEqualTo("ip.address");
            assertThat(ext.storageDType()).isEqualTo(DType.I32);
            assertThat(ext.metadata().byteSize()).isEqualTo(3);
        }

        @Test
        void extension_nullMetadata_becomesEmptyBuffer() {
            // Given null metadata — must not NPE, maps to an empty read-only buffer
            var proto = io.github.dfa1.vortex.proto.ProtoDType.ofExtension(new io.github.dfa1.vortex.proto.ProtoExtension(
                    "uuid", prim(io.github.dfa1.vortex.proto.ProtoPType.I64, false), null));

            // When
            DType.Extension result = (DType.Extension) VariantEncodingDecoder.dtypeFromProto(proto);

            // Then
            assertThat(result.metadata().byteSize()).isZero();
        }

        @Test
        void variant() {
            // Given / When
            DType result = VariantEncodingDecoder.dtypeFromProto(
                    io.github.dfa1.vortex.proto.ProtoDType.ofVariant(new io.github.dfa1.vortex.proto.ProtoVariant(false)));

            // Then
            assertThat(result).isEqualTo(DType.VARIANT);
        }

        @Test
        void noFieldSet_throws() {
            // Given a proto DType with no oneof arm populated
            var empty = new io.github.dfa1.vortex.proto.ProtoDType(
                    null, null, null, null, null, null, null, null, null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> VariantEncodingDecoder.dtypeFromProto(empty))
                    .hasMessageContaining("unsupported proto DType");
        }
    }
}
