package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoPrimitive;
import io.github.dfa1.vortex.core.proto.ProtoScalar;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoVariantMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariantEncodingEncoderTest {

    private static final VariantEncodingEncoder SUT = new VariantEncodingEncoder();
    private static final DType.Variant VARIANT = DType.VARIANT;

    private static ProtoScalar i32Scalar(long value) {
        // Inner typed scalar carrying its own i32 dtype, wrapped as a variant value
        // (mirrors Rust ProtoScalar::variant(ProtoScalar::primitive(value))).
        return new ProtoScalar(
                io.github.dfa1.vortex.core.proto.ProtoDType.ofPrimitive(
                        new ProtoPrimitive(io.github.dfa1.vortex.core.proto.ProtoPType.I32, false)),
                ProtoScalarValue.ofInt64Value(value));
    }

    private static long innerInt(MemorySegment buf) throws Exception {
        ProtoScalarValue scalar = ProtoScalarValue.decode(buf, 0, buf.byteSize());
        assertThat(scalar.variant_value()).isNotNull();
        return scalar.variant_value().value().int64_value();
    }

    @Nested
    class Accepts {

        @Test
        void trueForVariant_falseForPrimitive() {
            assertThat(SUT.accepts(VARIANT)).isTrue();
            assertThat(SUT.accepts(new DType.Primitive(io.github.dfa1.vortex.core.model.PType.I64, false))).isFalse();
        }
    }

    @Nested
    class ConstantColumn {

        @Test
        void allEqual_emitsSingleConstantChild() throws Exception {
            // Given a column whose rows are all the same value
            VariantData data = VariantData.constant(5, i32Scalar(7L));

            // When
            EncodeResult result = SUT.encode(VARIANT, data, EncodeTestHelper.testCtx());

            // Then — container holds exactly one buffer-backed constant child, no chunked layer.
            EncodeNode root = result.rootNode();
            assertThat(root.encodingId()).isEqualTo(EncodingId.VORTEX_VARIANT);
            assertThat(root.bufferIndices()).isEmpty();
            assertThat(root.children()).hasSize(1);

            EncodeNode child = root.children()[0];
            assertThat(child.encodingId()).isEqualTo(EncodingId.VORTEX_CONSTANT);
            assertThat(child.bufferIndices()).containsExactly(0);
            assertThat(result.buffers()).hasSize(1);
            assertThat(innerInt(result.buffers().get(0))).isEqualTo(7L);
        }

        @Test
        void metadataHasNoShreddedDtype() throws Exception {
            EncodeResult result = SUT.encode(VARIANT, VariantData.constant(3, i32Scalar(7L)), EncodeTestHelper.testCtx());

            MemorySegment meta = result.rootNode().metadata();
            ProtoVariantMetadata decoded = ProtoVariantMetadata.decode(meta, 0, meta.byteSize());
            assertThat(decoded.shredded_dtype()).isNull();
        }
    }

    @Nested
    class VaryingColumn {

        @Test
        void distinctValues_emitChunkedOfConstants() throws Exception {
            // Given three distinct per-row values
            VariantData data = new VariantData(List.of(i32Scalar(7L), i32Scalar(8L), i32Scalar(9L)));

            // When
            EncodeResult result = SUT.encode(VARIANT, data, EncodeTestHelper.testCtx());

            // Then — container wraps a chunked node: child 0 is the offsets, then one constant per run.
            EncodeNode chunked = result.rootNode().children()[0];
            assertThat(chunked.encodingId()).isEqualTo(EncodingId.VORTEX_CHUNKED);
            assertThat(chunked.children()).hasSize(4);
            assertThat(chunked.children()[0].encodingId()).isEqualTo(EncodingId.VORTEX_PRIMITIVE);
            for (int i = 1; i <= 3; i++) {
                assertThat(chunked.children()[i].encodingId()).isEqualTo(EncodingId.VORTEX_CONSTANT);
            }
            // offsets buffer (index 0) + one buffer per constant = 4 total
            assertThat(result.buffers()).hasSize(4);
        }

        @Test
        void distinctValues_offsetsAreCumulativeRunLengths() {
            // Given one row per distinct value: run offsets must be 0,1,2,3
            VariantData data = new VariantData(List.of(i32Scalar(7L), i32Scalar(8L), i32Scalar(9L)));

            EncodeResult result = SUT.encode(VARIANT, data, EncodeTestHelper.testCtx());

            MemorySegment offsets = result.buffers().get(0);
            var bb = offsets.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            assertThat(bb.getLong(0)).isZero();
            assertThat(bb.getLong(8)).isEqualTo(1L);
            assertThat(bb.getLong(16)).isEqualTo(2L);
            assertThat(bb.getLong(24)).isEqualTo(3L);
        }

        @Test
        void adjacentEqualValues_coalesceIntoOneRun() throws Exception {
            // Given [7,7,8]: two runs (7 length 2, 8 length 1) → offsets 0,2,3
            VariantData data = new VariantData(List.of(i32Scalar(7L), i32Scalar(7L), i32Scalar(8L)));

            EncodeResult result = SUT.encode(VARIANT, data, EncodeTestHelper.testCtx());

            EncodeNode chunked = result.rootNode().children()[0];
            assertThat(chunked.children()).hasSize(3); // offsets + 2 constants
            assertThat(result.buffers()).hasSize(3);

            MemorySegment offsets = result.buffers().get(0);
            var bb = offsets.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            assertThat(bb.getLong(0)).isZero();
            assertThat(bb.getLong(8)).isEqualTo(2L);
            assertThat(bb.getLong(16)).isEqualTo(3L);
            // run values preserved in order
            assertThat(innerInt(result.buffers().get(1))).isEqualTo(7L);
            assertThat(innerInt(result.buffers().get(2))).isEqualTo(8L);
        }
    }

    @Nested
    class RoundTrip {

        private static final io.github.dfa1.vortex.reader.ReadRegistry REGISTRY =
                io.github.dfa1.vortex.reader.decode.TestRegistry.ofDecoders(
                        new io.github.dfa1.vortex.reader.decode.VariantEncodingDecoder(),
                        new io.github.dfa1.vortex.reader.decode.ConstantEncodingDecoder(),
                        new io.github.dfa1.vortex.reader.decode.ChunkedEncodingDecoder(),
                        new io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder());

        private static io.github.dfa1.vortex.reader.decode.ArrayNode toArrayNode(EncodeNode node) {
            io.github.dfa1.vortex.reader.decode.ArrayNode[] children =
                    new io.github.dfa1.vortex.reader.decode.ArrayNode[node.children().length];
            for (int i = 0; i < children.length; i++) {
                children[i] = toArrayNode(node.children()[i]);
            }
            return new io.github.dfa1.vortex.reader.decode.ArrayNode(
                    node.encodingId(), node.metadata(), children, node.bufferIndices());
        }

        private static io.github.dfa1.vortex.reader.array.VariantArray decode(EncodeResult result, long rows) {
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            var ctx = new io.github.dfa1.vortex.reader.decode.DecodeContext(
                    toArrayNode(result.rootNode()), VARIANT, rows, bufs, REGISTRY, java.lang.foreign.Arena.global());
            return (io.github.dfa1.vortex.reader.array.VariantArray) new io.github.dfa1.vortex.reader.decode.VariantEncodingDecoder().decode(ctx);
        }

        @Test
        void constantColumn_decodesToBroadcastInnerValues() {
            // Given/When a constant column is encoded then decoded back
            var result = SUT.encode(VARIANT, VariantData.constant(4, i32Scalar(7L)), EncodeTestHelper.testCtx());
            var variant = decode(result, 4);

            // Then core storage is the inner i32 value broadcast to every row
            assertThat(variant.length()).isEqualTo(4);
            assertThat(variant.shredded()).isNull();
            var core = (io.github.dfa1.vortex.reader.array.IntArray) variant.coreStorage();
            assertThat(core.dtype()).isEqualTo(new DType.Primitive(io.github.dfa1.vortex.core.model.PType.I32, false));
            for (long i = 0; i < 4; i++) {
                assertThat(core.getInt(i)).isEqualTo(7);
            }
        }

        @Test
        void varyingColumn_decodesPerRowValuesInOrder() {
            // Given/When distinct per-row values are encoded (chunked) then decoded back
            var data = new VariantData(List.of(i32Scalar(10L), i32Scalar(20L), i32Scalar(30L)));
            var variant = decode(SUT.encode(VARIANT, data, EncodeTestHelper.testCtx()), 3);

            // Then the chunked core storage yields each row's inner value in order
            var core = (io.github.dfa1.vortex.reader.array.IntArray) variant.coreStorage();
            assertThat(core.getInt(0)).isEqualTo(10);
            assertThat(core.getInt(1)).isEqualTo(20);
            assertThat(core.getInt(2)).isEqualTo(30);
        }

        @Test
        void shreddedColumn_decodesShreddedTypedChild() {
            // Given/When a column with a shredded i32 projection is encoded then decoded
            DType i32 = new DType.Primitive(io.github.dfa1.vortex.core.model.PType.I32, false);
            var data = VariantData.shredded(
                    List.of(i32Scalar(10L), i32Scalar(20L), i32Scalar(30L)), new int[]{10, 20, 30}, i32);
            var variant = decode(SUT.encode(VARIANT, data, EncodeTestHelper.testCtx()), 3);

            // Then the shredded child decodes as the typed column
            assertThat(variant.shredded()).isNotNull();
            var shredded = (io.github.dfa1.vortex.reader.array.IntArray) variant.shredded();
            assertThat(shredded.dtype()).isEqualTo(i32);
            assertThat(shredded.getInt(0)).isEqualTo(10);
            assertThat(shredded.getInt(1)).isEqualTo(20);
            assertThat(shredded.getInt(2)).isEqualTo(30);
        }
    }

    @Nested
    class Shredded {

        @Test
        void emitsSecondChildAndRecordsShreddedDtype() throws Exception {
            // Given a column with a shredded i32 projection
            DType i32 = new DType.Primitive(io.github.dfa1.vortex.core.model.PType.I32, false);
            var data = VariantData.shredded(
                    List.of(i32Scalar(10L), i32Scalar(20L), i32Scalar(30L)), new int[]{10, 20, 30}, i32);

            // When
            EncodeResult result = SUT.encode(VARIANT, data, EncodeTestHelper.testCtx());

            // Then the container has a second (shredded) child encoded as a primitive array...
            EncodeNode root = result.rootNode();
            assertThat(root.children()).hasSize(2);
            assertThat(root.children()[1].encodingId()).isEqualTo(EncodingId.VORTEX_PRIMITIVE);

            // ...and the metadata records shredded_dtype = i32.
            MemorySegment meta = root.metadata();
            ProtoVariantMetadata vm = ProtoVariantMetadata.decode(meta, 0, meta.byteSize());
            assertThat(vm.shredded_dtype()).isNotNull();
            assertThat(vm.shredded_dtype().primitive()).isNotNull();
            assertThat(vm.shredded_dtype().primitive().type()).isEqualTo(io.github.dfa1.vortex.core.proto.ProtoPType.I32);
        }

        @Test
        void halfSpecifiedShredded_throws() {
            assertThatThrownBy(() -> VariantData.shredded(List.of(i32Scalar(1L)), null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new VariantData(List.of(i32Scalar(1L)), new int[]{1}, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("both set or both null");
        }
    }

    @Nested
    class Errors {

        @Test
        void wrongDtype_throws() {
            VariantData data = VariantData.constant(1, i32Scalar(1L));
            assertThatThrownBy(() -> SUT.encode(
                    new DType.Primitive(io.github.dfa1.vortex.core.model.PType.I64, false), data, EncodeTestHelper.testCtx()))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("Variant dtype");
        }

        @Test
        void wrongDataType_throws() {
            assertThatThrownBy(() -> SUT.encode(VARIANT, new long[]{1L}, EncodeTestHelper.testCtx()))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("VariantData");
        }
    }

    @Nested
    class Validation {

        @Test
        void emptyValues_throws() {
            assertThatThrownBy(() -> new VariantData(List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        void constant_nonPositiveLength_throws() {
            assertThatThrownBy(() -> VariantData.constant(0, i32Scalar(1L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("length");
        }

        @Test
        void constant_nullValue_throws() {
            assertThatThrownBy(() -> VariantData.constant(1, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("value");
        }
    }
}
