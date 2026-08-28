package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.PrimitiveEncodingEncoder;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/// Pure helpers for `vortex.stats` zone-map stats (ADR): deriving the per-zone min/max/sum dtype
/// for a column, encoding a zone-map layout's `vortex.stats` metadata bytes, and decoding each
/// zone's serialized [ProtoScalarValue] stat back into a typed array the zone-map's struct
/// encoder expects. Matches [io.github.dfa1.vortex.reader]'s `ZonedStatsSchema`.
final class ZoneMapStatCodec {

    // Stat ordinals in the Rust `Stat` enum (see ZonedStatsSchema). Emitted: MAX, MIN, SUM, NULL_COUNT.
    private static final int STAT_MAX = 3;
    private static final int STAT_MIN = 4;
    private static final int STAT_SUM = 5;
    private static final int STAT_NULL_COUNT = 6;

    private ZoneMapStatCodec() {
    }

    /// `vortex.stats` metadata: `u32` zone length (LE) + a 1-byte stat bitset (LSB-first) with the
    /// NULL_COUNT bit always set and the MAX/MIN and SUM bits set when present, matching
    /// [io.github.dfa1.vortex.reader] `ZonedStatsSchema`.
    static byte[] zonedMetadataBytes(long zoneLen, boolean hasMinMax, boolean hasSum) {
        byte[] meta = new byte[5];
        ByteBuffer.wrap(meta).order(ByteOrder.LITTLE_ENDIAN).putInt((int) zoneLen);
        int bits = 1 << STAT_NULL_COUNT;
        if (hasMinMax) {
            bits |= (1 << STAT_MAX) | (1 << STAT_MIN);
        }
        if (hasSum) {
            bits |= (1 << STAT_SUM);
        }
        meta[4] = (byte) bits;
        return meta;
    }

    /// The (nullable) dtype a zone-map stores per-zone min/max in for `dtype`, or `null` when the
    /// column has no recordable min/max. Primitives store the primitive; extension columns unwrap
    /// to their storage primitive (`ExtEncoding` propagates the storage min/max scalars unchanged);
    /// Utf8 stores the full string value. Binary is excluded:
    /// `vortex.varbin` records its min/max as string scalars, not `bytes`.
    static DType zoneMinMaxDtype(DType dtype) {
        return switch (dtype) {
            case DType.Primitive p -> p.withNullable(true);
            case DType.Extension ext when ext.storageDType() instanceof DType.Primitive p -> p.withNullable(true);
            case DType.Utf8 u -> u.withNullable(true);
            default -> null;
        };
    }

    /// The (nullable) dtype a zone-map stores SUM in for `dtype`, or `null` when the column has no
    /// recordable sum. Only plain numeric primitives are summed — signed → `i64`, unsigned → `u64`,
    /// float → `f64` — matching Rust, which emits SUM for primitives and decimals but not for
    /// Utf8/extension/date columns even when their storage is numeric.
    static DType zoneSumDtype(DType dtype) {
        if (!(dtype instanceof DType.Primitive p)) {
            return null;
        }
        return switch (p.ptype()) {
            case U8, U16, U32, U64 -> new DType.Primitive(PType.U64, true);
            case I8, I16, I32, I64 -> new DType.Primitive(PType.I64, true);
            case F16, F32, F64 -> new DType.Primitive(PType.F64, true);
        };
    }

    /// The serialized per-chunk SUM scalar for `data` of logical type `dtype`, or `null` when the
    /// column is not summable (non-primitive) or the sum overflowed. Validity placeholders are zero
    /// and therefore sum-neutral, so a nullable carrier sums correctly via its values.
    static byte[] columnSum(DType dtype, Object data) {
        if (!(dtype instanceof DType.Primitive p)) {
            return null;
        }
        Object values = data instanceof NullableData nd ? nd.values() : data;
        return PrimitiveEncodingEncoder.sumStat(p.ptype(), values);
    }

    /// Builds the per-zone min (or max) values array for the resolved min/max `dtype`, decoding each
    /// zone's serialized [ProtoScalarValue] stat into the array shape its encoder expects.
    static Object zoneStatValues(DType minMaxDtype, List<byte[]> statBytes) throws IOException {
        return switch (minMaxDtype) {
            case DType.Primitive p -> statColumn(p.ptype(), statBytes);
            case DType.Utf8 _ -> statStringColumn(statBytes);
            default -> throw new IllegalStateException("no zone stat values for " + minMaxDtype);
        };
    }

