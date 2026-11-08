package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.compute.Utf8Order;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.compute.LongIntMap;
import io.github.dfa1.vortex.writer.encode.MinMax;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.PrimitiveEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.VarBinEncodingEncoder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Cardinality-bounded buffering state for one global-dictionary candidate column (ADR 0021). Instead of
/// retaining raw values from a column's first chunk until `close()`, this holds a deduplicated
/// value-to-code map (first-seen order, at most [#MAX_ENTRIES] entries per dictionary) and one cheap
/// `short[]` code array per ingested chunk. Per-chunk stats are captured at ingest time from the
/// raw chunk, before it is discarded.
///
/// Codes are assigned as Rust's dict builder assigns them (`vortex-array` `builders/dict`): in
/// first-seen order, null included -- the first null slot takes the next code for an entry that
/// the values pool marks invalid, and every later null points at it. The buffered codes are
/// therefore the wire codes.
final class DictColumnState {

    /// The codes ptype of every global dict: Rust's `DictLayoutConstraints` derive it from the
    /// configured maximum dictionary length, not the actual one -- U8 only when `max_len <= 255`,
    /// and the default `max_len` is `u16::MAX` -- so a default Rust global dict always has U16
    /// codes. The width matters beyond the bytes on disk: the cascade scores candidates against
    /// the codes' raw width, and on U8 codes Sparse's estimate beat bit-packing where on Rust's
    /// U16 codes it does not (taxi Airport_fee).
    static final PType CODES_PTYPE = PType.U16;

    /// Rust's `DictLayoutConstraints` defaults: at most `u16::MAX` entries and 1 MiB of values (of
    /// views plus out-of-line bytes for text). A column that reaches either starts a new dictionary,
    /// so a column's cardinality is not a reason to give up the dict layout.
    static final int MAX_ENTRIES = 65_535;
    static final long MAX_DICT_BYTES = 1L << 20;
    private static final int VIEW_BYTES = 16;
    private static final int MAX_INLINED_BYTES = 12;

    private static final int INDEX_MIN_CAPACITY = 64;
    // The null entry's key in valueToCode: identity-compared, so no real value can collide with it.
    private static final Object NULL_KEY = new Object();

    private final DType dtype;
    // Utf8 or Binary: keyed by value (String, or a ByteBuffer wrapping the bytes, whose
    // equals/hashCode compare content), coded in first-seen order like the primitive path.
    private final boolean varBin;
    private final boolean binary;
    private final PType ptype;
    private final boolean nullable;
    // Only the legacy vortex.stats zone map records a sum: under vortex.zoned it is never read.
    private final boolean chunkSums;
    // First-seen value -> code map (keys are boxed primitives, String, or ByteBuffer — see varBinKey).
    private final Map<Object, Integer> valueToCode = new LinkedHashMap<>();
    // Hot-path side index for the primitive path: raw value bits -> code + 1 (0 == empty), open
    // addressing with a power-of-two capacity so probing masks instead of taking a modulo (AGENTS.md
    // hot-loop rule). valueToCode stays the authoritative store — it carries first-seen order, the
    // VarBin keys, and everything the demotion and flush paths read — but probing it needs a boxed key,
    // and one Long per row of every candidate column profiled as the writer's hottest single frame.
    private LongIntMap bitsIndex = new LongIntMap(INDEX_MIN_CAPACITY);
    // The null entry's code, or -1 until the first null slot; mirrors valueToCode.get(NULL_KEY)
    // so the hot loops never probe the map for it.
    private int nullCode = -1;
    // One code array per ingested chunk (null slots hold nullCode).
    private final List<short[]> chunkCodes = new ArrayList<>();
    private final List<boolean[]> chunkValidity = new ArrayList<>();
    // Per code, the stamp of the last chunk whose min/max already compared it; avoids clearing a flag array per chunk.
    private int[] seenInChunk;
    private int stamp;
    private final List<Long> chunkRowCounts = new ArrayList<>();
    private final List<Long> chunkNullCounts = new ArrayList<>();
    private final List<ProtoScalarValue> chunkStatsMin = new ArrayList<>();
    private final List<ProtoScalarValue> chunkStatsMax = new ArrayList<>();
    private final List<ProtoScalarValue> chunkStatsSum = new ArrayList<>();
    private long codeArrayBytes;
    // Rust's `dict_bytes()`: a 16-byte view per entry plus the bytes of each value too long to inline.
    private long dictBytes;

