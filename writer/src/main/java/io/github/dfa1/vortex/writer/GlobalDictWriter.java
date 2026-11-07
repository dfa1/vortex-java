package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.writer.encode.MaskedEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.RowCount;
import io.github.dfa1.vortex.writer.encode.VarBinEncodingEncoder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Rust's `DictStrategy` for one [VortexWriter]: columns admitted on their first batch are buffered
/// as dictionaries instead of encoded per chunk, and written at close as one dict layout per
/// dictionary (several in a chunked layout).
///
/// Buffering is cardinality-bounded (ADR 0021): rather than retain raw values, each candidate holds
/// a deduplicated value->code map (at most 65,535 entries or 1 MiB per dictionary) plus cheap
/// per-chunk code arrays. Past [WriteOptions#globalDictMaxRetainedBytes()] the largest columns
/// demote, replaying their buffered chunks to the writer's per-chunk path. The dict layouts
/// themselves are built by [LayoutTree] from [#written()].
final class GlobalDictWriter {

    /// Where a demoted column's buffered chunks go: the writer's per-chunk path.
    @FunctionalInterface
    interface ChunkAppender {
        void append(ColumnName column, DType dtype, Object data) throws IOException;
    }

    private final WriteOptions options;
    private final SegmentEncoder encoder;
    private final SegmentSink sink;
    // Rust's repartition of the codes, as of any data column; the legacy zone map keeps one code
    // chunk per batch, its zones.
    private final boolean repartitionCodes;
    private final ChunkAppender demoted;
    // Aggregate bytes every candidate's dictionaries may retain, from
    // WriteOptions.globalDictMaxRetainedBytes(): a secondary safety net (ADR 0021) so no set of
    // mis-detected columns can pin the heap. Codes cost ~2 B/row instead of raw values, so it rarely
    // fires; when the sum crosses it, the largest columns demote.
    private final long retainedBudget;
    // Each candidate's open dictionary; its keys are the candidate columns, in detection order.
    private final Map<ColumnName, DictColumnState> dictStates;
    // Dictionaries a column has already filled to Rust's constraints (a new one starts when it
    // reaches them); the open one is in dictStates.
    private final Map<ColumnName, List<DictColumnState>> dictRuns;
    private final Map<ColumnName, List<DictColRef>> dictColRefs;

    GlobalDictWriter(WriteOptions options, SegmentEncoder encoder, SegmentSink sink, boolean repartitionCodes,
            ChunkAppender demoted) {
        this.options = options;
        this.encoder = encoder;
        this.sink = sink;
        this.repartitionCodes = repartitionCodes;
        this.demoted = demoted;
        this.retainedBudget = options.globalDictMaxRetainedBytes().bytes();
        this.dictStates = new LinkedHashMap<>();
        this.dictRuns = new LinkedHashMap<>();
        this.dictColRefs = new LinkedHashMap<>();
    }

    /// Decides from a column's first batch whether it becomes a candidate.
    ///
    /// Candidate detection inspects raw primitive/String/byte[] arrays. Nullable columns (carried as
    /// NullableData) run the same cardinality/ratio check against their values, skipping null
    /// positions per the validity bitmap; the reader's dict lazy-decode already handles masked
    /// (nullable) codes children.
    void detect(ColumnName colName, DType colDtype, Object data) {
        if (!options.globalDict() || encoder.columnEncoder(colName) != null) {
            return;
        }
        Object values = data;
        boolean[] validity = null;
        if (data instanceof NullableData nd) {
            values = nd.values();
            validity = nd.validity();
        }
        boolean candidate = false;
        if (colDtype instanceof DType.Primitive p) {
            // Rust's DictStrategy admits a column when its probe compressor, the cascade, turns
            // the first 8192 rows into a dict; there is no cardinality cap
            candidate = DictColumnState.admitsPtype(p.ptype());
            if (candidate && options.allowedCascading() == 0) {
                // no cascade to ask: the sample's distinct count decides
                int probeRows = Math.min(Repartitioner.BLOCK_ROWS, RowCount.of(data));
                Object head = Repartitioner.head(values, probeRows);
                candidate = DictColumnState.isLowCardinality(p.ptype(), head,
                        validity != null ? Arrays.copyOf(validity, probeRows) : null);
            } else if (candidate) {
                candidate = encoder.probeCompressesToDict(colName, colDtype, data);
            }
        } else if (colDtype instanceof DType.Utf8 || colDtype instanceof DType.Binary) {
            // Rust's dict layout admits Primitive | Utf8 | Binary (dict_layout_supported), by what
            // its first 8192 rows do: not more than half distinct. The cascade probe stays with
            // primitives: ours picks OnPair for strings where Rust's StringDictScheme wins.
            int probeRows = Math.min(Repartitioner.BLOCK_ROWS, ((Object[]) values).length);
            candidate = DictColumnState.isVarBinDictCandidate(
                    Arrays.copyOf((Object[]) values, probeRows),
                    validity != null ? Arrays.copyOf(validity, probeRows) : null);
        }
        if (candidate) {
            dictStates.put(colName, new DictColumnState(colDtype));
        }
    }

