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

  **Suggested module:** `it/` Maven module, `test`-scoped dep on `writer` + JNI artifact, disabled by default (`-Pintegration` profile). Fixture: primitive int/float columns, multiple chunks.
