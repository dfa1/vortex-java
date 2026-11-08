package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ArrayStats;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;
import io.github.dfa1.vortex.writer.ZoneAccumulator.Extremes;
import io.github.dfa1.vortex.writer.ZoneAccumulator.Kind;
import io.github.dfa1.vortex.writer.encode.NodeStats;
import io.github.dfa1.vortex.writer.encode.NullableData;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.OptionalLong;

/// One top-level column's file statistics, written into the postscript's `statistics` segment:
/// Rust 0.90.0's `FileStatsAccumulator` (`vortex-layout/src/layouts/file_stats.rs`) over the default
/// `PRUNING_STATS` (min, max, sum, null count, NaN count), as vortex-jni writes them.
///
/// Rust takes every stat per `writeChunk` batch and folds the batches at the end, so this does too:
/// - a UTF-8 or binary bound is cut to 64 bytes per batch, and it is inexact when any batch's bound
///   was cut, even a batch that does not hold the extreme;
/// - a UTF-8 or binary max is dropped when any batch has none (all null, or a bound that cannot be
///   rounded up): skipping that batch would be unsound;
/// - a sum is dropped when any batch's sum, or the fold, overflows: integers in `i64`/`u64`, a
///   decimal of precision `p` past `p + 10` digits in a batch and `p + 20` in the fold (Rust sums
///   the batch sums with a second `Sum`).
///
/// An extension column has its storage's min and max but no sum or NaN count, a struct, list or
/// fixed-size list only its null count, and a variant column no statistic at all.
final class FileStatsAccumulator {

    /// Rust's `MAX_PRECISION` for decimals.
    private static final int MAX_DECIMAL_PRECISION = 76;

    private final Kind kind;
    private final PType ptype;
    private final DType.Decimal decimal;
    private final boolean extension;
    private final boolean nullDtype;
    private final boolean variant;
    private boolean seenBatch;
    private Object min;
    private boolean minTruncated;
    private Object max;
    private boolean maxTruncated;
    private boolean maxDropped;
    private Object sum;
    private boolean sumOverflowed;
    private long nullCount;
    private long nanCount;

    FileStatsAccumulator(DType dtype) {
        DType storage = dtype instanceof DType.Extension ext ? ext.storageDType() : dtype;
        this.kind = ZoneAccumulator.kindOf(storage);
        this.ptype = storage instanceof DType.Primitive p ? p.ptype() : null;
        this.decimal = storage instanceof DType.Decimal d ? d : null;
        this.extension = dtype instanceof DType.Extension;
        this.nullDtype = storage instanceof DType.Null;
        this.variant = storage instanceof DType.Variant;
        this.seenBatch = false;
        this.min = null;
        this.minTruncated = false;
        this.max = null;
        this.maxTruncated = false;
        this.maxDropped = false;
        this.sum = null;
        this.sumOverflowed = false;
        this.nullCount = 0;
        this.nanCount = 0;
    }

    /// Adds one `writeChunk` batch of the column; an empty batch is skipped, as Rust filters it out.
    ///
    /// @param batch the batch's values, [NullableData]-wrapped when the column is nullable
    /// @param rows  the batch's row count
    void add(Object batch, long rows) {
        if (rows == 0 || variant) {
            return;
        }
        seenBatch = true;
        if (nullDtype) {
            nullCount += rows;
            return;
        }
        int n = (int) rows;
        Object data = kind == Kind.NULL_COUNT_ONLY ? ZoneAccumulator.validityOnly(batch, n) : ZoneAccumulator.comparable(batch);
        if (ptype != null && !(data instanceof NullableData)) {
            // the common case, on the writer's critical path: the vectorized kernel, no widened copy
            addPrimitive(data);
        } else {
            Extremes extremes = ZoneAccumulator.extremes(kind, ptype, data, 0, n);
            nullCount += extremes.nulls();
            nanCount += extremes.nans();
            if (kind == Kind.UTF8 || kind == Kind.BINARY) {
                addBounds((byte[]) extremes.min(), (byte[]) extremes.max());
            } else if (extremes.min() != null) {
                addExtremes(extremes.min(), extremes.max());
            }
        }
        if (summable() && !sumOverflowed) {
            Object values = data;
            boolean[] validity = null;
            if (data instanceof NullableData nd) {
                values = nd.values();
                validity = nd.validity();
            }
            addSum(values, validity);
        }
    }