    /// Whether `colName`'s batches are buffered here rather than written per chunk.
    boolean isCandidate(ColumnName colName) {
        return dictStates.containsKey(colName);
    }

    /// Ingests one batch of a candidate column into its open dictionary, starting the next one when
    /// it is full: dedup values into the shared value->code map, buffer a cheap per-chunk code
    /// array, and capture the per-chunk stats now (before the raw array is discarded). Past the
    /// retained-bytes budget, the largest columns demote.
    ///
    /// @throws IOException if writing a demoted column's chunks fails
    void ingest(ColumnName colName, DType colDtype, Object data) throws IOException {
        ingestChunk(colName, colDtype, data);
        if (retainedTotal() > retainedBudget) {
            // Aggregate code-array budget exceeded (secondary safety net). Demote the
            // largest-retained columns until back under budget, so writer memory stays
            // bounded even across many wide candidate columns.
            evictLargestDictColumnsUntilUnderBudget();
        }
    }

    /// Writes every candidate's dictionaries: values pool, then codes.
    void flush() throws IOException {
        for (Map.Entry<ColumnName, DictColumnState> open : dictStates.entrySet()) {
            ColumnName colName = open.getKey();
            List<DictColumnState> states = new ArrayList<>(dictRuns.getOrDefault(colName, List.of()));
            states.add(open.getValue());
            for (DictColumnState state : states) {
                if (state.chunkCount() == 0 || state.cardinality() == 0) {
                    continue;
                }
                if (state.varBin()) {
                    writeGlobalDictVarBinColumn(colName, state);
                } else {
                    writeGlobalDictColumn(colName, state);
                }
            }
        }
    }

    /// The dictionaries [#flush()] wrote, per column, in detection order.
    Map<ColumnName, List<DictColRef>> written() {
        return Collections.unmodifiableMap(dictColRefs);
    }

    /// Feeds one chunk to the column's open dictionary; whatever does not fit under Rust's
    /// constraints closes it and starts the next one (`DictStrategy`: "when the dict constraints are
    /// hit, a new dictionary is created"), so a column never gives up its dict layout for its
    /// cardinality.
    private void ingestChunk(ColumnName colName, DType colDtype, Object data) {
        Object rest = data;
        while (true) {
            DictColumnState state = dictStates.get(colName);
            int consumed = colDtype instanceof DType.Primitive ? state.ingestDictChunk(rest) : state.ingestVarBinChunk(rest);
            long length = RowCount.of(rest);
            if (consumed == length) {
                return;
            }
            dictRuns.computeIfAbsent(colName, _ -> new ArrayList<>()).add(state);
            dictStates.put(colName, new DictColumnState(colDtype));
            rest = consumed == 0 ? rest : Repartitioner.tail(rest, consumed);
        }
    }

    private long retainedBytes(ColumnName colName) {
        long total = dictStates.get(colName).retainedBytes();
        for (DictColumnState run : dictRuns.getOrDefault(colName, List.of())) {
            total += run.retainedBytes();
        }
        return total;
    }

    private long retainedTotal() {
        long total = 0;
        for (ColumnName colName : dictStates.keySet()) {
            total += retainedBytes(colName);
        }
        return total;
    }

    /// Demotes the largest-retained global-dict candidate columns to per-chunk encoding, one at a
    /// time (largest first), until the aggregate retained bytes fall back under the budget. Demoting
    /// the largest column frees the most memory per eviction, so the fewest columns lose their shared
    /// dictionary. Called when a chunk pushes the running total over `retainedBudget`.
    ///
    /// @throws IOException if writing a flushed segment fails
    private void evictLargestDictColumnsUntilUnderBudget() throws IOException {
        while (retainedTotal() > retainedBudget && !dictStates.isEmpty()) {
            ColumnName largest = null;
            long largestBytes = -1L;
            for (ColumnName colName : dictStates.keySet()) {
                long bytes = retainedBytes(colName);
                if (bytes > largestBytes) {
                    largestBytes = bytes;
                    largest = colName;
                }
            }
            demoteDictColumn(largest);
        }
    }

    /// Abandons the shared global dictionary for one column, because the aggregate code-array budget
    /// was crossed, and replays its already-buffered chunks as ordinary per-chunk segments so no
    /// data is lost (ADR 0021). Each buffered chunk is reconstructed exactly from its `short[]`
    /// codes plus the inverse code-to-value map, then handed to the writer's per-chunk path,
    /// yielding a Chunked-of-Flats layout. The buffered state is released for GC.
    ///
    /// @param colName the column being demoted from global-dict to per-chunk encoding
    /// @throws IOException if writing a flushed segment fails
    private void demoteDictColumn(ColumnName colName) throws IOException {
        DictColumnState open = dictStates.remove(colName);
        List<DictColumnState> states = new ArrayList<>(dictRuns.getOrDefault(colName, List.of()));
        dictRuns.remove(colName);
        if (open == null) {
            return;
        }
        states.add(open);
        // the column's dtype: every dictionary of a column is created with it
        DType colDtype = open.dtype();
        for (DictColumnState state : states) {
            Object[] inverse = state.buildInverseMap();
            for (int c = 0; c < state.chunkCount(); c++) {
                demoted.append(colName, colDtype, state.reconstructChunk(inverse, c));
            }
        }
    }

