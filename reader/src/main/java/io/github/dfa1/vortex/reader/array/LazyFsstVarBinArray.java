package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.fsst.Decompressor;
import io.github.dfa1.vortex.reader.decode.SegmentBroadcast;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntConsumer;

/// Lazy [VarBinArray] backed by an undecoded `vortex.fsst` column.
///
/// FSST's per-row code range (`codesOffsets[i] .. codesOffsets[i+1]`) is independent of every
/// other row, so — unlike the window-based encodings (`Bitpacked`, `Pco`, `Zstd`) ADR 0010
/// originally grouped it with — a single row decodes without touching its neighbors. This class
/// exploits that: [#getBytes(long)] decompresses only the requested row's code range on each
/// call, and [#getByteLength(long)] and [#forEachByteLength(IntConsumer)] read the wire's own
/// per-row uncompressed-length child directly, never invoking the decompressor at all. See
/// ADR 0026.
///
/// [#bytesSegment()] is the [MemorySegment#NULL] sentinel and [#segmentIfPresent()] is empty — no
/// single contiguous buffer holds the expanded rows, the same convention [VarBinChunkedArray],
/// [VarBinRunEndArray], [VarBinSparseArray], and [VarBinConstantArray] use. A consumer that needs
/// the flat bytes-plus-offsets shape gets it via
/// [VarBinArray#toOffsetMode(VarBinArray, java.lang.foreign.SegmentAllocator)], which walks every
/// row through [#getBytes(long)] — decoding the whole column exactly once, on demand.
///
/// Per-row bounds are validated on access, never at construction: an untrusted length or code
/// range surfaces as a [VortexException] the first time the offending row is read, mirroring
/// [VarBinArrays#checkedLength(MemorySegment, long, long)]'s "offsets are not scanned at decode
/// time" convention. A row's claimed uncompressed length is cross-checked against the maximum a
/// symbol-table decode of its code range could ever produce (8 bytes per compressed byte, the FSST
/// paper's own bound — see [Decompressor#decompress(MemorySegment, long, long, MemorySegment,
/// long)]) rather than trusted outright, so a corrupted or adversarial length can never drive an
/// oversized allocation.
///
/// @param dtype                logical type (Utf8 or Binary)
/// @param length                number of logical elements (rows)
/// @param decompressor          decoder bound to this column's symbol table
/// @param compressedBytes       the FSST code stream (buffer 2 of the `vortex.fsst` node)
/// @param uncompressedLengths   per-row decoded byte count child (length = `length`, broadcastable)
/// @param uncompressedLengthsPType physical type of `uncompressedLengths`
/// @param codesOffsets          per-row code range child (length = `length + 1`, broadcastable)
/// @param codesOffsetsPType     physical type of `codesOffsets`
public record LazyFsstVarBinArray(
        DType dtype, long length, Decompressor decompressor,
        MemorySegment compressedBytes,
        MemorySegment uncompressedLengths, PType uncompressedLengthsPType,
        MemorySegment codesOffsets, PType codesOffsetsPType)
        implements VarBinArray {

    /// No single contiguous segment backs the lazily decoded rows.
    ///
    /// @return the [MemorySegment#NULL] sentinel
    @Override
    public MemorySegment bytesSegment() {
        return MemorySegment.NULL;
    }

    /// No single contiguous segment backs the lazily decoded rows.
    ///
    /// @return always empty
    @Override
    public Optional<MemorySegment> segmentIfPresent() {
        return Optional.empty();
    }

    @Override
    public byte[] getBytes(long i) {
        Objects.checkIndex(i, length);
        CodeRange range = codeRange(i);
        long maxLen = range.maxDecodedLength();
        long claimedLen = uncompressedLength(i);
        if (claimedLen < 0 || claimedLen > maxLen) {
            throw new VortexException(EncodingId.VORTEX_FSST, "decoded length too large: row " + i
                    + " claims " + claimedLen + " bytes but code range implies at most " + maxLen);
        }
        // claimedLen <= maxLen and maxLen was already range-checked against Integer.MAX_VALUE - 7
        // in getByteLength's callers historically; re-check directly since this path no longer
        // routes through getByteLength.
        if (claimedLen > Integer.MAX_VALUE) {
            throw new VortexException(EncodingId.VORTEX_FSST, "decoded length too large: row " + i
                    + " claims " + claimedLen + " bytes");
        }
        // Exactly-sized destination: no slack, no copy. Decompressor's byte[] overload falls back
        // to a per-byte store only for the final symbol, where an 8-byte store would overrun this
        // array.
        byte[] out = new byte[(int) claimedLen];
        long decodedLen;
        try {
            decodedLen = decompressor.decompress(compressedBytes, range.start(), range.end(), out, 0);
        } catch (IndexOutOfBoundsException e) {
            // Two adversarial shapes land here: a trailing escape code with no literal byte after
            // it (reads one byte past the row's own code range, potentially past compressedBytes
            // entirely) and a non-escape code naming a symbol index past the trained table's size
            // (ArrayIndexOutOfBoundsException, a subtype of IndexOutOfBoundsException, indexing
            // Decompressor's packedSymbols/lengths arrays). Both must surface as VortexException,
            // never a raw JDK exception (the reader parses untrusted binary input).
            throw new VortexException(EncodingId.VORTEX_FSST,
                    "row " + i + " code range [" + range.start() + ", " + range.end()
                            + ") decodes past its bounds or references an unknown symbol code", e);
        }
        if (decodedLen != claimedLen) {
            throw new VortexException(EncodingId.VORTEX_FSST, "row " + i + " decoded " + decodedLen
                    + " bytes but uncompressed lengths claim " + claimedLen);
        }
        return out;
    }

    @Override
    public String getString(long i) {
        return new String(getBytes(i), StandardCharsets.UTF_8);
    }

    @Override
    public int getByteLength(long i) {
        Objects.checkIndex(i, length);
        long maxLen = codeRange(i).maxDecodedLength();
        long claimedLen = uncompressedLength(i);
        if (claimedLen < 0 || claimedLen > maxLen) {
            throw new VortexException(EncodingId.VORTEX_FSST, "decoded length too large: row " + i
                    + " claims " + claimedLen + " bytes but code range implies at most " + maxLen);
        }
        return (int) claimedLen;
    }

    /// Sums per-row lengths straight from the uncompressed-lengths child — no decompression, no
    /// per-row bounds cross-check (matching [VarBinOffsetArray#forEachByteLength(IntConsumer)]'s
    /// "bulk walk trusts the data, typed accessors validate it" convention). Branch-split per ptype
    /// and on the broadcast case so the per-row body stays a uniform, fixed-stride read
    /// (CLAUDE.md hot-loop rule).
    ///
    /// @param c consumer called once per row with the claimed byte length at that index
    @Override
    public void forEachByteLength(IntConsumer c) {
        long n = length;
        long cap = SegmentBroadcast.capacity(uncompressedLengths, uncompressedLengthsPType.byteSize());
        if (cap == 0) {
            if (n == 0) {
                return;
            }
            throw new VortexException(EncodingId.VORTEX_FSST, "empty uncompressed-lengths child");
        }
        if (cap >= n) {
            forEachClaimedLength(c, n);
            return;
        }
        for (long i = 0; i < n; i++) {
            c.accept((int) readAt(uncompressedLengths, (i % cap) * uncompressedLengthsPType.byteSize(),
                    uncompressedLengthsPType));
        }
    }

    /// Zero-copy truncation: rows are resolved on read, so trailing rows past the new length
    /// simply go unvisited.
    ///
    /// @param rows number of leading rows to keep
    /// @return a length-`rows` view over the same underlying segments
    @Override
    public VarBinArray limited(long rows) {
        if (rows >= length) {
            return this;
        }
        return new LazyFsstVarBinArray(dtype, rows, decompressor, compressedBytes,
                uncompressedLengths, uncompressedLengthsPType, codesOffsets, codesOffsetsPType);
    }

    /// Bulk fast path for [VarBinArray#toOffsetMode(VarBinArray, SegmentAllocator)]: decodes the
    /// entire column's code stream in one [Decompressor#decompress(MemorySegment, long, long,
    /// MemorySegment, long)] call instead of walking rows one at a time through [#getBytes(long)]
    /// (each of which pays its own allocation) — the single largest win identified for FSST
    /// materialization, since row code ranges are contiguous and monotonic in the code stream.
    ///
    /// Row offsets come from a prefix sum over the claimed uncompressed lengths — the same values
    /// [#forEachByteLength(IntConsumer)] already trusts without a per-row cross-check against the
    /// code range. The `decodedLen != totalBytes` check below is this method's equivalent of that
    /// convention: it catches a wrong aggregate total but, like `forEachByteLength`, cannot catch
    /// one row's claim being wrong while another's absorbs the difference. Per-row callers that
    /// need the full per-row cross-check still get it through [#getBytes(long)].
    ///
    /// @param arena allocator for the materialized bytes and offsets segments
    /// @return a [VarBinOffsetArray] view over the same logical content
    VarBinOffsetArray toOffsetModeBulk(SegmentAllocator arena) {
        long n = length;
        MemorySegment outOffsets = arena.allocate((n + 1) * Long.BYTES, Long.BYTES);
        outOffsets.setAtIndex(VortexFormat.LE_LONG, 0, 0L);
        long[] runningTotal = {0L};
        long[] nextIndex = {1L};
        forEachByteLength(len -> {
            runningTotal[0] += len;
            outOffsets.setAtIndex(VortexFormat.LE_LONG, nextIndex[0]++, runningTotal[0]);
        });
        long totalBytes = runningTotal[0];

        long start = codeOffset(0);
        long end = codeOffset(n);
        if (start < 0 || end < start || end > compressedBytes.byteSize()) {
            throw new VortexException(EncodingId.VORTEX_FSST, "invalid code offsets [" + start
                    + ", " + end + ") of " + compressedBytes.byteSize());
        }
        // 7 bytes of trailing slack for the decompressor's unconditional 8-byte-store trick;
        // sliced off below so the caller sees an exactly-sized bytes segment.
        MemorySegment scratch = arena.allocate(totalBytes + 7);
        long decodedLen;
        try {
            decodedLen = decompressor.decompress(compressedBytes, start, end, scratch, 0);
        } catch (IndexOutOfBoundsException e) {
            throw new VortexException(EncodingId.VORTEX_FSST,
                    "code range [" + start + ", " + end
                            + ") decodes past its bounds or references an unknown symbol code", e);
        }
        if (decodedLen != totalBytes) {
            throw new VortexException(EncodingId.VORTEX_FSST, "column decoded " + decodedLen
                    + " bytes but uncompressed lengths claim " + totalBytes);
        }
        MemorySegment outBytes = scratch.asSlice(0, totalBytes).asReadOnly();
        return new VarBinOffsetArray(dtype, n, outBytes, outOffsets, PType.I64);
    }

    /// Fast path of [#forEachByteLength(IntConsumer)] when the uncompressed-lengths child holds at
    /// least `n` physical elements: reads at a constant stride per ptype, no per-row modulo.
    private void forEachClaimedLength(IntConsumer c, long n) {
        switch (uncompressedLengthsPType) {
            case U8 -> {
                for (long i = 0; i < n; i++) {
                    c.accept(Byte.toUnsignedInt(uncompressedLengths.get(ValueLayout.JAVA_BYTE, i)));
                }
            }
            case U16 -> {
                for (long i = 0; i < n; i++) {
                    c.accept(Short.toUnsignedInt(uncompressedLengths.get(VortexFormat.LE_SHORT, i * 2)));
                }
            }
            case U32 -> {
                for (long i = 0; i < n; i++) {
                    c.accept((int) Integer.toUnsignedLong(
                            uncompressedLengths.getAtIndex(VortexFormat.LE_INT, i)));
                }
            }
            case I32 -> {
                for (long i = 0; i < n; i++) {
                    c.accept(uncompressedLengths.getAtIndex(VortexFormat.LE_INT, i));
                }
            }
            case I64, U64 -> {
                for (long i = 0; i < n; i++) {
                    c.accept((int) uncompressedLengths.getAtIndex(VortexFormat.LE_LONG, i));
                }
            }
            default -> throw new VortexException(EncodingId.VORTEX_FSST,
                    "unsupported ptype " + uncompressedLengthsPType);
        }
    }

    /// Row `i`'s validated compressed code range, plus the FSST-paper bound (8 bytes per
    /// compressed byte — [Decompressor]'s "unconditional 8-byte store" trick) on how many bytes
    /// decoding it could ever produce.
    ///
    /// @param start start offset into [#compressedBytes()], inclusive
    /// @param end   end offset into [#compressedBytes()], exclusive
    private record CodeRange(long start, long end) {
        long maxDecodedLength() {
            return (end - start) * 8;
        }
    }

    /// Reads and validates row `i`'s code range against [#compressedBytes()]'s actual size.
    private CodeRange codeRange(long i) {
        long start = codeOffset(i);
        long end = codeOffset(i + 1);
        if (start < 0 || end < start || end > compressedBytes.byteSize()) {
            throw new VortexException(EncodingId.VORTEX_FSST, "invalid code offsets [" + start
                    + ", " + end + ") of " + compressedBytes.byteSize() + " at row " + i);
        }
        return new CodeRange(start, end);
    }

    /// Reads codes-offsets element `idx`, broadcasting if the physical child is shorter than
    /// `length + 1` (the [SegmentBroadcast] convention shared with every other lazy accessor in
    /// this codebase).
    private long codeOffset(long idx) {
        long byteOffset = SegmentBroadcast.elementOffset(codesOffsets, idx, codesOffsetsPType.byteSize());
        return readAt(codesOffsets, byteOffset, codesOffsetsPType);
    }

    /// Reads the claimed uncompressed length of row `i`, broadcasting if the physical child is
    /// shorter than `length`.
    private long uncompressedLength(long i) {
        long byteOffset = SegmentBroadcast.elementOffset(uncompressedLengths, i, uncompressedLengthsPType.byteSize());
        return readAt(uncompressedLengths, byteOffset, uncompressedLengthsPType);
    }

    private static long readAt(MemorySegment seg, long byteOffset, PType ptype) {
        return switch (ptype) {
            case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, byteOffset));
            case U16 -> Short.toUnsignedLong(seg.get(VortexFormat.LE_SHORT, byteOffset));
            case U32 -> Integer.toUnsignedLong(seg.get(VortexFormat.LE_INT, byteOffset));
            case I32 -> seg.get(VortexFormat.LE_INT, byteOffset);
            case I64, U64 -> seg.get(VortexFormat.LE_LONG, byteOffset);
            default -> throw new VortexException(EncodingId.VORTEX_FSST, "unsupported ptype " + ptype);
        };
    }
}
