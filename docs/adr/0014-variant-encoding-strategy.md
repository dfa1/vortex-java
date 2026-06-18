# ADR 0014: Variant encoding strategy — chunked constants now, parquet.variant later

- **Status:** Accepted
- **Date:** 2026-06-18
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —

## Context

Java could already *decode* the canonical variant container (`vortex.variant`) but
`VariantEncodingEncoder` threw `"encode not yet implemented"`, so no Java-written file
could carry a variant column. Variant is the biggest external-facing gap: every
JSON-shaped ingest pipeline maps to it.

The Rust reference has two distinct encodings:

- **`vortex.variant`** — the canonical container. Purely structural: `nbuffers = 0`,
  slots `[core_storage, shredded]`. It stores no bytes itself. `core_storage` must be a
  `DType::Variant` array but "may be chunked, constant, or otherwise encoded". `shredded`
  is an optional row-aligned typed tree for selected paths, its dtype recorded in
  `VariantMetadata.shredded_dtype`.
- **`vortex.parquet.variant`** — a separate *physical* encoding (id literally
  `"vortex.parquet.variant"`), slots `[validity, metadata, value, typed_value]`, where
  `metadata` and `value` are opaque Binary columns holding the Apache Variant binary
  (metadata dictionary + value bytes) per row. This is what Rust writes for real,
  arbitrary JSON-shaped columns.

A row-varying variant column therefore has (at least) two valid physical layouts. The
Rust test suite itself builds a non-constant column *without* `parquet.variant`: a
`ChunkedArray` of one-row `ConstantArray`s of variant scalars, wrapped in the canonical
`VariantArray`.

## Decision

Encode variant columns using **only the canonical `vortex.variant` container over
existing encodings** — no new physical encoding for now.

`core_storage` is built from per-row inner scalars (`Scalar::variant(inner)`):

- all rows equal → a single `vortex.constant` child (the constant broadcasts);
- varying rows → `vortex.chunked`, child 0 = cumulative `u64` run offsets, then one
  `vortex.constant` per run of equal adjacent values.

Adjacent-equal values are coalesced into runs so an all-equal column collapses to one
constant. Shredding is expressed through the container's `shredded` child plus
`VariantMetadata.shredded_dtype`.

**Defer `vortex.parquet.variant`** (the efficient Apache-Variant-binary physical
encoding) until a concrete need for arbitrary JSON-shaped object columns arrives.

## Consequences

### Positive

- No new `EncodingId`, decoder, encoder, proto message, or reader array type. Reuses
  `vortex.variant` + `vortex.chunked` + `vortex.constant`, all already round-tripped.
- Matches a layout the Rust reference produces and reads, so Java↔Rust interop holds
  (verified via JNI: rowCount + arrow schema for constant and row-varying columns).
- Keeps the variant surface small and reviewable; ships value now.

### Negative

- Per-row values are limited to **typed scalars** wrapped as variants, not arbitrary
  Apache Variant binary objects (nested JSON). Real object columns need
  `parquet.variant`.
- One `vortex.constant` per distinct run is space-inefficient for high-cardinality
  columns versus two packed Binary columns.

### Risks to manage

- Heterogeneous inner dtypes across rows: the chunked-of-constants read path assumes a
  consistent inner dtype. Mixed-type variant columns are out of scope until
  `parquet.variant`.
- When `parquet.variant` lands it becomes a second physical layout for the same logical
  column; readers must handle both (the canonical container already abstracts this).

## Alternatives considered

- **Implement `vortex.parquet.variant` now.** Rejected for the first milestone: new
  encoding id + decoder + encoder + `ParquetVariantMetadata{has_value, typed_value_dtype,
  value_nullable}` proto (regeneration) + reader array type — a much larger surface, not
  needed until arbitrary object columns are required. Tracked as the next step.

## References

- Rust: `vortex-array/src/arrays/variant/vtable/mod.rs` (`vortex.variant`),
  `encodings/parquet-variant/src/vtable.rs` (`vortex.parquet.variant`),
  `vortex-array/src/arrays/chunked/array.rs` (cumulative `chunk_offsets`).
- Commits `35438c72` (Layer A constant), `3b1be436` (Layer B chunked constants).
