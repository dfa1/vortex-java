# ADR 0016: vortex-arrow bridge module for Arrow ecosystem interop

- **Status:** Proposed — gated on a concrete downstream interop need
- **Date:** 2026-06-18
- **Deciders:** project maintainer
- **Related:** [TODO.md §Tooling](../../TODO.md),
  [ADR 0010 — Lazy decode](0010-lazy-decode.md),
  [ADR 0012 — Zero-copy layout decoding](0012-zero-copy-layout-decoding.md)

## Context

The reader exposes typed, zero-copy views (`ArrayLong`, `ArrayDouble`, …) backed
directly by the mmap'd `MemorySegment`. These have no third-party dependencies and
never touch `sun.misc.Unsafe` — a core property of the library (see CLAUDE.md
§Code style: "No `sun.misc.Unsafe` or internal JDK APIs").

A large part of the columnar ecosystem speaks Apache Arrow: Arrow Flight, DuckDB
ADBC, pandas/PyArrow, Polars, Spark. A consumer wanting to feed Vortex data into
any of these needs the data as Arrow vectors (`BigIntVector`, `Float8Vector`, …).

The tension: **Arrow's Java implementation (`arrow-memory-netty` / `arrow-vector`)
uses `sun.misc.Unsafe` and Netty internals.** Pulling Arrow into `core` or `reader`
would contaminate the entire library with an Unsafe-dependent, heavyweight
transitive dependency tree and break the Unsafe-free guarantee.

So the decision is not *whether* Arrow interop is valuable — it is — but *where*
the Arrow dependency lives and *how* data crosses the boundary.

## Decision

**Capture the design now; defer implementation until a concrete downstream
interop need surfaces (DuckDB ADBC, Arrow Flight, or pandas handoff against a real
workload).** When that need lands, implement Option A below.

The primary API stays the typed zero-copy views. Arrow is an opt-in bridge in a
**separate module** (`vortex-arrow`) so the core library remains dependency-light
and Unsafe-free. Only consumers who add the bridge module pay the Arrow cost.

## Options

### Option A — Separate `vortex-arrow` bridge module (recommended)

A new Maven module depending on `reader` + `arrow-vector`. Provides converters that
wrap typed views into Arrow vectors:

```java
BigIntVector v = VortexArrow.toBigIntVector(arrayLong, allocator);
```

- Conversion copies `MemorySegment` → Arrow off-heap buffer. The copy is **explicit
  and opt-in** — callers who never import the module never pay it.
- Arrow's `Unsafe`/Netty usage is quarantined to this module; `core`/`reader`/`writer`
  stay clean.
- Pros: clean dependency isolation; the Unsafe-free guarantee holds for everyone not
  using the bridge; incremental type-by-type coverage.
- Cons: a copy per batch (not zero-copy); a second representation to maintain as new
  dtypes ship.

### Option B — Zero-copy via Arrow C-Data Interface (FFM)

Export Vortex segments through the Arrow C-Data Interface (`ArrowArray` /
`ArrowSchema` structs) using FFM, avoiding the Arrow *Java* library and its Unsafe
dependency entirely. The consumer imports via any Arrow C-Data-aware runtime.

- Pros: potentially zero-copy; no `arrow-vector` Java dependency, so no Unsafe at
  all; aligns with the project's FFM-first philosophy.
- Cons: only helps consumers that speak C-Data (native/PyArrow), not pure-JVM Arrow
  consumers who want `BigIntVector` objects; requires hand-rolling the C-Data ABI
  structs and lifetime/release-callback management over FFM — significant complexity;
  validity/offset buffer layout must match Arrow's spec exactly.

### Option C — No bridge; document manual conversion

Ship nothing; point users at the typed views and let them copy into Arrow themselves.

- Pros: zero maintenance; zero dependency.
- Cons: every consumer re-implements the same fiddly buffer/validity mapping; high
  friction for the exact ecosystem (DuckDB/Flight/pandas) most likely to adopt the
  format.

## Consequences

### Positive
- Core library stays dependency-light and Unsafe-free regardless of decision.
- Arrow interop has a recorded home and a recommended shape when the need arrives.

### Negative
- Until built, Arrow consumers must hand-roll conversion (Option C behaviour).

### Risks to manage
- Arrow's Java buffer layout and `BufferAllocator` lifetime model must be mapped
  carefully onto `MemorySegment` lifetimes (segments are tied to the `VortexReader`
  arena; Arrow vectors must own copied memory so they outlive the reader).
- If Option B (C-Data) later proves more valuable, it can coexist with or supersede
  this ADR — they serve different consumer classes (JVM Arrow vs native C-Data).

## Alternatives considered

See Options A–C above. Option A is recommended for the common case (pure-JVM Arrow
consumers); Option B is a future complement for native/C-Data consumers, not a
replacement.

## References

- [TODO.md §Tooling — vortex-arrow bridge](../../TODO.md)
- Arrow Java (`arrow-vector`) — https://arrow.apache.org/docs/java/
- Arrow C-Data Interface — https://arrow.apache.org/docs/format/CDataInterface.html
- CLAUDE.md §Code style — no `sun.misc.Unsafe` / internal JDK APIs
