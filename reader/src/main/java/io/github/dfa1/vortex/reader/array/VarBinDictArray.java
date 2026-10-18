package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;

/// Dictionary-encoded [VarBinArray].
///
/// Stores dictionary values and per-row codes; all accessors resolve through the
/// dictionary without materializing strings at construction time.
///
/// [#getString(long)] decodes each dictionary entry once and returns the same `String` for every
/// row with that code (#551): a low-cardinality column would otherwise copy and decode the same
/// few values once per row. The Rust reference has no such cache because its strings are views
/// into the shared buffer; building a `java.lang.String` always copies, so the cost is JVM-only.
public final class VarBinDictArray implements VarBinArray {

    /// Most entries the string cache holds. The dictionary size comes from an untrusted offsets
    /// buffer, so codes past this bound decode per call rather than size a heap array from it.
    private static final int MAX_CACHED_STRINGS = 1 << 16;

    private final DType dtype;
    private final long length;
    private final MemorySegment bytesSegment;
    private final MemorySegment dictValOffsets;
    private final PType dictValOffPType;
    private final MemorySegment dictCodesSegs;
    private final PType dictCodesPType;
    /// Codes below this are cached; also bounded by `length` so a dictionary far larger than
    /// the rows (a shared dictionary over a small chunk) never allocates more than the rows do.
    private final int cacheSize;
    /// Allocated on the first [#getString(long)]. Unsynchronized on purpose: `String` is
    /// immutable, so a race only decodes an entry twice.
    private String[] strings;

