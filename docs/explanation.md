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
       │           └─ Registry.decodeSegment(seg, …)
       │                └─ Encoding.decode(DecodeContext)  →  Array (zero-copy)
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

writer.writeChunk(Map<String, data[]>)
  └─ per column:
       CascadingCompressor.compress(dtype, values)
         ├─ try structural encodings in order: Dict → RunEnd → RLE → Constant → …
         │   each may wrap a child (Dict codes → BitPacked, Dict values → FSST, …)
         └─ apply codec layer: ALP / BitPacked / FOR / Pco / Zstd / …
       → EncodeResult (EncodeNode tree + buffer list)
  └─ write buffers to FileChannel, record SegmentSpec (offset + length)
  └─ record Layout node (encoding ID + rowCount + segment index)

writer.close()
  └─ write DType blob  (Protobuf)
  └─ write Footer blob (FlatBuffer) → SegmentSpec[] + ArraySpec[]
  └─ write Layout blob (FlatBuffer) → Struct → Zoned(Stats) → Chunked → [Flat …]
  └─ write Postscript  (FlatBuffer) → blob offsets + lengths
  └─ write 8-byte trailer           → version · postscriptLen · magic (VTXF)
```

### How `Registry` resolves encodings

`Registry.loadAll()` uses `ServiceLoader` to discover all `Encoding`
implementations on the classpath. Each encoding declares its ID via `encodingId()`.
At decode time the registry maps the ID string from the Layout node to the right
`Encoding` instance and calls `decode(DecodeContext)`.

Custom encodings can be added at build time: `Registry.builder().register(myEncoding).build()`.
Files with unrecognised IDs throw `VortexException` unless the builder enabled `allowUnknown()`.

## Why cascading compression

Vortex stores each column as a tree of encodings. The leaves are raw memory
segments, the inner nodes describe how those bytes turn back into values.
Without cascading, the writer picks one encoding from a static list and stops.
With cascading, the writer samples the data, lets candidate encodings expose
their open child slots, and recursively picks the best inner encoding for
every child. That recursion is what turns a per-encoding sales-pitch into
real compression.

Six representative columns, both paths. The same scenarios run as
regression tests so the ratios below stay anchored:

```
./mvnw verify -pl integration -am \
  -Dit.test=CompressionShowcaseIntegrationTest
