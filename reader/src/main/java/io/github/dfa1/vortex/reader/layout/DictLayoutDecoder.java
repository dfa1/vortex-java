package io.github.dfa1.vortex.reader.layout;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_LONG;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_SHORT;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.core.model.PType;
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
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinConstantArray;
import io.github.dfa1.vortex.reader.array.VarBinOffsetArray;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.util.Optional;

/// Built-in decoder for the `vortex.dict` layout — a low-cardinality column stored as dictionary
/// values plus per-row codes. Both supported pool shapes decode lazily: a VarBin-shaped pool
/// (Utf8, Binary, or a VarBin-backed extension dtype) resolves through `VarBinDictArray`, a
/// primitive pool through the matching `DictXxxArray`. Nothing is expanded per row.
final class DictLayoutDecoder implements LayoutDecoder {

    @Override
    public LayoutId layoutId() {
        return LayoutId.DICT;
    }

    @Override
    public Array decode(LayoutDecodeContext ctx, Layout dictLayout, DType dtype) {
        SegmentAllocator arena = ctx.arena();
        MemorySegment rawMeta = dictLayout.metadata();
        // DictLayoutMetadata proto (Rust format): field 1 = codes_ptype (PType varint).
        // Read the varint directly to avoid field-number mismatch with the array-level DictMetadata proto.
        PType codesPType = readDictLayoutCodesPType(rawMeta);

        // child[0] = values layout; child[1] = codes layout
        if (dictLayout.children().size() < 2) {
            // Untrusted input: a malformed dict layout may carry any child count.
            throw new VortexException(EncodingId.VORTEX_DICT,
                    "expected 2 children (values, codes), got " + dictLayout.children().size());
        }
        Layout valuesLayout = dictLayout.children().get(0);
        Layout codesLayout = dictLayout.children().get(1);
        long n = codesLayout.rowCount();

        Array values = ctx.decodeChild(valuesLayout, dtype);
        Array codes = ctx.decodeChild(codesLayout, new DType.Primitive(codesPType, false));

        // VarBin (string) dict: ofDict returns the lazy VarBinDictArray record (no eager
        // expansion into per-row offsets/bytes). Unwrap a masked (nullable) codes/values child so
        // the dict carrier sees the raw payload; the row-level validity is re-applied by wrapping
        // the result below. This mirrors the primitive path (buildLazyDictPrimitive) and is the
        // shape a nullable global-dict Utf8 column produces (masked codes + non-nullable pool).
        BoolArray poolValidity = values instanceof MaskedArray mv ? mv.validity() : null;
        Array valuesData = values instanceof MaskedArray mv ? mv.inner() : values;
        BoolArray codesValidity = codes instanceof MaskedArray mc ? mc.validity() : null;
        Array codesData = codes instanceof MaskedArray mc ? mc.inner() : codes;
        if (valuesData instanceof VarBinArray vb) {
            // Zip-bomb guard: read the codes as a segment so we can validate the buffer
            // before building the dict carrier. For direct-mapped encodings (e.g.
            // vortex.primitive), the codes buffer is mmap-bounded and can be much smaller
            // than the claimed rowCount. Full-decode encodings (e.g. bitpacked) already
            // wrote n * elemBytes to the arena during decodeChild above, so their buffer
            // matches n.
            MemorySegment codesSeg = codesData.materialize(arena);
            long bufferCodes = codesSeg.byteSize() / codesPType.byteSize();
            if (bufferCodes < n) {
                throw new VortexException(EncodingId.VORTEX_DICT,
                        "dict codes: layout row_count=" + n + " exceeds buffer capacity=" + bufferCodes);
            }
            Array dict = buildLazyDictVarBin(dtype, n, vb, codesSeg, codesPType, arena);
            if (poolValidity == null && codesValidity == null) {
                return dict;
            }
            if (poolValidity == null) {
                return new MaskedArray(dict, codesValidity);
            }
            return new MaskedArray(dict, gatherRowValidity(codesData, codesValidity, poolValidity, n, arena));
        }
        if (dtype instanceof DType.Primitive pDtype) {
            // Zip-bomb guard (lazy path): the codes Array has already been decoded above;
            // its length() reflects the claimed rowCount but its backing buffer may be
            // mmap-bounded. Validate by inspecting the underlying segment without forcing
            // materialization of non-segment-backed codes (lazy variants).
            validateDictCodesCapacity(codes, codesPType, n);
            return buildLazyDictPrimitive(pDtype, n, values, codes, arena);
        }
        // Neither VarBin- nor primitive-shaped: no lazy dict carrier exists for this pool — e.g.
        // a dict-encoded FixedSizeList-backed extension such as vortex.uuid. Untrusted input can
        // also reach here with an arbitrary child encoding, so reject rather than blindly casting
        // (a raw ClassCastException would break the security contract).
        throw new VortexException(EncodingId.VORTEX_DICT, "unsupported dict values shape: "
                + valuesData.getClass().getSimpleName() + " for dtype "
                + dtype.getClass().getSimpleName());
    }

