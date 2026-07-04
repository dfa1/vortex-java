# ADR 0008: Domain primitives and unsigned integer representation

- **Status:** Proposed
- **Date:** 2026-06-13
- **Deciders:** project maintainer
- **Related:** [TODO.md — domain primitives / Valhalla item](../TODO.md),
  [ADR 0005 — Vector API adoption](0005-vector-api-adoption.md)

## Context

Throughout the public API, raw `long` and `int` values are used to represent
structurally distinct domain concepts. A caller cannot tell from a signature
alone whether a `long` parameter is a row count, a byte offset, a byte length,
or a U32 wire value widened to avoid sign issues. Misuse is a silent bug —
the compiler accepts any `long` in place of any other.

The same applies to unsigned integer types from the wire format. Java has no
native unsigned primitives. U8, U16, U32, and U64 are currently represented
as their widened signed counterparts (`int`, `int`, `long`, `long`) with
no enforcement of the unsigned range and no indication at the call site that
the value must be treated as unsigned.

The [TODO.md](../TODO.md) item reads:
> Use domain primitives (`UInt32`, `UInt64`, etc.) as value classes via
> Project Valhalla instead of raw `long`/`int`.

[A companion article](https://dfa1.github.io/articles/rethink-domain-primitives-with-valhalla)
argues that Valhalla's `value class` eliminates the historical heap-allocation
cost that forced domain primitives to stop at system boundaries. Until JEP 401
finalizes, identity-class wrappers (records) impose per-instance heap overhead
that makes them unsuitable in hot decode loops — but acceptable at API
boundaries where the type is created and consumed at most once per scan.

### Domain concepts and their invariants

| Concept | Raw type today | Invariant | Appears in |
|---|---|---|---|
| **Row count** | `long` | `>= 0` | `Chunk.rowCount()`, `Layout.rowCount`, `ScanOptions.limit`, `ArrayStats.nullCount/trueCount`, `WriteOptions.chunkSize` |
| **Byte offset** | `long` | `>= 0` | `SegmentSpec.offset()`, `VarBinArray.readOffset()` |
| **Byte length** | `long`/`int` | `>= 0` | `SegmentSpec.length()`, `Trailer.postscriptLen` |
| **Element index** | `long` | `>= 0`, `< array.length` | All array accessors (`getLong(long i)`, `getInt(long i)`, …) |
| **U32 wire value** | `long` (widened) | `0 <= v <= 0xFFFFFFFFL` | Array decode outputs, stats min/max for U32 columns |
| **U64 wire value** | `long` (reinterpreted) | any 64-bit pattern; treat with `Long.compareUnsigned` | Array decode outputs, stats min/max for U64 columns |
| **Segment index** | `int` | `>= 0` | `writeSegment()` return, `ChunkRef.segIdx` |
| **Cascade depth** | `int` | `>= 0` | `WriteOptions.allowedCascading` |
| **Chunk size** | `int` | `> 0` | `WriteOptions.chunkSize` |
| **Format version** | `int` | `>= 0` | `Trailer.version`, `VortexHandle.version()` |

### Valhalla status in Java 25

JEP 401 (Value Classes and Objects) is a preview feature in Java 25. Enabling
it requires `--enable-preview` across all compile and runtime invocations,
which flows into every downstream consumer — identical deployment tax to the
`--add-modules` problem described in ADR 0005. `value class` is expected to
finalize in Java 26 or 27.

## Decision

### Tier 1 — wrap as `record` at API boundaries now

Wrap concepts that appear at public API boundaries, are created at most once
per scan (not per row), and where confusion with another `long`/`int` is a
realistic bug risk:

| Wrapper | Replaces | Invariant enforced |
|---|---|---|
| `record RowCount(long value)` | `long` row count | `value >= 0` |
| `record ByteOffset(long value)` | `long` offset | `value >= 0` |
| `record ByteLength(long value)` | `long`/`int` length | `value >= 0` |

`RowCount` is the highest-priority: it appears on `Chunk.rowCount()`,
`Layout.rowCount`, `ScanOptions.limit`, and `ArrayStats`, and is the most
likely to be silently swapped with a byte count or a limit.

### Tier 2 — unsigned wire types: widen + document convention now, wrap later

U8 and U16 are widened to `int` — no ambiguity in practice.

U32 is widened to `long`. The range invariant (`0 <= v <= 0xFFFFFFFFL`) is
documented on the relevant accessor Javadoc. No wrapper yet; the value is
used in arithmetic and comparison contexts where a record would require
unwrapping on every operation.

U64 is reinterpreted as `long` (all 64 bits). Callers must use
`Long.compareUnsigned`, `Long.toUnsignedString`, `Long.divideUnsigned`, etc.
This convention is documented at the array accessor level. A `UInt64` wrapper
encoding this requirement explicitly is the end state but is deferred (see
below).

### Tier 3 — do not wrap

Element indices (`long i`), segment indices (`int`), cascade depth (`int`),
chunk size (`int`), and format version (`int`) are **not** wrapped:

- **Element indices** appear in hot decode loops. A record wrapper would force
  an unbox on every accessor call — unacceptable until `value class` is
  available.
- **Segment index, cascade depth, chunk size, format version** are internal
  or config values passed at most once per write/open. The confusion risk is
  low; wrapping adds friction with no proportionate safety gain.

### Tier 4 — migrate to `value class` when JEP 401 finalizes

When JEP 401 leaves preview (expected Java 26/27) and requires no
`--enable-preview` flag:

1. Promote Tier 1 records to `value class` — zero-cost in arrays and fields.
2. Add `value class UInt32` and `value class UInt64` for unsigned wire types.
3. Re-evaluate element indices — if `value class ElementIndex` has the same
   footprint as `long`, wrapping becomes viable for the accessor API.

This ADR will be superseded by a new ADR at that point.

## Consequences

### Positive

- `RowCount`, `ByteOffset`, `ByteLength` catch swap bugs at compile time.
- Unsigned integer conventions are written down once, not rediscovered per
  reviewer.
- Valhalla migration path is explicit: records today → value classes when
  available.

### Negative

- Tier 1 wrappers add `.value()` unwrapping at call sites that feed arithmetic
  operations. Acceptable for boundary types; would not be acceptable for hot
  inner loops.
- U64 as reinterpreted `long` remains a footgun until Tier 4. Mitigation:
  Javadoc on every U64 accessor states the unsigned convention explicitly.

### Risks to manage

- If a caller passes a `RowCount` to a method expecting a `ByteLength`, they
  get a compile error (good). If they unwrap both and do arithmetic on raw
  `long`s, the protection is lost. The wrappers defend the boundary, not
  internal computation.
- `value class` finalization could slip beyond Java 27. Tier 1 records remain
  correct indefinitely; the cost is minor boxing at non-hot sites.

## Alternatives considered

**Wrap everything including element indices.** Rejected: per-element record
allocation in a 3M-row scan is measurable overhead and defeats the purpose of
the FFM/zero-copy design.

**Keep raw primitives, use parameter names only.** Rejected: parameter names
are invisible at call sites without IDE support and do not enforce invariants.

**Use `@NonNegative` annotations (e.g., Checker Framework).** Considered as a
complement, not a replacement. Annotations are not enforced by `javac` without
the Checker Framework on the compile path — adding that as a mandatory
dependency is too heavy. Wrappers provide the same guarantee with zero
additional tooling.

## References

- [TODO.md — domain primitives / Valhalla item](../TODO.md)
- [Rethink Domain Primitives with Valhalla](https://dfa1.github.io/articles/rethink-domain-primitives-with-valhalla)
- JEP 401: Value Classes and Objects — https://openjdk.org/jeps/401
- [ADR 0005 — Vector API adoption](0005-vector-api-adoption.md) — analogous
  deferral pattern for a preview API