```

Numbers below: 1 000 000 rows per column, JDK 25, vortex-java HEAD.

### The encodings in play, and what they're good for

Before reading the table, you need to know what the writer's pick means. The
labels in the size table aren't marketing terms — each one is a concrete
encoding with a sweet-spot data shape.

| Encoding         | What it does                                                                                           | "Friendly" data shape                                                |
|------------------|--------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------|
| **Primitive**    | Raw little-endian bytes. The baseline.                                                                 | Truly random data — no overhead, no compression.                     |
| **FoR**          | Frame-of-reference. Subtract `min(col)` from every value; store the minimum + the residuals.           | Bounded range: timestamps, monotonic IDs, anything where `max - min` is small. |
| **Bitpacked**    | Packs N values into the smallest bit-width that fits the maximum value.                                | Small-range integers (e.g. FoR residuals, dict codes, small counts). |
| **Dict**         | Build a values table; store one code per row pointing into it.                                         | Low-cardinality strings or numbers. **Loses** above ~50 % distinct.  |
| **ALP**          | Adaptive Lossless float compression. Detects a per-column scale + exponent so that values become small integers, then stores the mantissa. | "Physical world" doubles — prices, sensor readings, percentages. **Loses** on truly random F64. |
| **RLE / RunEnd** | Replace each run of identical values with `(run_end_index, value)`.                                    | Long runs of repeated values — status flags, partition IDs, slow-tick counters. |
| **VarBin**       | Concatenate all bytes, store offsets per row. Arrow's classic string layout.                           | Strings of any cardinality — the safe default for Utf8.              |
| **FSST**         | Builds a per-column symbol table of the most common byte bigrams, then rewrites the strings as 1-byte codes (or escape + literal). | Short, repetitive-ish strings — log lines, identifiers, JSON keys. Truly random strings beat it. |
| **Constant**     | Store the scalar once. Done.                                                                           | All values identical.                                                |

So "ALP-friendly doubles" = doubles that can be written as `mantissa × 10^-exp`
with a small mantissa. "FoR-friendly" = bounded range. "RLE-friendly" = long
runs. The cascading compressor does the matchmaking; this table tells you
when each match is a win.

### Headline table

```
dataset                          raw bytes    no-cascade   cascade(3)    ratio
--------------------------------------------------------------------------------
monotonic-timestamps              8 000 000   8 000 664    2 501 832     3.20x
low-card-categorical              4 200 000   1 000 921    1 000 921     4.20x
random-doubles                    8 000 000  12 751 764    8 000 672     1.00x
alp-friendly-doubles              8 000 000   8 000 748    1 256 208     6.37x
rle-int                           4 000 000   4 000 656        1 604  2 493.77x
highcard-strings                  6 000 000  17 977 750   10 551 040     0.57x
```

ratio = raw / cascade(3).

Three patterns to notice before we dig in:

- **Cascading is usually a strict improvement** — never bigger, often dramatically
  smaller (RLE: 4 MB → 1.6 KB; ALP-friendly F64: 8 MB → 1.3 MB).
- **Without cascading, the writer can make the file *bigger* than raw.** Static
  fallbacks pick the first encoding that "accepts" the dtype, even when it
  fails to compress (random F64 → ALP adds overhead; high-cardinality strings
  → Dict blows up the values vector).
- **The cascade isn't magic.** Truly random data ends up close to raw size
  because there's no structure to exploit.

### 1. Monotonic timestamps — FoR + Bitpacked

Sensor / log streams produce strictly increasing UNIX timestamps. Each value
is only ~1 second above the previous one, so the *delta* needs ~1 bit, but
the *absolute* value is 64 bits.

```
dtype: I64
sample data: [1767225600, 1767225601, 1767225602, 1767225603, …]
```

**Without cascading** — `Primitive` (raw 8 bytes/row) → 8 MB.

```
                 Primitive(I64)
                       │
                  8 000 000 bytes
```

**With cascading depth 3** — sample shows all-positive small deltas. FoR
subtracts the minimum so residuals fit in a tiny number of bits; the open
residual child cascades into Bitpacked, which packs `~20` bits per row
instead of 64.

```
                       FoR
              ref = 1767225600
                       │
                  Bitpacked
                bit_width = 20
                       │
              ~2.5 MB packed
```

3.2× smaller than raw. The same shape covers row IDs, monotonic counters,
millisecond timestamps, anything with bounded local deltas.

### 2. Low-cardinality categorical — Dict

A column of ticker symbols repeats the same 5 distinct strings across a
million rows.

```
dtype: Utf8
sample data: ["AAPL", "MSFT", "NVDA", "GOOGL", "AMZN", "AAPL", "MSFT", …]
```

**Without cascading** — the default codec list picks Dict before VarBin, so
even the no-cascade path catches this one.

```
                       Dict
                   /         \
              Values         Codes
            (5 strings)   (1 M × U8)
              ~20 B          ~1 MB
```

**With cascading depth 3** — same shape. Dict already wins; codes are tight
(1 byte each because cardinality ≤ 256). 4.2× smaller than raw with no
recursive work needed.

The new cardinality gate (added in 0.6) only kicks in *above* 50 % distinct;
this column is at 0.0005 % distinct, far below the gate.

### 3. Random doubles — Primitive (no compression possible)

The worst case. Truly random F64 values have no exploitable structure.

```
dtype: F64
sample data: [0.733, 0.642, 0.218, 0.875, 0.157, 0.488, 0.999, …]
```

**Without cascading** — the static list tries ALP first. ALP detects no
common scale factor, falls back to its uncompressed path, and the resulting
file is *bigger* than raw (12.7 MB vs 8 MB) thanks to per-row mantissa /
exponent bookkeeping.

```
                  ALP (degenerate)
                /        \    \
            Encoded   Patch    Patch
            (no-op)   idx      values
                      8 MB     ~4.7 MB
```

**With cascading depth 3** — the cost-based selector measures ALP, sees it's
worse than primitive, and picks `Primitive` raw.

```
                Primitive(F64)
                      │
                  8 000 000 bytes