    /// The folded stats, every one exact unless [#minExact()] or [#maxExact()] says otherwise.
    ///
    /// @return the column's stats; [NodeStats#NONE] for a variant column or one without rows, whose
    ///         per-batch stats Rust drops as all null
    NodeStats stats() {
        if (!seenBatch) {
            return NodeStats.NONE;
        }
        ProtoScalarValue sumScalar = null;
        if (summable() && !sumOverflowed) {
            sumScalar = sumScalar(sum);
        }
        return new NodeStats(scalar(min), scalar(maxValue()), null, null, null, nullCount, null, nans(), sumScalar);
    }

    /// The folded stats as Java values: a `Long` for an integer (a `U64` as its bits) or a bool sum,
    /// a `Float` for an `F16`/`F32` bound and a `Double` for an `F64` one or any float sum, a
    /// `Boolean`, a `BigDecimal`, a `String` for UTF-8 and a `byte[]` for binary.
    ///
    /// @return the same stats as [#stats()]; [ArrayStats#empty()] where that is [NodeStats#NONE]
    ArrayStats arrayStats() {
        if (!seenBatch) {
            return ArrayStats.empty();
        }
        Object sumValue = null;
        if (summable() && !sumOverflowed) {
            sumValue = sum;
        }
        Object lo = javaValue(min);
        Object hi = javaValue(maxValue());
        return new ArrayStats(lo, lo != null && !minTruncated, hi, hi != null && !maxTruncated, sumValue,
                null, null, null, nullCount, null, nans());
    }

    private Object maxValue() {
        if (maxDropped) {
            return null;
        }
        return max;
    }

    private Long nans() {
        if (!extension && (kind == Kind.F16 || kind == Kind.FLOAT)) {
            return nanCount;
        }
        return null;
    }

    private Object javaValue(Object value) {
        if (value == null) {
            return null;
        }
        return switch (kind) {
            case F16 -> ((Double) value).floatValue();
            case FLOAT -> {
                if (ptype == PType.F32) {
                    yield ((Double) value).floatValue();
                }
                yield value;
            }
            case UTF8 -> new String((byte[]) value, StandardCharsets.UTF_8);
            default -> value;
        };
    }

    /// Whether the min is the column's exact minimum: no batch cut its bound.
    ///
    /// @return `false` when a UTF-8 or binary batch minimum was longer than 64 bytes
    boolean minExact() {
        return !minTruncated;
    }

    /// Whether the max is the column's exact maximum: no batch cut its bound.
    ///
    /// @return `false` when a UTF-8 or binary batch maximum was longer than 64 bytes
    boolean maxExact() {
        return !maxTruncated;
    }

    private void addExtremes(Object lo, Object hi) {
        if (min == null || compare(lo, min) < 0) {
            min = lo;
        }
        if (max == null || compare(hi, max) > 0) {
            max = hi;
        }
    }

    /// A batch of a primitive column without nulls: its extremes as Rust's `skip_nans` min/max,
    /// `-0.0` below `0.0`.
    private void addPrimitive(Object values) {
        long[] extremes = SimdOperationsSupport.preferred().minMax(values, ptype);
        if (kind == Kind.F16 || kind == Kind.FLOAT) {
            nanCount += nanCount(values);
        }
        if (extremes.length == 2) {
            addExtremes(fromBits(extremes[0]), fromBits(extremes[1]));
        }
    }

    /// A [io.github.dfa1.vortex.core.simd.SimdOperations#minMax(Object, PType)] element as the boxed
    /// value the fold compares: a float's raw bits
    /// back to its value.
    private Object fromBits(long bits) {
        return switch (ptype) {
            case F16 -> (double) Float.float16ToFloat((short) bits);
            case F32 -> (double) Float.intBitsToFloat((int) bits);
            case F64 -> Double.longBitsToDouble(bits);
            default -> bits;
        };
    }

