# Explanation

Background reading on design decisions, architecture, and benchmarks.

## Why pure Java instead of JNI

The official Vortex ecosystem provides JVM bindings via JNI (bundled native `.so`/`.dylib`).
JNI bindings are fast but add deployment friction: platform-specific artifacts, native build
toolchains, and crash-domain coupling between the JVM and native code. The JAR for
vortex-jni 0.85.0 is **52.5MB**.

This library takes a different approach — 100% Java, no JNI, no `sun.misc.Unsafe`.
It uses the Java FFM API (`MemorySegment` / `Arena`, Java 25+) for zero-copy memory-mapped
reads, making it easier to:

- embed in any JVM project without native-library management
- build and test on any platform with a standard JDK
- debug and profile with standard JVM tooling

The reader stack (`vortex-reader` + `vortex-core` + `vortex-fsst`) is ~800 KB with no other
required dependencies — `vortex-jni` is a 75 MB jar plus Arrow, Netty and Guava.

### Why Java 25+

The FFM API (`MemorySegment`, `Arena`) was finalized as a standard API in JDK 22 (JEP 454).
Java 25 is the first LTS release to ship FFM as stable — requiring it means no preview flags,
no upgrade risk, and a supported LTS for users.

## Benchmarks

JMH throughput (ops/s = full-file scans or writes per second). Higher is better. Re-measured
2026-10-09 against vortex-jni 0.86.1: the OHLC, big-file and Parquet suites on `04d862b0`
(after the `core.simd` kernels, #484), the real-file scans on `73bf8026` (after the sequential
decode work, #497–#502).

**Environment:** Apple M5, Zulu JDK 25.0.2. Reads 3 forks (`-f 3`), writes 5 forks (`-f 5`):
the cascading write varies ~±5% from fork to fork, so fewer forks cannot resolve it. Each
suite ran on its own; sustained multi-suite runs thermally throttle the laptop.

### Real files — full scans of every column (`RealFileScanBenchmark`)

Raincloud corpus files (written by Rust through Python `vortex-data`, see
`scripts/hydrate-raincloud-corpus.sh`) plus the Java-written NYC taxi file, every column of every
chunk materialized. "Before" is `main` at the start of the read work (`b0d75ba4`); the gains come
from decoding run-end, sparse, dictionary and date-time-parts columns sequentially as Rust does
instead of a binary search per row (#497), OnPair's 16-byte over-copy (#498), bool bitmaps 8 rows
per step (#499), frame-of-reference added in place (#500) and zero-copy scan windows (#502).
2 forks per file; green taxi 2025 swings ±4–8 between forks.

| File                                  | Before (scans/s) | Now (scans/s) | Speedup |
|---------------------------------------|------------------|---------------|---------|
| TPC-DS `store_sales` (sf1)            | 0.95             | 6.77 ± 0.54   | 7.1×    |
| NYC taxi 2024-01 (Java-written)       | 1.07             | 8.55 ± 2.15   | 8.0×    |
| IMDB `title`                          | 4.38             | 22.48 ± 1.43  | 5.1×    |
| green taxi 2025                       | 11.6             | ~33           | 2.8×    |
| BI `corporations`                     | 8.37             | 22.76 ± 1.01  | 2.7×    |
| Appian `order`                        | 7.41             | 18.52 ± 0.05  | 2.5×    |
| TPC-H `orders` (sf1)                  | 71.1             | 97.63 ± 5.50  | 1.4×    |

### OHLC read — 10 M rows, 61.7 MB (Rust-written file, single-column projection)

| Benchmark                        | vortex-java (ops/s) | vortex-jni (ops/s) | Speedup  |
|----------------------------------|---------------------|--------------------|----------|
| close (F64/ALP)                  | 77.3 ± 0.4          | 46.4 ± 0.5         | **1.7×** |
| volume (I64/bitpacked)           | 108.1 ± 0.9         | 48.1 ± 1.2         | **2.2×** |
| symbol (Utf8)                    | 68.8 ± 1.3          | 15.3 ± 0.9         | **4.5×** |
| cascading (Java-written, volume) | 108.9 ± 1.0         | n/a                | —        |

vortex-jni reads are 3–6× faster than in the 2026-07 snapshot (vortex-jni 0.79: `close` 8.1,
`volume` 8.2, `symbol` 9.6 ops/s), so the gap narrowed from 2–11× to 1.7–4.5×. These are single
numeric or string columns, which the real-file work above barely touches.

### OHLC write — 10 M rows

Both writers start from the same plain Java arrays. vortex-jni accepts only Arrow batches, so
its number includes converting each batch to Arrow vectors (~280 ms per write), as any Java
caller of vortex-jni pays; vortex-jni documents no faster path (#477).

| Benchmark                               | vortex-java (ops/s) | vortex-jni (ops/s) | Ratio |
|-----------------------------------------|---------------------|--------------------|-------|
| write, cascading depth 3 (`defaults()`) | 1.107 ± 0.035       | 0.853 ± 0.002      | 1.30× |

| vortex-java cascade depth | ops/s         | Output (bytes) |
|---------------------------|---------------|----------------|
| 0 (first match)           | 2.536 ± 0.419 | 460,424,918    |
| 1                         | 0.940 ± 0.034 | 76,831,090     |
| 2                         | 1.006 ± 0.137 | 59,461,778     |
| 3 (default, Rust's)       | 1.107 ± 0.035 | 59,456,658     |

| Output (bytes)          | vortex-java | vortex-jni |
|-------------------------|-------------|------------|
| OHLC 10 M rows          | 59,456,658  | 61,681,692 |
| NYC taxi 2024-01 (2.96 M rows, 19 cols) | 42,338,902 | 44,463,892 |

vortex-java now writes about as fast as Rust's encoder alone without the Arrow conversion
(1.094 ± 0.004 ops/s, measured earlier the same day), single-threaded; Rust overlaps part of its
compression on runtime threads, and Java's writer can too with `WriteOptions#withExecutor` (#474).
The last step, 0.951 → 1.107 ops/s, is the lane-inner FastLanes bit-pack and delta kernels (#484).

How it got here — porting the Rust compressor's decisions rather than tuning Java's own:
2026-10-08 `main` wrote 0.433 ops/s and 6.9 GB allocated per write; #464–#469 brought it to
0.881 ops/s and 2.5 GB. The large steps were Rust's verdict pass inside sample estimation plus
its run-length skips (#465: +36%), its stats-only estimates for FoR / integer Dict (#466:
+22%), and the `alp` crate's 64-value exponent search (#469: +15%). The same ports took the
taxi file from 59.9 MB to 42.3 MB, mostly `tip_amount` (20.2 → 4.6 MB): Rust's bit-packing
verdict skips arrays with a negative minimum, which 1024-row samples of rare negative tips
had hidden.

### Big-file scan — 100 M rows × 4 I64 columns, ~3 GB (Rust-written file, all columns)

| Benchmark | vortex-java (ops/s) | vortex-jni (ops/s) | Speedup  |
|-----------|---------------------|--------------------|----------|
| scan      | 18.5 ± 1.2          | 5.6 ± 0.1          | **3.3×** |

### Parquet vs Vortex read — NYC Yellow Taxi 2024-01, 3 M rows, 19 columns

Both formats store all 19 columns; projection happens at read time. 3 warmup × 3 s,
5 measurement × 5 s, 3 forks.

Two Parquet variants are measured to isolate format cost from API overhead:

- **batch**: Hardwood's `ColumnReader.nextBatch()` + loop over `getDoubles()`/`getInts()` arrays — apples-to-apples with Vortex's
  batch fold
- **row-by-row**: Hardwood's `RowReader.next()` + `getDouble("col")` per row — measures the full row-cursor overhead on top of
  format decode

| Benchmark                                                                | ops/s        | vs Parquet batch         |
|--------------------------------------------------------------------------|--------------|--------------------------|
| `parquetRead` — batch, 1 col (`trip_distance`)                           | 283.3 ± 3.5  | baseline                 |
| `parquetReadRowByRow` — row cursor, 1 col                                | 84.0 ± 1.9   | 0.30× (3.4× API penalty) |
| `vortexRead` — 1 col (`trip_distance`)                                   | 273.4 ± 1.6  | 0.97×                    |
| `parquetReadMultiColumn` — batch, 2 cols (`fare_amount`, `PULocationID`) | 191.1 ± 22.8 | baseline                 |
| `parquetReadMultiColumnRowByRow` — row cursor, 2 cols                    | 48.6 ± 1.9   | 0.25× (3.9× API penalty) |
| `vortexReadMultiColumn` — 2 cols                                         | 130.5 ± 0.6  | 0.68×                    |

Hardwood's batch reads got much faster since the 2026-07-24 snapshot (`trip_distance` 172.7 →
283.3, two columns 109.1 → 191.1), so single-column Vortex is now at parity rather than 1.58×
ahead. Two-column `vortexReadMultiColumn` nearly tripled (46.9 → 130.5) but still trails the
two-column Parquet batch. Both Vortex columns here are nullable global dictionaries, read as a
`MaskedArray` whose values the benchmark folds.

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
47.6 MB on disk                           40.8 MB on disk
+ page header parse × N pages             + ALP decode (branch-free ×/+)
+ definition-level RLE decode × 3 M rows  + fold() tight loop, no dispatch
```

## File layout

A Vortex file is written front-to-back: buffers first, then metadata blobs, then a small
self-describing tail. A reader bootstraps from the last 8 bytes — no scanning required.

```
 byte 0
 ┌──────────────────────────────────────────────┐
 │  Buffer 0   (encoded segment)                │  ← column data, coalesced
 │  Buffer 1   (encoded segment)                │    into ~1 MB chunks per column.
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
| `vortex.stats`  | `ZONED`   | 1         | Wraps a child layout and carries a per-zone stats table as a zone map. The legacy `vortex.stats` form declares its columns with a `Stat` bitset; the Rust >= 0.76 `vortex.zoned` form declares them with an aggregate-spec list, adding `sum`/`null_count` alongside `min`/`max`. Pruned at scan time when the filter predicate falls outside `[min, max]`. |
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

### Map column layout

`vortex.map` (`DType.Map`) is a good illustration of how an array encoding's own children
cascade below a single `Flat` leaf — unlike the plain-primitive and dict examples above, its
tree has real depth. Logically, a map column stores a variable-length list of `{key, value}`
entries per row; physically, that's exactly a `ListView<Struct{key, value}>` wearing a
`vortex.map` label:

```
 Flat → SegmentSpec → vortex.map            (0 buffers, no metadata — 1 child: entries)
          └─ entries: vortex.listview       (must be a *bare* listview — see below)
               ├─ elements: vortex.struct   (non-nullable {key, value} entry structs)
               │    ├─ "key":   vortex.varbin | vortex.dict | ...  (any encoding accepting keyType)
               │    └─ "value": vortex.masked(...) when valueType is nullable, else same as key
               ├─ offsets: vortex.primitive (i32, one per map row — start index into elements)
               ├─ sizes:   vortex.primitive (i32, one per map row — entry count)
               └─ [validity: vortex.bool]   (present only when the map itself is nullable)
```

`vortex.map` itself carries zero buffers and no metadata — every bit of information about a
map column lives either in its `DType.Map(keyType, valueType, keysSorted, nullable)` schema
(a schema-only producer assertion for `keysSorted`, never checked against the data) or in the
single `entries` child.

**Two independent nullability slots, easy to conflate:**

- **A null *map row*** (`DType.Map(..., nullable=true)`) has no representation on the
  `vortex.map` node at all — it's delegated entirely to the `entries` child's own validity,
  carried in `vortex.listview`'s optional fourth child slot (a `vortex.bool` bitmap). This is
  why `entries` must be a *bare* `vortex.listview`, never wrapped in a `vortex.masked` — a
  masked wrapper would give a nullable map two different on-disk representations for the same
  logical value, so both the Rust reference and vortex-java reject it. Every other nullable
  `DType.List` column not underneath a map is unaffected by this and still wraps in
  `vortex.masked` + `vortex.list` as before.
- **A null *entry value*** (`DType.Map(key, value.asNullable(), ...)`, e.g. `{a: 1, b: null}`
  inside an otherwise-present map row) is a completely different bit: it rides the entry
  struct's own `value` field validity — ordinary `vortex.masked` wrapping, exactly like a
  nullable field anywhere else inside a `vortex.struct`. It has nothing to do with the map row
  being present or absent.

See [how-to.md#write-and-read-a-map-column](how-to.md#write-and-read-a-map-column) for the
write/read Java API this shape maps to, and `docs/reference.md#core-types` for `DType.Map`'s
field list.

### Pruning by zone maps

The zoned layout (`vortex.zoned`, legacy `vortex.stats`) is the pruning hook. At scan time,
when `ScanOptions` carries a predicate, the reader walks `Zoned` nodes first: it reads the
per-zone stats table (one zone per 8192 rows for `vortex.zoned`), drops chunks whose overlapping
zones' `[min, max]` cannot satisfy the predicate, and only opens segments for survivors. Smaller chunks (default 131 072 rows) →
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

For bulk consumption with auto-close per element, `ScanIterator` overrides the standard
`Iterator.forEachRemaining`:

```java
try (var iter = reader.scan(opts)) {
    iter.forEachRemaining(c -> sum += c.column(ColumnName.of("price")).fold(0.0, Double::sum));
}
```

For the reader / scan method signatures, see [reference.md#reader-api](reference.md#reader-api).

## Internal architecture

### Module dependency graph

```
         ┌──────────────────────────────────────────┐
         │                  core                    │
         │  DType · EncodingId · LayoutId           │
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

`performance` depends on `reader` + `writer` but is omitted for clarity, as are the
supporting/tooling modules `fsst` (standalone dependency of `reader`/`writer`), `inspector`,
`calcite`, `jdbc`, `bom`, `fuzz`, `fbs-gen`, and `proto-gen`.

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

`CascadingCompressor`'s philosophy — chain multiple lightweight, random-access-preserving
encodings and re-evaluate each child array independently, rather than picking one heavyweight
codec up front — follows [BtrBlocks](https://vortex.dev/blog/btrblocks-compressor), the design
the Rust reference's own compressor is built on.

### How `ReadRegistry` resolves encodings

`ReadRegistry.loadAll()` registers all built-in `EncodingDecoder` implementations. Each
decoder declares its identity via `encodingId()`. At decode time the registry maps the typed
id from the array node to the right `EncodingDecoder` instance and calls `decode(DecodeContext)`.

Custom decoders can be added at build time: `ReadRegistry.builder().registerDefaults().register(myDecoder).build()`.
Files with unrecognized IDs throw `VortexException` unless the builder enabled `allowUnknown()`.

For the testing strategy (unit vs. integration vs. mutation layers, the Rust oracle, and
concrete bugs it caught), see [testing.md](testing.md).

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
  vs Rust's global dict — global dict is now implemented as an opt-in
  `WriteOptions.withGlobalDict(true)` ([ADR 0021](../adr/0021-cardinality-bounded-global-dict-buffering.md)),
  not yet the default, so this benchmark (run with default write options) doesn't reflect it.
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
| JVM library size | Parquet-mr: ~10 MB + transitive deps | vortex-java: ~800 KB, zero native deps; vortex-jni: 75 MB + Arrow/Netty/Guava |

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
