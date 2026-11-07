package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.writer.GlobalDictWriter.DictColRef;
import io.github.dfa1.vortex.writer.SegmentEncoder.SegmentStats;
import io.github.dfa1.vortex.writer.VortexWriter.ChunkRef;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.RowCount;
import io.github.dfa1.vortex.writer.encode.StructData;
import io.github.dfa1.vortex.writer.encode.StructEncodingEncoder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/// The per-column zone maps of one [VortexWriter], written at close when
/// [WriteOptions#enableZoneMaps()] is set.
///
/// With the zoned layout (Rust's `vortex.zoned`, edition `core2026.08.0` on) each column's stats
/// accumulate over fixed 8192-row zones fed from the raw batches, independent of chunking; otherwise
/// the legacy `vortex.stats` zone map holds one zone per written chunk.
final class ZoneMapWriter {

    private final DType.Struct schema;
    private final WriteOptions options;
    private final boolean zonedLayout;
    private final SegmentEncoder encoder;
    private final SegmentSink sink;
    // Per-column zone-maps, populated by flush() in close().
    private final Map<ColumnName, ZoneMapRef> zoneMaps;
    private final Map<ColumnName, ZoneAccumulator> zoneAccumulators;
    // Each column's zone stats accumulate on the executor, chained so a column's batches still
    // arrive in order; flush waits for every chain.
    private final Map<ColumnName, CompletableFuture<Void>> zoneTails;

    ZoneMapWriter(DType.Struct schema, WriteOptions options, boolean zonedLayout, SegmentEncoder encoder,
            SegmentSink sink) {
        this.schema = schema;
        this.options = options;
        this.zonedLayout = zonedLayout;
        this.encoder = encoder;
        this.sink = sink;
        this.zoneMaps = new LinkedHashMap<>();
        this.zoneAccumulators = new LinkedHashMap<>();
        this.zoneTails = new LinkedHashMap<>();
    }

    /// Feeds one batch of a column to its zoned accumulator, on the executor; a no-op for the legacy
    /// zone map, which reads the written chunks instead.
    void add(ColumnName colName, DType colDtype, Object batch) {
        if (!zonedLayout) {
            return;
        }
        ZoneAccumulator acc = zoneAccumulators.computeIfAbsent(colName, _ -> new ZoneAccumulator(colDtype));
        zoneTails.put(colName, zoneTails.getOrDefault(colName, CompletableFuture.completedFuture(null))
                .thenRunAsync(() -> acc.add(batch, RowCount.of(batch)), options.executor()));
    }

    /// Writes every column's zone map. The legacy `vortex.stats` one is one zone per chunk: from each
    /// chunk's segment stats for `colChunks`, from the per-batch stats for the `dicts` columns. Must
    /// run before the sink finishes, so the stats-table segments are present in `segment_specs`.
    void flush(Map<ColumnName, List<ChunkRef>> colChunks, Map<ColumnName, List<DictColRef>> dicts)
            throws IOException {
        if (!options.enableZoneMaps()) {
            return;
        }
        // The legacy zone map below reads each chunk's stats off its written segment.
        sink.drain();
        if (zonedLayout) {
            joinZoneTails();
            for (Map.Entry<ColumnName, ZoneAccumulator> e : zoneAccumulators.entrySet()) {
                ZoneAccumulator acc = e.getValue();
                acc.finish();
                if (acc.zoneCount() == 0) {
                    continue;
                }
                int zonesSegIdx = sink.write(acc.tableDtype(), acc.tableData(), encoder.zoneTableEncoder(), Set.of());
                zoneMaps.put(e.getKey(), new ZoneMapRef(zonesSegIdx, acc.zoneCount(), acc.metadata()));
            }
            return;
        }
        for (Map.Entry<ColumnName, List<ChunkRef>> e : colChunks.entrySet()) {
            ColumnName colName = e.getKey();
            List<ChunkRef> chunks = e.getValue();
            if (chunks.isEmpty()) {
                continue;
            }
            DType colDtype = columnDtype(colName);
            DType minMaxDtype = ZoneMapStatCodec.zoneMinMaxDtype(colDtype);
            DType sumDtype = ZoneMapStatCodec.zoneSumDtype(colDtype);
            long[] nullCounts = new long[chunks.size()];
            List<SegmentStats> stats = chunks.stream().map(c -> sink.stats(c.segIdx())).toList();
            for (int i = 0; i < chunks.size(); i++) {
                nullCounts[i] = stats.get(i).nullCount();
            }
            emitZoneMap(colName, minMaxDtype,
                    stats.stream().map(SegmentStats::min).toList(),
                    stats.stream().map(SegmentStats::max).toList(),
                    sumDtype, stats.stream().map(SegmentStats::sum).toList(),
                    nullCounts, chunks.stream().mapToLong(ChunkRef::rowCount).toArray());
        }
        // Dict-encoded columns (one zone per code chunk). MIN/MAX/SUM come from each chunk's logical
        // values (computed at dict-build time); NULL_COUNT always. Matches Rust, whose zone-map
        // stats are computed on the logical column dtype, independent of the dict encoding.
        for (Map.Entry<ColumnName, List<DictColRef>> e : dicts.entrySet()) {
            DType colDtype = columnDtype(e.getKey());
            DType minMaxDtype = ZoneMapStatCodec.zoneMinMaxDtype(colDtype);
            List<ProtoScalarValue> mins = new ArrayList<>();
            List<ProtoScalarValue> maxs = new ArrayList<>();
            List<ProtoScalarValue> sums = new ArrayList<>();
            List<Long> nulls = new ArrayList<>();
            List<Long> rows = new ArrayList<>();
            for (DictColRef ref : e.getValue()) {
                mins.addAll(ref.chunkStatsMin());
                maxs.addAll(ref.chunkStatsMax());
                sums.addAll(ref.chunkStatsSum());
                nulls.addAll(ref.chunkNullCounts());
                rows.addAll(ref.chunkRowCounts());
            }
            emitZoneMap(e.getKey(), minMaxDtype, mins, maxs, ZoneMapStatCodec.zoneSumDtype(colDtype), sums,
                    nulls.stream().mapToLong(Long::longValue).toArray(),
                    rows.stream().mapToLong(Long::longValue).toArray());
        }
    }