    /// Builds the per-zone SUM array for `sumDtype` (i64/u64 → `long[]`, f64 → `double[]`), decoding
    /// each zone's serialized scalar. Zones whose sum overflowed carry a `null` entry in `sumBytes`;
    /// `valid[i]` is set accordingly so the stat field reports them as null.
    static Object sumColumn(DType sumDtype, List<byte[]> sumBytes, boolean[] valid) throws IOException {
        PType ptype = ((DType.Primitive) sumDtype).ptype();
        int n = sumBytes.size();
        if (ptype == PType.F64) {
            double[] a = new double[n];
            for (int i = 0; i < n; i++) {
                valid[i] = sumBytes.get(i) != null;
                a[i] = valid[i] ? scalarDouble(sumBytes.get(i)) : 0.0;
            }
            return a;
        }
        long[] a = new long[n];
        for (int i = 0; i < n; i++) {
            valid[i] = sumBytes.get(i) != null;
            a[i] = valid[i] ? scalarLong(sumBytes.get(i)) : 0L;
        }
        return a;
    }

    /// Builds the per-zone string array by decoding each zone's serialized string [ProtoScalarValue]
    /// stat. Used for Utf8 columns whose `vortex.varbin` encoder records full string min/max scalars.
    private static String[] statStringColumn(List<byte[]> statBytes) throws IOException {
        String[] out = new String[statBytes.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = decodeScalar(statBytes.get(i)).string_value();
        }
        return out;
    }

    /// Builds the per-zone values array in the storage shape the primitive encoder expects, decoding
    /// each zone's serialized [ProtoScalarValue] stat.
    private static Object statColumn(PType ptype, List<byte[]> statBytes) throws IOException {
        int n = statBytes.size();
        return switch (ptype) {
            case I8, U8 -> {
                byte[] a = new byte[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (byte) scalarLong(statBytes.get(i));
                }
                yield a;
            }
            case I16, U16 -> {
                short[] a = new short[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (short) scalarLong(statBytes.get(i));
                }
                yield a;
            }
            case I32, U32 -> {
                int[] a = new int[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (int) scalarLong(statBytes.get(i));
                }
                yield a;
            }
            case I64, U64 -> {
                long[] a = new long[n];
                for (int i = 0; i < n; i++) {
                    a[i] = scalarLong(statBytes.get(i));
                }
                yield a;
            }
            case F32 -> {
                float[] a = new float[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (float) scalarDouble(statBytes.get(i));
                }
                yield a;
            }
            case F64 -> {
                double[] a = new double[n];
                for (int i = 0; i < n; i++) {
                    a[i] = scalarDouble(statBytes.get(i));
                }
                yield a;
            }
            case F16 -> {
                // F16 min/max are serialized as f32 scalars; re-pack to float16 storage.
                short[] a = new short[n];
                for (int i = 0; i < n; i++) {
                    a[i] = Float.floatToFloat16((float) scalarDouble(statBytes.get(i)));
                }
                yield a;
            }
        };
    }

    private static long scalarLong(byte[] bytes) throws IOException {
        // Integer columns serialize min/max as int64 (signed) or uint64 (unsigned).
        ProtoScalarValue sv = decodeScalar(bytes);
        return sv.int64_value() != null ? sv.int64_value() : sv.uint64_value();
    }

    private static double scalarDouble(byte[] bytes) throws IOException {
        // Float columns serialize min/max as f64 (F64) or f32 (F32). Branch rather than use a
        // ternary so the F32 path widens Float -> double explicitly instead of mixing boxed types.
        ProtoScalarValue sv = decodeScalar(bytes);
        if (sv.f64_value() != null) {
            return sv.f64_value();
        }
        return sv.f32_value();
    }

    private static ProtoScalarValue decodeScalar(byte[] bytes) throws IOException {
        MemorySegment seg = MemorySegment.ofArray(bytes);
        return ProtoScalarValue.decode(seg, 0, seg.byteSize());
    }
}
