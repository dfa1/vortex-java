package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoNullValue;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;

/// Write-only encoder for `vortex.constant`.
public final class ConstantEncodingEncoder implements EncodingEncoder {

    /// Creates the encoder.
    public ConstantEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_CONSTANT;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive || dtype instanceof DType.Bool || dtype instanceof DType.Utf8;
    }

    @Override
    public StatsOptions statsOptions() {
        return StatsOptions.defaults().withCountDistinct(true);
    }

    @Override
    public Estimate expectedRatio(DType dtype, ArrayAndStats data, EncodeContext ctx) {
        // Rust detects constants in the compressor itself, never on a sample: a constant sample
        // does not imply a constant array (`compressor/cascade.rs`).
        if (ctx.sample()) {
            return Estimate.SKIP;
        }
        if (dtype instanceof DType.Utf8) {
            // Strings carry no stats; Rust's constant check is one pass over the array
            return isConstantText(data.data()) ? Estimate.ALWAYS_USE : Estimate.SKIP;
        }
        ArrayStats stats = data.stats();
        if (stats.valueCount() == 0) {
            return Estimate.ALWAYS_USE;
        }
        if (!stats.hasDistinctCount()) {
            return Estimate.COMPLETE;
        }
        if (stats.distinctCount() == 1) {
            return Estimate.ALWAYS_USE;
        }
        return Estimate.SKIP;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (dtype instanceof DType.Bool) {
            return encodeBool((boolean[]) data);
        }
        if (dtype instanceof DType.Utf8) {
            return encodeUtf8(data);
        }
        if (!(dtype instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_CONSTANT, "encode only supports Primitive or Bool dtype, got " + dtype);
        }
        return encodeIfConstant(p.ptype(), data)
                .orElseThrow(() -> new VortexException(EncodingId.VORTEX_CONSTANT, "not a constant array"));
    }

    /// Encodes a decimal column as a `vortex.constant` when every value is equal. The scalar is the
    /// unscaled value little-endian at the column's storage width, as Rust encodes a decimal scalar.
    ///
    /// @param dtype  the decimal dtype
    /// @param values the decimals, none `null`
    /// @return the constant array, or empty when `values` is empty or the values differ
    static Optional<EncodeResult> encodeDecimalIfConstant(DType.Decimal dtype, BigDecimal[] values) {
        if (!isConstantDecimal(values)) {
            return Optional.empty();
        }
        ProtoScalarValue scalar = decimalScalar(dtype, values[0]);
        return Optional.of(EncodeResult.simple(EncodingId.VORTEX_CONSTANT,
                EncodedBuffer.bytes(MemorySegment.ofArray(scalar.encode())), scalar, scalar));
    }

    /// The value every row of a column batch holds, as Rust's `ConstantStrategy` asks of each chunk
    /// (`is_constant`): a null scalar when every row is null, else the value when every row is valid
    /// and equal (floats by raw bits). Empty when the rows differ, the batch is empty, or the dtype
    /// has no scalar this writer builds: nested, null, union and variant columns.
    ///
    /// @param dtype the column's dtype
    /// @param data  one batch of the column, in a carrier the writer accepts for `dtype`
    /// @return the one value of the batch, or empty
    public static Optional<ProtoScalarValue> runScalar(DType dtype, Object data) {
        // ponytail: flat dtypes only; Rust also detects nested (List, FSL, Map, Struct) constants,
        // which needs a nested-scalar builder here and nested carriers in the replay (#723)
        DType storage = dtype instanceof DType.Extension ext ? ext.storageDType() : dtype;
        if (!(storage instanceof DType.Primitive || storage instanceof DType.Bool || storage instanceof DType.Utf8
                || storage instanceof DType.Binary || storage instanceof DType.Decimal)) {
            return Optional.empty();
        }
        if (data instanceof NullableData nd) {
            boolean[] validity = nd.validity();
            if (validity.length == 0 || !SimdOperationsSupport.preferred().allEqual(validity)) {
                return Optional.empty();
            }
            if (!validity[0]) {
                return Optional.of(ProtoScalarValue.ofNullValue(ProtoNullValue.NULL_VALUE));
            }
            return runScalar(dtype, nd.values());
        }
        Object values = data instanceof ComparableValues cv ? cv.values() : data;
        if (dtype instanceof DType.Extension ext) {
            return runScalar(ext.storageDType(), values);
        }
        if (RowCount.of(values) == 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(switch (dtype) {
            case DType.Primitive p -> primitiveRunScalar(p.ptype(), values);
            case DType.Bool _ -> boolRunScalar((boolean[]) values);
            case DType.Utf8 _ -> textRunScalar(values);
            case DType.Binary _ -> binaryRunScalar((byte[][]) values);
            case DType.Decimal d -> decimalRunScalar(d, (BigDecimal[]) values);
            default -> null;
        });
    }

    private static ProtoScalarValue primitiveRunScalar(PType ptype, Object values) {
        OptionalLong bits = constantBits(values, ptype);
        if (bits.isEmpty()) {
            return null;
        }
        return buildScalar(ptype, bits.getAsLong());
    }

    private static ProtoScalarValue boolRunScalar(boolean[] values) {
        if (!SimdOperationsSupport.preferred().allEqual(values)) {
            return null;
        }
        return ProtoScalarValue.ofBoolValue(values[0]);
    }

    private static ProtoScalarValue textRunScalar(Object values) {
        if (!isConstantText(values)) {
            return null;
        }
        return ProtoScalarValue.ofStringValue(firstText(values));
    }

    private static ProtoScalarValue binaryRunScalar(byte[][] values) {
        for (byte[] value : values) {
            if (!Arrays.equals(value, values[0])) {
                return null;
            }
        }
        return ProtoScalarValue.ofBytesValue(values[0]);
    }

    private static ProtoScalarValue decimalRunScalar(DType.Decimal dtype, BigDecimal[] values) {
        if (!isConstantDecimal(values)) {
            return null;
        }
        return decimalScalar(dtype, values[0]);
    }

    private static boolean isConstantDecimal(BigDecimal[] values) {
        if (values.length == 0) {
            return false;
        }
        for (BigDecimal value : values) {
            if (value == null || value.compareTo(values[0]) != 0) {
                return false;
            }
        }
        return true;
    }

    /// Rust's decimal scalar: the unscaled value little-endian at the column's storage width.
    private static ProtoScalarValue decimalScalar(DType.Decimal dtype, BigDecimal value) {
        byte[] le = DecimalEncodingEncoder.pack(new BigDecimal[]{value}, dtype.byteWidth(), Arena.ofAuto())
                .toArray(ValueLayout.JAVA_BYTE);
        return ProtoScalarValue.ofBytesValue(le);
    }

    /// Encodes `data` as a `vortex.constant` when every value is equal (floats by raw bits), in
    /// one scan: checking first and then encoding would scan it twice.
    ///
    /// @param ptype the values' type
    /// @param data  a primitive array of `ptype`
    /// @return the constant array, or empty when the values differ
    static Optional<EncodeResult> encodeIfConstant(PType ptype, Object data) {
        OptionalLong bits = constantBits(data, ptype);
        if (bits.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(constant(ptype, bits.getAsLong(), Array.getLength(data) > 0));
    }

    /// The [#encodeIfConstant(PType, Object)] of integers already widened to `long`, as
    /// `PrimitiveArrays.toLongs` widens them: zero-extended when unsigned, sign-extended when signed.
    ///
    /// @param ptype   the integer type the values were widened from
    /// @param widened the widened values
    /// @return the constant array, or empty when the values differ
    static Optional<EncodeResult> encodeIfConstantWidened(PType ptype, long[] widened) {
        if (widened.length > 0 && !SimdOperationsSupport.preferred().allEqual(widened, PType.I64)) {
            return Optional.empty();
        }
        long bits = widened.length > 0 ? widened[0] : 0L;
        return Optional.of(constant(ptype, bits, widened.length > 0));
    }

    // A constant array's min and max are both the one repeated value, by construction -- no
    // scan needed. Empty arrays report no stats, matching every other encoder's convention.
    private static EncodeResult constant(PType ptype, long rawBits, boolean hasStats) {
        ProtoScalarValue scalar = buildScalar(ptype, rawBits);
        ProtoScalarValue stats = hasStats ? scalar : null;
        return EncodeResult.simple(EncodingId.VORTEX_CONSTANT, EncodedBuffer.bytes(MemorySegment.ofArray(scalar.encode())),
                stats, stats);
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext encodeCtx) {
        if (dtype instanceof DType.Utf8) {
            if (!isConstantText(data)) {
                return CascadeStep.notApplicable();
            }
            return CascadeStep.terminal(encode(dtype, data, encodeCtx));
        }
        if (dtype instanceof DType.Bool bool) {
            if (!SimdOperationsSupport.preferred().allEqual((boolean[]) data)) {
                return CascadeStep.notApplicable();
            }
            return CascadeStep.terminal(encode(bool, data, encodeCtx));
        }
        if (constantBits(data, ((DType.Primitive) dtype).ptype()).isEmpty()) {
            return CascadeStep.notApplicable();
        }
        return CascadeStep.terminal(encode(dtype, data, encodeCtx));
    }

    /// @param data a `String[]` or the column's UTF-8 `byte[][]`
    private static EncodeResult encodeUtf8(Object data) {
        if (!isConstantText(data)) {
            throw new VortexException(EncodingId.VORTEX_CONSTANT, "not a constant array");
        }
        ProtoScalarValue scalar = ProtoScalarValue.ofStringValue(firstText(data));
        byte[] scalarBytes = scalar.encode();
        // Both extremes are the one repeated value, by construction
        return EncodeResult.simple(EncodingId.VORTEX_CONSTANT, EncodedBuffer.bytes(MemorySegment.ofArray(scalarBytes)),
                scalar, scalar);
    }

    private static String firstText(Object data) {
        if (data instanceof byte[][] utf8) {
            return new String(utf8[0], StandardCharsets.UTF_8);
        }
        return ((String[]) data)[0];
    }

    /// Whether a non-empty `String[]` or UTF-8 `byte[][]` repeats one value.
    static boolean isConstantText(Object data) {
        if (data instanceof byte[][] utf8) {
            for (int i = 1; i < utf8.length; i++) {
                if (!Arrays.equals(utf8[i], utf8[0])) {
                    return false;
                }
            }
            return utf8.length > 0;
        }
        String[] strings = (String[]) data;
        for (int i = 1; i < strings.length; i++) {
            if (!strings[i].equals(strings[0])) {
                return false;
            }
        }
        return strings.length > 0;
    }

    private static EncodeResult encodeBool(boolean[] data) {
        if (!SimdOperationsSupport.preferred().allEqual(data)) {
            throw new VortexException(EncodingId.VORTEX_CONSTANT, "not a constant array");
        }
        boolean value = data.length == 0 || data[0];
        ProtoScalarValue scalar = ProtoScalarValue.ofBoolValue(value);
        return EncodeResult.simple(EncodingId.VORTEX_CONSTANT, EncodedBuffer.bytes(MemorySegment.ofArray(scalar.encode())));
    }

    // The raw bits of the one repeated value, or empty if the array is not constant. An empty array
    // is constant (bits 0), as `expectedRatio` already treats valueCount 0. Floats compare raw bits,
    // so distinct NaN payloads or -0.0 vs 0.0 are not constant.
    private static OptionalLong constantBits(Object data, PType ptype) {
        if (Array.getLength(data) == 0) {
            return OptionalLong.of(0L);
        }
        if (!SimdOperationsSupport.preferred().allEqual(data, ptype)) {
            return OptionalLong.empty();
        }
        // No default: every PType is handled, so a new one fails to compile here instead of at write
        // time. Unsigned values widen unsigned: a sign-extended u8 230 became the u64 scalar
        // 2^64 - 26, which Rust refuses to read ("out of range for dtype u8").
        return OptionalLong.of(switch (ptype) {
            case I8 -> ((byte[]) data)[0];
            case U8 -> Byte.toUnsignedLong(((byte[]) data)[0]);
            case I16, F16 -> ((short[]) data)[0];
            case U16 -> Short.toUnsignedLong(((short[]) data)[0]);
            case I32 -> ((int[]) data)[0];
            case U32 -> Integer.toUnsignedLong(((int[]) data)[0]);
            case I64, U64 -> ((long[]) data)[0];
            case F32 -> Float.floatToRawIntBits(((float[]) data)[0]);
            case F64 -> Double.doubleToRawLongBits(((double[]) data)[0]);
        });
    }

    private static ProtoScalarValue buildScalar(PType ptype, long rawBits) {
        return switch (ptype) {
            case U8, U16, U32, U64 -> ProtoScalarValue.ofUint64Value(rawBits);
            case I8, I16, I32, I64 -> ProtoScalarValue.ofInt64Value(rawBits);
            case F16 -> ProtoScalarValue.ofF16Value(Short.toUnsignedLong((short) rawBits));
            case F32 -> ProtoScalarValue.ofF32Value(Float.intBitsToFloat((int) rawBits));
            case F64 -> ProtoScalarValue.ofF64Value(Double.longBitsToDouble(rawBits));
        };
    }
}
