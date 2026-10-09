# Changelog

All notable changes to **vortex-java** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `WriteOptions#withExecutor(Executor)` compresses columns and chunks concurrently (4.4× on 8 threads). ([#474](https://github.com/dfa1/vortex-java/issues/474))

### Changed
- `vortex.onpair` strings decode up to 36% faster (Raincloud full scans: BI `corporations` 14.0 → 19.0 scans/s, TPC-H `orders` 69.6 → 85.9): each token is one fixed 16-byte over-copy, as Rust's `onpair` decoder does, and dictionaries are validated as Rust validates them (tokens 1–16 bytes, read padding after the last one) ([#498](https://github.com/dfa1/vortex-java/pull/498)).
- Full scans of real files are up to 6× faster (Raincloud, all columns: TPC-DS `store_sales` 0.95 → 5.88 scans/s, IMDB `title` 4.4 → 19.6, green taxi 2025 11.6 → 30.8): run-end, sparse, dictionary and date-time-parts columns decode sequentially, as Rust does, instead of a binary search per row ([#497](https://github.com/dfa1/vortex-java/pull/497)).
- Dictionary decode validates its codes 2.3–3.6× faster. ([#484](https://github.com/dfa1/vortex-java/issues/484))
- Bit-packing is about 2.3× faster. ([#484](https://github.com/dfa1/vortex-java/issues/484))
- Delta encode and decode are up to 2× faster. ([#484](https://github.com/dfa1/vortex-java/issues/484))
- `PrimitiveArrays.fromLongs` narrows about 13× faster. ([#484](https://github.com/dfa1/vortex-java/issues/484))
- Cascading writes are about 9% faster and allocate 11% less. ([#476](https://github.com/dfa1/vortex-java/issues/476))
- **Breaking:** `WriteOptions` is a final class instead of a record. ([#474](https://github.com/dfa1/vortex-java/issues/474))

## [0.16.0] — 2026-10-09

### Changed
- Bit-packing encodes about 45% faster. ([#475](https://github.com/dfa1/vortex-java/issues/475))
- The writer repartitions columns into ~1 MB chunks of 8192-row blocks, as Rust does. ([#470](https://github.com/dfa1/vortex-java/issues/470))
- ALP picks its exponents as Rust's `alp` does. ([#469](https://github.com/dfa1/vortex-java/pull/469))
- Nullable low-cardinality global-dictionary columns store null as a dictionary entry, as Rust does. ([#467](https://github.com/dfa1/vortex-java/pull/467), [#468](https://github.com/dfa1/vortex-java/pull/468))
- Cascading writes are about 35% faster and allocate half as much. ([#465](https://github.com/dfa1/vortex-java/pull/465))
- **Breaking:** `EncodingEncoder#expectedRatio` takes `(DType, ArrayAndStats, EncodeContext)`. ([#464](https://github.com/dfa1/vortex-java/pull/464), [#466](https://github.com/dfa1/vortex-java/pull/466))
- Default writes emit Rust's `vortex.zoned` zone map instead of the legacy `vortex.stats`. ([#447](https://github.com/dfa1/vortex-java/issues/447))
- `WriteOptions.defaults()` cascades up to depth 3, as Rust's writer does. ([#458](https://github.com/dfa1/vortex-java/issues/458))
- **Breaking:** the edition catalog mirrors Rust 0.86.1. ([#441](https://github.com/dfa1/vortex-java/issues/441))
- Default writes target the `core2026.08.3` edition. ([#441](https://github.com/dfa1/vortex-java/issues/441))
- `WriteOptions.withoutEditions()` emits `fastlanes.delta` and `vortex.patched`. ([#441](https://github.com/dfa1/vortex-java/issues/441))
- Nullable low-cardinality string columns dict-encoded per chunk are about 20% smaller. ([2091beb](https://github.com/dfa1/vortex-java/commit/2091beb3))

### Added
- Read Rust's `DType::Union` and `vortex.union`. ([#442](https://github.com/dfa1/vortex-java/issues/442))
- `WriteOptions.withColumnEncoding(column, ColumnEncoding.candidates(...))` restricts a column's encodings, writing about 3.5× faster. ([#461](https://github.com/dfa1/vortex-java/issues/461))
- Read `vortex.parquet.variant`. ([#445](https://github.com/dfa1/vortex-java/issues/445))
- Read `vortex.zstd_buffers`. ([#444](https://github.com/dfa1/vortex-java/issues/444))

### Fixed
- Global dictionaries assign codes in first-seen order, as Rust does. ([#471](https://github.com/dfa1/vortex-java/issues/471))
- Global-dict layouts write `all_values_referenced = false`, as Rust does. ([#473](https://github.com/dfa1/vortex-java/issues/473))
- Float arithmetic sequences are no longer written as `vortex.sequence`, which Rust rejects. ([#472](https://github.com/dfa1/vortex-java/issues/472))
- Integer columns with rare negatives are no longer bit-packed at full width (NYC taxi 60 → 42 MB). ([#466](https://github.com/dfa1/vortex-java/pull/466))
- Batches above ~100 000 rows no longer compress worse than smaller ones. ([#458](https://github.com/dfa1/vortex-java/issues/458))
- `VortexReader#columnStats()` reports min/max for string columns of Rust-written files. ([#447](https://github.com/dfa1/vortex-java/issues/447))
- Zone maps of Rust-written dictionary columns are no longer ignored. ([#447](https://github.com/dfa1/vortex-java/issues/447))
- Filtered scans of Rust-written files no longer skip strings beyond the Basic Multilingual Plane. ([#458](https://github.com/dfa1/vortex-java/issues/458))
- A float column whose first value is NaN no longer records NaN as its min and max. ([#458](https://github.com/dfa1/vortex-java/issues/458))
- File-level `U64` min/max no longer merge values at or above 2^63 as negative. ([#458](https://github.com/dfa1/vortex-java/issues/458))
- Filtered scans prune string and binary columns of Rust-written files. ([#446](https://github.com/dfa1/vortex-java/issues/446))
- Null rows of Rust-written `vortex.varbin` columns no longer read as empty values. ([#444](https://github.com/dfa1/vortex-java/issues/444))
- A corrupt `vortex.zstd` frame fails as `VortexException`. ([#444](https://github.com/dfa1/vortex-java/issues/444))
- Dictionary-encoded Binary columns no longer fail with `ClassCastException`. ([#445](https://github.com/dfa1/vortex-java/issues/445))
- Null rows of Rust-written `vortex.list` columns no longer read as empty lists. ([c27b37ac](https://github.com/dfa1/vortex-java/commit/c27b37ac))
- CSV export handles list-view columns. ([c27b37ac](https://github.com/dfa1/vortex-java/commit/c27b37ac))
- `vortex inspect --html` groups chunks correctly when columns chunk differently. ([6077211c](https://github.com/dfa1/vortex-java/commit/6077211c))
- `vortex.patched` columns of F16 values read. ([#449](https://github.com/dfa1/vortex-java/pull/449))

## [0.15.2] — 2026-10-03

### Added
- `VortexWriter` writes decimal columns. ([#434](https://github.com/dfa1/vortex-java/pull/434))
- Read and write `vortex.onpair`. ([#425](https://github.com/dfa1/vortex-java/issues/425))

### Changed
- Low-cardinality `Binary` columns are written as a global dictionary, as Rust does. ([#443](https://github.com/dfa1/vortex-java/pull/443))
- Cascading writes offer `vortex.zigzag` and `fastlanes.rle`, with Rust's exclusions. ([#436](https://github.com/dfa1/vortex-java/pull/436), [#437](https://github.com/dfa1/vortex-java/pull/437))
- Cascading writes can pick `fastlanes.delta` under an unstable edition. ([#439](https://github.com/dfa1/vortex-java/pull/439))
- The writer run-length encodes float columns. ([#438](https://github.com/dfa1/vortex-java/pull/438))
- Cascading writes compress run-end children further. ([#435](https://github.com/dfa1/vortex-java/pull/435))

### Fixed
- Reading Rust-written decimal columns accepts precision 1–76 and negative scales. ([#433](https://github.com/dfa1/vortex-java/pull/433))
- CSV export handles decimal columns. ([#430](https://github.com/dfa1/vortex-java/pull/430))
- Nullable `vortex.decimal_byte_parts` columns keep their nulls. ([#431](https://github.com/dfa1/vortex-java/pull/431))
- Bool arrays whose bits start mid-byte read correctly. ([#429](https://github.com/dfa1/vortex-java/pull/429))
- The published BOM manages only vortex-java modules and the zstd bindings. ([#427](https://github.com/dfa1/vortex-java/pull/427))
- vortex-jni no longer aborts the JVM on FSST chunks that trained no symbols. ([#425](https://github.com/dfa1/vortex-java/issues/425))
- Full scans of the TPC-H compat fixtures no longer fail slicing decimal, list-view or map columns. ([#424](https://github.com/dfa1/vortex-java/pull/424))
- `vortex inspect --html` lines schema and chunk rows up on a shared grid. ([#423](https://github.com/dfa1/vortex-java/pull/423))

## [0.15.1] — 2026-10-01

### Fixed
- `vortex inspect` shows min/max for Rust-written files. ([#416](https://github.com/dfa1/vortex-java/issues/416))
- `vortex inspect --html` shows value ranges, not code ranges, for dictionary columns. ([#416](https://github.com/dfa1/vortex-java/issues/416))
- vortex-jni no longer aborts the JVM on filtered or row-range reads of vortex-java files. ([#418](https://github.com/dfa1/vortex-java/issues/418))
- Filtered vortex-jni reads of zone-mapped vortex-java files no longer return too few rows. ([#418](https://github.com/dfa1/vortex-java/issues/418))

### Changed
- **Breaking:** removed `WriteOptions#chunkSize`. ([#418](https://github.com/dfa1/vortex-java/issues/418))
- **Breaking (custom encoders):** `EncodeResult` buffers are `EncodedBuffer`s carrying their alignment. ([#418](https://github.com/dfa1/vortex-java/issues/418))

### Added
- `ScanIterator#columnZones(String)` returns each zone-map row with the column rows it covers. ([#416](https://github.com/dfa1/vortex-java/issues/416))

## [0.15.0] — 2026-09-30

### Added
- `inspect --html` names each column's encoding and shows its compression ratio. ([2ee03bc](https://github.com/dfa1/vortex-java/commit/2ee03bc6))
- `inspect --html` writes a self-contained HTML report of a file. ([d62af4f](https://github.com/dfa1/vortex-java/commit/d62af4f9))
- `MIN`/`MAX` over a `VARCHAR` column push down to zone-map stats. ([#406](https://github.com/dfa1/vortex-java/issues/406))

### Changed
- A `vortex.timestamp` column competes as `vortex.datetimeparts`, shrinking timestamp columns. ([#417](https://github.com/dfa1/vortex-java/issues/417))
- **Breaking (custom encodings only):** `ChildSlot` declares its own exclusions. ([#410](https://github.com/dfa1/vortex-java/issues/410))
- The cascade samples ~1% of a chunk with a 1024-row floor, as Rust does, writing about a third faster. ([#410](https://github.com/dfa1/vortex-java/issues/410))
- The write path counts distinct values without boxing. ([#407](https://github.com/dfa1/vortex-java/issues/407), [#408](https://github.com/dfa1/vortex-java/issues/408), [#410](https://github.com/dfa1/vortex-java/issues/410))
- `RunEndEncodingEncoder` cuts write garbage by 26%. ([#407](https://github.com/dfa1/vortex-java/issues/407))
- Scanning a run-end column walks runs instead of binary-searching per row. ([#408](https://github.com/dfa1/vortex-java/issues/408))

### Fixed
- `inspect --html` lists all columns of a struct stored under a single flat layout node. ([0b3f84f](https://github.com/dfa1/vortex-java/commit/0b3f84fa))
- `vortex.varbinview`, `vortex.zstd`, `fastlanes.rle`, `vortex.pco`, `vortex.sparse` and `vortex.patched` write MIN/MAX zone-map stats. ([#417](https://github.com/dfa1/vortex-java/issues/417))
- `vortex.datetimeparts` writes MIN/MAX zone-map stats. ([#417](https://github.com/dfa1/vortex-java/issues/417))
- `vortex.ext` writes MIN/MAX zone-map stats. ([#417](https://github.com/dfa1/vortex-java/issues/417))
- `inspect --html` renders timestamp and date bounds as instants and dates. ([c949ec7](https://github.com/dfa1/vortex-java/commit/c949ec7f))
- `inspect --html` reports an unencoded size and ratio for extension columns. ([efe4f22](https://github.com/dfa1/vortex-java/commit/efe4f22c))
- `vortex.fsst` writes MIN/MAX zone-map stats. ([#417](https://github.com/dfa1/vortex-java/issues/417))
- `vortex.dict` on a primitive column writes the metadata and child order Rust expects. ([#410](https://github.com/dfa1/vortex-java/issues/410))
- `VortexReader#columnStats()` reports MIN/MAX/null count for global-dictionary columns. ([#409](https://github.com/dfa1/vortex-java/issues/409))
- Zone-map chunk pruning works for global-dictionary columns. ([#409](https://github.com/dfa1/vortex-java/issues/409))
- Zone-map chunk pruning no longer prunes chunks whose rows are non-null under a wider zone. ([#409](https://github.com/dfa1/vortex-java/issues/409))
- Floating-point `WHERE` filters no longer let `NaN` satisfy an ordering comparison. ([#406](https://github.com/dfa1/vortex-java/issues/406))

## [0.14.4] — 2026-09-25

### Fixed
- `vortex.listview` accepts a validity child under a non-nullable dtype. ([390fbb9](https://github.com/dfa1/vortex-java/commit/390fbb99))

## [0.14.3] — 2026-09-19

### Changed
- FSST trains and compresses from contiguous row bytes, cutting the live set during a write. ([997ecfe](https://github.com/dfa1/vortex-java/commit/997ecfef))
- FSST's matcher skips symbol probes where no multi-byte symbol can start. ([60c3a5a](https://github.com/dfa1/vortex-java/commit/60c3a5a2))
- `DictEncodingEncoder` abandons its `Utf8` dictionary scan as soon as dict cannot win. ([fa28198](https://github.com/dfa1/vortex-java/commit/fa281986))
- `ArrayStats` counts distinct values without boxing. ([75506bb](https://github.com/dfa1/vortex-java/commit/75506bb9))

## [0.14.2] — 2026-09-13

### Fixed
- Zone-map `MIN`/`MAX` stats are per-zone nullable, so one stats-less chunk no longer disables pruning. ([#378](https://github.com/dfa1/vortex-java/issues/378))
- Encoders write MIN/MAX zone-map stats for the remaining encodings. ([#379](https://github.com/dfa1/vortex-java/issues/379), [#382](https://github.com/dfa1/vortex-java/issues/382), [#384](https://github.com/dfa1/vortex-java/issues/384), [#385](https://github.com/dfa1/vortex-java/issues/385), [#386](https://github.com/dfa1/vortex-java/issues/386), [#387](https://github.com/dfa1/vortex-java/pull/387))
- `ScanIterator`'s zone-map pruning reads the compact stats table instead of each chunk's data. ([#380](https://github.com/dfa1/vortex-java/issues/380))
- `MaskedEncodingEncoder` computes `MIN`/`MAX` from valid rows only. ([#381](https://github.com/dfa1/vortex-java/pull/381))

## [0.14.1] — 2026-09-06

### Fixed
- `WriteOptions.withZstd(true)` throws `IllegalArgumentException` when cascading is `0`. ([#366](https://github.com/dfa1/vortex-java/pull/366))

## [0.14.0] — 2026-09-03

### Added
- `ParquetExporter` writes a Vortex file to Parquet. ([#362](https://github.com/dfa1/vortex-java/pull/362))
- `CsvImporter.importCsv(URI, Path)` imports a CSV file over HTTP(S). ([#363](https://github.com/dfa1/vortex-java/pull/363))
- Parquet import and export work against remote sources over HTTP(S). ([#362](https://github.com/dfa1/vortex-java/pull/362))

### Changed
- `hardwood-core` 1.0.0.Final → 1.1.0.Beta1 speeds up `ParquetImporter`. ([e66fb6eb](https://github.com/dfa1/vortex-java/commit/e66fb6eb))

## [0.13.4] — 2026-09-01

### Fixed
- `FsstEncodingEncoder`, `VarBinViewEncodingEncoder` and `ZstdEncodingEncoder` handle `DType.Binary`. ([#352](https://github.com/dfa1/vortex-java/issues/352))

### Changed
- `vortex-jni` 0.84.0 → 0.85.0; Rust-written files no longer get a pushed-down zone `SUM`. ([#360](https://github.com/dfa1/vortex-java/pull/360))

## [0.13.3] — 2026-08-15

### Added
- `ParquetImporter` imports nested `LIST`/`STRUCT` schemas. ([f0e7d3ba](https://github.com/dfa1/vortex-java/commit/f0e7d3ba))
- `DType.Map` and the `vortex.map` encoding, read and write. ([#351](https://github.com/dfa1/vortex-java/issues/351))

### Changed
- `fastlanes.rle` reads its run values and indices in place. ([#342](https://github.com/dfa1/vortex-java/issues/342))
- `WriteOptions` defaults to the `core2026.08.0` edition. ([#351](https://github.com/dfa1/vortex-java/issues/351))

### Fixed
- `ParquetImporter` fails with a clear `IllegalArgumentException` on data that violates its schema. ([071328a2](https://github.com/dfa1/vortex-java/commit/071328a2))
- `vortex.alp` no longer turns `-0.0` into `0.0`. ([ca33e8a8](https://github.com/dfa1/vortex-java/commit/ca33e8a8))
- `ParquetImporter` no longer turns null rows of nullable numeric and boolean columns into zeros. ([b4638373](https://github.com/dfa1/vortex-java/commit/b4638373))
- `vortex.list` columns with a nullable element type no longer throw `ClassCastException`. ([ddfd2737](https://github.com/dfa1/vortex-java/commit/ddfd2737), [8c8f4ea3](https://github.com/dfa1/vortex-java/commit/8c8f4ea3))
- `VortexWriter` writes `DType.Binary` columns. ([1cf5499d](https://github.com/dfa1/vortex-java/commit/1cf5499d))
- Scanning a file whose only projected column is a `Struct` keeps the column's name. ([d4f71c5a](https://github.com/dfa1/vortex-java/commit/d4f71c5a))
- `vortex.listview` keeps its validity bitmap on decode. ([#351](https://github.com/dfa1/vortex-java/issues/351))
- The JMH benchmarks compile against vortex-jni 0.84.0. ([#351](https://github.com/dfa1/vortex-java/issues/351))
- A malformed `fastlanes.rle` column fails as `VortexException`. ([#342](https://github.com/dfa1/vortex-java/issues/342))
- `vortex.dict` layouts over StringView or `vortex.constant` pools no longer expand every row or drop validity. ([#341](https://github.com/dfa1/vortex-java/issues/341))
- `vortex.dict` layouts no longer allocate against a values pool's claimed length. ([#341](https://github.com/dfa1/vortex-java/issues/341))
- `vortex.dict` layouts over unsupported pools fail as `VortexException`. ([#341](https://github.com/dfa1/vortex-java/issues/341))

## [0.13.2] — 2026-08-07

### Fixed
- `vortex.bytebool` is read in place from the mmapped buffer. ([#339](https://github.com/dfa1/vortex-java/issues/339))
- A short `vortex.bytebool` buffer fails as `VortexException`. ([#339](https://github.com/dfa1/vortex-java/issues/339))
- A short `vortex.bool` bitmap fails as `VortexException`. ([#339](https://github.com/dfa1/vortex-java/issues/339))
- A malformed `fastlanes.delta` column fails as `VortexException`. ([#338](https://github.com/dfa1/vortex-java/issues/338))
- `fastlanes.delta` decodes at the column's own width and only the requested chunks. ([#338](https://github.com/dfa1/vortex-java/issues/338))
- `vortex.runend` Utf8/Binary columns decode lazily. ([#334](https://github.com/dfa1/vortex-java/issues/334))
- `vortex.sequence` columns decode lazily. ([#335](https://github.com/dfa1/vortex-java/issues/335))
- Primitive `vortex.dict` columns decode lazily through the encoding path. ([#336](https://github.com/dfa1/vortex-java/issues/336))
- `vortex.patched` columns with no patches no longer copy their inner child. ([#337](https://github.com/dfa1/vortex-java/issues/337))
- `vortex.sparse` Utf8/Binary columns keep their non-null fill value. ([#340](https://github.com/dfa1/vortex-java/issues/340))
- `vortex.sparse` Utf8/Binary columns decode lazily. ([#340](https://github.com/dfa1/vortex-java/issues/340))

## [0.13.1] — 2026-08-06

### Fixed
- Malformed VarBin, Dict, Bitpacked, ALP, Sparse, Chunked and Struct columns fail as `VortexException`. ([ef982992](https://github.com/dfa1/vortex-java/commit/ef982992))
- Malformed RunEnd, Constant, zone-map stats and Pco columns fail as `VortexException`. ([12d7466c](https://github.com/dfa1/vortex-java/commit/12d7466c))
- `vortex.constant` Utf8/Binary columns broadcast lazily. ([987fe412](https://github.com/dfa1/vortex-java/commit/987fe412))

### Changed
- **Breaking:** `VarBinArray`'s representations are top-level classes. ([7e0d6e75](https://github.com/dfa1/vortex-java/commit/7e0d6e75))

### Added
- `MemorySize` domain primitive in `core.model`. ([#321](https://github.com/dfa1/vortex-java/issues/321))
- Opt-in Jazzer fuzz target for `VortexReader`. ([ce7ee357](https://github.com/dfa1/vortex-java/commit/ce7ee357))

## [0.13.0] — 2026-08-04

### Added
- Vortex editions: `WriteOptions` gates which encodings a write may emit. ([#301](https://github.com/dfa1/vortex-java/issues/301))

## [0.12.5] — 2026-07-26

### Added
- ALP-RD (`vortex.alprd`) competes in the cascade for high-precision floats. ([#307](https://github.com/dfa1/vortex-java/pull/307), [#308](https://github.com/dfa1/vortex-java/pull/308))

### Changed
- Default Parquet-import chunk size is 65536 rows. ([fbedbf03](https://github.com/dfa1/vortex-java/commit/fbedbf03))
- Default global-dictionary retained-memory budget is 2 GB. ([#306](https://github.com/dfa1/vortex-java/pull/306))

### Fixed
- `inspect` lists encodings nested inside another encoding. ([#298](https://github.com/dfa1/vortex-java/issues/298))
- `AlpEncodingEncoder` no longer divides by zero on an empty array. ([872b0554](https://github.com/dfa1/vortex-java/commit/872b0554))

### Performance
- `vortex.fsst` length and code-offset children are bitpacked or constant-folded. ([#302](https://github.com/dfa1/vortex-java/pull/302))
- `vortex.dict` Utf8 codes are bitpacked. ([#305](https://github.com/dfa1/vortex-java/pull/305))
- Utf8 columns up to 32768 distinct values share one global dictionary. ([d83d1b93](https://github.com/dfa1/vortex-java/commit/d83d1b93), [3f0efa66](https://github.com/dfa1/vortex-java/commit/3f0efa66), [6b9d1013](https://github.com/dfa1/vortex-java/commit/6b9d1013))

## [0.12.4] — 2026-07-24

### Performance
- `vortex.fsst` rewritten as a standalone `vortex-fsst` module: encode ~21.7× and decode ~5.2× faster. ([1b9714c7](https://github.com/dfa1/vortex-java/commit/1b9714c7))
- `vortex.fsst` hot paths reach `vortex-jni` parity on encode and decode. ([#300](https://github.com/dfa1/vortex-java/pull/300))

### Fixed
- `ParquetImporter` reports duplicate column names clearly. ([c148393a](https://github.com/dfa1/vortex-java/commit/c148393a))

## [0.12.3] — 2026-07-19

### Added
- `WriteOptions.withGlobalDictMaxRetainedBytes(long)` configures the global-dictionary budget. ([4cbc12dd](https://github.com/dfa1/vortex-java/commit/4cbc12dd))

### Fixed
- `VortexWriter` no longer exhausts the heap buffering global-dictionary candidates. ([fb4b6be0](https://github.com/dfa1/vortex-java/commit/fb4b6be0))
- Chunked `List` columns spanning several flat chunks decode. ([#268](https://github.com/dfa1/vortex-java/issues/268))
- Chunked `Utf8`/`Binary` columns with an all-null chunk decode. ([#269](https://github.com/dfa1/vortex-java/issues/269))
- Chunked `List` columns with an all-null chunk decode. ([#269](https://github.com/dfa1/vortex-java/issues/269))
- Nullable low-cardinality columns share one global dictionary across chunks. ([5fe8b544](https://github.com/dfa1/vortex-java/commit/5fe8b544))
- `MaskedEncodingEncoder` encodes uniform validity as `vortex.constant`. ([ecd47ead](https://github.com/dfa1/vortex-java/commit/ecd47ead))
- `MaskedEncodingEncoder` tries `vortex.sparse` for mixed validity. ([506d036f](https://github.com/dfa1/vortex-java/commit/506d036f))
- `SequenceEncodingEncoder.encodeCascade` no longer throws on data that is not arithmetic. ([506d036f](https://github.com/dfa1/vortex-java/commit/506d036f))
- `ConstantEncodingEncoder` accepts `DType.Bool`. ([5379af66](https://github.com/dfa1/vortex-java/commit/5379af66))
- `RunEndEncodingEncoder` accepts `DType.Bool`. ([5379af66](https://github.com/dfa1/vortex-java/commit/5379af66))
- `fastlanes.rle` accepts `DType.Bool`. ([fe6f132b](https://github.com/dfa1/vortex-java/commit/fe6f132b))
- `Utf8`/`Binary` columns use cost-based encoding selection. ([1bbc3549](https://github.com/dfa1/vortex-java/commit/1bbc3549))
- `FsstEncodingDecoder` accepts empty `vortex.fsst` metadata. ([1bbc3549](https://github.com/dfa1/vortex-java/commit/1bbc3549))
- `FsstEncodingEncoder` writes symbol tables in the order Rust requires. ([95e0dfb4](https://github.com/dfa1/vortex-java/commit/95e0dfb4))

### Performance
- `FsstEncodingEncoder` trains variable-length 1–8 byte symbols. ([95e0dfb4](https://github.com/dfa1/vortex-java/commit/95e0dfb4), [1bbc3549](https://github.com/dfa1/vortex-java/commit/1bbc3549))
- `FsstEncodingEncoder` matches symbols without boxing and trains on a stratified sample. ([4a3fa0a9](https://github.com/dfa1/vortex-java/commit/4a3fa0a9))
- `VortexWriter` buffers global-dictionary candidates by capped cardinality. ([62bb851e](https://github.com/dfa1/vortex-java/commit/62bb851e))

## [0.12.2] — 2026-07-12

### Added
- `CsvExporter` renders `FixedSizeList` and `List` columns as JSON arrays. ([#257](https://github.com/dfa1/vortex-java/issues/257))
- `VortexWriter` encodes `vortex.list` columns by default. ([#257](https://github.com/dfa1/vortex-java/issues/257))

### Changed
- `fbs-gen` strips trailing `_` from generated class names. ([84c912fe](https://github.com/dfa1/vortex-java/commit/84c912fe))
- **Breaking:** `ReadRegistry` and `WriteRegistry` are builder-only. ([#255](https://github.com/dfa1/vortex-java/issues/255))

### Fixed
- `EncodingId.Custom` and `LayoutId.Custom` reject control characters. ([db47dcd1](https://github.com/dfa1/vortex-java/commit/db47dcd1))
- Blank column names are accepted on read and write. ([#255](https://github.com/dfa1/vortex-java/issues/255))
- `VarBinArray.DictMode` reads dict-value offsets in a vectorizable loop. ([#243](https://github.com/dfa1/vortex-java/issues/243))
- `ConstantEncodingDecoder` returns `NullArray` for null-scalar constants. ([#246](https://github.com/dfa1/vortex-java/issues/246))
- `ScanIterator.sliceArray` handles `NullArray`. ([#247](https://github.com/dfa1/vortex-java/issues/247))
- `AlpRdEncodingDecoder` rejects a `left_parts_ptype` other than U16. ([#249](https://github.com/dfa1/vortex-java/issues/249))
- `SparseEncodingDecoder` rejects a child count other than two. ([#250](https://github.com/dfa1/vortex-java/issues/250))
- `DateTimePartsArrays.readLong` zero-extends unsigned children. ([246122a9](https://github.com/dfa1/vortex-java/commit/246122a9))
- `BoolEncodingDecoder` handles nullable `vortex.bool` columns. ([39c1d084](https://github.com/dfa1/vortex-java/commit/39c1d084))
- Nullable Utf8/Binary columns go through the full cascade. ([#258](https://github.com/dfa1/vortex-java/issues/258))
- `ScanIterator` slices shared `ListArray`/`FixedSizeListArray` chunks spanning several windows. ([#265](https://github.com/dfa1/vortex-java/issues/265))
- `CsvExporter` reads `vortex.list` offsets of any integer width. ([#263](https://github.com/dfa1/vortex-java/issues/263))

## [0.12.1] — 2026-07-08

### Fixed
- Per-zone stats from current Rust `vortex.zoned` writers decode again. ([#197](https://github.com/dfa1/vortex-java/pull/197))
- Scans of files whose columns use different chunk grids no longer fail. ([#221](https://github.com/dfa1/vortex-java/issues/221))
- The `vortex.zoned` metadata decoder rejects malformed input as `VortexException`. ([#197](https://github.com/dfa1/vortex-java/pull/197))
- CSV export renders nested struct columns as JSON objects. ([#217](https://github.com/dfa1/vortex-java/issues/217))
- Scanning a struct with a nested struct column no longer fails chunk planning. ([#207](https://github.com/dfa1/vortex-java/issues/207))
- CSV export renders unsigned integer columns unsigned. ([#208](https://github.com/dfa1/vortex-java/issues/208))
- Unsigned integer columns are handled unsigned in the CLI `filter` and remaining consumers. ([#216](https://github.com/dfa1/vortex-java/issues/216))
- `fastlanes.rle` decodes F64/F32 value pools. ([#209](https://github.com/dfa1/vortex-java/issues/209))
- Lazy dict decode covers I8/U8/I16/U16 value columns. ([#206](https://github.com/dfa1/vortex-java/issues/206))
- CSV export handles all-null columns. ([#211](https://github.com/dfa1/vortex-java/issues/211))
- FSST-compressed string dictionaries no longer fail to scan. ([#215](https://github.com/dfa1/vortex-java/issues/215))
- Null rows no longer silently decode as values in wrapper decoders. ([#210](https://github.com/dfa1/vortex-java/issues/210))
- `vortex.runend` propagates nullable run-values' validity. ([#225](https://github.com/dfa1/vortex-java/issues/225))
- `vortex.sparse` propagates nullability. ([#226](https://github.com/dfa1/vortex-java/issues/226))
- `vortex.sparse` over utf8/binary propagates row validity. ([#232](https://github.com/dfa1/vortex-java/issues/232))
- `vortex.datetimeparts` propagates null component rows. ([#235](https://github.com/dfa1/vortex-java/issues/235))
- `fastlanes.alprd` propagates left_parts validity. ([#234](https://github.com/dfa1/vortex-java/issues/234))

### Added
- Real-world conformance suite against the Raincloud corpus. ([#205](https://github.com/dfa1/vortex-java/issues/205))

## [0.12.0] — 2026-07-04

### Added
- `Compute.filteredSum` fuses a filter and a sum into a single scan. ([57d2225b](https://github.com/dfa1/vortex-java/commit/57d2225b))
- `Compute.filteredAggregate` fuses a multi-column `RowFilter` and an aggregate into a single scan. ([2ba54888](https://github.com/dfa1/vortex-java/commit/2ba54888))
- `core.model.ColumnName` validated column-name domain primitive. ([c993a355](https://github.com/dfa1/vortex-java/commit/c993a355), [dca815b9](https://github.com/dfa1/vortex-java/commit/dca815b9), [d3b5b251](https://github.com/dfa1/vortex-java/commit/d3b5b251))
- `core.model.LayoutId` typed layout identity. ([7df3a0db](https://github.com/dfa1/vortex-java/commit/7df3a0db))
- Pluggable layout decode through `LayoutDecoder` and `LayoutRegistry`. ([fc488d04](https://github.com/dfa1/vortex-java/commit/fc488d04), [dd196f17](https://github.com/dfa1/vortex-java/commit/dd196f17))

### Changed
- **Breaking:** column names are typed as `ColumnName`. ([84769863](https://github.com/dfa1/vortex-java/commit/84769863))
- **Breaking:** `ScanOptions.columns()` returns `List<ColumnName>`. ([e478a3a7](https://github.com/dfa1/vortex-java/commit/e478a3a7))
- **Breaking:** `Footer.arraySpecs()` and `layoutSpecs()` return typed ids. ([0dd677ef](https://github.com/dfa1/vortex-java/commit/0dd677ef))
- The CLI uber-jar bundles the zstd native library for all six platforms. ([1983656b](https://github.com/dfa1/vortex-java/commit/1983656b))
- The zstd binding stays an optional two-artifact opt-in. ([ae9f95cc](https://github.com/dfa1/vortex-java/commit/ae9f95cc))
- **Breaking:** the little-endian `ValueLayout` constants moved from `PTypeIO.LE_*` to `VortexFormat.LE_*`. ([c060d34f](https://github.com/dfa1/vortex-java/commit/c060d34f))
- **Breaking:** `Chunk.columns()` returns an order-preserving `SequencedMap<ColumnName, Chunk.Column>`. ([f8ad15d1](https://github.com/dfa1/vortex-java/commit/f8ad15d1))
- `Compute.filteredSum` over a dictionary-encoded filter column is ~20× faster. ([85e251cc](https://github.com/dfa1/vortex-java/commit/85e251cc))
- `Compute.filteredAggregate` over a dictionary-encoded filter column is ~22× faster. ([145791c7](https://github.com/dfa1/vortex-java/commit/145791c7), [6e6d7dd0](https://github.com/dfa1/vortex-java/commit/6e6d7dd0))
- Multi-column `AND` filters keep the dictionary code-scan lane. ([12e13501](https://github.com/dfa1/vortex-java/commit/12e13501))
- **Breaking:** `EncodingId` is a sealed interface. ([ea88a91b](https://github.com/dfa1/vortex-java/commit/ea88a91b))
- **Breaking:** `ArrayNode` is a single record carrying the typed `EncodingId`. ([21810d7e](https://github.com/dfa1/vortex-java/commit/21810d7e))
- **Breaking:** `Layout` and `ZonedStatsSchema` moved to `reader.layout`, and `Layout` carries a `LayoutId`. ([7df3a0db](https://github.com/dfa1/vortex-java/commit/7df3a0db), [b08ace79](https://github.com/dfa1/vortex-java/commit/b08ace79))
- **Breaking:** `UnknownArray.encodingId` is a typed `EncodingId`. ([7588aa31](https://github.com/dfa1/vortex-java/commit/7588aa31))

## [0.11.0] — 2026-06-28

### Added
- `VortexCalcite.connect(schemaName, tables)` opens a Calcite connection with the schema registered. ([24b64b32](https://github.com/dfa1/vortex-java/commit/24b64b32))
- The Calcite aggregate push-down rule auto-registers over a bare `jdbc:calcite:` connection. ([24b64b32](https://github.com/dfa1/vortex-java/commit/24b64b32))
- Calcite answers a whole-table `SUM(col)` from the zone map. ([24b64b32](https://github.com/dfa1/vortex-java/commit/24b64b32))
- Calcite answers a `WHERE`-filtered `SUM`/`COUNT`/`MIN`/`MAX` from zone-map statistics. ([32cc4a29](https://github.com/dfa1/vortex-java/commit/32cc4a29))
- Calcite answers range-filtered aggregates from zone maps even when the range cuts through a chunk. ([f89b5b69](https://github.com/dfa1/vortex-java/commit/f89b5b69))
- `VortexReader.decodeChunk(chunkIndex, columns)` and `chunkCount()` decode a single chunk. ([084a0133](https://github.com/dfa1/vortex-java/commit/084a0133))
- `ScanIterator.columnZoneStats(column)` surfaces per-zone statistics without decoding data. ([05dd9204](https://github.com/dfa1/vortex-java/commit/05dd9204))

### Changed
- `io.github.dfa1.zstd` 0.3 → 0.6. ([677c2cf7](https://github.com/dfa1/vortex-java/commit/677c2cf7), [6dcdbe94](https://github.com/dfa1/vortex-java/commit/6dcdbe94), [fec0a0d3](https://github.com/dfa1/vortex-java/commit/fec0a0d3))
- Apache Calcite 1.40 → 1.42. ([2f9f02c6](https://github.com/dfa1/vortex-java/commit/2f9f02c6))

### Security
- `DType`-tree and array-node decoding are depth-capped at 64. ([93f8d5f4](https://github.com/dfa1/vortex-java/commit/93f8d5f4), [428026d3](https://github.com/dfa1/vortex-java/commit/428026d3))
- The HTTP reader validates footer `segmentSpecs` against the file size. ([1d8ddebc](https://github.com/dfa1/vortex-java/commit/1d8ddebc))
- `vortex.zstd` decode bounds-checks declared frame sizes. ([2df4e3a7](https://github.com/dfa1/vortex-java/commit/2df4e3a7), [adc445e8](https://github.com/dfa1/vortex-java/commit/adc445e8))
- The HTTP reader parses `Content-Range` defensively. ([feac99b7](https://github.com/dfa1/vortex-java/commit/feac99b7))

## [0.10.0] — 2026-06-26

### Added
- `DType.isUnsigned()`. ([#159](https://github.com/dfa1/vortex-java/issues/159))
- The `vortex.zstd` encoder writes nullable columns. ([e0da32ff](https://github.com/dfa1/vortex-java/commit/e0da32ff), [1b3713b9](https://github.com/dfa1/vortex-java/commit/1b3713b9))
- `new ZstdEncodingEncoder(valuesPerFrame)` writes independently compressed frames. ([#170](https://github.com/dfa1/vortex-java/pull/170))

### Changed
- `vortex.zstd` compresses through `io.github.dfa1.zstd` instead of `aircompressor-v3`. ([689a30a9](https://github.com/dfa1/vortex-java/commit/689a30a9))

### Fixed
- CSV export renders unsigned integer columns unsigned. ([#208](https://github.com/dfa1/vortex-java/issues/208))
- `vortex.zstd` segments compressed with a trained dictionary decode. ([#104](https://github.com/dfa1/vortex-java/issues/104))
- Writing a nullable `Utf8`/`Binary` column no longer throws `NullPointerException`. ([#168](https://github.com/dfa1/vortex-java/pull/168))
- CSV export handles nullable columns. ([#168](https://github.com/dfa1/vortex-java/pull/168))
- Zone-map pruning compares filter values in the column's type domain. ([#159](https://github.com/dfa1/vortex-java/issues/159))

## [0.9.0] — 2026-06-24

### Added
- Canonical non-nullable `DType` constants. ([f4b22e42](https://github.com/dfa1/vortex-java/commit/f4b22e42))

### Changed
- **Breaking:** every `vortex-core` type moved under `io.github.dfa1.vortex.core.*`. ([52f30c16](https://github.com/dfa1/vortex-java/commit/52f30c16))
- Dropped the `flatbuffers-java` runtime dependency. ([5907302e](https://github.com/dfa1/vortex-java/commit/5907302e))

### Removed
- **Breaking:** removed the no-arg `DType` factories. ([f4b22e42](https://github.com/dfa1/vortex-java/commit/f4b22e42))

## [0.8.3] — 2026-06-23

### Performance
- `FastLanes.transposeIndex` and `iterateIndex` use permutation tables instead of per-element `%` and `/`. ([089b6e36](https://github.com/dfa1/vortex-java/commit/089b6e36), [e683a634](https://github.com/dfa1/vortex-java/commit/e683a634))

### Removed
- **Breaking:** removed `EncodingDecoder.accepts(DType)`. ([7516a544](https://github.com/dfa1/vortex-java/commit/7516a544))

### Fixed
- Cleared two SonarCloud-reported bugs in the writer's SUM zone-map stat plumbing. ([33798ab9](https://github.com/dfa1/vortex-java/commit/33798ab9))

## [0.8.2] — 2026-06-22

### Added
- The writer emits the `vortex.stats` zone-map layout, toggled by `WriteOptions.enableZoneMaps`. ([838dba82](https://github.com/dfa1/vortex-java/commit/838dba82), [f2d74351](https://github.com/dfa1/vortex-java/commit/f2d74351))
- The writer records per-zone MIN/MAX for primitive, extension, Utf8 and dictionary columns. ([838dba82](https://github.com/dfa1/vortex-java/commit/838dba82), [fb5d096a](https://github.com/dfa1/vortex-java/commit/fb5d096a), [38ab5c51](https://github.com/dfa1/vortex-java/commit/38ab5c51), [c1198253](https://github.com/dfa1/vortex-java/commit/c1198253), [e51da936](https://github.com/dfa1/vortex-java/commit/e51da936))
- The writer records per-zone NULL_COUNT for every column type. ([135c9b37](https://github.com/dfa1/vortex-java/commit/135c9b37), [c52d4b83](https://github.com/dfa1/vortex-java/commit/c52d4b83), [ab233b86](https://github.com/dfa1/vortex-java/commit/ab233b86))
- The writer records per-zone SUM for numeric primitive columns. ([9661f554](https://github.com/dfa1/vortex-java/commit/9661f554))
- `RowFilter.isNull` and `isNotNull` prune chunks by zone-map null counts. ([2749b6ca](https://github.com/dfa1/vortex-java/commit/2749b6ca))
- `columnStats()` aggregates `null_count` across a column's chunks. ([cb844f23](https://github.com/dfa1/vortex-java/commit/cb844f23))

## [0.8.1] — 2026-06-20

### Added
- The map-based `writeChunk` path accepts boxed nullable arrays. ([4d18939a](https://github.com/dfa1/vortex-java/commit/4d18939a))

### Changed
- **Breaking:** `ExtensionEncoder.encodeAll` is abstract. ([2dcd69ce](https://github.com/dfa1/vortex-java/commit/2dcd69ce))
- **Breaking:** `Estimate` is an enum. ([c355a4bf](https://github.com/dfa1/vortex-java/commit/c355a4bf))

### Fixed
- I8/I16 columns are excluded from the global dictionary, which the reader cannot decode. ([473256b1](https://github.com/dfa1/vortex-java/commit/473256b1))
- `WriteRegistry` iterates encoders in a deterministic order. ([9c4ebb18](https://github.com/dfa1/vortex-java/commit/9c4ebb18))
- Pco decode guards `preDeltaN` against int overflow. ([b7346e7c](https://github.com/dfa1/vortex-java/commit/b7346e7c))

## [0.8.0] — 2026-06-20

### Added
- The writer encodes `vortex.variant` columns. ([35da529d](https://github.com/dfa1/vortex-java/commit/35da529d), [e4e44980](https://github.com/dfa1/vortex-java/commit/e4e44980), [4566dca0](https://github.com/dfa1/vortex-java/commit/4566dca0))
- The reader decodes variant columns. ([76e4c741](https://github.com/dfa1/vortex-java/commit/76e4c741), [4566dca0](https://github.com/dfa1/vortex-java/commit/4566dca0))

### Security
- Reader bounds hardening: untrusted offsets and lengths fail as `VortexException`. ([e9af80d6](https://github.com/dfa1/vortex-java/commit/e9af80d6), [3bcd9881](https://github.com/dfa1/vortex-java/commit/3bcd9881), [a5ce8380](https://github.com/dfa1/vortex-java/commit/a5ce8380))

### Fixed
- CSV import streams rows instead of running out of memory on large files. ([d5280ae2](https://github.com/dfa1/vortex-java/commit/d5280ae2), [0b6784b5](https://github.com/dfa1/vortex-java/commit/0b6784b5), [62863616](https://github.com/dfa1/vortex-java/commit/62863616))
- CLI: `IoWorker.runAndAwait` no longer reports a finished task as pending. ([95c06b1a](https://github.com/dfa1/vortex-java/commit/95c06b1a), [27446d81](https://github.com/dfa1/vortex-java/commit/27446d81))
- `BoolArray.materialize` no longer sign-promotes the accumulator byte. ([bc8e9d4e](https://github.com/dfa1/vortex-java/commit/bc8e9d4e))

### Changed
- Transform encodings decode lazily only. ([cd59fefa](https://github.com/dfa1/vortex-java/commit/cd59fefa))
- **Breaking:** `DecimalArray` is a `non-sealed` family interface. ([a6a9611e](https://github.com/dfa1/vortex-java/commit/a6a9611e))
- **Breaking:** `Array.truncate(rows)` is renamed `Array.limited(rows)`. ([87ab65e2](https://github.com/dfa1/vortex-java/commit/87ab65e2), [4d9ac1f8](https://github.com/dfa1/vortex-java/commit/4d9ac1f8), [332b067e](https://github.com/dfa1/vortex-java/commit/332b067e), [32a35e03](https://github.com/dfa1/vortex-java/commit/32a35e03))
- CSV import reports progress every 10K rows. ([07a056e7](https://github.com/dfa1/vortex-java/commit/07a056e7))

### Removed
- **Breaking:** removed `EmptyArray`. ([3a4dcdfa](https://github.com/dfa1/vortex-java/commit/3a4dcdfa))

## [0.7.3] — 2026-06-17

### Added
- ZSTD-compressed Parquet import works. ([bea15f2d](https://github.com/dfa1/vortex-java/commit/bea15f2d))
- The writer encodes `vortex.patched` columns. ([d63ab7c3](https://github.com/dfa1/vortex-java/commit/d63ab7c3))

### Fixed
- CLI: the Windows TUI reads raw keys through `ReadFile`. ([31b77acc](https://github.com/dfa1/vortex-java/commit/31b77acc))
- Single-distinct-value columns are encoded as constants. ([0e8b945e](https://github.com/dfa1/vortex-java/commit/0e8b945e))

## [0.7.2] — 2026-06-16

### Added
- CLI `view <file>` opens a scrollable grid TUI. ([1c0311fb](https://github.com/dfa1/vortex-java/commit/1c0311fb), [b7f6b6c1](https://github.com/dfa1/vortex-java/commit/b7f6b6c1), [94e5bff8](https://github.com/dfa1/vortex-java/commit/94e5bff8), [6a8ddd3a](https://github.com/dfa1/vortex-java/commit/6a8ddd3a))
- CLI `export` writes a derived `<name>.csv` by default, with a progress bar. ([2b26da9a](https://github.com/dfa1/vortex-java/commit/2b26da9a))
- `ScanIterator.chunkRowCounts()` returns per-chunk row counts without decoding. ([b7f6b6c1](https://github.com/dfa1/vortex-java/commit/b7f6b6c1))
- `vortex.decimal` decodes lazily. ([6bc955d2](https://github.com/dfa1/vortex-java/commit/6bc955d2))
- `Offset*Array` records and `VarBinArray.SlicedMode` slice shared arrays by offset. ([5df3d9a9](https://github.com/dfa1/vortex-java/commit/5df3d9a9))

### Fixed
- Reader: columns with different chunking align. ([5df3d9a9](https://github.com/dfa1/vortex-java/commit/5df3d9a9))
- `FrameOfReferenceEncodingDecoder` materializes lazy children instead of throwing. ([5df3d9a9](https://github.com/dfa1/vortex-java/commit/5df3d9a9))

## [0.7.1] — 2026-06-16

### Added
- `vortex.constant` decodes lazily. ([3edf6e8c](https://github.com/dfa1/vortex-java/commit/3edf6e8c))
- Top-N read benchmarks (N=10, 100). ([c00fdf7f](https://github.com/dfa1/vortex-java/commit/c00fdf7f), [33714d7b](https://github.com/dfa1/vortex-java/commit/33714d7b), [a6fd92fc](https://github.com/dfa1/vortex-java/commit/a6fd92fc))

### Changed
- CLI `schema` prints a per-row column listing. ([9b3fe4b5](https://github.com/dfa1/vortex-java/commit/9b3fe4b5))
- CLI: `Terminal.readKey` takes a `Duration`. ([2942a4da](https://github.com/dfa1/vortex-java/commit/2942a4da))

### Fixed
- CLI: a clear error on Git Bash / MinTTY. ([6ec42288](https://github.com/dfa1/vortex-java/commit/6ec42288))
- `ArraySegments.of(arr)` falls back to typed accessors for lazy arrays. ([74ec207b](https://github.com/dfa1/vortex-java/commit/74ec207b))

## [0.7.0] — 2026-06-16

### Added
- The writer encodes `vortex.pco` columns. ([1bb14ab](https://github.com/dfa1/vortex-java/commit/1bb14ab), [086aa52](https://github.com/dfa1/vortex-java/commit/086aa52), [30579ed](https://github.com/dfa1/vortex-java/commit/30579ed), [7219974](https://github.com/dfa1/vortex-java/commit/7219974), [f856559](https://github.com/dfa1/vortex-java/commit/f856559))
- `LeBitWriter`, an LSB-first bit writer symmetric to `LeBitReader`. ([1bb14ab](https://github.com/dfa1/vortex-java/commit/1bb14ab))
- `DType` static factories, `asNullable()` and `DType.structBuilder()` for write-API ergonomics. ([6367eb37](https://github.com/dfa1/vortex-java/commit/6367eb37))
- Lazy `ALP`, `FoR` and `ZigZag` arrays defer the transform until first element access. ([cff3acb5](https://github.com/dfa1/vortex-java/commit/cff3acb5), [c47c055c](https://github.com/dfa1/vortex-java/commit/c47c055c), [c3ca6951](https://github.com/dfa1/vortex-java/commit/c3ca6951), [68186f8f](https://github.com/dfa1/vortex-java/commit/68186f8f))
- `ChunkedXxxArray` wraps child arrays instead of concatenating them. ([f6a19c47](https://github.com/dfa1/vortex-java/commit/f6a19c47), [2578f892](https://github.com/dfa1/vortex-java/commit/2578f892), [1c7f5950](https://github.com/dfa1/vortex-java/commit/1c7f5950))
- `forEach*` and `fold` default methods on Short, Byte and Bool array interfaces. ([7dc6567e](https://github.com/dfa1/vortex-java/commit/7dc6567e), [f500afe3](https://github.com/dfa1/vortex-java/commit/f500afe3))
- `truncateArray` preserves zero-copy on `ChunkedXxxArray`. ([6f4eaa96](https://github.com/dfa1/vortex-java/commit/6f4eaa96))
- ALP size-based exponent search, ported from Rust. ([f9bb7373](https://github.com/dfa1/vortex-java/commit/f9bb7373))
- Writer compression closes ~93% of the file-size gap to vortex-jni on NYC Yellow Taxi 2024-01 (47.0 → 43.4 MB). ([2ad275c](https://github.com/dfa1/vortex-java/commit/2ad275c))
- `BitpackedEncodingEncoder` picks the best `bit_width` and stores overflow as sparse patches. ([007e6c47](https://github.com/dfa1/vortex-java/commit/007e6c47))
- Per-chunk zone-map stats shown in the TUI inspector. ([5e24fb62](https://github.com/dfa1/vortex-java/commit/5e24fb62))
- The writer validates per-chunk column row counts. ([c54c8dab](https://github.com/dfa1/vortex-java/commit/c54c8dab))

### Changed
- Primitive `Array` types are non-sealed interfaces with default `fold` and `forEach`. ([aec4d813](https://github.com/dfa1/vortex-java/commit/aec4d813), [f500afe3](https://github.com/dfa1/vortex-java/commit/f500afe3))
- `FoR` decode writes in place when the source segment is writable. ([b1906a08](https://github.com/dfa1/vortex-java/commit/b1906a08), [9955a39f](https://github.com/dfa1/vortex-java/commit/9955a39f))
- ALP eager fallback is a single allocate-and-transform pass. ([e3a6c21a](https://github.com/dfa1/vortex-java/commit/e3a6c21a))
- OHLC read benchmarks re-run at 80M rows. ([9b7fd61f](https://github.com/dfa1/vortex-java/commit/9b7fd61f))
- `vortex-jni` 0.74.0 → 0.75.0. ([ff5fe4b3](https://github.com/dfa1/vortex-java/commit/ff5fe4b3), [2c885d3b](https://github.com/dfa1/vortex-java/commit/2c885d3b))

### Fixed
- CLI: terminal mode is restored on TUI exit. ([60cda920](https://github.com/dfa1/vortex-java/commit/60cda920))
- CLI: `aircompressor` is bundled in the uber-jar so the zstd decoder loads. ([e96c5968](https://github.com/dfa1/vortex-java/commit/e96c5968))
- CLI: scan-based filter parser; `VortexException` caught at the boundary. ([d9cff370](https://github.com/dfa1/vortex-java/commit/d9cff370), [6165c497](https://github.com/dfa1/vortex-java/commit/6165c497))
- CLI: CSV import `--delimiter` flag. ([976934b3](https://github.com/dfa1/vortex-java/commit/976934b3))
- CLI: `IoWorker` no longer drops submission failures silently. ([a624d3da](https://github.com/dfa1/vortex-java/commit/a624d3da))
- `LazySparseXxxArray` guards null `patchValues` when `numPatches == 0`. ([d83ec1b5](https://github.com/dfa1/vortex-java/commit/d83ec1b5))

## [0.6.0] — 2026-06-13

### Added
- `proto-gen` module generates Java records from `.proto` files at build time. ([ae6c46a](https://github.com/dfa1/vortex-java/commit/ae6c46a), [743278d](https://github.com/dfa1/vortex-java/commit/743278d), [b527f84](https://github.com/dfa1/vortex-java/commit/b527f84))
- `ProtoReader` and `ProtoWriter`, MemorySegment-native proto3 wire primitives. ([ae6c46a](https://github.com/dfa1/vortex-java/commit/ae6c46a), [b527f84](https://github.com/dfa1/vortex-java/commit/b527f84))
- Oneof factories on generated records, e.g. `ScalarValue.ofInt64Value(v)`. ([b527f84](https://github.com/dfa1/vortex-java/commit/b527f84))
- `PatchedMetadata` and `VariantMetadata` in `encodings.proto`. ([743278d](https://github.com/dfa1/vortex-java/commit/743278d), [b527f84](https://github.com/dfa1/vortex-java/commit/b527f84))
- Nullable extension columns (`vortex.date/time/timestamp/uuid`). ([1015f9b](https://github.com/dfa1/vortex-java/commit/1015f9b))
- Extension decoders return `null` at invalid positions. ([24c64a9](https://github.com/dfa1/vortex-java/commit/24c64a9))
- `ExtensionDecoder` and `ExtensionEncoder` SPIs. ([a560563](https://github.com/dfa1/vortex-java/commit/a560563))
- Date, Time, Timestamp and Uuid extension decoders in `reader.extension`. ([a560563](https://github.com/dfa1/vortex-java/commit/a560563))
- Date, Time, Timestamp and Uuid extension encoders in `writer.encode`. ([a560563](https://github.com/dfa1/vortex-java/commit/a560563))
- The writer routes `List<LocalDate>`, `List<Instant>` and `List<UUID>` to extension storage. ([1d54b57](https://github.com/dfa1/vortex-java/commit/1d54b57), [75d7b4b](https://github.com/dfa1/vortex-java/commit/75d7b4b))
- `vortex.uuid` extension. ([89a0a69](https://github.com/dfa1/vortex-java/commit/89a0a69), [cce2d2d](https://github.com/dfa1/vortex-java/commit/cce2d2d))
- JDBC import for `DATE`, `TIME`, `TIMESTAMP` and UUID columns. ([9f31d9e](https://github.com/dfa1/vortex-java/commit/9f31d9e), [cce2d2d](https://github.com/dfa1/vortex-java/commit/cce2d2d))
- `Chunk.as(name, Class)` typed extension column access. ([e5cefb0](https://github.com/dfa1/vortex-java/commit/e5cefb0))
- `ExtEncoding` storage children are cascade-compressed. ([33cf42e](https://github.com/dfa1/vortex-java/commit/33cf42e))

### Breaking
- **Breaking:** `EncodingRegistry` is renamed `ReadRegistry`. ([834d2f1](https://github.com/dfa1/vortex-java/commit/834d2f1), [a560563](https://github.com/dfa1/vortex-java/commit/a560563))
- **Breaking:** `core.Extension` and `core.ExtensionEncoder` moved to `reader.ExtensionDecoder` and `writer.ExtensionEncoder`. ([2a0ed93](https://github.com/dfa1/vortex-java/commit/2a0ed93), [a560563](https://github.com/dfa1/vortex-java/commit/a560563))
- **Breaking:** `VortexHttpReader.open` gains an `HttpClient` overload. ([235826f](https://github.com/dfa1/vortex-java/commit/235826f))
- **Breaking:** `core.array.*` moved to `reader.array.*`. ([286715c](https://github.com/dfa1/vortex-java/commit/286715c))
- **Breaking:** `core.array.NullableData` moved to `writer.encode.NullableData`. ([286715c](https://github.com/dfa1/vortex-java/commit/286715c))
- **Breaking:** decode utilities moved to `reader.decode`. ([d514435](https://github.com/dfa1/vortex-java/commit/d514435))
- **Breaking:** encode data holders moved to `writer.encode`. ([d514435](https://github.com/dfa1/vortex-java/commit/d514435))
- **Breaking:** removed the `ExtEncoding` unwrap shortcut from the registry. ([4d4ab34](https://github.com/dfa1/vortex-java/commit/4d4ab34), [75d7b4b](https://github.com/dfa1/vortex-java/commit/75d7b4b))
- **Breaking:** removed `ArrayNode.stats()`. ([dc3aa00](https://github.com/dfa1/vortex-java/commit/dc3aa00f))

### Fixed
- `VortexHttpReader` throws `VortexException` on an HTTP body length mismatch. ([235826f](https://github.com/dfa1/vortex-java/commit/235826f))
- `vortex.date` and `vortex.uuid` metadata fixes Java → Rust compatibility. ([bb7fcb0](https://github.com/dfa1/vortex-java/commit/bb7fcb0))
- Extension dtype `nullable` derives from the storage dtype. ([1015f9b](https://github.com/dfa1/vortex-java/commit/1015f9b))
- `DType.Extension.metadata` is capped at 64 KiB on parse. ([22a5f59](https://github.com/dfa1/vortex-java/commit/22a5f59))
- CLI startup silences the `dev.hardwood` INFO log. ([57a5a38](https://github.com/dfa1/vortex-java/commit/57a5a38))

### Removed
- `vortex-parquet` no longer depends on `vortex-reader`. ([eca40f4](https://github.com/dfa1/vortex-java/commit/eca40f4))
- Dropped `protobuf-java`: the CLI jar shrinks from 14 MB to 12 MB. ([743278d](https://github.com/dfa1/vortex-java/commit/743278d))
- Dropped the `protoc` build dependency. ([743278d](https://github.com/dfa1/vortex-java/commit/743278d))

### Performance
- `ProtoWriter` backpatches length-delimited writes, avoiding per-message temp allocation. ([c79611e](https://github.com/dfa1/vortex-java/commit/c79611e))

## [0.5.0] — 2026-06-09

### Added
- Interactive TUI inspector. ([aa7561f](https://github.com/dfa1/vortex-java/commit/aa7561f), [397b64a](https://github.com/dfa1/vortex-java/commit/397b64a), [d4cd0bc](https://github.com/dfa1/vortex-java/commit/d4cd0bc), [8dae240](https://github.com/dfa1/vortex-java/commit/8dae240), [00452e4](https://github.com/dfa1/vortex-java/commit/00452e4), [7a51165](https://github.com/dfa1/vortex-java/commit/7a51165), [e8db30a](https://github.com/dfa1/vortex-java/commit/e8db30a), [a43f340](https://github.com/dfa1/vortex-java/commit/a43f340))
- Extension type decode for `vortex.date`, `vortex.time`, `vortex.timestamp` and `vortex.uuid`. ([4963aa9](https://github.com/dfa1/vortex-java/commit/4963aa9), [ca8d687](https://github.com/dfa1/vortex-java/commit/ca8d687), [99417ad](https://github.com/dfa1/vortex-java/commit/99417ad), [9da2a78](https://github.com/dfa1/vortex-java/commit/9da2a78), [175ad07](https://github.com/dfa1/vortex-java/commit/175ad07))
- Decimal decode, including i128. ([23d5019](https://github.com/dfa1/vortex-java/commit/23d5019), [4735324](https://github.com/dfa1/vortex-java/commit/4735324), [ff20a24](https://github.com/dfa1/vortex-java/commit/ff20a24), [f4ae8c0](https://github.com/dfa1/vortex-java/commit/f4ae8c0))
- The CLI uber-jar is deployed to Maven Central under classifier `all`. ([3e2c552](https://github.com/dfa1/vortex-java/commit/3e2c552), [cfc5cc8](https://github.com/dfa1/vortex-java/commit/cfc5cc8))
- The writer emits a global dictionary for low-cardinality `Utf8` columns. ([b4d1b43](https://github.com/dfa1/vortex-java/commit/b4d1b43))

### Changed
- **Breaking:** `ScanIterator` implements `Iterator<Chunk>`, with closeable chunks. ([b45fd98](https://github.com/dfa1/vortex-java/commit/b45fd98))
- **Breaking:** `EncodingRegistry` is immutable. ([64ffbaa](https://github.com/dfa1/vortex-java/commit/64ffbaa))
- **Breaking:** `inspect` is split into `inspect` (text) and `tui` (interactive). ([e8db30a](https://github.com/dfa1/vortex-java/commit/e8db30a))
- `Extension` sealed hierarchy replaces the `Extensions` utility class. ([175ad07](https://github.com/dfa1/vortex-java/commit/175ad07))
- CLI errors always print the exception class and cause chain. ([6a4464b](https://github.com/dfa1/vortex-java/commit/6a4464b), [f2f85bd](https://github.com/dfa1/vortex-java/commit/f2f85bd))

### Performance
- Bitpacked unpack is faster. ([ab3ca3f](https://github.com/dfa1/vortex-java/commit/ab3ca3f), [ad8a64d](https://github.com/dfa1/vortex-java/commit/ad8a64d))
- ALP and Dict hot paths are 5–10× faster after restoring C2 vectorization. ([442021f](https://github.com/dfa1/vortex-java/commit/442021f))
- Bitpacked scans are about 25% faster on non-broadcast reads. ([051a794](https://github.com/dfa1/vortex-java/commit/051a794))
- `GenericArray.getDecimal` is allocation-free for widths 1/2/4/8. ([f4ae8c0](https://github.com/dfa1/vortex-java/commit/f4ae8c0))

### Fixed
- Decimal element width derives from the buffer size. ([c798e95](https://github.com/dfa1/vortex-java/commit/c798e95))
- `Extensions.localDate` rejects out-of-range storage values. ([a7eab37](https://github.com/dfa1/vortex-java/commit/a7eab37))
- `GenericArray.getDecimal` rejects null cells. ([5198115](https://github.com/dfa1/vortex-java/commit/5198115))
- The TUI reads layout metadata on the I/O worker thread. ([6c732de](https://github.com/dfa1/vortex-java/commit/6c732de), [0cc1137](https://github.com/dfa1/vortex-java/commit/0cc1137), [a47b6fd](https://github.com/dfa1/vortex-java/commit/a47b6fd))
- `InspectorTree` formats `vortex.date` columns by the declared dtype. ([0e749df](https://github.com/dfa1/vortex-java/commit/0e749df), [b5ce1d6](https://github.com/dfa1/vortex-java/commit/b5ce1d6))
- `ScanIterator.truncateArray` supports `GenericArray`. ([f09f564](https://github.com/dfa1/vortex-java/commit/f09f564))
- CLI prints the exception class and full cause chain on `inspect` errors. ([f2f85bd](https://github.com/dfa1/vortex-java/commit/f2f85bd))

### Removed
- **Breaking:** `ScanResult` is renamed `Chunk`. ([b45fd98](https://github.com/dfa1/vortex-java/commit/b45fd98))
- **Breaking:** removed the `Extensions` utility class. ([175ad07](https://github.com/dfa1/vortex-java/commit/175ad07))
- Removed the unused `Extension.Time#unit` and `Extension.Timestamp#unit` accessors. ([2fcb311](https://github.com/dfa1/vortex-java/commit/2fcb311))
- Removed the `VORTEX_DEBUG` environment variable. ([6a4464b](https://github.com/dfa1/vortex-java/commit/6a4464b))
- Dropped the Lanterna dependency for an FFM-based ANSI terminal. ([397b64a](https://github.com/dfa1/vortex-java/commit/397b64a))

## [0.4.0] — 2026-06-07

### Security
- Zip-bomb protection in `ConstantEncoding` and dict-layout decode. ([10a7776](https://github.com/dfa1/vortex-java/commit/10a7776))
- Trailer and postscript validation. ([f8f89fe](https://github.com/dfa1/vortex-java/commit/f8f89fe))
- Footer `segmentSpecs` are bounds-checked against the file size. ([03845ac](https://github.com/dfa1/vortex-java/commit/03845ac))
- `PType.fromOrdinal(int)` bounds-checks PType ordinals. ([b4988c3](https://github.com/dfa1/vortex-java/commit/b4988c3))
- Layout-tree depth is capped at 64. ([29adbe0](https://github.com/dfa1/vortex-java/commit/29adbe0))
- Layout metadata is capped at 4 MiB. ([ebbe644](https://github.com/dfa1/vortex-java/commit/ebbe644))
- `DType.Decimal` precision and scale are validated. ([ebbe644](https://github.com/dfa1/vortex-java/commit/ebbe644))
- `readFlatStats` bounds-checks zone-map stats reads. ([ebbe644](https://github.com/dfa1/vortex-java/commit/ebbe644))

### Added
- `vortex.sequence` F16 encode and decode. ([7b3d7a9](https://github.com/dfa1/vortex-java/commit/7b3d7a9))
- The writer emits a global dictionary layout for low-cardinality columns in the cascade. ([53b2a19](https://github.com/dfa1/vortex-java/commit/53b2a19), [d383765](https://github.com/dfa1/vortex-java/commit/d383765))
- Opt-in Zstd compression through `WriteOptions.withZstd(boolean)`. ([ea10d37](https://github.com/dfa1/vortex-java/commit/ea10d37))
- `DecodeContext.decodeChild(int, DType, long)` typed child-decode helper. ([d07faf0](https://github.com/dfa1/vortex-java/commit/d07faf0), [a1512da](https://github.com/dfa1/vortex-java/commit/a1512da))
- Typed accessors on concrete array types. ([84a34f4](https://github.com/dfa1/vortex-java/commit/84a34f4))

### Changed
- **Breaking:** `Array` drops `buffer(int)`, `child(int)` and `segment()`. ([bdb4e7d](https://github.com/dfa1/vortex-java/commit/bdb4e7d), [1283168](https://github.com/dfa1/vortex-java/commit/1283168), [ba5957c](https://github.com/dfa1/vortex-java/commit/ba5957c), [df6ab3f](https://github.com/dfa1/vortex-java/commit/df6ab3f), [bb7b656](https://github.com/dfa1/vortex-java/commit/bb7b656))
- `ArrayStats` is read on demand from the FlatBuffer node. ([9237e28](https://github.com/dfa1/vortex-java/commit/9237e28))

### Fixed
- `MaskedArray.segment()` delegates to its inner array. ([8a16119](https://github.com/dfa1/vortex-java/commit/8a16119))
- Constant-encoded array indexing broadcasts the index correctly. ([ed658b7](https://github.com/dfa1/vortex-java/commit/ed658b7))

[0.12.3]: https://github.com/dfa1/vortex-java/compare/v0.12.2...v0.12.3
[0.8.3]: https://github.com/dfa1/vortex-java/compare/v0.8.2...v0.8.3
[0.8.2]: https://github.com/dfa1/vortex-java/compare/v0.8.1...v0.8.2
[0.8.1]: https://github.com/dfa1/vortex-java/compare/v0.8.0...v0.8.1
[0.8.0]: https://github.com/dfa1/vortex-java/compare/v0.7.3...v0.8.0
[0.7.3]: https://github.com/dfa1/vortex-java/compare/v0.7.2...v0.7.3
[0.7.2]: https://github.com/dfa1/vortex-java/compare/v0.7.1...v0.7.2
[0.7.1]: https://github.com/dfa1/vortex-java/compare/v0.7.0...v0.7.1
[0.7.0]: https://github.com/dfa1/vortex-java/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/dfa1/vortex-java/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/dfa1/vortex-java/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/dfa1/vortex-java/compare/v0.3.2...v0.4.0
