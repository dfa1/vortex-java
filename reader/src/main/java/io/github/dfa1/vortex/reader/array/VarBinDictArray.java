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
/// @param dtype           logical type (Utf8 or Binary)
/// @param length          number of logical elements (rows)
/// @param bytesSegment    concatenated raw bytes for all dictionary values
/// @param dictValOffsets  offsets into `bytesSegment` for each dictionary entry (length = dictSize + 1)
/// @param dictValOffPType physical type of the dictionary value offsets
/// @param dictCodesSegs   per-row dictionary code indices (length = `length`)
/// @param dictCodesPType  physical type of the dictionary codes
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record VarBinDictArray(DType dtype, long length, MemorySegment bytesSegment,
                              MemorySegment dictValOffsets, PType dictValOffPType,
                              MemorySegment dictCodesSegs, PType dictCodesPType)
        implements VarBinArray {

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
        return new String(getBytes(i), StandardCharsets.UTF_8);
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
