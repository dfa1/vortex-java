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
