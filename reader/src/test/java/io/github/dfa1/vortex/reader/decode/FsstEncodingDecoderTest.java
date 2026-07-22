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
            // value (a constant-encoded offsets child, capacity 1), while the row count exceeds
            // ROWS_PER_DECODE_BATCH (256). This is the regression guard for the batched fast path,
            // which read offsets at row indices 256, 512, ... — off the end of the 1-element offsets
            // segment — until the capacity-1 case was split out to decode nothing instead.
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
    }

    @Nested
    class Guards {

        @Test
        void rowCountAtIntMax_throws() {
            // Given — n == Integer.MAX_VALUE overflows the I32 offset array sizing; the guard rejects
            // it before any allocation. Empty buffers/children suffice because the check fires first.
            long[] symbols = {};
            byte[] symbolLengths = {};
            byte[] compressed = {};
            // Broadcast (single-element) children keep the fixtures tiny while claiming a huge n.
            long[] uncompLengths = {0};
            long[] codeOffsets = {0};

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> decodeFsst(Integer.MAX_VALUE, symbols, symbolLengths, compressed,
                            PType.U8, uncompLengths, PType.U8, codeOffsets))
                    .withMessageContaining("row count too large");
        }

        @Test
        void emptyUncompressedLengthsChild_throws() {
            // Given — n > 0 but the uncompressed-lengths child has zero physical elements. There is
            // no per-row length to read, so the prefix-sum loop cannot proceed.
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00};
            long[] uncompLengths = {};                        // empty length child with n == 1
            long[] codeOffsets = {0, 1};

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> decodeFsst(1, symbols, symbolLengths, compressed,
                            PType.U8, uncompLengths, PType.U8, codeOffsets))
                    .withMessageContaining("empty uncompressed-lengths child");
        }

        @Test
        void emptyCodesOffsetsChild_throws() {
            // Given — n > 0 but the code-offsets child is empty. Without offsets there is no code
            // range for any row, so the fast path's codesOffCap == 0 branch must reject it.
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00};
            long[] uncompLengths = {2};
            long[] codeOffsets = {};                          // empty offsets child with n == 1

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> decodeFsst(1, symbols, symbolLengths, compressed,
                            PType.U8, uncompLengths, PType.U8, codeOffsets))
                    .withMessageContaining("empty codes-offsets child");
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

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> decodeFsst(1, symbols, symbolLengths, compressed,
                            PType.U8, uncompLengths, PType.U8, codeOffsets))
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

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> decodeFsst(1, symbols, symbolLengths, compressed,
                            PType.U8, uncompLengths, PType.U8, codeOffsets))
                    .withMessageContaining("invalid code offsets");
        }

        @Test
        void decodedLengthMismatchesClaim_throws() {
            // Given — a valid symbol table plus a code stream that decodes to FEWER bytes than the
            // uncompressed-lengths child claims: the stream is a single "ab" symbol (2 bytes) but the
            // length child claims 5. The fast-path post-decode check must catch the disagreement.
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00};                       // decodes to exactly "ab" (2 bytes)
            long[] uncompLengths = {5};                       // but the child claims 5 bytes
            long[] codeOffsets = {0, 1};

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> decodeFsst(1, symbols, symbolLengths, compressed,
                            PType.U8, uncompLengths, PType.U8, codeOffsets))
                    .withMessageContaining("uncompressed lengths claim");
        }

        @Test
        void constantCodeOffsetsButLengthsClaimBytes_throws() {
            // Given — a capacity-1 (constant) offsets child means every row's code range is empty,
            // so the column must decode to zero bytes; but the uncompressed-lengths child claims
            // non-zero bytes per row. The two disagree, so the file is malformed and must be
            // rejected rather than silently returning zero-filled output.
            int rows = 300;
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00};
            long[] uncompLengths = {5};                       // broadcast: claims 5 bytes per row
            long[] codeOffsets = {0};                         // capacity 1: all ranges empty

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> decodeFsst(rows, symbols, symbolLengths, compressed,
                            PType.U8, uncompLengths, PType.U8, codeOffsets))
                    .withMessageContaining("constant code offsets imply an empty column");
        }

        @Test
        void totalUncompressedOverflowsIntMax_throws() {
            // Given — an I64 length child whose few huge values sum past Integer.MAX_VALUE. The
            // output offsets are I32 prefix sums, so this would silently wrap; the step-1 guard must
            // reject it. Placed BEFORE the outBytes allocation, so the test must fail without ever
            // attempting a multi-gigabyte allocation. Two rows of ~1.5 GB each overflow I32.
            long huge = 1_500_000_000L;
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00, 0x00};
            long[] uncompLengths = {huge, huge};              // sum == 3e9 > Integer.MAX_VALUE
            long[] codeOffsets = {0, 1, 2};

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> decodeFsst(2, symbols, symbolLengths, compressed,
                            PType.I64, uncompLengths, PType.U8, codeOffsets))
                    .withMessageContaining("decoded length too large");
        }

        @Test
        void negativeTotalUncompressed_throws() {
            // Given — an adversarial I32 length whose value is negative; the running prefix sum goes
            // negative, which the step-1 guard must reject (a negative size would otherwise flow into
            // the allocation and throw a raw exception instead of a sanitized VortexException).
            long[] symbols = {packSymbol("ab")};
            byte[] symbolLengths = {2};
            byte[] compressed = {0x00};
            long[] uncompLengths = {-1};                      // I32 -1 => sum == -1
            long[] codeOffsets = {0, 1};

            // When / Then
            assertThatExceptionOfType(VortexException.class)
                    .isThrownBy(() -> decodeFsst(1, symbols, symbolLengths, compressed,
                            PType.I32, uncompLengths, PType.U8, codeOffsets))
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
        MemorySegment symbolsSeg = leLongs(symbols);
        MemorySegment symbolLensSeg = bytes(symbolLengths);
        MemorySegment compressedSeg = bytes(compressed);
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
