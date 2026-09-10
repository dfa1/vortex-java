# How-to guides

Task-oriented recipes. Each section solves one concrete goal.
For API details (classes, methods, operator tables), see [reference.md](reference.md).
For the design rationale behind the iterator lifecycle, see [explanation.md#memory-model](explanation.md#memory-model).

---

## Build the CLI

Build the fat jar once; reuse it for every CLI recipe below:

```bash
./mvnw package -pl cli -am -DskipTests
java -jar cli/target/vortex-cli-*-all.jar <subcommand> [args]
```

For the full subcommand list, see [reference.md#cli](reference.md#cli).

---

## Count rows

**API:**

```java
var total = new java.util.concurrent.atomic.AtomicLong();
try (VortexReader vf = VortexReader.open(Path.of("data.vortex"));
     var iter = vf.scan(ScanOptions.all())) {
    iter.forEachRemaining(c -> total.addAndGet(c.rowCount()));
}
System.out.println(total.get());
```

**CLI:**

```bash
java -jar cli/target/vortex-cli-*-all.jar count data.vortex
```

---

## Inspect file structure

**API:**

```java
try (VortexReader vf = VortexReader.open(Path.of("data.vortex"))) {
    System.out.println(vf.dtype());   // column names and types
    System.out.println(vf.layout());  // layout tree (Struct → Chunked → Flat …)
}
```

**CLI:**

```bash
# column names and types
java -jar cli/target/vortex-cli-*-all.jar schema data.vortex

# full layout tree with encoding IDs, row counts, buffer sizes
java -jar cli/target/vortex-cli-*-all.jar inspect data.vortex

# per-column min/max statistics
java -jar cli/target/vortex-cli-*-all.jar stats data.vortex
```

---

## Inspect interactively (TUI)

For files where the static `inspect` output is too dense, the `tui` subcommand opens
an interactive terminal browser. The layout tree is loaded lazily — per-array
statistics, dictionary entries, hex previews, and decoded data are fetched
on demand as you navigate.

```bash
# local file
java -jar cli/target/vortex-cli-*-all.jar tui data.vortex

# remote file (HTTP range requests)
java -jar cli/target/vortex-cli-*-all.jar tui https://example.com/data.vortex
```

A loading bar prints to stderr while metadata is read, then the screen splits
into a tree pane on the left and a details pane on the right:

```
 data.vortex                                                                    
 v struct  (3000000 rows)                                                       
     v timestamp: vortex.zoned  (3000000 rows, stats)              | encoding: vortex.zoned
         > vortex.chunked  (3000000 rows)                          | rows:     3000000
     > symbol: vortex.dict  (3000000 rows)                         | min:      1700000000000
     > price: vortex.alp  (3000000 rows, stats)                    | max:      1700002999000
       volume: fastlanes.bitpacked  (3000000 rows)                 |
                                                                   | bit width: 21
                                                                   | offsets:   8 segments
                                                                   |
                                                                   | preview (hex):
                                                                   |   0x00f0c2e9b3 8c01...
 ↑↓ nav   →/Enter expand   ← collapse   q quit                                  
```

**Keymap:**

| Key                 | Action                                  |
|---------------------|-----------------------------------------|
| `↑` / `↓`           | Move selection one row                  |
| `PgUp` / `PgDn`     | Jump 10 rows                            |
| `Home` / `End`      | Jump to first / last visible row        |
| `→`                 | Expand node                             |
| `←`                 | Collapse node                           |
| `Enter`             | Toggle expand / collapse                |
| `q` / `Q` / `Esc`   | Quit                                    |

**Tree markers:**

| Marker | Meaning                                |
|--------|----------------------------------------|
| `>`    | Collapsed (has children)               |
| `v`    | Expanded                               |
| (none) | Leaf node                              |

The `, stats` suffix on a row indicates the node carries zone-map statistics
(min / max per chunk) — selecting it shows the values in the details pane.
`vortex.dict` nodes show their dictionary entries; flat numeric leaves show
a hex preview of the encoded buffer plus decoded data.

**Windows: Git Bash / MinTTY.** The TUI calls `GetConsoleMode` on stdio,
which only works on a real Windows console handle. Git Bash and other
MinTTY-based shells pipe stdio through the terminal emulator, so the
console APIs fail and the TUI aborts with a `winpty` pointer in the error
message. Two options:

```bash
# wrap with winpty (ships with Git for Windows)
winpty java -jar vortex-cli-*-all.jar tui data.vortex

# or switch to a terminal that attaches a real console: Windows Terminal,
# PowerShell, or cmd.exe
```

`inspect` (static, non-interactive) works in any shell since it does not
toggle terminal modes.

---

## Project columns

**API:**

```java
ScanOptions opts = ScanOptions.all().withColumns("symbol", "price");

try (VortexReader vf = VortexReader.open(Path.of("trades.vortex"));
     var iter = vf.scan(opts)) {
    while (iter.hasNext()) {
        try (var chunk = iter.next()) {
            // chunk.columns() contains only "symbol" and "price"
        }
    }
}
```

**CLI:**

```bash
java -jar cli/target/vortex-cli-*-all.jar select trades.vortex symbol price
```

---

## Filter rows

**API:**

```java
RowFilter filter = RowFilter.gte("volume", 1_000_000);
ScanOptions opts = ScanOptions.all().withFilter(filter);

try (VortexReader vf = VortexReader.open(Path.of("trades.vortex"));
     var iter = vf.scan(opts)) {
    while (iter.hasNext()) {
        try (var chunk = iter.next()) {
            // only rows where volume >= 1_000_000
        }
    }
}
```

Combine filters with `and()`:

```java
RowFilter filter = RowFilter.gte("volume", 1_000_000)
    .and(RowFilter.lte("price", 200.0));
```

For the supported predicate set and CLI operator syntax, see
[reference.md#rowfilter](reference.md#rowfilter-iogithubdfa1vortexreaderrowfilter)
and [reference.md#filter-expression-syntax](reference.md#filter-expression-syntax).

**CLI:**

```bash
java -jar cli/target/vortex-cli-*-all.jar filter trades.vortex "volume >= 1000000"
```

---

## Preview the first N rows

**API:**

```java
ScanOptions opts = ScanOptions.all().withLimit(10);

try (VortexReader vf = VortexReader.open(Path.of("data.vortex"));
     var iter = vf.scan(opts)) {
    while (iter.hasNext()) {
        try (var chunk = iter.next()) {
            // at most 10 rows total across all chunks
        }
    }
}
```

**CLI:**

```bash
# export first 10 rows to CSV
java -jar cli/target/vortex-cli-*-all.jar export data.vortex | head -n 11   # 1 header + 10 rows
```

---

## Convert Parquet to Vortex

**API:**

```java
import io.github.dfa1.vortex.parquet.ParquetImporter;

ParquetImporter.importParquet(
    Path.of("data.parquet"),
    Path.of("data.vortex")
);
```

Project specific columns during conversion:

```java
import io.github.dfa1.vortex.parquet.ImportOptions;

ImportOptions opts = ImportOptions.defaults()
    .withColumns(List.of("trip_distance", "fare_amount"));

ParquetImporter.importParquet(Path.of("data.parquet"), Path.of("data.vortex"), opts);
```

From a remote Parquet file over HTTP(S), fetched entirely through targeted Range requests —
no full-file download occurs:

```java
ParquetImporter.importParquet(URI.create("https://example.com/data.parquet"), Path.of("data.vortex"));
```

**CLI:**

```bash
# output defaults to <input>.vortex
java -jar cli/target/vortex-cli-*-all.jar import data.parquet

# explicit output path
java -jar cli/target/vortex-cli-*-all.jar import data.parquet out.vortex

# remote source — Parquet only, output path required or derived from the URL's file name
java -jar cli/target/vortex-cli-*-all.jar import https://example.com/data.parquet out.vortex
```

---

## Convert Vortex to Parquet

Flat schemas only (`Bool`, non-`F16` `Primitive`, `Utf8`, `Binary`, `vortex.timestamp`); a
`Struct`/`List`/`Map` top-level column throws `UnsupportedOperationException`.

**API:**

```java
import io.github.dfa1.vortex.parquet.ParquetExporter;

ParquetExporter.exportParquet(
    Path.of("data.vortex"),
    Path.of("data.parquet")
);
```

Project specific columns during conversion:

```java
import io.github.dfa1.vortex.parquet.ExportOptions;

ExportOptions opts = ExportOptions.defaults()
    .withColumns(List.of("trip_distance", "fare_amount"));

ParquetExporter.exportParquet(Path.of("data.vortex"), Path.of("data.parquet"), opts);
```

From an already-open handle — a local `VortexReader` or a remote `VortexHttpReader` — without an
intervening local copy; the handle isn't closed here, the caller keeps ownership of its lifecycle:

```java
import io.github.dfa1.vortex.reader.VortexHttpReader;

try (var vortex = VortexHttpReader.open(URI.create("https://example.com/data.vortex"))) {
    ParquetExporter.exportParquet(vortex, Path.of("data.parquet"));
}
```

**CLI:**

```bash
# dispatches on the output extension — same `export` subcommand CSV export uses
java -jar cli/target/vortex-cli-*-all.jar export data.vortex out.parquet

# remote source — Parquet output only, and the output path must be given explicitly
java -jar cli/target/vortex-cli-*-all.jar export https://example.com/data.vortex out.parquet
```

---

## Convert CSV to Vortex

**API:**

```java
import io.github.dfa1.vortex.csv.CsvImporter;

CsvImporter.importCsv(Path.of("data.csv"), Path.of("data.vortex"));
```

From a remote CSV file over HTTP(S). CSV is read front to back in one streaming pass, so the
response body is consumed directly — no Range requests, no local temp file:

```java
CsvImporter.importCsv(URI.create("https://example.com/data.csv"), Path.of("data.vortex"));
```

**CLI** (types are inferred from the data):

```bash
java -jar cli/target/vortex-cli-*-all.jar import data.csv
# writes data.vortex, prints size savings

# remote source
java -jar cli/target/vortex-cli-*-all.jar import https://example.com/data.csv out.vortex

# straight to Parquet — chains CSV -> temp Vortex -> Parquet internally, local or remote source
java -jar cli/target/vortex-cli-*-all.jar import data.csv out.parquet
java -jar cli/target/vortex-cli-*-all.jar import https://example.com/data.csv out.parquet
```

---

## Import from a JDBC source

**API:**

```java
import io.github.dfa1.vortex.jdbc.JdbcImporter;

import java.sql.Connection;
import java.sql.DriverManager;

try (Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost/mydb", "user", "pw")) {
    JdbcImporter.importTable(conn, "trades", Path.of("trades.vortex"));
}
```

An arbitrary query instead of a whole table:

```java
try (Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost/mydb", "user", "pw")) {
    JdbcImporter.importQuery(conn, "SELECT * FROM trades WHERE volume > 1000000", Path.of("trades.vortex"));
}
```

Tune the driver fetch size, Vortex chunk size, write options, and progress reporting:

```java
import io.github.dfa1.vortex.jdbc.JdbcImportOptions;

JdbcImportOptions opts = JdbcImportOptions.defaults()
    .withFetchSize(50_000)
    .withProgressListener((rowsDone, rowsTotal) -> System.out.println("imported " + rowsDone + " rows"));

try (Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost/mydb", "user", "pw")) {
    JdbcImporter.importQuery(conn, "SELECT * FROM trades", Path.of("trades.vortex"), opts);
}
```

The schema is derived entirely from `ResultSetMetaData` — no separate schema step needed. Columns
mapping to `vortex.date`/`vortex.time`/`vortex.timestamp`/`vortex.uuid` round-trip through the
matching JDBC getter; other SQL types map to the closest `DType.Primitive`/`Utf8`.

---

## Query a Vortex file with SQL (Calcite)

**API:**

```java
import io.github.dfa1.vortex.calcite.VortexCalcite;

import java.sql.ResultSet;
import java.sql.Statement;

try (Connection conn = VortexCalcite.connect("vtx", Map.of("ohlc", Path.of("ohlc.vortex")));
     Statement st = conn.createStatement();
     ResultSet rs = st.executeQuery("select symbol, avg(price) from vtx.ohlc group by symbol")) {
    while (rs.next()) {
        System.out.println(rs.getString(1) + ": " + rs.getDouble(2));
    }
}
```

Register several files under one schema — each map entry becomes `vtx.<tableName>`:

```java
Map<String, Path> tables = Map.of(
    "ohlc", Path.of("ohlc.vortex"),
    "trades", Path.of("trades.vortex")
);
try (Connection conn = VortexCalcite.connect("vtx", tables)) {
    // select * from vtx.ohlc join vtx.trades on ...
}
```

Whole-table `min`/`max`/`sum` aggregates are answered straight from zone-map statistics — no data
segment is decoded. Column names that collide with SQL reserved words (`close`, `open`, `value`,
`year`, …) work unquoted; the handful that open a typed literal (`date`, `time`, `timestamp`,
`interval`) still need back-ticks: `` select `date` from vtx.ohlc ``. See
[reference.md#calcite-sql-adapter](reference.md#calcite-sql-adapter) for the full lexical/parser
policy.

---

## Export to CSV

**CLI:**

```bash
# all columns
java -jar cli/target/vortex-cli-*-all.jar export data.vortex > out.csv

# specific columns
java -jar cli/target/vortex-cli-*-all.jar select data.vortex col1 col2 > out.csv

# filtered rows
java -jar cli/target/vortex-cli-*-all.jar filter data.vortex "price >= 100" > out.csv
```

---

## Write and read a Map column

`DType.Map` has no dedicated write-side value type. Physically, a map column's `vortex.map`
node has exactly one child — `entries`, a `ListView<Struct{key, value}>` — so you hand
`writeChunk` the same shape you'd hand a plain `ListView<Struct>` column: a `ListViewData`
whose `elements` is a `StructData` of a keys array and a values array. See
[explanation.md#map-column-layout](explanation.md#map-column-layout) for why the wire format
looks like this and how the two independent nullability slots (map row vs. entry value) work.

**Write:**

```java
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.encode.ListViewData;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.StructData;

// map<utf8, i64?> — non-nullable string keys, nullable long values
DType.Map mapType = new DType.Map(DType.UTF8, DType.I64.asNullable(), false, false);
DType.Struct schema = new DType.Struct(List.of(ColumnName.of("attrs")), List.of(mapType), false);

// 3 rows: {a:1, b:2}, {} (empty map), {c:null}
String[] keys = {"a", "b", "c"};
long[] values = {1L, 2L, 0L};                     // placeholder at the null entry
boolean[] valueValidity = {true, true, false};    // per-entry value validity
StructData entryStructs = new StructData(List.of(keys, new NullableData(values, valueValidity)));

int[] offsets = {0, 2, 2};   // row i's entries start at entryStructs[offsets[i]]
int[] sizes = {2, 0, 1};     // row i has sizes[i] entries
ListViewData column = new ListViewData(entryStructs, offsets, sizes, 3);

try (var ch = FileChannel.open(Path.of("attrs.vortex"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
     var writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
    writer.writeChunk(Map.of(ColumnName.of("attrs"), column));
}
```

A *nullable map row* (as opposed to a nullable value inside a present map) wraps the whole
`ListViewData` in `NullableData` instead — `mapType.asNullable()` in the schema, and
`new NullableData(column, new boolean[]{true, false, true})` in place of `column` above.

**Read:**

```java
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.ListViewArray;
import io.github.dfa1.vortex.reader.array.MapArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

try (var reader = VortexReader.open(Path.of("attrs.vortex"));
     var iter = reader.scan(ScanOptions.all())) {
    while (iter.hasNext()) {
        try (var chunk = iter.next()) {
            MapArray map = chunk.column("attrs");

            // If the map itself is nullable, entries() is a MaskedArray; unwrap it first.
            var entries = map.entries() instanceof MaskedArray masked
                    ? (ListViewArray) masked.inner() : (ListViewArray) map.entries();
            StructArray entryStructs = (StructArray) entries.elements();
            VarBinArray keys = (VarBinArray) entryStructs.field("key");
            var values = entryStructs.field("value"); // MaskedArray, since the value type is nullable here
            // A file written by vortex-java's own writer always emits I32 offsets/sizes; a file
            // from another producer (e.g. the Rust reference) may pick a narrower or wider integer
            // width, so switch on the concrete Array subtype there instead of casting to IntArray.
            IntArray offsets = (IntArray) entries.offsets();
            IntArray sizes = (IntArray) entries.sizes();

            for (long row = 0; row < map.length(); row++) {
                long start = offsets.getInt(row);
                long end = start + sizes.getInt(row);
                for (long i = start; i < end; i++) {
                    // keys.getBytes(i) / values at index i are this row's i-th {key, value} pair
                }
            }
        }
    }
}
```

`ScanOptions.all()`/CLI `inspect` show `vortex.map` in a file's layout tree as a `vortex.listview`
child under the `vortex.map` node — see `docs/reference.md#core-types` for `DType.Map`'s full
field list (`keyType`, `valueType`, `keysSorted`, `nullable`) and `entriesDtype()`.

---

## Register a custom encoding (write side)

`EncodingId` is a sealed `WellKnown`/`Custom` type — `EncodingId.Custom` lets a third party mint
its own wire id (e.g. `"acme.xor64"`) without touching vortex-java itself. A custom
`EncodingEncoder` writes it; the matching `EncodingDecoder`, registered separately in the reader
module, reads it back — writer and reader never share code, only the wire id.

**Write side** (encoder + registration + write, in one flow):

```java
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.encode.EncodeContext;
import io.github.dfa1.vortex.writer.encode.EncodeResult;
import io.github.dfa1.vortex.writer.encode.EncodingEncoder;
import io.github.dfa1.vortex.writer.WriteRegistry;

import java.lang.foreign.MemorySegment;

final class XorI64EncodingEncoder implements EncodingEncoder {
    private static final EncodingId ID = new EncodingId.Custom("acme.xor64");
    private static final long KEY = 0xA5A5_A5A5_A5A5_A5A5L;

    @Override
    public EncodingId encodingId() {
        return ID;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive p && p.ptype() == PType.I64;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        long[] values = (long[]) data;
        MemorySegment seg = ctx.arena().allocate((long) values.length * 8, 8);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(VortexFormat.LE_LONG, i, values[i] ^ KEY);
        }
        return EncodeResult.simple(ID, seg, null, null);
    }
}

DType.Struct schema = new DType.Struct(List.of(ColumnName.of("id")), List.of(DType.I64), false);

// a registry containing only this encoder — accepts(DType) alone wouldn't win a default
// cascade competition against the built-in vortex.primitive for every I64 column
WriteRegistry registry = WriteRegistry.builder().register(new XorI64EncodingEncoder()).build();

try (var ch = FileChannel.open(Path.of("data.vortex"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
     var writer = VortexWriter.create(ch, schema, WriteOptions.defaults(), registry)) {
    writer.writeChunk(chunk -> chunk.put(ColumnName.of("id"), new long[]{1, 2, 3}));
}
```

To mix a custom encoder into the normal cascade competition instead of a single-encoder registry,
start from `WriteRegistry.builder().registerDefaults()` and override `EncodingEncoder#expectedRatio`
to steer selection, rather than relying on `accepts(DType)` alone.

**Read side** (matching decoder, registered on `ReadRegistry` — see
[reference.md#encoding-registry](reference.md#encoding-registry)):

```java
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.reader.decode.EncodingDecoder;

import java.lang.foreign.MemorySegment;

final class XorI64EncodingDecoder implements EncodingDecoder {
    private static final EncodingId ID = new EncodingId.Custom("acme.xor64");
    private static final long KEY = 0xA5A5_A5A5_A5A5_A5A5L;

    @Override
    public EncodingId encodingId() {
        return ID;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment buf = ctx.buffer(0);
        long n = ctx.rowCount();
        MemorySegment out = ctx.arena().allocate(n * 8, 8);
        for (long i = 0; i < n; i++) {
            out.setAtIndex(VortexFormat.LE_LONG, i, buf.getAtIndex(VortexFormat.LE_LONG, i) ^ KEY);
        }
        return new MaterializedLongArray(ctx.dtype(), n, out);
    }
}

ReadRegistry readRegistry = ReadRegistry.builder()
    .registerDefaults()
    .register(new XorI64EncodingDecoder())
    .build();

try (VortexReader vf = VortexReader.open(Path.of("data.vortex"), readRegistry)) {
    // "id" decodes through XorI64EncodingDecoder
}
```

`ExtensionId` (the `vortex.date`/`vortex.time`/`vortex.timestamp`/`vortex.uuid` family) is a
**closed enum**, unlike `EncodingId` — a custom `ExtensionEncoder`/`ExtensionDecoder` pair can only
re-implement one of those four spec-defined ids, not introduce a wire-new extension type from
outside the codebase.

---

## Read files with unknown encodings

By default, a file containing an unrecognized encoding ID throws `VortexException`.
Use `allowUnknown()` to read the file anyway — columns with unknown encodings are
returned as `UnknownArray` (opaque, not decodable, but the rest of the file is readable):

```java
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.UnknownArray;

ReadRegistry registry = ReadRegistry.builder()
        .registerDefaults()
        .allowUnknown()
        .build();

try (VortexReader vf = VortexReader.open(Path.of("future.vortex"), registry);
     var iter = vf.scan(ScanOptions.all())) {
    while (iter.hasNext()) {
        try (var chunk = iter.next()) {
            chunk.columns().forEach((name, column) -> {
                if (column.array() instanceof UnknownArray u) {
                    System.out.println(name + ": unknown encoding " + u.encodingId());
                }
            });
        }
    }
}
```
