package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructEncodingDecoderTest {

    private static final StructEncodingDecoder SUT = new StructEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            SUT, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder(), new NotBoolDecoder());

    @Test
    void encodingId_isVortexStruct() {
        // Given / When / Then
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.VORTEX_STRUCT);
    }

    @Test
    void decode_twoFields_returnsStructArrayWithBothColumns() {
        // Given — a two-field struct whose children are one i64 buffer each
        MemorySegment[] segs = {TestSegments.leLongs(1, 2), TestSegments.leLongs(30, 40)};

        // When
        Array result = decode(structOf("a", "b"), 2, segs, primitiveNode(0), primitiveNode(1));

        // Then
        assertThat(result).isInstanceOf(StructArray.class);
        StructArray struct = (StructArray) result;
        assertThat(((LongArray) struct.field(0)).getLong(0)).isEqualTo(1);
        assertThat(((LongArray) struct.field(1)).getLong(1)).isEqualTo(40);
    }

    /// Malformed-input cases for `vortex.struct` (TODO.md §Security, ADR 0003).
    ///
    /// The TODO's two Struct gotchas — `fieldNames.size() != children.size()` and an invalid
    /// field name — are both a *dtype* concern rather than an encoding one, and are guarded and
    /// tested one layer up in `PostscriptParser.convertDType` (see
    /// `PostscriptParserDTypeGuardsTest`: names/dtypes arity mismatch, duplicate names, control
    /// characters in a name). Malformed UTF-8 in a name cannot reach a guard at all: the
    /// FlatBuffer string decode substitutes U+FFFD rather than failing, so there is nothing left
    /// to reject by the time the name is a `String`.
    ///
    /// What genuinely belongs here is the encoding-level arity check: the `ArrayNode` child
    /// count is independent file data and must agree with the dtype's field count (plus at most
    /// one leading validity child).
    @Nested
    class AdversarialInput {

        @Test
        void fewerChildrenThanFields_throws() {
            // Given — a three-field struct dtype but only two children in the array node
            MemorySegment[] segs = {TestSegments.leLongs(1, 2), TestSegments.leLongs(30, 40)};
            DType.Struct dtype = structOf("a", "b", "c");
            ArrayNode firstField = primitiveNode(0);
            ArrayNode secondField = primitiveNode(1);

            // When / Then
            assertThatThrownBy(() -> decode(dtype, 2, segs, firstField, secondField))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("children for struct dtype");
        }

        /// One child over the validity-inclusive maximum: `nfields + 1` is legal (leading
        /// validity), `nfields + 2` is not, so this pins the upper edge of the accepted range.
        @Test
        void moreChildrenThanFieldsPlusValidity_throws() {
            // Given — a one-field struct dtype with three children
            MemorySegment[] segs = {TestSegments.leLongs(1, 2)};
            DType.Struct dtype = structOf("a");
            ArrayNode child = primitiveNode(0);

            // When / Then
            assertThatThrownBy(() -> decode(dtype, 2, segs, child, child, child))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("children for struct dtype");
        }

        /// The non-struct dtype branch is a scalar wrapper accepting exactly one child (values)
        /// or two (validity + values); anything else is a corrupt node.
        @Test
        void scalarWrapperWithThreeChildren_throws() {
            // Given — an i64 dtype (not a struct) under a struct-encoded node with three children
            MemorySegment[] segs = {TestSegments.leLongs(1, 2)};
            ArrayNode child = primitiveNode(0);

            // When / Then
            assertThatThrownBy(() -> decode(DType.I64, 2, segs, child, child, child))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unexpected child count 3");
        }

        /// A validity child is only meaningful as a bool array; a crafted node whose validity
        /// subtree decodes to something else must fail loudly instead of being cast blind.
        @Test
        void scalarWrapperValidityChildNotBool_throws() {
            // Given — the leading validity child decodes to an i64 array. Real files reach this
            // through encodings that honor their own node's shape over the requested BOOL dtype;
            // the stub decoder reproduces that without depending on any one of them.
            MemorySegment[] segs = {TestSegments.leLongs(1, 2), TestSegments.leLongs(30, 40)};
            ArrayNode notBool = new ArrayNode(NotBoolDecoder.ID, null, new ArrayNode[0], new int[0]);
            ArrayNode valuesNode = primitiveNode(1);

            // When / Then
            assertThatThrownBy(() -> decode(DType.I64, 2, segs, notBool, valuesNode))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("validity decoded to unexpected type");
        }

        /// The same guard on the struct-dtype path, where the validity child is the extra
        /// `nfields + 1`-th child rather than the first of a scalar wrapper pair.
        @Test
        void structValidityChildNotBool_throws() {
            // Given — a one-field struct with a leading validity child that decodes to i64
            MemorySegment[] segs = {TestSegments.leLongs(1, 2)};
            ArrayNode notBool = new ArrayNode(NotBoolDecoder.ID, null, new ArrayNode[0], new int[0]);
            DType.Struct dtype = structOf("a");
            ArrayNode fieldNode = primitiveNode(0);

            // When / Then
            assertThatThrownBy(() -> decode(dtype, 2, segs, notBool, fieldNode))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("validity decoded to unexpected type");
        }
    }

    /// Decodes anything into an i64 array — the stand-in for a validity subtree that decodes
    /// successfully but not into a [io.github.dfa1.vortex.reader.array.BoolArray].
    private static final class NotBoolDecoder implements EncodingDecoder {

        private static final EncodingId ID = EncodingId.parse("test.notbool");

        @Override
        public EncodingId encodingId() {
            return ID;
        }

        @Override
        public Array decode(DecodeContext ctx) {
            return new MaterializedLongArray(DType.I64, ctx.rowCount(), TestSegments.leLongs(1, 2));
        }
    }

    private static Array decode(DType dtype, long rowCount, MemorySegment[] segs, ArrayNode... children) {
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_STRUCT, null, children, new int[0]);
        return SUT.decode(new DecodeContext(node, dtype, rowCount, segs, REGISTRY, Arena.ofAuto()));
    }

    private static DType.Struct structOf(String... names) {
        List<ColumnName> fieldNames = List.of(names).stream().map(ColumnName::of).toList();
        List<DType> fieldTypes = fieldNames.stream().<DType>map(n -> DType.I64).toList();
        return new DType.Struct(fieldNames, fieldTypes, false);
    }

    private static ArrayNode primitiveNode(int bufferIndex) {
        return new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{bufferIndex});
    }
}
