package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ArrayStats;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoListValue;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;
import io.github.dfa1.vortex.writer.ZoneAccumulator.Extremes;
import io.github.dfa1.vortex.writer.ZoneAccumulator.Kind;
import io.github.dfa1.vortex.writer.encode.FixedSizeListData;
import io.github.dfa1.vortex.writer.encode.NodeStats;
import io.github.dfa1.vortex.writer.encode.NullableData;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/// One top-level column's file statistics, written into the postscript's `statistics` segment:
/// Rust 0.93.0's `FileStatsAccumulator` (`vortex-layout/src/layouts/file_stats.rs`) over its default
/// aggregates (max, min, null count, NaN count, uncompressed size), as vortex-jni writes them.
///
/// Rust accumulates every aggregate batch by batch, so this does too:
/// - a UTF-8 or binary bound is Rust's `BoundedMin`/`BoundedMax`, cut to 64 bytes per batch, and
///   always inexact: it only approximates the `Min`/`Max` the statistics slot asks for;
/// - an all-null batch adds no bound, and a batch whose max cannot be rounded up within 64 bytes
///   leaves the column without a max;
/// - the uncompressed size is the sum of each batch's ([UncompressedSize]).
///
/// An extension column has its storage's min and max but no NaN count, a struct, list or
/// fixed-size list only its null count and size, and a variant column no statistic at all. A
/// `vortex.uuid` column, though its storage is a `FixedSizeList(U8, 16)`, has an exact min and max:
/// its 16 bytes in unsigned lexicographic order, written as a list of `u8` scalars (Rust from 0.92,
/// vortex-data/vortex#11299).
final class FileStatsAccumulator {

    private final DType dtype;
    private final Kind kind;
    private final PType ptype;
    private final DType.Decimal decimal;
    private final boolean extension;
    private final boolean nullDtype;
    private final boolean variant;
    private final boolean uuid;
    private Object min;
    private Object max;
    private boolean maxDropped;
    private long nullCount;
    private long nanCount;
    private long uncompressedSize;

    FileStatsAccumulator(DType dtype) {
        DType storage = dtype instanceof DType.Extension ext ? ext.storageDType() : dtype;
        this.dtype = dtype;
        this.kind = ZoneAccumulator.kindOf(storage);
        this.ptype = storage instanceof DType.Primitive p ? p.ptype() : null;
        this.decimal = storage instanceof DType.Decimal d ? d : null;
        this.extension = dtype instanceof DType.Extension;
        this.nullDtype = storage instanceof DType.Null;
        this.variant = storage instanceof DType.Variant;
        this.uuid = dtype instanceof DType.Extension ext && ext.extensionId() == ExtensionId.VORTEX_UUID;
        this.min = null;
        this.max = null;
        this.maxDropped = false;
        this.nullCount = 0;
        this.nanCount = 0;
        this.uncompressedSize = 0;
    }

    /// Adds one `writeChunk` batch of the column; an empty batch is skipped, as Rust filters it out.
    ///
    /// @param batch the batch's values, [NullableData]-wrapped when the column is nullable
    /// @param rows  the batch's row count
    void add(Object batch, long rows) {
        if (rows == 0 || variant) {
            return;
        }
        int n = (int) rows;
        uncompressedSize += UncompressedSize.of(dtype, batch, n);
        if (nullDtype) {
            nullCount += rows;
            return;
        }
        if (uuid) {
            addUuids(batch, n);
            return;
        }
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
    }

    /// The folded stats, every one exact unless [#boundsExact()] says otherwise.
    ///
    /// @return the column's stats, a zero null count and size when it has no row; [NodeStats#NONE]
    ///         for a variant column, which Rust gives no statistic
    NodeStats stats() {
        if (variant) {
            return NodeStats.NONE;
        }
        return new NodeStats(scalar(min), scalar(maxValue()), null, null, null, nullCount, uncompressedSize, nans());
    }

