# ADR 0007: Pure-Java `vortex.pco` encoder

- **Status:** Accepted (E0 gate cleared — E1 is next)
- **Date:** 2026-06-13
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —

## Context

Java can decode Pco-encoded files produced by Rust but cannot write them. All production
fixtures are Rust-produced; decode is stable and passes the full integration suite. A
Java encoder closes the last major write-side gap and enables pure-Java round-trips for
workloads that need the best integer / float compression ratio.

Encoding is substantially harder than decoding:

- Encode chooses mode + bin layout + tANS weights; decode just executes a fixed program.
- Bin optimization is dynamic programming over histogram partitions (`bin_cost`).
- tANS encoding table differs from the decode table (weight quantization → symbol table).
- Mode selection samples input, trial-compresses against candidates (Classic, FloatMult,
  IntMult, FloatQuant), picks best ratio. See `pco/src/sampling.rs`.
- No oracle: encode is non-deterministic. Validation = round-trip Java→Java + Java→Rust.

What can be reused from the decode path:

- `LeBitReader` (decode) ↔ `LeBitWriter` (encode, new) — same bit layout, opposite direction.
- tANS table structure (decode-built) ↔ tANS encode table (`ans/encoding.rs`).
- Mode constants, delta constants, proto types.

## Decision

Implement in phases, landing each independently green. The "valid but suboptimal" path
(`E1 + E2 + E9`, ~5 days) gives Rust-compatible wire format first; ratio parity follows.

### References

- [`pco/src/wrapped/chunk_compressor.rs`](https://github.com/pcodec/pcodec/blob/main/pco/src/wrapped/chunk_compressor.rs)
- [`pco/src/bin_optimization.rs`](https://github.com/pcodec/pcodec/blob/main/pco/src/bin_optimization.rs)
- [`pco/src/histograms.rs`](https://github.com/pcodec/pcodec/blob/main/pco/src/histograms.rs)
- [`pco/src/ans/`](https://github.com/pcodec/pcodec/tree/main/pco/src/ans)
- [`pco/src/sampling.rs`](https://github.com/pcodec/pcodec/blob/main/pco/src/sampling.rs)
- [`spiraldb/fastlanes-rs`](https://github.com/spiraldb/fastlanes-rs)

### Phases

| Phase | What                                  | Estimate |
|-------|---------------------------------------|----------|
| E1    | `LeBitWriter` + round-trip property test | 1 day |
| E2    | Classic mode, fixed bins, no optimization — valid (suboptimal) stream | 2 days |
| E3    | Histogram + bin optimization DP (`bin_cost`, `log2_approx`) | 2 days |
| E4    | tANS weight quantization + encoding table | 2 days |
| E5    | Delta Consecutive encoder | 1 day |
| E6    | Mode selection (stratified sample, trial-compress) | 2 days |
| E7    | Multi-chunk, multi-page, nullable input | 1 day |
| E8    | Java→Rust integration tests (`JavaWritesRustReadsIntegrationTest`) | 1 day |
| E9    | `EncodeResult` glue — `PcoEncoding.Encoder.encode` | 0.5 day |

**Total:** ~12.5 days. Suboptimal path (E1+E2+E9): ~3.5 days.

### Risks

- **Bin optimization DP** — bug → catastrophic ratio loss but still valid output; silent.
  Test ratio against Rust on fixed inputs.
- **tANS weight quantization** — bug → Rust decoder rejects with checksum mismatch.
  Caught fast by Java→Rust integration test.
- **Mode selection** — wrong mode = valid output but poor ratio; same silent failure.
- **`log2_approx`** — fast-math hack in Rust; Java can use `Math.log` (exact, slower);
  measure JMH cost before chasing parity.

## Consequences

### Positive

- Pure-Java Pco round-trips without a Rust JNI process.
- Closes the last major write-side compression gap vs Rust.
- `LeBitWriter` (E1) is immediately useful for other future bit-oriented encoders.

### Negative

- ~12.5 person-days of focused work.
- Two `ServiceLoader` entries per phase (decoder + encoder).
- tANS weight quantization must be bit-exact against Rust — no leeway for "good enough".

## Alternatives considered

- **Wrap Rust via JNI** — zero Java implementation effort, but adds a native dependency
  to the write path, defeating the "pure-Java write runtime" goal.
- **Defer indefinitely** — acceptable until a Java consumer explicitly asks for Pco write;
  decode is sufficient for reading Rust-produced files. Not chosen because E1 is a clean,
  contained unit that unblocks future work.