    DictColumnState(DType dtype, boolean chunkSums) {
        this.dtype = dtype;
        this.chunkSums = chunkSums;
        this.binary = dtype instanceof DType.Binary;
        this.varBin = binary || dtype instanceof DType.Utf8;
        this.ptype = dtype instanceof DType.Primitive p ? p.ptype() : null;
        this.nullable = dtype.nullable();
    }

    DType dtype() {
        return dtype;
    }

    boolean varBin() {
        return varBin;
    }

    PType ptype() {
        return ptype;
    }

    boolean nullable() {
        return nullable;
    }

    /// Number of dictionary entries, the null entry included.
    int cardinality() {
        return valueToCode.size();
    }

    /// The null entry's code, or `-1` when no null has been seen.
    int nullCode() {
        return nullCode;
    }

    /// Approximate retained heap footprint: the buffered code arrays (2 B/row) plus the small
    /// cardinality-capped dedup map. The map footprint is bounded by the cap, so the code arrays
    /// dominate — this is the quantity the aggregate byte-budget safety net now tracks.
    long retainedBytes() {
        // ~40 B per map entry (boxed key + Integer code + LinkedHashMap.Entry) plus the codes.
        return codeArrayBytes + 40L * valueToCode.size();
    }

    /// Number of chunks buffered so far.
    int chunkCount() {
        return chunkCodes.size();
    }

    short[] chunkCodes(int c) {
        return chunkCodes.get(c);
    }

    List<Long> chunkRowCounts() {
        return chunkRowCounts;
    }

    List<Long> chunkNullCounts() {
        return chunkNullCounts;
    }

    List<ProtoScalarValue> chunkStatsMin() {
        return chunkStatsMin;
    }

    List<ProtoScalarValue> chunkStatsMax() {
        return chunkStatsMax;
    }

    List<ProtoScalarValue> chunkStatsSum() {
        return chunkStatsSum;
    }

    /// The dictionary entries in code order: a typed primitive array, `String[]` or `byte[][]`. The
    /// null entry's slot holds a zero (or `null`) placeholder, for the caller to mask off.
    Object uniques() {
        Object[] inverse = buildInverseMap();
        int n = inverse.length;
        if (varBin) {
            Object[] out = binary ? new byte[n][] : new String[n];
            for (int i = 0; i < n; i++) {
                if (i != nullCode) {
                    out[i] = binary ? ((ByteBuffer) inverse[i]).array() : inverse[i];
                }
            }
            return out;
        }
        // every code but the null entry's names a value; its slot keeps the carrier's zero
        short[] codes = new short[n];
        boolean[] valid = new boolean[n];
        for (int i = 0; i < n; i++) {
            codes[i] = (short) i;
            valid[i] = i != nullCode;
        }
        return reconstructPrimitiveValues(ptype, codes, valid, inverse);
    }

