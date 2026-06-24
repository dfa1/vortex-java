# ADR 0017: In-house FlatBuffers codegen + MemorySegment-native runtime

- **Status:** Accepted
- **Date:** 2026-06-23
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —
- **Related:** ADR 0001 (split read/write runtimes), ADR 0011 (writer zero-copy
  MemorySegment overload). Mirrors the existing in-house `proto-gen`.

## Context

The file format is described by two schema languages: FlatBuffers (`.fbs` —
Footer, Layout, Postscript, Array) and Protobuf (`.proto` — DType, ScalarValue,
encoding metadata). Protobuf is already self-hosted: `proto-gen` parses `.proto`
and emits records with `decode(MemorySegment, long, long)` / `encode()` that
operate directly on a `MemorySegment`, with no `protobuf-java` runtime.

FlatBuffers is not. We still depend on:

- **`flatc`** (the schema compiler) at code-gen time — an external native tool
  installed via `brew install flatbuffers`, gated behind the
  `regenerate-sources` profile, with a version-guard strip hack in `core/pom.xml`
  because `flatbuffers-java` on Maven Central lags the `flatc` CLI by months.
- **`com.google.flatbuffers:flatbuffers-java`** as a runtime dependency.

Two forces make this dependency costly out of proportion to the ~32 generated
types we actually use:

1. **It is not MemorySegment-native.** Generated tables extend
   `com.google.flatbuffers.Table` and read through a `java.nio.ByteBuffer`; the
   builder writes into a `ByteBuffer`. The reader memory-maps the file into one
   `MemorySegment` (ADR 0001 memory model) and the writer is moving toward
   MemorySegment output (ADR 0011), so every FlatBuffers access crosses a
   `ByteBuffer`↔`MemorySegment` boundary — an extra representation on read and a
   bridge+copy on write. The rest of the codebase is FFM-only by policy
   (CLAUDE.md: "Uses FFM — never JNI or `sun.misc.Unsafe`").

2. **`flatbuffers-java` ships as an automatic module.** It has no
   `module-info`; its module name is derived from the jar filename and is
   unstable. A proper named JPMS module cannot reliably `requires` an automatic
   module and be published to Maven Central. As long as we depend on it, adding
   `module-info.java` to hide implementation packages (e.g. the generated
   `fbs`/`proto` wire-format classes) is off the table. A patch to give
   `flatbuffers-java` a real module descriptor has been submitted upstream but is
   **not merged**, so the blocker stands. (See the prefix workaround in the
   `Fbs`/`Proto` class-name decision and the "No JPMS" rationale.)

`proto-gen` already proved the pattern works for a fixed, small schema set we
fully control: a hand-rolled parser + MemorySegment-native emitter is far less
code than a general compiler, and the Rust interop suite is byte-exact ground
truth that makes a self-hosted wire-format implementation safe to attempt.

## Decision

Replace `flatc` and `com.google.flatbuffers` with an in-house FlatBuffers
toolchain, mirroring `proto-gen`:

- **`fbs-gen` module** — a build-time `.fbs` lexer/parser/AST + code generator
  (`Lexer`, `Token`, `Ast`, `Parser`, `TypeRegistry`, `CodeGen`, `Main`), not
  published. Covers only the schema subset vortex uses.
- **MemorySegment-native runtime in `core`** — `FbsTable` / `FbsStruct` provide
  the read primitives (vtable lookup, indirect uoffsets, vectors, unions, inline
  structs, UTF-8 strings, zero-copy byte slices) over a `MemorySegment`; an
  in-house builder provides the write path (vtable dedup, back-to-front layout,
  alignment) emitting straight into a `MemorySegment`.
- **Generated classes** extend that runtime and are emitted with the `Fbs`
  name prefix applied **in the generator**, so the `.fbs` schemas revert to
  byte-identical with upstream (matching how `proto-gen` keeps `.proto`
  pristine). This removes the schema divergence introduced when the prefix was
  applied by hand-editing the `.fbs` files.

The wire format does not change: FlatBuffers binary layout is fixed, and the
emitted bytes must be identical so the Rust reference reads them.

### Rollout (spike order)

1. **Parser** — `.fbs` lexer + parser, validated against all four real schemas.
2. **Read runtime** — `FbsTable`/`FbsStruct`, validated against the flatc reader
   on bytes produced by the flatc writer (byte-exact cross-check).
3. **CodeGen (read side)** — emit the reader accessors from the AST.
4. **Write builder** — in-house `FlatBufferBuilder` over `MemorySegment`.
5. **Cutover** — switch `core` to the generated MemorySegment classes, drop
   `com.google.flatbuffers`, remove `flatc` (and the version-guard strip) from
   the `regenerate-sources` profile.

