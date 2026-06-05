# Explanation

Background reading on design decisions, architecture, and benchmarks.

## Why pure Java instead of JNI

The official Vortex ecosystem provides JVM bindings via JNI (bundled native `.so`/`.dylib`).
JNI bindings are fast but add deployment friction: platform-specific artifacts, native build
toolchains, and crash-domain coupling between the JVM and native code. The JAR for
vortex-jni 0.72 is **258MB**.

This library takes a different approach — 100% Java, no JNI, no `sun.misc.Unsafe`.
It uses the Java FFM API (`MemorySegment` / `Arena`, Java 25+) for zero-copy memory-mapped
reads, making it easier to:

- embed in any JVM project without native-library management
- build and test on any platform with a standard JDK
- debug and profile with standard JVM tooling

The total JAR size is less than **1MB**.

### Why Java 25+

The FFM API (`MemorySegment`, `Arena`) was finalized as a standard API in JDK 22 (JEP 454).
Java 25 is the first LTS release to ship FFM as stable — requiring it means no preview flags,
no upgrade risk, and a supported LTS for users.

## Memory model

`VortexReader` memory-maps the entire file into one `MemorySegment` (confined `Arena`).
All `Array` buffers returned during a scan are zero-copy slices of that segment — their
lifetime is tied to the `VortexReader`. Close the reader to release the mapped region.

The iterator-based scan API is load-bearing: `iter.hasNext()` closes the previous chunk's
arena. Access all column data before calling `hasNext()` again.

