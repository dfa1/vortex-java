package io.github.dfa1.vortex.inspect;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanFilter;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexHandle;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.compute.Compute;
import io.github.dfa1.vortex.reader.compute.FilteredAggregate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongToDoubleFunction;

/// What one full pass over a column's values tells that its recorded statistics cannot: its total,
/// how its values are spread, and for a string column how long they are. Unlike [ColumnProfile] it
/// decodes the column, so it is computed on demand, one column at a time.
///
/// The spread is a value count when the column has few distinct values (a status, a country, a
/// flag), else a histogram for a numeric column, else the most frequent values. A column with more
/// than [#MAX_DISTINCT] distinct values is not counted at all: past that, a count says nothing a
/// histogram does not, and holding it costs memory per value.
///
/// @param rows     rows scanned
/// @param nulls    null values among them
/// @param sum      the total of a numeric column (`Long` for integers, `Double` for floats), or `null`
///                 for a non-numeric column or an integer total past the `long` range
/// @param distinct distinct non-null values, or `null` when there are more than [#MAX_DISTINCT]
/// @param bars     the spread: value counts or histogram bins, in display order
/// @param histogram `true` when `bars` are numeric bins rather than individual values
/// @param lengths  byte lengths of a string column's non-null values, or `null` for other columns
public record ColumnDistribution(
        long rows,
        long nulls,
        Number sum,
        Long distinct,
        List<Bar> bars,
        boolean histogram,
        Lengths lengths) {

    /// Distinct values counted before a column counts as high-cardinality.
    public static final int MAX_DISTINCT = 10_000;

    /// A column with at most this many distinct values shows every one of them; above it a numeric
    /// column shows a histogram and any other the [#TOP_VALUES] most frequent.
    static final int MAX_CATEGORIES = 30;

    /// Most frequent values shown for a column with too many distinct values to list.
    static final int TOP_VALUES = 10;

    /// Bins in a histogram.
    static final int BINS = 30;

    /// Matches every row: the aggregate kernels take a filter, and an empty `AND` holds for all.
    private static final ScanFilter EVERY_ROW = new ScanFilter.And(List.of());

    /// One bar of the spread.
    ///
    /// @param value the value counted, or a histogram bin's lower bound (a `Double`)
    /// @param upper a histogram bin's upper bound (a `Double`), or `null` for a value count
    /// @param count rows holding the value, or falling in the bin
    public record Bar(Object value, Object upper, long count) {
    }

    /// Byte lengths of a string column's values.
    ///
    /// @param min   shortest value
    /// @param max   longest value
    /// @param total bytes over all values, for the average
    public record Lengths(long min, long max, long total) {
    }

    /// The mean of the non-null values.
    ///
    /// @return `sum / (rows - nulls)`, or `null` without a sum or with no non-null value
    public Double mean() {
        long present = rows - nulls;
        return sum == null || present <= 0 ? null : sum.doubleValue() / present;
    }

    /// Scans `profile`'s column once. A histogram's bins span the profile's min and max; a numeric
    /// column whose file records no exact min/max (an older write of a timestamp, say) is scanned
    /// once more first, to find them.
    ///
    /// @param handle  an open file; must be used from the thread that opened it
    /// @param profile the column's recorded statistics, from [ColumnProfile#of(VortexHandle)]
    /// @return the column's distribution
    public static ColumnDistribution of(VortexHandle handle, ColumnProfile profile) {
        ColumnName name = ColumnName.of(profile.name());
        ColumnProfile bounded = profile;
        if (isNumeric(profile.type()) && !(profile.min() instanceof Number && profile.max() instanceof Number)) {
            bounded = withScannedRange(handle, profile, name);
        }
        Accumulator acc = new Accumulator(bounded);
        try (ScanIterator scan = handle.scan(ScanOptions.columns(name))) {
            while (scan.hasNext()) {
                try (Chunk chunk = scan.next()) {
                    acc.add(chunk, name);
                }
            }
        }
        return acc.result();
    }

    private static boolean isNumeric(DType type) {
        return type instanceof DType.Primitive
                || type instanceof DType.Extension ext && ext.storageDType() instanceof DType.Primitive;
    }

    /// The profile with its min/max replaced by the column's scanned extremes, or unchanged when
    /// the column holds no non-null, non-`NaN` value.
    private static ColumnProfile withScannedRange(VortexHandle handle, ColumnProfile profile, ColumnName name) {
        double[] range = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        try (ScanIterator scan = handle.scan(ScanOptions.columns(name))) {
            while (scan.hasNext()) {
                try (Chunk chunk = scan.next()) {
                    MaskedArray.Unwrapped split = MaskedArray.unwrap(chunk.column(name));
                    LongToDoubleFunction value = Accumulator.numericReader(split.inner());
                    BoolArray validity = split.validity();
                    for (long i = 0; value != null && i < chunk.rowCount(); i++) {
                        double v = value.applyAsDouble(i);
                        // NaN would poison min/max; the histogram leaves it out too.
                        if ((validity == null || validity.getBoolean(i)) && !Double.isNaN(v)) {
                            range[0] = Math.min(range[0], v);
                            range[1] = Math.max(range[1], v);
                        }
                    }
                }
            }
        }
        if (!(range[0] <= range[1])) {
            return profile;
        }
        return new ColumnProfile(profile.name(), profile.type(), profile.rows(), profile.nullCount(),
                profile.nanCount(), range[0], range[1], profile.sum(), profile.zones());
    }

    /// Picks the bars to show from what the scan gathered.
    ///
    /// @param counts rows per distinct non-null value, or `null` when there were too many to count
    /// @param bins   rows per histogram bin, or `null` when the column has no histogram
    /// @param lo     the first bin's lower bound
    /// @param hi     the last bin's upper bound
    /// @return the bars, and whether they are histogram bins
    static Map.Entry<List<Bar>, Boolean> bars(Map<Object, Long> counts, long[] bins, double lo, double hi) {
        if (counts != null && counts.size() <= MAX_CATEGORIES) {
            List<Bar> bars = new ArrayList<>(counts.size());
            counts.forEach((value, count) -> bars.add(new Bar(value, null, count)));
            // Numbers, dates and flags read in their own order; names read most frequent first.
            if (counts.keySet().stream().allMatch(value -> value instanceof Number || value instanceof Boolean)) {
                bars.sort(Comparator.comparing(Bar::value, ColumnDistribution::compareKeys));
            } else {
                bars.sort(Comparator.comparingLong(Bar::count).reversed());
            }
            return Map.entry(List.copyOf(bars), false);
        }
        if (bins != null) {
            List<Bar> bars = new ArrayList<>(bins.length);
            double width = (hi - lo) / bins.length;
            for (int i = 0; i < bins.length; i++) {
                bars.add(new Bar(lo + i * width, i == bins.length - 1 ? hi : lo + (i + 1) * width, bins[i]));
            }
            return Map.entry(List.copyOf(bars), true);
        }
        if (counts != null) {
            List<Bar> top = counts.entrySet().stream()
                    .sorted(Map.Entry.<Object, Long>comparingByValue().reversed())
                    .limit(TOP_VALUES)
                    .map(entry -> new Bar(entry.getKey(), null, entry.getValue()))
                    .toList();
            return Map.entry(top, false);
        }
        return Map.entry(List.of(), false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareKeys(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        return ((Comparable) a).compareTo(b);
    }

    /// The running state of one scan.
    private static final class Accumulator {

        private final DType type;
        private final boolean summable;
        private Map<Object, Long> counts = new HashMap<>();
        private final long[] bins;
        private final double lo;
        private final double hi;
        private final double scale;
        private long rows;
        private long nulls;
        private Number sum;
        private boolean summed;
        private long[] lengths;

        Accumulator(ColumnProfile profile) {
            this.type = profile.type();
            this.summable = type instanceof DType.Primitive;
            if (isNumeric(type) && profile.min() instanceof Number min && profile.max() instanceof Number max) {
                this.bins = new long[BINS];
                this.lo = min.doubleValue();
                this.hi = max.doubleValue();
                // A constant column puts everything in the first bin rather than dividing by zero.
                this.scale = hi > lo ? BINS / (hi - lo) : 0;
            } else {
                this.bins = null;
                this.lo = 0;
                this.hi = 0;
                this.scale = 0;
            }
        }

        void add(Chunk chunk, ColumnName name) {
            long n = chunk.rowCount();
            rows += n;
            Array column = chunk.column(name);
            if (column instanceof NullArray) {
                nulls += n;
                return;
            }
            MaskedArray.Unwrapped split = MaskedArray.unwrap(column);
            BoolArray validity = split.validity();
            nulls += validity == null ? 0 : n - trueCount(validity, n);
            if (summable) {
                Number part = Compute.filteredAggregate(chunk, EVERY_ROW, name).sum();
                sum = summed ? addSum(sum, part) : part;
                summed = true;
            }
            if (counts != null) {
                count(chunk, name, split.inner(), validity);
            }
            if (bins != null) {
                LongToDoubleFunction value = numericReader(split.inner());
                if (value != null) {
                    histogram(value, validity, n);
                }
            }
            if (split.inner() instanceof VarBinArray strings) {
                lengths(strings, validity, n);
            }
        }

        ColumnDistribution result() {
            Map.Entry<List<Bar>, Boolean> bars = bars(counts, bins, lo, hi);
            Lengths stringLengths = null;
            if (lengths != null && lengths[0] != Long.MAX_VALUE) {
                stringLengths = new Lengths(lengths[0], lengths[1], lengths[2]);
            }
            return new ColumnDistribution(rows, nulls, sum, counts == null ? null : (long) counts.size(),
                    bars.getKey(), bars.getValue(), stringLengths);
        }

        /// Counts through the grouping kernel, which reads dictionary codes rather than decoding
        /// every row; a plain (non-dictionary) string chunk is counted here instead. A float column
        /// is not counted: its values are a histogram's job.
        private void count(Chunk chunk, ColumnName name, Array data, BoolArray validity) {
            Optional<Map<Object, FilteredAggregate>> groups = Compute.groupedAggregate(chunk, name, null, null);
            if (groups.isPresent()) {
                groups.get().forEach((value, group) -> {
                    if (value != null) {
                        counts.merge(value, group.selectedRows(), Long::sum);
                    }
                });
            } else if (data instanceof VarBinArray strings) {
                for (long i = 0; i < strings.length() && counts.size() <= MAX_DISTINCT; i++) {
                    if (validity == null || validity.getBoolean(i)) {
                        counts.merge(strings.getString(i), 1L, Long::sum);
                    }
                }
            } else {
                counts = null;
                return;
            }
            if (counts.size() > MAX_DISTINCT) {
                counts = null;
            }
        }

        /// Branch-split on validity so the common no-null chunk runs a uniform loop.
        private void histogram(LongToDoubleFunction value, BoolArray validity, long n) {
            if (validity == null) {
                for (long i = 0; i < n; i++) {
                    bin(value.applyAsDouble(i));
                }
            } else {
                for (long i = 0; i < n; i++) {
                    if (validity.getBoolean(i)) {
                        bin(value.applyAsDouble(i));
                    }
                }
            }
        }

        private void bin(double v) {
            // NaN sits outside every bin; min/max skip it too.
            if (!Double.isNaN(v)) {
                bins[Math.clamp((long) ((v - lo) * scale), 0, BINS - 1)]++;
            }
        }

        private void lengths(VarBinArray strings, BoolArray validity, long n) {
            if (lengths == null) {
                lengths = new long[] {Long.MAX_VALUE, 0, 0};
            }
            for (long i = 0; i < n; i++) {
                if (validity == null || validity.getBoolean(i)) {
                    int length = strings.getByteLength(i);
                    lengths[0] = Math.min(lengths[0], length);
                    lengths[1] = Math.max(lengths[1], length);
                    lengths[2] += length;
                }
            }
        }

        private static long trueCount(BoolArray validity, long n) {
            long valid = 0;
            for (long i = 0; i < n; i++) {
                if (validity.getBoolean(i)) {
                    valid++;
                }
            }
            return valid;
        }

        /// Resolves the typed accessor once per chunk, so the histogram loop calls one monomorphic
        /// function rather than switching on the array type per row.
        private static LongToDoubleFunction numericReader(Array data) {
            boolean unsigned = data.dtype().isUnsigned();
            return switch (data) {
                case LongArray a when unsigned -> i -> unsignedToDouble(a.getLong(i));
                case LongArray a -> i -> a.getLong(i);
                case IntArray a when unsigned -> i -> Integer.toUnsignedLong(a.getInt(i));
                case IntArray a -> i -> a.getInt(i);
                case ShortArray a when unsigned -> i -> Short.toUnsignedInt(a.getShort(i));
                case ShortArray a -> i -> a.getShort(i);
                case ByteArray a when unsigned -> i -> Byte.toUnsignedInt(a.getByte(i));
                case ByteArray a -> i -> a.getByte(i);
                case DoubleArray a -> a::getDouble;
                case FloatArray a -> a::getFloat;
                default -> null;
            };
        }

        private static double unsignedToDouble(long v) {
            return v >= 0 ? v : (v >>> 1) * 2.0 + (v & 1);
        }

        private static Number addSum(Number total, Number part) {
            if (total == null || part == null) {
                return null;
            }
            if (total instanceof Long a && part instanceof Long b) {
                try {
                    return Math.addExact(a, b);
                } catch (ArithmeticException _) {
                    return null;
                }
            }
            return total.doubleValue() + part.doubleValue();
        }
    }
}
