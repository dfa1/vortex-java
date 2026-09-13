package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoFSSTMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/// Unit tests for [FsstEncodingDecoder], focused on the untrusted-input guards that turn a
/// malformed `vortex.fsst` node into a [VortexException] rather than an
/// `ArrayIndexOutOfBoundsException`, `NegativeArraySizeException`, or a wrapped/oversized
/// allocation (the reader parses untrusted binary input; see the security contract in CLAUDE.md).
///
/// The wire node shape mirrors [io.github.dfa1.vortex.writer.encode.FsstEncodingEncoder]: 3 buffers
/// (symbols — one LE long per symbol; symbol lengths — one byte per symbol; compressed code stream)
/// plus 2 primitive children (uncompressed lengths, `n` elements; code offsets, `n + 1` elements)
/// plus a [ProtoFSSTMetadata] naming the two child ptypes. The children are `vortex.primitive`
/// leaves, so the test registry registers [PrimitiveEncodingDecoder] alongside the SUT.
class FsstEncodingDecoderTest {

    private static final FsstEncodingDecoder SUT = new FsstEncodingDecoder();
    private static final ReadRegistry REGISTRY =
            TestRegistry.ofDecoders(SUT, new PrimitiveEncodingDecoder());

    /// Escape code copied verbatim by the decompressor (see [io.github.dfa1.vortex.fsst.Decompressor]).
    private static final int ESCAPE = 0xFF;

