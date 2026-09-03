package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoSparseMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazySparseBoolArray;
import io.github.dfa1.vortex.reader.array.LazySparseByteArray;
import io.github.dfa1.vortex.reader.array.LazySparseDoubleArray;
import io.github.dfa1.vortex.reader.array.LazySparseFloatArray;
import io.github.dfa1.vortex.reader.array.LazySparseIntArray;
import io.github.dfa1.vortex.reader.array.LazySparseLongArray;
import io.github.dfa1.vortex.reader.array.LazySparseShortArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinConstantArray;
import io.github.dfa1.vortex.reader.array.VarBinSparseArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/// Read-only decoder for `vortex.sparse`.
public final class SparseEncodingDecoder implements EncodingDecoder {

    /// `role` argument naming the patch indices child, for error messages.
    private static final String ROLE_INDICES = "indices";

    /// `role` argument naming the patch values child, for error messages.
    private static final String ROLE_VALUES = "values";

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_SPARSE;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment rawMeta = ctx.metadata();
        if (rawMeta == null || rawMeta.byteSize() == 0) {
            throw new VortexException(EncodingId.VORTEX_SPARSE, "missing metadata");
        }
        ProtoSparseMetadata sparseMeta;
        try {
            sparseMeta = ProtoSparseMetadata.decode(rawMeta, 0, rawMeta.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_SPARSE, "invalid metadata", e);
        }

        int childCount = ctx.node().children().length;
        if (childCount != 2) {
            throw new VortexException(EncodingId.VORTEX_SPARSE,
                    "expected 2 children (patch_indices, patch_values), got " + childCount + " (#250)");
        }

        ProtoPatchesMetadata patches = sparseMeta.patches();
        if (patches == null) {
            // proto3 elides an unset message field entirely; a sparse array with no patches
            // metadata at all is not a legal encoding (even zero patches must say so
            // explicitly), and dereferencing null here would leak a raw NullPointerException.
            throw new VortexException(EncodingId.VORTEX_SPARSE, "missing patches metadata");
        }
        long numPatches = patches.len();
        long offset = patches.offset();
        PType indicesPtype = PType.fromOrdinal(patches.indices_ptype().value());

        long n = ctx.rowCount();
        // Patches sit at distinct positions inside the array, so there can never be more of
        // them than rows — the same invariant the Rust reference asserts in `Patches::new`
        // (`indices.len() <= array_len`). The count comes from untrusted metadata and drives
        // both child decodes and the row-validity bitmap sizing, so an absurd or negative
        // value must fail here as a VortexException rather than as an OutOfMemoryError from
        // the `allValid` allocation further down (ADR 0003).
        if (numPatches < 0 || numPatches > n) {
            throw new VortexException(EncodingId.VORTEX_SPARSE,
                    "patch count " + numPatches + " out of range for " + n + " row(s)");
        }
        // Every lazy sparse carrier maps logical row `i` to absolute `i + offset`, and the
        // sequential walkers iterate `[offset, offset + n)`. An untrusted offset near
        // Long.MAX_VALUE wraps that end bound negative, which makes the walk visit no rows at
        // all while the per-row binary search still resolves each one — the two accessors then
        // disagree on the same array. A negative offset is likewise not a position.
        if (offset < 0 || offset > Long.MAX_VALUE - n) {
            throw new VortexException(EncodingId.VORTEX_SPARSE,
                    "patch offset " + offset + " out of range for " + n + " row(s)");
        }

        // Row validity mirrors the Rust reference `ValidityVTable<Sparse>`: it is a sparse
        // bool array whose fill is `fill_value.is_valid()` and whose per-patch value is the
        // patch value's validity bit. So a position is valid iff (it is a patch AND that
        // patch is valid) OR (it is unpatched AND the fill is non-null). Dropping either
        // facet lost nulls: a `fill_value: null` array (world-energy `biofuel_cons_change_pct`
        // f64?) decoded unpatched rows as 0.0, and a null patch (nuclear_share_energy)
        // decoded to raw 0 — #226. The Rust vtable is generic over the values encoding, so
        // utf8/binary sparse reuses it verbatim; not doing so lost the same nulls for string
        // columns — #232.
        MemorySegment fillBuf = ctx.buffer(0);
        ProtoScalarValue fillScalar = decodeFill(fillBuf);
        boolean fillValid = !isNullScalar(fillScalar);

