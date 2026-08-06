package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoDictMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DictByteArray;
import io.github.dfa1.vortex.reader.array.DictDoubleArray;
import io.github.dfa1.vortex.reader.array.DictFloatArray;
import io.github.dfa1.vortex.reader.array.DictIntArray;
import io.github.dfa1.vortex.reader.array.DictLongArray;
import io.github.dfa1.vortex.reader.array.DictShortArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinOffsetArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for `vortex.dict`.
///
/// Every value type decodes lazily: primitives to the `DictXxxArray` carriers, strings to
/// [VarBinArray#ofDict]. Nothing is expanded into a per-row buffer — an `n`-row column over a
/// small pool costs the pool plus the codes, not `n * elemSize`. This is the same shape
/// [io.github.dfa1.vortex.reader.layout.DictLayoutDecoder] produces for the layout-level form
/// of the same dictionary (ADR 0012); the two used to disagree, with this path expanding
/// eagerly (#336).
///
/// Broadcast is preserved rather than special-cased: an undersized codes or values buffer (a
/// `ConstantEncoding` fan-out) is fanned out by the `Materialized*` accessors' own
/// `i % elementCount`, which is where the old `expandXxx` scatter loops got it too. A
/// zero-element child would make that wrap divide by zero, so it is still rejected up front.
public final class DictEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_DICT;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment meta = ctx.metadata();

        if (ctx.dtype() instanceof DType.Utf8) {
            if (ctx.node().children().length == 0) {
                if (meta == null || meta.byteSize() == 0) {
                    throw new VortexException(EncodingId.VORTEX_DICT, "missing metadata for legacy utf8 dict");
                }
                return decodeUtf8DictLegacy(ctx, meta);
            }
            if (meta == null || meta.byteSize() == 0) {
                throw new VortexException(EncodingId.VORTEX_DICT, "missing metadata for utf8 dict");
            }
            return decodeUtf8DictProto(ctx, meta);
        }

        if (meta == null || meta.byteSize() == 0) {
            throw new VortexException(EncodingId.VORTEX_DICT, "missing metadata");
        }

        if (meta.byteSize() == 1) {
            return decodeLegacyJava(ctx, meta.get(ValueLayout.JAVA_BYTE, 0));
        }
        return decodeRustProto(ctx, meta);
    }

    private static Array decodeLegacyJava(DecodeContext ctx, byte codeTypeByte) {
        PType codePType = PType.fromOrdinal(Byte.toUnsignedInt(codeTypeByte));
        PType valPType = ((DType.Primitive) ctx.dtype()).ptype();
        long rowCount = ctx.rowCount();
        requireUnsignedCodePType(codePType);

        MemorySegment valuesBuf = ctx.childBuffer(0, 0);

        DType codesDtype = new DType.Primitive(codePType, false);
        MemorySegment codesBuf = ctx.decodeChildSegment(1, codesDtype, rowCount);

        rejectEmptyChildren(rowCount, codesBuf.byteSize(), codePType.byteSize(),
                valuesBuf.byteSize(), valPType.byteSize());

        long poolLength = valuesBuf.byteSize() / valPType.byteSize();
        Array values = typedArray(ctx.dtype(), valPType, poolLength, valuesBuf);
        Array codes = typedArray(codesDtype, codePType, rowCount, codesBuf);
        validateCodesInRange(codes, poolLength);
        return buildLazyDict(ctx.dtype(), valPType, rowCount, values, codes);
    }

    private static Array decodeRustProto(DecodeContext ctx, MemorySegment metaBuf) {
        ProtoDictMetadata meta;
        try {
            meta = ProtoDictMetadata.decode(metaBuf, 0, metaBuf.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_DICT, "invalid proto metadata", e);
        }

        PType codePType = PType.fromOrdinal(meta.codes_ptype().value());
        long valuesLen = meta.values_len();
        long rowCount = ctx.rowCount();
        PType valPType = ((DType.Primitive) ctx.dtype()).ptype();
        requireUnsignedCodePType(codePType);

        // Row validity mirrors the Rust reference: a DictArray row is null when its CODE
        // is null (codes-side validity child) or when the code points at an invalid pool
        // slot (pool-null representation). Both arrive as MaskedArray children and must
        // be propagated per row, not flattened away (#210).
        DType codesDtype = new DType.Primitive(codePType, ctx.dtype().nullable());
        Array codesArr = ctx.decodeChild(0, codesDtype, rowCount);
        BoolArray codesValidity = null;
        Array rawCodes = codesArr;
        if (codesArr instanceof MaskedArray masked) {
            rawCodes = masked.inner();
            codesValidity = masked.validity();
        }
        Array valuesArr = ctx.decodeChild(1, ctx.dtype(), valuesLen);
        BoolArray poolValidity = null;
        Array rawValues = valuesArr;
        if (valuesArr instanceof MaskedArray masked) {
            rawValues = masked.inner();
            poolValidity = masked.validity();
        }

        rejectEmptyChildren(rowCount, physicalBytes(rawCodes), codePType.byteSize(),
                physicalBytes(rawValues), valPType.byteSize());
        validateCodesInRange(rawCodes, rawValues.length());

        Array values = buildLazyDict(ctx.dtype(), valPType, rowCount, rawValues, rawCodes);
        BoolArray rowValidity = poolValidity == null
                ? codesValidity
                : rowValidity(ctx, ctx.materialize(rawCodes), codePType, codesValidity, poolValidity, rowCount);
        return rowValidity == null ? values : new MaskedArray(values, rowValidity);
    }

    /// Builds the matching lazy `DictXxxArray` for a primitive dictionary — the same carriers
    /// [io.github.dfa1.vortex.reader.layout.DictLayoutDecoder] builds for the layout-level
    /// form of the same dictionary. Nothing is expanded: `getXxx(i)` resolves
    /// `values[codes[i]]` on access, so an `n`-row column over a small pool costs the pool
    /// plus the codes, not `n * elemSize`.
    ///
    /// @param dtype    logical element type
    /// @param valPType value ptype (selects the carrier)
    /// @param n        logical row count
    /// @param values   dictionary pool, already mask-unwrapped
    /// @param codes    per-row codes, already mask-unwrapped
    /// @return the lazy dict array
    private static Array buildLazyDict(DType dtype, PType valPType, long n, Array values, Array codes) {
        try {
            return switch (valPType) {
                case I64, U64 -> DictLongArray.of(dtype, n, (LongArray) values, codes);
                case I32, U32 -> DictIntArray.of(dtype, n, (IntArray) values, codes);
                case I16, U16 -> DictShortArray.of(dtype, n, (ShortArray) values, codes);
                case I8, U8 -> DictByteArray.of(dtype, n, (ByteArray) values, codes);
                case F64 -> DictDoubleArray.of(dtype, n, (DoubleArray) values, codes);
                case F32 -> DictFloatArray.of(dtype, n, (FloatArray) values, codes);
                // F16 has no Array subtype yet, matching DictLayoutDecoder.
                default -> throw new VortexException(EncodingId.VORTEX_DICT, "unsupported ptype " + valPType);
            };
        } catch (ClassCastException e) {
            // The values child is untrusted and may decode to any Array family regardless of
            // the declared dtype; a raw ClassCastException would violate ADR 0003.
            throw new VortexException(EncodingId.VORTEX_DICT,
                    "values child is not a " + valPType + " array: " + values.getClass().getSimpleName(), e);
        }
    }

    /// Rejects a codes ptype the format does not allow. Codes are unsigned indices into the
    /// pool, so only `U8`/`U16`/`U32` are legal — the eager expansion enforced this through the
    /// `expandU8`/`expandU16`/`expandU32` switch it dispatched on, and the lazy carriers would
    /// otherwise accept a signed or `U64` codes child that no writer emits.
    ///
    /// @param codePType codes ptype from the metadata
    private static void requireUnsignedCodePType(PType codePType) {
        switch (codePType) {
            case U8, U16, U32 -> {
                // legal
            }
            default -> throw new VortexException(EncodingId.VORTEX_DICT, "unexpected code type: " + codePType);
        }
    }

    /// Rejects a code pointing past the values pool.
    ///
    /// The eager expansion this replaces got the same guarantee from a boundary catch around
    /// its scatter loop. A lazy carrier resolves codes at scan time instead, where an
    /// out-of-range code would surface as a raw `IndexOutOfBoundsException` rather than a
    /// [VortexException] (ADR 0003) — and from a call site far from the malformed file. So the
    /// check moves here, to decode.
    ///
    /// One pass, no allocation: strictly cheaper than the `n * elemSize` allocate-and-scatter
    /// it replaces. The maximum is accumulated branchlessly so the loop body stays uniform
    /// (CLAUDE.md hot-loop rule) and only the single comparison afterwards can fail. Only the
    /// three legal codes widths appear ([#requireUnsignedCodePType(PType)] runs first), so
    /// zero-extending each read covers the whole unsigned range.
    ///
    /// @param codes      per-row codes
    /// @param poolLength number of entries in the values pool
    private static void validateCodesInRange(Array codes, long poolLength) {
        long n = codes.length();
        if (n == 0) {
            return;
        }
        long max = 0;
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    max = Math.max(max, Byte.toUnsignedLong(ba.getByte(i)));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    max = Math.max(max, Short.toUnsignedLong(sa.getShort(i)));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    max = Math.max(max, Integer.toUnsignedLong(ia.getInt(i)));
                }
            }
            default -> throw new VortexException(EncodingId.VORTEX_DICT,
                    "unsupported codes array type: " + codes.getClass().getSimpleName());
        }
        if (Long.compareUnsigned(max, poolLength) >= 0) {
            throw new VortexException(EncodingId.VORTEX_DICT, "code " + Long.toUnsignedString(max)
                    + " out of range for a values pool of " + poolLength + " element(s)");
        }
    }

    /// Rejects a child with no elements at all while the metadata claims rows.
    ///
    /// The `Materialized*` accessors deliberately broadcast an undersized buffer with
    /// `i % elementCount` (the `ConstantEncoding` fan-out), which a zero-element buffer turns
    /// into a divide-by-zero — an `ArithmeticException`, not a [VortexException] (ADR 0003).
    /// The eager expansion rejected the same shape up front; the lazy carriers need it just
    /// as much, since the division moves to scan time rather than disappearing.
    ///
    /// @param rowCount       logical row count
    /// @param codesBytes     physical bytes behind the codes child, or `-1` when unknown
    /// @param codeWidth      code element width
    /// @param valuesBytes    physical bytes behind the values child, or `-1` when unknown
    /// @param valueWidth     value element width
    private static void rejectEmptyChildren(long rowCount, long codesBytes, int codeWidth,
            long valuesBytes, int valueWidth) {
        boolean codesEmpty = codesBytes >= 0 && codesBytes < codeWidth;
        boolean valuesEmpty = valuesBytes >= 0 && valuesBytes < valueWidth;
        if (rowCount > 0 && (codesEmpty || valuesEmpty)) {
            throw new VortexException(EncodingId.VORTEX_DICT,
                    "empty dict child for " + rowCount + " rows (codes=" + codesBytes
                            + " bytes, values=" + valuesBytes + " bytes)");
        }
    }

    /// Physical byte size behind `array`, or `-1` when it has no single backing segment
    /// (a lazy child, whose own decoder already enforced its length).
    ///
    /// @param array the child array
    /// @return backing byte size, or `-1` when not segment-backed
    private static long physicalBytes(Array array) {
        return array.segmentIfPresent().map(MemorySegment::byteSize).orElse(-1L);
    }


    /// Combines codes-side and pool-side validity into per-row validity: row `i` is
    /// valid iff its code is valid and the pool slot the code references is valid.
    /// Returns `null` when neither side carries validity (all rows valid), and the
    /// codes-side mask unchanged when the pool is all-valid (it is already per-row).
    /// Output is bit-packed LSB-first ([MaterializedBoolArray] layout). The broadcast
    /// branch mirrors the `expandXxx` loops (undersized codes buffer = ConstantEncoding
    /// fan-out) and is split out of the fast path.
    ///
    /// @param ctx           decode context (allocation arena)
    /// @param codesBuf      decoded raw codes buffer
    /// @param codePType     unsigned code ptype (U8/U16/U32)
    /// @param codesValidity per-row validity from the codes child, or `null`
    /// @param poolValidity  validity of the values pool, or `null`
    /// @param rowCount      logical row count
    /// @return a bit-packed row validity array of `rowCount` bits, or `null` when all valid
    private static BoolArray rowValidity(DecodeContext ctx, MemorySegment codesBuf, PType codePType,
            BoolArray codesValidity, BoolArray poolValidity, long rowCount) {
        if (poolValidity == null) {
            return codesValidity;
        }
        MemorySegment bits = ctx.arena().allocate((rowCount + 7) >>> 3);
        long codesCap = SegmentBroadcast.capacity(codesBuf, codePType.byteSize());
        if (codesCap >= rowCount) {
            for (long i = 0; i < rowCount; i++) {
                boolean valid = (codesValidity == null || codesValidity.getBoolean(i))
                        && poolValid(poolValidity, readCode(codesBuf, i, codePType));
                if (valid) {
                    setBit(bits, i);
                }
            }
        } else {
            for (long i = 0; i < rowCount; i++) {
                boolean valid = (codesValidity == null || codesValidity.getBoolean(i))
                        && poolValid(poolValidity, readCode(codesBuf, i % codesCap, codePType));
                if (valid) {
                    setBit(bits, i);
                }
            }
        }
        return new MaterializedBoolArray(DType.BOOL, rowCount, bits.asReadOnly());
    }

    /// Untrusted-input guard: a malformed file may carry codes outside the pool, and the
    /// validity bitmap lookup must fail as [VortexException], never as a raw JDK
    /// IndexOutOfBoundsException.
    private static boolean poolValid(BoolArray poolValidity, long code) {
        if (code >= poolValidity.length()) {
            throw new VortexException(EncodingId.VORTEX_DICT,
                    "code " + code + " out of range for pool validity of length " + poolValidity.length());
        }
        return poolValidity.getBoolean(code);
    }

    private static long readCode(MemorySegment codes, long i, PType codePType) {
        return switch (codePType) {
            case U8 -> Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i));
            case U16 -> Short.toUnsignedLong(codes.getAtIndex(VortexFormat.LE_SHORT, i));
            case U32 -> Integer.toUnsignedLong(codes.getAtIndex(VortexFormat.LE_INT, i));
            default -> throw new VortexException(EncodingId.VORTEX_DICT, "unexpected code type: " + codePType);
        };
    }

    private static void setBit(MemorySegment bits, long i) {
        long byteIdx = i >>> 3;
        byte cur = bits.get(ValueLayout.JAVA_BYTE, byteIdx);
        bits.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) ((cur & 0xff) | (1 << (i & 7))));
    }

    private static Array decodeUtf8DictLegacy(DecodeContext ctx, MemorySegment meta) {
        PType codePType = PType.fromOrdinal(Byte.toUnsignedInt(meta.get(ValueLayout.JAVA_BYTE, 0)));
        long n = ctx.rowCount();

        MemorySegment dictBytes = ctx.buffer(0);
        MemorySegment dictOffsets = ctx.buffer(1);
        MemorySegment codes = ctx.buffer(2);

        return VarBinArray.ofDict(ctx.dtype(), n,
                dictBytes, dictOffsets, PType.I64,
                codes, codePType);
    }

    private static Array decodeUtf8DictProto(DecodeContext ctx, MemorySegment metaBuf) {
        ProtoDictMetadata meta;
        try {
            meta = ProtoDictMetadata.decode(metaBuf, 0, metaBuf.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_DICT, "invalid utf8 dict proto metadata", e);
        }
        PType codePType = PType.fromOrdinal(meta.codes_ptype().value());
        long dictSize = meta.values_len();
        long n = ctx.rowCount();

        // Same two null representations as the primitive path (#210): codes-side
        // validity and/or an invalid pool slot referenced by null rows.
        DType codesDtype = new DType.Primitive(codePType, ctx.dtype().nullable());
        Array codesArr = ctx.decodeChild(0, codesDtype, n);
        BoolArray codesValidity = null;
        Array rawCodes = codesArr;
        if (codesArr instanceof MaskedArray masked) {
            rawCodes = masked.inner();
            codesValidity = masked.validity();
        }
        MemorySegment codesBuf = ctx.materialize(rawCodes);

        Array valuesDecoded = ctx.decodeChild(1, ctx.dtype(), dictSize);
        BoolArray poolValidity = null;
        if (valuesDecoded instanceof MaskedArray masked) {
            valuesDecoded = masked.inner();
            poolValidity = masked.validity();
        }
        VarBinArray valuesArr = (VarBinArray) valuesDecoded;
        VarBinOffsetArray dictValues = VarBinArray.toOffsetMode(valuesArr, ctx.arena());

        BoolArray rowValidity = rowValidity(ctx, codesBuf, codePType, codesValidity, poolValidity, n);
        // Carry the offsets ptype that `dictValues` actually materialized. `toOffsetMode`
        // only builds fresh I64 offsets on its slow path; on the fast path it returns the
        // decoded values array unchanged, keeping its own ptype (e.g. FSST decompresses to
        // I32 offsets). Hardcoding I64 here made the VarBinDictArray carrier disagree with its
        // buffer width (4-byte stride read as 8) and threw IOOBE — see #215.
        Array dict = VarBinArray.ofDict(ctx.dtype(), n,
                dictValues.bytesSegment(), dictValues.offsetsSegment(), dictValues.offsetsPtype(),
                codesBuf, codePType);
        return rowValidity == null ? dict : new MaskedArray(dict, rowValidity);
    }


    private static Array typedArray(DType dtype, PType ptype, long n, MemorySegment seg) {
        return switch (ptype) {
            case I64, U64 -> new MaterializedLongArray(dtype, n, seg);
            case I32, U32 -> new MaterializedIntArray(dtype, n, seg);
            case F64 -> new MaterializedDoubleArray(dtype, n, seg);
            case F32 -> new MaterializedFloatArray(dtype, n, seg);
            case I16, U16 -> new MaterializedShortArray(dtype, n, seg);
            case I8, U8 -> new MaterializedByteArray(dtype, n, seg);
            default -> throw new VortexException(EncodingId.VORTEX_DICT, "unsupported ptype " + ptype);
        };
    }
}
