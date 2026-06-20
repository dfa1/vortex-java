# ADR 0005: Vector API adoption strategy

- **Status:** Deferred — adoption gated on API finalization + benchmark evidence
- **Date:** 2026-06-13
- **Deciders:** project maintainer
- **Related:** [CLAUDE.md §Memory model — Hot-loop rule](../../CLAUDE.md),
  [TODO.md §Vector API](../../TODO.md)

## Context

Two TODO items describe Vector API adoption:

1. *"Evaluate Vector API (JEP 469+) for hot decode loops — only adopt where
   speedup is material and code stays readable."*
2. *"Support for preview Vector API — when JVM flag is activated, put in
   Encode/Decode context the type SCALAR / VECTOR; if flag is active, any
   encoder/decoder will switch to vectorized."*

These reflect two structurally different strategies. This ADR picks one and
defers the other.

### Current auto-vectorization story

The project's hot-loop rule in CLAUDE.md (no modulo, division, or
variable-branch per element) is specifically designed to let the JIT C2
superword pass auto-vectorize scalar loops. Past incidents (`ed658b7` →
`051a794` → `442021f`) show that a single `i % cap` in a 1M-row loop caused
5–10× regressions, and removing it recovered JIT-level SIMD. The baseline
is not "scalar"; it is already "JIT-SIMD where the loop body is clean enough
for C2 to vectorize."

### Vector API status in Java 25

JEP 469 (Vector API, 8th incubator) targets promotion out of incubator in a
near-future LTS. In Java 25 (this project's target) the API is still under
`jdk.incubator.vector` and requires `--add-modules jdk.incubator.vector` at
compile and runtime. It is not on the standard module path.

Adding a hard `--add-modules` requirement to the library forces every
downstream consumer to pass the flag — a deployment tax that breaks
container images, application servers, and OSGi environments that do not
set it.

### The two strategies

**Strategy A — Transparent per-loop adoption (no API change):**
Replace scalar inner loops in specific hot paths with Vector API calls.
No `DecodeContext` change. Controlled by a static `HAS_VECTOR` boolean
detected at class-load time via `VectorSpecies.preferredSpecies`. Falls
back to scalar if the module is absent.

Pros: no API surface added; callers unaffected; adoption is incremental
one loop at a time.
Cons: runtime module detection is fragile under OSGi / custom class
loaders; the incubator flag is still required at the JVM level; C2
already vectorizes most loops that are clean enough (uncertain net gain).

**Strategy B — Explicit SCALAR/VECTOR dispatch flag in `DecodeContext`:**
Add a `VectorMode { SCALAR, VECTOR }` enum to `DecodeContext`. Each
decoder checks `ctx.vectorMode()` and branches to a vectorized
implementation. Callers opt in at scan time.

Pros: clean opt-in, benchmarkable A/B at the call site; no forced flag.
Cons: adds API complexity to `DecodeContext` before there is evidence of
gain; doubles the code paths per decoder; the flag is meaningless without
the `--add-modules` JVM arg anyway — it cannot be "just a hint."

## Decision

**Defer both strategies. Adopt transparent per-loop (Strategy A) only when:**

1. **The API finalizes.** Vector API leaves `jdk.incubator.vector` and is
   on the standard module path — no `--add-modules` required. Expected in
   JDK 26 or 27 (post-JEP 469 promotion). Revisit this ADR at that point.
2. **Benchmark evidence shows C2 is insufficient.** A JMH benchmark
   against the specific loop shows ≥10% speedup over the C2-auto-vectorized
   baseline on at least two CPU architectures. The hot-loop rule already
   recovers most C2 SIMD; add Vector API only for loops where it cannot.
3. **The loop is a confirmed bottleneck.** JFR profile shows the target
   loop in the top-5 hot frames for a real workload, not just a
   micro-benchmark.

**Strategy B (DecodeContext flag) is rejected outright.** It adds API
complexity before there is any evidence it is needed. The opt-in
granularity is wrong — the bottleneck is one or two specific loops, not a
whole scan; a scan-level flag is too coarse. If a caller needs to A/B test
SCALAR vs VECTOR, they run two JVMs with/without `--add-modules`.

### Candidate loops for future Strategy A evaluation

When conditions 1–3 are met, evaluate in priority order:

| Loop | File | Why a candidate |
|------|------|-----------------|
| FastLanes bitpacked unpack | `BitpackedEncodingDecoder` | 64-lane pack/unpack; innermost loop over all rows |
| FrameOfReference add-base | `ForEncodingDecoder` | trivial broadcast-add; C2 already does this well |
| ZigZag decode | `ZigZagEncodingDecoder` | XOR + shift per element; likely already vectorized by C2 |
| ALP F64 reconstruction | `AlpEncodingDecoder` | fused multiply-add candidate |
| pco offset+base | `PcoEncodingDecoder` (future) | once pco encode lands |

FrameOfReference and ZigZag are low-priority because they are exactly the
kind of loop C2 superword handles best; measure before touching.

### What to do now (before conditions are met)

Continue applying the hot-loop rule from CLAUDE.md. Any loop that follows
the rule (no modulo, uniform body, no variable-target branch) is eligible
for C2 auto-vectorization at no extra cost. This is the correct first-pass
strategy. Vector API is an optimization layer on top, not a substitute for
loop structure.

## Consequences

### Positive
- No `--add-modules` flag requirement on downstream consumers.
- No speculative API surface added to `DecodeContext`.
- The hot-loop rule continues to be the primary performance lever.

### Negative
- If Vector API finalizes and shows large gains, this deferral costs
  time. Mitigation: the TODO items remain; the ADR is the trigger to
  act when conditions are met, not a permanent block.
- **Code duplication is a deliberate cost of the auto-vectorization
  strategy.** Keeping each hot loop's body uniform (condition for C2
  superword) forces *monomorphization* — one specialized copy per element
  width / ptype rather than a shared generic loop. `BitpackedEncodingDecoder`
  carries `unpackLoop16` / `unpackLoop32` / `unpackLoop64`, three ~96-line
  methods identical except for their `2L`/`4L`/`8L` strides and
  `LE_SHORT`/`LE_INT`/`LE_LONG` accessors (~254 duplicated lines, ~48%
  of the file). They cannot be merged: a single generic loop parameterized
  by element width reintroduces a per-element variable-width branch, which
  is exactly what makes C2 refuse to vectorize. The same trade-off produces
  the per-ptype duplication in `DeltaEncodingEncoder`, `AlpEncodingEncoder`,
  and the FrameOfReference paths.

  Consequence for tooling: SonarCloud reports this as duplication
  (project density ~4.7%, dominated by these files). That number is
  expected and should not be "fixed" by collapsing the loops — doing so
  would regress throughput. If the metric becomes noisy, suppress it for
  the specialized decoders via `sonar.cpd.exclusions` rather than
  refactoring. (Generated `fbs/`/`proto/` sources are already excluded
  from analysis via `sonar.exclusions` for the same reason — machine
  output, not hand-maintained code.)

### Risk
- If a downstream consumer benchmarks the library and finds a
  vectorization gap vs a Rust implementation before conditions 1–3 are
  met, this ADR may need to be superseded. That's acceptable; an
  external benchmark is exactly the evidence condition 2 asks for.

## References

- [CLAUDE.md §Hot-loop rule](../../CLAUDE.md) — the primary vectorization
  strategy in use today
- [TODO.md §Vector API items](../../TODO.md)
- JEP 469: Vector API (8th Incubator) —
  https://openjdk.org/jeps/469
- JMH benchmark harness: `./bench` command, `RustVsJavaReadBenchmark`