    /// Ingests the leading rows of a primitive chunk that fit this dictionary under Rust's constraints
    /// (`DictLayoutConstraints`: [#MAX_ENTRIES] entries and 1 MiB of values), as Rust's primitive dict
    /// builder does: the first entry is always admitted, and the row that needs an entry that no
    /// longer fits ends the prefix. The caller starts a new dictionary with the rest. Dedups each
    /// valid value into the shared value-to-code map, appends a per-chunk `short[]` code array, and
    /// captures the prefix's row/null counts and min/max/sum stats before the raw array is discarded.
    /// Null slots buffer the null entry's code, assigned at the first one.
    ///
    /// @param data the chunk data (a primitive array or a [NullableData] wrapper)
    /// @return how many leading rows were ingested, `0` when the first row already needs a new
    ///         dictionary
    int ingestDictChunk(Object data) {
        boolean nullableData = data instanceof NullableData;
        Object values = nullableData ? ((NullableData) data).values() : data;
        boolean[] validity = nullableData ? ((NullableData) data).validity() : null;
        int len = primitiveArrayLen(values, ptype);
        int cap = (int) Math.min(MAX_ENTRIES, MAX_DICT_BYTES / ptype.byteSize());

        short[] codes = new short[len];
        // Probe the unboxed bits index and box only when inserting a value the dictionary has not
        // seen before. The boxed key itself still comes from the source array, so I32 vs I64 and
        // NaN/-0.0 keying stay exactly what a Double/Long-keyed map gave.
        long[] bits = rawBits(values, ptype, len);
        int consumed = len;
        for (int i = 0; i < len; i++) {
            if (validity != null && !validity[i]) {
                if (nullCode < 0 && !addNullEntry(cap)) {
                    consumed = i;
                    break;
                }
                codes[i] = (short) nullCode;
                continue;
            }
            int code = bitsIndex.get(bits[i]);
            if (code < 0) {
                if (!valueToCode.isEmpty() && valueToCode.size() >= cap) {
                    consumed = i;
                    break;
                }
                code = valueToCode.size();
                valueToCode.put(readPrimitiveElement(values, ptype, i), code);
                bitsIndex.put(bits[i], code);
            }
            codes[i] = (short) code;
        }
        if (consumed == 0) {
            return 0;
        }
        if (consumed < len) {
            codes = Arrays.copyOf(codes, consumed);
            validity = validity != null ? Arrays.copyOf(validity, consumed) : null;
            values = prefix(values, consumed);
        }
        MinMax mm = PrimitiveEncodingEncoder.minMaxStats(ptype, values);
        ProtoScalarValue sum = null;
        if (chunkSums) {
            sum = PrimitiveEncodingEncoder.sumStat(ptype, values);
        }
        record(codes, validity, consumed, mm != null ? mm.min() : null, mm != null ? mm.max() : null, sum);
        return consumed;
    }

    private static Object prefix(Object values, int n) {
        return switch (values) {
            case byte[] a -> Arrays.copyOf(a, n);
            case short[] a -> Arrays.copyOf(a, n);
            case int[] a -> Arrays.copyOf(a, n);
            case long[] a -> Arrays.copyOf(a, n);
            case float[] a -> Arrays.copyOf(a, n);
            case double[] a -> Arrays.copyOf(a, n);
            default -> throw new IllegalStateException("not a global dict carrier: " + values.getClass());
        };
    }

    /// Ingests the leading rows of a text chunk that fit this dictionary under Rust's constraints
    /// (`DictLayoutConstraints`: [#MAX_ENTRIES] entries, [#MAX_DICT_BYTES] bytes), as
    /// Rust's bytes dict builder does: the first entry is always admitted, and the row that needs an
    /// entry that no longer fits ends the prefix. The caller starts a new dictionary with the rest.
    ///
    /// @param data the chunk data (`String[]`, `byte[][]`, or a [NullableData] wrapper)
    /// @return how many leading rows were ingested, `0` when the first row already needs a new
    ///         dictionary
    int ingestVarBinChunk(Object data) {
        boolean nullableData = data instanceof NullableData;
        Object[] strings = (Object[]) (nullableData ? ((NullableData) data).values() : data);
        boolean[] validity = nullableData ? ((NullableData) data).validity() : null;
        int len = strings.length;
        short[] codes = new short[len];
        boolean statsStrings = !binary;
        String minString = null;
        String maxString = null;
        int chunkStamp = ++stamp;
        if (statsStrings && seenInChunk == null) {
            seenInChunk = new int[16];
        }
        int consumed = len;
        for (int i = 0; i < len; i++) {
            // Nullable Utf8/Binary keeps a real null at invalid positions (ChunkImpl.adaptUtf8,
            // adaptBinary); either way the slot is null and points at the null entry.
            if ((validity != null && !validity[i]) || strings[i] == null) {
                if (nullCode < 0 && !addVarBinNullEntry()) {
                    consumed = i;
                    break;
                }
                codes[i] = (short) nullCode;
                continue;
            }
            Object v = dictKey(strings[i]);
            Integer code = valueToCode.get(v);
            if (code == null) {
                long added = entryBytes(v);
                boolean first = valueToCode.isEmpty();
                if (!first && (valueToCode.size() >= MAX_ENTRIES || dictBytes + added > MAX_DICT_BYTES)) {
                    consumed = i;
                    break;
                }
                code = valueToCode.size();
                valueToCode.put(v, code);
                dictBytes += added;
            }
            codes[i] = code.shortValue();
            // Min/max depend on the distinct values only, so compare each code once per chunk
            // instead of twice per row (UTF-8 order compare was 4% of a cascading write).
            if (statsStrings) {
                if (code >= seenInChunk.length) {
                    // Grown with the dictionary, not sized to the cap: a low-cardinality column
                    // must not hold 128 KiB the retained-bytes budget cannot see.
                    seenInChunk = Arrays.copyOf(seenInChunk, Math.max(code + 1, seenInChunk.length * 2));
                }
                if (seenInChunk[code] != chunkStamp) {
                    seenInChunk[code] = chunkStamp;
                    String s = (String) v;
                    if (minString == null || Utf8Order.compare(s, minString) < 0) {
                        minString = s;
                    }
                    if (maxString == null || Utf8Order.compare(s, maxString) > 0) {
                        maxString = s;
                    }
                }
            }
        }
        if (consumed == 0) {
            return 0;
        }
        if (consumed < len) {
            codes = Arrays.copyOf(codes, consumed);
            validity = validity != null ? Arrays.copyOf(validity, consumed) : null;
        }
        // No min/max for Binary, matching the per-chunk VarBin path (VarBinEncodingEncoder).
        MinMax mm = binary ? null : VarBinEncodingEncoder.encodeMinMax(minString, maxString);
        record(codes, validity, consumed, mm != null ? mm.min() : null, mm != null ? mm.max() : null, null);
        return consumed;
    }