    private static long nanCount(Object values) {
        long nans = 0;
        switch (values) {
            case short[] a -> {
                for (short v : a) {
                    // an F16 NaN: all exponent bits set and a non-zero mantissa
                    if ((v & 0x7FFF) > 0x7C00) {
                        nans++;
                    }
                }
            }
            case float[] a -> {
                for (float v : a) {
                    if (Float.isNaN(v)) {
                        nans++;
                    }
                }
            }
            case double[] a -> {
                for (double v : a) {
                    if (Double.isNaN(v)) {
                        nans++;
                    }
                }
            }
            default -> throw new IllegalStateException("not a float array: " + values.getClass().getSimpleName());
        }
        return nans;
    }

    private void addBounds(byte[] lo, byte[] hi) {
        if (lo == null) {
            // an all-null batch: its null max makes the column max unsound, its null min is skipped
            maxDropped = true;
            return;
        }
        int limit = ZoneAccumulator.BOUNDED_MAX_BYTES;
        byte[] lower = kind == Kind.UTF8 ? ZoneAccumulator.utf8LowerBound(lo) : Arrays.copyOf(lo, Math.min(lo.length, limit));
        byte[] upper = kind == Kind.UTF8 ? ZoneAccumulator.utf8UpperBound(hi) : ZoneAccumulator.binaryUpperBound(hi);
        minTruncated |= lo.length > limit;
        if (min == null || Arrays.compareUnsigned(lower, (byte[]) min) < 0) {
            min = lower;
        }
        if (upper == null) {
            maxDropped = true;
            return;
        }
        maxTruncated |= hi.length > limit;
        if (max == null || Arrays.compareUnsigned(upper, (byte[]) max) > 0) {
            max = upper;
        }
    }

    private boolean summable() {
        return !extension && (kind == Kind.INT || kind == Kind.F16 || kind == Kind.FLOAT
                || kind == Kind.BOOL || kind == Kind.DECIMAL);
    }

    private void addSum(Object values, boolean[] validity) {
        Object batchSum = switch (kind) {
            case INT -> {
                OptionalLong s = SimdOperationsSupport.preferred().sum(values, ptype);
                yield s.isPresent() ? s.getAsLong() : null;
            }
            case F16, FLOAT -> SimdOperationsSupport.preferred().sumFloating(values, ptype);
            case BOOL -> trueCount((boolean[]) values, validity);
            case DECIMAL -> decimalSum((BigDecimal[]) values, validity, decimal.precision() + 10);
            default -> throw new IllegalStateException("no sum for " + kind);
        };
        Object folded = batchSum == null ? null : fold(sum == null ? zeroSum() : sum, batchSum);
        if (folded == null) {
            sumOverflowed = true;
        }
        sum = folded;
    }

    /// `acc + batch` in the sum's type, or `null` on overflow.
    private Object fold(Object acc, Object batch) {
        return switch (kind) {
            case INT -> ptype.isUnsigned() ? addUnsigned((Long) acc, (Long) batch) : addSigned((Long) acc, (Long) batch);
            case BOOL -> addUnsigned((Long) acc, (Long) batch);
            case F16, FLOAT -> (Double) acc + (Double) batch;
            case DECIMAL -> fitsPrecision(((BigDecimal) acc).add((BigDecimal) batch), decimal.precision() + 20);
            default -> throw new IllegalStateException("no sum for " + kind);
        };
    }

    private Object zeroSum() {
        return switch (kind) {
            case F16, FLOAT -> 0.0;
            case DECIMAL -> BigDecimal.ZERO.setScale(decimal.scale());
            default -> 0L;
        };
    }

    private static Long addSigned(long a, long b) {
        long r = a + b;
        // overflow iff both operands have the sign the result lacks
        if (((a ^ r) & (b ^ r)) < 0) {
            return null;
        }
        return r;
    }

    private static Long addUnsigned(long a, long b) {
        long r = a + b;
        if (Long.compareUnsigned(r, a) < 0) {
            return null;
        }
        return r;
    }

