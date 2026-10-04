package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoDType;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.core.proto.ProtoParquetVariantMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPrimitive;
import io.github.dfa1.vortex.core.proto.ProtoVarBinMetadata;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Child shapes mirror Rust's `ParquetVariant::deserialize`: `[validity?, metadata, value?,
/// typed_value?]`. The interop test covers what vortex-jni writes; these cover the optional
/// children Rust's writer does not emit on its own (`typed_value` stays in the canonical
/// container there) and every malformed shape Rust rejects.
class ParquetVariantEncodingDecoderTest {

    private static final ParquetVariantEncodingDecoder SUT = new ParquetVariantEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            SUT, new PrimitiveEncodingDecoder(), new VarBinEncodingDecoder(), new BoolEncodingDecoder());
    private static final int N = 2;

    // Segments: 0 = metadata bytes, 1 = metadata offsets, 2 = value bytes, 3 = value offsets,
    // 4 = typed_value ints, 5 = validity bits.
    private static final MemorySegment[] SEGMENTS = {
            MemorySegment.ofArray(new byte[]{0x01, 0x00, 0x01, 0x00}), TestSegments.leLongs(0, 2, 4),
            MemorySegment.ofArray(new byte[]{0x0c, 0x2a, 0x0c, 0x07}), TestSegments.leLongs(0, 2, 4),
            TestSegments.leInts(10, 20),
            MemorySegment.ofArray(new byte[]{0b01}),
    };

    @Test
    void encodingId_isParquetVariant() {
        // When
        EncodingId result = SUT.encodingId();

        // Then
        assertThat(result).isEqualTo(EncodingId.VORTEX_PARQUET_VARIANT);
    }

    @Test
    void metadataAndValue_decodeToStructOfBinaries() {
        // Given
        ArrayNode node = parquetVariant(meta(true, null, false), metadataNode(), valueNode());

        // When
        Array result = SUT.decode(ctx(node));

        // Then
        StructArray struct = (StructArray) result;
        assertThat(((DType.Struct) struct.dtype()).fieldNames()).extracting(Object::toString).containsExactly("metadata", "value");
        assertThat(((VarBinArray) struct.field(0)).getBytes(1)).containsExactly(0x01, 0x00);
        assertThat(((VarBinArray) struct.field(1)).getBytes(0)).containsExactly(0x0c, 0x2a);
        assertThat(((VarBinArray) struct.field(1)).getBytes(1)).containsExactly(0x0c, 0x07);
    }

    @Test
    void typedValueOnly_decodesTheShreddedChildAtItsDeclaredDtype() {
        // Given — no `value`: every row is fully shredded into an I32 `typed_value`
        ProtoDType i32 = ProtoDType.ofPrimitive(new ProtoPrimitive(ProtoPType.I32, false));
        ArrayNode node = parquetVariant(meta(false, i32, false), metadataNode(), primitiveNode(4));

        // When
        Array result = SUT.decode(ctx(node));

        // Then
        StructArray struct = (StructArray) result;
        assertThat(((DType.Struct) struct.dtype()).fieldNames()).extracting(Object::toString).containsExactly("metadata", "typed_value");
        assertThat(((DType.Struct) struct.dtype()).fieldTypes().get(1)).isEqualTo(new DType.Primitive(PType.I32, false));
        assertThat(((IntArray) struct.field(1)).getInt(1)).isEqualTo(20);
    }

    @Test
    void leadingValidityChild_masksTheStruct() {
        // Given — one extra child beyond the expected count is the row validity, first
        ArrayNode validity = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{5});
        ArrayNode node = parquetVariant(meta(true, null, false), validity, metadataNode(), valueNode());

        // When
        Array result = SUT.decode(ctx(node));

        // Then
        MaskedArray masked = (MaskedArray) result;
        assertThat(masked.isValid(0)).isTrue();
        assertThat(masked.isValid(1)).isFalse();
        assertThat(masked.inner()).isInstanceOf(StructArray.class);
    }

    @Nested
    class Malformed {

        @Test
        void nonVariantDtype_throws() {
            // Given
            ArrayNode node = parquetVariant(meta(true, null, false), metadataNode(), valueNode());
            DecodeContext ctx = new DecodeContext(node, DType.BINARY, N, SEGMENTS, REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("expected variant dtype");
        }

        @Test
        void neitherValueNorTypedValue_throws() {
            // Given — empty metadata decodes as all-defaults: no value, no typed_value
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_PARQUET_VARIANT, null,
                    new ArrayNode[]{metadataNode()}, new int[0]);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx(node)))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("at least one of value or typed_value");
        }

        @Test
        void buffers_throw() {
            // Given
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_PARQUET_VARIANT, meta(true, null, false),
                    new ArrayNode[]{metadataNode(), valueNode()}, new int[]{0});

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx(node)))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("expected 0 buffers");
        }

        @Test
        void tooFewChildren_throws() {
            // Given — metadata declares `value`, but only the metadata child is present
            ArrayNode node = parquetVariant(meta(true, null, false), metadataNode());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx(node)))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("expected 2 or 3 children, got 1");
        }

        @Test
        void tooManyChildren_throws() {
            // Given
            ArrayNode node = parquetVariant(meta(true, null, false),
                    metadataNode(), valueNode(), valueNode(), valueNode());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx(node)))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("expected 2 or 3 children, got 4");
        }

        @Test
        void typedValueDtypeWithUnknownPType_throwsVortexException() {
            // Given — has_value=true, typed_value_dtype = Primitive{type: 99}: hand-encoded because
            // the generated enum cannot name an out-of-range PType. Parsed off the wire, it must not
            // leak an index or argument exception from the PType lookup.
            MemorySegment meta = MemorySegment.ofArray(new byte[]{
                    0x08, 0x01,             // has_value = true
                    0x12, 0x04,             // typed_value_dtype, 4 bytes
                    0x1a, 0x02,             //   DType.primitive, 2 bytes
                    0x08, 0x63});           //     Primitive.type = 99
            ArrayNode node = parquetVariant(meta, metadataNode(), valueNode(), primitiveNode(4));

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx(node))).isInstanceOf(VortexException.class);
        }

        @Test
        void truncatedMetadata_throws() {
            // Given — a length-delimited field (tag 2) claiming more bytes than follow
            MemorySegment meta = MemorySegment.ofArray(new byte[]{0x12, 0x7f});
            ArrayNode node = parquetVariant(meta, metadataNode(), valueNode());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx(node)))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("invalid metadata");
        }
    }

    private static DecodeContext ctx(ArrayNode node) {
        return new DecodeContext(node, DType.VARIANT, N, SEGMENTS, REGISTRY, Arena.ofAuto());
    }

    private static MemorySegment meta(boolean hasValue, ProtoDType typedValueDtype, boolean valueNullable) {
        return MemorySegment.ofArray(new ProtoParquetVariantMetadata(hasValue, typedValueDtype, valueNullable).encode());
    }

    private static ArrayNode parquetVariant(MemorySegment meta, ArrayNode... children) {
        return new ArrayNode(EncodingId.VORTEX_PARQUET_VARIANT, meta, children, new int[0]);
    }

    private static ArrayNode metadataNode() {
        return varBinNode(0, 1);
    }

    private static ArrayNode valueNode() {
        return varBinNode(2, 3);
    }

    private static ArrayNode varBinNode(int bytesSegment, int offsetsSegment) {
        MemorySegment varBinMeta = MemorySegment.ofArray(new ProtoVarBinMetadata(ProtoPType.I64).encode());
        return new ArrayNode(EncodingId.VORTEX_VARBIN, varBinMeta,
                new ArrayNode[]{primitiveNode(offsetsSegment)}, new int[]{bytesSegment});
    }

    private static ArrayNode primitiveNode(int segment) {
        return new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{segment});
    }
}
