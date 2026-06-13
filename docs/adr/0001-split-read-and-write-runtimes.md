# ADR 0001: Split read and write runtimes out of `core`

- **Status:** Accepted (Phase 0 + Phase 1 complete)
- **Date:** 2026-06-11
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —

## Context

The current module layout collapses the file-format model, the read runtime,
and the write runtime into a single `core` module. The other modules
(`reader`, `writer`, `inspector`, `cli`, `csv`, `jdbc`, `parquet`) are thin
orchestration layers that call back into `core` for every meaningful operation.

### What `core` currently contains

```
io.github.dfa1.vortex.core           — DType, PType, Footer, Layout,
                                       VortexException, VortexFormat,
                                       Array hierarchy, ArrayStats
io.github.dfa1.vortex.encoding       — Encoding (encode + decode on one type),
                                       Registry (read + write dispatch),
                                       DecodeContext, EncodeContext,
                                       FlatSegmentDecoder, ArrayNode,
                                       30+ concrete *Encoding.java classes
io.github.dfa1.vortex.extension      — Extension interface, ExtensionId,
                                       4 spec extension impls
io.github.dfa1.vortex.proto          — generated proto records (in-tree codec)
io.github.dfa1.vortex.fbs            — generated flatbuffer types
```

### The smell

1. **`Encoding` is bifunctional.** Every encoding implements both
   `encode(DType, Object, EncodeContext)` and `decode(DecodeContext)`. A
   read-only consumer (the most common deployment shape — analytical engines
   reading columnar files) pulls in the full write path, including Zstd
   compression libraries, dictionary builders, and stats sketchers.

2. **`Registry` is a dual dispatcher.** It maps `EncodingId` to a single
   `Encoding` instance and exposes both read (`decode`, `decodeAsSegment`) and
   write surfaces. Read-only callers carry write-side identity even when no
   write code path will ever fire.

3. **`reader` is a shell, not a runtime.** `VortexReader` memory-maps the
   file, parses the trailer/postscript/footer/layout, then hands off to
   `FlatSegmentDecoder` — which lives in `core`. From that point on every
   meaningful operation (per-buffer slicing, `Registry.decode` dispatch,
   `Encoding.decode` call) happens inside `core`. The `reader` module
   contributes ~3 KLOC of orchestration around ~30 KLOC of decode runtime that
   lives in `core`.

