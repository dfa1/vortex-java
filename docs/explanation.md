# Explanation

Background reading on design decisions, architecture, and benchmarks.

## Why pure Java instead of JNI

The official Vortex ecosystem provides JVM bindings via JNI (bundled native `.so`/`.dylib`).
JNI bindings are fast but add deployment friction: platform-specific artifacts, native build
toolchains, and crash-domain coupling between the JVM and native code. The JAR for
vortex-jni 0.74.0 is **33MB**.

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

## Benchmarks

JMH throughput (ops/s = full-file scans per second). Higher is better. Numbers
re-measured 2026-06-13 against commit `fea3aa29`.

**Environment:** Apple M5, Azul Zulu JDK 25.0.2, 3 warmup iterations, 5 measurement iterations, 2 forks.

### OHLC read — 10 M rows, 58.9 MB (Rust-written file, single-column projection)

| Benchmark           | vortex-java (ops/s)  | vortex-jni (ops/s) | Speedup      |
|---------------------|---------------|------------------|--------------|
| close (F64/ALP)     | 69.2 ± 0.1    | 50.6 ± 0.1       | **1.4×**     |
| volume (I64/bitpacked) | 118.0 ± 0.3 | 51.3 ± 3.5      | **2.3×**     |
| symbol (Utf8/varbin) | 106.5 ± 0.2  | 9.8 ± 0.1        | **10.9×**    |
| cascading (depth 3, volume) | 83.1 ± 4.3 | n/a         | —            |

### OHLC write — 10 M rows

| Benchmark | vortex-java (ops/s) | vortex-jni (ops/s) | Speedup      |
|-----------|--------------|------------------|--------------|
| write     | 4.4 ± 1.1    | 0.7 ± 0.1        | **6.4×**     |

The Java write is faster but also produces bigger files (more optimization work remains).
_Last measured before 2026-06-08; re-run pending._

### Big-file scan — 100 M rows × 4 I64 columns, ~3 GB (Rust-written file, all columns)

| Benchmark | vortex-java (ops/s) | vortex-jni (ops/s) | Speedup      |
|-----------|--------------|------------------|--------------|
| scan      | 20.4 ± 0.9   | 5.7 ± 0.6        | **3.6×**     |

_Last measured before 2026-06-08; re-run pending._

### Parquet vs Vortex read — NYC Yellow Taxi 2024-01, 3 M rows, 19 columns

Both formats store all 19 columns; projection happens at read time. Both sides scalar decode
(Hardwood disables SIMD on JDK 25; Vortex Java uses FFM scalar reads throughout).

**Environment:** Apple M5, OpenJDK 25, 5 warmup × 3 s, 10 measurement × 5 s, fork 1.
Re-measured 2026-06-08 against commit `051a794`.

Two Parquet variants are measured to isolate format cost from API overhead:

- **batch**: Hardwood's `ColumnReader.nextBatch()` + loop over `getDoubles()`/`getInts()` arrays — apples-to-apples with Vortex's
  batch fold
- **row-by-row**: Hardwood's `RowReader.next()` + `getDouble("col")` per row — measures the full row-cursor overhead on top of
  format decode

| Benchmark                                                                | ops/s        | vs Parquet batch         |
|--------------------------------------------------------------------------|--------------|--------------------------|
| `parquetRead` — batch, 1 col (`trip_distance`)                           | 137.0 ± 14.8 | baseline                 |
| `parquetReadRowByRow` — row cursor, 1 col                                | 69.7 ± 0.9   | 0.51× (2× API penalty)   |
| `vortexRead` — 1 col (`trip_distance`)                                   | 43.0 ± 1.5   | **0.31×**                |
| `parquetReadMultiColumn` — batch, 2 cols (`fare_amount`, `PULocationID`) | 137.4 ± 10.7 | baseline                 |
| `parquetReadMultiColumnRowByRow` — row cursor, 2 cols                    | 40.7 ± 1.9   | 0.30× (3.4× API penalty) |
| `vortexReadMultiColumn` — 2 cols                                         | 34.1 ± 1.6   | 0.25×                    |

**Known regression vs 2026-06-05 snapshot** (`vortexRead` was 235 → 43; `vortexReadMultiColumn`
was 122 → 34, Parquet path stable). The collapse is in the Vortex decode path on the
`ParquetImporter`-generated file — likely a cascade choice change that landed between
`363a885` and `051a794`. The OHLC bench (raw I64/F64 columns) recovered to 100+ ops/s
with the broadcast fast-path fix; this one did not, which points at a path the broadcast
fix doesn't cover (probably dict-of-ALP or ZSTD-on-F64 sneaking into the cascade). Bisect
+ fix tracked separately — these numbers are the current honest snapshot, not the target.