    /// The folded stats as Java values: a `Long` for an integer (a `U64` as its bits), a `Float` for
    /// an `F16`/`F32` bound and a `Double` for an `F64` one, a `Boolean`, a `BigDecimal`, a `String`
    /// for UTF-8, a `byte[]` for binary and a `UUID` for `vortex.uuid`.
    ///
    /// @return the same stats as [#stats()]; [ArrayStats#empty()] where that is [NodeStats#NONE]
    ArrayStats arrayStats() {
        if (variant) {
            return ArrayStats.empty();
        }
        Object lo = javaValue(min);
        Object hi = javaValue(maxValue());
        return new ArrayStats(lo, lo != null && boundsExact(), hi, hi != null && boundsExact(), null,
                null, null, null, nullCount, uncompressedSize, nans());
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
            case NULL_COUNT_ONLY -> uuid((byte[]) value);
            default -> value;
        };
    }

    private static UUID uuid(byte[] bytes) {
        long msb = 0;
        long lsb = 0;
        for (int b = 0; b < 8; b++) {
            msb = (msb << 8) | (bytes[b] & 0xFFL);
            lsb = (lsb << 8) | (bytes[8 + b] & 0xFFL);
        }
        return new UUID(msb, lsb);
    }

    /// A batch of a `vortex.uuid` column: its null rows, and its valid rows' extremes as unsigned
    /// 16-byte strings, the order Rust compares a `FixedSizeList(U8, 16)` scalar in.
    private void addUuids(Object batch, int rows) {
        boolean[] validity = null;
        Object storage = batch;
        if (batch instanceof NullableData nd) {
            validity = nd.validity();
            storage = nd.values();
        }
        byte[] flat = (byte[]) ((FixedSizeListData) storage).elements();
        int lo = -1;
        int hi = -1;
        for (int row = 0; row < rows; row++) {
            if (validity != null && !validity[row]) {
                nullCount++;
                continue;
            }
            int off = row * 16;
            if (lo < 0 || Arrays.compareUnsigned(flat, off, off + 16, flat, lo, lo + 16) < 0) {
                lo = off;
            }
            if (hi < 0 || Arrays.compareUnsigned(flat, off, off + 16, flat, hi, hi + 16) > 0) {
                hi = off;
            }
        }
        if (lo >= 0) {
            byte[] batchMin = Arrays.copyOfRange(flat, lo, lo + 16);
            byte[] batchMax = Arrays.copyOfRange(flat, hi, hi + 16);
            if (min == null || Arrays.compareUnsigned(batchMin, (byte[]) min) < 0) {
                min = batchMin;
            }
            if (max == null || Arrays.compareUnsigned(batchMax, (byte[]) max) > 0) {
                max = batchMax;
            }
        }
    }

    /// Whether the min and max are the column's exact extremes: they are, but for a UTF-8 or binary
    /// column, whose `BoundedMin`/`BoundedMax` Rust writes as inexact even when no bound was cut.
    ///
    /// @return `false` for a UTF-8 or binary column
    boolean boundsExact() {
        return kind != Kind.UTF8 && kind != Kind.BINARY;
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
        return switch (values) {
            case short[] a -> f16NanCount(a);
            case float[] a -> nanCount(a);
            case double[] a -> nanCount(a);
            default -> throw new IllegalStateException("not a float array: " + values.getClass().getSimpleName());
        };
    }

    private static long f16NanCount(short[] values) {
        long nans = 0;
        for (short v : values) {
            // an F16 NaN: all exponent bits set and a non-zero mantissa
            if ((v & 0x7FFF) > 0x7C00) {
                nans++;
            }
        }
        return nans;
    }

    private static long nanCount(float[] values) {
        long nans = 0;
        for (float v : values) {
            if (Float.isNaN(v)) {
                nans++;
            }
        }
        return nans;
    }

    private static long nanCount(double[] values) {
        long nans = 0;
        for (double v : values) {
            if (Double.isNaN(v)) {
                nans++;
            }
        }
        return nans;
    }

    private void addBounds(byte[] lo, byte[] hi) {
        if (lo == null) {
            // an all-null batch has no bound to add
            return;
        }
        int limit = ZoneAccumulator.BOUNDED_MAX_BYTES;
        byte[] lower = kind == Kind.UTF8 ? ZoneAccumulator.utf8LowerBound(lo) : Arrays.copyOf(lo, Math.min(lo.length, limit));
        byte[] upper = kind == Kind.UTF8 ? ZoneAccumulator.utf8UpperBound(hi) : ZoneAccumulator.binaryUpperBound(hi);
        if (min == null || Arrays.compareUnsigned(lower, (byte[]) min) < 0) {
            min = lower;
        }
        if (upper == null) {
            // Rust's BoundedMax state `Unknown`: no upper bound, now or after any later batch
            maxDropped = true;
            return;
        }
        if (max == null || Arrays.compareUnsigned(upper, (byte[]) max) > 0) {
            max = upper;
        }
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
            case NULL_COUNT_ONLY -> {
                if (!uuid) {
                    throw new IllegalStateException("no min or max for " + kind);
                }
                yield ProtoScalarValue.ofListValue(u8List((byte[]) value));
            }
        };
    }

    /// A `vortex.uuid` bound as Rust writes its `FixedSizeList(U8, 16)` scalar: one `u8` per byte.
    private static ProtoListValue u8List(byte[] bytes) {
        List<ProtoScalarValue> elements = new ArrayList<>(bytes.length);
        for (byte b : bytes) {
            elements.add(ProtoScalarValue.ofUint64Value(Byte.toUnsignedLong(b)));
        }
        return new ProtoListValue(elements);
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
