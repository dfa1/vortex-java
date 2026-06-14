# ADR 0011: Zero-copy `MemorySegment` overload for the writer's typed `Chunk`

- **Status:** Deferred — awaiting a concrete re-encode / native-integrator use case
- **Date:** 2026-06-14
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —

## Context

[ADR 0009](0009-write-api-ergonomics.md) introduced a typed `Chunk` builder
on `VortexWriter.writeChunk(Consumer<Chunk>)`. The shipped overloads accept
Java heap arrays — `long[]`, `String[]`, `Long[]`, etc. — which is the
right shape for app developers (the dominant persona).

Two narrower personas remain underserved:

| Persona | Data they hold | Pain today |
|---|---|---|
| **Re-encode pipelines** | `MemorySegment` slices returned by `VortexReader.scan().column("…").buffer()` | Must copy each segment to a typed Java array before `.put` — defeats the zero-copy reader. |
| **Native integrators** | Off-heap segments from Panama allocation, Arrow C-Data, or JNI returns | Same: forced heap-array round-trip. |

Concretely, on the NYC taxi 2024-01 dataset (2.96M rows × 7 numeric F64/I64
columns × 8 bytes), a single re-encode sweep without this overload allocates
~166 MB of throwaway `long[]` / `double[]` to feed the writer. The data
already lives in `MemorySegment`s on the reader side; the heap copy is pure
GC pressure with no transformation.

A second `Chunk.put` overload accepting `MemorySegment` directly closes the
gap:

```java
try (var reader = VortexReader.open(src)) {
    for (Chunk c : reader.scan()) {
        writer.writeChunk(wb -> wb
            .put("timestamp", ((LongArray)   c.column("timestamp")).buffer())
            .put("price",     ((DoubleArray) c.column("price")).buffer())
            .put("symbol",    ((VarBinArray) c.column("symbol")).bytesSegment(),
                              c.rowCount()));
    }
}
```

## Decision

**Deferred.** Land this overload when a concrete downstream user appears —
not speculatively.

When the time comes, the change spans three layers:

1. `Chunk` (public interface): add
   - `Chunk put(String column, MemorySegment buffer)` for fixed-width primitive columns
   - `Chunk put(String column, MemorySegment bytes, long rowCount)` for variable-width
     (Utf8/Binary) columns where the segment doesn't encode row count by itself
2. `ChunkImpl` (validation): assert `segment.byteSize() == n * elemBytes(dtype)` for
   primitive columns; reject non-primitive dtypes that don't have an obvious segment
   interpretation (Bool, FixedSizeList, …).
3. **Per-encoder branch** in the writer's encoders (the invasive part).
   Today each encoder's `encode()` does `(byte[]) data`, `(long[]) data`,
   etc. To consume a `MemorySegment` without copying, every encoder that
   accepts a primitive dtype needs an extra branch reading via `PTypeIO` /
   `MemorySegment.get`. Affected encoders: `PrimitiveEncodingEncoder`,
   `BitpackedEncodingEncoder`, `FrameOfReferenceEncodingEncoder`,
   `RunEndEncodingEncoder`, `RleEncodingEncoder`,
   `SparseEncodingEncoder`, `AlpEncodingEncoder`,
   `AlpRdEncodingEncoder`, `DictEncodingEncoder`,
   `DeltaEncodingEncoder`, `ConstantEncodingEncoder`,
   `ZstdEncodingEncoder`, `ZigZagEncodingEncoder`, `PcoEncodingEncoder`,
   `MaskedEncodingEncoder` — ~15 encoders, ~30 LOC each + tests.

The convenience-only path (copying the `MemorySegment` to a typed array inside
`ChunkImpl`) is **rejected**: it adds API surface without removing the heap
allocation we wanted to skip.

## Consequences

### Positive

- Zero-copy `VortexReader → VortexWriter` re-encode for typed primitive columns.
- Eliminates per-chunk heap allocation in compaction / partition-rewrite tools.
- Same wire format — no reader changes, no schema changes.

### Negative

- Touches every primitive-accepting encoder. The repeated `(byte[]) data` →
  `(MemorySegment) data` branch is mechanical but easy to drift on the next
  encoder added.
- Two more `put` overloads on `Chunk` widen the public API and the persona
  matrix in the docs.

### Risks to manage

- **Lifetime mismatch.** Callers may hand the writer a `MemorySegment` from
  a reader whose `Arena` they then close before the writer is done. The
  writer must either copy the segment internally (regaining the cost) or
  document the lifetime contract loudly.
- **Endianness / alignment.** Encoders today read from Java arrays
  via `PTypeIO.LE_*` view layouts; the same view layouts work for
  segments. Need to assert callers pass LE-byte-ordered, naturally aligned
  buffers (true for any `MemorySegment` previously decoded by Vortex).

## Alternatives considered

- **Status quo (no overload).** Keeps the writer simple but forces a
  measurable heap-allocation cost on re-encode tools. Acceptable while no
  such tool is in tree.
- **Convenience overload that copies inside `ChunkImpl`.** Rejected: gives
  callers the wrong impression of zero-copy semantics and adds API surface
  without removing the cost.
- **Single overload for *every* dtype including Utf8/Bool/FixedSizeList.**
  Tempting for symmetry, but most non-primitive dtypes have an encoder
  pipeline that can't directly consume a flat segment (Utf8 needs an
  offsets buffer too; Bool needs validity bookkeeping). Scope to primitive
  columns first; revisit the rest only if a user wants them.

## References

- [ADR 0009](0009-write-api-ergonomics.md) — write API ergonomics
- `VortexReader` / `LongArray.buffer()`, `DoubleArray.buffer()` — segment accessors used by re-encode pipelines
- `MemorySegment` (Java 21+ Panama FFM) — off-heap buffer type