#### Why ZstdEncoding is excluded from the numeric cascade

Adding `ZstdEncoding` to `CASCADE_CODECS` improves file size (50 MB → 43 MB) because
Zstd out-compresses ALP on some F64 columns. But ZSTD decompression is an order of
magnitude slower than ALP reconstruction or bitpack unpack: single-column read throughput
collapses from 235 to 40 ops/s (6×), falling below Parquet batch (166.5 ops/s).

The smaller file is not worth the read regression. `ZstdEncoding` is retained in the
codec registry for `Utf8`/`Binary` columns where no faster structural alternative exists,
but it is not a candidate in the numeric cascade.

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
`ArrowArray`/`ArrowSchema` structs, and (3) materializing the result into Apache Arrow
`VectorSchemaRoot` objects before the application can read a single value. The JIT cannot
inline or optimize across the JNI boundary.

`vortex-java` eliminates all of that. The FFM API (`MemorySegment`) gives Java code a
typed, bounds-checked view directly into the OS mmap region. Decoding reads bytes directly
from that view with no copies, no intermediate Arrow format, and no boundary crossings.
The JIT sees the full decode path as ordinary Java bytecode.

#### Format-level advantages

**0. O(1) random access within a column.**
Fixed-width encodings (ALP, BitPacked) make row N directly addressable:
`byte_offset = column_base + N * fixed_bits / 8`. Reading row 5 000 000 does not
require scanning or decompressing rows 0–4 999 999. The OS pages in only the
memory-mapped region that is actually touched, so filtered scans that skip
large ranges pay nothing for the skipped bytes. Variable-width encodings (RLE,
RunEnd) are not O(1), but they encode low-cardinality columns where the run table
is tiny and the scan is over a handful of entries, not individual rows.

**1. mmap zero-copy.**
Vortex reads directly from the mmap'd `MemorySegment` — the file bytes _are_ the decode
input, no intermediate copies. Hardwood reads into internal page buffers and materializes
values before batch hand-off. Parquet also pays per-page framing overhead: RLE-encoded
definition/repetition levels, page header parsing, optional dictionary decode. Vortex's
layout is a flat array of encoded values with no per-row framing.

**2. Typed scatter instead of per-element copy.**
`DictEncoding` expansion uses `getAtIndex`/`setAtIndex` with loop-unswitched elemSize —
a single typed load + store per row. The prior `MemorySegment.copy(8 bytes)` per element
dominated 60% of JFR execution samples on multi-column scans before it was fixed.

```
Hardwood parquetRead (per 3 M rows)       Vortex vortexRead (per 3 M rows)
────────────────────────────────────      ──────────────────────────────────
47.6 MB on disk                           50 MB on disk
+ page header parse × N pages             + ALP decode (branch-free ×/+)
+ definition-level RLE decode × 3 M rows  + fold() tight loop, no dispatch
```

## File layout

A Vortex file is written front-to-back: buffers first, then metadata blobs, then a small
self-describing tail. A reader bootstraps from the last 8 bytes — no scanning required.

```
 byte 0
 ┌──────────────────────────────────────────────┐
 │  Buffer 0   (encoded segment)                │  ← column data, written by
 │  Buffer 1   (encoded segment)                │    each writeChunk() call.
 │  ...                                         │    Aligned, no per-buffer header.
 │  Buffer N-1 (encoded segment)                │
 ├──────────────────────────────────────────────┤
 │  Footer    (FlatBuffer)                      │  ← SegmentSpec[]: (offset,length)
 │                                              │    for every buffer above.
 ├──────────────────────────────────────────────┤
 │  DType     (Protobuf)                        │  ← schema: column names + types.
 ├──────────────────────────────────────────────┤
 │  Layout    (FlatBuffer)                      │  ← tree of Flat / Chunked /
 │                                              │    Zoned / Struct / Dict nodes;
 │                                              │    leaves point into Footer's
 │                                              │    SegmentSpec[] by index.
 ├──────────────────────────────────────────────┤
 │  Postscript (FlatBuffer)                     │  ← (offset,length) of Footer,
 │                                              │    DType, Layout above.
 ├──────────────────────────────────────────────┤
 │  Trailer   (8 bytes, little-endian)          │
 │    u16 version │ u16 postscriptLen │ "VTXF"  │  ← magic confirms file type;
 └──────────────────────────────────────────────┘     postscriptLen locates Postscript.
                                                EOF
```

