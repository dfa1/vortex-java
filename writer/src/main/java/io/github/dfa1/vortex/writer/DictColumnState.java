package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.encode.LongIntMap;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.PrimitiveEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.VarBinEncodingEncoder;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
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
    // columns (ALP/bitpacked codes beat U16 dict codes). Utf8/Binary is raised far higher — text columns
    // with thousands of repeated distinct values (street/place names) dictionary-compress well
    // (#299), and the per-chunk short[] code buffer holds codes 0..32767 (up to 32768 distinct)
    // with no wider buffer; the codes are always U16 (CODES_PTYPE).
    static final int GLOBAL_DICT_MAX_CARDINALITY = 2_048;

    /// The codes ptype of every global dict: Rust's `DictLayoutConstraints` derive it from the
    /// configured maximum dictionary length, not the actual one -- U8 only when `max_len <= 255`,
    /// and the default `max_len` is `u16::MAX` -- so a default Rust global dict always has U16
    /// codes. The width matters beyond the bytes on disk: the cascade scores candidates against
    /// the codes' raw width, and on U8 codes Sparse's estimate beat bit-packing where on Rust's
    /// U16 codes it does not (taxi Airport_fee).
    static final PType CODES_PTYPE = PType.U16;
    static final int GLOBAL_DICT_MAX_CARDINALITY_UTF8 = 32_768;

    private static final int INDEX_MIN_CAPACITY = 64;

    private final DType dtype;
    // Utf8 or Binary: keyed by value (String, or a ByteBuffer wrapping the bytes, whose
    // equals/hashCode compare content), coded in first-seen order with no frequency remap.
    private final boolean varBin;
    private final boolean binary;
    private final PType ptype;
    private final boolean nullable;
    // First-seen value -> code map (keys are boxed primitives, String, or ByteBuffer — see varBinKey).
    private final Map<Object, Integer> valueToCode = new LinkedHashMap<>();
    // Hot-path side index for the primitive path: raw value bits -> code + 1 (0 == empty), open
    // addressing with a power-of-two capacity so probing masks instead of taking a modulo (CLAUDE.md
    // hot-loop rule). valueToCode stays the authoritative store — it carries first-seen order, the
    // VarBin keys, and everything the demotion and flush paths read — but probing it needs a boxed key,
    // and one Long per row of every candidate column profiled as the writer's hottest single frame.
    private LongIntMap bitsIndex = new LongIntMap(INDEX_MIN_CAPACITY);
    // Occurrence count per code, indexed by code; grows in lockstep with valueToCode. A primitive
    // array, not a List<Long>: this is incremented once per row, and boxing there cost one Long
    // allocation per row of every global-dict candidate column.
    private long[] codeCounts = new long[16];
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

    /// The distinct Utf8/Binary values seen so far, in first-seen order: a `String[]` for Utf8, a
    /// `byte[][]` for Binary. Only valid when [#varBin()].
    Object varBinUniques() {
        if (!binary) {
            return valueToCode.keySet().toArray(new String[0]);
        }
        byte[][] out = new byte[valueToCode.size()][];
        int i = 0;
        for (Object key : valueToCode.keySet()) {
            out[i++] = ((ByteBuffer) key).array();
        }
        return out;
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
    /// @param data the chunk data (primitive array, `String[]`, `byte[][]`, or a [NullableData] wrapper)
    /// @return `true` if the chunk was ingested within the cardinality cap; `false` if the column
    ///         must be demoted
    boolean ingestDictChunk(Object data) {
        boolean nullableData = data instanceof NullableData;
        Object values = nullableData ? ((NullableData) data).values() : data;
        boolean[] validity = nullableData ? ((NullableData) data).validity() : null;
        int len = varBin ? ((Object[]) values).length : primitiveArrayLen(values, ptype);
        int cap = dictMaxCardinality(varBin);
        int startSize = valueToCode.size();
        Object[] strings = varBin ? (Object[]) values : null;

        // One pass: insert new values and build the per-chunk code array. Ingest stays
        // all-or-nothing — a chunk that would breach the cap rolls back the entries it added (the
        // map's tail, codes >= startSize) — so the demoting caller still sees untouched state. The
        // earlier shape ran a non-mutating counting pass first to get that atomicity, which cost a
        // second boxed map probe for every row in the file.
        short[] codes = new short[len];
        if (strings != null) {
            for (int i = 0; i < len; i++) {
                if (validity != null && !validity[i]) {
                    continue;
                }
                // Nullable Utf8/Binary keeps a real null at invalid positions (ChunkImpl.adaptUtf8,
                // adaptBinary); treat it as a null slot (code 0), never as a dictionary entry.
                if (strings[i] == null) {
                    continue;
                }
                Object v = varBinKey(strings[i]);
                Integer code = valueToCode.get(v);
                if (code == null) {
                    if (valueToCode.size() == cap) {
                        rollbackTo(startSize);
                        return false;
                    }
                    code = valueToCode.size();
                    valueToCode.put(v, code);
                }
                codes[i] = code.shortValue();
            }
        } else {
            // Primitive path: probe the unboxed bits index and box only when inserting a value the
            // dictionary has not seen before (at most `cap` times for the whole file). The boxed
            // key itself still comes from the source array, so I32 vs I64 and NaN/-0.0 keying stay
            // exactly what a Double/Long-keyed map gave.
            long[] bits = rawBits(values, ptype, len);
            for (int i = 0; i < len; i++) {
                if (validity != null && !validity[i]) {
                    continue;
                }
                int code = bitsIndex.get(bits[i]);
                if (code < 0) {
                    if (valueToCode.size() == cap) {
                        rollbackTo(startSize);
                        return false;
                    }
                    code = valueToCode.size();
                    valueToCode.put(readPrimitiveElement(values, ptype, i), code);
                    bitsIndex.put(bits[i], code);
                }
                codes[i] = (short) code;
            }
        }

        // Counts are applied only once the chunk is committed, which keeps the rollback above to
        // the map alone and the hot loop above to a single probe per row.
        int size = valueToCode.size();
        if (size > codeCounts.length) {
            codeCounts = java.util.Arrays.copyOf(codeCounts, Math.max(size, codeCounts.length * 2));
        }
        for (int i = 0; i < len; i++) {
            if ((validity != null && !validity[i]) || (strings != null && strings[i] == null)) {
                continue;
            }
            codeCounts[codes[i] & 0xFFFF]++;
        }

        chunkCodes.add(codes);
        chunkValidity.add(validity);
        chunkRowCounts.add((long) len);
        chunkNullCounts.add(validity != null ? VortexWriter.countNulls(validity) : 0L);
        if (binary) {
            // No min/max for Binary, matching the per-chunk VarBin path (VarBinEncodingEncoder).
            chunkStatsMin.add(null);
            chunkStatsMax.add(null);
            chunkStatsSum.add(null);
        } else if (varBin) {
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

    /// Drops every dictionary entry added since the map held `startSize` values, restoring the
    /// state a rejected chunk found — the demoting caller replays the already-buffered chunks
    /// through [#buildInverseMap] and [#reconstructChunk], which must not see this chunk's values.
    private void rollbackTo(int startSize) {
        valueToCode.values().removeIf(code -> code >= startSize);
        if (varBin) {
            return;
        }
        bitsIndex = new LongIntMap(INDEX_MIN_CAPACITY);
        for (Map.Entry<Object, Integer> e : valueToCode.entrySet()) {
            bitsIndex.put(valueBits(ptype, e.getKey()), e.getValue());
        }
    }





    /// The chunk's values as raw bit patterns, keyed so that two rows share a pattern exactly when
    /// their boxed values are `equals` (hence `doubleToLongBits`, which folds every NaN together
    /// and keeps `-0.0` apart from `0.0`, just like `Double.equals`). I64/U64 arrays are used in
    /// place; only the narrower carriers pay for a widened copy.
    private static long[] rawBits(Object values, PType ptype, int len) {
        switch (ptype) {
            case I64, U64 -> {
                return (long[]) values;
            }
            case I32, U32 -> {
                int[] src = (int[]) values;
                long[] out = new long[len];
                for (int i = 0; i < len; i++) {
                    out[i] = src[i];
                }
                return out;
            }
            case F64 -> {
                double[] src = (double[]) values;
                long[] out = new long[len];
                for (int i = 0; i < len; i++) {
                    out[i] = Double.doubleToLongBits(src[i]);
                }
                return out;
            }
            default -> throw new IllegalStateException("ptype not admitted to the global dict: " + ptype);
        }
    }

    /// Inverse of [#rawBits] for a single already-boxed dictionary key.
    private static long valueBits(PType ptype, Object value) {
        return switch (ptype) {
            case I32, U32 -> (Integer) value;
            case I64, U64 -> (Long) value;
            case F64 -> Double.doubleToLongBits((Double) value);
            default -> throw new IllegalStateException("ptype not admitted to the global dict: " + ptype);
        };
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
            if (validity == null || validity[i]) {
                Object key = inverse[codes[i] & 0xFFFF];
                arr[i] = binary ? ((ByteBuffer) key).array() : key;
            }
        }
        return arr;
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
        java.util.Arrays.sort(order, (a, b) -> Long.compare(codeCounts[b], codeCounts[a]));
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

    /// Emits one chunk's wire codes ([#CODES_PTYPE]) from its buffered `short[]` first-seen codes,
    /// optionally translated through a frequency remap (primitive path). A null slot (validity[i]
    /// false) emits `nullCode` unconditionally, never remapped: the primitive path points it at the
    /// pool's invalid null entry, as Rust's dict builder does; the Utf8/Binary path passes `0`, which
    /// the reader ignores because its codes child is masked by the same validity.
    ///
    /// @param buffered the buffered first-seen codes for one chunk
    /// @param remap    the first-seen -> frequency-rank remap, or `null` to emit codes unchanged
    /// @param validity per-row validity, or `null` when every row is valid
    /// @param nullCode the code a null slot emits
    /// @return the U16 codes
    static short[] emitCodes(short[] buffered, int[] remap, boolean[] validity, int nullCode) {
        short[] codes = new short[buffered.length];
        for (int i = 0; i < buffered.length; i++) {
            if (validity == null || validity[i]) {
                int fs = buffered[i] & 0xFFFF;
                codes[i] = (short) (remap == null ? fs : remap[fs]);
            } else {
                codes[i] = (short) nullCode;
            }
        }
        return codes;
    }

    /// The values pool with one more slot, for the invalid null entry Rust's dict builder adds
    /// when it meets a null: the slot holds a zero placeholder and is masked off by the caller.
    ///
    /// @param values the frequency-ranked distinct values (a primitive array)
    /// @return a copy one element longer
    static Object withNullSlot(Object values) {
        return switch (values) {
            case long[] a -> Arrays.copyOf(a, a.length + 1);
            case int[] a -> Arrays.copyOf(a, a.length + 1);
            case short[] a -> Arrays.copyOf(a, a.length + 1);
            case byte[] a -> Arrays.copyOf(a, a.length + 1);
            case double[] a -> Arrays.copyOf(a, a.length + 1);
            case float[] a -> Arrays.copyOf(a, a.length + 1);
            default -> throw new IllegalStateException("not a primitive values pool: " + values.getClass());
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
    /// @param data     the Utf8 (`String[]`) or Binary (`byte[][]`) values; null elements at invalid
    ///                 positions are skipped
    /// @param validity per-row validity bitmap, or `null` meaning every row is valid
    /// @return `true` if the column's distinct valid-value count is low enough to dictionary-encode
    static boolean isVarBinDictCandidate(Object[] data, boolean[] validity) {
        if (data.length == 0) {
            return false;
        }
        var seen = HashSet.newHashSet(Math.min(GLOBAL_DICT_MAX_CARDINALITY_UTF8, data.length));
        for (int i = 0; i < data.length; i++) {
            if ((validity != null && !validity[i]) || data[i] == null) {
                continue;
            }
            seen.add(varBinKey(data[i]));
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
        // Counts distinct values on raw bits rather than into a HashSet<Object>, which boxed one
        // value per row of the probe chunk — the same cost already removed from ingestDictChunk
        // and DictEncodingEncoder, and 8.2% of a bitpacked write.
        int distinct = countDistinctCapped(rawBits(data, ptype, n), n, validity);
        if (distinct > GLOBAL_DICT_MAX_CARDINALITY || distinct == 0) {
            return false;
        }
        // Single-value columns fit vortex.constant better than dict (zero dict overhead).
        // Delegate to the cascading compressor.
        if (distinct == 1) {
            return false;
        }
        return distinct * 2 < n;
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

    /// Counts distinct raw bit patterns, abandoning as soon as the count exceeds
    /// [#GLOBAL_DICT_MAX_CARDINALITY] — past that the column is not a dictionary candidate, so the
    /// exact count is never needed. The set never grows: it is sized once for the cap.
    ///
    /// @param bits     the chunk's values as raw bit patterns
    /// @param n        element count
    /// @param validity per-row validity, or `null` when every row is valid
    /// @return the distinct count, or [#GLOBAL_DICT_MAX_CARDINALITY] + 1 if the cap was exceeded
    private static int countDistinctCapped(long[] bits, int n, boolean[] validity) {
        LongIntMap seen = new LongIntMap(GLOBAL_DICT_MAX_CARDINALITY + 1);
        int distinct = 0;
        for (int i = 0; i < n; i++) {
            if (validity != null && !validity[i]) {
                continue;
            }
            if (seen.add(bits[i])) {
                distinct++;
                if (distinct > GLOBAL_DICT_MAX_CARDINALITY) {
                    return distinct;
                }
            }
        }
        return distinct;
    }

    // The global-dict cardinality cap for a column, by whether it is Utf8/Binary (see the constants above).
    private static int dictMaxCardinality(boolean varBin) {
        return varBin ? GLOBAL_DICT_MAX_CARDINALITY_UTF8 : GLOBAL_DICT_MAX_CARDINALITY;
    }

    // A byte[] compares by identity, so Binary values are keyed by a ByteBuffer view, whose
    // equals/hashCode compare content. The array is never mutated after ChunkImpl hands it over.
    private static Object varBinKey(Object value) {
        return value instanceof byte[] bytes ? ByteBuffer.wrap(bytes) : value;
    }
}