4. **The `slice()` escape hatch is forced by this layout.**
   [PR #27](https://github.com/dfa1/vortex-java/pull/27) wraps untrusted
   `MemorySegment.asSlice` calls in a `BoundedSegment` type, but ended up
   shipping 33 `unwrapForSubParser(...)` sites because the consumers
   (`Encoding.decode`, `FlatSegmentDecoder`, `Registry`) live in `core` while
   the byte-producing handle (`VortexHandle.slice`) lives in `reader`. They
   cannot share package-private access; the cross-module API must be public;
   the public API must expose raw segments because that is what the consumers
   take. Every typed wrapper added to plug the gap (`BoundedSegment`,
   `unwrapForSubParser`, `MemorySegments.slice`) is a workaround for the fact
   that the read runtime should not be in a different module than the byte
   source it consumes.

5. **Concrete consequences of (1)–(4):**
   - **`Registry.decodeAsSegment`** exists purely so `ScanIterator` (in
     `reader`) can decode a child node back into a raw `MemorySegment` —
     an inversion of the natural data-flow direction.
   - **`DecodeContext.segmentBuffers`** had to become `BoundedSegment[]` so
     the security contract survives the cross-module hand-off; if decoders
     lived alongside `FlatSegmentDecoder`, package-private `MemorySegment[]`
     would have sufficed.
   - **Read-only jars cannot be smaller than the full reactor.** The CLI
     uber-jar pulls every encoder, every writer dependency (`zstd-jni`,
     `air-compressor`, etc.) even if the binary only reads files.
   - **`Extension`** mirrors the same problem at a smaller scale —
     `encodeAll` and decode helpers live on one interface, pulling
     write-side dependencies into read-only consumers.

## Decision

Split `core` into three logical surfaces along the read/write axis:

```
core/                       — only the model
  Encoding                  — id() + accepts(); no encode/decode methods
  EncodingId
  DType, PType, ArrayStats, Footer, Layout
  NullableData              — the one Array-shaped type both sides need
  BoundedSegment            — the byte-access primitive
  proto, fbs                — generated code (already pure data)

reader/                     — read runtime
  VortexReader, VortexHandle, VortexHttpReader, ScanIterator
  Array hierarchy           — BoolArray, IntArray, LongArray, VarBinArray,
                              StructArray, ... (the read-only data exchange
                              format; writer never touches these)
  ReadRegistry              — Map<EncodingId, EncodingDecoder>
  EncodingDecoder           — Array decode(DecodeContext)
  DecodeContext, FlatSegmentDecoder
  BitpackedDecoder, AlpDecoder, PcoDecoder, ...  (30+ files)

writer/                     — write runtime
  VortexWriter
  WriteRegistry             — Map<EncodingId, EncodingEncoder>
  EncodingEncoder           — EncodeResult encode(DType, Object, EncodeContext)
  EncodeContext
  BitpackedEncoder, AlpEncoder, PcoEncoder, ...  (30+ files)
```

### How the registry attaches at the API surface

`Registry` splits into two distinct types — `ReadRegistry` and
`WriteRegistry` — not a generic `Registry<T>`. Each is passed alongside
its corresponding entry point, **not** folded into an options record:

```java
ReadRegistry rr = ReadRegistry.builder().registerServiceLoaded().build();
VortexReader.open(path, rr);                          // ReadRegistry directly

WriteRegistry wr = WriteRegistry.builder().registerServiceLoaded().build();
VortexWriter.builder(path, schema)
    .registry(wr)                                      // WriteRegistry directly
    .options(WriteOptions.defaults())                  // tuning knobs only
    .build();
```

Two design choices feed this shape.

**Distinct types, not `Registry<T>`.** Reasons:
- Different builder ergonomics: the read side has no cascade chain to
  configure; the write side does (`cascadeCodecs`, allowed-cascading depth).
  A generic type would carry irrelevant builder methods on both sides.
- `ServiceLoader` manifests are already separate
  (`META-INF/services/...EncodingDecoder` vs `...EncodingEncoder`), so
  type-level separation matches the runtime story.
- Mistakes like passing a write registry to `VortexReader.open` become
  compile errors, not runtime errors.

**Alongside the options, not inside them.** Reasons:
- `WriteOptions` is a record (an immutable value). `WriteRegistry` is a
  configured map with lifecycle: typically built once at app startup and
  reused across many file writes. Mixing forces re-creating options every
  time you want a new file with the same registry.
- Records work badly for fields with non-trivial equality semantics
  (`Registry.equals`?).
- Today the registry already lives on the method signature; keeping that
  split is the lowest-migration shape.

The same applies to read-side configuration. There is no `ReadOptions`
record today (the reader takes `ScanOptions` per-scan instead); the
proposal keeps that as-is. `ReadRegistry` is the file-open parameter;
`ScanOptions` is the per-scan parameter.

Effect on caller code:
- Read-only callers (analytics engines, inspector, CLI inspector)
  construct only `ReadRegistry`. No transitive dependency on writer
  encoders — the `writer` module isn't on their classpath at all.
- Write-only callers (CSV importer, JDBC importer) construct only
  `WriteRegistry`.
- Tools that do both (integration tests, parquet bridge) construct both.

### What changes structurally

- `Encoding` becomes a small metadata-only interface in `core`. It carries
  `EncodingId` and `accepts(DType)` and nothing else. No bifunctional decode
  + encode methods.
- Each encoding's `Decoder` static inner class becomes a top-level
  `EncodingDecoder` implementation in `reader`. The `Encoder` inner class
  becomes `EncodingEncoder` in `writer`. CLAUDE.md already documents this
  split via private inner classes; the migration largely lifts those into
  separate compilation units across modules.
- `Registry` splits into `ReadRegistry` and `WriteRegistry`. Each registry
  exposes only the dispatch surface its side needs. The `decodeAsSegment`
  escape hatch is deleted; the corresponding adapter logic lives in
  `FlatSegmentDecoder` (in `reader`) instead.
- `DecodeContext` moves to `reader`; `EncodeContext` moves to `writer`.
- `FlatSegmentDecoder` moves to `reader`, into the same package as
  `VortexReader`. The `slice()` method on `VortexHandle` becomes
  package-private — `FlatSegmentDecoder`, `Trailer`, and `PostscriptParser`
  are its only callers, all co-resident in `reader/io`.
- `unwrapForSubParser` and the corresponding audit trail collapse to the
  minority of decoders that genuinely call into a sub-parser (`ProtoReader`)
  with a raw `MemorySegment`. Cross-module byte hand-offs disappear.
- `Extension` similarly splits into `ExtensionDecoder` + `ExtensionEncoder`,
  or keeps a single interface with read-only and write-only sub-types.

### Effect on the `slice()` problem

The motivating problem disappears as a side effect:

- `VortexHandle.slice(long, long)` → package-private. External callers cannot
  see it; cross-module consumers (`ScanIterator`, `InspectorTree`) move into
  the same module so the package-private access works.
- `BoundedSegment` stays in `core` as the primitive, but no longer needs to
  travel through public API surfaces. Most internal uses can drop back to
  raw `MemorySegment` because they live in the same package as the byte
  source and the trust boundary is now spatially local.
- The 33 `unwrapForSubParser` sites from PR #27 are mostly eliminated —
  not because we wrote more wrappers, but because the wrappers are no longer
  needed once read code stops crossing module boundaries to reach its bytes.

## Migration phases

Each phase is a separate PR, lands independently green, and keeps the old
shape running side-by-side until cut-over.

**Phase 0 — preparation (≈0.5 day)**
- Land this ADR.
- Add `Encoding` metadata-only interface in `core` (extends the existing
  one for now). Verify all current `Encoding` impls already implement
  `id()` and `accepts(DType)`.
- Introduce `ReadRegistry` and `WriteRegistry` skeletons that for now
  delegate to the existing `Registry`. No call-site changes yet.

**Phase 1 — split `DecodeContext` and the read registry (≈1 day)**
- Move `DecodeContext`, `ArrayNode`, `FlatSegmentDecoder` to `reader`.
- `ReadRegistry` becomes the canonical read dispatcher; `Registry.decode`
  forwards to it during transition.
- `ScanIterator` uses `ReadRegistry` directly.
- `decodeAsSegment` deleted; `FlatSegmentDecoder` gains the equivalent
  package-private helper.

**Phase 2 — lift `*Decoder` impls into `reader` (≈1 day per family, ≈3 days)**
- Pick one encoding family at a time (Fastlanes, ALP, Pco, …).
- For each: extract the `Decoder` inner class into a new
  `*EncodingDecoder` in `reader/encoding`; register via
  `META-INF/services/...EncodingDecoder`; delete the `decode(...)` method
  from the old `*Encoding` in `core`.
- After all families lifted, `Encoding` in `core` no longer has a `decode`
  method. `Registry` (the old dual) no longer has a read surface.

**Phase 3 — repeat for the write side (≈3 days)**
- Mirror Phase 2 for writers. `Encoding` in `core` becomes the
  metadata-only shape promised in the Decision section.

**Phase 4 — `VortexHandle.slice` to package-private (≈0.5 day)**
- Drop `slice()` from the public `VortexHandle` interface. All remaining
  callers are now in `reader` and use a package-private accessor on the
  concrete `VortexReader` / `VortexHttpReader` types.
- Inspector and CLI inspector code that today calls `handle.slice(...)`
  receives a new typed accessor instead (e.g.
  `FlatSegmentInspector.peek(handle, spec)`).
- The 33 `unwrapForSubParser` sites from PR #27 are deleted at the same
  time; the corresponding decoders take raw `MemorySegment` again because
  they live in the same package as the byte source.

**Phase 5 — `Extension` split (≈0.5 day)**
- `ExtensionDecoder` and `ExtensionEncoder` in their respective modules.
- Confirm the four spec extensions (`Date`, `Time`, `Timestamp`, `Uuid`)
  ride through the split cleanly.

**Phase 6 — read-only jar artifact (≈0.5 day)**
- Verify the CLI's "read-only" personality (the inspector) can be built
  without the writer module on the classpath. Document in `compatibility.md`.

Cumulative effort estimate: ~9 person-days of focused work, plus ~3 days
of CI / integration-test fallout, plus reviewer time. Not a weekend.

## Consequences

### Positive

- **Public API never exposes raw `MemorySegment`** for the read path.
  `VortexHandle.slice` disappears from the public surface. The SECURITY.md
  contract is enforced architecturally, not by audit-trail convention.
- **PR #27's 33 `unwrapForSubParser` sites collapse to a handful** —
  only the decoders that genuinely call a sub-parser
  (`ProtoReader`-bound decoders: Constant, Pco, Sparse, plus Zstd's
  native lib hand-off) retain a documented trust transfer.
- **`Registry.decodeAsSegment` deleted.** The current adapter exists only
  because cross-module dispatch needs a raw-segment escape; once decoders
  are co-resident with the byte source, the adapter is no longer needed.
- **Read-only deployments shrink.** No transitive pull on `zstd-jni`
  encode paths, FSST dictionary builders, ALP encoders, etc. The CLI
  inspector becomes a true read-only artifact.
- **Dependency direction matches data flow.** `reader` depends on `core`;
  `writer` depends on `core`; neither depends on the other. Today both
  live inside `core` and the dependency direction is invisible.

### Negative

- **Multi-day refactor.** ~9 person-days plus CI iteration. Cannot land in
  a single PR; must be staged carefully so each phase runs green.
- **Encoding impls double in file count** during transition. `BitpackedEncoding`
  becomes `BitpackedDecoder` (in `reader`) + `BitpackedEncoder` (in `writer`).
  Test files split similarly.
- **CLAUDE.md updates** — the "three touch-points for adding an encoding"
  rule becomes "decoder side + encoder side + EncodingId enum constant",
  each in its own module.
- **CHANGELOG breaking-changes section grows.** External users (none today,
  but any future ones) see `Encoding`, `Registry`, and `DecodeContext`
  moved. Probably worth bundling under a 0.7.0 release boundary.
- **Two `ServiceLoader` manifests per encoding** instead of one.
- **Integration tests need re-routing.** Tests that today construct a
  `Registry` and call `decode` directly will need to construct a
  `ReadRegistry` instead — mechanical but pervasive.

### Risks to manage

- **Side-by-side period drift.** Phases 1–3 leave both the old `Registry`
  and the new `ReadRegistry`/`WriteRegistry` registered for each encoding
  during transition. Risk: divergent behaviour if a bug fix lands on one
  side and not the other. Mitigation: integration tests run against both
  paths during the transition; the old `Registry` becomes a thin forwarder
  early in Phase 1.
- **Extension split.** `Extension` carries the same encode/decode tension
  as `Encoding`; the migration plan assumes a parallel split. If the
  extension API has tighter user-facing constraints (it does — see
  `DateExtension.decodeAll`), Phase 5 may need a separate ADR.
- **JMH benchmarks.** `RustVsJavaReadBenchmark` and friends construct
  `Registry` + `DecodeContext` directly. They live in the `performance`
  module, which depends on `reader`. The benchmarks need re-wiring at the
  end of Phase 2.

## Alternatives considered

- **Keep `core` as-is, hide `slice()` via Java modules (JPMS).** Drops
  PR #27's escape-hatch noise but does not address the underlying smell —
  `core` still hosts the read runtime, `reader` still calls back into
  `core` for every operation, `Registry` still dispatches both sides,
  and read-only deployments still pull the writer surface. Rejected as
  cosmetic.
- **Move `FlatSegmentDecoder` alone into `reader`, leave everything else.**
  Solves the immediate `slice()` problem at the cost of a circular module
  dependency: `Encoding.decode` (in `core`) would call
  `Registry.decode` (in `core`) which would route into
  `FlatSegmentDecoder` (in `reader`). Rejected as architecturally worse
  than the current state.
- **Adopt an existing pluggable codec framework (e.g. Arrow's
  `CompressionCodec` SPI shape).** Considered briefly. Vortex's
  cascading-encoding model has tighter requirements than Arrow's flat
  codec model; an external SPI does not fit. Rejected.
- **Status quo + documentation.** Document that `core` is the read runtime
  and `reader` is a shell. Cheapest. Rejected because every future
  feature that needs cross-module byte access reintroduces the same
  escape-hatch problem.

## Decision drivers

- The 33 `unwrapForSubParser` sites in PR #27 are a strong proxy signal:
  every one of them documents a place where read code needs bytes that
  live in another module.
- A genuinely read-only deployment (inspector + scan) should be possible
  without pulling Zstd encoders or FSST builders. Today it is not.
- The `Encoding` interface bifunctional shape blocks ahead-of-time
  pruning of the write surface; the refactor is the only path to a
  smaller read-only artifact.

## References

- [PR #27 — `sec(parser): BoundedSegment + audit trail for untrusted asSlice`](https://github.com/dfa1/vortex-java/pull/27)
- [Phase 1–4 commits — BoundedSegment introduction and migration](https://github.com/dfa1/vortex-java/pull/27/commits)
- [SECURITY.md — the contract this work hardens](../../SECURITY.md)
- [CLAUDE.md — current "three touch-points" rule for adding an encoding](../../CLAUDE.md)
- [TODO.md — parser hardening backlog](../../TODO.md)