    private static long trueCount(boolean[] values, boolean[] validity) {
        long count = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] && (validity == null || validity[i])) {
                count++;
            }
        }
        return count;
    }

    private static BigDecimal decimalSum(BigDecimal[] values, boolean[] validity, int precision) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null && (validity == null || validity[i])) {
                total = total.add(values[i]);
            }
        }
        return fitsPrecision(total, precision);
    }

    /// `value`, or `null` when its unscaled value has more digits than `precision` (capped at
    /// Rust's maximum): Rust's `fits_in_precision` check on a decimal sum.
    private static BigDecimal fitsPrecision(BigDecimal value, int precision) {
        int digits = Math.min(precision, MAX_DECIMAL_PRECISION);
        if (value.unscaledValue().abs().compareTo(BigInteger.TEN.pow(digits)) >= 0) {
            return null;
        }
        return value;
    }

    private int compare(Object a, Object b) {
        return switch (kind) {
            case INT -> ptype.isUnsigned() ? Long.compareUnsigned((Long) a, (Long) b) : Long.compare((Long) a, (Long) b);
            case F16, FLOAT -> Double.compare((Double) a, (Double) b);
            case BOOL -> Boolean.compare((Boolean) a, (Boolean) b);
            case DECIMAL -> ((BigDecimal) a).compareTo((BigDecimal) b);
            default -> throw new IllegalStateException("no order for " + kind);
        };
    }

    /// A min or max as the scalar Rust writes for the column's storage type.
    private ProtoScalarValue scalar(Object value) {
        if (value == null) {
            return null;
        }
        return switch (kind) {
            case INT -> ptype.isUnsigned() ? ProtoScalarValue.ofUint64Value((Long) value) : ProtoScalarValue.ofInt64Value((Long) value);
            case F16 -> ProtoScalarValue.ofF16Value(Short.toUnsignedLong(Float.floatToFloat16(((Double) value).floatValue())));
            case FLOAT -> {
                if (ptype == PType.F32) {
                    yield ProtoScalarValue.ofF32Value(((Double) value).floatValue());
                }
                yield ProtoScalarValue.ofF64Value((Double) value);
            }
            case BOOL -> ProtoScalarValue.ofBoolValue((Boolean) value);
            case DECIMAL -> ProtoScalarValue.ofBytesValue(littleEndian(((BigDecimal) value).unscaledValue(), decimal.byteWidth()));
            case UTF8 -> ProtoScalarValue.ofStringValue(new String((byte[]) value, StandardCharsets.UTF_8));
            case BINARY -> ProtoScalarValue.ofBytesValue((byte[]) value);
            case NULL_COUNT_ONLY -> throw new IllegalStateException("no min or max for " + kind);
        };
    }

    /// A sum as the scalar of Rust's sum dtype: `i64`, `u64` (unsigned and bool), `f64`, or a
    /// decimal of precision `p + 20`.
    private ProtoScalarValue sumScalar(Object value) {
        return switch (kind) {
            case INT -> ptype.isUnsigned() ? ProtoScalarValue.ofUint64Value((Long) value) : ProtoScalarValue.ofInt64Value((Long) value);
            case BOOL -> ProtoScalarValue.ofUint64Value((Long) value);
            case F16, FLOAT -> ProtoScalarValue.ofF64Value((Double) value);
            case DECIMAL -> {
                int precision = Math.min(decimal.precision() + 20, MAX_DECIMAL_PRECISION);
                int width = DType.Decimal.of(precision, decimal.scale(), true).byteWidth();
                yield ProtoScalarValue.ofBytesValue(littleEndian(((BigDecimal) value).unscaledValue(), width));
            }
            default -> throw new IllegalStateException("no sum for " + kind);
        };
    }

    private static byte[] littleEndian(BigInteger unscaled, int width) {
        // toByteArray is big-endian and minimal: reverse it, then sign-extend to the width
        byte[] be = unscaled.toByteArray();
        byte fill = unscaled.signum() < 0 ? (byte) -1 : 0;
        byte[] le = new byte[width];
        for (int b = 0; b < width; b++) {
            le[b] = b < be.length ? be[be.length - 1 - b] : fill;
        }
        return le;
    }
}
