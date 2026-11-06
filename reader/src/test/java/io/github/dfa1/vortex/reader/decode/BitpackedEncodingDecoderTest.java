package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoBitPackedMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

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

    /// Adversarial metadata and buffers from an untrusted file (AGENTS.md §Security contract,
    /// per-encoding adversarial tests). `bit_width` was read straight off the wire and fed
    /// into the shift and byte-offset math of the unpack loops, so a negative or oversized
    /// width — or a `packed` buffer too short for the declared row count — escaped as a raw
    /// `IndexOutOfBoundsException` from the segment reads instead of a [VortexException].
    @Nested
    class AdversarialMetadata {

        @Test
        void negativeBitWidth_throws() {
            // Given bit_width = -1, which makes every shift and mask nonsensical
            DecodeContext ctx = ctxWithMeta(new ProtoBitPackedMetadata(-1, 0, null), packedBytes(64), 8);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("bit width -1 out of range [0,32] for I32");
        }

        @Test
        void bitWidthAboveElementWidth_throws() {
            // Given bit_width = 40 on an I32 column: below the 64-bit ceiling but wider than
            // the column's own elements, so the bound has to be the element width
            DecodeContext ctx = ctxWithMeta(new ProtoBitPackedMetadata(40, 0, null), packedBytes(64), 8);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("bit width 40 out of range [0,32] for I32");
        }

        @Test
        void bitWidthAboveSixtyFour_throws() {
            // Given bit_width = 65, past even the widest supported element
            DecodeContext ctx = ctxWithMeta(new ProtoBitPackedMetadata(65, 0, null), packedBytes(64), 8);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("bit width 65 out of range");
        }

        @Test
        void negativeOffset_throws() {
            // Given a negative in-block offset (a u32 large enough to wrap when read as int)
            DecodeContext ctx = ctxWithMeta(new ProtoBitPackedMetadata(8, -1, null), packedBytes(64), 8);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("offset -1 out of range [0,1024)");
        }

        @Test
        void offsetPastBlockSize_throws() {
            // Given an in-block offset far past the 1024-element FastLanes block: it is not
            // just wrong, it inflates the block count so the decoder would grind through
            // billions of skipped lanes from a single metadata varint before failing
            DecodeContext ctx = ctxWithMeta(new ProtoBitPackedMetadata(8, Integer.MAX_VALUE, null),
                    packedBytes(64), 4);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range [0,1024)");
        }

        @Test
        void packedBufferTooSmallForRowCount_throws() {
            // Given 1024 rows at bit_width 8 (one full 1024-element block = 1024 packed
            // bytes for I32) but only 4 bytes of packed data on the wire
            DecodeContext ctx = ctxWithMeta(new ProtoBitPackedMetadata(8, 0, null), packedBytes(4), 1024);

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("too small for 1024 rows at bit width 8");
        }

        @Test
        void nonPrimitiveDtype_throws() {
            // Given a struct column whose segment claims bit-packing (a fuzzer finding: the
            // decoder cast the dtype blindly and escaped as ClassCastException)
            ArrayNode node = new ArrayNode(BITPACKED, MemorySegment.ofArray(new ProtoBitPackedMetadata(8, 0, null).encode()),
                    new ArrayNode[0], new int[]{0});
            DType struct = new DType.Struct(List.of(), List.of(), false);
            DecodeContext ctx = new DecodeContext(node, struct, 8, new MemorySegment[]{packedBytes(64)}, REGISTRY,
                    Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("expected primitive dtype");
        }

        private DecodeContext ctxWithMeta(ProtoBitPackedMetadata meta, MemorySegment packed, long rowCount) {
            ArrayNode node = new ArrayNode(BITPACKED, MemorySegment.ofArray(meta.encode()),
                    new ArrayNode[0], new int[]{0});
            return new DecodeContext(node, I32, rowCount, new MemorySegment[]{packed}, REGISTRY, Arena.ofAuto());
        }

        private MemorySegment packedBytes(int n) {
            return MemorySegment.ofArray(new byte[n]);
        }
    }


    /// Value-level round trip for every width of every integer ptype. The validity fixtures above
    /// all use `bit_width=0`, so nothing else pins the 8/16/32/64-bit unpack kernels or the
    /// per-row schedule (shifts, masks, word spill into the next word): a wrong shift or mask
    /// there silently corrupts values and only integration tests would notice. The reference
    /// packer below is written from the FastLanes layout, independently of the decoder. The row
    /// counts cover a partial block, several blocks, and a non-zero offset into the first block.
    @ParameterizedTest(name = "{0} bitWidth={1} rows={2} offset={3}")
    @MethodSource("roundTripCases")
    void unpack_roundTrip_matchesReferencePacker(PType ptype, int bitWidth, int rows, int offset) {
        // Given
        int typeBits = ptype.bits();
        Random random = new Random(31L * typeBits + bitWidth);
        long mask = FastLanes.lowMask(bitWidth);
        long[] logical = new long[rows + offset];
        for (int i = 0; i < logical.length; i++) {
            logical[i] = random.nextLong() & mask;
        }
        ArrayNode node = new ArrayNode(BITPACKED, MemorySegment.ofArray(new ProtoBitPackedMetadata(bitWidth, offset, null).encode()),
                new ArrayNode[0], new int[]{0});
        DecodeContext ctx = new DecodeContext(node, new DType.Primitive(ptype, false), rows,
                new MemorySegment[]{referencePack(logical, bitWidth, typeBits)}, REGISTRY, Arena.ofAuto());

        // When
        Array result = SUT.decode(ctx);

        // Then
        for (int i = 0; i < rows; i++) {
            assertThat(read(result, ptype, i)).as("row %d", i).isEqualTo(logical[offset + i]);
        }
    }

    private static Stream<Arguments> roundTripCases() {
        int[][] shapes = {{1, 0}, {1500, 37}, {2500, 0}};
        List<Arguments> cases = new ArrayList<>();
        for (PType ptype : List.of(PType.I8, PType.I16, PType.I32, PType.I64)) {
            for (int bitWidth = 1; bitWidth <= ptype.bits(); bitWidth++) {
                for (int[] shape : shapes) {
                    cases.add(Arguments.of(ptype, bitWidth, shape[0], shape[1]));
                }
            }
        }
        return cases.stream();
    }

    /// Packs `logical` the FastLanes way: per 1024-element block, lane `l` holds the values at
    /// `iterateIndex(row, l)` for `row` in `[0, typeBits)` as a little-endian bit stream of
    /// `bitWidth` words, interleaved so word `w` of lane `l` sits at word index `w * lanes + l`.
    private static MemorySegment referencePack(long[] logical, int bitWidth, int typeBits) {
        int lanes = 1024 / typeBits;
        int blocks = (logical.length + 1023) / 1024;
        ByteBuffer out = ByteBuffer.allocate(blocks * 128 * bitWidth).order(ByteOrder.LITTLE_ENDIAN);
        for (int block = 0; block < blocks; block++) {
            long[][] words = new long[lanes][bitWidth];
            for (int lane = 0; lane < lanes; lane++) {
                for (int row = 0; row < typeBits; row++) {
                    int idx = block * 1024 + FastLanes.iterateIndex(row, lane);
                    long v = idx < logical.length ? logical[idx] : 0L;
                    int bitPos = row * bitWidth;
                    int word = bitPos / typeBits;
                    int shift = bitPos % typeBits;
                    words[lane][word] |= v << shift;
                    if (shift + bitWidth > typeBits) {
                        words[lane][word + 1] |= v >>> (typeBits - shift);
                    }
                }
            }
            for (int w = 0; w < bitWidth; w++) {
                for (int lane = 0; lane < lanes; lane++) {
                    long word = words[lane][w];
                    switch (typeBits) {
                        case 8 -> out.put((byte) word);
                        case 16 -> out.putShort((short) word);
                        case 32 -> out.putInt((int) word);
                        default -> out.putLong(word);
                    }
                }
            }
        }
        MemorySegment seg = Arena.ofAuto().allocate(out.capacity(), 8);
        seg.copyFrom(MemorySegment.ofArray(out.array()));
        return seg;
    }

    /// Reads row `i` zero-extended to its `ptype` width, so it compares with the unsigned logical value.
    private static long read(Array array, PType ptype, int i) {
        return switch (ptype) {
            case I8 -> ((ByteArray) array).getByte(i) & 0xFFL;
            case I16 -> ((ShortArray) array).getShort(i) & 0xFFFFL;
            case I32 -> ((IntArray) array).getInt(i) & 0xFFFFFFFFL;
            default -> ((LongArray) array).getLong(i);
        };
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
