# Changelog

All notable changes to **vortex-java** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.5.0] — Unreleased

### Added

### Changed

### Fixed

### Removed

[0.5.0]: https://github.com/dfa1/vortex-java/compare/v0.4.0...main

## [0.4.0] — 2026-06-07

The headline themes for this release are a **security-hardening sweep** of the file-format
parser, a **public-API cleanup** of the `Array` hierarchy (the heap-allocated `buffer(int)` /
`segment()` accessors are gone from the interface), and **cascading writer features** that
close the compression gap with the Rust reference implementation on real-world workloads.

### Security

Every malformed input now surfaces as `VortexException` rather than a JDK exception
(`IndexOutOfBoundsException`, `ArrayIndexOutOfBoundsException`, `StackOverflowError`, raw
FlatBuffer/Protobuf runtime exceptions). Regression suite lives under
`reader/src/test/java/.../*SecurityTest`.

- **Zip-bomb protection** — `ConstantEncoding` and dict-layout decode no longer pre-allocate
  `O(rowCount)` memory; a 150-byte crafted file claiming 10⁹ rows is now constant-cost.
- **Trailer + postscript validation** — `VortexReader` and `VortexHttpReader` reject unknown
  file `version`, `postscriptLen == 0`, and `postscriptLen > fileSize - 8`. Footer/layout/dtype
  blob offsets and `Layout.encoding` index are bounds-checked at parse time.
- **Footer `segmentSpecs` bounds** — every spec is validated against `fileSize` the moment
  the footer is materialised, eliminating later `IndexOutOfBoundsException` on
  `MemorySegment.asSlice`.
- **PType ordinal bounds-check** — `PType.fromOrdinal(int)` replaces all 22 `PType.values()[idx]`
  call sites across encodings; crafted Protobuf ptype fields are rejected up front.
- **Layout-tree depth cap** — `PostscriptParser.convertLayout` is capped at depth 64,
  preventing both unbounded nesting and self-referential FlatBuffer cycles (a ~120-byte
  cycle attack previously triggered `StackOverflowError`).
- **Layout metadata size cap** — per-layout `metadataAsByteBuffer()` is capped at 4 MiB
  (above any real encoding's footprint; FSST's symbol table is the largest at ~32 KiB).
- **Decimal field validation** — `DType.Decimal` is rejected unless `precision ∈ [1, 38]`
  and `scale ∈ [0, precision]`, matching IEEE 754-2008 decimal128.
- **`readFlatStats` bounds-check** — zone-map stats reads now validate the trailing
  little-endian `fbLen` field against the segment size, returning empty stats on malformed
  input rather than throwing `IndexOutOfBoundsException` from `MemorySegment.asSlice`.

### Added

- **`vortex.sequence` F16 encode/decode** — half-precision floats now round-trip through the
  sequence encoding.
- **Writer: cascading with global dict layout** — low-cardinality columns (≤ 256 distinct
  values in the chunk sample) are now emitted as a `vortex.dict` layout, with the dict
  candidate detection tightened to avoid false positives.
- **Writer: opt-in Zstd compression** — `WriteOptions.withZstd(boolean)` exposes the
  size/throughput trade-off. Off by default; turn on for archival workloads.
- **`Encoding.decodeSegment` extension point** — added as part of the typed-segment migration
  (see *Changed* below). Provides a typed alternative to `Array.segment()`.
- **`DecodeContext.decodeChild(int, DType, long)`** — typed child-decode helper that replaces
  the per-encoding `decodeChildAs(...)` private utilities.
- **Typed accessors on concrete array types** — `LongArray.segment()`, `VarBinArray.offsetsSegment()`,
  `MaskedArray.inner()`, and friends now live on the concrete types where they fit cleanly,
  rather than on the `Array` interface.
- **`*SecurityTest` test-naming convention** — adversarial / robustness tests are now grouped
  under the `*SecurityTest` suffix, mirroring the existing `*IntegrationTest` convention.
  Run with `./mvnw test -Dtest='*SecurityTest'`.
- **`FlatSegmentDecoder`** — extracted from `EncodingRegistry`; the registry is now pure
  dispatch.

### Changed

- **`Array` interface slimmed down.** `buffer(int)`, `child(int)`, and `segment()` are no
  longer part of the `Array` interface; consumers should use the typed accessors on the
  concrete subtype (e.g. `LongArray.segment()`) or `ArraySegments.of(arr)` for a generic
  fallback. `buffer(int)` is now package-private on the concrete array types.
- **`VarBinArray`** no longer keeps a redundant `offsetsArr` field; consumers read offsets
  via `offsetsSegment()`.
- **`ArrayStats`** is no longer eagerly stored on decoded array types; statistics are now
  read on demand from the FlatBuffer node, matching the Rust reference implementation.

### Fixed

- **`MaskedArray.segment()`** delegates correctly to its inner array (regression introduced
  during the typed-accessor migration).
- **Constant-encoded array indexing** broadcasts the index correctly when scanning multiple
  rows from a single stored value.
- **Performance benchmark** (`RustWritesJavaReadsBigFileBenchmark`) migrated off the removed
  `Array.buffer(int)` accessor, unblocking `./mvnw verify` and `./bench`.

### Removed

- `Array.buffer(int)`, `Array.child(int)`, and `Array.segment()` from the public `Array`
  interface (see *Changed*). Callers should migrate to the concrete-type accessors or
  `ArraySegments.of(arr)`.
- `Encoding.decodeSegment(...)` is removed after the migration to `DecodeContext.decodeChild`.
- `ArrayStats` field on decoded array types (statistics are now lazy).

### Documentation

- Added `CONTRIBUTING.md` covering trunk-based workflow, commit conventions, and the
  three-touch-point rule for adding encodings.
- Added an internal-architecture diagram set covering the file format, layout tree, and
  scan path.
- Added a "Vortex vs Parquet" comparison section to the README.
- Expanded the `## Security` section in `TODO.md` with the open hardening roadmap (resource
  caps, per-encoding adversarial tests, Jazzer fuzz harness, OSS-Fuzz submission).

### Build & Tooling

- **Dependabot enabled** for Maven and GitHub Actions.
- Numerous dependency bumps: JUnit Jupiter (5.11.4 → 6.1.0, tests now require JUnit 6),
  Mockito, FastCSV (3 → 4), H2 (2.3 → 2.4), Checkstyle, Zstd-JNI,
  maven-compiler/surefire/failsafe/javadoc/source/shade/gpg/antrun/exec/build-helper plugins,
  `actions/checkout` (4 → 6), `actions/setup-java` (4 → 5), `actions/cache` (4 → 5),
  Sonatype central-publishing plugin.
- `pom.xml` files now group dependencies under `production` / `testing` comment sections
  with a consistent project-internal-first ordering.
- Checkstyle scope tightened to exclude generated `fbs`/`proto` packages.

[0.4.0]: https://github.com/dfa1/vortex-java/compare/v0.3.2...v0.4.0