```

Lesson: cascading is the cheapest insurance against the writer making the
file *larger*. Without it, "first match" can lose to raw bytes.

### 4. Slowly-varying doubles — ALP + Bitpacked

Stock prices, sensor readings, and most "physical world" doubles drift
slowly. They're representable as `mantissa × 10^-exp` with a small mantissa,
which is exactly what ALP is for.

```
dtype: F64
sample data: [100.05, 100.04, 100.06, 100.07, 100.05, 100.03, …]
```

**Without cascading** — ALP picks the right shape, but its mantissa child
gets emitted as raw `Primitive(I64)` → ~8 MB.

```
                       ALP
                     e=2 f=1
                       │
                  Primitive(I64)
                       │
                  8 000 000 bytes
```

**With cascading depth 3** — the same ALP outer, but its mantissa child
cascades into FoR + Bitpacked. The mantissa range fits in ~10 bits.

```
                       ALP
                     e=2 f=1
                       │
                       FoR
                       │
                  Bitpacked
                bit_width = 10
                       │
                  ~1.25 MB
```

6.4× smaller than raw. This is where Vortex really shines vs Parquet's
fixed page-level codecs: nested arithmetic encodings stack cleanly.

### 5. Run-encoded ints — RunEnd

Long runs of the same value: status flags, monotonic counters that tick
slowly, partition IDs.

```
dtype: I32
sample data: [1,1,1,…(10 000)…,1, 2,2,2,…(10 000)…,2, 3,3,…]
```

**Without cascading** — `Primitive` → 4 MB.

```
                 Primitive(I32)
                       │
                  4 000 000 bytes
```

**With cascading depth 3** — the run structure is detected, RunEnd encodes
each run as `(end_index, value)` and both children compress.

```
                     RunEnd
                   /        \
             Run ends     Values
             (100 ints)   (100 ints)
              ~400 B       ~400 B
                  ↓             ↓
              Bitpacked     Bitpacked
              (or FoR)       (or FoR)
```

**2 493×** smaller than raw — the run is so long that the actual payload
collapses to ~1.6 KB.

### 6. High-cardinality strings — Dict fail / FSST partial win

A million all-distinct random 6-character strings — the kind of column that
turns into a tar pit for dictionary-style encodings.

```
dtype: Utf8
sample data: ["wkzqof", "tdmgxh", "ablrpe", "yvcjsi", …]
```

**Without cascading** — Dict is the first acceptor for Utf8 in the default
codec list. It builds a dictionary nearly as big as the input plus a 4-byte
code per row.

```
                       Dict
                   /         \
              Values         Codes
            (1 M strings)  (1 M × U32)
              ~10 MB         ~4 MB
                                =  18 MB (3× raw!)
```

**With cascading depth 3** — the new (0.6) cardinality gate in `DictEncoding`
detects > 50 % distinct on the sample, returns `notApplicable`, and the
cascade rotates to FSST.

```
                       FSST
              ╭────────┼────────╮
          Symbol     Symbol    Compressed
           table     lengths    payload
           (~2 KB)    (~255 B)   (~5 MB)
                       │
                   ↓ cascade
              uncompressed_lens     codes_offsets
              (Constant: ~4 B)      (FoR+Bitpacked)
                                       ~5 MB
