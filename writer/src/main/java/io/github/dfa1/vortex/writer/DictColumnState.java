package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.PrimitiveEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.VarBinEncodingEncoder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Cardinality-bounded buffering state for one global-dictionary candidate column (ADR 0021). Instead of
/// retaining raw values from a column's first chunk until `close()`, this holds a deduplicated
/// value-to-code map (first-seen order, capped at [#GLOBAL_DICT_MAX_CARDINALITY]), a parallel
/// per-code occurrence count (used by the primitive path's frequency remap), and one cheap
/// `short[]` code array per ingested chunk. Per-chunk stats are captured at ingest time from the
/// raw chunk, before it is discarded.
///
/// A null (invalid) slot buffers code `0` unconditionally: the raw placeholder value is never
/// looked up in the map, matching the pre-ADR builders. The reader ignores those slots because the
/// codes child is masked by the same per-chunk validity.
final class DictColumnState {

    // Columns with global cardinality below this threshold are dict-encoded across all chunks.
    // The cap is type-aware. Numeric stays low: a global dict hurts high-cardinality F64/I64
    // columns (ALP/bitpacked codes beat U16 dict codes). Utf8 is raised far higher — text columns
    // with thousands of repeated distinct values (street/place names) dictionary-compress well
    // (#299), and the per-chunk short[] code buffer holds codes 0..32767 (up to 32768 distinct)
    // with no wider buffer; codePTypeForSize already emits U16 codes above 256.
    static final int GLOBAL_DICT_MAX_CARDINALITY = 2_048;
    static final int GLOBAL_DICT_MAX_CARDINALITY_UTF8 = 32_768;

    private final DType dtype;
    private final boolean utf8;
    private final PType ptype;
    private final boolean nullable;
    // First-seen value -> code map (keys are boxed primitives or String, matching readPrimitiveElement).
    private final Map<Object, Integer> valueToCode = new LinkedHashMap<>();
    // Occurrence count per code, indexed by code; grows in lockstep with valueToCode.
    private final List<Long> codeCounts = new ArrayList<>();
    // One code array per ingested chunk (null slots hold code 0).
    private final List<short[]> chunkCodes = new ArrayList<>();
    private final List<boolean[]> chunkValidity = new ArrayList<>();
    private final List<Long> chunkRowCounts = new ArrayList<>();
    private final List<Long> chunkNullCounts = new ArrayList<>();
    private final List<byte[]> chunkStatsMin = new ArrayList<>();
    private final List<byte[]> chunkStatsMax = new ArrayList<>();
    private final List<byte[]> chunkStatsSum = new ArrayList<>();
    private long codeArrayBytes;

    DictColumnState(DType dtype) {
        this.dtype = dtype;
        this.utf8 = dtype instanceof DType.Utf8;
        this.ptype = dtype instanceof DType.Primitive p ? p.ptype() : null;
        this.nullable = dtype.nullable();
    }

    DType dtype() {
        return dtype;
    }

    boolean utf8() {
        return utf8;
    }

    PType ptype() {
        return ptype;
    }

    boolean nullable() {
        return nullable;
    }