Bootstrap sequence on open:

1. `mmap` whole file into one `MemorySegment`.
2. Read last 8 bytes → check `VTXF` magic, read `postscriptLen`.
3. Postscript sits at `EOF - 8 - postscriptLen`; parse it to get offsets of Footer, DType, Layout.
4. Parse Footer (segment table), DType (schema), Layout (tree).
5. Scans resolve Layout leaves to `SegmentSpec` → slice the mmap region zero-copy.

### Layout nodes

Every `Layout` node carries five fields: `encodingId`, `rowCount`, `metadata` (opaque
bytes for the node type), `children` (sub-layouts), `segments` (indices into the
file-level `SegmentSpec[]` table). Five node types exist today:

| ID              | Constant  | Children  | Role |
|-----------------|-----------|-----------|------|
| `vortex.struct` | `STRUCT`  | N         | Row type. One child per column. Root of every file. |
| `vortex.stats`  | `ZONED`   | 1         | Wraps a child layout and carries per-chunk min/max as zone maps. Pruned at scan time when filter predicate falls outside `[min, max]`. |
| `vortex.chunked`| `CHUNKED` | M (+1)    | Row-group sequence. Optional stats child at index 0 when `metadata[0] == 1` (per-chunk stats sidecar); remaining children are the data chunks. |
| `vortex.dict`   | `DICT`    | 2         | Dictionary-encoded leaf. `children[0]` = values layout, `children[1]` = codes layout. `metadata` holds the codes `PType` (varint, proto field 1). Decoder gathers values by code. |
| `vortex.flat`   | `FLAT`    | 0         | Leaf. References one `SegmentSpec` via `segments[0]`. Decoded by the encoding named in the segment's `arraySpec`, not by `encodingId` itself — see below. |

### Layout vs. array encoding

Two encoding-ID namespaces, easy to confuse:

- **Layout encoding** — node type in the layout tree (`vortex.flat`, `vortex.chunked`,
  `vortex.struct`, `vortex.stats`, `vortex.dict`). Tells the reader *how to navigate*.
- **Array encoding** — bytes-on-disk codec (`vortex.primitive`, `fastlanes.bitpacked`,
  `vortex.alp`, `vortex.alp_rd`, `vortex.for`, `vortex.runend`, `vortex.varbin`,
  `vortex.bool`, `vortex.constant`, `pco`, `zstd`, `fsst`, …). Tells the reader
  *how to decode the bytes* a `Flat` leaf points at.

A `Flat` leaf's `segments[0]` resolves to a `SegmentSpec` (offset + length in the file)
plus an `ArraySpec` (the array-encoding ID + child segment indices for cascaded codecs).
`Registry` looks up the array encoding and calls `decode(DecodeContext)`.

### Typical trees

Plain primitive column (e.g. `Int64`, single chunk):

```
 Struct
   └─ Zoned(stats)
        └─ Chunked              ← rowCount = total rows; one Flat per chunk
             ├─ Flat → SegmentSpec → fastlanes.bitpacked
             ├─ Flat → SegmentSpec → fastlanes.bitpacked
             └─ ...
```

Low-cardinality string column with dict layout:

```
 Struct
   └─ Zoned(stats)
        └─ Chunked
             └─ Dict
                  ├─ values:  Flat → SegmentSpec → vortex.varbin   (the unique strings)
                  └─ codes:   Flat → SegmentSpec → fastlanes.bitpacked  (one code per row)
```

### Pruning by zone maps

`vortex.stats` is the pruning hook. At scan time, when `ScanOptions` carries a
predicate, the reader walks `Zoned` nodes first: it inspects the child `Chunked`'s
per-chunk min/max sidecar, drops chunks whose `[min, max]` cannot satisfy the predicate,
and only opens segments for survivors. Smaller chunks (default 131 072 rows) →
finer-grained pruning than Parquet's row-group granularity (typically 1 M rows).

When `WriteOptions.enableZoneMaps` is false, the writer omits the wrapping `Zoned` node
and the chunk-0 stats child — the tree collapses to `Struct → Chunked → [Flat …]`.

## Memory model

`VortexReader` memory-maps the entire file into one `MemorySegment` (confined `Arena`).
Decoded `Array` buffers returned during a scan are zero-copy slices of that segment —
or of a per-chunk arena allocated for decode output. Close the reader to release
the mapped region.

