# ADR 0002: Runtime pluggability of DType, Layout, and Compute

- **Status:** Deferred — awaiting a real downstream consumer use case
- **Date:** 2026-06-11
- **Deciders:** project maintainer
- **Related:** [ADR 0001 — Split read and write runtimes](0001-split-read-and-write-runtimes.md), [TODO.md §Audit runtime pluggability vs Rust impl](../../TODO.md)

## Context

The Rust reference implementation
([vortex-data/vortex](https://github.com/vortex-data/vortex)) supports
**runtime registration** of four extension axes:

| Axis      | Rust SPI                                                                                                                  | Java status today |
|-----------|---------------------------------------------------------------------------------------------------------------------------|-------------------|
| Encodings | `VortexSession::register_encoding`, plus `allow_unknown()` passthrough                                                    | ✅ Pluggable via `ServiceLoader` + `EncodingRegistry.Builder.register()`; `allowUnknown()` matches Rust |
| DTypes    | `VortexSession::register_dtype`                                                                                            | ❌ `sealed interface DType permits ...` — no user-extensible type |
| Layouts   | `VortexSession::register_layout`                                                                                           | ❌ Fixed set: `Flat`, `Chunked`, `Zoned`, `Struct` (sealed) |
| Compute   | `VortexSession::register_compute`                                                                                          | ❌ No compute layer yet |

The maintainer flagged this gap on 2026-06-04. The existing TODO entry
(line 215) carries explicit guidance: **"short design note weighing
sealed-vs-pluggable for DType + Layout; revisit when Java impl has a
real downstream consumer asking for it. Don't pre-open these without
a use case."**

This ADR is that design note. It does **not** propose changing the
current sealed shape. It records the trade-offs so that a future
maintainer faced with a real consumer ask can act quickly, and records
the criteria under which the deferral should be reconsidered.

## Decision

**Keep `DType` and `Layout` sealed for now. Defer the compute layer
entirely until the reader is feature-complete and at least one
downstream JVM consumer has filed a concrete request.**

Re-evaluate when *any* of the following is true:

1. A downstream JVM consumer (analytics engine, ETL framework, OLAP
   query layer) files a concrete request to register a custom logical
   type or layout, and provides the wire-format spec they need.
2. The Rust impl ships a new `DType` variant or layout that the Java
   reader needs to interoperate with — at which point the cost of
   adding it via the sealed hierarchy versus exposing an SPI becomes
   a comparable conversation.
3. The compute layer is added on the Rust side in a way that the Java
   reader needs to mirror for full file-level compatibility (e.g.
   pruning predicates serialized into the layout tree).

Under any of those conditions, this ADR is superseded by a follow-up
that picks a concrete shape from §"Shapes considered" below.

## Trade-offs

### Why sealed today is the right default

- **Exhaustive `switch` patterns.** Every place the reader pattern-matches
  on `DType` (`PostscriptParser.convertDType`, every encoding's
  `accepts(DType)`, the inspector's column formatter) compiles to a
  fast exhaustive switch with no default-case fallthrough. Adding a
  user-defined variant requires either (a) breaking exhaustiveness or
  (b) every consumer gracefully handling unknown DTypes — a real
  expressiveness loss.
- **Equality and hashing are free.** Records give `equals`/`hashCode`
  by structural comparison. A user-registered DType subtype with
  arbitrary fields cannot guarantee stable equality across module
  boundaries, leaking to caching bugs in layers like `ColumnStats`
  and dictionary lookup.
- **No premature abstraction.** The current sealed set covers every
  encoding the project supports and every type the Rust reference
  emits as of v0.74.0. The cost of opening the hierarchy buys
  flexibility that no current Java consumer uses.
- **CLAUDE.md alignment.** The project's tone explicitly prefers
  sealed types + pattern matching as a stylistic norm. Opening the
  hierarchy fights that tone for a hypothetical benefit.

### When sealed becomes the wrong default

- **Downstream consumer with a domain-specific logical type.** A team
  building on top of vortex-java for, say, time-series IP-address
  columns might want a `DType.IpAddress(byte version)` carrying its
  own validity and parsing semantics, instead of stuffing it into
  `Extension`. Today they can use `Extension` with a custom id; if
  that ergonomic gap becomes noisy in their codebase, a real SPI
  request follows.
- **Rust adds a new variant Java cannot read.** Today the only Rust
  variant Java does not handle is `DType::Union` (added in Rust 0.71.0,
  see `compatibility.md`). The current sealed hierarchy makes adding
  it a 3-line patch. The cost-balance flips only if Rust starts
  shipping experimental DTypes at a faster cadence than Java can
  follow with sealed-hierarchy additions.

### Why compute is deferred entirely

- The Java reader does not yet have a query / filter pushdown layer.
  Adding a compute SPI before there is anything to plug into is the
  textbook overengineering case the TODO guidance is meant to prevent.
- The Rust compute layer drives layout-tree pruning via statistic
  predicates. Java's `ZonedEncoding` does the equivalent today by
  reading min/max stats directly out of the layout tree — a
  hard-coded special case rather than a general predicate engine.
  General-purpose pruning is a separate, large piece of work that
  warrants its own design discussion.

## Shapes considered (for the future un-deferral)

Recorded so that the deferral can be lifted quickly. None are
recommended today.

### Shape A — Open the sealed hierarchies via SPI, mirror Rust

```java
public interface DType {                 // no longer sealed
    EncodingId encodingHint();
    boolean nullable();
}

public final class DTypeRegistry {
    public static DTypeRegistry builder() { ... }
    public DTypeRegistry register(DTypeSpec spec) { ... }
    public DType resolve(io.github.dfa1.vortex.proto.DType proto) { ... }
}
```

Pros: closest to Rust's `VortexSession::register_dtype`; downstream
extensions don't need an `Extension` wrapper hack. Cons: every
`switch (dtype)` in the project becomes non-exhaustive; equality
contract fragile across module boundaries.

### Shape B — Keep sealed for spec types, allow nominal Extension expansion

```java
public sealed interface DType permits ... {
    record Extension(String id, DType storage, ByteBuffer meta, boolean nullable)
        implements DType { ... }
}

public interface ExtensionPlugin {
    String id();
    Object decode(Extension dtype, Array storage);
    Object encode(Extension dtype, Object value);
}
```

This is roughly the shape vortex-java already ships for the four spec
extensions (`Date`, `Time`, `Timestamp`, `Uuid`). The shift would be
to elevate the plugin interface to a public SPI rather than
package-private. Pros: doesn't break exhaustive switching on the
top-level hierarchy; downstream consumers register through a narrower
API; matches the "extension type" wire-format already in the proto
schema. Cons: not as expressive as Shape A — extensions piggy-back on
a storage type, no new physical layouts.

### Shape C — Defer to JDK 25+ pattern-matching with default fallthroughs

If at some point the language adds first-class "open sealed" support
or improved default-case ergonomics, Shape A becomes cheaper. Not a
present option; recorded for completeness.

## Decision drivers (what would un-defer this)

A future PR that wants to lift the deferral should be able to point to
all of the following, in writing:

1. **A named downstream consumer.** Not a hypothetical "someone might
   want X." A concrete project / team with a name and a use case.
2. **A spec for the new variant.** Wire format, serialisation,
   round-trip semantics. Not just "register a custom type" in the
   abstract.
3. **Confirmation the existing `Extension` mechanism does not fit.**
   Most user-defined logical types belong in `Extension` already; an
   SPI is only justified if `Extension` is the wrong shape for the
   request.
4. **A statement on the equality + switch-exhaustiveness implications.**
   How will reader-internal pattern matching adapt? Is the project
   willing to add `default ->` branches across the codebase?

Until those four boxes can be ticked, the deferral stands.

## Consequences of deferring

- **Positive.** No premature abstraction. Existing sealed hierarchies
  keep their performance and ergonomic benefits. Compatibility-doc
  follow-ups (e.g. adding `DType::Union`) remain 3-line patches.
- **Negative.** Downstream consumers with custom logical types must
  go through `Extension` and tolerate its limitations (storage type
  must be one of the built-in DTypes). Java cannot read Rust files
  whose authors registered custom DTypes via `VortexSession::register_dtype`
  — though no such files have been observed in practice.
- **Risk.** If the deferral becomes the default reflex ("we'll do it
  when someone asks"), the project may miss a window where an
  emerging Java analytics ecosystem (Polars-on-JVM, DuckDB JVM
  bindings, etc.) lands and the SPI gap blocks adoption. Mitigation:
  a maintainer should set a calendar reminder to revisit this ADR
  every 6 months.

## References

- [TODO.md §"Audit runtime pluggability vs Rust impl"](../../TODO.md)
  (line ~215, dated 2026-06-04)
- [ADR 0001 — Split read and write runtimes out of `core`](0001-split-read-and-write-runtimes.md)
- [docs/compatibility.md — Known wire-format gaps](../compatibility.md)
  (notes `DType::Union` as one new variant Java does not yet decode)
- Rust upstream:
  [`VortexSession`](https://docs.rs/vortex/latest/vortex/session/struct.VortexSession.html)
  exposes `register_encoding`, `register_dtype`, `register_layout`,
  `register_compute`.
