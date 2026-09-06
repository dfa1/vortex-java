package io.github.dfa1.vortex.reader.layout;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.UnknownArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/// Drives [StructLayoutDecoder]: it reassembles a [StructArray] from the per-field children of a
/// nested `vortex.struct` layout (issue #207), threading a leading validity child through as a
/// per-field mask when the struct dtype is nullable, and failing loudly on shape mismatches.
class StructLayoutDecoderTest {

    private final StructLayoutDecoder sut = new StructLayoutDecoder();

    @Test
    void decode_nonNullableStruct_reassemblesFieldsInOrder() {
        // Given — a two-field, non-nullable struct layout: one flat child per field, no validity.
        // The context returns a distinct sentinel per child so field ordering is observable.
        DType.Struct dtype = struct(false, "a", intType(), "b", intType());
        Layout child0 = flat(0);
        Layout child1 = flat(1);
        Layout layout = new Layout(LayoutId.STRUCT, 3L, null, List.of(child0, child1), List.of());
        Array field0 = sentinel();
        Array field1 = sentinel();
        LayoutDecodeContext ctx = mock(LayoutDecodeContext.class);
        given(ctx.decodeChild(child0, intType())).willReturn(field0);
        given(ctx.decodeChild(child1, intType())).willReturn(field1);

        // When
        Array result = sut.decode(ctx, layout, dtype);

        // Then — a StructArray carrying exactly the two field arrays, in schema order, at the
        // layout's row count
        assertThat(result).isInstanceOfSatisfying(StructArray.class, sa -> {
            assertThat(sa.length()).isEqualTo(3L);
            assertThat(sa.fieldCount()).isEqualTo(2);
            assertThat(sa.field(0)).isSameAs(field0);
            assertThat(sa.field(1)).isSameAs(field1);
        });
    }

    @Test
    void decode_nullableStruct_masksEveryFieldWithLeadingValidity() {
        // Given — a nullable struct: child[0] is the Bool validity, matching the Rust reference
        // (StructReader inserts a validity child when the layout dtype is nullable). Each field
        // must come back wrapped in a MaskedArray sharing that struct-level validity.
        DType.Struct dtype = struct(true, "a", intType(), "b", intType());
        Layout validityChild = flat(0);
        Layout fieldChild0 = flat(1);
        Layout fieldChild1 = flat(2);
        Layout layout = new Layout(LayoutId.STRUCT, 4L, null,
                List.of(validityChild, fieldChild0, fieldChild1), List.of());
        BoolArray validity = constantValidity();
        Array field0 = sentinel();
        Array field1 = sentinel();
        LayoutDecodeContext ctx = mock(LayoutDecodeContext.class);
        given(ctx.decodeChild(validityChild, DType.BOOL)).willReturn(validity);
        given(ctx.decodeChild(fieldChild0, intType())).willReturn(field0);
        given(ctx.decodeChild(fieldChild1, intType())).willReturn(field1);

        // When
        Array result = sut.decode(ctx, layout, dtype);

        // Then — both fields are masked by the same validity, so struct-level nulls propagate
        assertThat(result).isInstanceOfSatisfying(StructArray.class, sa -> {
            assertThat(sa.field(0)).isInstanceOfSatisfying(MaskedArray.class, m -> {
                assertThat(m.inner()).isSameAs(field0);
                assertThat(m.validity()).isSameAs(validity);
            });
            assertThat(sa.field(1)).isInstanceOfSatisfying(MaskedArray.class, m ->
                    assertThat(m.inner()).isSameAs(field1));
        });
    }

    @Test
    void decode_nonStructDtype_throwsVortexException() {
        // Given — a struct layout handed a primitive dtype: a corrupt file could pair them
        Layout layout = new Layout(LayoutId.STRUCT, 1L, null, List.of(flat(0)), List.of());
        LayoutDecodeContext ctx = mock(LayoutDecodeContext.class);
        DType dtype = intType();

        // When / Then — fail fast rather than reassemble a struct from a non-struct type
        assertThatThrownBy(() -> sut.decode(ctx, layout, dtype))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("requires a struct dtype");
    }

    @Test
    void decode_childCountMismatch_throwsVortexException() {
        // Given — a two-field struct but only one child (neither nfields nor nfields+1)
        DType.Struct dtype = struct(false, "a", intType(), "b", intType());
        Layout layout = new Layout(LayoutId.STRUCT, 1L, null, List.of(flat(0)), List.of());
        LayoutDecodeContext ctx = mock(LayoutDecodeContext.class);

        // When / Then — the shape is unrecoverable, so it fails loudly
        assertThatThrownBy(() -> sut.decode(ctx, layout, dtype))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("1 children")
                .hasMessageContaining("2 fields");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static DType intType() {
        return new DType.Primitive(PType.I32, false);
    }

    private static DType.Struct struct(boolean nullable, String n0, DType t0, String n1, DType t1) {
        return new DType.Struct(
                List.of(ColumnName.of(n0), ColumnName.of(n1)), List.of(t0, t1), nullable);
    }

    private static Layout flat(int segment) {
        return new Layout(LayoutId.FLAT, 0L, null, List.of(), List.of(segment));
    }

    /// A concrete stand-in [Array] — the sealed interface cannot be mocked, and these tests only
    /// need object identity.
    private static Array sentinel() {
        return new UnknownArray(EncodingId.parse("stub"), intType(), 0L, null,
                new MemorySegment[0], new Array[0]);
    }

    /// A minimal all-valid [BoolArray]; only its identity is asserted, never its contents.
    private static BoolArray constantValidity() {
        return new BoolArray() {
            @Override
            public boolean getBoolean(long i) {
                return true;
            }

            @Override
            public long length() {
                return 4L;
            }

            @Override
            public DType dtype() {
                return DType.BOOL;
            }
        };
    }
}