### Per-chunk lifetime: `Chunk implements AutoCloseable`

`ScanIterator` implements `Iterator<Chunk>`. Each `Chunk` owns a confined `Arena`
that holds its decoded columnar buffers; calling `chunk.close()` releases the arena.
The idiomatic pattern is nested try-with-resources:

```java
try (var reader = VortexReader.open(path);
     var iter   = reader.scan(opts)) {           // releases iterator state
    while (iter.hasNext()) {
        try (Chunk chunk = iter.next()) {        // releases this chunk's arena
            // use chunk.column(...) — refs are valid only inside this block
        }
    }
}
```

Calling `iter.next()` while a previous chunk is still open throws
`IllegalStateException` — the API refuses to silently invalidate live references.
After `chunk.close()`, touching any previously-returned `Array` raises FFM's scope
check (`IllegalStateException` from `MemorySegment`), not undefined behavior.

For bulk consumption with auto-close per element, override the standard
`Iterator.forEachRemaining` is provided:

```java
try (var iter = reader.scan(opts)) {
    iter.forEachRemaining(c -> sum += c.column("price").fold(0.0, Double::sum));
}
```

For the reader / scan method signatures, see [reference.md#reader-api](reference.md#reader-api).

## Internal architecture

### Module dependency graph

```
         ┌──────────────────────────────────────────┐
         │                  core                    │
         │  DType · Encoding · Registry     │
         │  proto/fbs generated sources             │
         └──────────┬─────────────────┬─────────────┘
                    │                 │
          ┌─────────▼──────┐  ┌───────▼─────────────┐
          │     reader     │  │       writer        │
          │  VortexReader  │  │    VortexWriter     │
          │  ScanIterator  │  │  CascadingCompressor│
          └──┬─────────────┘  └───────┬─────────────┘
             │    ┌───────────────────┘
             │    │
     ┌───────▼────▼──┐   ┌──────────┐   ┌───────────────┐
     │  integration  │   │ parquet  │   │      csv      │
     │  (Rust oracle │   │          │   │               │
     │   for tests)  │   └────┬─────┘   └───────┬───────┘
     └───────────────┘        │                 │
                              └────────┬────────┘
                                       ▼
                               ┌───────────────┐
                               │      cli      │
                               │  fat jar      │
                               └───────────────┘
```

`performance` depends on `reader` + `writer` but is omitted for clarity.

### Read path

```
VortexReader.open(path)
  ├─ mmap entire file → MemorySegment (confined Arena)
  ├─ parse 8-byte trailer at EOF  →  version · postscriptLen · magic (VTXF)
  ├─ parse Postscript (FlatBuffer) → offsets to Footer / DType / Layout blobs
  ├─ parse Footer    (FlatBuffer) → SegmentSpec[] (offset+length per buffer)
  ├─ parse DType     (Protobuf)   → column names + types
  └─ parse Layout    (FlatBuffer) → tree of Flat/Chunked/Zoned/Struct nodes

vortexReader.scan(opts) → ScanIterator
  └─ pre-index Flat nodes into ChunkSpec[] — one entry per row group per column

ScanIterator.next() → Chunk (per row-group, AutoCloseable; owns its own Arena)
  └─ decodeLayout(layout, dtype, chunk.arena)
       ├─ Flat   → slice MemorySegment from mmap region
       │           └─ SerializedArrayDecoder.decode(seg, …)
       │                └─ ReadRegistry → EncodingDecoder.decode(DecodeContext)  →  Array (zero-copy)
       ├─ Chunked → collect Flat children, decode each, concatenate buffers
       ├─ Zoned   → skip zone-map metadata, recurse into child layout
       └─ Dict    → decode values layout + codes layout separately, then expand
```

Decoded `Array` buffers are either zero-copy slices of the mmap'd `MemorySegment`
or allocations in the chunk's own `Arena`. `chunk.close()` releases that arena —
after which any reference into it raises FFM's scope check.

### Write path

```
VortexWriter.create(channel, schema, opts)

writer.writeChunk(c -> c.put(column, data[]).put(...))
  └─ per column:
       CascadingCompressor.compress(dtype, values)
         ├─ try structural encodings in order: Dict → RunEnd → RLE → Constant → …
         │   each may wrap a child (Dict codes → BitPacked, Dict values → FSST, …)
         └─ apply codec layer: ALP / BitPacked / FOR / Pco / Zstd / …
       → EncodeResult (EncodeNode tree + buffer list)
  └─ write buffers to FileChannel, record SegmentSpec (offset + length)
  └─ record Layout node (encoding ID + rowCount + segment index)

writer.close()
  └─ write DType blob  (FlatBuffer)
  └─ write Footer blob (FlatBuffer) → SegmentSpec[] + ArraySpec[]
  └─ write Layout blob (FlatBuffer) → Struct → Zoned(Stats) → Chunked → [Flat …]
  └─ write Postscript  (FlatBuffer) → blob offsets + lengths
  └─ write 8-byte trailer           → version · postscriptLen · magic (VTXF)
```

### How `ReadRegistry` resolves encodings

`ReadRegistry.loadAll()` uses `ServiceLoader` to discover all `EncodingDecoder`
implementations on the classpath. Each decoder declares its identity via `encodingId()`.
At decode time the registry maps the typed id from the array node to the right
`EncodingDecoder` instance and calls `decode(DecodeContext)`.

Custom decoders can be added at build time: `ReadRegistry.builder().registerServiceLoaded().register(myDecoder).build()`.
Files with unrecognized IDs throw `VortexException` unless the builder enabled `allowUnknown()`.

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

## Vortex vs Parquet

Both are columnar formats for analytics workloads. The right choice depends on your
constraints.

### Format model

| Aspect | Parquet | Vortex |
|---|---|---|
| Encoding model | Fixed set: RLE, delta, dictionary, bit-packing | Pluggable tree — any encoding wraps any other |
| Layout unit | Row group → column chunk → page | Struct → Zoned(Stats) → Chunked → Flat |
| Random access | Must decode the entire page containing row N | O(1) for fixed-width encodings (ALP, BitPacked) |
| Statistics | Row-group min/max stored in footer | Per-chunk zone maps as a first-class layout node (Zoned) |
| Schema format | Thrift | FlatBuffer + Protobuf |
| Nullability | Definition levels (RLE-encoded per row) | Validity bitmap as a child encoding |
| Nested types | Repetition + definition levels | Recursive DType tree |

### Performance (read)

See the [benchmark tables](#benchmarks) for numbers. Summary:

- **Single-column scan**: Vortex 1.4× faster than Parquet batch. ALP + mmap zero-copy
  beats Parquet's RLE definition-level decode + page framing overhead.
- **Multi-column scan**: roughly even today. Gap caused by per-chunk dict encoding in Java
  vs Rust's global dict — closes when global dict is implemented.
- **Filtered scan (zone-map pruning)**: Vortex skips entire chunks when the Zoned
  min/max rules out a predicate. Parquet does the same at row-group granularity, but
  Vortex chunks are smaller (131 072 rows vs Parquet's typical 1 M row groups), so
  pruning is finer-grained.

### Ecosystem maturity

| | Parquet | Vortex |
|---|---|---|
| Tooling | Ubiquitous: Spark, DuckDB, pandas, Arrow, Hive, … | Early-stage — fewer readers outside the Rust impl |
| Spec | [Apache Parquet format spec](https://parquet.apache.org/docs/file-format/) | Rust reference implementation is the ground truth |
| Write maturity | Stable, battle-tested | Alpha — APIs will change |
| JVM library size | Parquet-mr: ~10 MB + transitive deps | vortex-java: < 1 MB, zero native deps |

### When to choose Vortex

- You control both writer and reader (no third-party tooling needed)
- You need sub-page random access or finer-grained zone-map pruning
- You want a zero-JNI, zero-Unsafe JVM library with no native artifacts to manage
- You are building an analytics engine and want a pluggable encoding layer

### When to stick with Parquet

- You need interoperability with Spark, DuckDB, pandas, or other ecosystem tools
- You cannot use an alpha-stability API
- Your workload is write-heavy and file-size efficiency is more important than read speed

## Design principles

- Zero-copy everywhere via FFM `MemorySegment`
- No JNI, no `sun.misc.Unsafe` ([FFM vs Unsafe](https://inside.java/2025/06/12/ffm-vs-unsafe/))
- Align with vortex-rust and vortex-go semantics
- Make the JIT happy: constant layouts, predictable strides, no virtual dispatch in hot loops
- Rigorous testing: unit + property-based + cross-language integration — see [testing strategy](testing.md)
- Tracking [JEP 469](https://openjdk.org/jeps/469) (Vector API) for future SIMD paths
