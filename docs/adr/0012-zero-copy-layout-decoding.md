# ADR 0012: Zero-copy layout decoding — lazy Chunked and Dict arrays

- **Status:** Accepted
- **Date:** 2026-06-14
- **Implemented:** 2026-06-15 (PRs #38, #39, #42)
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —
- **Related:** [ADR 0010 — Lazy decode for 1:1 transform encodings](0010-lazy-decode.md),
  [ADR 0001 — Split read and write runtimes out of core](0001-split-read-and-write-runtimes.md)

## Context

vortex-java advertises zero-copy reads via a single mmap'd `MemorySegment`.
Decoded `Array`s are returned as views over slices of that segment; the
lifetime of those slices is tied to the `VortexReader` close.

[ADR 0010](0010-lazy-decode.md) closed part of the gap: ALP, FoR, and ZigZag
are now lazy 1:1 transforms (commits `d82d2f7c` through `8d6fe4f0`). The
encoded child segment is held by reference, the transform runs on
`getXxx(i)`. For chains like `ALP(Flat)`, `FoR(Flat)`, `ZigZag(Flat)`, reads
are now genuinely zero-copy.

Two avoidable allocations remain. They sit one level higher in the stack —
**layout decode**, not **encoding decode** — in `ScanIterator.decodeLayout`
when the layout is `Chunked` or `Dict`:

| Path                                             | File:lines                                  | Cost                       |
|--------------------------------------------------|---------------------------------------------|----------------------------|
| Chunked concat (`ScanIterator`)                  | `ScanIterator.java:447-474`                 | `totalRows * elemBytes`    |
| Chunked concat (encoding-decoder path)           | `ChunkedEncodingDecoder.java:90-94`         | same — duplicate code path |
| Dict primitive expansion                         | `ScanIterator.java:156-178`                 | `n * elemBytes`            |
| Dict string expansion                            | `ScanIterator.java:182-218`                 | `totalBytes + (n + 1) * 4` |

For a 10M-row F64 file split into eight 1.25M-row chunks, the chunked path
allocates 80 MB of arena, memcpys 80 MB from the children, then returns a
`MaterializedDoubleArray` over the result. The cost is paid even when the
caller never reads the column (projection-only scan) or only reads the first
100 rows (LIMIT). This is incompatible with the zero-copy claim.

ADR 0010 §Generalization mentions Dict in passing — "Dict is a special case
(lazy is trivial — `getDouble(i) = values[indices[i]]`) but is already O(1)
per access." This is aspirational, not what the Java code does today. ADR
0010 also does **not** discuss the Chunked layout at all: its scope is
encoding-level transforms, and Chunked is a file-structure layout, decoded
via `ScanIterator.decodeLayout` rather than `EncodingDecoder.decode`.

### Decompression encodings stay eager

Out of scope for this ADR: Bitpacked, Pco, Zstd, Fsst, Delta, RLE, RunEnd,
Sparse, Constant. These transform compact compressed bytes into a wider
element array — element-at-`i` requires unpacking a window, so they must
allocate an output buffer. Their *output* can be wrapped in a lazy transform
(ADR 0010), but the decompression pass itself is unavoidable. The audit
identified roughly 11 such allocation sites; none are addressed here.

### Rust reference design

The Rust reference implementation (`github.com/spiraldb/vortex`) treats
both Chunked and Dict as first-class lazy storage types:

- **`ChunkedArray`** (`vortex-array/src/arrays/chunked/array.rs`) —
  `ChunkedData { chunk_offsets: Vec<usize>, next_builder_slot: usize }`
  plus child `ArrayRef`s. `find_chunk_idx(index)` does binary search over
  `chunk_offsets` (O(log n_chunks)) and delegates. Never concatenates.
- **`DictArray`** (`vortex-array/src/arrays/dict/array.rs`) —
  `DictSlots { codes: ArrayRef, values: ArrayRef }`. Never expands.
- **Compute kernels per encoding** —
  `vortex-array/src/arrays/chunked/compute/take.rs` sorts indices by chunk,
  takes from each child separately, assembles. No canonical materialisation.
- **Materialisation is opt-in** — `vortex-array/src/canonical.rs` provides
  `to_canonical()` for Arrow handoff. Default reads stay in encoded form.

Java today inverts this: lazy is the exception (just the six transform
variants from ADR 0010); eager is the default. This ADR closes the
layout-side of that inversion.

## Decision

Introduce first-class lazy *layout* storage types matching the Rust model.

### New array types

For each numeric interface, add a `Chunked*Array` and `Dict*Array`:

```java
public record ChunkedDoubleArray(
        DType dtype, long length, DoubleArray[] children, long[] offsets
) implements DoubleArray {
    @Override public double getDouble(long i) {
        int chunkIdx = findChunk(offsets, i);
        return children[chunkIdx].getDouble(i - offsets[chunkIdx]);
    }
    // fold / forEachDouble: default-method inherited from interface,
    // but override here to iterate children sequentially — each child
    // loop stays tight and the JIT vectorises per child.
    @Override public double fold(double identity, DoubleBinaryOperator op) {
        double result = identity;
        for (DoubleArray c : children) {
            result = c.fold(result, op);
        }
        return result;
    }
}

public record DictDoubleArray(
        DType dtype, long length, DoubleArray values, IntArray codes
) implements DoubleArray {
    @Override public double getDouble(long i) {
        return values.getDouble(codes.getInt(i));
    }
}
```

Scope:
- `ChunkedDoubleArray`, `ChunkedFloatArray`, `ChunkedLongArray`,
  `ChunkedIntArray`. (Other ptypes — I8/I16/Bool/VarBin — added when a
  real workload demands.)
- `DictDoubleArray`, `DictLongArray`, `DictIntArray`, `DictVarBinArray`.

### Materialisation fallback

`ArraySegments.of(arr, arena)` already handles lazy variants. Add cases:

```java
case ChunkedDoubleArray a -> materialise(a, arena);
case DictDoubleArray a    -> materialise(a, arena);
// … etc.
```

Each materialiser allocates `length * elemBytes` and walks
children/codes — **same cost as today's eager path.** This fires only
when a parent decoder demands a flat segment via `decodeChildSegment`
(rare for Chunked at the outer layer; common for Dict codes flowing into
a Bitpacked sibling).

When nothing forces materialisation — projection-only reads, fold/forEach
on the user-facing array — no allocation happens.

### Decoder wiring

- `ScanIterator.decodeConcatPrimitive` returns `ChunkedXxxArray` over the
  decoded children + their offsets. The alloc + memcpy loop deletes.
- `ChunkedEncodingDecoder.decode` collapses to the same shape (the
  duplicate path).
- `ScanIterator.expandDictPrimitive` returns `DictXxxArray` wrapping
  `values + codes`. The alloc + scatter loop deletes.
- `ScanIterator.expandDictStrings` returns `DictVarBinArray`. Same idea.

### Bench gate

Before merging the implementation, run `./bench
JavaVsJniReadBenchmark.javaReadClose` on the single-chunk OHLC fixture
**and** add a multi-chunk variant (e.g. 8-chunk OHLC). The single-chunk
number measures the inlining regression risk in isolation; the
multi-chunk number measures the concat-avoidance win. The decision to
ship depends on the multi-chunk delta exceeding any single-chunk
regression by a comfortable margin.

## Consequences

### Positive

- **Zero-copy honoured on multi-chunk files.** A scan over an 8-chunk
  10M-row F64 column avoids 80 MB of arena alloc + 80 MB of memcpy per
  scan. Projection-only scans pay zero copy cost for skipped columns.
- **Zero-copy on dict-encoded columns.** Dictionary columns (common for
  low-cardinality categorical data) stop materialising n elements every
  scan.
- **Java aligns with Rust.** Every encoding (per ADR 0010) and every
  layout (this ADR) is permanent storage. The mental model becomes
  consistent: encoded form is the default, canonicalisation is opt-in.
- **Unlocks per-encoding compute pushdown.** ADR 0010 §Phase 3 plans
  `compareGt`/`take`/`filter` per encoding. With first-class Chunked,
  `take_chunked(indices)` can sort indices by chunk and delegate without
  canonicalising — matching `vortex-array/src/arrays/chunked/compute/take.rs`.

### Negative

- **Implementation count per interface grows past the JIT inline cap.**
  After this ADR:
  - `DoubleArray`: Materialized, LazyAlp, ChunkedDouble, DictDouble = 4
  - `FloatArray`: Materialized, LazyAlpFloat, ChunkedFloat, DictFloat = 4
  - `LongArray`: Materialized, LazyForLong, LazyZigZagLong, ChunkedLong, DictLong = 5
  - `IntArray`: Materialized, LazyForInt, LazyZigZagInt, ChunkedInt, DictInt = 5

  Per [ADR 0010 §Risks to manage](0010-lazy-decode.md), the JVM stops
  inlining `getXxx(i)` past 3 impls per interface. Sites that see ≥4
  impls within one JIT compilation unit pay a real method-dispatch cost
  per row (~2-5 ns/row depending on inline-cache state). For 10M-row
  hot loops, 20-50 ms of overhead.

- **`ArraySegments.of(arr, arena)` fallback has the same memcpy cost as
  today's eager path.** Net win only when nobody asks for a flat segment.
  Decoders that route through `decodeChildSegment` still pay the
  materialisation cost — they just pay it through a different code path.

### Risks to manage

- **Full-fold flat-only benchmark regression.** `javaReadClose` runs
  against a single-chunk fixture today; the DoubleArray call sites it
  hits used to see only `MaterializedDoubleArray`. After this ADR they
  see 4 impls. Expected regression: 5-15%. Mitigations:
  1. *Pattern-match at known call sites.* ADR 0010 §Risks notes:
     encoding-specific classes accessed via `instanceof` at the case arm
     don't count against the cap — JVM resolves at the case, not through
     the interface vtable. Hot consumers (CLI inspector, integration
     test harness) can switch on the concrete type.
  2. *Fold `LazyFor`+`LazyZigZag` into one transform class.* A
     `LazyTransformLongArray` keyed by an enum (`FOR_ADD` / `ZIGZAG`)
     costs a per-row branch but recovers room for Chunked+Dict under the
     cap. Trade-off: branch in the inner loop vs megamorphic dispatch
     across the codebase.
  3. *Defer Dict.* Chunked is the bigger OHLC win. Shipping Chunked
     alone holds the cap at 4 per interface (already over but the
     smaller jump).

- **Wrong children-segment lifetime.** Chunked/Dict hold references to
  their child arrays' encoded segments. Those segments live on the
  chunk arena. If the lazy array escapes the chunk's
  try-with-resources, it dereferences freed memory. The existing
  `Chunk.close()` contract covers this; the implementation must not
  leak Chunked/Dict arrays past their chunk scope. Integration tests
  against Rust-written multi-chunk files are mandatory.

- **`find_chunk_idx` is a per-row hot-loop branch.** Binary search over
  `offsets` runs on every `getDouble(i)` call. The CLAUDE.md hot-loop
  rule bans per-element modulo/division because it kills C2 superword
  vectorisation. Binary search is conditional control flow with a
  variable-target branch — also bad for vectorisation. Mitigations:
  use the per-child `fold` path (no `find_chunk_idx` calls); for random
  access workloads, accept the cost — random access is non-vectorisable
  by nature.

## Alternatives considered

### A — Stay eager, keep concat + dict expansion

Status quo. Continue allocating `n * elemBytes` per scan. Zero
implementation cost, zero risk of regressing the flat-only bench.

Pros: zero churn. Cons: breaks the zero-copy claim, leaves the largest
remaining allocation source in place. Rejected.

### B — Extend ADR 0010 instead of writing a new ADR

Add a "Phase 4: lazy layouts" section to ADR 0010.

Pros: one ADR, one decision trail. Cons: different code path
(`ScanIterator.decodeLayout` vs `EncodingDecoder.decode`), different
decision (lazy layouts vs lazy transforms), different risk profile.
Mixing them muddles the audit trail — future readers chasing "why is
Chunked lazy" land in a doc about ALP. Rejected; cross-link instead.

### C — Strict zero-copy contract (refuse `ArraySegments.of` materialisation)

`ArraySegments.of(ChunkedXxxArray, arena)` throws instead of materialising.
Force all decoders to use typed access (`getXxx(i)` / `fold`).

Pros: strictest possible contract; impossible to accidentally pay for
materialisation. Cons: breaks chains where a parent decoder genuinely
needs a flat segment — most importantly, the dict-codes-into-bitpacked
path where `codesSeg` flows into a tight bit-unpack loop. Forcing those
sites to use `getInt(i)` per code would megamorphic-dispatch on every
unpacked bit. Rejected — relaxed fallback is the right trade.

### D — Chunked only, defer Dict to a follow-up

Land Chunked first; Dict comes later.

Pros: smaller blast radius (4 impls per interface instead of 5 for Long/Int
arrays). Lower risk of inlining regression. Faster ship. Cons: defers
half the zero-copy win. Dict workloads continue to pay full materialisation
cost.

This is a viable shipping order, not a rejection of Dict — recorded as an
open question below.

### E — Generic transform class to free inlining budget

Fold `LazyForLong`+`LazyZigZagLong` into one
`LazyTransformLongArray(kind, encoded, ref)` keyed by an enum. Same for
Int.

Pros: drops LongArray and IntArray back to 4 impls each (within reach of
the cap with one more mitigation). Cons: adds a per-row enum branch in
the inner loop. The earlier ADR 0010 work explicitly avoided this in
favour of distinct types for Phase 3 compute pushdown.

Recorded as a mitigation, not a rejection — implementation PR decides.

## Open questions

The implementation PR resolves these; recorded here so the trail is clear:

1. **Scope:** Chunked-only first, or Chunked+Dict together?
2. **Megamorphic mitigation:** accept the 4-5-impl dispatch cost,
   refactor `LazyFor`+`LazyZigZag` into a generic transform class, or
   restrict the new types to pattern-match dispatch (no interface
   exposure)?
3. **Fallback policy:** relaxed (`ArraySegments.of(_, arena)` materialises)
   or strict (throws)?
4. **Sequencing vs ADR 0010 §Phase 2 fused chain:** which lands first?
   Both target OHLC; fused chain fixes ALP(FoR(Bitpacked)) materialisation
   inside one chunk, Chunked fixes the cross-chunk concat. They compose
   but order matters for measurement.

## Outcome

Shipped across three PRs against `main`:

- **PR #38 — Chunked half.** `ChunkedLongArray`/`IntArray`/`DoubleArray`/`FloatArray` records as proposed, plus
  `ChunkedShortArray`/`ByteArray`/`BoolArray` (scope expanded — adding them was free since each new type only
  pushed those interfaces from 1 to 2 impls, well under the JIT inline budget). `ScanIterator.decodeConcatPrimitive`
  renamed to `decodeChunkedLayout` and rewritten to construct `ChunkedXxxArray` directly; the alloc + memcpy loop
  deleted. `ChunkedEncodingDecoder.decode` rewritten the same way (`wrap`/`wrapPrimitive`/`wrapStruct` replaces
  `concat`/`concatPrimitive`/`concatStruct`). `ArraySegments.of(arr, arena)` gained the chunked materialise cases
  per §"Materialisation fallback". Bench gate passed: `JavaVsJniReadBenchmark` showed no statistically significant
  delta vs the previously-considered sticky-cache class shape; record shape chosen on architecture grounds
  (immutable, thread-safe, idiomatic Java). `forEach*` overrides iterate children directly so sequential scans
  bypass the per-row binary search.

- **PR #39 — Dict half.** `DictLongArray`/`IntArray`/`DoubleArray`/`FloatArray` records as proposed.
  Codes ptype variance (U8/U16/U32/U64 = Byte/Short/Int/Long Array) handled via centralised
  `DictArrays.readCode(codes, i)` plus per-method codes-type switches hoisted outside the inner loops per the
  CLAUDE.md hot-loop rule. `ScanIterator.expandDictPrimitive` deleted; the primitive dict-layout branch
  returns the matching `DictXxxArray`. `ArraySegments.of(arr, arena)` gained the four dict materialise cases.
  `truncateArray` got Dict cases before the per-interface catch-all so LIMIT keeps the dictionary and just
  slices codes.

- **PR #42 — Multi-chunk Utf8/Binary + ADR closeout.** `VarBinArray` gained a `ChunkedMode` record alongside the
  pre-existing `OffsetMode` and `DictMode`. `ScanIterator.decodeChunkedLayout` routes Utf8/Binary to
  `ChunkedMode.of`. The previously-stale TODO on `DictEncodingDecoder` is replaced with a "kept eager by design"
  note: the encoding-level path uses `SegmentBroadcast.capacity`-aware scatter for ConstantEncoding fan-out, which
  doesn't trivially wrap as a lazy `Array`; the layout-level path (the primary scan entry point) is fully lazy.
  `docs/compatibility.md` Decode shape table updated.

Resolutions to the open questions:

1. **Scope:** Chunked first (PR #38), Dict second (PR #39). Bench between confirmed Chunked was net positive
   before adding Dict polymorphism.
2. **Megamorphic mitigation:** accepted the 5-impl dispatch cost on `LongArray`/`IntArray` (Materialized + LazyFor +
   LazyZigZag + Chunked + Dict). `JavaVsJniReadBenchmark` between PRs showed no measurable regression because
   sequential reads use `forEach*` (single impl per call site) and the polymorphic `getXxx(i)` site isn't the
   benchmark's hot path. Re-evaluate if a real workload surfaces the cost.
3. **Fallback policy:** relaxed — `ArraySegments.of(arr, arena)` materialises Chunked and Dict variants on
   demand. Used internally by `decodeDictLayout` for string dict expansion (the codes side) and reserved for
   future decoders that genuinely need a contiguous segment.
4. **Sequencing vs ADR 0010 §Phase 2:** Chunked + Dict lazy decoding landed first. Phase 2 (fused chain) is
   still open and now composes against a fully lazy multi-chunk + dict-encoded pipeline.

What was **not** shipped (intentional):

- **`DictEncodingDecoder.decode` (encoding-level dict).** Not in the ADR's §"Decoder wiring" scope. Its
  `SegmentBroadcast.capacity`-aware scatter for ConstantEncoding fan-out makes lazy wrapping non-trivial;
  the layout-level path is the common case and is lazy. Documented in the decoder's class-level Javadoc.
- **`DictVarBinArray` as a fifth record.** Turned out unnecessary: `VarBinArray.DictMode` was already a lazy
  dict record (pre-dating this ADR). The ADR's §Scope mention of `DictVarBinArray` is satisfied by the
  pre-existing `DictMode`.
- **Bench fixture for column-level chunked Utf8.** The current writer doesn't produce a column-level Chunked
  layout for Utf8 — multi-batch Utf8 writes surface as one scan-`Chunk` per writer batch, with leaf
  `VarBinArray` columns inside. `VarBinArray.ChunkedMode` is therefore defensive: it fixes a pre-existing
  crash (`decodeChunkedLayout` threw on Utf8 dtype cast) but the bug only fires on files whose layout
  explicitly carries column-level Chunked Utf8. Unit-tested in `VarBinChunkedModeTest`; round-trip-tested in
  `MultiChunkUtf8RoundTripTest`. When a future writer change produces column-level chunked Utf8, the test
  assertion should flip to require `sawChunkedMode == true`.

## References

- [ADR 0010 — Lazy decode for 1:1 transform encodings](0010-lazy-decode.md)
- [ADR 0001 — Split read and write runtimes out of core](0001-split-read-and-write-runtimes.md)
- [CLAUDE.md §Memory model](../../CLAUDE.md) — hot-loop rule, encoding output allocation rule
- Rust reference:
  - `vortex-array/src/arrays/chunked/array.rs` — ChunkedArray storage and `find_chunk_idx`
  - `vortex-array/src/arrays/dict/array.rs` — DictArray storage
  - `vortex-array/src/arrays/chunked/compute/take.rs` — per-chunk compute kernel
  - `vortex-array/src/canonical.rs` — `to_canonical()` opt-in materialisation
- Local code:
  - `ScanIterator.java:447-474` — `decodeConcatPrimitive`
  - `ScanIterator.java:156-218` — `expandDictPrimitive`, `expandDictStrings`
  - `ChunkedEncodingDecoder.java:90-94` — duplicate concat path
  - `ArraySegments.java` — two-arg `of(arr, arena)` overload (the materialisation hook)
