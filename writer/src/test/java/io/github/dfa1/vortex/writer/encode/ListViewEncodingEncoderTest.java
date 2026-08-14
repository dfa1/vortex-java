package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.ListViewArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.reader.decode.BoolEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestDecodeContexts;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.proto.ProtoListViewMetadata;
import io.github.dfa1.vortex.reader.decode.ListViewEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListViewEncodingEncoderTest {

    private static final ListViewEncodingEncoder ENCODER = new ListViewEncodingEncoder();
    private static final ListViewEncodingDecoder DECODER = new ListViewEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            DECODER, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());

    private static ArrayNode toArrayNode(EncodeNode node) {
        ArrayNode[] children = new ArrayNode[node.children().length];
        for (int i = 0; i < children.length; i++) {
            children[i] = toArrayNode(node.children()[i]);
        }
        return new ArrayNode(node.encodingId(), node.metadata(), children, node.bufferIndices());
    }

    @Nested
    class Encode {

        @Test
        void accepts_listDtype_true() {
            // Given / When / Then
            assertThat(ENCODER.accepts(DTypes.LIST_I32)).isTrue();
        }

        @Test
        void accepts_primitiveDtype_false() {
            // Given / When / Then
            assertThat(ENCODER.accepts(DTypes.I32)).isFalse();
        }

        @Test
        void encode_producesThreeChildren_noBuffers() {
            // Given
            int[] elements = {1, 2, 3, 4, 5};
            int[] offsets = {0, 2};
            int[] sizes = {2, 3};
            ListViewData data = new ListViewData(elements, offsets, sizes, 2);

            // When
            EncodeResult result = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_LISTVIEW);
            assertThat(result.rootNode().bufferIndices()).isEmpty();
            assertThat(result.rootNode().children()).hasSize(3);
        }

        @Test
        void encode_nullableList_addsValidityAsFourthChild() {
            // Given a nullable list column — its validity belongs in the list-view's own fourth
            // child slot, not in a vortex.masked wrapper, matching the Rust reference
            int[] elements = {1, 2, 3};
            ListViewData values = new ListViewData(elements, new int[]{0, 2}, new int[]{2, 1}, 2);
            NullableData data = new NullableData(values, new boolean[]{true, false});

            // When
            EncodeResult result = ENCODER.encode(
                    DTypes.LIST_I32.asNullable(), data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_LISTVIEW);
            assertThat(result.rootNode().children()).hasSize(4);
        }

        @Test
        void encode_nullableElementType_wrapsElementsInMasked() {
            // Given a list of nullable elements: the elements payload arrives as NullableData,
            // which only the masked encoder understands. Until now this branch was covered solely
            // by the cross-language integration test (a map's nullable values), which surefire
            // never runs — a plain `./mvnw test` would not have caught a regression here.
            NullableData elements = new NullableData(new int[]{1, 2, 3}, new boolean[]{true, false, true});
            ListViewData data = new ListViewData(elements, new int[]{0, 2}, new int[]{2, 1}, 2);

            // When
            EncodeResult result = ENCODER.encode(
                    new DType.List(DTypes.I32.asNullable(), false), data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.rootNode().children()).hasSize(3);
            assertThat(result.rootNode().children()[0].encodingId()).isEqualTo(EncodingId.VORTEX_MASKED);
            assertThat(result.rootNode().children()[0].children()).hasSize(2);
        }

        @Test
        void encode_nullableElementType_metadataCountsEveryElement() throws Exception {
            // Given the same shape — elementCount() must read the NullableData's validity length,
            // not reflect over it as an array, or the elements_len metadata would be wrong
            NullableData elements = new NullableData(new int[]{1, 2, 3}, new boolean[]{true, false, true});
            ListViewData data = new ListViewData(elements, new int[]{0, 2}, new int[]{2, 1}, 2);

            // When
            EncodeResult encoded = ENCODER.encode(
                    new DType.List(DTypes.I32.asNullable(), false), data, EncodeTestHelper.testCtx());
            MemorySegment metaSeg = encoded.rootNode().metadata();
            ProtoListViewMetadata result = ProtoListViewMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            // Then
            assertThat(result.elements_len()).isEqualTo(3);
        }

        @Test
        void encode_wrongDataShape_throws() {
            // Given a raw array where a ListViewData is required
            EncodeContext ctx = EncodeTestHelper.testCtx();

            // When / Then
            assertThatThrownBy(() -> ENCODER.encode(DTypes.LIST_I32, new int[]{1, 2}, ctx))
                    .hasMessageContaining("expected ListViewData");
        }
    }

    @Nested
    class Decode {

        @Test
        void roundTrip_i32Elements_preservesValues() {
            // Given
            int[] elements = {10, 20, 30, 40, 50};
            int[] offsets = {0, 2, 3};
            int[] sizes = {2, 1, 2};
            ListViewData data = new ListViewData(elements, offsets, sizes, 3);

            // When
            EncodeResult resultEncoded = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = resultEncoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(resultEncoded.rootNode()), DTypes.LIST_I32, 3, bufs, REGISTRY, Arena.global());
            ListViewArray resultDecoded = (ListViewArray) DECODER.decode(ctx);

            // Then
            assertThat(resultDecoded.length()).isEqualTo(3);
            IntArray decodedElems = (IntArray) resultDecoded.elements();
            assertThat(decodedElems.length()).isEqualTo(5);
            for (int i = 0; i < elements.length; i++) {
                assertThat(decodedElems.getInt(i)).isEqualTo(elements[i]);
            }
            IntArray decodedOffsets = (IntArray) resultDecoded.offsets();
            assertThat(decodedOffsets.length()).isEqualTo(3);
            IntArray decodedSizes = (IntArray) resultDecoded.sizes();
            assertThat(decodedSizes.length()).isEqualTo(3);
        }

        @Test
        void roundTrip_emptyLists_preservesZeroSizes() {
            // Given
            int[] elements = {};
            int[] offsets = {0, 0};
            int[] sizes = {0, 0};
            ListViewData data = new ListViewData(elements, offsets, sizes, 2);

            // When
            EncodeResult resultEncoded = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = resultEncoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(resultEncoded.rootNode()), DTypes.LIST_I32, 2, bufs, REGISTRY, Arena.global());
            ListViewArray resultDecoded = (ListViewArray) DECODER.decode(ctx);

            // Then
            assertThat(resultDecoded.length()).isEqualTo(2);
            assertThat(resultDecoded.elements().length()).isZero();
            assertThat(resultDecoded.offsets().length()).isEqualTo(2);
            assertThat(resultDecoded.sizes().length()).isEqualTo(2);
        }

        @Test
        void roundTrip_singleList_preservesValues() {
            // Given
            int[] elements = {7, 8, 9};
            int[] offsets = {0};
            int[] sizes = {3};
            ListViewData data = new ListViewData(elements, offsets, sizes, 1);

            // When
            EncodeResult resultEncoded = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = resultEncoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(resultEncoded.rootNode()), DTypes.LIST_I32, 1, bufs, REGISTRY, Arena.global());
            ListViewArray resultDecoded = (ListViewArray) DECODER.decode(ctx);

            // Then
            assertThat(resultDecoded.length()).isEqualTo(1);
            IntArray decodedElems = (IntArray) resultDecoded.elements();
            assertThat(decodedElems.length()).isEqualTo(3);
            for (int i = 0; i < elements.length; i++) {
                assertThat(decodedElems.getInt(i)).isEqualTo(elements[i]);
            }
        }

        @Test
        void roundTrip_nullableList_preservesValidity() {
            // Given a two-row nullable list column whose second row is null — the null row still
            // occupies an offset/size slot, so only the validity child distinguishes it
            int[] elements = {10, 20, 30};
            ListViewData values = new ListViewData(elements, new int[]{0, 2}, new int[]{2, 1}, 2);
            NullableData data = new NullableData(values, new boolean[]{true, false});
            DType nullableList = DTypes.LIST_I32.asNullable();

            // When
            EncodeResult encoded = ENCODER.encode(nullableList, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = encoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(encoded.rootNode()), nullableList, 2, bufs, REGISTRY, Arena.global());
            MaskedArray result = (MaskedArray) DECODER.decode(ctx);

            // Then
            assertThat(result.dtype()).isEqualTo(nullableList);
            assertThat(result.isValid(0)).isTrue();
            assertThat(result.isValid(1)).isFalse();
            assertThat(((ListViewArray) result.inner()).elements().length()).isEqualTo(3);
        }

        @Test
        void decode_wrongDtype_throws() {
            // Given
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_LISTVIEW, null,
                    new ArrayNode[0], new int[0]);
            DecodeContext ctx = TestDecodeContexts.of(node, DTypes.I32).registry(REGISTRY).build();

            // When / Then
            assertThatThrownBy(() -> DECODER.decode(ctx)).hasMessageContaining("DType.List");
        }

        @Test
        void decode_wrongChildCount_throws() {
            // Given
            ArrayNode child = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[0]);
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_LISTVIEW,
                    MemorySegment.ofArray(new byte[0]),
                    new ArrayNode[]{child}, new int[0]);
            DecodeContext ctx = TestDecodeContexts.of(node, DTypes.LIST_I32).registry(REGISTRY).build();

            // When / Then
            assertThatThrownBy(() -> DECODER.decode(ctx)).hasMessageContaining("expected 3 or 4 children");
        }
    }

    @Nested
    class Metadata {

        @Test
        void encode_metadata_elementsLen_matchesElementCount() throws Exception {
            // Given
            int[] elements = {1, 2, 3, 4, 5};
            int[] offsets = {0, 2};
            int[] sizes = {2, 3};
            ListViewData data = new ListViewData(elements, offsets, sizes, 2);

            // When
            EncodeResult result = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());
            java.lang.foreign.MemorySegment metaSeg = result.rootNode().metadata();
            ProtoListViewMetadata meta = ProtoListViewMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            // Then
            assertThat(meta.elements_len()).isEqualTo(5);
        }
    }
}