    /// Builds the lazy carrier for a VarBin-shaped dictionary — Utf8, Binary, or any
    /// VarBin-backed extension dtype (#341). No row is ever expanded: rows resolve through
    /// their code on access.
    ///
    /// Only [VarBinOffsetArray] is directly indexable by code, so any other pool shape has to
    /// be walked entry by entry to become one. That walk is bounded by the codes, not by the
    /// pool's own `length()`: shapes that carry no per-entry storage report whatever length
    /// the file declares (a `vortex.constant` pool a few hundred bytes long can claim 2^40
    /// entries), and normalizing that claim would allocate until the reader dies. The codes
    /// buffer is the one bound the file must physically back, and it has already been checked
    /// to hold `n` entries by the caller.
    ///
    /// @param dtype      logical dtype of the column
    /// @param n          logical row count
    /// @param values     the decoded values pool, mask already unwrapped
    /// @param codesSeg   per-row codes into `values`
    /// @param codesPType physical type of the codes
    /// @param arena      allocator for a normalized pool, when one is needed
    /// @return a lazy `VarBinDictArray`, or a `VarBinConstantArray` when the pool holds a
    ///         single broadcast value
    private static Array buildLazyDictVarBin(DType dtype, long n, VarBinArray values,
            MemorySegment codesSeg, PType codesPType, SegmentAllocator arena) {
        if (values instanceof VarBinOffsetArray flat) {
            // The common Utf8/Binary dict: already bytes-plus-offsets, so nothing is walked or
            // copied and an out-of-pool code is caught by the carrier on access.
            return VarBinArray.ofDict(dtype, n, flat.bytesSegment(), flat.offsetsSegment(),
                    flat.offsetsPtype(), codesSeg, codesPType);
        }
        long poolExtent = checkedPoolExtent(codesSeg, codesPType, n, values.length());
        if (values instanceof VarBinConstantArray constant) {
            // A `vortex.constant` pool holds one distinct value broadcast across its entries,
            // so every in-range code resolves to the same bytes and the dict collapses to that
            // constant — O(1), no pool walk, no allocation whatever length the pool claims.
            return new VarBinConstantArray(dtype, n, constant.bytes());
        }
        // Remaining shapes (StringView, chunked, run-end, sparse, sliced pools) are walked, but
        // only up to the highest entry a code actually reaches.
        VarBinOffsetArray pool = VarBinArray.toOffsetMode(values.limited(poolExtent), arena);
        return VarBinArray.ofDict(dtype, n, pool.bytesSegment(), pool.offsetsSegment(),
                pool.offsetsPtype(), codesSeg, codesPType);
    }