Each step keeps `main` green; the flatc-generated classes stay in place until
the in-house path matches byte-for-byte on the full integration suite.

## Consequences

### Positive

- **All-FFM.** Both read and write operate on `MemorySegment`; the
  `ByteBuffer` bridge in the writer and the `ByteBuffer` view on read both
  disappear. Consistent with ADR 0001/0011 and the FFM-only policy.
- **Two external dependencies removed:** the `flatc` native tool (no more
  `brew install flatbuffers`, no version-guard strip hack) and the
  `flatbuffers-java` runtime jar.
- **JPMS unblocked.** With both wire formats self-hosted, no upstream automatic
  module remains; `module-info.java` becomes viable, which in turn lets the
  generated `fbs`/`proto` packages be encapsulated at the root instead of merely
  prefixed.
- **One codegen pattern.** `fbs-gen` and `proto-gen` share structure; the
  prefix lives in the generator for both.
- **Pristine schemas.** `.fbs` no longer diverges from upstream.

### Negative

- **A second hand-rolled wire-format implementation to maintain.** This runs
  counter to the "`proto-gen` is interim" stance — but the driver here (kill the
  automatic module to unblock JPMS, go all-FFM) is stronger than `proto-gen`'s
  original JDK-25 motivation.
- **The write builder is non-trivial.** FlatBuffers serializes back-to-front
  with vtable dedup and alignment; correctness is all-or-nothing.

### Risks to manage

- **Byte-exact correctness.** Mitigation: the Rust interop suite
  (`JavaWritesRustReadsIntegrationTest`, `RustJavaReaderComparisonIntegrationTest`,
  275 tests) is the gate; do not cut over until green. Validate read and write
  independently against the flatc path before removing it.
- **Schema features we don't yet emit.** The parser/codegen cover only the used
  subset; an added schema feature (nested vectors, fixed arrays, key/sorting
  attributes) needs a generator change. Acceptable for a fixed in-repo schema
  set; fail loudly on unsupported constructs.
- **Upstream patch lands first.** If `flatbuffers-java` gets a real module
  descriptor and a lock-step Maven Central release before cutover, the
  automatic-module half of the motivation evaporates — but the MemorySegment-native
  half still justifies the work.

## Known limitations

- **No intra-FlatBuffer bounds checks** in the `fbsrt` read path (e.g.
  `FbsTable.readStringAt` allocates `new byte[len]` with `len` from the blob).
  Same posture as the previous `com.google.flatbuffers` runtime; file-level framing
  is range-checked upstream by `IoBounds`, intra-FlatBuffer fields are not. Trusted-blob
  assumption.
- **Inline struct *table* fields are not exercised.** `createX` adds fields by
  descending alignment; `FbsBuilder.addStruct` requires the struct be the most
  recently written object. Vortex structs (Buffer, SegmentSpec) only appear as vector
  elements today, so this never fires. A future inline struct field sharing an
  alignment group with a scalar could trip the guard — fix by emitting struct fields
  last within their group. Documented at the sort site in `CodeGen`.
- **`FbsBuilder` grows on the heap** (`MemorySegment.ofArray(new byte[])`). Fine: it is
  write scaffolding, not the decode hot path the CLAUDE allocation rule targets.
- **Generator unit coverage is light** relative to the surface; the real safety net is
  the 275-test Rust-interop suite plus idempotent regeneration and the byte-identical-vs-flatc
  builder check in `CodeGenTest`.

## Alternatives considered

- **Wait for the upstream `flatbuffers-java` module patch.** Unblocks JPMS
  eventually but leaves the `ByteBuffer` boundary and the `flatc`/version-guard
  toolchain in place, and has no committed timeline.
- **Shade/relocate `flatbuffers-java` into a named module.** Removes the
  automatic-module problem but keeps the `ByteBuffer` runtime and adds a shading
  step; does nothing for the FFM goal.
- **Keep `flatc`, write only a MemorySegment runtime adapter.** Still requires
  the `flatbuffers-java` types (or a parallel runtime) and the external compiler;
  half-measure.

## References

- `proto-gen` module (the precedent this mirrors).
- `core/pom.xml` `regenerate-sources` profile — the `flatc` invocation and the
  `ValidateVersion()` strip hack this ADR removes.
- FlatBuffers binary format: `google/flatbuffers` `java/.../Table.java`,
  `Struct.java`, `FlatBufferBuilder.java`.