    /// The zone maps [#flush] wrote, per column.
    Map<ColumnName, ZoneMapRef> written() {
        return Collections.unmodifiableMap(zoneMaps);
    }

    private void joinZoneTails() throws IOException {
        try {
            CompletableFuture.allOf(zoneTails.values().toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException e) {
            throw SegmentSink.rethrowCause(e);
        }
    }

    private DType columnDtype(ColumnName colName) {
        return schema.fieldTypes().get(schema.fieldNames().indexOf(colName));
    }

    /// Writes one `vortex.stats` zone-map for `colName`: one zone per chunk, with NULL_COUNT always,
    /// MAX/MIN (plus always-false `_is_truncated` flags) when `minMaxDtype` is non-null, and SUM when
    /// `sumDtype` is non-null. `minBytes`/`maxBytes`/`sumBytes` hold each zone's serialized scalar —
    /// read only when the matching dtype is set; a `null` entry marks that specific zone's stat as
    /// unknown (e.g. an all-null chunk, an overflowed sum, or an encoder that does not surface a
    /// min/max) rather than dropping the stat for the whole column — MIN/MAX/SUM are nullable per
    /// zone, matching Rust. Field/bit order follows ZonedStatsSchema: MAX(3), MIN(4), SUM(5),
    /// NULL_COUNT(6).
    private void emitZoneMap(ColumnName colName, DType minMaxDtype, List<ProtoScalarValue> minBytes, List<ProtoScalarValue> maxBytes,
                             DType sumDtype, List<ProtoScalarValue> sumBytes, long[] nullCounts,
                             long[] rowCounts) throws IOException {
        int nZones = nullCounts.length;
        boolean[] allValid = new boolean[nZones];
        Arrays.fill(allValid, true);

        List<String> names = new ArrayList<>();
        List<DType> types = new ArrayList<>();
        List<Object> fields = new ArrayList<>();
        if (minMaxDtype != null) {
            boolean[] notTruncated = new boolean[nZones];
            boolean[] maxValid = new boolean[nZones];
            Object maxValues = ZoneMapStatCodec.zoneStatValues(minMaxDtype, maxBytes, maxValid);
            names.add("max");
            types.add(minMaxDtype);
            fields.add(new NullableData(maxValues, maxValid));
            names.add("max_is_truncated");
            types.add(DTypes.BOOL);
            fields.add(notTruncated);
            boolean[] minValid = new boolean[nZones];
            Object minValues = ZoneMapStatCodec.zoneStatValues(minMaxDtype, minBytes, minValid);
            names.add("min");
            types.add(minMaxDtype);
            fields.add(new NullableData(minValues, minValid));
            names.add("min_is_truncated");
            types.add(DTypes.BOOL);
            fields.add(notTruncated.clone());
        }
        if (sumDtype != null) {
            boolean[] sumValid = new boolean[nZones];
            Object sumArr = ZoneMapStatCodec.sumColumn(sumDtype, sumBytes, sumValid);
            names.add("sum");
            types.add(sumDtype);
            fields.add(new NullableData(sumArr, sumValid));
        }
        names.add("null_count");
        types.add(new DType.Primitive(PType.U64, true));
        fields.add(new NullableData(nullCounts, allValid.clone()));

        DType.Struct statsDtype = new DType.Struct(
                names.stream().map(ColumnName::of).toList(), List.copyOf(types), false);
        int zonesSegIdx = sink.write(statsDtype, new StructData(fields), new StructEncodingEncoder(), Set.of());
        zoneMaps.put(colName,
                new ZoneMapRef(zonesSegIdx, nZones, ZoneMapStatCodec.zonedMetadataBytes(
                        ZoneMapStatCodec.uniformZoneLength(rowCounts), minMaxDtype != null, sumDtype != null)));
    }

    /// Per-column zone-map: the flat segment holding the per-zone stats table, the zone count, and
    /// the zoned layout's metadata.
    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    record ZoneMapRef(int zonesSegIdx, long nZones, byte[] metadata) {
    }
}
