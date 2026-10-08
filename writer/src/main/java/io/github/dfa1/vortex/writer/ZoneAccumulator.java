package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.encode.ComparableValues;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.StructData;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Per-column `vortex.zoned` statistics, one zone per [#ZONE_LEN] rows of the column regardless of
/// how the rows arrive in `writeChunk` batches — Rust's `ZonedStrategy` over its 8192-row
/// repartition. A batch's last partial zone is carried over and completed by the next batch.
///
/// The aggregate set per dtype is Rust 0.86.1's `default_zoned_aggregate_fns`, as recorded by
/// vortex-jni: `bounded_max`/`bounded_min` (64 bytes) for Utf8/Binary; `max`/`min` (skipping NaN)
/// plus `nan_count` for floats; `max`/`min` for the other orderable types; `null_count` always.
final class ZoneAccumulator {

    /// Rust's `ZonedLayoutOptions::default().block_size`.
    static final int ZONE_LEN = 8192;

    /// Rust's `default_bounded_stat_max_bytes()`.
    private static final int BOUNDED_MAX_BYTES = 64;

    private enum Kind { INT, F16, FLOAT, BOOL, DECIMAL, UTF8, BINARY, NULL_COUNT_ONLY }

    private final DType dtype;
    private final Kind kind;
    private final PType ptype;
    // A partial zone (< ZONE_LEN rows) left over from the previous batch, or null.
    private Object carry;
    private final List<Object> mins = new ArrayList<>();
    private final List<Object> maxs = new ArrayList<>();
    private final List<Boolean> maxUnknown = new ArrayList<>();
    private final List<Long> nullCounts = new ArrayList<>();
    private final List<Long> nanCounts = new ArrayList<>();

    ZoneAccumulator(DType dtype) {
        this.dtype = dtype;
        DType storage = dtype instanceof DType.Extension ext ? ext.storageDType() : dtype;
        this.ptype = storage instanceof DType.Primitive p ? p.ptype() : null;
        this.kind = switch (storage) {
            case DType.Primitive p when p.ptype() == PType.F16 -> Kind.F16;
            case DType.Primitive p when p.ptype().isFloating() -> Kind.FLOAT;
            case DType.Primitive _ -> Kind.INT;
            case DType.Bool _ -> Kind.BOOL;
            case DType.Decimal _ -> Kind.DECIMAL;
            case DType.Utf8 _ -> Kind.UTF8;
            case DType.Binary _ -> Kind.BINARY;
            default -> Kind.NULL_COUNT_ONLY;
        };
    }

    /// Adds one batch of the column, as handed to the column's encoder.
    ///
    /// @param data the batch's values, [NullableData]-wrapped when the column is nullable
    /// @param rows the batch's row count
    void add(Object batch, long rows) {
        // Only validity matters for a null-count-only column, whose carriers (lists, structs, …)
        // are not plain arrays: reduce it to a validity-only array so slicing stays uniform.
        Object data = kind == Kind.NULL_COUNT_ONLY ? validityOnly(batch, (int) rows) : comparable(batch);
        Object values = data instanceof NullableData nd ? nd.values() : data;
        boolean[] validity = data instanceof NullableData nd ? nd.validity() : null;
        int n = (int) rows;
        int off = 0;
        if (carry != null) {
            // Complete the carried-over zone with this batch's head, then continue in place.
            int carried = length(carry);
            int take = Math.min(ZONE_LEN - carried, n);
            Object joined = concat(carry, slice(values, validity, 0, take));
            off = take;
            carry = null;
            if (carried + take == ZONE_LEN) {
                zone(joined, 0, ZONE_LEN);
            } else {
                carry = joined;
                return;
            }
        }
        while (n - off >= ZONE_LEN) {
            zone(data, off, ZONE_LEN);
            off += ZONE_LEN;
        }
        if (off < n) {
            carry = slice(values, validity, off, n - off);
        }
    }

    /// Closes the final partial zone, if any.
    void finish() {
        if (carry != null) {
            zone(carry, 0, length(carry));
            carry = null;
        }
    }

    int zoneCount() {
        return nullCounts.size();
    }

    // ── table ──────────────────────────────────────────────────────────────

    /// The ordered aggregate ids this column records, as written in the layout metadata.
    List<String> aggregateIds() {
        return switch (kind) {
            case UTF8, BINARY -> List.of("vortex.bounded_max", "vortex.bounded_min", "vortex.null_count");
            case F16, FLOAT -> List.of("vortex.max", "vortex.min", "vortex.nan_count", "vortex.null_count");
            case INT, BOOL, DECIMAL -> List.of("vortex.max", "vortex.min", "vortex.null_count");
            case NULL_COUNT_ONLY -> List.of("vortex.null_count");
        };
    }

    /// `vortex.zoned` layout metadata: version byte `1`, then the protobuf
    /// `ZonedMetadataProto { uint32 zone_len = 1; repeated AggregateSpecProto aggregate_specs = 2; }`
    /// with `AggregateSpecProto { string id = 1; bytes options = 2; }` — byte-for-byte what
    /// vortex-jni writes.
    byte[] metadata() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);
        out.write(0x08);
        writeVarint(out, ZONE_LEN);
        for (String id : aggregateIds()) {
            byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
            byte[] options = options(id);
            ByteArrayOutputStream spec = new ByteArrayOutputStream();
            spec.write(0x0a);
            writeVarint(spec, idBytes.length);
            spec.writeBytes(idBytes);
            if (options.length > 0) {
                spec.write(0x12);
                writeVarint(spec, options.length);
                spec.writeBytes(options);
            }
            out.write(0x12);
            writeVarint(out, spec.size());
            out.writeBytes(spec.toByteArray());
        }
        return out.toByteArray();
    }

    private static byte[] options(String id) {
        return switch (id) {
            // NumericalAggregateOpts { skip_nans: true }
            case "vortex.max", "vortex.min" -> new byte[]{0x08, 0x01};
            // BoundedMaxOptions/BoundedMinOptions: max_bytes as u64 LE
            case "vortex.bounded_max", "vortex.bounded_min" -> new byte[]{BOUNDED_MAX_BYTES, 0, 0, 0, 0, 0, 0, 0};
            default -> new byte[0];
        };
    }

    /// The stats-table struct dtype: one nullable field per aggregate, in [#aggregateIds()] order,
    /// each holding that aggregate's Rust partial-state dtype.
    DType.Struct tableDtype() {
        DType value = dtype.withNullable(true);
        List<ColumnName> names = new ArrayList<>();
        List<DType> types = new ArrayList<>();
        for (String id : aggregateIds()) {
            names.add(ColumnName.of(id.substring("vortex.".length())));
            types.add(switch (id) {
                case "vortex.bounded_max" -> new DType.Struct(
                        List.of(ColumnName.of("bound"), ColumnName.of("unknown")),
                        List.of(value, DType.BOOL), true);
                case "vortex.max", "vortex.min", "vortex.bounded_min" -> value;
                default -> new DType.Primitive(PType.U64, true);
            });
        }
        return new DType.Struct(List.copyOf(names), List.copyOf(types), false);
    }

    /// The stats-table data, aligned with [#tableDtype()].
    StructData tableData() {
        int zones = zoneCount();
        List<Object> fields = new ArrayList<>();
        for (String id : aggregateIds()) {
            fields.add(switch (id) {
                case "vortex.max" -> valueColumn(maxs);
                case "vortex.min", "vortex.bounded_min" -> valueColumn(mins);
                case "vortex.bounded_max" -> boundedMaxColumn(zones);
                case "vortex.nan_count" -> counts(nanCounts);
                default -> counts(nullCounts);
            });
        }
        return new StructData(fields);
    }

    private static NullableData counts(List<Long> counts) {
        long[] values = counts.stream().mapToLong(Long::longValue).toArray();
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        return new NullableData(values, valid);
    }

    private NullableData boundedMaxColumn(int zones) {
        // The state struct is null for a zone with no non-null value; otherwise `unknown` says
        // whether any bound fit, and `bound` holds it when one did.
        boolean[] stateValid = new boolean[zones];
        boolean[] unknown = new boolean[zones];
        List<Object> bounds = new ArrayList<>(zones);
        for (int i = 0; i < zones; i++) {
            boolean hasValue = maxs.get(i) != null || maxUnknown.get(i);
            stateValid[i] = hasValue;
            unknown[i] = maxUnknown.get(i);
            bounds.add(maxs.get(i));
        }
        return new NullableData(new StructData(List.of(valueColumn(bounds), unknown)), stateValid);
    }

    /// A per-zone value column in the column's own carrier, `null` entries invalid.
    private NullableData valueColumn(List<Object> perZone) {
        int zones = perZone.size();
        boolean[] valid = new boolean[zones];
        for (int i = 0; i < zones; i++) {
            valid[i] = perZone.get(i) != null;
        }
        Object values = switch (kind) {
            case INT, F16 -> {
                long[] longs = new long[zones];
                for (int i = 0; i < zones; i++) {
                    longs[i] = valid[i] ? (Long) perZone.get(i) : 0L;
                }
                yield PrimitiveArrays.fromBitsArray(longs, ptype, EncodingId.VORTEX_PRIMITIVE);
            }
            case FLOAT -> {
                if (ptype == PType.F32) {
                    float[] a = new float[zones];
                    for (int i = 0; i < zones; i++) {
                        a[i] = valid[i] ? ((Double) perZone.get(i)).floatValue() : 0f;
                    }
                    yield a;
                }
                double[] a = new double[zones];
                for (int i = 0; i < zones; i++) {
                    a[i] = valid[i] ? (Double) perZone.get(i) : 0.0;
                }
                yield a;
            }
            case BOOL -> {
                boolean[] a = new boolean[zones];
                for (int i = 0; i < zones; i++) {
                    a[i] = valid[i] && (Boolean) perZone.get(i);
                }
                yield a;
            }
            case DECIMAL -> perZone.toArray(new BigDecimal[0]);
            case UTF8 -> {
                String[] a = new String[zones];
                for (int i = 0; i < zones; i++) {
                    a[i] = valid[i] ? new String((byte[]) perZone.get(i), StandardCharsets.UTF_8) : null;
                }
                yield a;
            }
            case BINARY -> perZone.toArray(new byte[0][]);
            case NULL_COUNT_ONLY -> throw new IllegalStateException("no value column for " + dtype);
        };
        return new NullableData(values, valid);
    }

    // ── per-zone stats ─────────────────────────────────────────────────────

    /// Records the zone of `len` rows starting at `off` in `data`.
    private void zone(Object data, int off, int len) {
        Object values = data instanceof NullableData nd ? nd.values() : data;
        boolean[] validity = data instanceof NullableData nd ? nd.validity() : null;
        long nulls = 0;
        long nans = 0;
        Object min = null;
        Object max = null;
        boolean unknown = false;
        switch (kind) {
            case INT -> {
                long[] longs = PrimitiveArrays.toLongs(slice(values, null, off, len), ptype, EncodingId.VORTEX_PRIMITIVE);
                boolean unsigned = ptype.isUnsigned();
                for (int i = 0; i < len; i++) {
                    if (validity != null && !validity[off + i]) {
                        nulls++;
                        continue;
                    }
                    long v = longs[i];
                    if (min == null || compare(v, (Long) min, unsigned) < 0) {
                        min = v;
                    }
                    if (max == null || compare(v, (Long) max, unsigned) > 0) {
                        max = v;
                    }
                }
            }
            case F16, FLOAT -> {
                for (int i = 0; i < len; i++) {
                    if (validity != null && !validity[off + i]) {
                        nulls++;
                        continue;
                    }
                    double v = switch (values) {
                        case short[] a -> Float.float16ToFloat(a[off + i]);
                        case float[] a -> a[off + i];
                        default -> ((double[]) values)[off + i];
                    };
                    if (Double.isNaN(v)) {
                        nans++;
                        continue;
                    }
                    if (min == null || v < (Double) min) {
                        min = v;
                    }
                    if (max == null || v > (Double) max) {
                        max = v;
                    }
                }
                if (kind == Kind.F16) {
                    // The F16 table column is stored as raw half-float bits like the data.
                    min = min == null ? null : (long) Float.floatToFloat16(((Double) min).floatValue());
                    max = max == null ? null : (long) Float.floatToFloat16(((Double) max).floatValue());
                }
            }
            case BOOL -> {
                boolean[] a = (boolean[]) values;
                for (int i = 0; i < len; i++) {
                    if (validity != null && !validity[off + i]) {
                        nulls++;
                        continue;
                    }
                    boolean v = a[off + i];
                    min = min == null ? v : (Boolean) min && v;
                    max = max == null ? v : (Boolean) max || v;
                }
            }
            case DECIMAL -> {
                BigDecimal[] a = (BigDecimal[]) values;
                for (int i = 0; i < len; i++) {
                    BigDecimal v = a[off + i];
                    if (v == null || (validity != null && !validity[off + i])) {
                        nulls++;
                        continue;
                    }
                    if (min == null || v.compareTo((BigDecimal) min) < 0) {
                        min = v;
                    }
                    if (max == null || v.compareTo((BigDecimal) max) > 0) {
                        max = v;
                    }
                }
            }
            case UTF8, BINARY -> {
                byte[] lo = null;
                byte[] hi = null;
                for (int i = 0; i < len; i++) {
                    Object raw = values instanceof String[] s ? s[off + i] : ((byte[][]) values)[off + i];
                    if (raw == null || (validity != null && !validity[off + i])) {
                        nulls++;
                        continue;
                    }
                    // Rust orders strings by their UTF-8 bytes, not by UTF-16 (String#compareTo).
                    byte[] v = raw instanceof String str ? str.getBytes(StandardCharsets.UTF_8) : (byte[]) raw;
                    if (lo == null || Arrays.compareUnsigned(v, lo) < 0) {
                        lo = v;
                    }
                    if (hi == null || Arrays.compareUnsigned(v, hi) > 0) {
                        hi = v;
                    }
                }
                if (lo != null) {
                    min = kind == Kind.UTF8 ? utf8LowerBound(lo) : Arrays.copyOf(lo, Math.min(lo.length, BOUNDED_MAX_BYTES));
                    max = kind == Kind.UTF8 ? utf8UpperBound(hi) : binaryUpperBound(hi);
                    unknown = max == null;
                }
            }
            case NULL_COUNT_ONLY -> {
                if (validity != null) {
                    for (int i = 0; i < len; i++) {
                        if (!validity[off + i]) {
                            nulls++;
                        }
                    }
                }
            }
        }
        mins.add(min);
        maxs.add(max);
        maxUnknown.add(unknown);
        nullCounts.add(nulls);
        nanCounts.add(nans);
    }

    private static int compare(long a, long b, boolean unsigned) {
        return unsigned ? Long.compareUnsigned(a, b) : Long.compare(a, b);
    }

    // ── Rust's scalar truncation (vortex-array/src/scalar/truncation.rs) ───

    /// `BufferString::lower_bound`: the value cut to at most [#BOUNDED_MAX_BYTES] bytes at the last
    /// UTF-8 character boundary — a prefix, so never greater than the value.
    static byte[] utf8LowerBound(byte[] value) {
        if (value.length <= BOUNDED_MAX_BYTES) {
            return value;
        }
        return Arrays.copyOf(value, charBoundary(value, BOUNDED_MAX_BYTES));
    }

    /// `BufferString::upper_bound`: the value cut at a character boundary with its last character
    /// replaced by the next code point of the same UTF-8 width; `null` when there is none (Rust's
    /// `unknown` bound).
    static byte[] utf8UpperBound(byte[] value) {
        if (value.length <= BOUNDED_MAX_BYTES) {
            return value;
        }
        byte[] cut = Arrays.copyOf(value, charBoundary(value, BOUNDED_MAX_BYTES));
        if (cut.length == 0) {
            return null;
        }
        String s = new String(cut, StandardCharsets.UTF_8);
        int lastStart = s.offsetByCodePoints(s.length(), -1);
        int last = s.codePointAt(lastStart);
        int next = last + 1;
        if (next > Character.MAX_CODE_POINT || (next >= Character.MIN_SURROGATE && next <= Character.MAX_SURROGATE)
                || utf8Width(next) != utf8Width(last)) {
            return null;
        }
        return (s.substring(0, lastStart) + Character.toString(next)).getBytes(StandardCharsets.UTF_8);
    }

    /// `ByteBuffer::upper_bound`: the first [#BOUNDED_MAX_BYTES] bytes incremented as a big-endian
    /// number; `null` when every byte overflows.
    static byte[] binaryUpperBound(byte[] value) {
        if (value.length <= BOUNDED_MAX_BYTES) {
            return value;
        }
        byte[] cut = Arrays.copyOf(value, BOUNDED_MAX_BYTES);
        for (int i = cut.length - 1; i >= 0; i--) {
            cut[i]++;
            if (cut[i] != 0) {
                return cut;
            }
        }
        return null;
    }

    /// The largest char boundary at or below `max`, searching `max - 3 ..= max` as Rust does.
    private static int charBoundary(byte[] value, int max) {
        for (int p = max; p >= Math.max(0, max - 3); p--) {
            if (p >= value.length || (value[p] & 0xC0) != 0x80) {
                return p;
            }
        }
        throw new IllegalStateException("no UTF-8 character boundary near byte " + max);
    }

    private static int utf8Width(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }

    // ── carrier helpers ────────────────────────────────────────────────────

    /// Unwraps a pre-transformed carrier (e.g. the Parquet importer's [ComparableValues]
    /// `DateTimePartsData`) to the original values its stats are taken over.
    private static Object comparable(Object batch) {
        if (batch instanceof NullableData(ComparableValues cv, boolean[] validity)) {
            return new NullableData(cv.values(), validity);
        }
        return batch instanceof ComparableValues cv ? cv.values() : batch;
    }

    private static Object validityOnly(Object batch, int rows) {
        boolean[] placeholder = new boolean[rows];
        return batch instanceof NullableData nd ? new NullableData(placeholder, nd.validity()) : placeholder;
    }

    private static int length(Object data) {
        Object values = data instanceof NullableData nd ? nd.values() : data;
        return java.lang.reflect.Array.getLength(values);
    }

    private static Object slice(Object values, boolean[] validity, int off, int len) {
        Object out = java.lang.reflect.Array.newInstance(values.getClass().getComponentType(), len);
        System.arraycopy(values, off, out, 0, len);
        return validity == null ? out : new NullableData(out, Arrays.copyOfRange(validity, off, off + len));
    }

    private static Object concat(Object a, Object b) {
        Object av = a instanceof NullableData nd ? nd.values() : a;
        Object bv = b instanceof NullableData nd ? nd.values() : b;
        int al = java.lang.reflect.Array.getLength(av);
        int bl = java.lang.reflect.Array.getLength(bv);
        Object out = java.lang.reflect.Array.newInstance(av.getClass().getComponentType(), al + bl);
        System.arraycopy(av, 0, out, 0, al);
        System.arraycopy(bv, 0, out, al, bl);
        if (!(a instanceof NullableData) && !(b instanceof NullableData)) {
            return out;
        }
        boolean[] validity = new boolean[al + bl];
        copyValidity(a, validity, 0, al);
        copyValidity(b, validity, al, bl);
        return new NullableData(out, validity);
    }

    private static void copyValidity(Object data, boolean[] into, int at, int len) {
        if (data instanceof NullableData nd) {
            System.arraycopy(nd.validity(), 0, into, at, len);
        } else {
            Arrays.fill(into, at, at + len, true);
        }
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }
}
