# TODO

## Done

- [x] **#1 Generate FlatBuffer + Protobuf sources**
- [x] **#2 Implement `PostscriptParser.parse()`**
- [x] **#3 Implement `ScanIterator.hasNext()` / `next()`** (layout traversal + flat decode; zone-map pruning pending)
- [x] **#4 Implement `VortexWriter.writeChunk()` + `close()`** (primitive + bool encodings, full file format)
- [x] **#5 Round-trip unit tests** (`VortexWriterTest`: 5 tests; `VortexFileTest`: 17 tests)

## Open

- [ ] **#6 Zone-map pruning in `ScanIterator`**
  - Apply `options.rowFilter()` against `vortex.stats` segments during `hasNext()`
  - Skip chunks where min/max stats exclude the predicate range

- [ ] **#7 Additional encodings**
  - `fastlanes.bitpacked` — integer bit-packing
  - `fastlanes.delta` — delta encoding for monotonic sequences
  - `dict` — dictionary encoding for low-cardinality columns
  - `pcodec` — float compression

## Cross-compatibility (blocked by: JNI bindings)

- [ ] **#8 Rust writes → Java reads**
  - Use JNI vortex writer to produce a `.vtx` file
  - Read with `VortexFile` + `ScanIterator`, assert decoded values match input

- [ ] **#9 Java writes → Rust reads**
  - Use `VortexWriter` to produce a `.vtx` file
  - Decode with JNI reader, assert decoded values match input

  **Module:** `integration/` — activate with `-Pintegration`. Tests `@Disabled` until JNI artifact coordinates are known.

## Performance (blocked by: JNI bindings for comparison baseline)

- [ ] **#10 Write-speed benchmark** (`performance/` module, `-Pperformance`)
  - JMH: `VortexWriter` throughput (rows/s, MB/s) vs JNI writer
  - Fixture: 1M rows, int64 + float64 columns; single-chunk and multi-chunk variants
  - Java-only half already runnable; JNI half stubbed until bindings available

- [ ] **#11 Read-speed benchmark** (`performance/` module, `-Pperformance`)
  - JMH: `VortexFile` + `ScanIterator` throughput vs JNI reader
  - Same fixture as #10; full-scan and projected-column variants
