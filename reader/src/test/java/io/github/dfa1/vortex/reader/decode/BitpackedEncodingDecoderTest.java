package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoBitPackedMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Pins the validity-child propagation that [BitpackedEncodingDecoder] performs for the
/// `ValidityChild<BitPacked>` shape (#210): a trailing bool validity child that stacked
/// encodings (ALP, FoR) delegate down to bitpacked, and whose position in the child vector
/// depends on the patch shape. Dropping it silently un-nulls rows.
///
/// Every fixture uses `bit_width=0`, the real constant-residual shape seen when bitpacked is
/// nested under FoR/RLE (penguins `bill_length` decodes alp -> for -> bitpacked with the
/// residual collapsed to zero width). That makes the unpacked base deterministically all-zero,
/// so the tests isolate validity-index selection and mask wrapping from the packed-bit unpack.
/// Patch children then write real non-zero values, proving the patch path and validity index
/// coexist.
class BitpackedEncodingDecoderTest {

    private static final BitpackedEncodingDecoder SUT = new BitpackedEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            SUT, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());

    private static final EncodingId BITPACKED = EncodingId.FASTLANES_BITPACKED;
    private static final EncodingId PRIMITIVE = EncodingId.VORTEX_PRIMITIVE;
    private static final EncodingId BOOL = EncodingId.VORTEX_BOOL;
    private static final DType I32 = new DType.Primitive(io.github.dfa1.vortex.core.model.PType.I32, true);

    @Test
    void encodingId_isFastlanesBitpacked() {
        // Given / When / Then
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.FASTLANES_BITPACKED);
    }

    @Nested
    class ValidityChildIndex {

        @Test
        void noPatches_trailingValidityAtIndexZero_masksRows() {
            // Given — no patches, so the validity child is the first (index 0) child. This is the
            // penguins bill_length shape: bitpacked residual (bit_width 0 -> all zero) carrying only
            // a validity child. Rows 0,2,4 valid; 1,3,5 null.
            byte[] meta = new ProtoBitPackedMetadata(0, 0, null).encode();
            MemorySegment validity = boolBitmap(true, false, true, false, true, false);
            ArrayNode validityNode = new ArrayNode(BOOL, null, new ArrayNode[0], new int[]{1});
            ArrayNode node = new ArrayNode(BITPACKED, MemorySegment.ofArray(meta),
                    new ArrayNode[]{validityNode}, new int[]{0});
            DecodeContext ctx = new DecodeContext(node, I32, 6,
                    new MemorySegment[]{empty(), validity}, REGISTRY, Arena.ofAuto());

            // When
            Array result = SUT.decode(ctx);

            // Then — a MaskedArray whose validity mirrors the child; base values all zero
            MaskedArray masked = assertMasked(result);
            assertValidity(masked, true, false, true, false, true, false);
            IntArray inner = (IntArray) masked.inner();
            for (int i = 0; i < 6; i++) {
                assertThat(inner.getInt(i)).as("row %d", i).isZero();
            }
        }

        @Test
        void patchesWithoutChunkOffsets_validityAtIndexTwo_masksRows() {
            // Given — patches present but no chunk offsets: children are [indices, values, validity],
            // so validity sits at index 2. One patch overwrites row 2 with 700; rows 1,4 null.
            ProtoPatchesMetadata pm = new ProtoPatchesMetadata(1L, 0L, ProtoPType.U8, null, null, null);
            byte[] meta = new ProtoBitPackedMetadata(0, 0, pm).encode();

            ArrayNode indices = new ArrayNode(PRIMITIVE, null, new ArrayNode[0], new int[]{1});
            ArrayNode values = new ArrayNode(PRIMITIVE, null, new ArrayNode[0], new int[]{2});
            ArrayNode validityNode = new ArrayNode(BOOL, null, new ArrayNode[0], new int[]{3});
            ArrayNode node = new ArrayNode(BITPACKED, MemorySegment.ofArray(meta),
                    new ArrayNode[]{indices, values, validityNode}, new int[]{0});

            MemorySegment[] segs = {
                    empty(),                                  // 0: packed (unread, bit_width 0)
                    MemorySegment.ofArray(new byte[]{2}),     // 1: patch index -> row 2
                    TestSegments.leInts(700),                 // 2: patch value
                    boolBitmap(true, false, true, true, false) // 3: validity
            };
            DecodeContext ctx = new DecodeContext(node, I32, 5, segs, REGISTRY, Arena.ofAuto());

            // When
            Array result = SUT.decode(ctx);

            // Then — validity at index 2 survives; the patch wrote row 2
            MaskedArray masked = assertMasked(result);
            assertValidity(masked, true, false, true, true, false);
            IntArray inner = (IntArray) masked.inner();
            assertThat(inner.getInt(2)).isEqualTo(700);
            assertThat(inner.getInt(0)).isZero();
            assertThat(inner.getInt(4)).isZero();
        }

        @Test
        void patchesWithChunkOffsets_validityAtIndexThree_masksRows() {
            // Given — patches carrying chunk offsets: children are [indices, values, chunkOffsets,
            // validity], pushing validity to index 3. The chunk-offsets child is never decoded by the
            // patch path, so it is a placeholder. One patch overwrites row 1 with 900; rows 3,4 null.
            ProtoPatchesMetadata pm = new ProtoPatchesMetadata(1L, 0L, ProtoPType.U8, 1L, ProtoPType.U32, 0L);
            byte[] meta = new ProtoBitPackedMetadata(0, 0, pm).encode();

            ArrayNode indices = new ArrayNode(PRIMITIVE, null, new ArrayNode[0], new int[]{1});
            ArrayNode values = new ArrayNode(PRIMITIVE, null, new ArrayNode[0], new int[]{2});
            ArrayNode chunkOffsets = new ArrayNode(PRIMITIVE, null, new ArrayNode[0], new int[]{0}); // placeholder
            ArrayNode validityNode = new ArrayNode(BOOL, null, new ArrayNode[0], new int[]{3});
            ArrayNode node = new ArrayNode(BITPACKED, MemorySegment.ofArray(meta),
                    new ArrayNode[]{indices, values, chunkOffsets, validityNode}, new int[]{0});

            MemorySegment[] segs = {
                    empty(),
                    MemorySegment.ofArray(new byte[]{1}),     // patch index -> row 1
                    TestSegments.leInts(900),                 // patch value
                    boolBitmap(true, true, true, false, false)
            };
            DecodeContext ctx = new DecodeContext(node, I32, 5, segs, REGISTRY, Arena.ofAuto());

            // When
            Array result = SUT.decode(ctx);

            // Then — validity at index 3 survives; the patch wrote row 1
            MaskedArray masked = assertMasked(result);
            assertValidity(masked, true, true, true, false, false);
            IntArray inner = (IntArray) masked.inner();
            assertThat(inner.getInt(1)).isEqualTo(900);
            assertThat(inner.getInt(0)).isZero();
        }

        @Test
        void noValidityChild_returnsPlainArray() {
            // Given — no patches and zero children: nothing to mask, so the decoder returns the bare
            // values array, never a MaskedArray (the no-regression path for non-nullable columns).
            byte[] meta = new ProtoBitPackedMetadata(0, 0, null).encode();
            ArrayNode node = new ArrayNode(BITPACKED, MemorySegment.ofArray(meta),
                    new ArrayNode[0], new int[]{0});
            DecodeContext ctx = new DecodeContext(node, I32, 4,
                    new MemorySegment[]{empty()}, REGISTRY, Arena.ofAuto());

            // When
            Array result = SUT.decode(ctx);

            // Then
            assertThat(result).isInstanceOf(IntArray.class).isNotInstanceOf(MaskedArray.class);
        }

        @Test
        void unexpectedChildCount_throwsWithExpectedCounts() {
            // Given — no patches (expected 0 or 1 children) but two children are present: an
            // ambiguous shape a trusted encoder never emits, so decode fails loudly.
            byte[] meta = new ProtoBitPackedMetadata(0, 0, null).encode();
            ArrayNode a = new ArrayNode(BOOL, null, new ArrayNode[0], new int[]{1});
            ArrayNode b = new ArrayNode(BOOL, null, new ArrayNode[0], new int[]{1});
            ArrayNode node = new ArrayNode(BITPACKED, MemorySegment.ofArray(meta),
                    new ArrayNode[]{a, b}, new int[]{0});
            DecodeContext ctx = new DecodeContext(node, I32, 4,
                    new MemorySegment[]{empty(), boolBitmap(true, true, true, true)}, REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("expected 0 or 1 children")
                    .hasMessageContaining("got 2");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static MaskedArray assertMasked(Array result) {
        assertThat(result).isInstanceOf(MaskedArray.class);
        return (MaskedArray) result;
    }

    private static void assertValidity(MaskedArray masked, boolean... expected) {
        for (int i = 0; i < expected.length; i++) {
            assertThat(masked.isValid(i)).as("valid row %d", i).isEqualTo(expected[i]);
        }
    }

    /// Builds an LSB-first packed validity bitmap (`MaterializedBoolArray` layout) where bit `i`
    /// is set when `valid[i]` is true.
    private static MemorySegment boolBitmap(boolean... valid) {
        byte[] bytes = new byte[(valid.length + 7) / 8];
        for (int i = 0; i < valid.length; i++) {
            if (valid[i]) {
                bytes[i >>> 3] |= (byte) (1 << (i & 7));
            }
        }
        return MemorySegment.ofArray(bytes);
    }

    private static MemorySegment empty() {
        return MemorySegment.ofArray(new byte[0]);
    }
}