```

Result: 10.5 MB — still larger than the 6 MB raw, but **42 % smaller than
no-cascade**. Truly random short strings are hard for any encoder (Rust hits
the same wall on this input). Java's FSST symbol-table builder is also less
aggressive than Rust's for now — see `TODO.md`.

### Takeaways

- **Always use `WriteOptions.cascading(...)` unless you have a reason not
  to.** The default is `cascading(0)` for legacy compatibility; we'll
  probably flip the default in a future major.
- **Cascade depth 3** is the sweet spot in practice. Deeper costs more
  encode CPU for tiny diminishing returns; shallower misses key combos
  like ALP→FoR→Bitpacked.
- **The encoding tree is the API.** If you `vortex inspect <file>` you'll
  see the exact structure of each column, with sizes per node. No black
  box.
- **Codec choice is data-dependent.** No single encoding is "best". The
  point of cascading is to let the writer admit when it's wrong and try
  again.

## Benchmarks

JMH throughput (ops/s = full-file scans per second). Higher is better.

**Apples-to-apples.** The Java and JNI numbers in the OHLC and big-file tables
below come from reading the **same on-disk file**, written once by `vortex-jni`
(Rust-chosen encodings) and opened by both decoders. Differences are pure
decoder cost — same bytes in, same row count out. See
[`RustVsJavaReadBenchmark.@Setup`](../performance/src/main/java/io/github/dfa1/vortex/performance/RustVsJavaReadBenchmark.java)
— `sharedBenchFile` is written by `writeJni(...)` and shared across every
`jniRead*` and `javaRead*` method (`javaReadCascading` is the one exception:
it reads a Java-written file with `WriteOptions.cascading(3)`).

**Environment:** Apple M5, OpenJDK 25, 3 warmup × 3 s, 5 measurement × 5 s, fork 1.
Numbers below re-measured 2026-06-11.

### OHLC read — 10 M rows, 58.9 MB (Rust-written file, single-column projection)

| Benchmark                   | Java (ops/s)   | JNI/Rust (ops/s) | Java speedup |
|-----------------------------|----------------|------------------|--------------|
| close (F64/ALP)             | 68.8 ± 0.2     | 50.2 ± 1.2       | **1.4×**     |
| volume (I64/bitpacked)      | 118.9 ± 0.9    | 50.1 ± 2.6       | **2.4×**     |
| symbol (varbin)             | 104.8 ± 5.1    | 9.7 ± 0.5        | **10.8×**    |
| cascading (depth 3, volume) | 86.7 ± 2.5     | n/a              | —            |

### OHLC write — 10 M rows

| Benchmark | Java (ops/s) | JNI/Rust (ops/s) | Java speedup |
|-----------|--------------|------------------|--------------|
| write     | 4.4 ± 1.1    | 0.7 ± 0.1        | **6.4×**     |

The Java write is faster but also produces bigger files (more optimization work remains).
_Last measured before 2026-06-08; re-run pending._

### Big-file scan — 100 M rows × 4 I64 columns, ~3 GB (Rust-written file, all columns)

| Benchmark | Java (ops/s) | JNI/Rust (ops/s) | Java speedup |
|-----------|--------------|------------------|--------------|
| scan      | 20.4 ± 0.9   | 5.7 ± 0.6        | **3.6×**     |

_Last measured before 2026-06-08; re-run pending._

### Parquet vs Vortex read — NYC Yellow Taxi 2024-01, 3 M rows, 19 columns

Both formats store all 19 columns; projection happens at read time. Both sides scalar decode
(Hardwood disables SIMD on JDK 25; Vortex Java uses FFM scalar reads throughout).

**Environment:** Apple M5, OpenJDK 25, 5 warmup × 3 s, 10 measurement × 5 s, fork 1.
Re-measured 2026-06-08 against commit `051a794`.

Two Parquet variants are measured to isolate format cost from API overhead:

- **batch**: `ColumnReader.nextBatch()` + loop over `getDoubles()`/`getInts()` arrays — apples-to-apples with Vortex's
  batch fold
- **row-by-row**: `RowReader.next()` + `getDouble("col")` per row — measures the full row-cursor overhead on top of
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

#### Format-level advantages (theory)

The bullets below describe the structural reasons Vortex *should* outperform Parquet on
single-column reads, and did so in the 2026-06-05 measurement (235 → vs Parquet's 166).
The current Vortex score sits below Parquet on this benchmark while the regression noted
above is being investigated; the format properties themselves are unchanged.

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
input, no intermediate copies. Hardwood reads into internal page buffers and materialises
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

#### Why ZstdEncoding is excluded from the numeric cascade

Adding `ZstdEncoding` to `CASCADE_CODECS` improves file size (50 MB → 43 MB) because
Zstd out-compresses ALP on some F64 columns. But ZSTD decompression is an order of
magnitude slower than ALP reconstruction or bitpack unpack: single-column read throughput
collapses from 235 to 40 ops/s (6×), falling below Parquet batch (166.5 ops/s).

The smaller file is not worth the read regression. `ZstdEncoding` is retained in the
codec registry for `Utf8`/`Binary` columns where no faster structural alternative exists,
but it is not a candidate in the numeric cascade.

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
- Rigorous testing: unit + property-based + cross-language integration
- Tracking [JEP 469](https://openjdk.org/jeps/469) (Vector API) for future SIMD paths