    // The bytes a new entry adds to the dictionary: its view, and the value itself when too long to inline.
    private long entryBytes(Object key) {
        int length = key instanceof String s ? s.getBytes(StandardCharsets.UTF_8).length : ((ByteBuffer) key).remaining();
        return length > MAX_INLINED_BYTES ? VIEW_BYTES + length : VIEW_BYTES;
    }

    // Gives the null entry the next code under the text constraints (Rust's `encode_null`); false
    // when it no longer fits.
    private boolean addVarBinNullEntry() {
        if (!valueToCode.isEmpty()
                && (valueToCode.size() >= MAX_ENTRIES || dictBytes + VIEW_BYTES > MAX_DICT_BYTES)) {
            return false;
        }
        nullCode = valueToCode.size();
        valueToCode.put(NULL_KEY, nullCode);
        dictBytes += VIEW_BYTES;
        return true;
    }

    private void record(short[] codes, boolean[] validity, int len, ProtoScalarValue min, ProtoScalarValue max,
                        ProtoScalarValue sum) {
        chunkCodes.add(codes);
        chunkValidity.add(validity);
        chunkRowCounts.add((long) len);
        chunkNullCounts.add(validity != null ? countNulls(validity) : 0L);
        chunkStatsMin.add(min);
        chunkStatsMax.add(max);
        chunkStatsSum.add(sum);
        codeArrayBytes += 2L * len;
    }

    private static long countNulls(boolean[] validity) {
        long nulls = 0;
        for (boolean valid : validity) {
            if (!valid) {
                nulls++;
            }
        }
        return nulls;
    }

    // Gives the null entry the next code, as Rust's `encode_null`; false when it no longer fits.
    private boolean addNullEntry(int cap) {
        if (!valueToCode.isEmpty() && valueToCode.size() >= cap) {
            return false;
        }
        nullCode = valueToCode.size();
        valueToCode.put(NULL_KEY, nullCode);
        return true;
    }