    int cardinality() {
        return valueToCode.size();
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

    boolean[] chunkValidity(int c) {
        return chunkValidity.get(c);
    }

    List<Long> chunkRowCounts() {
        return chunkRowCounts;
    }

    List<Long> chunkNullCounts() {
        return chunkNullCounts;
    }

    List<byte[]> chunkStatsMin() {
        return chunkStatsMin;
    }

    List<byte[]> chunkStatsMax() {
        return chunkStatsMax;
    }

    List<byte[]> chunkStatsSum() {
        return chunkStatsSum;
    }

    /// The distinct Utf8 values seen so far, in first-seen order. Only valid when [#utf8()].
    String[] utf8Uniques() {
        return valueToCode.keySet().toArray(new String[0]);
    }

    /// Ingests one chunk into this candidate column's cardinality-bounded dict state (ADR 0021): dedups
    /// each valid value into the shared value-to-code map, appends a per-chunk `short[]` code array,
    /// and captures the chunk's row/null counts and min/max/sum stats before the raw array is
    /// discarded. Null slots buffer code `0` and are excluded from the distinct set.
    ///
    /// Returns `false` — without mutating this state — the moment a new distinct value would push the
    /// map past [#GLOBAL_DICT_MAX_CARDINALITY]; the caller then demotes the column to per-chunk
    /// encoding. This moves the cap check from `close()` to a continuous, mid-file guard so a column
    /// whose distinct set grows past the cap never accumulates unbounded memory first.
    ///
    /// @param data the chunk data (primitive array, `String[]`, or a [NullableData] wrapper)
    /// @return `true` if the chunk was ingested within the cardinality cap; `false` if the column
    ///         must be demoted
    boolean ingestDictChunk(Object data) {
        boolean nullableData = data instanceof NullableData;
        Object values = nullableData ? ((NullableData) data).values() : data;
        boolean[] validity = nullableData ? ((NullableData) data).validity() : null;
        int len = utf8 ? ((String[]) values).length : primitiveArrayLen(values, ptype);
        int cap = dictMaxCardinality(utf8);

        // First pass: would this chunk's fresh distinct values push the map past the cap? Count them
        // without mutating so the whole chunk is either ingested or rejected atomically — a partial
        // ingest would corrupt the dictionary when the caller demotes on rejection.
        var pendingNew = new HashSet<>(Math.min(cap, len) + 1);
        for (int i = 0; i < len; i++) {
            if (validity != null && !validity[i]) {
                continue;
            }
            Object v = utf8 ? ((String[]) values)[i] : readPrimitiveElement(values, ptype, i);
            if (v == null) {
                // Nullable Utf8 keeps a real null at invalid positions (ChunkImpl.adaptUtf8); treat it
                // as a null slot (code 0), never as a dictionary entry. Primitive placeholders never
                // reach here because their slots are guarded by validity above.
                continue;
            }
            if (!valueToCode.containsKey(v) && pendingNew.add(v)
                    && valueToCode.size() + pendingNew.size() > cap) {
                return false;
            }
        }

        // Second pass: commit — insert new values and build the per-chunk code array.
        short[] codes = new short[len];
        for (int i = 0; i < len; i++) {
            if (validity != null && !validity[i]) {
                continue;
            }
            Object v = utf8 ? ((String[]) values)[i] : readPrimitiveElement(values, ptype, i);
            if (v == null) {
                continue;
            }
            Integer code = valueToCode.get(v);
            if (code == null) {
                code = valueToCode.size();
                valueToCode.put(v, code);
                codeCounts.add(0L);
            }
            codes[i] = code.shortValue();
            codeCounts.set(code, codeCounts.get(code) + 1L);
        }

        chunkCodes.add(codes);
        chunkValidity.add(validity);
        chunkRowCounts.add((long) len);
        chunkNullCounts.add(validity != null ? VortexWriter.countNulls(validity) : 0L);
        if (utf8) {
            byte[][] mm = VarBinEncodingEncoder.minMaxStats((String[]) values);
            chunkStatsMin.add(mm != null ? mm[0] : null);
            chunkStatsMax.add(mm != null ? mm[1] : null);
            chunkStatsSum.add(null);
        } else {
            byte[][] mm = PrimitiveEncodingEncoder.minMaxStats(ptype, values);
            chunkStatsMin.add(mm != null ? mm[0] : null);
            chunkStatsMax.add(mm != null ? mm[1] : null);
            chunkStatsSum.add(PrimitiveEncodingEncoder.sumStat(ptype, values));
        }
        codeArrayBytes += 2L * len;
        return true;
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

    /// Reconstructs demoted chunk `c`'s raw array (a typed primitive array or `String[]`, wrapped in
    /// [NullableData] when the chunk carried validity) from its buffered `short[]` codes and the
    /// inverse code-to-value map. Null slots restore a zero/`null` placeholder — exactly what the
    /// per-chunk encoders expect from [NullableData].
    Object reconstructChunk(Object[] inverse, int c) {
        short[] codes = chunkCodes.get(c);
        boolean[] validity = chunkValidity.get(c);
        int len = codes.length;
        Object values;
        if (utf8) {
            String[] arr = new String[len];
            for (int i = 0; i < len; i++) {
                if (validity == null || validity[i]) {
                    arr[i] = (String) inverse[codes[i] & 0xFFFF];
                }
            }
            values = arr;
        } else {
            values = reconstructPrimitiveValues(ptype, codes, validity, inverse);
        }
        return validity != null ? new NullableData(values, validity) : values;
    }

    private static Object reconstructPrimitiveValues(PType ptype, short[] codes, boolean[] validity, Object[] inverse) {
        int len = codes.length;
        return switch (ptype) {
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
            case F64 -> {
                double[] arr = new double[len];
                for (int i = 0; i < len; i++) {
                    if (validity == null || validity[i]) {
                        arr[i] = (Double) inverse[codes[i] & 0xFFFF];
                    }
                }
                yield arr;
            }
            default -> throw new IllegalStateException("ptype not admitted to the global dict: " + ptype);
        };
    }

    /// Builds the first-seen -> frequency-rank code remap for the primitive dict path. Distinct values
    /// are ranked by their (incrementally tracked) occurrence count descending, so the dominant value
    /// gets rank 0. `remap[firstSeenCode]` is the frequency-rank code. Ties keep first-seen order,
    /// matching the pre-ADR stable sort on a first-seen-ordered `LinkedHashMap`.
    int[] buildFrequencyRemap() {
        int n = cardinality();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        // Stable sort by count descending; equal counts preserve first-seen (ascending index) order.
        java.util.Arrays.sort(order, (a, b) -> Long.compare(codeCounts.get(b), codeCounts.get(a)));
        int[] remap = new int[n];
        for (int rank = 0; rank < n; rank++) {
            remap[order[rank]] = rank;
        }
        return remap;
    }

    /// Builds the primitive dictionary's unique-values array in frequency-rank order: slot `rank`
    /// holds the value whose first-seen code remaps to `rank`. Only the carriers [#isDictCandidate]
    /// admits — I32/U32, I64/U64, F64 — reach here.
    Object buildFrequencyRankedUniqueArray(int[] remap) {
        int dictSize = cardinality();
        Object[] byRank = new Object[dictSize];
        for (Map.Entry<Object, Integer> e : valueToCode.entrySet()) {
            byRank[remap[e.getValue()]] = e.getKey();
        }
        return switch (ptype) {
            case I32, U32 -> {
                int[] a = new int[dictSize];
                for (int i = 0; i < dictSize; i++) {
                    a[i] = (Integer) byRank[i];
                }
                yield a;
            }
            case I64, U64 -> {
                long[] a = new long[dictSize];
                for (int i = 0; i < dictSize; i++) {
                    a[i] = (Long) byRank[i];
                }
                yield a;
            }
            case F64 -> {
                double[] a = new double[dictSize];
                for (int i = 0; i < dictSize; i++) {
                    a[i] = (Double) byRank[i];
                }
                yield a;
            }
            default -> throw new IllegalStateException("ptype not admitted to the global dict: " + ptype);
        };
    }

    /// Emits one chunk's wire codes array (U8/U16/U32) from its buffered `short[]` first-seen codes,
    /// optionally translated through a frequency remap (primitive path) and masking null slots to
    /// code `0`. A null slot (validity[i] false) emits code `0` unconditionally, never remapped: the
    /// reader ignores those slots because the codes child is masked by the same validity.
    ///
    /// @param buffered the buffered first-seen codes for one chunk
    /// @param remap    the first-seen -> frequency-rank remap, or `null` to emit codes unchanged
    /// @param validity per-row validity, or `null` when every row is valid
    /// @param codePType the wire code ptype chosen from the dictionary size
    /// @return a `byte[]`, `short[]`, or `int[]` codes array matching `codePType`
    static Object emitCodes(short[] buffered, int[] remap, boolean[] validity, PType codePType) {
        int len = buffered.length;
        return switch (codePType) {
            case U8 -> {
                byte[] codes = new byte[len];
                for (int i = 0; i < len; i++) {
                    if (validity == null || validity[i]) {
                        int fs = buffered[i] & 0xFFFF;
                        codes[i] = (byte) (remap == null ? fs : remap[fs]);
                    }
                }
                yield codes;
            }
            case U16 -> {
                short[] codes = new short[len];
                for (int i = 0; i < len; i++) {
                    if (validity == null || validity[i]) {
                        int fs = buffered[i] & 0xFFFF;
                        codes[i] = (short) (remap == null ? fs : remap[fs]);
                    }
                }
                yield codes;
            }
            default -> {
                int[] codes = new int[len];
                for (int i = 0; i < len; i++) {
                    if (validity == null || validity[i]) {
                        int fs = buffered[i] & 0xFFFF;
                        codes[i] = remap == null ? fs : remap[fs];
                    }
                }
                yield codes;
            }
        };
    }

    static boolean isUtf8DictCandidate(String[] data) {
        return isUtf8DictCandidate(data, null);
    }

    /// Like [#isUtf8DictCandidate(String[])] but ignores null (invalid) rows when counting distinct
    /// values, so a nullable low-cardinality column still qualifies for the shared global dictionary.
    /// The ratio denominator stays the total row count (not the valid-row count), matching the
    /// per-chunk encoders' convention that null placeholders occupy a row like any other value.
    ///
    /// @param data     the string values; null elements at invalid positions are skipped
    /// @param validity per-row validity bitmap, or `null` meaning every row is valid
    /// @return `true` if the column's distinct valid-value count is low enough to dictionary-encode
    static boolean isUtf8DictCandidate(String[] data, boolean[] validity) {
        if (data.length == 0) {
            return false;
        }
        var seen = new java.util.HashSet<String>(Math.min(GLOBAL_DICT_MAX_CARDINALITY_UTF8, data.length) + 1);
        for (int i = 0; i < data.length; i++) {
            if ((validity != null && !validity[i]) || data[i] == null) {
                continue;
            }
            seen.add(data[i]);
            if (seen.size() > GLOBAL_DICT_MAX_CARDINALITY_UTF8) {
                return false;
            }
        }
        if (seen.isEmpty()) {
            return false;
        }
        return seen.size() * 2 < data.length;
    }

    static boolean isDictCandidate(PType ptype, Object data) {
        return isDictCandidate(ptype, data, null);
    }

    /// Like [#isDictCandidate(PType, Object)] but ignores null (invalid) rows when counting distinct
    /// values, so a nullable low-cardinality column still qualifies for the shared global dictionary.
    /// Null slots hold zero-valued placeholders (per NullableData's contract); skipping them keeps a
    /// legitimate `0` value from being deduplicated against those placeholders and keeps nulls out of
    /// the distinct count. The ratio denominator stays the total row count (not the valid-row count),
    /// matching the per-chunk encoders' convention that a null placeholder occupies a row like any
    /// other value.
    ///
    /// @param ptype    the column's primitive type
    /// @param data     the packed values array; positions marked invalid by `validity` are skipped
    /// @param validity per-row validity bitmap, or `null` meaning every row is valid
    /// @return `true` if the column's distinct valid-value count is low enough to dictionary-encode
    static boolean isDictCandidate(PType ptype, Object data, boolean[] validity) {
        // Only the carriers the reader's lazy dict decode supports (I32/I64/F64) are admitted.
        // - I8/U8/I16/U16 excluded: dict gives little/no benefit (a U8/U16 code is no smaller
        //   than the value), the Rust compressor does not dict them either (verified by
        //   RustWritesJavaReadsIntegrationTest#jniWriter_javaReader_lowCardinalityI16), and the
        //   reader cannot decode a narrow-int dict — emitting one produced an unreadable file.
        // - F16/F32 excluded: no measured workload; ALP usually wins.
        // F64 admitted: low-card F64 columns (taxi mta_tax/Airport_fee/extra) compress better via
        // global dict + sparse-coded codes (matches Rust FloatDictScheme). The skip rule
        // (cardinality / 2 below) mirrors Rust's >50%-distinct skip.
        if (ptype == PType.I8 || ptype == PType.U8
                || ptype == PType.I16 || ptype == PType.U16
                || ptype == PType.F16 || ptype == PType.F32) {
            return false;
        }
        int n = primitiveArrayLen(data, ptype);
        if (n == 0) {
            return false;
        }
        var seen = new HashSet<>(GLOBAL_DICT_MAX_CARDINALITY + 1);
        for (int i = 0; i < n; i++) {
            if (validity != null && !validity[i]) {
                continue;
            }
            seen.add(readPrimitiveElement(data, ptype, i));
            if (seen.size() > GLOBAL_DICT_MAX_CARDINALITY) {
                return false;
            }
        }
        if (seen.isEmpty()) {
            return false;
        }
        // Single-value columns fit vortex.constant better than dict (zero dict overhead).
        // Delegate to the cascading compressor.
        if (seen.size() == 1) {
            return false;
        }
        return seen.size() * 2 < n;
    }

    /// Length of a global-dict column's chunk array. Only the dict-admitted carriers ([#isDictCandidate])
    /// — I32/U32, I64/U64, F64 — reach here; narrow-int and F16/F32 ptypes are rejected upstream.
    static int primitiveArrayLen(Object data, PType ptype) {
        return switch (ptype) {
            case I32, U32 -> ((int[]) data).length;
            case I64, U64 -> ((long[]) data).length;
            case F64 -> ((double[]) data).length;
            default -> throw new IllegalStateException("ptype not admitted to the global dict: " + ptype);
        };
    }

    /// Boxed element `i` of a global-dict column's chunk array. Only the dict-admitted carriers
    /// ([#isDictCandidate]) — I32/U32, I64/U64, F64 — reach here; narrow-int and F16/F32 ptypes are
    /// rejected upstream.
    static Object readPrimitiveElement(Object data, PType ptype, int i) {
        return switch (ptype) {
            case I32, U32 -> ((int[]) data)[i];
            case I64, U64 -> ((long[]) data)[i];
            case F64 -> ((double[]) data)[i];
            default -> throw new IllegalStateException("ptype not admitted to the global dict: " + ptype);
        };
    }

    static PType codePTypeForSize(int dictSize) {
        if (dictSize <= 256) {
            return PType.U8;
        }
        if (dictSize <= 65_536) {
            return PType.U16;
        }
        return PType.U32;
    }

    // The global-dict cardinality cap for a column, by whether it is Utf8 (see the constants above).
    private static int dictMaxCardinality(boolean utf8) {
        return utf8 ? GLOBAL_DICT_MAX_CARDINALITY_UTF8 : GLOBAL_DICT_MAX_CARDINALITY;
    }
}