    @Test
    void encodingId_isVortexFsst() {
        // Given / When / Then
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.VORTEX_FSST);
    }

    @Nested
    class HappyPath {

        @Test
        void decodesTinyNode_provingHarnessIsSound() {
            // Given — a symbol table of one symbol "ab" (wire code 0) plus a stream that uses both
            // that symbol and the escape path, so the round-trip exercises the fast decode loop, the
            // symbol store, and the escape branch at once. Row 0 = "ab" (code [0]); row 1 = "abZ"
            // (codes [0, ESCAPE, 'Z']) — 'Z' is not a symbol, so it must round-trip via the escape.
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00, 0x00, (byte) ESCAPE, 'Z'};
            long[] uncompLengths = {2, 3};                    // "ab" is 2 bytes, "abZ" is 3
            long[] codeOffsets = {0, 1, 4};                   // row 0 = [0,1), row 1 = [1,4)

            // When
            VarBinArray result = decodeFsst(2, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // Then
            assertThat(result.length()).isEqualTo(2);
            assertThat(result.getString(0)).isEqualTo("ab");
            assertThat(result.getString(1)).isEqualTo("abZ");
        }

        @Test
        void constantCodeOffsetsManyRows_decodesEmptyColumn() {
            // Given — a column of all-empty rows: every compressed offset collapses to one physical
            // value (a constant-encoded offsets child, capacity 1), across enough rows (300) that a
            // batched decode loop would have walked past a 1-element offsets segment — the
            // regression this fixture used to guard when decode() batch-decoded eagerly. The lazy
            // per-row decoder has no batch loop to run off the end of, but the broadcast offsets
            // must still resolve correctly for every row.
            int rows = 300;
            long[] symbols = {};
            byte[] symbolLengths = {};
            byte[] compressed = {};
            long[] uncompLengths = {0};                       // broadcast: every row is 0 bytes
            long[] codeOffsets = {0};                         // capacity 1: all ranges empty [0,0)

            // When
            VarBinArray result = decodeFsst(rows, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // Then — 300 empty strings, no out-of-bounds read.
            assertThat(result.length()).isEqualTo(rows);
            assertThat(result.getString(0)).isEmpty();
            assertThat(result.getString(rows - 1)).isEmpty();
        }

        @Test
        void bytesSegment_isNullSentinel_noContiguousBufferBacksLazyRows() {
            // Given — no single flat buffer holds the lazily decoded rows (same convention as
            // VarBinChunkedArray / VarBinRunEndArray / VarBinConstantArray).
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00};
            long[] uncompLengths = {2};
            long[] codeOffsets = {0, 1};

            // When
            VarBinArray result = decodeFsst(1, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // Then
            assertThat(result.bytesSegment()).isSameAs(MemorySegment.NULL);
            assertThat(result.segmentIfPresent()).isEmpty();
        }

        @Test
        void limited_returnsShorterZeroCopyView() {
            // Given
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00, 0x00};
            long[] uncompLengths = {2, 2};
            long[] codeOffsets = {0, 1, 2};
            VarBinArray sut = decodeFsst(2, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // When
            VarBinArray result = sut.limited(1);

            // Then
            assertThat(result.length()).isEqualTo(1);
            assertThat(result.getString(0)).isEqualTo("ab");
        }
    }

    @Nested
    class Laziness {

        @Test
        void decode_doesNotDecompressUntilRowIsAccessed() {
            // Given — row 0 is well-formed ("ab"); row 1's code range points past the compressed
            // buffer entirely, which would blow up any eager whole-column decode. decode() itself
            // must not touch row 1's code range at all.
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00};                       // only 1 code byte physically present
            long[] uncompLengths = {2, 99};
            long[] codeOffsets = {0, 1, 50};                  // row 1 = [1, 50) — far past the buffer

            // When
            VarBinArray result = decodeFsst(2, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // Then — decode() succeeded (no eager validation of row 1), row 0 reads correctly, and
            // only reading row 1 surfaces the malformed offsets.
            assertThat(result.getString(0)).isEqualTo("ab");
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> result.getString(1))
                    .withMessageContaining("invalid code offsets");
        }

        @Test
        void forEachByteLength_neverTouchesCodeOffsetsOrCompressedBytes() {
            // Given — the codes-offsets child and compressed buffer are both nonsense (a range past
            // the buffer's end), but forEachByteLength only ever reads the uncompressed-lengths
            // child, so it must return the claimed lengths without tripping over either.
            long[] symbols = {};
            byte[] symbolLengths = {};
            byte[] compressed = {0x00};
            long[] uncompLengths = {2, 3};
            long[] codeOffsets = {0, 50, 100};                // wildly out of range for `compressed`
            VarBinArray sut = decodeFsst(2, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // When
            List<Integer> lengths = new ArrayList<>();
            sut.forEachByteLength(lengths::add);

            // Then
            assertThat(lengths).containsExactly(2, 3);
        }
    }

    @Nested
    class AdversarialEdgeCases {

        @Test
        void zeroRows_decodesToNothing() {
            // Given — n == 0: no row is ever legal to read, and forEachByteLength must not try to
            // divide by a broadcast capacity of 0 just because the length child happens to be empty.
            long[] symbols = {};
            byte[] symbolLengths = {};
            byte[] compressed = {};
            long[] uncompLengths = {};
            long[] codeOffsets = {};

            // When
            VarBinArray result = decodeFsst(0, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);
            List<Integer> lengths = new ArrayList<>();
            result.forEachByteLength(lengths::add);

            // Then
            assertThat(result.length()).isZero();
            assertThat(lengths).isEmpty();
        }

        @Test
        void singleEmptyRowAmidNonEmptyRows_decodesCorrectly() {
            // Given — a genuinely empty row (start == end) sitting between two non-empty rows in the
            // physical (non-broadcast) fast path — distinct from the existing all-empty-broadcast
            // fixture, which never exercises a per-row zero-length range.
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00, 0x00};
            long[] uncompLengths = {2, 0, 2};
            long[] codeOffsets = {0, 1, 1, 2};                // row1 = [1, 1): empty
            VarBinArray result = decodeFsst(3, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // When / Then
            assertThat(result.getString(0)).isEqualTo("ab");
            assertThat(result.getString(1)).isEmpty();
            assertThat(result.getString(2)).isEqualTo("ab");
        }

        @Test
        void laterRowIsIndependentlyAccessible_whenAnEarlierRowIsStructurallyInvalid() {
            // Given — row 0's own offsets are descending (structurally invalid on its own), but row
            // 1's range is well-formed. True random access means reading row 1 must not require
            // validating — or even touching — row 0 first.
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00, 0x00};
            long[] uncompLengths = {99, 2};                   // row 0's claim is irrelevant; never read
            long[] codeOffsets = {3, 1, 2};                   // row 0 = [3, 1): invalid; row 1 = [1, 2): valid
            VarBinArray result = decodeFsst(2, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // When / Then — row 1 alone, never touching row 0.
            assertThat(result.getString(1)).isEqualTo("ab");
        }

        @Test
        void getBytes_repeatedAccessOfSameRowIsIdempotent() {
            // Given — nothing about decoding row i should mutate shared state that a later re-read
            // of the same row would observe.
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00};
            long[] uncompLengths = {2};
            long[] codeOffsets = {0, 1};
            VarBinArray result = decodeFsst(1, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // When
            String first = result.getString(0);
            String second = result.getString(0);

            // Then
            assertThat(first).isEqualTo("ab");
            assertThat(second).isEqualTo("ab");
        }

        @Test
        void getByteLength_claimedLengthExactlyAtCodeRangeBound_passes() {
            // Given — a maximum-length-8 FSST symbol decoded from a single compressed byte: the
            // claimed length (8) exactly equals the code range's bound (1 code * 8 bytes/code). The
            // boundary must be inclusive (<=), not exclusive.
            long[] symbols = {packSymbol("abcdefgh")};
            byte[] symbolLengths = {8};
            byte[] compressed = {0x00};
            long[] uncompLengths = {8};
            long[] codeOffsets = {0, 1};
            VarBinArray result = decodeFsst(1, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // When / Then
            assertThat(result.getByteLength(0)).isEqualTo(8);
            assertThat(result.getString(0)).isEqualTo("abcdefgh");
        }

        @Test
        void getByteLength_negativeCodeStart_throws() {
            // Given — an adversarial signed code-offset that is itself negative (not merely
            // descending relative to its pair).
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00, 0x00};
            long[] uncompLengths = {2};
            long[] codeOffsets = {-1, 5};
            VarBinArray result = decodeFsst(1, symbols, symbolLengths, compressed,
                    PType.I32, uncompLengths, PType.I32, codeOffsets);

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> result.getByteLength(0))
                    .withMessageContaining("invalid code offsets");
        }

        @Test
        void getBytes_codeRangeImpliesLengthBeyondIntMax_throwsWithoutAllocating() {
            // Given — a single row whose code range is exactly large enough that the FSST 8-bytes-
            // per-compressed-byte bound crosses Integer.MAX_VALUE - 7. The guard must reject this
            // BEFORE sizing a scratch array, or the (int) cast would silently wrap into a negative
            // size instead of a clean VortexException.
            long codeLen = 256L * 1024 * 1024;                // 256 MiB of code bytes => 2 GiB max output
            long[] symbols = {};
            byte[] symbolLengths = {};
            long[] uncompLengths = {0};                       // irrelevant: the bound check fires first
            long[] codeOffsets = {0, codeLen};
            VarBinArray result = decodeFsstWithHugeCompressedBuffer(codeLen, symbols, symbolLengths,
                    PType.U8, uncompLengths, PType.I64, codeOffsets);

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> result.getBytes(0))
                    .withMessageContaining("decoded length too large");
        }

        @Test
        void getBytes_truncatedEscapeAtBufferEnd_throwsVortexExceptionNotRaw() {
            // Given — a code range whose last code is the escape marker (0xFF) with no following
            // literal byte, and the buffer ends exactly there. Decompressor reads one byte past the
            // declared range to fetch the (nonexistent) literal; that read falls off the segment
            // entirely and must surface as a VortexException, not a raw IndexOutOfBoundsException
            // (the reader parses untrusted binary input; CLAUDE.md security contract).
            long[] symbols = {};
            byte[] symbolLengths = {};
            byte[] compressed = {(byte) 0xFF};                // ESCAPE, no literal follows
            long[] uncompLengths = {1};
            long[] codeOffsets = {0, 1};
            VarBinArray result = decodeFsst(1, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> result.getBytes(0));
        }

        @Test
        void getBytes_codeReferencesUnknownSymbol_throwsVortexExceptionNotRaw() {
            // Given — a code byte that names a symbol index past the trained table's size (the table
            // has 1 symbol, code 0; this row's code is 5). Decompressor indexes packedSymbols[5] on
            // an array of length 1, which must surface as a VortexException, not a raw
            // ArrayIndexOutOfBoundsException.
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x05};                       // code 5: no such symbol was trained
            long[] uncompLengths = {2};
            long[] codeOffsets = {0, 1};
            VarBinArray result = decodeFsst(1, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> result.getBytes(0));
        }
    }

    @Nested
    class Guards {

        @Test
        void rowCountAtIntMax_throws() {
            // Given — n == Integer.MAX_VALUE would overflow the codes-offsets child's `n + 1`
            // element count; the guard rejects it before that child is even decoded. Empty
            // buffers/children suffice because the check fires first.
            long[] symbols = {};
            byte[] symbolLengths = {};
            byte[] compressed = {};
            // Broadcast (single-element) children keep the fixtures tiny while claiming a huge n.
            long[] uncompLengths = {0};
            long[] codeOffsets = {0};

            // When / Then — decode() itself throws; this is the one guard that still fires eagerly.
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> decodeFsst(Integer.MAX_VALUE, symbols, symbolLengths, compressed,
                            PType.U8, uncompLengths, PType.U8, codeOffsets))
                    .withMessageContaining("row count too large");
        }

        // The remaining guards below all validate untrusted per-row content (lengths, code
        // offsets), which — like every other VarBinArray's offsets (see VarBinArrays.checkedLength)
        // — is deliberately not scanned at decode() time. decode() always succeeds; the malformed
        // input surfaces the first time the offending row is actually read, which is what proves
        // these rows are not being decoded up front. See the Laziness nested class above.

        @Test
        void emptyUncompressedLengthsChild_throws() {
            // Given — n > 0 but the uncompressed-lengths child has zero physical elements.
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00};
            long[] uncompLengths = {};                        // empty length child with n == 1
            long[] codeOffsets = {0, 1};
            VarBinArray result = decodeFsst(1, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // When / Then — decode() succeeded; reading row 0's length is where this surfaces.
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> result.getByteLength(0))
                    .withMessageContaining("empty");
        }

        @Test
        void emptyCodesOffsetsChild_throws() {
            // Given — n > 0 but the code-offsets child is empty: no code range exists for any row.
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00};
            long[] uncompLengths = {2};
            long[] codeOffsets = {};                          // empty offsets child with n == 1
            VarBinArray result = decodeFsst(1, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> result.getByteLength(0))
                    .withMessageContaining("empty");
        }

        @Test
        void codeOffsetsDescending_throws() {
            // Given — firstOffset > lastOffset. A descending offset pair would make the code range
            // negative-length; the guard must reject it rather than let the decompressor read wild.
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00, 0x00};
            long[] uncompLengths = {2};
            long[] codeOffsets = {2, 0};                      // first (2) > last (0)
            VarBinArray result = decodeFsst(1, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> result.getByteLength(0))
                    .withMessageContaining("invalid code offsets");
        }

        @Test
        void codeOffsetsPastCompressedBuffer_throws() {
            // Given — lastOffset exceeds the compressed buffer's size, so the code range points past
            // the end of the stream. The guard must reject it before the decompressor reads OOB.
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00};                       // only 1 code byte physically present
            long[] uncompLengths = {2};
            long[] codeOffsets = {0, 5};                      // claims 5 code bytes, buffer has 1
            VarBinArray result = decodeFsst(1, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> result.getByteLength(0))
                    .withMessageContaining("invalid code offsets");
        }

        @Test
        void decodedLengthMismatchesClaim_throws() {
            // Given — a valid symbol table plus a code stream that decodes to FEWER bytes than the
            // uncompressed-lengths child claims: the stream is a single "ab" symbol (2 bytes) but the
            // length child claims 5 (within the code range's 8-byte max, so getByteLength alone would
            // not catch this — only an actual decode-and-compare does).
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00};                       // decodes to exactly "ab" (2 bytes)
            long[] uncompLengths = {5};                       // but the child claims 5 bytes
            long[] codeOffsets = {0, 1};
            VarBinArray result = decodeFsst(1, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> result.getString(0))
                    .withMessageContaining("uncompressed lengths claim");
        }

        @Test
        void constantCodeOffsetsButLengthsClaimBytes_throws() {
            // Given — a capacity-1 (constant) offsets child means every row's code range is empty,
            // so the column must decode to zero bytes; but the uncompressed-lengths child claims
            // non-zero bytes per row. The two disagree, so the file is malformed; getByteLength's
            // code-range cross-check catches it without needing to decode anything.
            int rows = 300;
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00};
            long[] uncompLengths = {5};                       // broadcast: claims 5 bytes per row
            long[] codeOffsets = {0};                         // capacity 1: all ranges empty
            VarBinArray result = decodeFsst(rows, symbols, symbolLengths, compressed,
                    PType.U8, uncompLengths, PType.U8, codeOffsets);

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> result.getByteLength(0))
                    .withMessageContaining("decoded length too large");
        }

        @Test
        void totalUncompressedOverflowsIntMax_throws() {
            // Given — an I64 length child whose per-row value is individually implausible for its
            // 1-code range (max 8 bytes), even though each row's raw value alone fits comfortably
            // under Integer.MAX_VALUE. getByteLength's code-range cross-check catches it per row.
            long huge = 1_500_000_000L;
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00, 0x00};
            long[] uncompLengths = {huge, huge};
            long[] codeOffsets = {0, 1, 2};
            VarBinArray result = decodeFsst(2, symbols, symbolLengths, compressed,
                    PType.I64, uncompLengths, PType.U8, codeOffsets);

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> result.getByteLength(0))
                    .withMessageContaining("decoded length too large");
        }

        @Test
        void negativeTotalUncompressed_throws() {
            // Given — an adversarial I32 length whose value is negative.
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00};
            long[] uncompLengths = {-1};                      // I32 -1
            long[] codeOffsets = {0, 1};
            VarBinArray result = decodeFsst(1, symbols, symbolLengths, compressed,
                    PType.I32, uncompLengths, PType.U8, codeOffsets);

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> result.getByteLength(0))
                    .withMessageContaining("decoded length too large");
        }
    }

    // ── decode harness ─────────────────────────────────────────────────────────

    /// Builds and decodes a `vortex.fsst` node from the raw component arrays.
    ///
    /// Segment layout matches the encoder: buffers 0-2 are the FSST node's own (symbols, symbol
    /// lengths, compressed stream); buffers 3-4 back the two primitive children (uncompressed
    /// lengths, code offsets). The children's ptypes are carried in the [ProtoFSSTMetadata].
    private static VarBinArray decodeFsst(long rowCount, long[] symbols, byte[] symbolLengths,
            byte[] compressed, PType uncompLenPType, long[] uncompLengths,
            PType codesOffPType, long[] codeOffsets) {
        return decodeFsst(rowCount, symbols, symbolLengths, bytes(compressed),
                uncompLenPType, uncompLengths, codesOffPType, codeOffsets);
    }

    /// Variant of [#decodeFsst] for the code-range-overflow guard: a real 256 MiB+ compressed
    /// buffer is unwieldy as a `byte[]` literal, and its content is irrelevant — only its
    /// `byteSize()` needs to bound the (huge) code range under test. Allocated zero-filled, so it
    /// never round-trips through the decompressor before the guard rejects it.
    private static VarBinArray decodeFsstWithHugeCompressedBuffer(long compressedByteSize,
            long[] symbols, byte[] symbolLengths, PType uncompLenPType, long[] uncompLengths,
            PType codesOffPType, long[] codeOffsets) {
        MemorySegment compressedSeg = Arena.ofAuto().allocate(compressedByteSize);
        return decodeFsst(1, symbols, symbolLengths, compressedSeg,
                uncompLenPType, uncompLengths, codesOffPType, codeOffsets);
    }

    private static VarBinArray decodeFsst(long rowCount, long[] symbols, byte[] symbolLengths,
            MemorySegment compressedSeg, PType uncompLenPType, long[] uncompLengths,
            PType codesOffPType, long[] codeOffsets) {
        MemorySegment symbolsSeg = leLongs(symbols);
        MemorySegment symbolLensSeg = bytes(symbolLengths);
        MemorySegment uncompLensSeg = typedSegment(uncompLenPType, uncompLengths);
        MemorySegment codeOffsetsSeg = typedSegment(codesOffPType, codeOffsets);
        MemorySegment[] segs = {symbolsSeg, symbolLensSeg, compressedSeg, uncompLensSeg, codeOffsetsSeg};

        MemorySegment meta = MemorySegment.ofArray(new ProtoFSSTMetadata(
                ProtoPType.fromValue(uncompLenPType.ordinal()),
                ProtoPType.fromValue(codesOffPType.ordinal())).encode());

        ArrayNode uncompLensNode = primitiveNode(3);
        ArrayNode codeOffsetsNode = primitiveNode(4);
        ArrayNode fsstNode = new ArrayNode(EncodingId.VORTEX_FSST, meta,
                new ArrayNode[]{uncompLensNode, codeOffsetsNode}, new int[]{0, 1, 2});

        DecodeContext ctx = new DecodeContext(fsstNode, DType.UTF8, rowCount, segs, REGISTRY,
                Arena.ofAuto());
        return (VarBinArray) SUT.decode(ctx);
    }

    private static ArrayNode primitiveNode(int bufferIndex) {
        return new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{bufferIndex});
    }

    // ── segment builders ───────────────────────────────────────────────────────

    /// Packs a symbol string's UTF-8 bytes LSB-first into a `long` (byte 0 in the low byte), the
    /// [io.github.dfa1.vortex.fsst.Symbol] wire convention.
    private static long packSymbol(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        long value = 0;
        for (int k = 0; k < b.length; k++) {
            value |= Byte.toUnsignedLong(b[k]) << (k * 8);
        }
        return value;
    }

    private static MemorySegment leLongs(long[] values) {
        MemorySegment seg = Arena.ofAuto().allocate(Math.max(values.length * 8L, 1), 8);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(VortexFormat.LE_LONG, i, values[i]);
        }
        return seg;
    }

    private static MemorySegment bytes(byte[] values) {
        MemorySegment seg = Arena.ofAuto().allocate(Math.max(values.length, 1));
        for (int i = 0; i < values.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, values[i]);
        }
        return seg;
    }

    /// Writes `values` into a segment at the stride of `ptype`. Only the ptypes the FSST children
    /// can carry (U8, I32, I64) are needed by these tests. An empty `values` yields a zero-length
    /// segment (not a 1-byte floor) so the child decodes with capacity 0 — the shape the
    /// empty-child guards inspect.
    private static MemorySegment typedSegment(PType ptype, long[] values) {
        long stride = ptype.byteSize();
        if (values.length == 0) {
            return MemorySegment.ofArray(new byte[0]);
        }
        MemorySegment seg = Arena.ofAuto().allocate(values.length * stride, stride);
        for (int i = 0; i < values.length; i++) {
            switch (ptype) {
                case U8, I8 -> seg.set(ValueLayout.JAVA_BYTE, i, (byte) values[i]);
                case U16, I16 -> seg.set(VortexFormat.LE_SHORT, i * 2, (short) values[i]);
                case U32, I32 -> seg.setAtIndex(VortexFormat.LE_INT, i, (int) values[i]);
                case I64, U64 -> seg.setAtIndex(VortexFormat.LE_LONG, i, values[i]);
                default -> throw new IllegalArgumentException("unsupported ptype: " + ptype);
            }
        }
        return seg;
    }
}