    /// Creates a dictionary-encoded array.
    ///
    /// @param dtype           logical type (Utf8 or Binary)
    /// @param length          number of logical elements (rows)
    /// @param bytesSegment    concatenated raw bytes for all dictionary values
    /// @param dictValOffsets  offsets into `bytesSegment` for each dictionary entry (length = dictSize + 1)
    /// @param dictValOffPType physical type of the dictionary value offsets
    /// @param dictCodesSegs   per-row dictionary code indices (length = `length`)
    /// @param dictCodesPType  physical type of the dictionary codes
    public VarBinDictArray(DType dtype, long length, MemorySegment bytesSegment,
                           MemorySegment dictValOffsets, PType dictValOffPType,
                           MemorySegment dictCodesSegs, PType dictCodesPType) {
        this.dtype = dtype;
        this.length = length;
        this.bytesSegment = bytesSegment;
        this.dictValOffsets = dictValOffsets;
        this.dictValOffPType = dictValOffPType;
        this.dictCodesSegs = dictCodesSegs;
        this.dictCodesPType = dictCodesPType;
        long dictSize = dictValOffsets.byteSize() / dictValOffPType.byteSize() - 1;
        this.cacheSize = (int) Math.max(0, Math.min(Math.min(dictSize, length), MAX_CACHED_STRINGS));
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public MemorySegment bytesSegment() {
        return bytesSegment;
    }

    /// Returns the offsets into [#bytesSegment()] for each dictionary entry.
    ///
    /// @return the value offsets segment (length = dictSize + 1)
    public MemorySegment dictValOffsets() {
        return dictValOffsets;
    }

    /// Returns the physical type of the dictionary value offsets.
    ///
    /// @return the offsets ptype
    public PType dictValOffPType() {
        return dictValOffPType;
    }

    /// Returns the per-row dictionary codes.
    ///
    /// @return the codes segment (length = [#length()])
    public MemorySegment dictCodesSegs() {
        return dictCodesSegs;
    }

    /// Returns the physical type of the dictionary codes.
    ///
    /// @return the codes ptype
    public PType dictCodesPType() {
        return dictCodesPType;
    }

    /// Returns the dictionary values as an array, one element per entry, so a predicate can be
    /// evaluated once per entry instead of once per row (Rust's `DictArray::values`).
    ///
    /// @return a zero-copy view over the dictionary entries
    public VarBinOffsetArray values() {
        long dictSize = Math.max(0, dictValOffsets.byteSize() / dictValOffPType.byteSize() - 1);
        return new VarBinOffsetArray(dtype, dictSize, bytesSegment, dictValOffsets, dictValOffPType);
    }

    /// Returns the dictionary code of row `i`: the index of its entry in [#values()].
    ///
    /// @param i zero-based row index
    /// @return the code, widened to a signed long; not checked against the dictionary size
    public long code(long i) {
        return dictReadCode(i);
    }

    @Override
    public byte[] getBytes(long i) {
        long code = dictReadCode(i);
        long start = dictReadOff(code);
        long end = dictReadOff(code + 1);
        int len = VarBinArrays.checkedLength(bytesSegment, start, end);
        byte[] out = new byte[len];
        MemorySegment.copy(bytesSegment, start, MemorySegment.ofArray(out), 0, len);
        return out;
    }

    @Override
    public String getString(long i) {
        long code = dictReadCode(i);
        if (code < 0 || code >= cacheSize) {
            return new String(getBytes(i), StandardCharsets.UTF_8);
        }
        String[] cache = strings;
        if (cache == null) {
            cache = new String[cacheSize];
            strings = cache;
        }
        String s = cache[(int) code];
        if (s == null) {
            s = new String(getBytes(i), StandardCharsets.UTF_8);
            cache[(int) code] = s;
        }
        return s;
    }

    @Override
    public int getByteLength(long i) {
        long code = dictReadCode(i);
        return VarBinArrays.checkedLength(bytesSegment, dictReadOff(code), dictReadOff(code + 1));
    }

    @Override
    public void forEachByteLength(IntConsumer c) {
        // Hot loop: hoist both ptype dispatches out of the per-row body so C2 sees a
        // uniform, fixed-stride loop (CLAUDE.md hot-loop rule). A variable-target
        // switch(dictValOffPType)/switch(dictCodesPType) per element blocks C2
        // superword vectorization and adds invariant width/bounds arithmetic on every
        // row (regression introduced by #215). I32 dict-value offsets are by far the
        // most common (FSST + most dict encodings emit 32-bit offsets), so the fast
        // path reads offsets at a constant 4-byte stride and branch-splits the code
        // read once; wider offset ptypes take the general per-row path.
        //
        // The per-row body must stay branch-free, so the untrusted-code bounds check is
        // a boundary catch-and-wrap around the whole loop rather than a test per row:
        // an out-of-pool code (or a truncated codes buffer) trips the segment access and
        // must surface as a VortexException, never a raw IndexOutOfBoundsException. The
        // handler re-validates on the cold path so the blame — and the exception type —
        // land on whichever input actually failed, including the caller's own consumer.
        try {
            if (dictValOffPType == PType.I32) {
                forEachI32OffsetByteLength(c);
            } else {
                for (long i = 0; i < length; i++) {
                    long code = dictReadCode(i);
                    c.accept((int) (dictReadOff(code + 1) - dictReadOff(code)));
                }
            }
        } catch (IndexOutOfBoundsException e) {
            throw attribute(e);
        }
    }

    /// Cold path: works out which untrusted input made a bulk length walk run off the
    /// end, so the message names the segment that actually failed.
    ///
    /// Re-checks the codes extent, then every code against the value-offsets extent —
    /// affordable here because it only runs after something has already thrown. When
    /// both check out the failure came from the caller's [IntConsumer], and that
    /// exception is returned unchanged rather than relabeled as malformed input.
    ///
    /// @param e the out-of-bounds failure raised by the walk
    /// @return a [VortexException] describing the malformed input, or `e` itself
    private RuntimeException attribute(IndexOutOfBoundsException e) {
        int codeWidth = dictCodesPType.byteSize();
        if (length > dictCodesSegs.byteSize() / codeWidth) {
            return new VortexException("dict codes segment of " + dictCodesSegs.byteSize()
                    + " bytes holds fewer than " + length + " " + dictCodesPType + " codes");
        }
        long offsetCount = dictValOffsets.byteSize() / dictValOffPType.byteSize();
        for (long i = 0; i < length; i++) {
            long code = readCodeAt(i);
            if (code < 0 || code + 1 >= offsetCount) {
                return new VortexException("dict code " + code + " at row " + i
                        + " out of range for " + offsetCount + " value offsets");
            }
        }
        return e;
    }

    /// Fast path of [#forEachByteLength(IntConsumer)] for I32 dict-value offsets: the
    /// code-ptype switch is hoisted out of the loop so each specialized loop body reads
    /// codes at a single fixed stride and computes lengths from a constant 4-byte offset
    /// stride, leaving the per-row body uniform and vectorizable.
    ///
    /// @param c consumer called once per row with the byte length at that index
    private void forEachI32OffsetByteLength(IntConsumer c) {
        long n = length;
        switch (dictCodesPType) {
            case U8 -> {
                for (long i = 0; i < n; i++) {
                    c.accept(i32OffsetLength(
                            Byte.toUnsignedLong(dictCodesSegs.get(ValueLayout.JAVA_BYTE, i))));
                }
            }
            case U16 -> {
                for (long i = 0; i < n; i++) {
                    c.accept(i32OffsetLength(
                            Short.toUnsignedLong(dictCodesSegs.getAtIndex(VortexFormat.LE_SHORT, i))));
                }
            }
            case U32 -> {
                for (long i = 0; i < n; i++) {
                    c.accept(i32OffsetLength(
                            Integer.toUnsignedLong(dictCodesSegs.getAtIndex(VortexFormat.LE_INT, i))));
                }
            }
            case I32 -> {
                for (long i = 0; i < n; i++) {
                    c.accept(i32OffsetLength(dictCodesSegs.getAtIndex(VortexFormat.LE_INT, i)));
                }
            }
            case I64, U64 -> {
                for (long i = 0; i < n; i++) {
                    c.accept(i32OffsetLength(dictCodesSegs.getAtIndex(VortexFormat.LE_LONG, i)));
                }
            }
            default -> throw new VortexException("unsupported codes ptype: " + dictCodesPType);
        }
    }

    /// Byte length of the dictionary entry `code` when the value offsets are I32:
    /// `offsets[code + 1] - offsets[code]` read at a constant 4-byte stride. The
    /// segment access itself bounds-checks, so an out-of-range code is caught without
    /// the per-row width recomputation that [#dictReadOff(long)] performs.
    ///
    /// @param code zero-based dictionary entry index (in `[0, dictSize)`)
    /// @return the byte length of dictionary entry `code`
    private int i32OffsetLength(long code) {
        return dictValOffsets.getAtIndex(VortexFormat.LE_INT, code + 1)
                - dictValOffsets.getAtIndex(VortexFormat.LE_INT, code);
    }

    @Override
    public VarBinArray limited(long rows) {
        if (rows >= length) {
            return this;
        }
        int codeBytes = dictCodesPType.byteSize();
        if (rows > dictCodesSegs.byteSize() / codeBytes) {
            throw new VortexException("dict codes segment of " + dictCodesSegs.byteSize()
                    + " bytes holds fewer than " + rows + " " + dictCodesPType + " codes");
        }
        return VarBinArray.ofDict(dtype, rows, bytesSegment, dictValOffsets, dictValOffPType,
                dictCodesSegs.asSlice(0, rows * codeBytes), dictCodesPType);
    }

    /// Reads the dictionary code for row `i` at the width of [#dictCodesPType].
    ///
    /// The codes buffer is untrusted and may be shorter than [#length()], so an
    /// overrun is reported as a [VortexException] instead of a raw
    /// `IndexOutOfBoundsException` (ADR 0003).
    ///
    /// @param i zero-based row index
    /// @return the dictionary code, widened to a signed long
    private long dictReadCode(long i) {
        try {
            return readCodeAt(i);
        } catch (IndexOutOfBoundsException e) {
            throw new VortexException("dict code index " + i + " (" + dictCodesPType
                    + ") out of range for a codes segment of "
                    + dictCodesSegs.byteSize() + " bytes", e);
        }
    }

    private long readCodeAt(long i) {
        return switch (dictCodesPType) {
            case U8 -> Byte.toUnsignedLong(dictCodesSegs.get(ValueLayout.JAVA_BYTE, i));
            case U16 -> Short.toUnsignedLong(dictCodesSegs.getAtIndex(VortexFormat.LE_SHORT, i));
            case U32 -> Integer.toUnsignedLong(dictCodesSegs.getAtIndex(VortexFormat.LE_INT, i));
            case I32 -> dictCodesSegs.getAtIndex(VortexFormat.LE_INT, i);
            case I64, U64 -> dictCodesSegs.getAtIndex(VortexFormat.LE_LONG, i);
            default -> throw new VortexException("unsupported codes ptype: " + dictCodesPType);
        };
    }

    /// Reads dictionary-value offset `i` at the true width of [#dictValOffPType].
    ///
    /// Offsets can arrive at any integer width — FSST decompresses its values to a
    /// child with I32 offsets, legacy dicts use I64, and narrow sequence-encoded
    /// offsets keep their U8/U16 ptype on the wire. Reading at the wrong width (e.g.
    /// an 8-byte read against a 4-byte-stride buffer) walks off the segment. The
    /// buffer bounds and ptype are untrusted input, so both are checked here and a
    /// [VortexException] is thrown rather than a bare `IndexOutOfBoundsException`
    /// (ADR 0003).
    ///
    /// @param i zero-based offset index (in `[0, dictSize]`)
    /// @return the offset value widened to a signed long
    private long dictReadOff(long i) {
        int width = dictValOffPType.byteSize();
        long byteOffset = i * width;
        if (i < 0 || byteOffset + width > dictValOffsets.byteSize()) {
            throw new VortexException("dict value offset index " + i + " (" + dictValOffPType
                    + ", " + width + "-byte) out of range for offsets segment of "
                    + dictValOffsets.byteSize() + " bytes");
        }
        return switch (dictValOffPType) {
            case U8 -> Byte.toUnsignedLong(dictValOffsets.get(ValueLayout.JAVA_BYTE, byteOffset));
            case I8 -> dictValOffsets.get(ValueLayout.JAVA_BYTE, byteOffset);
            case U16 -> Short.toUnsignedLong(dictValOffsets.get(VortexFormat.LE_SHORT, byteOffset));
            case I16 -> dictValOffsets.get(VortexFormat.LE_SHORT, byteOffset);
            case U32 -> Integer.toUnsignedLong(dictValOffsets.get(VortexFormat.LE_INT, byteOffset));
            case I32 -> dictValOffsets.get(VortexFormat.LE_INT, byteOffset);
            case I64, U64 -> dictValOffsets.get(VortexFormat.LE_LONG, byteOffset);
            default -> throw new VortexException(
                    "unsupported dict value offset ptype: " + dictValOffPType);
        };
    }
}