    /// Values-side bounds guard: validates every per-row code against the values pool and
    /// returns how many pool entries the codes can actually reach.
    ///
    /// The codes-side guard in [#decode(LayoutDecodeContext, Layout, DType)] has no values-side
    /// analogue, because a pool's `length()` is a claim rather than a measurement for the shapes
    /// with no per-entry storage. Reading the codes bounds the pool by something the file must
    /// physically contain. Runs only when the pool is not already flat, so the common dict path
    /// pays nothing; the ptype dispatch is hoisted out of the loop (CLAUDE.md hot-loop rule) and
    /// the range test is a single check on the reduced min/max rather than one per row.
    ///
    /// @param codesSeg   per-row codes, already checked to hold at least `n` entries
    /// @param codesPType physical type of the codes
    /// @param n          logical row count
    /// @param poolLength number of entries the values pool claims to hold
    /// @return `maxCode + 1`, in `[0, poolLength]`; `0` when there are no rows
    /// @throws VortexException if any code is negative or outside the pool
    private static long checkedPoolExtent(MemorySegment codesSeg, PType codesPType, long n,
            long poolLength) {
        long max = -1;
        long min = 0;
        switch (codesPType) {
            case U8 -> {
                for (long i = 0; i < n; i++) {
                    long code = Byte.toUnsignedLong(codesSeg.get(ValueLayout.JAVA_BYTE, i));
                    max = Math.max(max, code);
                }
            }
            case U16 -> {
                for (long i = 0; i < n; i++) {
                    long code = Short.toUnsignedLong(codesSeg.getAtIndex(LE_SHORT, i));
                    max = Math.max(max, code);
                }
            }
            case U32 -> {
                for (long i = 0; i < n; i++) {
                    long code = Integer.toUnsignedLong(codesSeg.getAtIndex(LE_INT, i));
                    max = Math.max(max, code);
                }
            }
            case I32 -> {
                for (long i = 0; i < n; i++) {
                    long code = codesSeg.getAtIndex(LE_INT, i);
                    max = Math.max(max, code);
                    min = Math.min(min, code);
                }
            }
            // A u64 code past Long.MAX_VALUE reads back negative, which the min test rejects —
            // it is out of range for any pool a file can actually hold anyway.
            case I64, U64 -> {
                for (long i = 0; i < n; i++) {
                    long code = codesSeg.getAtIndex(LE_LONG, i);
                    max = Math.max(max, code);
                    min = Math.min(min, code);
                }
            }
            default -> throw new VortexException(EncodingId.VORTEX_DICT,
                    "layout: unsupported codes ptype: " + codesPType);
        }
        if (min < 0 || max >= poolLength) {
            throw new VortexException(EncodingId.VORTEX_DICT, "layout: dict code "
                    + (min < 0 ? min : max) + " out of range for a values pool of "
                    + poolLength + " entries");
        }
        return max + 1;
    }

    /// Lazy-path zip-bomb guard. Inspects `codes`'s primary segment when available
    /// (segment-backed encodings can be mmap-bounded and undersized); skips validation
    /// for non-segment variants whose own decoder has already enforced length.
    ///
    /// @param codes      the decoded codes array
    /// @param codesPType code ptype reported by the dict layout metadata
    /// @param n          claimed dict row count
    private static void validateDictCodesCapacity(Array codes, PType codesPType, long n) {
        Optional<MemorySegment> maybeSeg = codes.segmentIfPresent();
        if (maybeSeg.isEmpty()) {
            return;
        }
        long bufferCodes = maybeSeg.get().byteSize() / codesPType.byteSize();
        if (bufferCodes < n) {
            throw new VortexException(EncodingId.VORTEX_DICT,
                    "dict codes: layout row_count=" + n + " exceeds buffer capacity=" + bufferCodes);
        }
    }