    /// The chunk's values as raw bit patterns, keyed so that two rows share a pattern exactly when
    /// their boxed values are `equals` (hence `doubleToLongBits` / `floatToIntBits`, which fold every
    /// NaN together and keep `-0.0` apart from `0.0`, just like `Double.equals`). I64/U64 arrays are
    /// used in place; only the narrower carriers pay for a widened copy. F16 keys on its raw bits.
    private static long[] rawBits(Object values, PType ptype, int len) {
        if (ptype == PType.I64 || ptype == PType.U64) {
            return (long[]) values;
        }
        long[] out = new long[len];
        switch (ptype) {
            case I8, U8 -> {
                byte[] src = (byte[]) values;
                for (int i = 0; i < len; i++) {
                    out[i] = src[i];
                }
            }
            case I16, U16, F16 -> {
                short[] src = (short[]) values;
                for (int i = 0; i < len; i++) {
                    out[i] = src[i];
                }
            }
            case I32, U32 -> {
                int[] src = (int[]) values;
                for (int i = 0; i < len; i++) {
                    out[i] = src[i];
                }
            }
            case F32 -> {
                float[] src = (float[]) values;
                for (int i = 0; i < len; i++) {
                    out[i] = Float.floatToIntBits(src[i]);
                }
            }
            case F64 -> {
                double[] src = (double[]) values;
                for (int i = 0; i < len; i++) {
                    out[i] = Double.doubleToLongBits(src[i]);
                }
            }
            default -> throw new IllegalStateException("unreachable: " + ptype);
        }
        return out;
    }

    /// Inverse of this column's first-seen value-to-code map: `inverse[code]` is the value with that
    /// code. Used by demotion to reconstruct raw chunks from their buffered code arrays.
    Object[] buildInverseMap() {
        Object[] inverse = new Object[valueToCode.size()];
        for (Map.Entry<Object, Integer> e : valueToCode.entrySet()) {
            inverse[e.getValue()] = e.getKey();
        }
        return inverse;
    }

    /// Reconstructs demoted chunk `c`'s raw array (a typed primitive array, `String[]` or `byte[][]`, wrapped in
    /// [NullableData] when the chunk carried validity) from its buffered `short[]` codes and the
    /// inverse code-to-value map. Null slots restore a zero/`null` placeholder — exactly what the
    /// per-chunk encoders expect from [NullableData].
    Object reconstructChunk(Object[] inverse, int c) {
        short[] codes = chunkCodes.get(c);
        boolean[] validity = chunkValidity.get(c);
        Object values = varBin
                ? reconstructVarBinValues(binary, codes, validity, inverse)
                : reconstructPrimitiveValues(ptype, codes, validity, inverse);
        return validity != null ? new NullableData(values, validity) : values;
    }

    private static Object reconstructVarBinValues(boolean binary, short[] codes, boolean[] validity, Object[] inverse) {
        int len = codes.length;
        Object[] arr = binary ? new byte[len][] : new String[len];
        for (int i = 0; i < len; i++) {
            Object key = inverse[codes[i] & 0xFFFF];
            // A null string at a valid row also points at the null entry; it stays null.
            if ((validity == null || validity[i]) && key != NULL_KEY) {
                arr[i] = binary ? ((ByteBuffer) key).array() : key;
            }
        }
        return arr;
    }