        if (ctx.dtype() instanceof DType.Utf8 || ctx.dtype() instanceof DType.Binary) {
            return decodeVarBin(ctx, n, numPatches, offset, indicesPtype, fillValid, fillScalar);
        }

        if (ctx.dtype() instanceof DType.Bool) {
            DType indicesDtype = new DType.Primitive(indicesPtype, false);
            Array patchIndices = ctx.decodeChild(0, indicesDtype, numPatches);
            Array patchValues = ctx.decodeChild(1, ctx.dtype(), numPatches);
            Array idxData = patchIndices instanceof MaskedArray m ? m.inner() : patchIndices;
            BoolArray patchValidity = null;
            Array valData = patchValues;
            if (patchValues instanceof MaskedArray m) {
                valData = m.inner();
                patchValidity = m.validity();
            }
            checkPatchChild(idxData, numPatches, ROLE_INDICES);
            checkPatchChild(valData, numPatches, ROLE_VALUES);
            boolean fillValue = Boolean.TRUE.equals(fillScalar.bool_value());
            BoolArray boolValues = checkedCast(valData, BoolArray.class, ROLE_VALUES);
            Array result = new LazySparseBoolArray(ctx.dtype(), n, fillValue, boolValues, idxData, offset);
            return withSparseValidity(ctx, result, fillValid, patchValidity, idxData, numPatches, n, offset);
        }

        if (!(ctx.dtype() instanceof DType.Primitive)) {
            throw new VortexException(EncodingId.VORTEX_SPARSE, "expected primitive dtype, got " + ctx.dtype());
        }
        PType valuePtype = ((DType.Primitive) ctx.dtype()).ptype();
        long fillBits = scalarToLong(fillScalar);

        // Lazy path: keep fill bits + decoded patches; no n-sized buffer allocated.
        // When numPatches == 0 we still decode zero-length children so the record's
        // patchValues.length() and findPatch can rely on real (empty) Array instances.
        DType indicesDtype = new DType.Primitive(indicesPtype, false);
        Array patchIndices = ctx.decodeChild(0, indicesDtype, numPatches);
        Array patchValues = ctx.decodeChild(1, ctx.dtype(), numPatches);
        Array idxData = patchIndices instanceof MaskedArray m ? m.inner() : patchIndices;
        BoolArray patchValidity = null;
        Array valData = patchValues;
        if (patchValues instanceof MaskedArray m) {
            valData = m.inner();
            patchValidity = m.validity();
        }
        checkPatchChild(idxData, numPatches, ROLE_INDICES);
        checkPatchChild(valData, numPatches, ROLE_VALUES);