For the reader / scan method signatures, see [reference.md#reader-api](reference.md#reader-api).

## Testing strategy

Unit tests verify internal correctness (encoding round-trips, edge cases), but the format
has no formal specification — the Rust implementation is the ground truth. Unit tests alone
miss cross-language wire-format bugs: Java can round-trip a value internally while writing
bytes that another implementation cannot decode.

The `integration` module addresses this by using the Rust JNI reader as a **test oracle**:
Java writes a file, the Rust reader decodes it, and the values are compared exactly.
Seeded random parameterized tests generate large, diverse inputs automatically,
covering edge cases no hand-written test would anticipate.

This combination caught two real bugs in ALP floating-point encoding:
- Java selected exponents outside the range Rust's decoder accepts (silent data corruption)
- Java's encode round-trip check used a different floating-point associativity than Rust's
  decode (`encoded * (F10[f] * IF10[e])` vs `(encoded * F10[f]) * IF10[e]`), passing values
  that Rust decoded differently

Both bugs were invisible to pure-Java tests and would have shipped undetected without the
cross-language oracle.

## Architecture: fewer layers = faster

```
  vortex-jni                              vortex-java
  ──────────────────────────────          ──────────────────────────
  ┌──────────────────────────┐            ┌──────────────────────┐
  │  Java App                │            │  Java App            │
  │  (BigIntVector.get(i))   │            │  (buffer.getAtIndex) │
  └────────────┬─────────────┘            └──────────┬───────────┘
               │ Arrow Java API                      │ FFM API
  ┌────────────▼─────────────┐                       │ (MemorySegment,
  │  Apache Arrow (Java)     │                       │  zero-copy slice)
  │  VectorSchemaRoot, …     │                       │
  └────────────┬─────────────┘            ┌──────────▼───────────┐
               │ Arrow C Data Interface   │  OS mmap region      │
               │ + JNI boundary crossing  │  (file on disk)      │
  ┌────────────▼─────────────┐            └──────────────────────┘
  │  Native lib (.so/.dylib) │
  │  Rust decode             │
  └────────────┬─────────────┘
               │ mmap / read
  ┌────────────▼─────────────┐
  │  OS mmap region          │
  │  (file on disk)          │
  └──────────────────────────┘

  4 layers, 1 JNI crossing,              2 layers, 0 boundary crossings,
  Arrow C Data Interface overhead         no intermediate format
```

The JNI path pays three costs per batch: (1) a JNI boundary crossing to call into native
code, (2) the Arrow C Data Interface handshake to pass decoded buffers back to the JVM as
`ArrowArray`/`ArrowSchema` structs, and (3) materialising the result into Apache Arrow
`VectorSchemaRoot` objects before the application can read a single value. The JIT cannot
inline or optimise across the JNI boundary.

`vortex-java` eliminates all of that. The FFM API (`MemorySegment`) gives Java code a
typed, bounds-checked view directly into the OS mmap region. Decoding reads bytes directly
from that view with no copies, no intermediate Arrow format, and no boundary crossings.
The JIT sees the full decode path as ordinary Java bytecode.

## Benchmarks

JMH throughput (ops/s = full-file scans per second). Higher is better.

**Environment:** Apple M5, OpenJDK 25, 3 warmup × 3 s, 5 measurement × 5 s, fork 1.

### OHLC read — 10 M rows, 58.9 MB (Rust-written file, single-column projection)

| Benchmark       | Java (ops/s) | JNI/Rust (ops/s) | Java speedup |
|-----------------|--------------|------------------|--------------|
| close (F64/ALP) | 76.7 ± 0.3   | 50.4 ± 2.8       | **1.5×**     |
| volume (I64)    | 127.9 ± 2.3  | 52.9 ± 0.6       | **2.4×**     |
| symbol (varbin) | 110.4 ± 0.4  | 9.6 ± 0.9        | **11.5×**    |

### OHLC write — 10 M rows

| Benchmark | Java (ops/s) | JNI/Rust (ops/s) | Java speedup |
|-----------|--------------|------------------|--------------|
| write     | 4.4 ± 1.1    | 0.7 ± 0.1        | **6.4×**     |

The Java write is faster but also produces bigger files (more optimization work remains).

### Big-file scan — 100 M rows × 4 I64 columns, ~3 GB (Rust-written file, all columns)

| Benchmark | Java (ops/s) | JNI/Rust (ops/s) | Java speedup |
|-----------|--------------|------------------|--------------|
| scan      | 20.4 ± 0.9   | 5.7 ± 0.6        | **3.6×**     |

### Parquet vs Vortex read — NYC Yellow Taxi 2024-01, 3 M rows, 19 columns

Both formats store all 19 columns; projection happens at read time. Both sides scalar decode
(Hardwood disables SIMD on JDK 25; Vortex Java uses FFM scalar reads throughout).
File sizes: Parquet 47.6 MB, Vortex Java 50 MB.

**Environment:** Apple M5, OpenJDK 25, 5 warmup × 3 s, 5 measurement × 5 s, fork 2.

| Benchmark | ops/s | ms/scan | vs Parquet |
|---|---|---|---|
| `parquetRead` — Hardwood, 1 col (`trip_distance`) | 73.9 ± 1.5 | 13.5 ms | baseline |
| `vortexRead` — 1 col (`trip_distance`) | 240.3 ± 3.8 | 4.2 ms | **3.3×** |
| `parquetReadMultiColumn` — 2 cols (`fare_amount`, `PULocationID`) | 45.2 ± 1.2 | 22.1 ms | baseline |
| `vortexReadMultiColumn` — 2 cols (`fare_amount`, `PULocationID`) | 129.1 ± 2.3 | 7.7 ms | **2.9×** |

#### Why Vortex is faster

**1. Batch columnar API vs row-by-row cursor.**
Hardwood's `RowReader` requires `rows.next()` + `rows.getDouble("trip_distance")` per row
— 2 virtual calls × 3 M rows = 6 M calls, plus a string-keyed column lookup on every
access. `DoubleArray.fold()` is a tight loop over a flat `MemorySegment`; the JIT sees a
scalar reduction over contiguous memory with no dispatch overhead.

**2. mmap zero-copy.**
Vortex reads directly from the mmap'd `MemorySegment` — the file bytes _are_ the decode
input, no intermediate copies. Hardwood reads into internal page buffers and materialises
values into a row cursor (one extra copy per page). Parquet also pays per-page framing
overhead: RLE-encoded definition/repetition levels, page header parsing, optional dictionary
decode. Vortex's layout is a flat array of encoded values with no per-row framing.

**3. Typed scatter instead of per-element copy.**
`DictEncoding` expansion uses `getAtIndex`/`setAtIndex` with loop-unswitched elemSize —
a single typed load + store per row. Prior to this fix, each element was expanded via
`MemorySegment.copy(8 bytes)`, which carries per-call bounds-check overhead and dominated
60% of JFR execution samples on multi-column scans.

```
Hardwood parquetRead (per 3 M rows)       Vortex vortexRead (per 3 M rows)
────────────────────────────────────      ──────────────────────────────────
47.6 MB on disk                           50 MB on disk
+ page header parse × N pages             + ALP decode (branch-free ×/+)
+ definition-level RLE decode × 3 M rows  + fold() tight loop, no dispatch
+ getDouble("col") × 3 M virtual calls
```

#### Why ZstdEncoding is excluded from the numeric cascade

Adding `ZstdEncoding` to `CASCADE_CODECS` improves file size (50 MB → 43 MB) because
Zstd out-compresses ALP on some F64 columns. But ZSTD decompression is an order of
magnitude slower than ALP reconstruction or bitpack unpack: single-column read throughput
collapses from 240 to 40 ops/s (6×), falling below Parquet's 73.9 ops/s baseline.

The smaller file is not worth the read regression. `ZstdEncoding` is retained in the
codec registry for `Utf8`/`Binary` columns where no faster structural alternative exists,
but it is not a candidate in the numeric cascade.

## Design principles

- Zero-copy everywhere via FFM `MemorySegment`
- No JNI, no `sun.misc.Unsafe` ([FFM vs Unsafe](https://inside.java/2025/06/12/ffm-vs-unsafe/))
- Align with vortex-rust and vortex-go semantics
- Make the JIT happy: constant layouts, predictable strides, no virtual dispatch in hot loops
- Rigorous testing: unit + property-based + cross-language integration
- Tracking [JEP 469](https://openjdk.org/jeps/469) (Vector API) for future SIMD paths
