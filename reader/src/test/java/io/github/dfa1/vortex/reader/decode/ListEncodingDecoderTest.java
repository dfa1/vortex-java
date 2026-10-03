package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoListMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ListArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `vortex.list`'s optional third (validity) child. Rust stores a nullable list's validity there,
/// not under a `vortex.masked` wrapper; the decoder used to read only elements and offsets, so
/// every null row of a vortex-jni-written list column came back as an empty list
/// (`RustListsInteropIntegrationTest` covers the end-to-end case).
class ListEncodingDecoderTest {

    private static final DType.List LIST_I32 = new DType.List(DType.I32, false);

    private final ListEncodingDecoder sut = new ListEncodingDecoder();

    private final ReadRegistry registry = TestRegistry.ofDecoders(
            new ListEncodingDecoder(), new PrimitiveEncodingDecoder(),
            new BoolEncodingDecoder(), new NullEncodingDecoder());

    @Test
    void decode_nullableWithValidityChild_consultsIt() {
        // Given a nullable list whose third child is a vortex.null — a child the decoder only
        // notices if it actually reads the validity slot, which it previously never did
        DecodeContext ctx = TestDecodeContexts.of(nodeWithChildren(3), LIST_I32.asNullable())
                                              .rowCount(2).registry(registry).build();

        // When / Then
        assertThatThrownBy(() -> sut.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("validity child decoded to unexpected type: NullArray");
    }

    @Test
    void decode_validityChildOnNonNullableDtype_ignoresIt() {
        // Given a three-child node under a dtype declared non-nullable: as for vortex.listview,
        // the declared nullability is authoritative and the extra child is not consulted
        DecodeContext ctx = TestDecodeContexts.of(nodeWithChildren(3), LIST_I32)
                                              .rowCount(2).registry(registry).build();

        // When
        Array result = sut.decode(ctx);

        // Then
        assertThat(result).isInstanceOf(ListArray.class);
        assertThat(result.dtype()).isEqualTo(LIST_I32);
    }

    @Test
    void decode_nullableWithoutValidityChild_isAllValid() {
        // Given a nullable list with only elements and offsets: Rust derives an all-valid
        // validity from the dtype when the third child is absent
        DecodeContext ctx = TestDecodeContexts.of(nodeWithChildren(2), LIST_I32.asNullable())
                                              .rowCount(2).registry(registry).build();

        // When
        Array result = sut.decode(ctx);

        // Then
        assertThat(result).isInstanceOf(ListArray.class);
    }

    /// A list node whose children are all `vortex.null`: enough for the validity-slot guards,
    /// which fire before any buffer is read.
    private static ArrayNode nodeWithChildren(int count) {
        ArrayNode[] children = new ArrayNode[count];
        for (int i = 0; i < count; i++) {
            children[i] = new ArrayNode(EncodingId.VORTEX_NULL, null, new ArrayNode[0], new int[0]);
        }
        byte[] meta = new ProtoListMetadata(0L, ProtoPType.fromValue(PType.I32.ordinal())).encode();
        return new ArrayNode(EncodingId.VORTEX_LIST, MemorySegment.ofArray(meta), children, new int[0]);
    }
}
