package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.ListViewArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MapArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.BoolEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.reader.decode.ListViewEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.MapEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.MaskedEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.StructEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.reader.decode.VarBinEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapEncodingEncoderTest {

    /// `map<utf8, i64>` — non-nullable string keys, non-nullable long values.
    private static final DType.Map MAP_UTF8_I64 =
            new DType.Map(DType.UTF8, DType.I64, false, false);

    /// `map<utf8, i64?>` — a nullable *value type*, which is a different thing from a nullable
    /// map row: the null lives on one entry inside a present map, so it rides the entry struct's
    /// own field validity rather than the entries list-view's validity slot.
    private static final DType.Map MAP_UTF8_NULLABLE_I64 =
            new DType.Map(DType.UTF8, DType.I64.asNullable(), false, false);

    private static final MapEncodingEncoder ENCODER = new MapEncodingEncoder();
    private static final MapEncodingDecoder DECODER = new MapEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            DECODER, new ListViewEncodingDecoder(), new StructEncodingDecoder(),
            new PrimitiveEncodingDecoder(), new VarBinEncodingDecoder(),
            new MaskedEncodingDecoder(), new BoolEncodingDecoder());

    @Nested
    class Encode {

        @Test
        void accepts_mapDtype_true() {
            // Given / When / Then
            assertThat(ENCODER.accepts(MAP_UTF8_I64)).isTrue();
        }

        @Test
        void accepts_listDtype_false() {
            // Given / When / Then
            assertThat(ENCODER.accepts(new DType.List(DType.I32, false))).isFalse();
        }

        @Test
        void encode_producesOneEntriesChild_noBuffersOnRoot() {
            // Given
            ListViewData data = entries(new String[]{"a", "b", "c"}, new long[]{1, 2, 3},
                    new int[]{0, 2, 2}, new int[]{2, 0, 1}, 3);

            // When
            EncodeResult result = ENCODER.encode(MAP_UTF8_I64, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_MAP);
            assertThat(result.rootNode().bufferIndices()).isEmpty();
            assertThat(result.rootNode().metadata()).isNull();
            assertThat(result.rootNode().children()).hasSize(1);
            assertThat(result.rootNode().children()[0].encodingId()).isEqualTo(EncodingId.VORTEX_LISTVIEW);
        }

        @Test
        void encode_nullableMap_putsValidityInTheListViewSlot() {
            // Given a nullable map — validity is delegated to the entries child, which keeps it in
            // the list-view's own fourth child slot. A vortex.masked wrapper here would be rejected
            // by the Rust reference, which requires a map's entries child to be a bare list-view.
            ListViewData values = entries(new String[]{"a"}, new long[]{1},
                    new int[]{0, 1}, new int[]{1, 0}, 2);
            NullableData data = new NullableData(values, new boolean[]{true, false});

            // When
            EncodeResult result = ENCODER.encode(MAP_UTF8_I64.asNullable(), data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_MAP);
            assertThat(result.rootNode().children()).hasSize(1);
            assertThat(result.rootNode().children()[0].encodingId()).isEqualTo(EncodingId.VORTEX_LISTVIEW);
            assertThat(result.rootNode().children()[0].children()).hasSize(4);
        }

        @Test
        void encode_nullableMapWithWrongValuesShape_throws() {
            // Given a NullableData carrying a raw array instead of the required ListViewData
            NullableData data = new NullableData(new long[]{1, 2}, new boolean[]{true, true});
            EncodeContext ctx = EncodeTestHelper.testCtx();

            // When / Then
            assertThatThrownBy(() -> ENCODER.encode(MAP_UTF8_I64.asNullable(), data, ctx))
                    .hasMessageContaining("ListViewData");
        }
    }

    @Nested
    class RoundTrip {

        @Test
        void roundTrip_preservesEveryEntry() {
            // Given three rows: {a:1, b:2}, {}, {c:3} — the middle empty row pins that a zero
            // size is preserved rather than collapsed
            ListViewData data = entries(new String[]{"a", "b", "c"}, new long[]{1, 2, 3},
                    new int[]{0, 2, 2}, new int[]{2, 0, 1}, 3);

            // When
            MapArray result = decode(MAP_UTF8_I64, data, 3);

            // Then
            assertThat(result.length()).isEqualTo(3);
            assertThat(result.dtype()).isEqualTo(MAP_UTF8_I64);
            ListViewArray entries = (ListViewArray) result.entries();
            StructArray entryStructs = (StructArray) entries.elements();
            VarBinArray keys = (VarBinArray) entryStructs.field(0);
            LongArray values = (LongArray) entryStructs.field(1);
            assertThat(text(keys, 0)).isEqualTo("a");
            assertThat(text(keys, 1)).isEqualTo("b");
            assertThat(text(keys, 2)).isEqualTo("c");
            assertThat(values.getLong(0)).isEqualTo(1L);
            assertThat(values.getLong(1)).isEqualTo(2L);
            assertThat(values.getLong(2)).isEqualTo(3L);
        }

        @Test
        void roundTrip_emptyMapColumn_preservesRowCount() {
            // Given a column of rows that are all empty maps — no entries at all
            ListViewData data = entries(new String[]{}, new long[]{},
                    new int[]{0, 0}, new int[]{0, 0}, 2);

            // When
            MapArray result = decode(MAP_UTF8_I64, data, 2);

            // Then
            assertThat(result.length()).isEqualTo(2);
            assertThat(((ListViewArray) result.entries()).elements().length()).isZero();
        }

        @Test
        void roundTrip_zeroRows_decodes() {
            // Given a chunk with no rows at all
            ListViewData data = entries(new String[]{}, new long[]{}, new int[]{}, new int[]{}, 0);

            // When
            MapArray result = decode(MAP_UTF8_I64, data, 0);

            // Then
            assertThat(result.length()).isZero();
        }

        @Test
        void roundTrip_nullableMap_preservesValidity() {
            // Given row 1 null; its offset/size slots still carry placeholders, exactly as the
            // masked contract requires (values dense, nulls carried only by the bitmap)
            ListViewData values = entries(new String[]{"a", "z"}, new long[]{1, 9},
                    new int[]{0, 1, 1}, new int[]{1, 0, 1}, 3);
            NullableData data = new NullableData(values, new boolean[]{true, false, true});

            // When
            MapArray result = decode(MAP_UTF8_I64.asNullable(), data, 3);

            // Then
            assertThat(result.dtype()).isEqualTo(MAP_UTF8_I64.asNullable());
            MaskedArray masked = (MaskedArray) result.entries();
            assertThat(masked.isValid(0)).isTrue();
            assertThat(masked.isValid(1)).isFalse();
            assertThat(masked.isValid(2)).isTrue();
            ListViewArray entries = (ListViewArray) masked.inner();
            StructArray entryStructs = (StructArray) entries.elements();
            assertThat(text((VarBinArray) entryStructs.field(0), 1)).isEqualTo("z");
        }

        @Test
        void roundTrip_nullableValueType_preservesNullEntry() {
            // Given one present map row {a:1, b:null} — the null is on an entry's value, not on
            // the map row, so it must survive in the entry struct's value-field validity while
            // the entries list itself stays non-nullable
            ListViewData data = new ListViewData(
                    new StructData(List.of(
                            new String[]{"a", "b"},
                            new NullableData(new long[]{1L, 0L}, new boolean[]{true, false}))),
                    new int[]{0}, new int[]{2}, 1);

            // When
            MapArray result = decode(MAP_UTF8_NULLABLE_I64, data, 1);

            // Then
            assertThat(result.dtype()).isEqualTo(MAP_UTF8_NULLABLE_I64);
            ListViewArray entries = (ListViewArray) result.entries();
            StructArray entryStructs = (StructArray) entries.elements();
            assertThat(text((VarBinArray) entryStructs.field(0), 1)).isEqualTo("b");
            MaskedArray values = (MaskedArray) entryStructs.field(1);
            assertThat(values.isValid(0)).isTrue();
            assertThat(values.isValid(1)).isFalse();
            assertThat(((LongArray) values.inner()).getLong(0)).isEqualTo(1L);
        }

        @Test
        void limited_narrowsRowCount() {
            // Given a three-row map column
            ListViewData data = entries(new String[]{"a", "b", "c"}, new long[]{1, 2, 3},
                    new int[]{0, 2, 2}, new int[]{2, 0, 1}, 3);
            MapArray sut = decode(MAP_UTF8_I64, data, 3);

            // When
            MapArray result = (MapArray) sut.limited(2);

            // Then
            assertThat(result.length()).isEqualTo(2);
            assertThat(result.entries().length()).isEqualTo(2);
        }

        @Test
        void materialize_throws() {
            // Given a decoded map array — it is its entries child, not a flat segment
            ListViewData data = entries(new String[]{"a"}, new long[]{1},
                    new int[]{0}, new int[]{1}, 1);
            MapArray sut = decode(MAP_UTF8_I64, data, 1);

            // When / Then
            assertThatThrownBy(() -> sut.materialize(Arena.global()))
                    .hasMessageContaining("MapArray has no primary segment");
        }
    }

    private static ListViewData entries(String[] keys, long[] values, int[] offsets, int[] sizes, long rows) {
        return new ListViewData(new StructData(List.of(keys, values)), offsets, sizes, rows);
    }

    private static MapArray decode(DType dtype, Object data, long rows) {
        EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
        MemorySegment[] bufs = encoded.buffers().toArray(MemorySegment[]::new);
        DecodeContext ctx = new DecodeContext(
                toArrayNode(encoded.rootNode()), dtype, rows, bufs, REGISTRY, Arena.global());
        return (MapArray) DECODER.decode(ctx);
    }

    private static String text(VarBinArray array, long i) {
        return new String(array.getBytes(i), StandardCharsets.UTF_8);
    }

    private static ArrayNode toArrayNode(EncodeNode node) {
        ArrayNode[] children = new ArrayNode[node.children().length];
        for (int i = 0; i < children.length; i++) {
            children[i] = toArrayNode(node.children()[i]);
        }
        return new ArrayNode(node.encodingId(), node.metadata(), children, node.bufferIndices());
    }
}
