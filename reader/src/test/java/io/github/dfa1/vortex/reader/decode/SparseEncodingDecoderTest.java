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
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinConstantArray;
import io.github.dfa1.vortex.reader.array.VarBinSparseArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    /// The utf8/binary path resolved every UNPATCHED row to the empty string instead of the
    /// fill: the eager merge was handed only `fill.is_valid()` and described unpatched rows as
    /// zero-length ranges, so a non-null string fill was silently dropped. The Rust
    /// `SparseArray` resolves an unpatched row to the fill value whatever the values encoding
    /// is, exactly as the primitive path here already did.
    @Test
    void utf8NonNullFill_unpatchedRowsRenderTheFill() {
        // Given — fill "zz"; patches "b" at pos 1 and "d" at pos 3 over 5 rows.
        MemorySegment[] segs = {
                utf8Fill("zz"),
                TestSegments.leInts(1, 3),
                utf8Bytes("bd"),
                TestSegments.leInts(0, 1, 2)
        };
        ArrayNode idxNode = primitiveNode(1);
        ArrayNode valNode = varBinNode(2, 3);

        // When
        Array result = decode(DType.UTF8, 2, 0, PType.U32, 5, segs, idxNode, valNode);

        // Then — rows 0, 2, 4 are the fill; the patched rows keep their own values.
        assertThat(result).isInstanceOf(VarBinSparseArray.class);
        VarBinArray inner = (VarBinArray) result;
        assertThat(inner.getString(0)).isEqualTo("zz");
        assertThat(inner.getString(1)).isEqualTo("b");
        assertThat(inner.getString(2)).isEqualTo("zz");
        assertThat(inner.getString(3)).isEqualTo("d");
        assertThat(inner.getString(4)).isEqualTo("zz");
        assertThat(inner.getByteLength(0)).isEqualTo(2);
    }

    /// The same fill resolution over `forEachByteLength`, which walks patches rather than
    /// binary-searching per row — the two paths must agree.
    @Test
    void utf8NonNullFill_forEachByteLengthEmitsFillLengths() {
        // Given — 2-byte fill, 1-byte patches at positions 1 and 3 over 5 rows.
        MemorySegment[] segs = {
                utf8Fill("zz"),
                TestSegments.leInts(1, 3),
                utf8Bytes("bd"),
                TestSegments.leInts(0, 1, 2)
        };
        List<Integer> lengths = new ArrayList<>();

        // When
        Array result = decode(DType.UTF8, 2, 0, PType.U32, 5, segs, primitiveNode(1), varBinNode(2, 3));
        ((VarBinArray) result).forEachByteLength(lengths::add);

        // Then
        assertThat(lengths).containsExactly(2, 1, 2, 1, 2);
    }

    /// A binary (not utf8) fill arrives as the scalar's `bytes_value` rather than
    /// `string_value`; both must reach the carrier, or binary columns keep the dropped-fill bug
    /// after utf8 stops having it.
    @Test
    void binaryNonNullFill_unpatchedRowsRenderTheFill() {
        // Given — a binary fill of 0x01 0x02, one patch "b" at position 1 over 3 rows.
        MemorySegment[] segs = {
                MemorySegment.ofArray(ProtoScalarValue.ofBytesValue(new byte[]{1, 2}).encode()),
                TestSegments.leInts(1),
                utf8Bytes("b"),
                TestSegments.leInts(0, 1)
        };

        // When
        Array result = decode(new DType.Binary(false), 1, 0, PType.U32, 3, segs,
                primitiveNode(1), varBinNode(2, 3));

        // Then
        VarBinArray inner = (VarBinArray) result;
        assertThat(inner.getBytes(0)).containsExactly(1, 2);
        assertThat(inner.getBytes(1)).containsExactly('b');
        assertThat(inner.getBytes(2)).containsExactly(1, 2);
    }

    /// A sparse utf8 column whose scanned range holds no patch at all — the common case for a
    /// genuinely sparse column — must not pay an `(n + 1)` offsets table of all zeros to say
    /// "every row is the fill" (#340). The constant carrier represents it in O(1).
    @Test
    void utf8ZeroPatches_returnsConstantCarrier() {
        // Given — a non-null string fill and no patches over 5 rows.
        MemorySegment[] segs = {utf8Fill("x"), empty(), empty(), empty()};
        ArrayNode idxNode = primitiveNode(1);
        ArrayNode valNode = varBinNode(2, 3);

        // When
        Array result = decode(DType.UTF8, 0, 0, PType.U32, 5, segs, idxNode, valNode);

        // Then — every row is the fill, with no buffer behind it.
        assertThat(result).isInstanceOf(VarBinConstantArray.class).isNotInstanceOf(MaskedArray.class);
        VarBinArray inner = (VarBinArray) result;
        assertThat(inner.length()).isEqualTo(5);
        assertThat(inner.getString(0)).isEqualTo("x");
        assertThat(inner.getString(4)).isEqualTo("x");
        assertThat(inner.getByteLength(2)).isEqualTo(1);
    }

    /// The null-fill half of #340: with no patches and a null fill, every row is null. The
    /// carrier changed but the row validity must not — this is what keeps the swap behavior
    /// preserving rather than merely allocation-free.
    @Test
    void utf8ZeroPatchesNullFill_nullsEveryRow() {
        // Given — a null fill and no patches over 3 rows.
        MemorySegment[] segs = {nullFill(), empty(), empty(), empty()};
        ArrayNode idxNode = primitiveNode(1);
        ArrayNode valNode = varBinNode(2, 3);

        // When
        Array result = decode(nullableUtf8(), 0, 0, PType.U32, 3, segs, idxNode, valNode);

        // Then
        MaskedArray masked = assertMasked(result);
        assertValidity(masked, false, false, false);
        assertThat(masked.inner()).isInstanceOf(VarBinConstantArray.class);
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

    /// Malformed-input cases for `vortex.sparse` (TODO.md §Security, ADR 0003): the patch count,
    /// the patch children's buffers and the patch-value offsets all come from untrusted file
    /// bytes and none of them are cross-validated at decode time. Every case here crashed with a
    /// raw JDK exception before the guards landed — the class named in each comment is what the
    /// reader used to leak.
    ///
    /// Unsorted patch indices are covered separately in `LazySparseArrayTest.AdversarialInput`:
    /// the crash surfaces in the lazy array's `forEach`/`fold` walker, not in this decoder's
    /// `decode()`, since the primitive/bool paths stay lazy by design.
    @Nested
    class AdversarialInput {

        @Test
        void missingPatchesMetadata_throws() {
            // Given — non-empty metadata that never sets field 1 (patches): an unrelated
            // unknown field (tag = field 2, varint wire type 0, value 0) that the proto reader
            // silently skips. This is different from *absent* metadata (already rejected by the
            // "missing metadata" check above): the bytes are present and parse cleanly, but
            // `patches` stays null, which used to NPE on `patches.len()`.
            MemorySegment meta = MemorySegment.ofArray(new byte[]{0x10, 0x00});
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_SPARSE, meta,
                    new ArrayNode[]{primitiveNode(1), primitiveNode(2)}, new int[]{0});
            MemorySegment[] segs = {f64Fill(0.0), TestSegments.leInts(0), TestSegments.leDoubles(1.0)};
            DecodeContext ctx = new DecodeContext(node, DType.F64, 1, segs, REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing patches metadata");
        }

        /// `ClassCastException` — `decodeChild` dispatches on the *child node's own* encoding
        /// id, not on the ptype this decoder expects. A crafted values child of the wrong
        /// concrete array type (here `vortex.bool` under an `i64` sparse array) decodes without
        /// error and used to blow up at the unchecked `(LongArray)` cast.
        @Test
        void patchValuesChildWrongType_throws() {
            // Given — an i64 sparse array whose values child is `vortex.bool`, not primitive i64
            ArrayNode boolValuesNode = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{2});
            MemorySegment[] segs = {f64Fill(0.0), TestSegments.leInts(1), boolBitmap(true)};

            // When / Then
            assertThatThrownBy(() -> decode(DType.I64, 1, 0, PType.U32, 5, segs,
                    primitiveNode(1), boolValuesNode))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("patch values child decoded to unexpected type");
        }

        /// `ArithmeticException: / by zero` — the `Materialized*` accessors broadcast an
        /// undersized buffer with `i % elementCount`, and a zero-byte patch-indices buffer makes
        /// `elementCount` zero.
        @Test
        void emptyPatchIndicesChild_throws() {
            // Given — metadata claims 2 patches but the patch-indices segment carries no bytes
            MemorySegment[] segs = {f64Fill(0.0), empty(), TestSegments.leDoubles(5.0, 7.0)};

            // When / Then
            assertThatThrownBy(() -> decode(DType.F64, 2, 0, PType.U32, 5, segs,
                    primitiveNode(1), primitiveNode(2)))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("empty patch indices child");
        }

        /// Same divide-by-zero, reached through the values child instead of the indices child.
        @Test
        void emptyPatchValuesChild_throws() {
            // Given — metadata claims 2 patches but the patch-values segment carries no bytes
            MemorySegment[] segs = {f64Fill(0.0), TestSegments.leInts(1, 3), empty()};

            // When / Then
            assertThatThrownBy(() -> decode(DType.F64, 2, 0, PType.U32, 5, segs,
                    primitiveNode(1), primitiveNode(2)))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("empty patch values child");
        }

        /// `OutOfMemoryError` — a null fill makes the decoder build a per-patch validity bitmap
        /// sized from the declared patch count, so an absurd count reserved exabytes of direct
        /// memory before any buffer was even read. Patches sit at distinct row positions, so the
        /// count can never exceed the row count (Rust `Patches::new`: `indices.len() <= array_len`).
        @Test
        void patchCountAboveRowCount_throws() {
            // Given — 5 rows but a patch count of 2^61, with a null fill to reach the bitmap alloc
            MemorySegment[] segs = {nullFill(), TestSegments.leInts(1, 3), TestSegments.leDoubles(5.0, 7.0)};

            // When / Then
            assertThatThrownBy(() -> decode(nullableF64(), 1L << 61, 0, PType.U32, 5, segs,
                    primitiveNode(1), primitiveNode(2)))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("patch count");
        }

        /// The other end of the same range check: proto `len` is a signed int64, so a crafted
        /// file can declare a negative patch count.
        @Test
        void negativePatchCount_throws() {
            // Given — a negative declared patch count
            MemorySegment[] segs = {f64Fill(0.0), TestSegments.leInts(1, 3), TestSegments.leDoubles(5.0, 7.0)};

            // When / Then
            assertThatThrownBy(() -> decode(DType.F64, -1, 0, PType.U32, 5, segs,
                    primitiveNode(1), primitiveNode(2)))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("patch count");
        }

        /// Declaring 4 patches over a values child carrying only 3 offsets is absorbed by the
        /// `SegmentBroadcast` convention: [VarBinEncodingDecoder] broadcast-fills a short
        /// offsets child, so patch 3 resolves through offset index `3 % 3 = 0` and reads inside
        /// the payload. The eager merge used to raise here, but only incidentally — it overran a
        /// merge buffer it had itself sized from the same broken offsets. What ADR 0003 requires
        /// is what this asserts: no raw JDK exception, and no read outside the value buffer.
        @Test
        void varBinPatchCountBeyondValueOffsets_broadcastsWithoutRawException() {
            // Given — 4 declared patches but only 3 offsets, covering 2 values ("b", "d")
            MemorySegment[] segs = {
                    nullFill(),
                    TestSegments.leInts(0, 1, 2, 3),     // 4 patch indices, so the count is plausible
                    utf8Bytes("bd"),
                    TestSegments.leInts(0, 1, 2)         // only 3 offsets = 2 values
            };

            // When — row 3 resolves to patch 3, past what the offsets child covers
            VarBinArray result = assertVarBin(decode(nullableUtf8(), 4, 0, PType.U32, 5, segs,
                    primitiveNode(1), varBinNode(2, 3)));

            // Then — the broadcast wraps to offsets [0, 1), still inside the 2-byte payload
            assertThat(result.getString(3)).isEqualTo("b");
        }

        /// `IndexOutOfBoundsException` from `MemorySegment.copy` under the eager merge —
        /// non-monotonic patch-value offsets give a patch a negative length. Read lazily, the
        /// same pair reaches the shared varbin length check on the row that uses it.
        @Test
        void varBinNonMonotonicValueOffsets_throwsOnRead() {
            // Given — offsets 0, 2, 0: patch 0 is 2 bytes long, patch 1 is -2
            MemorySegment[] segs = {
                    nullFill(),
                    TestSegments.leInts(0, 1),
                    utf8Bytes("bd"),
                    TestSegments.leInts(0, 2, 0)
            };

            // When — row 1 resolves to patch 1, whose [2, 0) range runs backwards
            VarBinArray result = assertVarBin(decode(nullableUtf8(), 2, 0, PType.U32, 5, segs,
                    primitiveNode(1), varBinNode(2, 3)));

            // Then
            assertThatThrownBy(() -> result.getString(1))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for a data buffer");
        }

        /// The offsets-vs-payload cross-check: a patch-value offsets buffer claiming more bytes
        /// than the value buffer holds must not read past the payload. Nothing is sized from the
        /// offsets any more — no buffer is allocated at all — so the guard that matters is the
        /// per-row one on the read.
        @Test
        void varBinValueOffsetsBeyondValueBuffer_throwsOnRead() {
            // Given — 2 bytes of value data but offsets claiming a 1 GiB final patch
            MemorySegment[] segs = {
                    nullFill(),
                    TestSegments.leInts(0, 1),
                    utf8Bytes("bd"),
                    TestSegments.leInts(0, 1, 1 << 30)
            };

            // When — row 1 resolves to patch 1, whose end offset is past the 2-byte payload
            VarBinArray result = assertVarBin(decode(nullableUtf8(), 2, 0, PType.U32, 5, segs,
                    primitiveNode(1), varBinNode(2, 3)));

            // Then
            assertThatThrownBy(() -> result.getString(1))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range for a data buffer");
        }

        /// A null fill wraps the values in a [MaskedArray]; the adversarial reads above target
        /// the values array underneath it.
        private static VarBinArray assertVarBin(Array result) {
            Array inner = result instanceof MaskedArray m ? m.inner() : result;
            assertThat(inner).isInstanceOf(VarBinArray.class);
            return (VarBinArray) inner;
        }
    }

    private static MemorySegment empty() {
        return MemorySegment.ofArray(new byte[0]);
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
