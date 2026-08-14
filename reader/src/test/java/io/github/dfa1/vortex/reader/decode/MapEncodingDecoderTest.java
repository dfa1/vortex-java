package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ListViewArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapEncodingDecoderTest {

    private static final DType.Map MAP_UTF8_I64 = new DType.Map(DType.UTF8, DType.I64, false, false);

    private final MapEncodingDecoder sut = new MapEncodingDecoder();

    private final ReadRegistry registry = TestRegistry.ofDecoders(
            new MapEncodingDecoder(), new ListViewEncodingDecoder(), new StructEncodingDecoder(),
            new PrimitiveEncodingDecoder(), new VarBinEncodingDecoder());

    @Test
    void encodingId_isVortexMap() {
        // Given / When
        EncodingId result = sut.encodingId();

        // Then
        assertThat(result).isEqualTo(EncodingId.VORTEX_MAP);
    }

    @Test
    void decode_nonMapDtype_throws() {
        // Given a map node handed a list dtype
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_MAP, null, new ArrayNode[0], new int[0]);
        DecodeContext ctx = TestDecodeContexts.of(node, new DType.List(DType.I32, false))
                                              .registry(registry).build();

        // When / Then
        assertThatThrownBy(() -> sut.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("expected DType.Map");
    }

    @Test
    void decode_zeroChildren_throws() {
        // Given a map node with no entries child at all
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_MAP, null, new ArrayNode[0], new int[0]);
        DecodeContext ctx = TestDecodeContexts.of(node, MAP_UTF8_I64).registry(registry).build();

        // When / Then
        assertThatThrownBy(() -> sut.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("expected 1 child, got 0");
    }

    @Test
    void decode_twoChildren_throws() {
        // Given a map node with a second, spurious child — the spec allows exactly one
        ArrayNode child = new ArrayNode(EncodingId.VORTEX_LISTVIEW, null, new ArrayNode[0], new int[0]);
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_MAP, null,
                new ArrayNode[]{child, child}, new int[0]);
        DecodeContext ctx = TestDecodeContexts.of(node, MAP_UTF8_I64).registry(registry).build();

        // When / Then
        assertThatThrownBy(() -> sut.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("expected 1 child, got 2");
    }

    @Test
    void decode_entriesChildNotAListView_throws() {
        // Given an entries child claiming a flat primitive encoding — the Rust reference requires
        // the entries child to be a bare vortex.listview and rejects anything else by encoding id
        ArrayNode child = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_MAP, null, new ArrayNode[]{child}, new int[0]);
        DecodeContext ctx = TestDecodeContexts.of(node, MAP_UTF8_I64).rowCount(2).registry(registry).build();

        // When / Then
        assertThatThrownBy(() -> sut.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("entries child must use vortex.listview encoding, got vortex.primitive");
    }

    @Test
    void decode_entriesChildWrappedInMasked_throws() {
        // Given a vortex.masked wrapper around the entries list-view: a plausible shape (it is how
        // every other nullable column carries validity) that no writer emits for a map, because a
        // map delegates its validity to the list-view's own validity slot. Rust rejects it, and so
        // must this decoder — otherwise one logical column would have two wire encodings.
        ArrayNode listView = new ArrayNode(EncodingId.VORTEX_LISTVIEW, null, new ArrayNode[0], new int[0]);
        ArrayNode child = new ArrayNode(EncodingId.VORTEX_MASKED, null, new ArrayNode[]{listView}, new int[0]);
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_MAP, null, new ArrayNode[]{child}, new int[0]);
        DecodeContext ctx = TestDecodeContexts.of(node, MAP_UTF8_I64.asNullable())
                                              .rowCount(2).registry(registry).build();

        // When / Then
        assertThatThrownBy(() -> sut.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("entries child must use vortex.listview encoding, got vortex.masked");
    }

    @Test
    void decode_entriesDtypeMismatch_throws() {
        // Given a registered vortex.listview decoder that hands back an entries array whose entry
        // struct has key and value swapped. The built-in decoder can never do this — it decodes
        // against the dtype the map passes down — but ReadRegistry is pluggable, so the decoder's
        // own dtype check is what stops a third-party decoder from silently reshaping a column.
        ReadRegistry swapped = TestRegistry.ofDecoders(new MapEncodingDecoder(), new SwappedEntriesDecoder());
        ArrayNode child = new ArrayNode(EncodingId.VORTEX_LISTVIEW, null, new ArrayNode[0], new int[0]);
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_MAP, null, new ArrayNode[]{child}, new int[0]);
        DecodeContext ctx = TestDecodeContexts.of(node, MAP_UTF8_I64).rowCount(2).registry(swapped).build();

        // When / Then
        assertThatThrownBy(() -> sut.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("entries child decoded to")
                .hasMessageContaining("expected");
    }

    /// A `vortex.listview` decoder returning an entries list whose `{key, value}` struct is
    /// ordered `{value, key}` — the shape [MapEncodingDecoder]'s own dtype guard exists to reject.
    private static final class SwappedEntriesDecoder implements EncodingDecoder {

        @Override
        public EncodingId encodingId() {
            return EncodingId.VORTEX_LISTVIEW;
        }

        @Override
        public Array decode(DecodeContext ctx) {
            DType.Struct swapped = new DType.Struct(
                    List.of(ColumnName.of("key"), ColumnName.of("value")),
                    List.of(DType.I64, DType.UTF8),
                    false);
            Array empty = new NullArray(DType.NULL, ctx.rowCount());
            return new ListViewArray(new DType.List(swapped, false), ctx.rowCount(), empty, empty, empty);
        }
    }
}
