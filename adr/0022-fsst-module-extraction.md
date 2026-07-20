# ADR 0022: Extract FSST into a standalone module, ported faithfully from the paper

- **Status:** Accepted
- **Date:** 2026-07-20
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —
- **Related:** [ADR 0005 — Vector API adoption](0005-vector-api-adoption.md), [ADR 0010 — Lazy
  decode](0010-lazy-decode.md), [ADR 0017 — In-house FlatBuffers codegen](0017-in-house-flatbuffers-codegen.md)

## Context

`writer/FsstEncodingEncoder` and `reader/FsstEncodingDecoder` held a first-pass, simplified FSST
(Fast Static Symbol Table) implementation. Comparing it against `vortex-jni` (issue #287) surfaced
real gaps against both the original paper (Boncz/Neumann/Leis, "FSST: Fast Random Access String
Compression", PVLDB 13(11), 2020) and the Rust reference (`spiraldb/fsst`, the crate the Rust
`vortex-fsst` wire adapter is built on):

- **Matching.** The old `longestMatch` probed symbol lengths 8 down to 1 in a per-position loop —
  up to eight sequential open-addressing hash lookups per input byte, a variable-length loop body
  that blocks JIT auto-vectorization (the exact shape CLAUDE.md's hot-loop rule forbids). The
  paper's Algorithm 4 ("lossy perfect hashing", §5.1) resolves any match in O(1): a direct
  65536-entry array for 0/1/2-byte symbols, and a small hash table for 3-8 byte symbols, combined
  branch-free.
- **Training.** The old bottom-up loop always compress-counted the *full* training sample on every
  one of its five generations, ranked candidates by plain `count * length`, and never pruned
  low-frequency noise. The real reference (`spiraldb/fsst`) additionally uses a growing
  per-generation sample fraction (~6% → 100%), a per-generation min-count floor, an 8x gain boost
  for single-byte candidates (to suppress escapes), and a final cost-based prune pass.
- **Decode.** The old decoder copied a matched symbol's bytes one at a time in a per-byte loop
  instead of the paper's Algorithm 1 trick — one unconditional 8-byte store, advancing the output
  cursor by only the symbol's true length.

A zero-dependency Java FSST implementation was searched for and does not exist (only the C
reference `cwida/fsst`, the Rust `spiraldb/fsst`, and a Go port `axiomhq/fsst`). Per that search's
own conclusion, the fix isolates FSST into its own module — mirroring how the Rust reference itself
splits a pure algorithm crate (`spiraldb/fsst`, zero dependencies, no Vortex awareness) from
vortex-rust's thin wire adapter (`vortex-fsst`) — so the compression algorithm can be iterated on
and benchmarked without the Vortex wire-format "shell" in the way.

During the rewrite's own review, a real data-corruption bug was found and fixed: the new
branch-free matcher has no notion of "end of input" (unlike the old bounded loop), so a trained
symbol whose trailing bytes happen to be zero could spuriously match zero-padded bytes past a short
input's true end, silently appending garbage bytes to the decoded output. Confirmed with a concrete
repro (a 3-byte input round-tripping to 8 bytes) before it reached a merged PR. Fixed by an explicit
`pos + length <= end` guard at both call sites that feed the matcher a possibly-short remaining
range (`Compressor.compress` and the training loop's compress-count step) — the paper's own
guidance for a branch-free scalar kernel (§5.2) names exactly this bound as the alternative to a
terminator byte (which is an AVX512-batch-kernel-specific optimization, out of scope here).

## Decision

Extract FSST into a new top-level Maven module, `fsst` (artifact `vortex-fsst`), with zero
dependency on `core`/`reader`/`writer`:

1. **Port the paper faithfully**, plus the real reference's engineering refinements: branch-free
   O(1) matching (`ShortCodeTable` for 0/1/2-byte symbols, a 2048-slot lossy perfect hash table for
   3-8 byte symbols — matching `spiraldb/fsst`'s L1D-cache-line-split sizing over the paper's
   literal 4096, since that Rust crate is the actual `vortex-jni` comparison target); adaptive
   byte-size-bounded sampling with a growing per-generation fraction, min-count pruning, the
   single-byte gain boost, and the final cost-based prune during training; the unconditional-8-byte-
   store decode trick.
2. **Memory boundary: `MemorySegment`, not `ByteBuffer`.** `java.lang.foreign` is a standard JDK
   module, not a third-party dependency — using it satisfies "zero deps" exactly as well as
   `ByteBuffer` would, while staying consistent with every other module in this codebase (CLAUDE.md:
   "Uses FFM (`MemorySegment`/`Arena`) — never JNI or `sun.misc.Unsafe`") and avoiding a dual-API
   translation layer with its own parity-testing burden. The module's hot-path `compress`/
   `decompress` methods take `MemorySegment` directly; plain `byte[]` overloads exist alongside them
   for standalone use with zero FFM ceremony.
3. **`writer`/`reader` become thin wire adapters.** `FsstEncodingEncoder`/`FsstEncodingDecoder` keep
   all Vortex-specific plumbing (UTF-8 conversion, `ProtoFSSTMetadata`, `EncodeNode`/`EncodeResult`
   assembly, `Arena` allocation) and delegate the algorithm itself to `CompressorBuilder`/
   `Compressor`/`Decompressor`. The wire format (`vortex.fsst`) is unchanged — this is a pure
   algorithm/performance rewrite, not a wire-format change. The one adapter-side subtlety: the
   module numbers codes gain-descending internally (load-bearing for the hash table's
   first-writer-wins collision rule), while the wire format requires length-sorted order
   (`Compressor#codesSortedByLength()`), so the writer remaps every code the compressor emits
   through the wire permutation before writing it out.

## Consequences

### Positive

- Measured, real speedup (`JavaVsJniFsstBenchmark`, unmodified before and after — see the
  `[Unreleased]` CHANGELOG entry for the full table): `javaFsstEncode` 0.085 → 1.848 ops/s (~21.7x
  faster, closing the gap to `vortex-jni` from 36x slower to 1.6x slower); `javaFsstDecode` 5.243 →
  27.137 ops/s (~5.2x faster, gap 6.5x → 1.3x).
- The `fsst` module is independently testable and benchmarkable with zero Vortex file-format
  scaffolding in the loop (`FsstEncodingEncoderTest`'s wire-format tests are unaffected; new
  `fsst`-module tests exercise the algorithm directly) — the issue's own stated motivation for the
  extraction.
- A golden test (`PaperFigure2Test`) pins the paper's own worked example against a hand-built
  (not trained) symbol table — an external, human-verifiable fixed point independent of this
  project's training heuristics.

### Negative

- Real rework: five new PRs' worth of module surface (`Symbol`, `Matcher`/`ShortCodeTable`/
  `LossyPerfectHashTable`, `Sample`/`TrainingGeneration`/`CompressorBuilder`/`Compressor`,
  `Decompressor`) versus the ~250-line inline implementation it replaces.
- The compressor's internal (gain-descending) code numbering versus the wire's (length-sorted)
  numbering is a second permutation the adapter must get exactly right in both directions (symbol
  table population and code-stream remapping) — a subtle class of bug (wrong output, not a crash)
  that needs its own dedicated attention in any future change to either ordering.

### Risks to manage

- The branch-free matcher's lack of an input-end bound is a **structural** property, not a bug that
  was simply fixed once — any future caller of `Matcher.longestMatch`/`Compressor.compress` that
  operates on a range shorter than 8 bytes from the true end of a real buffer must carry the same
  `pos + length <= end` bound. This is exactly the kind of thing a future port or refactor could
  silently drop, since it is not obviously part of the "core algorithm."
- `LossyPerfectHashTable`'s 2048-slot sizing is a deliberately chosen constant (matching the
  `spiraldb/fsst` reference), not derived from first principles for this JVM's cache behavior — if a
  future benchmark shows a different size wins on the actual hardware/workload this project cares
  about, revisit it deliberately rather than assuming 2048 is universally correct.

## Alternatives considered

- **Reuse an existing Java library.** Searched (GitHub, web) and found none with zero dependencies;
  the only real Java-ecosystem candidates were the C reference (via JNI, which this project's FFM
  mandate rules out) and ports in other languages entirely (Rust, Go).
- **Keep the old inline implementation and just tune constants.** Rejected: the old matching
  algorithm's O(8)-probes-per-byte structure is the dominant cost, not a constant that tuning could
  fix — no amount of tuning turns a sequential 8-probe loop into an O(1) branch-free lookup.
- **`ByteBuffer` instead of `MemorySegment` at the module boundary.** Rejected: reintroduces a dual
  API (parity tests, two hot-path implementations to keep in sync) for no benefit over
  `java.lang.foreign`, which is already a zero-dependency JDK-standard API and is what every other
  module in this codebase uses for the same purpose.

## References

- The paper: Boncz, Neumann, Leis, "FSST: Fast Random Access String Compression", PVLDB 13(11),
  2020. <https://www.vldb.org/pvldb/vol13/p2649-boncz.pdf>
- Rust reference: [`spiraldb/fsst`](https://github.com/spiraldb/fsst) on GitHub.
- The 8-PR sequence (all on `main`):
  - `2b8db4f2` — module skeleton, `Symbol`, `Decompressor`
  - `8d778bac` — branch-free matching (`ShortCodeTable` + `LossyPerfectHashTable` + `Matcher`)
  - `1003a673` — `JavaVsJniFsstBenchmark` baseline (pre-rewrite numbers)
  - `4158c924` — training (adaptive sampling, min-count pruning, gain boost, final prune) — includes
    the boundary-overrun fix described in Context
  - `76e26eb6` — `MemorySegment` hot paths + unconditional-store decode
  - `1b9714c7` — rewire `writer`/`reader` adapters onto the `fsst` module
  - `75f58734` — golden test for the paper's Figure 2 worked example
  - `b218740f` — CHANGELOG before/after benchmark table
