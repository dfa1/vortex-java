package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoNullValue;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoSparseMetadata;
import io.github.dfa1.vortex.core.proto.ProtoVarBinMetadata;
import io.github.dfa1.vortex.encoding.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SparseEncodingDecoderTest {

    private static final SparseEncodingDecoder SUT = new SparseEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            SUT, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder(),
            new VarBinEncodingDecoder(), new MaskedEncodingDecoder());

    @Test
    void encodingId_isVortexSparse() {
        // Given / When / Then
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.VORTEX_SPARSE);
    }

    /// A `fill_value: null` sparse array must null every UNPATCHED position, not decode it as
    /// the fill's zero bits. Mirrors world-energy-consumption `biofuel_cons_change_pct` (f64?):
    /// unpatched rows previously decoded as 0.0 (#226). Row validity is a sparse bool whose fill
    /// is `fill.is_valid()` (Rust `ValidityVTable<Sparse>`).
    @Test
    void nullFill_nullsUnpatchedPositions_keepsPatchesValid() {
        // Given — null fill; patches at positions 1 and 3 with values 5.0 and 7.0 over 5 rows.
        MemorySegment[] segs = {
                nullFill(),                          // fill_value: null
                TestSegments.leInts(1, 3),           // patch indices (U32)
                TestSegments.leDoubles(5.0, 7.0)     // patch values (all valid)
        };
        ArrayNode idxNode = primitiveNode(1);
        ArrayNode valNode = primitiveNode(2);

        // When
        Array result = decode(nullableF64(), 2, 0, PType.U32, 5, segs, idxNode, valNode);

        // Then — only the patched positions are valid
        MaskedArray masked = assertMasked(result);
        assertValidity(masked, false, true, false, true, false);
        DoubleArray inner = (DoubleArray) masked.inner();
        assertThat(inner.getDouble(1)).isCloseTo(5.0, within(1e-9));
        assertThat(inner.getDouble(3)).isCloseTo(7.0, within(1e-9));
    }

    /// A non-null fill with a null PATCH value must null only that patch's position; other
    /// patches and all fill positions stay valid. Mirrors nuclear_share_energy: a patched-but-null
    /// slot previously decoded to raw 0 (#226).
    @Test
    void nonNullFill_withNullPatch_nullsOnlyThatPatch() {
        // Given — fill 0.0 (valid); patches at 1 and 3 with the patch at position 3 null.
        MemorySegment[] segs = {
                f64Fill(0.0),                        // valid fill
                TestSegments.leInts(1, 3),           // patch indices
                TestSegments.leDoubles(5.0, 7.0),    // patch values
                boolBitmap(true, false)              // patch validity: patch 1 (pos 3) null
        };
        ArrayNode idxNode = primitiveNode(1);
        ArrayNode valNode = maskedPrimitiveNode(2, 3);

        // When
        Array result = decode(nullableF64(), 2, 0, PType.U32, 5, segs, idxNode, valNode);

        // Then — fill positions valid, patch at pos 1 valid, patch at pos 3 null
        MaskedArray masked = assertMasked(result);
        assertValidity(masked, true, true, true, false, true);
        DoubleArray inner = (DoubleArray) masked.inner();
        assertThat(inner.getDouble(0)).isCloseTo(0.0, within(1e-9));
        assertThat(inner.getDouble(1)).isCloseTo(5.0, within(1e-9));
        assertThat(inner.getDouble(2)).isCloseTo(0.0, within(1e-9));
    }

    /// No-regression: a non-null fill with non-nullable patches must NOT produce a [MaskedArray].
    @Test
    void nonNullFill_validPatches_returnsPlainArray() {
        // Given — fill 0.0 (valid), patches all valid
        MemorySegment[] segs = {
                f64Fill(0.0),
                TestSegments.leInts(1, 3),
                TestSegments.leDoubles(5.0, 7.0)
        };
        ArrayNode idxNode = primitiveNode(1);
        ArrayNode valNode = primitiveNode(2);

        // When
        Array result = decode(DType.F64, 2, 0, PType.U32, 5, segs, idxNode, valNode);

        // Then
        assertThat(result).isInstanceOf(DoubleArray.class).isNotInstanceOf(MaskedArray.class);
        DoubleArray inner = (DoubleArray) result;
        assertThat(inner.getDouble(0)).isCloseTo(0.0, within(1e-9));
        assertThat(inner.getDouble(1)).isCloseTo(5.0, within(1e-9));
    }

    /// The string/binary sibling of #226 (#232): a `fill_value: null` utf8 sparse column must
    /// null every UNPATCHED position instead of rendering the empty string. No corpus column hits
    /// this shape yet, so this test IS the reproduction — the same world-energy null-fill shape,
    /// but with a utf8 values encoding rather than f64. The Rust `ValidityVTable<Sparse>` is generic
    /// over the values encoding, so VarBin reuses the same sparse-bool row validity.
    @Test
    void utf8NullFill_nullsUnpatchedPositions_keepsPatchesValid() {
        // Given — null fill; patches "b" at pos 1 and "d" at pos 3 over 5 rows.
        MemorySegment[] segs = {
                nullFill(),                          // fill_value: null (type-agnostic)
                TestSegments.leInts(1, 3),           // patch indices (U32)
                utf8Bytes("bd"),                     // concatenated patch values
                TestSegments.leInts(0, 1, 2)         // patch value offsets
        };
        ArrayNode idxNode = primitiveNode(1);
        ArrayNode valNode = varBinNode(2, 3);

        // When
        Array result = decode(nullableUtf8(), 2, 0, PType.U32, 5, segs, idxNode, valNode);

        // Then — only the patched positions are valid; patches keep their strings.
        MaskedArray masked = assertMasked(result);
        assertValidity(masked, false, true, false, true, false);
        VarBinArray inner = (VarBinArray) masked.inner();
        assertThat(inner.getBytes(1)).containsExactly('b');
        assertThat(inner.getBytes(3)).containsExactly('d');
    }

    /// A non-null utf8 fill with a null PATCH value must null only that patch's position (#232):
    /// previously the dropped mask let a patched-but-null slot render as its raw string bytes.
    @Test
    void utf8NonNullFill_withNullPatch_nullsOnlyThatPatch() {
        // Given — non-null string fill; patches "b" at pos 1 and "d" at pos 3, the patch at pos 3 null.
        MemorySegment[] segs = {
                utf8Fill("x"),                       // valid (non-null) string fill
                TestSegments.leInts(1, 3),           // patch indices
                utf8Bytes("bd"),                     // patch values
                TestSegments.leInts(0, 1, 2),        // patch value offsets
                boolBitmap(true, false)              // patch validity: patch 1 (pos 3) null
        };
        ArrayNode idxNode = primitiveNode(1);
        ArrayNode valNode = maskedVarBinNode(2, 3, 4);

        // When
        Array result = decode(nullableUtf8(), 2, 0, PType.U32, 5, segs, idxNode, valNode);

        // Then — fill positions valid, patch at pos 1 valid, patch at pos 3 null.
        MaskedArray masked = assertMasked(result);
        assertValidity(masked, true, true, true, false, true);
        VarBinArray inner = (VarBinArray) masked.inner();
        assertThat(inner.getBytes(1)).containsExactly('b');
    }

    /// No-regression: a non-null utf8 fill with non-nullable patches must NOT produce a [MaskedArray].
    @Test
    void utf8NonNullFill_validPatches_returnsPlainArray() {
        // Given — non-null string fill, patches all valid.
        MemorySegment[] segs = {
                utf8Fill("x"),
                TestSegments.leInts(1, 3),
                utf8Bytes("bd"),
                TestSegments.leInts(0, 1, 2)
        };
        ArrayNode idxNode = primitiveNode(1);
        ArrayNode valNode = varBinNode(2, 3);

        // When
        Array result = decode(DType.UTF8, 2, 0, PType.U32, 5, segs, idxNode, valNode);

        // Then
        assertThat(result).isInstanceOf(VarBinArray.class).isNotInstanceOf(MaskedArray.class);
        VarBinArray inner = (VarBinArray) result;
        assertThat(inner.getBytes(1)).containsExactly('b');
    }

    /// A sparse node with 3 children must be rejected: the spec requires exactly 2
    /// (patch_indices, patch_values). This is the fail-loud guard against future format
    /// variants that carry chunk_offsets as a 3rd child (#250).
    @Test
    void decode_threeChildren_throws() {
        // Given — a valid 2-patch sparse node whose ArrayNode has an extra third child
        ProtoPatchesMetadata patches = new ProtoPatchesMetadata(2, 0, ProtoPType.U32, null, null, null);
        MemorySegment meta = MemorySegment.ofArray(new ProtoSparseMetadata(patches).encode());
        ArrayNode dummy = primitiveNode(1);
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_SPARSE, meta,
                new ArrayNode[]{dummy, dummy, dummy}, new int[]{0});
        MemorySegment[] segs = {f64Fill(0.0), TestSegments.leInts(0, 1)};
        DecodeContext ctx = new DecodeContext(node, DType.F64, 2, segs, REGISTRY, Arena.ofAuto());

        // When / Then — must reject rather than silently ignoring the extra child
        assertThatThrownBy(() -> SUT.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("2");
    }

    private static Array decode(DType dtype, long numPatches, long offset, PType indicesPtype, long n,
            MemorySegment[] segs, ArrayNode idxNode, ArrayNode valNode) {
        ProtoPatchesMetadata patches = new ProtoPatchesMetadata(
                numPatches, offset, ProtoPType.valueOf(indicesPtype.name()), null, null, null);
        MemorySegment meta = MemorySegment.ofArray(new ProtoSparseMetadata(patches).encode());
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_SPARSE, meta,
                new ArrayNode[]{idxNode, valNode}, new int[]{0});
        DecodeContext ctx = new DecodeContext(node, dtype, n, segs, REGISTRY, Arena.ofAuto());
        return SUT.decode(ctx);
    }

    private static DType nullableF64() {
        return new DType.Primitive(PType.F64, true);
    }

    private static DType nullableUtf8() {
        return new DType.Utf8(true);
    }

    private static MemorySegment nullFill() {
        return MemorySegment.ofArray(ProtoScalarValue.ofNullValue(ProtoNullValue.NULL_VALUE).encode());
    }

    private static MemorySegment f64Fill(double v) {
        return MemorySegment.ofArray(ProtoScalarValue.ofF64Value(v).encode());
    }

    /// A non-null utf8 fill scalar: sets `string_value`, so [SparseEncodingDecoder] treats it as
    /// valid. Exercises the string-typed field of the fill-null detection helper.
    private static MemorySegment utf8Fill(String v) {
        return MemorySegment.ofArray(ProtoScalarValue.ofStringValue(v).encode());
    }

    private static MemorySegment utf8Bytes(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static MaskedArray assertMasked(Array result) {
        assertThat(result).isInstanceOf(MaskedArray.class);
        return (MaskedArray) result;
    }

    private static void assertValidity(MaskedArray masked, boolean... expected) {
        for (int i = 0; i < expected.length; i++) {
            assertThat(masked.isValid(i)).as("valid row %d", i).isEqualTo(expected[i]);
        }
    }

    private static ArrayNode primitiveNode(int bufferIndex) {
        return new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{bufferIndex});
    }

    /// A primitive node whose single `vortex.bool` validity child makes
    /// [PrimitiveEncodingDecoder] surface it as a [MaskedArray].
    private static ArrayNode maskedPrimitiveNode(int dataBufIndex, int validityBufIndex) {
        ArrayNode validity = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0],
                new int[]{validityBufIndex});
        return new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[]{validity},
                new int[]{dataBufIndex});
    }

    /// A `vortex.varbin` node: I32 offsets in child 0 (segment `offsetsBufIndex`), byte data in
    /// buffer `bytesBufIndex`.
    private static ArrayNode varBinNode(int bytesBufIndex, int offsetsBufIndex) {
        MemorySegment meta = MemorySegment.ofArray(new ProtoVarBinMetadata(ProtoPType.I32).encode());
        ArrayNode offsets = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0],
                new int[]{offsetsBufIndex});
        return new ArrayNode(EncodingId.VORTEX_VARBIN, meta, new ArrayNode[]{offsets}, new int[]{bytesBufIndex});
    }

    /// A `vortex.masked` node wrapping a varbin child plus a `vortex.bool` validity child — the
    /// shape a nullable varbin patch-values array decodes from.
    private static ArrayNode maskedVarBinNode(int bytesBufIndex, int offsetsBufIndex, int validityBufIndex) {
        ArrayNode validity = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0],
                new int[]{validityBufIndex});
        return new ArrayNode(EncodingId.VORTEX_MASKED, null,
                new ArrayNode[]{varBinNode(bytesBufIndex, offsetsBufIndex), validity}, new int[0]);
    }

    private static MemorySegment boolBitmap(boolean... valid) {
        byte[] bytes = new byte[(valid.length + 7) / 8];
        for (int i = 0; i < valid.length; i++) {
            if (valid[i]) {
                bytes[i >>> 3] |= (byte) (1 << (i & 7));
            }
        }
        return MemorySegment.ofArray(bytes);
    }
}
