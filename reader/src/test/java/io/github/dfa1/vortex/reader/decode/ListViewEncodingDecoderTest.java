package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoListViewMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Adversarial coverage for `vortex.listview`'s fourth (validity) child slot, the one shape the
/// round-trip tests in the writer module cannot produce: those always encode a well-formed node,
/// while these inputs only ever arrive crafted or corrupt.
class ListViewEncodingDecoderTest {

    private static final DType.List LIST_I32 = new DType.List(DType.I32, false);

    private final ListViewEncodingDecoder sut = new ListViewEncodingDecoder();

    private final ReadRegistry registry = TestRegistry.ofDecoders(
            new ListViewEncodingDecoder(), new PrimitiveEncodingDecoder(),
            new BoolEncodingDecoder(), new NullEncodingDecoder());

    @Test
    void encodingId_isVortexListView() {
        // Given / When
        EncodingId result = sut.encodingId();

        // Then
        assertThat(result).isEqualTo(EncodingId.VORTEX_LISTVIEW);
    }

    @Test
    void decode_validityChildOnNonNullableDtype_throws() {
        // Given a four-child node — i.e. one carrying validity — under a dtype the file itself
        // declares non-nullable. Decoding it would hand back an array reporting nullable=true
        // while the column's declared dtype says otherwise, so a consumer trusting that dtype
        // would read the null rows' placeholder slots as real values: a silent wrong answer.
        DecodeContext ctx = TestDecodeContexts.of(nodeWithChildren(4), LIST_I32)
                                              .rowCount(2).registry(registry).build();

        // When / Then
        assertThatThrownBy(() -> sut.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("validity child present but the declared dtype is non-nullable");
    }

    @Test
    void decode_validityChildOfNonBoolEncoding_throws() {
        // Given a nullable list-view whose validity slot holds a vortex.null child: it decodes
        // fine on its own but yields a NullArray, not the BoolArray a validity bitmap must be
        DecodeContext ctx = TestDecodeContexts.of(nodeWithChildren(4), LIST_I32.asNullable())
                                              .rowCount(2).registry(registry).build();

        // When / Then
        assertThatThrownBy(() -> sut.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("validity child decoded to unexpected type: NullArray");
    }

    /// Builds a list-view node whose children are all `vortex.null` — enough for the guards under
    /// test, which fire before any buffer is read. Elements/offsets/sizes decode to zero-length
    /// all-null children, and a fourth child (when present) lands in the validity slot.
    private static ArrayNode nodeWithChildren(int count) {
        ArrayNode[] children = new ArrayNode[count];
        for (int i = 0; i < count; i++) {
            children[i] = new ArrayNode(EncodingId.VORTEX_NULL, null, new ArrayNode[0], new int[0]);
        }
        byte[] meta = new ProtoListViewMetadata(
                0L,
                ProtoPType.fromValue(PType.I32.ordinal()),
                ProtoPType.fromValue(PType.I32.ordinal())).encode();
        return new ArrayNode(EncodingId.VORTEX_LISTVIEW, MemorySegment.ofArray(meta), children, new int[0]);
    }
}