        Array result = switch (valuePtype) {
            case I64, U64 -> new LazySparseLongArray(ctx.dtype(), n, fillBits,
                    checkedCast(valData, LongArray.class, ROLE_VALUES), idxData, offset);
            case I32, U32 -> new LazySparseIntArray(ctx.dtype(), n, (int) fillBits,
                    checkedCast(valData, IntArray.class, ROLE_VALUES), idxData, offset);
            case F64 -> new LazySparseDoubleArray(ctx.dtype(), n, Double.longBitsToDouble(fillBits),
                    checkedCast(valData, DoubleArray.class, ROLE_VALUES), idxData, offset);
            case F32 -> new LazySparseFloatArray(ctx.dtype(), n, Float.intBitsToFloat((int) fillBits),
                    checkedCast(valData, FloatArray.class, ROLE_VALUES), idxData, offset);
            case I16 -> new LazySparseShortArray(ctx.dtype(), n, (short) fillBits, (short) fillBits,
                    checkedCast(valData, ShortArray.class, ROLE_VALUES), idxData, offset);
            case U16 -> new LazySparseShortArray(ctx.dtype(), n, (short) fillBits, (int) (fillBits & 0xFFFFL),
                    checkedCast(valData, ShortArray.class, ROLE_VALUES), idxData, offset);
            case I8 -> new LazySparseByteArray(ctx.dtype(), n, (byte) fillBits, (byte) fillBits,
                    checkedCast(valData, ByteArray.class, ROLE_VALUES), idxData, offset);
            case U8 -> new LazySparseByteArray(ctx.dtype(), n, (byte) fillBits, (int) (fillBits & 0xFFL),
                    checkedCast(valData, ByteArray.class, ROLE_VALUES), idxData, offset);
            default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "unsupported ptype " + valuePtype);
        };
        return withSparseValidity(ctx, result, fillValid, patchValidity, idxData, numPatches, n, offset);
    }

    /// Wraps `result` in a [MaskedArray] whose per-row validity is a sparse bool array:
    /// fill = `fillValid` (the fill scalar is non-null), each patch bit = that patch's
    /// validity. Returns `result` unchanged only when the fill is non-null and no patch
    /// carried a null (the all-valid no-regression path).
    ///
    /// @param ctx           decode context (allocation arena)
    /// @param result        the decoded sparse value array
    /// @param fillValid     `true` when the fill scalar is non-null
    /// @param patchValidity per-patch validity bits, or `null` when all patches are valid
    /// @param idxData       sorted absolute patch positions (raw, mask-unwrapped)
    /// @param numPatches    number of patches
    /// @param n             logical row count
    /// @param offset        starting absolute position
    /// @return `result`, or a [MaskedArray] carrying the lazy per-row validity
    private static Array withSparseValidity(DecodeContext ctx, Array result, boolean fillValid,
            BoolArray patchValidity, Array idxData, long numPatches, long n, long offset) {
        if (fillValid && patchValidity == null) {
            return result;
        }
        BoolArray patchBits = patchValidity != null ? patchValidity : allValid(ctx, numPatches);
        BoolArray rowValidity = new LazySparseBoolArray(DType.BOOL, n, fillValid, patchBits, idxData, offset);
        return new MaskedArray(result, rowValidity);
    }

    /// Builds an all-valid bit-packed [BoolArray] of `len` bits — the patch-validity stand-in
    /// when the patch values are non-nullable but the fill is null (every patch punches in a
    /// valid position over an all-invalid base).
    private static BoolArray allValid(DecodeContext ctx, long len) {
        MemorySegment bits = ctx.arena().allocate(Math.max(1, (len + 7) >>> 3));
        bits.fill((byte) 0xFF);
        return new MaterializedBoolArray(DType.BOOL, len, bits.asReadOnly());
    }

    /// Rejects a patch child whose physical buffer holds no elements at all while the
    /// metadata claims patches.
    ///
    /// The `Materialized*` accessors deliberately broadcast an undersized buffer with
    /// `i % elementCount` (the `ConstantEncoding` fan-out), so a zero-element buffer would
    /// divide by zero — an `ArithmeticException`, not a [VortexException] (ADR 0003). The
    /// probe is O(1) and non-allocating: lazy children report no segment and are skipped,
    /// exactly like the equivalent guard in [DictEncodingDecoder].
    ///
    /// @param child      decoded patch child (indices or values)
    /// @param numPatches number of patches the metadata declares
    /// @param role       `"indices"` or `"values"`, for the error message
    private static void checkPatchChild(Array child, long numPatches, String role) {
        if (numPatches > 0 && child.segmentIfPresent().filter(s -> s.byteSize() == 0).isPresent()) {
            throw new VortexException(EncodingId.VORTEX_SPARSE,
                    "empty patch " + role + " child for " + numPatches + " patch(es)");
        }
    }

    /// Casts a decoded patch child to the type its declared ptype/dtype demands, rejecting a
    /// mismatch as a [VortexException].
    ///
    /// `decodeChild` dispatches on the *child node's own* encoding id, not on the dtype this
    /// decoder asked for — a crafted file can put e.g. a `vortex.bool` node where an `i64`
    /// sparse array expects its values child, which decodes without error and would otherwise
    /// blow up as a raw `ClassCastException` at the unchecked cast site (ADR 0003).
    ///
    /// @param child decoded patch child (indices or values)
    /// @param type  the concrete [Array] subtype required at this call site
    /// @param role  `"indices"` or `"values"`, for the error message
    /// @param <T>   the required array type
    /// @return `child`, cast to `type`
    private static <T extends Array> T checkedCast(Array child, Class<T> type, String role) {
        if (!type.isInstance(child)) {
            throw new VortexException(EncodingId.VORTEX_SPARSE,
                    "patch " + role + " child decoded to unexpected type: " + child.getClass().getSimpleName());
        }
        return type.cast(child);
    }

    private static ProtoScalarValue decodeFill(MemorySegment fillBuf) {
        try {
            return ProtoScalarValue.decode(fillBuf, 0, fillBuf.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_SPARSE, "invalid fill value", e);
        }
    }

    /// Detects a null fill scalar: either an explicit `null_value`, or a scalar with no
    /// value-bearing field set (Rust encodes a null fill as `ScalarValue::Null`, and a
    /// non-null fill always sets exactly one typed field — even integer/float `0`, or a
    /// utf8/binary fill via `string_value`/`bytes_value`).
    private static boolean isNullScalar(ProtoScalarValue s) {
        // keep in sync with ProtoScalarValue components: a new value-bearing field must be
        // added below, else a non-null fill of that kind is misclassified as null.
        return s.null_value() != null
                || (s.bool_value() == null && s.int64_value() == null && s.uint64_value() == null
                        && s.f32_value() == null && s.f64_value() == null && s.string_value() == null
                        && s.bytes_value() == null && s.f16_value() == null && s.list_value() == null
                        && s.variant_value() == null);
    }

    private static Array decodeVarBin(
            DecodeContext ctx, long n, long numPatches, long offset, PType indicesPtype,
            boolean fillValid, ProtoScalarValue fillScalar
    ) {
        // Patch positions are decoded as an Array (not just a segment) so the shared
        // row-validity helper can index them lazily, exactly like the primitive path.
        DType indicesDtype = new DType.Primitive(indicesPtype, false);
        Array patchIndices = ctx.decodeChild(0, indicesDtype, numPatches);
        Array idxData = patchIndices instanceof MaskedArray m ? m.inner() : patchIndices;
        checkPatchChild(idxData, numPatches, ROLE_INDICES);
        byte[] fill = fillBytes(fillScalar, fillValid);

        if (numPatches == 0) {
            // No patch lands in this range, so every row is the fill — the common case for a
            // genuinely sparse column, resolved in O(1) with no search (#340).
            Array result = new VarBinConstantArray(ctx.dtype(), n, fill);
            return withSparseValidity(ctx, result, fillValid, null, idxData, 0, n, offset);
        }

        // A nullable patch child arrives wrapped in `vortex.masked`; unwrap it to reach the
        // raw VarBin values and carry the per-patch validity bits into the row validity (#232).
        Array patchValues = ctx.decodeChild(1, ctx.dtype(), numPatches);
        BoolArray patchValidity = null;
        Array valData = patchValues;
        if (patchValues instanceof MaskedArray m) {
            valData = m.inner();
            patchValidity = m.validity();
        }
        VarBinArray values = checkedCast(valData, VarBinArray.class, ROLE_VALUES);
        Array result = new VarBinSparseArray(ctx.dtype(), n, fill, values, idxData, offset);
        return withSparseValidity(ctx, result, fillValid, patchValidity, idxData, numPatches, n, offset);
    }

    /// Extracts the raw bytes an unpatched utf8/binary row resolves to. A utf8 fill arrives as
    /// `string_value`, a binary fill as `bytes_value`.
    ///
    /// A null fill has neither, and its bytes are never read — [#withSparseValidity] marks
    /// every unpatched row invalid — so the empty array stands in. A fill that is non-null but
    /// carries some other arm of the scalar oneof (an integer fill on a utf8 column, say) is a
    /// malformed file rather than an empty string: silently rendering every unpatched row as a
    /// valid `""` would be the same class of bug this decode path just stopped having.
    ///
    /// @param fill      the decoded fill scalar
    /// @param fillValid `true` when the fill scalar is non-null
    /// @return the fill's raw bytes, empty for a null fill
    /// @throws VortexException if a non-null fill carries no string or bytes value
    private static byte[] fillBytes(ProtoScalarValue fill, boolean fillValid) {
        if (fill.string_value() != null) {
            return fill.string_value().getBytes(StandardCharsets.UTF_8);
        }
        if (fill.bytes_value() != null) {
            return fill.bytes_value();
        }
        if (fillValid) {
            throw new VortexException(EncodingId.VORTEX_SPARSE,
                    "utf8/binary fill scalar carries no string or bytes value");
        }
        return new byte[0];
    }

    private static long scalarToLong(ProtoScalarValue scalar) {
        if (scalar.int64_value() != null) {
            return scalar.int64_value();
        }
        if (scalar.uint64_value() != null) {
            return scalar.uint64_value();
        }
        if (scalar.f32_value() != null) {
            return Float.floatToRawIntBits(scalar.f32_value());
        }
        if (scalar.f64_value() != null) {
            return Double.doubleToRawLongBits(scalar.f64_value());
        }
        return 0L;
    }

}