    private void writeGlobalDictColumn(ColumnName colName, DictColumnState state) throws IOException {
        // Write values segment using the same codec path as regular segments so codes benefit from
        // bitpacking/FOR when cascading is enabled. Safe: global dict is disabled for custom-encoding
        // writers (withGlobalDict(false)), so the writer's encodings are the default ones here.
        int valuesSegIdx = sink.write(colName, state.dtype(), dictPool(state), null, Set.of());
        writeGlobalDictCodes(colName, state, valuesSegIdx);
    }

    private void writeGlobalDictVarBinColumn(ColumnName colName, DictColumnState state) throws IOException {
        // Compress the distinct-values pool through the normal Utf8/Binary competition
        // (FSST/VarBin/Zstd) so it captures substring redundancy across dictionary entries (#299),
        // but exclude Dict so the cascade never wraps the (all-unique-by-construction) dictionary in
        // another dict the reader cannot unwrap. At cascade depth 0 there is no competition to run,
        // so force flat VarBin as before; a pool with a null entry is masked there instead, its
        // values still VarBin.
        Object pool = dictPool(state);
        int valuesSegIdx;
        if (options.allowedCascading() > 0) {
            valuesSegIdx = sink.write(colName, state.dtype(), pool, null, Set.of(EncodingId.VORTEX_DICT));
        } else if (state.nullCode() >= 0) {
            valuesSegIdx = sink.write(colName, state.dtype(), pool, new MaskedEncodingEncoder(), Set.of(EncodingId.VORTEX_DICT));
        } else {
            valuesSegIdx = sink.write(colName, state.dtype(), pool, new VarBinEncodingEncoder(), Set.of());
        }
        writeGlobalDictCodes(colName, state, valuesSegIdx);
    }

    // The dictionary entries in code order. A column that met a null holds one entry, invalid, that
    // every null row points at -- Rust's dict builder (vortex-array builders/dict) -- so the codes
    // need no validity of their own: Rust's dict layout writer always emits non-nullable codes.
    private static Object dictPool(DictColumnState state) {
        Object uniques = state.uniques();
        int nullCode = state.nullCode();
        if (nullCode < 0) {
            return uniques;
        }
        boolean[] poolValidity = new boolean[state.cardinality()];
        Arrays.fill(poolValidity, true);
        poolValidity[nullCode] = false;
        return new NullableData(uniques, poolValidity);
    }

    // The buffered codes are already the wire codes: first-seen order, null included. Rust's dict
    // strategy coalesces the codes like any data column, so U16 codes chunk at 512Ki rows; the
    // legacy zone map keeps one code chunk per batch, its zones.
    private void writeGlobalDictCodes(ColumnName colName, DictColumnState state, int valuesSegIdx) throws IOException {
        DType codesDtype = new DType.Primitive(DictColumnState.CODES_PTYPE, false);
        List<Integer> codesSegIdxes = new ArrayList<>();
        List<Long> codesRowCounts = new ArrayList<>();
        Repartitioner repartitioner = repartitionCodes ? new Repartitioner(codesDtype) : null;
        for (int c = 0; c < state.chunkCount(); c++) {
            short[] codes = state.chunkCodes(c);
            List<Object> ready = repartitioner != null ? repartitioner.add(codes, codes.length) : List.of(codes);
            for (Object chunk : ready) {
                codesSegIdxes.add(sink.write(colName, codesDtype, chunk, null, Set.of()));
                codesRowCounts.add((long) ((short[]) chunk).length);
            }
        }
        for (Object last : repartitioner != null ? repartitioner.finish() : List.of()) {
            codesSegIdxes.add(sink.write(colName, codesDtype, last, null, Set.of()));
            codesRowCounts.add((long) ((short[]) last).length);
        }
        dictColRefs.computeIfAbsent(colName, _ -> new ArrayList<>()).add(new DictColRef(valuesSegIdx, state.cardinality(),
                codesSegIdxes, codesRowCounts, state.chunkRowCounts(), state.chunkNullCounts(),
                state.chunkStatsMin(), state.chunkStatsMax(), state.chunkStatsSum(), false));
    }

    /// One written dictionary: its values segment, its code chunks, and the per-batch stats the
    /// legacy zone map is built from.
    ///
    /// @param nullableCodes whether the codes child carries validity (Rust's `is_nullable_codes`)
    record DictColRef(int valuesSegIdx, long valuesLen, List<Integer> codesSegIdxes,
            List<Long> codesRowCounts, List<Long> chunkRowCounts, List<Long> chunkNullCounts,
            List<ProtoScalarValue> chunkStatsMin, List<ProtoScalarValue> chunkStatsMax, List<ProtoScalarValue> chunkStatsSum,
            boolean nullableCodes) {
        long totalRows() {
            return chunkRowCounts.stream().mapToLong(Long::longValue).sum();
        }
    }
}