    /// Builds the matching `DictXxxArray` for a primitive dictionary.
    ///
    /// Row validity mirrors the Rust reference (#210): a dict row is null when its CODE
    /// is null (codes child arrives as a [MaskedArray]) or when the code points at an
    /// invalid pool slot (nullable values pool arrives as a [MaskedArray]). Both masks
    /// must be propagated to per-row validity — dropping either silently un-nulls rows.
    ///
    /// @param dtype  primitive logical type of dict values
    /// @param n      total logical row count
    /// @param values dictionary values
    /// @param codes  per-row codes into `values`
    /// @param arena  allocator for the gathered row-validity bitmap
    /// @return a lazy `DictXxxArray` matching the value ptype, wrapped in a
    ///         [MaskedArray] when either side carries validity
    private static Array buildLazyDictPrimitive(DType.Primitive dtype, long n, Array values, Array codes,
            SegmentAllocator arena) {
        BoolArray poolValidity = values instanceof MaskedArray mv ? mv.validity() : null;
        Array valuesData = values instanceof MaskedArray mv ? mv.inner() : values;
        BoolArray codesValidity = codes instanceof MaskedArray mc ? mc.validity() : null;
        Array codesData = codes instanceof MaskedArray mc ? mc.inner() : codes;
        PType ptype = dtype.ptype();
        Array dict = switch (ptype) {
            case I64, U64 -> DictLongArray.of(dtype, n, (LongArray) valuesData, codesData);
            case I32, U32 -> DictIntArray.of(dtype, n, (IntArray) valuesData, codesData);
            case I16, U16 -> DictShortArray.of(dtype, n, (ShortArray) valuesData, codesData);
            case I8, U8 -> DictByteArray.of(dtype, n, (ByteArray) valuesData, codesData);
            case F64 -> DictDoubleArray.of(dtype, n, (DoubleArray) valuesData, codesData);
            case F32 -> DictFloatArray.of(dtype, n, (FloatArray) valuesData, codesData);
            // F16 has no Array subtype yet
            default -> throw new VortexException(EncodingId.VORTEX_DICT,
                    "layout: unsupported ptype for lazy dict: " + ptype);
        };
        if (poolValidity == null && codesValidity == null) {
            return dict;
        }
        if (poolValidity == null) {
            return new MaskedArray(dict, codesValidity);
        }
        return new MaskedArray(dict, gatherRowValidity(codesData, codesValidity, poolValidity, n, arena));
    }

    /// Combines codes-side and pool-side validity per row: row `i` is valid iff its code
    /// is valid and the pool slot the code references is valid. Bit-packed LSB-first
    /// ([MaterializedBoolArray] layout).
    ///
    /// @param codes         raw per-row codes
    /// @param codesValidity per-row validity from the codes side, or `null`
    /// @param poolValidity  validity of the values pool
    /// @param n             logical row count
    /// @param arena         allocator for the bitmap
    /// @return a bit-packed row validity array of `n` bits
    private static BoolArray gatherRowValidity(Array codes, BoolArray codesValidity, BoolArray poolValidity,
            long n, SegmentAllocator arena) {
        MemorySegment bits = arena.allocate((n + 7) >>> 3);
        for (long i = 0; i < n; i++) {
            long code = switch (codes) {
                case ByteArray ba -> Byte.toUnsignedLong(ba.getByte(i));
                case ShortArray sa -> Short.toUnsignedLong(sa.getShort(i));
                case IntArray ia -> Integer.toUnsignedLong(ia.getInt(i));
                case LongArray la -> la.getLong(i);
                default -> throw new VortexException(EncodingId.VORTEX_DICT,
                        "layout: invalid codes type: " + codes.getClass().getSimpleName());
            };
            // Untrusted-input guard: codes outside the pool must fail as VortexException,
            // never as a raw JDK IndexOutOfBoundsException.
            if (code >= poolValidity.length()) {
                throw new VortexException(EncodingId.VORTEX_DICT,
                        "layout: code " + code + " out of range for pool validity of length "
                                + poolValidity.length());
            }
            boolean valid = (codesValidity == null || codesValidity.getBoolean(i))
                    && poolValidity.getBoolean(code);
            if (valid) {
                long byteIdx = i >>> 3;
                byte cur = bits.get(ValueLayout.JAVA_BYTE, byteIdx);
                bits.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) ((cur & 0xff) | (1 << (i & 7))));
            }
        }
        return new MaterializedBoolArray(DType.BOOL, n, bits.asReadOnly());
    }

    private static PType readDictLayoutCodesPType(MemorySegment rawMeta) {
        // DictLayoutMetadata (Rust): field 1 = codes_ptype, wire type 0 (varint).
        // Tag byte = (field_number << 3) | wire_type = (1 << 3) | 0 = 0x08.
        // Proto3 omits field 1 when it holds the default value (0 = U8), so empty metadata means U8.
        if (rawMeta == null || rawMeta.byteSize() == 0) {
            return PType.U8;
        }
        byte tag = rawMeta.get(ValueLayout.JAVA_BYTE, 0);
        if (tag == 0x08 && rawMeta.byteSize() > 1) {
            int ordinal = rawMeta.get(ValueLayout.JAVA_BYTE, 1) & 0xFF;
            PType[] values = PType.values();
            if (ordinal < values.length) {
                return values[ordinal];
            }
        }
        return PType.U8;
    }
}
