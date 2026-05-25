# TODO

## Blocked by: FlatBuffer codegen (#1)

- [x] **#1 Generate FlatBuffer + Protobuf sources** ✓
  - Schemas in `core/src/main/flatbuffers/` and `core/src/main/proto/`
  - `./mvnw verify` runs `flatc` + `protoc` → `target/generated-sources/`
  - Requires: `brew install flatbuffers protobuf`

- [ ] **#2 Implement `PostscriptParser.parse()`** _(needs #1)_
  - Parse postscript FlatBuffer → 4 `PostscriptSegment` entries (dtype, layout, statistics, footer)
  - Each segment: `offset(u64)`, `length(u32)`, `alignment_exponent(u8)`, compression, encryption
  - Footer FlatBuffer → `array_specs[]`, `layout_specs[]`, `segment_specs[]`, `compression_specs[]`
  - DType Protobuf → `DType` sealed interface tree
  - Layout FlatBuffer → `Layout` record tree

- [ ] **#4 Implement `VortexWriter.writeChunk()` + `close()`** _(needs #1)_
  - `writeChunk`: encode per column (int→FoR+BitPack, float→Pcodec, string→Dict/VarBinView, bool→bit-packed), write segments, accumulate `SegmentSpec` + zone-map stats
  - `close`: build Layout tree, serialize Footer+DType+Layout blobs, write postscript + trailer (`version u16 LE | postscriptLen u16 LE | VTXF`)

## Blocked by: PostscriptParser (#2)

- [ ] **#3 Implement `ScanIterator.hasNext()` / `next()`** _(needs #2)_
  - Walk Layout tree: Struct → per-column subtrees
  - Apply column projection, zone-map pruning (`RowFilter` vs `ArrayStats` min/max), limit
  - Flat node: `VortexFile.slice` → decompress → parse `ArrayNode` → `DecoderRegistry.decode`

## Independent

- [ ] **#5 Add unit tests**
  - `core`: `DType` pattern matching, `PType.byteSize()`, `CompressionScheme.of()` round-trip, `DecoderRegistry` dispatch, `Array` construction
  - `reader`: ~~magic/trailer validation~~ ✓ done — `VortexFileTest` (17 tests); fixtures from `s3://vortex-compat-fixtures` v0.72.0: primitives, booleans, null, varbin, chunked; `RowFilter` zone-map evaluation against known `ArrayStats`
  - `writer`: `WriteOptions` defaults; round-trip write→read once #2+#4 done