    private static Object reconstructPrimitiveValues(PType ptype, short[] codes, boolean[] validity, Object[] inverse) {
        int len = codes.length;
        return switch (ptype) {
            case I8, U8 -> {
                byte[] arr = new byte[len];
                for (int i = 0; i < len; i++) {
                    if (validity == null || validity[i]) {
                        arr[i] = (Byte) inverse[codes[i] & 0xFFFF];
                    }
                }
                yield arr;
            }
            case I16, U16, F16 -> {
                short[] arr = new short[len];
                for (int i = 0; i < len; i++) {
                    if (validity == null || validity[i]) {
                        arr[i] = (Short) inverse[codes[i] & 0xFFFF];
                    }
                }
                yield arr;
            }
            case I32, U32 -> {
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) {
                    if (validity == null || validity[i]) {
                        arr[i] = (Integer) inverse[codes[i] & 0xFFFF];
                    }
                }
                yield arr;
            }
            case I64, U64 -> {
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) {
                    if (validity == null || validity[i]) {
                        arr[i] = (Long) inverse[codes[i] & 0xFFFF];
                    }
                }
                yield arr;
            }
            case F32 -> {
                float[] arr = new float[len];
                for (int i = 0; i < len; i++) {
                    if (validity == null || validity[i]) {
                        arr[i] = (Float) inverse[codes[i] & 0xFFFF];
                    }
                }
                yield arr;
            }
            case F64 -> {
                double[] arr = new double[len];
                for (int i = 0; i < len; i++) {
                    if (validity == null || validity[i]) {
                        arr[i] = (Double) inverse[codes[i] & 0xFFFF];
                    }
                }
                yield arr;
            }
        };
    }

    static boolean isVarBinDictCandidate(Object[] data) {
        return isVarBinDictCandidate(data, null);
    }

    /// Like [#isVarBinDictCandidate(Object[])] but ignores null (invalid) rows when counting distinct
    /// values, so a nullable low-cardinality column still qualifies for the shared global dictionary.
    /// The ratio denominator stays the total row count (not the valid-row count), matching the
    /// per-chunk encoders' convention that null placeholders occupy a row like any other value.
    ///
    /// Rust's `StringDictScheme` skips a sample with more than half its values distinct, and has no
    /// cap on the count: a column that outgrows one dictionary starts another.
    ///
    /// @param data     the Utf8 (`String[]`) or Binary (`byte[][]`) values; null elements at invalid
    ///                 positions are skipped
    /// @param validity per-row validity bitmap, or `null` meaning every row is valid
    /// @return `true` if the sample's distinct valid-value count is low enough to dictionary-encode
    static boolean isVarBinDictCandidate(Object[] data, boolean[] validity) {
        if (data.length == 0) {
            return false;
        }
        var seen = HashSet.newHashSet(data.length);
        for (int i = 0; i < data.length; i++) {
            if ((validity != null && !validity[i]) || data[i] == null) {
                continue;
            }
            seen.add(varBinKey(data[i]));
        }
        if (seen.isEmpty()) {
            return false;
        }
        return seen.size() * 2 < data.length;
    }


    /// Whether a sample of a primitive column is dictionary material when there is no cascade to ask:
    /// Rust's dict scheme skips a sample with more than half of its values distinct, and a single
    /// value is a constant, not a dictionary.
    ///
    /// @param ptype    the column's primitive type
    /// @param data     the sample's values; positions marked invalid by `validity` are skipped
    /// @param validity per-row validity, or `null` when every row is valid
    /// @return `true` if the valid values are under half distinct, and more than one
    static boolean isLowCardinality(PType ptype, Object data, boolean[] validity) {
        int n = primitiveArrayLen(data, ptype);
        if (n == 0) {
            return false;
        }
        long[] bits = rawBits(data, ptype, n);
        LongIntMap seen = new LongIntMap(64);
        int distinct = 0;
        for (int i = 0; i < n; i++) {
            if ((validity == null || validity[i]) && seen.add(bits[i])) {
                distinct++;
            }
        }
        return distinct > 1 && distinct * 2 < n;
    }

    /// Length of a global-dict column's chunk array, the carrier of its `ptype` (F16 as its raw
    /// `short` bits).
    static int primitiveArrayLen(Object data, PType ptype) {
        return switch (ptype) {
            case I8, U8 -> ((byte[]) data).length;
            case I16, U16, F16 -> ((short[]) data).length;
            case I32, U32 -> ((int[]) data).length;
            case I64, U64 -> ((long[]) data).length;
            case F32 -> ((float[]) data).length;
            case F64 -> ((double[]) data).length;
        };
    }

    /// Boxed element `i` of a global-dict column's chunk array, the carrier of its `ptype`.
    static Object readPrimitiveElement(Object data, PType ptype, int i) {
        return switch (ptype) {
            case I8, U8 -> ((byte[]) data)[i];
            case I16, U16, F16 -> ((short[]) data)[i];
            case I32, U32 -> ((int[]) data)[i];
            case I64, U64 -> ((long[]) data)[i];
            case F32 -> ((float[]) data)[i];
            case F64 -> ((double[]) data)[i];
        };
    }

    // The dictionary key of one Utf8/Binary row. Utf8 keys are always Strings, a UTF-8 row decoded,
    // so a column written partly as String[] and partly as byte[][] still codes each value once.
    private Object dictKey(Object value) {
        if (!binary && value instanceof byte[] utf8) {
            return new String(utf8, StandardCharsets.UTF_8);
        }
        return varBinKey(value);
    }

    // A byte[] compares by identity, so Binary values are keyed by a ByteBuffer view, whose
    // equals/hashCode compare content. The array is never mutated after ChunkImpl hands it over.
    private static Object varBinKey(Object value) {
        return value instanceof byte[] bytes ? ByteBuffer.wrap(bytes) : value;
    }
}
